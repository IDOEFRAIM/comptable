package com.example.comptable.collecte;

import com.example.comptable.agent.finding.domain.Finding;
import com.example.comptable.agent.finding.domain.StatutFinding;
import com.example.comptable.agent.finding.domain.TypeResolution;
import com.example.comptable.collecte.delivery.DeliveryResult;
import com.example.comptable.collecte.service.CollecteService;
import com.example.comptable.collecte.service.DemandeMatcher;
import com.example.comptable.collecte.service.OutboundMessageWorker;
import com.example.comptable.collecte.service.RelanceCollecteService;
import com.example.comptable.controle.ControlCodes;
import com.example.comptable.demande.domain.DemandePiece;
import com.example.comptable.demande.domain.StatutDemandePiece;
import com.example.comptable.demande.repository.DemandePieceRepository;
import com.example.comptable.document.domain.CanalSource;
import com.example.comptable.document.domain.Document;
import com.example.comptable.document.domain.StatutDemandeDocument;
import com.example.comptable.document.domain.TypeDocument;
import com.example.comptable.document.repository.DocumentRepository;
import com.example.comptable.document.service.DocumentTraitementService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.server.ResponseStatusException;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Parcours metier complet sans geste du comptable : piece manquante -> demande -> client contacte -> piece recue -> rapprochement
 * -> demande resolue -> constat resolu. Plus les cas PROBABLE (confirmation humaine) et AMBIGUOUS (jamais « la premiere »).
 */
class CollecteMatchingTest extends CollecteTestBase {

    @Autowired CollecteService collecteService;
    @Autowired OutboundMessageWorker worker;
    @Autowired RelanceCollecteService relanceService;
    @Autowired DemandeMatcher matcher;
    @Autowired DemandePieceRepository demandeRepository;
    @Autowired DocumentRepository documentRepository;
    @Autowired DocumentTraitementService traitementService;

    @BeforeEach
    void viderLaBoiteDEnvoi() {
        jdbc.update("update outbound_message set status = 'CANCELLED' where status in ('PENDING', 'SENDING')");
    }

    private record Demande(UUID findingId, DemandePiece demande) {
    }

    private Demande demander(Dossier d, int montant) {
        acheterSansPiece(d, montant);
        Finding f = piecesManquantes(d).stream().filter(x -> x.getSourceId() != null)
                .filter(x -> !demandeRepository.findOuverteParEcriture(d.id(), UUID.fromString(x.getSourceId()),
                        List.of(StatutDemandePiece.A_ENVOYER, StatutDemandePiece.EN_ATTENTE, StatutDemandePiece.RECU, StatutDemandePiece.ECHEC_ENVOI)).isPresent())
                .findFirst().orElseThrow();
        UUID demandeId = collecteService.demander(d.id(), List.of(f.getId()), d.expert()).demandeIds().get(0);
        return new Demande(f.getId(), demandeRepository.findById(demandeId).orElseThrow());
    }

    private Document piece(Dossier d, DemandePiece demande, UUID tiersId, LocalDate date) {
        Document doc = new Document();
        doc.setTenant(entrepriseRepository.findById(d.id()).orElseThrow().getTenant());
        doc.setEntreprise(entrepriseRepository.findById(d.id()).orElseThrow());
        doc.setTypeDocument(TypeDocument.FACTURE_ACHAT);
        doc.setCanalSource(CanalSource.WEB);
        doc.setCheminFichier("test/" + UUID.randomUUID());
        doc.setNomFichierOriginal("facture.png");
        doc.setMontantTtc(demande.getMontant());
        doc.setTiersId(tiersId);
        doc.setDatePiece(date);
        return documentRepository.save(doc);
    }

    private Finding finding(UUID id) {
        return findingRepository.findById(id).orElseThrow();
    }

