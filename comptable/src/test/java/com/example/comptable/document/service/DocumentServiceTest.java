package com.example.comptable.document.service;

import com.example.comptable.agent.imputation.ImputationSuggestionBuilder;
import com.example.comptable.agent.fraude.AlerteFraude;
import com.example.comptable.agent.fraude.AnalyseFraudeService;
import com.example.comptable.agent.fraude.NiveauRisqueFraude;
import com.example.comptable.agent.fraude.RapportFraude;
import com.example.comptable.document.domain.CanalSource;
import com.example.comptable.document.domain.Document;
import com.example.comptable.document.domain.StatutDocument;
import com.example.comptable.document.domain.TypeDocument;
import com.example.comptable.document.ocr.OcrExtractionService;
import com.example.comptable.document.ocr.OcrResult;
import com.example.comptable.document.repository.DocumentRepository;
import com.example.comptable.document.storage.DocumentStorageService;
import com.example.comptable.document.storage.StoredFile;
import com.example.comptable.document.web.dto.DocumentDto;
import com.example.comptable.entreprise.domain.Entreprise;
import com.example.comptable.entreprise.repository.EntrepriseRepository;
import com.example.comptable.entreprise.service.EntrepriseAccessService;
import com.example.comptable.identite.domain.Tenant;
import com.example.comptable.identite.repository.UtilisateurRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

/**
 * Se concentre sur l'integration du controleur de fraude (Sprint 7) dans
 * {@code DocumentService.appliquerExtraction} : c'est le seul comportement nouveau/a risque
 * introduit par ce sprint sur un service jusque-la non couvert par un test dedie.
 */
@ExtendWith(MockitoExtension.class)
class DocumentServiceTest {
    /** Octets de tête d'un vrai JPEG : le service refuse tout contenu qui ne correspond pas au type annoncé. */
    private static final byte[] JPEG_VALIDE = {(byte) 0xFF, (byte) 0xD8, (byte) 0xFF, (byte) 0xE0, 0, 0x10, 'J', 'F', 'I', 'F', 0};


    @Mock
    private EntrepriseAccessService entrepriseAccessService;
    @Mock
    private EntrepriseRepository entrepriseRepository;
    @Mock
    private DocumentRepository documentRepository;
    @Mock
    private UtilisateurRepository utilisateurRepository;
    @Mock
    private DocumentStorageService storageService;
    @Mock
    private OcrExtractionService ocrExtractionService;
    @Mock
    private AnalyseFraudeService analyseFraudeService;
    @Mock
    private ImputationSuggestionBuilder imputationSuggestionBuilder;
    @Mock
    private DocumentPipeline documentPipeline;

    private DocumentService documentService;
    private Entreprise entreprise;
    private UUID entrepriseId;

