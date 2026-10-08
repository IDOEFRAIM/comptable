package com.example.comptable.collecte;

import com.example.comptable.agent.finding.domain.Finding;
import com.example.comptable.collecte.delivery.DeliveryResult;
import com.example.comptable.collecte.domain.OutboundMessage;
import com.example.comptable.collecte.domain.StatutMessage;
import com.example.comptable.collecte.domain.TypeMessage;
import com.example.comptable.collecte.repository.OutboundMessageRepository;
import com.example.comptable.collecte.service.CollecteService;
import com.example.comptable.collecte.service.OutboundMessageWorker;
import com.example.comptable.collecte.service.RelanceCollecteService;
import com.example.comptable.demande.domain.DemandePiece;
import com.example.comptable.demande.domain.StatutDemandePiece;
import com.example.comptable.demande.repository.DemandePieceRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** Cycle de vie de la collecte, avec un FAUX fournisseur : demande, boite d'envoi, worker, retry technique, relances, volume. */
class CollecteWorkflowTest extends CollecteTestBase {

    @Autowired CollecteService collecteService;
    @Autowired OutboundMessageWorker worker;
    @Autowired RelanceCollecteService relanceService;
    @Autowired DemandePieceRepository demandeRepository;
    @Autowired OutboundMessageRepository messageRepository;

    @BeforeEach
    void viderLaBoiteDEnvoi() {
        jdbc.update("update outbound_message set status = 'CANCELLED' where status in ('PENDING', 'SENDING')");
    }

    private List<UUID> constats(Dossier d, int nombre) {
        for (int i = 0; i < nombre; i++) {
            acheterSansPiece(d, 1000 + i * 10);
        }
        List<UUID> ids = piecesManquantes(d).stream().map(Finding::getId).toList();
        assertThat(ids).hasSize(nombre);
        return ids;
    }

    private OutboundMessage message(UUID id) {
        return messageRepository.findById(id).orElseThrow();
    }

    private void avancerLaRelance(UUID demandeId) {
        jdbc.update("update demande_piece set prochaine_relance_at = now() - interval '1 minute' where id = ?", demandeId);
    }

    @Test
    void demanderCreeUneDemandeEtUnMessagePending_sansAppelerLeFournisseur() {
        Dossier d = nouveauDossier(true);
        var resultat = collecteService.demander(d.id(), constats(d, 1), d.expert());

        assertThat(resultat.demandeIds()).hasSize(1);
        assertThat(resultat.messageIds()).hasSize(1);
        assertThat(message(resultat.messageIds().get(0)).getStatus()).isEqualTo(StatutMessage.PENDING);
        assertThat(demandeRepository.findById(resultat.demandeIds().get(0)).orElseThrow().getStatut()).isEqualTo(StatutDemandePiece.A_ENVOYER);
        verify(fournisseur, never()).send(any());
        assertThat(evenementsPour("DOCUMENT_REQUEST_CREATED", resultat.demandeIds().get(0))).isEqualTo(1);
    }

    @Test
    void doubleClic_neCreeQuUneDemandeOuverte() {
        Dossier d = nouveauDossier(true);
        List<UUID> ids = constats(d, 1);
        var premier = collecteService.demander(d.id(), ids, d.expert());
        var second = collecteService.demander(d.id(), ids, d.expert());

        assertThat(premier.demandeIds()).hasSize(1);
        assertThat(second.demandeIds()).isEmpty();
        assertThat(second.messageIds()).isEmpty();
        assertThat(second.ignores()).isEqualTo(1);
    }