    @Test
    void parcoursComplet_exact_demandeEtConstatResolusSansGesteDuComptable() {
        Dossier d = nouveauDossier(true);
        when(fournisseur.send(any())).thenReturn(DeliveryResult.envoye("wamid"));
        Demande creee = demander(d, 7300);
        worker.traiterEnAttente();
        assertThat(demandeRepository.findById(creee.demande().getId()).orElseThrow().getStatut()).isEqualTo(StatutDemandePiece.EN_ATTENTE);

        // Le client depose la piece : traitee par le pipeline (ici : donnees deja extraites), puis rapprochee seule.
        Document piece = piece(d, creee.demande(), d.fournisseur().id(), creee.demande().getDateOperation());
        assertThat(matcher.appliquer(piece, null)).isEqualTo(DemandeMatcher.Decision.EXACT);

        DemandePiece apres = demandeRepository.findById(creee.demande().getId()).orElseThrow();
        assertThat(apres.getStatut()).isEqualTo(StatutDemandePiece.RAPPROCHEE);
        assertThat(apres.getResoluPar()).isEqualTo("SYSTEME");
        assertThat(apres.getDocumentId()).isEqualTo(piece.getId());
        assertThat(finding(creee.findingId()).getStatus()).isEqualTo(StatutFinding.RESOLVED);
        assertThat(finding(creee.findingId()).getResolutionType()).isEqualTo(TypeResolution.SYSTEM);
        assertThat(documentRepository.findById(piece.getId()).orElseThrow().getStatutDemande()).isEqualTo(StatutDemandeDocument.LIEE);
        assertThat(evenementsPour("DOCUMENT_REQUEST_CREATED", apres.getId())).isEqualTo(1);
        assertThat(evenementsPour("DOCUMENT_REQUEST_SENT", apres.getId())).isEqualTo(1);
        assertThat(evenementsPour("DOCUMENT_REQUEST_RECEIVED", apres.getId())).isGreaterThanOrEqualTo(1);
        assertThat(evenementsPour("DOCUMENT_REQUEST_RESOLVED", apres.getId())).isGreaterThanOrEqualTo(1);

        // Le planificateur repasse : rien de plus n'est envoye au client.
        jdbc.update("update demande_piece set prochaine_relance_at = now() - interval '1 day' where id = ?", apres.getId());
        relanceService.evaluer(OffsetDateTime.now(), d.id());
        worker.traiterEnAttente();
        var cap = org.mockito.ArgumentCaptor.forClass(com.example.comptable.collecte.domain.OutboundMessage.class);
        verify(fournisseur, org.mockito.Mockito.atLeast(0)).send(cap.capture());
        // Isole de toute autre entreprise : seul le message initial de CE dossier est parti.
        assertThat(cap.getAllValues()).filteredOn(m -> d.id().equals(m.getEntrepriseId())).hasSize(1);
        assertThat(piecesManquantes(d)).extracting(Finding::getId).doesNotContain(creee.findingId());
    }

    @Test
    void probable_proposeLaPieceEtAttendLaConfirmationHumaine() {
        Dossier d = nouveauDossier(true);
        Demande creee = demander(d, 5100);
        // Meme montant, fournisseur inconnu de la piece, date proche : probable, jamais automatique.
        Document piece = piece(d, creee.demande(), null, creee.demande().getDateOperation().plusDays(2));

        assertThat(matcher.appliquer(piece, null)).isEqualTo(DemandeMatcher.Decision.PROBABLE);

        DemandePiece proposee = demandeRepository.findById(creee.demande().getId()).orElseThrow();
        assertThat(proposee.getStatut()).isEqualTo(StatutDemandePiece.RECU);
        assertThat(proposee.getProchaineRelanceAt()).isNull();
        assertThat(documentRepository.findById(piece.getId()).orElseThrow().getStatutDemande()).isEqualTo(StatutDemandeDocument.A_CONFIRMER);
        assertThat(finding(creee.findingId()).getStatus()).isNotEqualTo(StatutFinding.RESOLVED);

        traitementService.associerDemande(d.id(), piece.getId(), proposee.getId(), d.expert());

        assertThat(demandeRepository.findById(proposee.getId()).orElseThrow().getStatut()).isEqualTo(StatutDemandePiece.RAPPROCHEE);
        assertThat(finding(creee.findingId()).getStatus()).isEqualTo(StatutFinding.RESOLVED);
        assertThat(finding(creee.findingId()).getResolutionType()).isEqualTo(TypeResolution.MANUAL);
    }