    @BeforeEach
    void setUp() throws java.io.IOException {
        ObjectMapper objectMapper = new ObjectMapper().registerModule(new JavaTimeModule());
        documentService = new DocumentService(entrepriseAccessService, entrepriseRepository, documentRepository,
                utilisateurRepository, storageService, ocrExtractionService, analyseFraudeService,
                imputationSuggestionBuilder, objectMapper, documentPipeline);

        entrepriseId = UUID.randomUUID();
        Tenant tenant = new Tenant();
        tenant.setId(UUID.randomUUID());
        entreprise = new Entreprise();
        entreprise.setId(entrepriseId);
        entreprise.setTenant(tenant);
        entreprise.setRaisonSociale("SARL Test");

        when(entrepriseRepository.findById(entrepriseId)).thenReturn(Optional.of(entreprise));
        when(storageService.stocker(any(), any(), any(), any())).thenReturn(new StoredFile("chemin/piece.jpg", 10L));
        when(documentRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));
    }

    @Test
    void bascule_en_attente_de_validation_humaine_quand_le_score_de_fraude_est_critique() {
        when(ocrExtractionService.extraire(any(), any())).thenReturn(
                new OcrResult(new BigDecimal("10000"), new BigDecimal("1800"), new BigDecimal("11800"),
                        LocalDate.of(2027, 1, 5), true, "ok", 0.9, null));
        RapportFraude rapportCritique = new RapportFraude(NiveauRisqueFraude.CRITIQUE,
                List.of(new AlerteFraude("DOUBLON_POTENTIEL", "Montant deja enregistre", NiveauRisqueFraude.CRITIQUE,
                        "Comparez avec la pièce déjà enregistrée avant de valider.")),
                OffsetDateTime.now());
        when(analyseFraudeService.analyser(any(), any(), any(), any())).thenReturn(rapportCritique);

        DocumentDto resultat = documentService.ingererDepuisCanalExterne(entrepriseId, TypeDocument.RECU,
                CanalSource.WHATSAPP, JPEG_VALIDE, "recu.jpg", "image/jpeg");

        assertThat(resultat.statut()).isEqualTo("EN_ATTENTE_VALIDATION_HUMAINE");
        assertThat(resultat.scoreRisqueFraude()).isEqualTo("CRITIQUE");
        assertThat(resultat.alertesFraude()).hasSize(1);
    }

    @Test
    void reste_traite_quand_lanalyse_de_fraude_ne_releve_rien() {
        when(ocrExtractionService.extraire(any(), any())).thenReturn(
                new OcrResult(new BigDecimal("10000"), new BigDecimal("1800"), new BigDecimal("11800"),
                        LocalDate.of(2027, 1, 5), true, "ok", 0.9, null));
        when(analyseFraudeService.analyser(any(), any(), any(), any()))
                .thenReturn(RapportFraude.depuisAlertes(List.of()));

        DocumentDto resultat = documentService.ingererDepuisCanalExterne(entrepriseId, TypeDocument.RECU,
                CanalSource.WHATSAPP, JPEG_VALIDE, "recu.jpg", "image/jpeg");

        assertThat(resultat.statut()).isEqualTo("TRAITE");
        assertThat(resultat.scoreRisqueFraude()).isEqualTo("FAIBLE");
    }

    @Test
    void ne_lance_pas_lanalyse_de_fraude_quand_lextraction_a_echoue() {
        when(ocrExtractionService.extraire(any(), any())).thenReturn(OcrResult.echec("Piece illisible"));

        DocumentDto resultat = documentService.ingererDepuisCanalExterne(entrepriseId, TypeDocument.RECU,
                CanalSource.WHATSAPP, JPEG_VALIDE, "recu.jpg", "image/jpeg");

        assertThat(resultat.statut()).isEqualTo("ERREUR");
        assertThat(analyseFraudeService).satisfies(m -> org.mockito.Mockito.verifyNoInteractions(m));
    }

    @Test
    void transmet_bien_le_document_lentreprise_et_le_contenu_au_controleur_de_fraude() {
        when(ocrExtractionService.extraire(any(), any())).thenReturn(
                new OcrResult(new BigDecimal("10000"), new BigDecimal("1800"), new BigDecimal("11800"),
                        LocalDate.of(2027, 1, 5), true, "ok", 0.9, null));
        when(analyseFraudeService.analyser(any(), any(), any(), any()))
                .thenReturn(RapportFraude.depuisAlertes(List.of()));
        byte[] contenu = JPEG_VALIDE;

        documentService.ingererDepuisCanalExterne(entrepriseId, TypeDocument.RECU, CanalSource.WHATSAPP, contenu,
                "recu.jpg", "image/jpeg");

        ArgumentCaptor<Document> documentCaptor = ArgumentCaptor.forClass(Document.class);
        org.mockito.Mockito.verify(analyseFraudeService).analyser(documentCaptor.capture(), any(), org.mockito.ArgumentMatchers.eq(contenu),
                org.mockito.ArgumentMatchers.eq("image/jpeg"));
        assertThat(documentCaptor.getValue().getEntreprise().getId()).isEqualTo(entrepriseId);
    }
}
