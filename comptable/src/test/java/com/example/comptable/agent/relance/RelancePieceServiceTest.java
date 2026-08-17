package com.example.comptable.agent.relance;

import com.example.comptable.agent.relance.domain.RelancePieceEnvoyee;
import com.example.comptable.agent.relance.domain.StatutRelance;
import com.example.comptable.agent.relance.repository.RelancePieceEnvoyeeRepository;
import com.example.comptable.demande.domain.DemandePiece;
import com.example.comptable.demande.domain.StatutDemandePiece;
import com.example.comptable.demande.repository.DemandePieceRepository;
import com.example.comptable.entreprise.domain.Entreprise;
import com.example.comptable.entreprise.domain.UtilisateurEntreprise;
import com.example.comptable.entreprise.repository.UtilisateurEntrepriseRepository;
import com.example.comptable.entreprise.service.EntrepriseAccessService;
import com.example.comptable.identite.domain.Utilisateur;
import com.example.comptable.identite.repository.UtilisateurRepository;
import com.example.comptable.notification.domain.TypeNotification;
import com.example.comptable.notification.service.NotificationService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class RelancePieceServiceTest {

    @Mock
    private DemandePieceRepository demandePieceRepository;
    @Mock
    private UtilisateurEntrepriseRepository utilisateurEntrepriseRepository;
    @Mock
    private UtilisateurRepository utilisateurRepository;
    @Mock
    private RelancePieceEnvoyeeRepository relancePieceEnvoyeeRepository;
    @Mock
    private WhatsAppCloudApiClient whatsAppCloudApiClient;
    @Mock
    private NotificationService notificationService;
    @Mock
    private EntrepriseAccessService entrepriseAccessService;

    private RelancePieceService relancePieceService;

    private UUID entrepriseId;
    private UUID clientId;
    private Entreprise entreprise;
    private Utilisateur demandeur;
    private Utilisateur client;

    @BeforeEach
    void setUp() {
        relancePieceService = new RelancePieceService(demandePieceRepository, utilisateurEntrepriseRepository,
                utilisateurRepository, relancePieceEnvoyeeRepository, whatsAppCloudApiClient, notificationService,
                entrepriseAccessService, "relance_pieces_comptables", "fr");

        entrepriseId = UUID.randomUUID();
        clientId = UUID.randomUUID();

        entreprise = new Entreprise();
        entreprise.setId(entrepriseId);
        entreprise.setRaisonSociale("SARL Bâtir Plus");

        demandeur = new Utilisateur();
        demandeur.setId(UUID.randomUUID());
        demandeur.setEmail("compta@cabinet.ci");
        demandeur.setMotDePasse("hash");
        demandeur.setNom("Kouassi");
        demandeur.setPrenom("Aya");

        client = new Utilisateur();
        client.setId(clientId);
        client.setEmail("client@batir-plus.ci");
        client.setMotDePasse("hash");
        client.setNom("Traore");
        client.setPrenom("Rokia");
        client.setTelephone("+225 01 02 03 04 05");
    }

    private DemandePiece demandeEnRetard() {
        DemandePiece demande = new DemandePiece();
        demande.setId(UUID.randomUUID());
        demande.setEntreprise(entreprise);
        demande.setLibelle("Facture d'électricité de juin");
        demande.setDateLimite(LocalDate.now().minusDays(3));
        demande.setStatut(StatutDemandePiece.EN_ATTENTE);
        demande.setDemandeePar(demandeur);
        return demande;
    }

    private UtilisateurEntreprise lienClient() {
        return new UtilisateurEntreprise(clientId, entrepriseId);
    }

    @Test
    void relance_un_client_en_retard_et_journalise_le_succes() {
        DemandePiece demande = demandeEnRetard();
        when(demandePieceRepository.findEnRetard(StatutDemandePiece.EN_ATTENTE, LocalDate.now()))
                .thenReturn(List.of(demande));
        when(utilisateurEntrepriseRepository.findByIdEntrepriseIdIn(any())).thenReturn(List.of(lienClient()));
        when(utilisateurRepository.findAllById(any())).thenReturn(List.of(client));
        when(relancePieceEnvoyeeRepository.existsByDemandePieceIdAndEnvoyeAtAfter(eq(demande.getId()), any()))
                .thenReturn(false);
        when(whatsAppCloudApiClient.envoyerTemplate(eq("2250102030405"), eq("relance_pieces_comptables"), eq("fr"), anyList()))
                .thenReturn("wamid.TEST123");

        RelanceResumeDto resultat = relancePieceService.executerRelancesDuJour();

        assertThat(resultat.envoyees()).isEqualTo(1);
        assertThat(resultat.echecs()).isEqualTo(0);
        assertThat(resultat.ignorees()).isEqualTo(0);

        ArgumentCaptor<RelancePieceEnvoyee> captor = ArgumentCaptor.forClass(RelancePieceEnvoyee.class);
        verify(relancePieceEnvoyeeRepository).save(captor.capture());
        assertThat(captor.getValue().getStatut()).isEqualTo(StatutRelance.ENVOYEE);
        assertThat(captor.getValue().getWhatsappMessageId()).isEqualTo("wamid.TEST123");
        assertThat(captor.getValue().getTelephone()).isEqualTo("2250102030405");

        verify(notificationService).notifier(eq(demandeur), eq(TypeNotification.RELANCE_PIECE), anyString());
    }

    @Test
    void journalise_lechec_sans_lever_dexception_si_lenvoi_whatsapp_echoue() {
        DemandePiece demande = demandeEnRetard();
        when(demandePieceRepository.findEnRetard(StatutDemandePiece.EN_ATTENTE, LocalDate.now()))
                .thenReturn(List.of(demande));
        when(utilisateurEntrepriseRepository.findByIdEntrepriseIdIn(any())).thenReturn(List.of(lienClient()));
        when(utilisateurRepository.findAllById(any())).thenReturn(List.of(client));
        when(relancePieceEnvoyeeRepository.existsByDemandePieceIdAndEnvoyeAtAfter(any(), any())).thenReturn(false);
        when(whatsAppCloudApiClient.envoyerTemplate(anyString(), anyString(), anyString(), anyList()))
                .thenThrow(new WhatsAppCloudApiClient.WhatsAppEnvoiIndisponibleException("panne Graph API"));

        RelanceResumeDto resultat = relancePieceService.executerRelancesDuJour();

        assertThat(resultat.envoyees()).isEqualTo(0);
        assertThat(resultat.echecs()).isEqualTo(1);

        ArgumentCaptor<RelancePieceEnvoyee> captor = ArgumentCaptor.forClass(RelancePieceEnvoyee.class);
        verify(relancePieceEnvoyeeRepository).save(captor.capture());
        assertThat(captor.getValue().getStatut()).isEqualTo(StatutRelance.ECHEC);
        assertThat(captor.getValue().getMotifEchec()).contains("panne Graph API");
    }

    @Test
    void ignore_une_demande_sans_client_rattache_a_lentreprise() {
        DemandePiece demande = demandeEnRetard();
        when(demandePieceRepository.findEnRetard(StatutDemandePiece.EN_ATTENTE, LocalDate.now()))
                .thenReturn(List.of(demande));
        when(utilisateurEntrepriseRepository.findByIdEntrepriseIdIn(any())).thenReturn(List.of());
        when(utilisateurRepository.findAllById(any())).thenReturn(List.of());

        RelanceResumeDto resultat = relancePieceService.executerRelancesDuJour();

        assertThat(resultat.ignorees()).isEqualTo(1);
        verify(whatsAppCloudApiClient, never()).envoyerTemplate(any(), any(), any(), any());
    }

    @Test
    void ignore_un_client_sans_numero_de_telephone() {
        client.setTelephone(null);
        DemandePiece demande = demandeEnRetard();
        when(demandePieceRepository.findEnRetard(StatutDemandePiece.EN_ATTENTE, LocalDate.now()))
                .thenReturn(List.of(demande));
        when(utilisateurEntrepriseRepository.findByIdEntrepriseIdIn(any())).thenReturn(List.of(lienClient()));
        when(utilisateurRepository.findAllById(any())).thenReturn(List.of(client));
        when(relancePieceEnvoyeeRepository.existsByDemandePieceIdAndEnvoyeAtAfter(any(), any())).thenReturn(false);

        RelanceResumeDto resultat = relancePieceService.executerRelancesDuJour();

        assertThat(resultat.ignorees()).isEqualTo(1);
        verify(whatsAppCloudApiClient, never()).envoyerTemplate(any(), any(), any(), any());
    }

    @Test
    void nenvoie_pas_deux_relances_pour_la_meme_demande_dans_la_fenetre_anti_spam() {
        DemandePiece demande = demandeEnRetard();
        when(demandePieceRepository.findEnRetard(StatutDemandePiece.EN_ATTENTE, LocalDate.now()))
                .thenReturn(List.of(demande));
        when(utilisateurEntrepriseRepository.findByIdEntrepriseIdIn(any())).thenReturn(List.of(lienClient()));
        when(utilisateurRepository.findAllById(any())).thenReturn(List.of(client));
        when(relancePieceEnvoyeeRepository.existsByDemandePieceIdAndEnvoyeAtAfter(eq(demande.getId()), any()))
                .thenReturn(true);

        RelanceResumeDto resultat = relancePieceService.executerRelancesDuJour();

        assertThat(resultat.ignorees()).isEqualTo(1);
        assertThat(resultat.envoyees()).isEqualTo(0);
        verify(whatsAppCloudApiClient, never()).envoyerTemplate(any(), any(), any(), any());
    }

    @Test
    void ne_fait_rien_si_aucune_demande_nest_en_retard() {
        when(demandePieceRepository.findEnRetard(StatutDemandePiece.EN_ATTENTE, LocalDate.now()))
                .thenReturn(List.of());

        RelanceResumeDto resultat = relancePieceService.executerRelancesDuJour();

        assertThat(resultat.envoyees()).isEqualTo(0);
        assertThat(resultat.echecs()).isEqualTo(0);
        assertThat(resultat.ignorees()).isEqualTo(0);
        verify(utilisateurEntrepriseRepository, never()).findByIdEntrepriseIdIn(any());
    }
}