    @Test
    void ambigu_deuxDemandesIdentiques_aucuneN_estChoisie_etUnConstatEstOuvert() {
        Dossier d = nouveauDossier(true);
        Demande premiere = demander(d, 3300);
        Demande seconde = demander(d, 3300);
        Document piece = piece(d, premiere.demande(), d.fournisseur().id(), premiere.demande().getDateOperation());

        assertThat(matcher.appliquer(piece, null)).isEqualTo(DemandeMatcher.Decision.AMBIGUOUS);

        assertThat(documentRepository.findById(piece.getId()).orElseThrow().getStatutDemande()).isEqualTo(StatutDemandeDocument.AMBIGU);
        assertThat(demandeRepository.findById(premiere.demande().getId()).orElseThrow().getStatut().estOuverte()).isTrue();
        assertThat(demandeRepository.findById(seconde.demande().getId()).orElseThrow().getStatut().estOuverte()).isTrue();
        assertThat(finding(premiere.findingId()).getStatus()).isNotEqualTo(StatutFinding.RESOLVED);
        assertThat(finding(seconde.findingId()).getStatus()).isNotEqualTo(StatutFinding.RESOLVED);

        controlRunService.executer(entrepriseRepository.findById(d.id()).orElseThrow());
        assertThat(findingRepository.findActifsParCodes(d.id(), List.of(ControlCodes.DOCUMENT_RECEIVED_AMBIGUOUS))).isNotEmpty();

        // Les deux demandes sont proposees comme candidates avec leur niveau, sans identifiant a saisir.
        var candidats = traitementService.candidatsDemandes(d.id(), piece.getId(), d.expert());
        assertThat(candidats).hasSize(2).allSatisfy(c -> {
            assertThat(c.niveau()).isEqualTo("AMBIGUOUS");
            assertThat(c.montant()).isNotNull();
            assertThat(c.date()).isNotNull();
        });

        // Le comptable tranche : la demande choisie est resolue, l'autre reste ouverte.
        traitementService.associerDemande(d.id(), piece.getId(), seconde.demande().getId(), d.expert());
        assertThat(demandeRepository.findById(seconde.demande().getId()).orElseThrow().getStatut()).isEqualTo(StatutDemandePiece.RAPPROCHEE);
        assertThat(demandeRepository.findById(premiere.demande().getId()).orElseThrow().getStatut().estOuverte()).isTrue();
    }

    @Test
    void uneDemandeDUneAutreEntrepriseNePeutPasEtreAssociee() {
        Dossier a = nouveauDossier(true);
        Dossier b = nouveauDossier(true);
        Demande demandeA = demander(a, 2200);
        Demande demandeB = demander(b, 2200);
        Document pieceA = piece(a, demandeA.demande(), a.fournisseur().id(), demandeA.demande().getDateOperation());

        assertThatThrownBy(() -> traitementService.associerDemande(a.id(), pieceA.getId(), demandeB.demande().getId(), a.expert()))
                .isInstanceOf(ResponseStatusException.class);
        assertThat(demandeRepository.findById(demandeB.demande().getId()).orElseThrow().getStatut().estOuverte()).isTrue();
    }

    @Test
    void associerUneDemandeDejaClose_estRefuse() {
        Dossier d = nouveauDossier(true);
        Demande creee = demander(d, 1900);
        collecteService.annuler(d.id(), creee.demande().getId(), d.expert());
        Document piece = piece(d, creee.demande(), d.fournisseur().id(), creee.demande().getDateOperation());

        assertThatThrownBy(() -> traitementService.associerDemande(d.id(), piece.getId(), creee.demande().getId(), d.expert()))
                .isInstanceOf(ResponseStatusException.class);
    }
}
