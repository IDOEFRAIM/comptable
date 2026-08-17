package com.example.comptable.agent.pipeline.web;

import com.example.comptable.agent.pipeline.PieceJustificativeAgentService;
import com.example.comptable.agent.pipeline.web.dto.DocumentEnAttenteValidationDto;
import com.example.comptable.agent.pipeline.web.dto.ValiderDocumentRequest;
import com.example.comptable.document.domain.CanalSource;
import com.example.comptable.document.domain.Document;
import com.example.comptable.document.domain.StatutDocument;
import com.example.comptable.document.domain.TypeDocument;
import com.example.comptable.document.repository.DocumentRepository;
import com.example.comptable.document.web.dto.DocumentDto;
import com.example.comptable.entreprise.domain.Entreprise;
import com.example.comptable.entreprise.service.EntrepriseAccessService;
import com.example.comptable.identite.domain.Role;
import com.example.comptable.identite.domain.Utilisateur;
import com.example.comptable.security.UtilisateurPrincipal;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.web.server.ResponseStatusException;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class DocumentAgentServiceTest {

    @Mock
    private EntrepriseAccessService entrepriseAccessService;
    @Mock
    private DocumentRepository documentRepository;
    @Mock
    private PieceJustificativeAgentService pieceJustificativeAgentService;

    private DocumentAgentService documentAgentService;

    private UUID entrepriseId;
    private UUID documentId;
    private Entreprise entreprise;
    private UtilisateurPrincipal principal;

    @BeforeEach
    void setUp() {
        documentAgentService = new DocumentAgentService(entrepriseAccessService, documentRepository,
                pieceJustificativeAgentService);

        entrepriseId = UUID.randomUUID();
        documentId = UUID.randomUUID();
        entreprise = new Entreprise();
        entreprise.setId(entrepriseId);

        Role role = new Role();
        role.setCode("EXPERT_COMPTABLE");
        Utilisateur utilisateur = new Utilisateur();
        utilisateur.setId(UUID.randomUUID());
        utilisateur.setEmail("test@cabinet.ci");
        utilisateur.setMotDePasse("hash");
        utilisateur.setNom("Nom");
        utilisateur.setPrenom("Prenom");
        utilisateur.setRoles(Set.of(role));
        principal = new UtilisateurPrincipal(utilisateur);
    }

    private Document documentEnAttente() {
        Document document = new Document();
        document.setId(documentId);
        document.setEntreprise(entreprise);
        document.setTypeDocument(TypeDocument.RECU);
        document.setCanalSource(CanalSource.WHATSAPP);
        document.setStatut(StatutDocument.EN_ATTENTE_VALIDATION_HUMAINE);
        document.setMontantHt(new BigDecimal("10000"));
        document.setMontantTva(new BigDecimal("1800"));
        document.setMontantTtc(new BigDecimal("11800"));
        document.setDatePiece(LocalDate.of(2027, 1, 5));
        document.setDonneesOcr(Map.of("confiance", 0.6));
        return document;
    }

    @Test
    void liste_les_documents_en_attente_de_validation() {
        when(entrepriseAccessService.verifierAcces(entrepriseId, principal)).thenReturn(entreprise);
        when(documentRepository.findByEntrepriseIdAndStatutOrderByCreatedAtDesc(
                entrepriseId, StatutDocument.EN_ATTENTE_VALIDATION_HUMAINE))
                .thenReturn(List.of(documentEnAttente()));

        List<DocumentEnAttenteValidationDto> resultat = documentAgentService.listerEnAttenteValidation(entrepriseId, principal);

        assertThat(resultat).hasSize(1);
        assertThat(resultat.get(0).confiance()).isEqualTo(0.6);
        assertThat(resultat.get(0).urlFichier()).contains(documentId.toString());
    }

    @Test
    void expose_le_badge_de_fraude_quand_la_piece_est_en_attente_pour_ce_motif() {
        Document document = documentEnAttente();
        document.setRapportFraude(Map.of(
                "scoreRisque", "CRITIQUE",
                "alertes", List.of(Map.of("code", "DOUBLON_POTENTIEL", "motif", "Montant deja enregistre", "gravite", "CRITIQUE"))));
        when(entrepriseAccessService.verifierAcces(entrepriseId, principal)).thenReturn(entreprise);
        when(documentRepository.findByEntrepriseIdAndStatutOrderByCreatedAtDesc(
                entrepriseId, StatutDocument.EN_ATTENTE_VALIDATION_HUMAINE))
                .thenReturn(List.of(document));

        List<DocumentEnAttenteValidationDto> resultat = documentAgentService.listerEnAttenteValidation(entrepriseId, principal);

        assertThat(resultat.get(0).scoreRisqueFraude()).isEqualTo("CRITIQUE");
        assertThat(resultat.get(0).alertesFraude()).hasSize(1);
        assertThat(resultat.get(0).alertesFraude().get(0).code()).isEqualTo("DOUBLON_POTENTIEL");
    }

    @Test
    void applique_les_corrections_et_reprend_le_pipeline() {
        Document document = documentEnAttente();
        when(entrepriseAccessService.verifierAcces(entrepriseId, principal)).thenReturn(entreprise);
        when(documentRepository.findById(documentId)).thenReturn(Optional.of(document));
        when(documentRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        ValiderDocumentRequest request = new ValiderDocumentRequest(new BigDecimal("12000"), null, new BigDecimal("14160"), null);

        DocumentDto resultat = documentAgentService.validerEtReprendre(entrepriseId, documentId, request, principal);

        assertThat(document.getMontantHt()).isEqualByComparingTo("12000");
        assertThat(document.getMontantTva()).isEqualByComparingTo("1800"); // non corrige, inchange
        assertThat(document.getMontantTtc()).isEqualByComparingTo("14160");
        assertThat(resultat.id()).isEqualTo(documentId);
        verify(pieceJustificativeAgentService).reprendreApresValidation(documentId);
    }

    @Test
    void leve_la_revue_humaine_meme_si_aucun_graphe_nest_repris_ex_piece_web() {
        // Simule une piece web bloquee EN_ATTENTE_VALIDATION_HUMAINE par un score de fraude
        // CRITIQUE (Sprint 7) : aucun checkpoint LangGraph4j ne lui correspond, donc
        // pieceJustificativeAgentService.reprendreApresValidation(...) est un no-op silencieux
        // (mock non stubbe = ne fait rien) - le statut doit tout de meme sortir de la revue humaine.
        Document document = documentEnAttente();
        document.setCanalSource(CanalSource.WEB);
        when(entrepriseAccessService.verifierAcces(entrepriseId, principal)).thenReturn(entreprise);
        when(documentRepository.findById(documentId)).thenReturn(Optional.of(document));
        when(documentRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        ValiderDocumentRequest request = new ValiderDocumentRequest(null, null, null, null);
        DocumentDto resultat = documentAgentService.validerEtReprendre(entrepriseId, documentId, request, principal);

        assertThat(document.getStatut()).isEqualTo(StatutDocument.TRAITE);
        assertThat(resultat.statut()).isEqualTo("TRAITE");
    }

    @Test
    void refuse_de_valider_un_document_qui_nest_pas_en_attente() {
        Document document = documentEnAttente();
        document.setStatut(StatutDocument.TRAITE);
        when(entrepriseAccessService.verifierAcces(entrepriseId, principal)).thenReturn(entreprise);
        when(documentRepository.findById(documentId)).thenReturn(Optional.of(document));

        ValiderDocumentRequest request = new ValiderDocumentRequest(null, null, null, null);

        assertThatThrownBy(() -> documentAgentService.validerEtReprendre(entrepriseId, documentId, request, principal))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("n'est pas en attente");
        verify(pieceJustificativeAgentService, never()).reprendreApresValidation(any());
    }

    @Test
    void refuse_un_document_dune_autre_entreprise() {
        Document document = documentEnAttente();
        Entreprise autreEntreprise = new Entreprise();
        autreEntreprise.setId(UUID.randomUUID());
        document.setEntreprise(autreEntreprise);
        when(entrepriseAccessService.verifierAcces(entrepriseId, principal)).thenReturn(entreprise);
        when(documentRepository.findById(documentId)).thenReturn(Optional.of(document));

        ValiderDocumentRequest request = new ValiderDocumentRequest(null, null, null, null);

        assertThatThrownBy(() -> documentAgentService.validerEtReprendre(entrepriseId, documentId, request, principal))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("introuvable");
        verify(pieceJustificativeAgentService, never()).reprendreApresValidation(any());
    }
}