    @Test
    void succesDuFournisseur_passeLeMessageEnSentEtLaDemandeEnAttente() {
        Dossier d = nouveauDossier(true);
        when(fournisseur.send(any())).thenReturn(DeliveryResult.envoye("wamid-1"));
        var resultat = collecteService.demander(d.id(), constats(d, 1), d.expert());

        assertThat(worker.traiterEnAttente()).isEqualTo(1);

        OutboundMessage m = message(resultat.messageIds().get(0));
        assertThat(m.getStatus()).isEqualTo(StatutMessage.SENT);
        assertThat(m.getProviderMessageId()).isEqualTo("wamid-1");
        DemandePiece demande = demandeRepository.findById(resultat.demandeIds().get(0)).orElseThrow();
        assertThat(demande.getStatut()).isEqualTo(StatutDemandePiece.EN_ATTENTE);
        assertThat(demande.getSentAt()).isNotNull();
        assertThat(demande.getProchaineRelanceAt()).isAfter(OffsetDateTime.now().plusDays(2));
        assertThat(evenementsPour("DOCUMENT_REQUEST_SENT", demande.getId())).isEqualTo(1);
        // Rien de plus a envoyer : un second passage ne rappelle pas le fournisseur.
        assertThat(worker.traiterEnAttente()).isZero();
        verify(fournisseur, times(1)).send(any());
    }

    @Test
    void echecTechnique_reessayeLeMemeMessageSansToucherLaDemande_puisReussit() {
        Dossier d = nouveauDossier(true);
        when(fournisseur.send(any())).thenReturn(DeliveryResult.reessayable("timeout")).thenReturn(DeliveryResult.envoye("wamid-2"));
        var resultat = collecteService.demander(d.id(), constats(d, 1), d.expert());
        UUID messageId = resultat.messageIds().get(0);

        worker.traiterEnAttente();

        OutboundMessage apresEchec = message(messageId);
        assertThat(apresEchec.getStatus()).isEqualTo(StatutMessage.PENDING);
        assertThat(apresEchec.getAttemptCount()).isEqualTo(1);
        assertThat(apresEchec.getNextAttemptAt()).isAfter(OffsetDateTime.now());
        DemandePiece demande = demandeRepository.findById(resultat.demandeIds().get(0)).orElseThrow();
        assertThat(demande.getStatut()).isEqualTo(StatutDemandePiece.A_ENVOYER);
        assertThat(demande.getNbRelances()).isZero();
        // Le delai de retry n'est pas ecoule : rien ne repart.
        assertThat(worker.traiterEnAttente()).isZero();

        jdbc.update("update outbound_message set next_attempt_at = now() - interval '1 minute' where id = ?", messageId);
        worker.traiterEnAttente();

        assertThat(message(messageId).getStatus()).isEqualTo(StatutMessage.SENT);
        assertThat(message(messageId).getAttemptCount()).isEqualTo(2);
        assertThat(demandeRepository.findById(demande.getId()).orElseThrow().getStatut()).isEqualTo(StatutDemandePiece.EN_ATTENTE);
        verify(fournisseur, times(2)).send(any());
    }

    @Test
    void echecTechniquePlafonne_passeEnEchecEtLaDemandeEnEchecEnvoi() {
        Dossier d = nouveauDossier(true);
        when(fournisseur.send(any())).thenReturn(DeliveryResult.reessayable("503"));
        var resultat = collecteService.demander(d.id(), constats(d, 1), d.expert());
        UUID messageId = resultat.messageIds().get(0);
        int max = message(messageId).getMaxAttempts();

        for (int i = 0; i < max; i++) {
            jdbc.update("update outbound_message set next_attempt_at = now() - interval '1 minute' where id = ? and status = 'PENDING'", messageId);
            worker.traiterEnAttente();
        }

        assertThat(message(messageId).getStatus()).isEqualTo(StatutMessage.FAILED);
        assertThat(message(messageId).getAttemptCount()).isEqualTo(max);
        assertThat(demandeRepository.findById(resultat.demandeIds().get(0)).orElseThrow().getStatut()).isEqualTo(StatutDemandePiece.ECHEC_ENVOI);
        verify(fournisseur, times(max)).send(any());
    }

    @Test
    void echecDefinitif_neReessaiePas() {
        Dossier d = nouveauDossier(true);
        when(fournisseur.send(any())).thenReturn(DeliveryResult.definitif("numero invalide"));
        var resultat = collecteService.demander(d.id(), constats(d, 1), d.expert());

        worker.traiterEnAttente();
        worker.traiterEnAttente();

        assertThat(message(resultat.messageIds().get(0)).getStatus()).isEqualTo(StatutMessage.FAILED);
        assertThat(demandeRepository.findById(resultat.demandeIds().get(0)).orElseThrow().getStatut()).isEqualTo(StatutDemandePiece.ECHEC_ENVOI);
        verify(fournisseur, times(1)).send(any());
    }

