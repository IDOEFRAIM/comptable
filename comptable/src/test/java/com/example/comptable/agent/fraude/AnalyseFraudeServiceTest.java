package com.example.comptable.agent.fraude;

import com.example.comptable.agent.client.AiServiceClient;
import com.example.comptable.document.domain.Document;
import com.example.comptable.document.domain.TypeDocument;
import com.example.comptable.document.ocr.OcrResult;
import com.example.comptable.document.repository.DocumentRepository;
import com.example.comptable.entreprise.domain.Entreprise;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

/**
 * Ne couvre que les controles deterministes (TVA, montant rond, doublon) : l'analyse visuelle
 * reste desactivee par defaut ({@code agent.fraude.analyse-visuelle.enabled=false} dans tous ces
 * tests), donc {@code aiServiceClient} n'est jamais sollicite ici.
 */
@ExtendWith(MockitoExtension.class)
class AnalyseFraudeServiceTest {

    @Mock
    private DocumentRepository documentRepository;
    @Mock
    private AiServiceClient aiServiceClient;

    private AnalyseFraudeService service;
    private Entreprise entreprise;

    @BeforeEach
    void setUp() {
        service = new AnalyseFraudeService(documentRepository, aiServiceClient, false,
                new BigDecimal("0.18"), new BigDecimal("200"), 3, new BigDecimal("5000"));

        entreprise = new Entreprise();
        entreprise.setId(UUID.randomUUID());
        entreprise.setRaisonSociale("SARL Test");
        entreprise.setRegimeFiscal("REEL_NORMAL");
    }

    private Document document(TypeDocument type) {
        Document document = new Document();
        document.setEntreprise(entreprise);
        document.setTypeDocument(type);
        return document;
    }

    private OcrResult extraction(BigDecimal ht, BigDecimal tva, BigDecimal ttc, LocalDate date) {
        return new OcrResult(ht, tva, ttc, date, true, "ok", 0.9, null);
    }

    @Test
    void aucune_alerte_quand_tout_est_coherent() {
        when(documentRepository.rechercherDoublonsPotentiels(any(), any(), any(), any())).thenReturn(List.of());
        Document document = document(TypeDocument.FACTURE_ACHAT);
        OcrResult extraction = extraction(new BigDecimal("10000"), new BigDecimal("1800"), new BigDecimal("11800"),
                LocalDate.of(2027, 1, 5));

        RapportFraude rapport = service.analyser(document, extraction, new byte[0], "application/pdf");

        assertThat(rapport.scoreRisque()).isEqualTo(NiveauRisqueFraude.FAIBLE);
        assertThat(rapport.alertes()).isEmpty();
    }

    @Test
    void signale_un_ecart_de_tva_grossier() {
        when(documentRepository.rechercherDoublonsPotentiels(any(), any(), any(), any())).thenReturn(List.of());
        Document document = document(TypeDocument.FACTURE_ACHAT);
        // TVA attendue ~1800 (18% de 10000), ici 500 declares : ecart largement au-dela de la tolerance.
        OcrResult extraction = extraction(new BigDecimal("10000"), new BigDecimal("500"), new BigDecimal("10500"),
                LocalDate.of(2027, 1, 5));

        RapportFraude rapport = service.analyser(document, extraction, new byte[0], "application/pdf");

        assertThat(rapport.alertes()).anyMatch(a -> a.code().equals("ECART_TVA") && a.gravite() == NiveauRisqueFraude.MOYEN);
        assertThat(rapport.scoreRisque()).isEqualTo(NiveauRisqueFraude.MOYEN);
    }

    @Test
    void nignore_pas_lecart_de_tva_pour_une_entreprise_exoneree() {
        entreprise.setRegimeFiscal("EXONERE_TVA");
        when(documentRepository.rechercherDoublonsPotentiels(any(), any(), any(), any())).thenReturn(List.of());
        Document document = document(TypeDocument.FACTURE_ACHAT);
        OcrResult extraction = extraction(new BigDecimal("10000"), BigDecimal.ZERO, new BigDecimal("10000"),
                LocalDate.of(2027, 1, 5));

        RapportFraude rapport = service.analyser(document, extraction, new byte[0], "application/pdf");

        assertThat(rapport.alertes()).noneMatch(a -> a.code().equals("ECART_TVA"));
    }

    @Test
    void signale_un_montant_rond_suspect_sur_une_facture_dachat() {
        when(documentRepository.rechercherDoublonsPotentiels(any(), any(), any(), any())).thenReturn(List.of());
        Document document = document(TypeDocument.FACTURE_ACHAT);
        OcrResult extraction = extraction(new BigDecimal("25423.73"), new BigDecimal("4576.27"), new BigDecimal("30000"),
                LocalDate.of(2027, 1, 5));

        RapportFraude rapport = service.analyser(document, extraction, new byte[0], "application/pdf");

        assertThat(rapport.alertes()).anyMatch(a -> a.code().equals("MONTANT_ROND_SUSPECT"));
    }

    @Test
    void ne_signale_pas_de_montant_rond_sur_un_releve_bancaire() {
        when(documentRepository.rechercherDoublonsPotentiels(any(), any(), any(), any())).thenReturn(List.of());
        Document document = document(TypeDocument.RELEVE_BANCAIRE);
        OcrResult extraction = extraction(null, null, new BigDecimal("30000"), LocalDate.of(2027, 1, 5));

        RapportFraude rapport = service.analyser(document, extraction, new byte[0], "application/pdf");

        assertThat(rapport.alertes()).noneMatch(a -> a.code().equals("MONTANT_ROND_SUSPECT"));
    }

    @Test
    void signale_un_doublon_strict_avec_une_gravite_critique() {
        Document autre = document(TypeDocument.FACTURE_ACHAT);
        when(documentRepository.rechercherDoublonsPotentiels(any(), any(), any(), any())).thenReturn(List.of(autre));
        Document document = document(TypeDocument.FACTURE_ACHAT);
        OcrResult extraction = extraction(new BigDecimal("10000"), new BigDecimal("1800"), new BigDecimal("11800"),
                LocalDate.of(2027, 1, 5));

        RapportFraude rapport = service.analyser(document, extraction, new byte[0], "application/pdf");

        assertThat(rapport.alertes()).anyMatch(a -> a.code().equals("DOUBLON_POTENTIEL") && a.gravite() == NiveauRisqueFraude.CRITIQUE);
        assertThat(rapport.scoreRisque()).isEqualTo(NiveauRisqueFraude.CRITIQUE);
        assertThat(rapport.estCritique()).isTrue();
    }

    @Test
    void le_score_global_retient_toujours_la_gravite_la_plus_forte() {
        Document autre = document(TypeDocument.FACTURE_ACHAT);
        when(documentRepository.rechercherDoublonsPotentiels(any(), any(), any(), any())).thenReturn(List.of(autre));
        Document document = document(TypeDocument.FACTURE_ACHAT);
        // Montant rond (MOYEN) ET doublon (CRITIQUE) en meme temps : le score global doit rester CRITIQUE.
        OcrResult extraction = extraction(new BigDecimal("10000"), new BigDecimal("1800"), new BigDecimal("30000"),
                LocalDate.of(2027, 1, 5));

        RapportFraude rapport = service.analyser(document, extraction, new byte[0], "application/pdf");

        assertThat(rapport.scoreRisque()).isEqualTo(NiveauRisqueFraude.CRITIQUE);
        assertThat(rapport.alertes()).hasSizeGreaterThanOrEqualTo(2);
    }
}
