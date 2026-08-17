package com.example.comptable.document.ocr;

import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.apache.pdfbox.pdmodel.font.Standard14Fonts;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Couvre la regression diagnostiquee sur une facture ONEA reelle en recette : le montant "22 012"
 * extrait d'un PDF genere (impression HTML -> PDF) utilise une espace insecable U+00A0 comme
 * separateur de milliers, pas une espace ASCII - {@link #normaliserEspaces} garantit que le
 * caractere [0-9 .,] des regex de capture continue de la reconnaitre.
 */
class HeuristiqueOcrExtractionServiceTest {

    private final HeuristiqueOcrExtractionService service = new HeuristiqueOcrExtractionService();

    @Test
    void normalise_lespace_insecable_en_espace_ascii() {
        String texte = "Total Montant HT\n18 654 FCFA\nNET A PAYER : 22 012 FCFA";

        String normalise = HeuristiqueOcrExtractionService.normaliserEspaces(texte);

        assertThat(normalise).doesNotContain(" ");
        assertThat(normalise).contains("18 654", "22 012");
    }

    @Test
    void normalise_lespace_fine_insecable_en_espace_ascii() {
        String texte = "TOTAL TTC 1 234 567";

        String normalise = HeuristiqueOcrExtractionService.normaliserEspaces(texte);

        assertThat(normalise).doesNotContain(" ");
        assertThat(normalise).contains("1 234 567");
    }

    @Test
    void capture_le_montant_ttc_apres_normalisation_dune_espace_insecable() {
        String texteBrut = "NET A PAYER : 22 012 FCFA";
        String texte = HeuristiqueOcrExtractionService.normaliserEspaces(texteBrut);

        BigDecimal montant = service.extraireMontant(texte, HeuristiqueOcrExtractionService.MONTANT_TTC);

        assertThat(montant).isEqualByComparingTo("22012");
    }

    @Test
    void naurait_pas_capture_le_montant_sans_la_normalisation_prealable() {
        // Preuve que la regression existait bien avant le correctif : sans normaliserEspaces,
        // le caractere [0-9 .,] ne reconnait pas l'espace insecable et rate le montant.
        String texteNonNormalise = "NET A PAYER : 22 012 FCFA";

        BigDecimal montant = service.extraireMontant(texteNonNormalise, HeuristiqueOcrExtractionService.MONTANT_TTC);

        assertThat(montant).isNull();
    }

    @Test
    void signale_clairement_un_pdf_sans_aucun_texte_extractible() throws Exception {
        // PDF valide, une page blanche sans aucun contenu texte - reproduit fidelement le cas
        // reel (facture ONEA generee par impression HTML dont le rendu n'expose aucun texte via PDFBox).
        byte[] pdfSansTexte = pdfPageBlanche();

        OcrResult resultat = service.extraire(pdfSansTexte, "application/pdf");

        assertThat(resultat.reussite()).isFalse();
        assertThat(resultat.message()).contains("aucun texte extractible").contains("agent.vlm.enabled");
    }

    @Test
    void distingue_le_message_texte_absent_du_message_montant_non_reconnu() throws Exception {
        // Un PDF avec du texte reel mais aucun montant reconnaissable garde l'ancien message
        // ("Aucun montant reconnu"), distinct du cas "aucun texte extractible" ci-dessus - deux
        // diagnostics differents pour deux causes differentes.
        byte[] pdfAvecTexteSansMontant = pdfAvecTexte("Ceci est une page de texte sans aucun montant.");

        OcrResult resultat = service.extraire(pdfAvecTexteSansMontant, "application/pdf");

        assertThat(resultat.reussite()).isFalse();
        assertThat(resultat.message()).contains("Aucun montant reconnu");
        assertThat(resultat.message()).doesNotContain("aucun texte extractible");
    }

    private byte[] pdfPageBlanche() throws Exception {
        try (PDDocument document = new PDDocument()) {
            document.addPage(new PDPage());
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            document.save(out);
            return out.toByteArray();
        }
    }

    private byte[] pdfAvecTexte(String texte) throws Exception {
        try (PDDocument document = new PDDocument()) {
            PDPage page = new PDPage();
            document.addPage(page);
            try (PDPageContentStream stream = new PDPageContentStream(document, page)) {
                stream.beginText();
                stream.setFont(new PDType1Font(Standard14Fonts.FontName.HELVETICA), 12);
                stream.newLineAtOffset(50, 700);
                stream.showText(texte);
                stream.endText();
            }
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            document.save(out);
            return out.toByteArray();
        }
    }
}
