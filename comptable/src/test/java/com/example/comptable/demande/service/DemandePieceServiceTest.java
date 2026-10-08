package com.example.comptable.demande.service;

import com.example.comptable.audit.AuditTrailService;
import com.example.comptable.demande.domain.DemandePiece;
import com.example.comptable.demande.domain.StatutDemandePiece;
import com.example.comptable.demande.repository.DemandePieceRepository;
import com.example.comptable.demande.web.dto.CreerDemandePieceRequest;
import com.example.comptable.demande.web.dto.DemandePieceDto;
import com.example.comptable.document.domain.TypeDocument;
import com.example.comptable.document.service.DocumentService;
import com.example.comptable.document.web.dto.DocumentDto;
import com.example.comptable.entreprise.domain.Entreprise;
import com.example.comptable.entreprise.service.EntrepriseAccessService;
import com.example.comptable.identite.domain.Role;
import com.example.comptable.identite.domain.Utilisateur;
import com.example.comptable.identite.repository.UtilisateurRepository;
import com.example.comptable.notification.domain.TypeNotification;
import com.example.comptable.notification.service.NotificationService;
import com.example.comptable.security.UtilisateurPrincipal;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.web.server.ResponseStatusException;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class DemandePieceServiceTest {

    @Mock
    private EntrepriseAccessService entrepriseAccessService;
    @Mock
    private DemandePieceRepository demandePieceRepository;
    @Mock
    private UtilisateurRepository utilisateurRepository;
    @Mock
    private DocumentService documentService;
    @Mock
    private NotificationService notificationService;
    @Mock
    private AuditTrailService auditTrailService;

    @InjectMocks
    private DemandePieceService demandePieceService;

    private UUID entrepriseId;
    private Entreprise entreprise;
    private UtilisateurPrincipal principal;
    private Utilisateur demandeur;

    @BeforeEach
    void setUp() {
        entrepriseId = UUID.randomUUID();
        entreprise = new Entreprise();
        entreprise.setId(entrepriseId);
        entreprise.setRaisonSociale("SARL Bâtir Plus");

        Role role = new Role();
        role.setCode("CLIENT");
        Utilisateur utilisateur = new Utilisateur();
        utilisateur.setId(UUID.randomUUID());
        utilisateur.setEmail("client@test.ci");
        utilisateur.setMotDePasse("hash");
        utilisateur.setNom("Kone");
        utilisateur.setPrenom("Awa");
        utilisateur.setRoles(Set.of(role));
        principal = new UtilisateurPrincipal(utilisateur);

        demandeur = new Utilisateur();
        demandeur.setId(UUID.randomUUID());
        demandeur.setEmail("collab@cabinet.ci");
        demandeur.setNom("Traore");
        demandeur.setPrenom("Ibrahim");

        when(entrepriseAccessService.verifierAcces(entrepriseId, principal)).thenReturn(entreprise);
    }

    private DemandePiece demandeEnAttente() {
        DemandePiece demande = new DemandePiece();
        demande.setId(UUID.randomUUID());
        demande.setEntreprise(entreprise);
        demande.setLibelle("Facture électricité juin");
        demande.setStatut(StatutDemandePiece.EN_ATTENTE);
        demande.setDemandeePar(demandeur);
        return demande;
    }

    @Test
    void creer_une_demande_en_attente() {
        when(utilisateurRepository.getReferenceById(principal.getId())).thenReturn(demandeur);
        when(demandePieceRepository.save(any())).thenAnswer(inv -> {
            DemandePiece d = inv.getArgument(0);
            if (d.getId() == null) {
                d.setId(UUID.randomUUID());
            }
            return d;
        });

        DemandePieceDto resultat = demandePieceService.creer(
                entrepriseId, new CreerDemandePieceRequest("Ticket essence 14/06", null, LocalDate.now().plusDays(5)),
                principal);

        assertThat(resultat.statut()).isEqualTo("EN_ATTENTE");
        assertThat(resultat.libelle()).isEqualTo("Ticket essence 14/06");
    }

    @Test
    void repondre_uploade_le_document_et_notifie_le_demandeur() {
        DemandePiece demande = demandeEnAttente();
        UUID documentId = UUID.randomUUID();

        when(demandePieceRepository.findById(demande.getId())).thenReturn(Optional.of(demande));
        when(documentService.uploader(eq(entrepriseId), eq(TypeDocument.FACTURE_ACHAT), any(), eq(principal)))
                .thenReturn(new DocumentDto(documentId, entrepriseId, "FACTURE_ACHAT", "WEB", "facture.pdf",
                        "application/pdf", 100L, "TRAITE", null, null, null, null, null, null, null, List.of(), null));
        when(demandePieceRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        MockMultipartFile fichier = new MockMultipartFile("fichier", "facture.pdf", "application/pdf", new byte[] { 1 });

        DemandePieceDto resultat = demandePieceService.repondre(
                entrepriseId, demande.getId(), TypeDocument.FACTURE_ACHAT, fichier, principal);

        assertThat(resultat.statut()).isEqualTo("RECU");
        assertThat(resultat.documentId()).isEqualTo(documentId);
        verify(notificationService).notifier(eq(demandeur), eq(TypeNotification.PIECE_RECUE), any());
    }

    @Test
    void repondre_refuse_si_la_demande_est_deja_traitee() {
        DemandePiece demande = demandeEnAttente();
        demande.setStatut(StatutDemandePiece.RECU);
        when(demandePieceRepository.findById(demande.getId())).thenReturn(Optional.of(demande));

        MockMultipartFile fichier = new MockMultipartFile("fichier", "facture.pdf", "application/pdf", new byte[] { 1 });

        assertThatThrownBy(() -> demandePieceService.repondre(
                entrepriseId, demande.getId(), TypeDocument.FACTURE_ACHAT, fichier, principal))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("deja ete traitee");

        verify(documentService, never()).uploader(any(), any(), any(), any());
    }
}