    @Test
    void deuxWorkersSurUnMessage_envoientExactementUneFois() throws Exception {
        Dossier d = nouveauDossier(true);
        when(fournisseur.send(any())).thenAnswer(inv -> {
            Thread.sleep(300);
            return DeliveryResult.envoye("wamid-3");
        });
        collecteService.demander(d.id(), constats(d, 1), d.expert());

        ExecutorService pool = Executors.newFixedThreadPool(2);
        CountDownLatch depart = new CountDownLatch(1);
        List<Future<Integer>> futurs = new ArrayList<>();
        for (int i = 0; i < 2; i++) {
            Callable<Integer> tache = () -> {
                depart.await();
                return worker.traiterEnAttente();
            };
            futurs.add(pool.submit(tache));
        }
        depart.countDown();
        int total = 0;
        for (Future<Integer> f : futurs) {
            total += f.get();
        }
        pool.shutdown();

        assertThat(total).isEqualTo(1);
        verify(fournisseur, times(1)).send(any());
    }

    @Test
    void demandeAnnuleeAvantEnvoi_annuleLeMessageSansAppelerLeFournisseur() {
        Dossier d = nouveauDossier(true);
        var resultat = collecteService.demander(d.id(), constats(d, 1), d.expert());
        collecteService.annuler(d.id(), resultat.demandeIds().get(0), d.expert());

        worker.traiterEnAttente();

        assertThat(message(resultat.messageIds().get(0)).getStatus()).isEqualTo(StatutMessage.CANCELLED);
        verify(fournisseur, never()).send(any());
        assertThat(evenementsPour("DOCUMENT_REQUEST_CANCELLED", resultat.demandeIds().get(0))).isEqualTo(1);
    }

    @Test
    void clientSansContact_bloqueAvecUnMotifExplicite_sansAucunAppel() {
        Dossier d = nouveauDossier(false);
        var resultat = collecteService.demander(d.id(), constats(d, 1), d.expert());

        assertThat(resultat.messageIds()).isEmpty();
        assertThat(resultat.blocage()).isNotBlank();
        DemandePiece demande = demandeRepository.findById(resultat.demandeIds().get(0)).orElseThrow();
        assertThat(demande.getStatut()).isEqualTo(StatutDemandePiece.ECHEC_ENVOI);
        assertThat(demande.getMotifBlocage()).isNotBlank();
        worker.traiterEnAttente();
        verify(fournisseur, never()).send(any());
    }

    @Test
    void relances_j3PuisJ7_puisPlusRien_etLeRetryTechniqueNeCompteJamaisCommeRelance() {
        Dossier d = nouveauDossier(true);
        when(fournisseur.send(any())).thenReturn(DeliveryResult.envoye("wamid"));
        var resultat = collecteService.demander(d.id(), constats(d, 1), d.expert());
        UUID demandeId = resultat.demandeIds().get(0);
        worker.traiterEnAttente();

        // Avant l'echeance : rien.
        assertThat(relanceService.evaluer(OffsetDateTime.now(), d.id()).relancees()).isZero();

        for (int numero = 1; numero <= 2; numero++) {
            avancerLaRelance(demandeId);
            if (numero == 2) {
                // Plafond quotidien : deux messages deja partis aujourd'hui, la relance est reportee, pas envoyee.
                var plafonne = relanceService.evaluer(OffsetDateTime.now(), d.id());
                assertThat(plafonne.relancees()).isZero();
                assertThat(plafonne.plafonnees()).isGreaterThanOrEqualTo(1);
                // Le temps passe : les messages precedents datent de plus de 24 h.
                jdbc.update("update outbound_message set created_at = created_at - interval '4 days' where entreprise_id = ?", d.id());
                avancerLaRelance(demandeId);
            }
            assertThat(relanceService.evaluer(OffsetDateTime.now(), d.id()).relancees()).isEqualTo(1);
            worker.traiterEnAttente();
            DemandePiece demande = demandeRepository.findById(demandeId).orElseThrow();
            assertThat(demande.getNbRelances()).isEqualTo(numero);
            assertThat(demande.getStatut()).isEqualTo(StatutDemandePiece.EN_ATTENTE);
        }

        // Relances epuisees : plus de message automatique, l'humain reprend la main.
        DemandePiece epuisee = demandeRepository.findById(demandeId).orElseThrow();
        assertThat(epuisee.getProchaineRelanceAt()).isNull();
        avancerLaRelance(demandeId);
        worker.traiterEnAttente();
        long rappels = messageRepository.findParDemande(demandeId).stream().filter(m -> m.getKind() == TypeMessage.REMINDER).count();
        assertThat(rappels).isEqualTo(2);
        assertThat(evenementsPour("DOCUMENT_REQUEST_REMINDER_SENT", demandeId)).isEqualTo(2);
    }

    @Test
    void relance_neSeDeclenchePasPourUneDemandeAnnulee() {
        Dossier d = nouveauDossier(true);
        when(fournisseur.send(any())).thenReturn(DeliveryResult.envoye("wamid"));
        var resultat = collecteService.demander(d.id(), constats(d, 1), d.expert());
        UUID demandeId = resultat.demandeIds().get(0);
        worker.traiterEnAttente();
        collecteService.annuler(d.id(), demandeId, d.expert());
        avancerLaRelance(demandeId);

        assertThat(relanceService.evaluer(OffsetDateTime.now(), d.id()).relancees()).isZero();
        verify(fournisseur, times(1)).send(any());
    }

    @Test
    void dixPiecesDuMemeClient_donnentDixDemandesEtUnSeulMessage() {
        Dossier d = nouveauDossier(true);
        var resultat = collecteService.demander(d.id(), constats(d, 10), d.expert());

        assertThat(resultat.demandeIds()).hasSize(10).doesNotHaveDuplicates();
        assertThat(resultat.messageIds()).hasSize(1);
    }

    @Test
    void volume_centPiecesDixClients_centDemandesDixMessages_sansPerteNiDoublon() {
        List<Dossier> dossiers = new ArrayList<>();
        for (int i = 0; i < 10; i++) {
            dossiers.add(nouveauDossier(true));
        }
        when(fournisseur.send(any())).thenReturn(DeliveryResult.envoye("wamid-vol"));
        List<UUID> demandes = new ArrayList<>();
        List<UUID> messages = new ArrayList<>();
        for (Dossier d : dossiers) {
            var resultat = collecteService.demander(d.id(), constats(d, 10), d.expert());
            demandes.addAll(resultat.demandeIds());
            messages.addAll(resultat.messageIds());
        }

        assertThat(demandes).hasSize(100).doesNotHaveDuplicates();
        assertThat(messages).hasSize(10).doesNotHaveDuplicates();
        int traites = 0;
        int passe;
        do {
            passe = worker.traiterEnAttente();
            traites += passe;
        } while (passe > 0);

        assertThat(traites).isEqualTo(10);
        verify(fournisseur, times(10)).send(any());
        assertThat(messages).allSatisfy(id -> assertThat(message(id).getStatus()).isEqualTo(StatutMessage.SENT));
        assertThat(demandeRepository.findAllById(demandes)).allSatisfy(x -> assertThat(x.getStatut()).isEqualTo(StatutDemandePiece.EN_ATTENTE));
    }

    @Test
    void isolationMultiTenant_uneDemandeEtSonMessageSontInvisiblesPourUneAutreEntreprise() {
        Dossier a = nouveauDossier(true);
        Dossier b = nouveauDossier(true);
        var resultat = collecteService.demander(a.id(), constats(a, 1), a.expert());

        org.junit.jupiter.api.Assertions.assertThrows(org.springframework.web.server.ResponseStatusException.class,
                () -> collecteService.annuler(b.id(), resultat.demandeIds().get(0), b.expert()));
        assertThat(demandeRepository.findById(resultat.demandeIds().get(0)).orElseThrow().getStatut()).isEqualTo(StatutDemandePiece.A_ENVOYER);
    }
}
