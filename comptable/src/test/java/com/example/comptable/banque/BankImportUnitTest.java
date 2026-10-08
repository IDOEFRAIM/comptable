package com.example.comptable.banque;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/** Regles pures de l'import bancaire : normalisation, indices, montants, colonnes, empreintes. */
class BankImportUnitTest {

    // ------------------------------------------------------------------------------------------ normalisation

    @Test
    void normalisation_gardeLOriginalIntactEtUniformiseLeReste() {
        String original = "  Vir. reçu de  ÉTS Koné & Fils – Réf: FAC-2026/184  ";

        String normalise = BankLabelNormalizer.normaliser(original);

        assertThat(normalise).isEqualTo("VIR RECU DE ETS KONE FILS REF FAC-2026/184");
        assertThat(original).startsWith("  Vir. reçu"); // la chaine d'origine n'est jamais modifiee
        assertThat(BankLabelNormalizer.normaliser(null)).isEmpty();
    }

    @Test
    void indices_sontDesIndicesLusDansLeLibelle() {
        var i = BankLabelNormalizer.extraire("VIR RECU DE ETS KONE FILS REF FAC-2026/184");

        assertThat(i.moyenPaiement()).isEqualTo("VIREMENT");
        assertThat(i.nomTiers()).isEqualTo("ETS KONE FILS");
        assertThat(i.numeroFacture()).isEqualTo("FAC-2026/184");
    }

    @Test
    void indices_moyensDePaiement() {
        assertThat(BankLabelNormalizer.extraire("PRLV SDE FACTURE EAU").moyenPaiement()).isEqualTo("PRELEVEMENT");
        assertThat(BankLabelNormalizer.extraire("RETRAIT DAB PLATEAU").moyenPaiement()).isEqualTo("RETRAIT_ESPECES");
        assertThat(BankLabelNormalizer.extraire("FRAIS DE TENUE DE COMPTE").moyenPaiement()).isEqualTo("FRAIS");
        assertThat(BankLabelNormalizer.extraire("CHQ 0012345").moyenPaiement()).isEqualTo("CHEQUE");
        assertThat(BankLabelNormalizer.extraire("ORANGE MONEY ENCAISSEMENT").moyenPaiement()).isEqualTo("MOBILE_MONEY");
        assertThat(BankLabelNormalizer.extraire("DIVERS").moyenPaiement()).isNull();
        assertThat(BankLabelNormalizer.extraire("DIVERS").nomTiers()).isNull();
    }

    // ------------------------------------------------------------------------------------------ montants

    @Test
    void montants_formatsUsuels() {
        assertThat(BankCsvParser.parserMontant("1 234 567")).isEqualByComparingTo("1234567");
        assertThat(BankCsvParser.parserMontant("1 234,56")).isEqualByComparingTo("1234.56");
        assertThat(BankCsvParser.parserMontant("1.234,56")).isEqualByComparingTo("1234.56");
        assertThat(BankCsvParser.parserMontant("1,234.56")).isEqualByComparingTo("1234.56");
        assertThat(BankCsvParser.parserMontant("1.234.567")).isEqualByComparingTo("1234567");
        assertThat(BankCsvParser.parserMontant("-45 000")).isEqualByComparingTo("-45000");
        assertThat(BankCsvParser.parserMontant("(100)")).isEqualByComparingTo("-100");
        assertThat(BankCsvParser.parserMontant("500 FCFA")).isEqualByComparingTo("500");
        assertThat(BankCsvParser.parserMontant("12,5")).isEqualByComparingTo("12.5");
        assertThat(BankCsvParser.parserMontant("abc")).isNull();
        assertThat(BankCsvParser.parserMontant("")).isNull();
    }

    // ------------------------------------------------------------------------------------------ colonnes

    private static byte[] csv(String texte) {
        return texte.getBytes(StandardCharsets.UTF_8);
    }

    @Test
    void enTeteFrancais_avecDebitEtCredit_estDeduitSansConfiguration() throws Exception {
        var lecture = BankCsvParser.lire(csv("Date opération;Date valeur;Libellé;Référence;Débit;Crédit;Solde\n"
                + "05/01/2027;06/01/2027;VIR RECU CLIENT A;R1;;30 000;130 000\n"
                + "10/01/2027;10/01/2027;FRAIS BANCAIRES;;1 500;;128 500\n"), null);

        assertThat(lecture.rejets()).isEmpty();
        assertThat(lecture.valides()).hasSize(2);
        var premiere = lecture.valides().get(0);
        assertThat(premiere.date()).isEqualTo(LocalDate.of(2027, 1, 5));
        assertThat(premiere.dateValeur()).isEqualTo(LocalDate.of(2027, 1, 6));
        assertThat(premiere.montant()).isEqualByComparingTo("30000");
        assertThat(premiere.reference()).isEqualTo("R1");
        assertThat(premiere.solde()).isEqualByComparingTo("130000");
        assertThat(lecture.valides().get(1).montant()).isEqualByComparingTo("-1500");
    }

    @Test
    void enTeteAnglais_avecMontantSigne_estDeduit() throws Exception {
        var lecture = BankCsvParser.lire(csv("Booking Date,Description,Amount\n2027-02-01,\"Transfer, ACME\",250000\n"), null);

        assertThat(lecture.valides()).hasSize(1);
        assertThat(lecture.valides().get(0).libelle()).isEqualTo("Transfer, ACME"); // virgule dans un champ entre guillemets
        assertThat(lecture.valides().get(0).montant()).isEqualByComparingTo("250000");
    }

    @Test
    void formatHistorique_troisColonnesSansEnTete_resteLu() throws Exception {
        var lecture = BankCsvParser.lire(csv("2027-01-05;Virement client;30000\n2027-01-10;Frais;-1500\n"), null);

        assertThat(lecture.valides()).hasSize(2);
        assertThat(lecture.mapping().montant()).isEqualTo(2);
    }

    @Test
    void mappingExplicite_estRespecte() throws Exception {
        var mapping = new BankCsvMapping(2, null, 0, null, null, null, 1, null, true, ";");

        var lecture = BankCsvParser.lire(csv("Texte;Somme;Quand\nLoyer;-90000;03/01/2027\n"), mapping);

        assertThat(lecture.valides()).hasSize(1);
        assertThat(lecture.valides().get(0).libelle()).isEqualTo("Loyer");
        assertThat(lecture.valides().get(0).montant()).isEqualByComparingTo("-90000");
    }

    @Test
    void lignesInvalides_sontRejeteesAvecLeurRaison_jamaisIgnoreesEnSilence() throws Exception {
        var lecture = BankCsvParser.lire(csv("date;libelle;montant\n"
                + "2027-01-05;OK;1000\n"
                + "pas-une-date;KO;1000\n"
                + "2027-01-07;Sans montant;abc\n"), null);

        assertThat(lecture.totalLignes()).isEqualTo(3);
        assertThat(lecture.valides()).hasSize(1);
        assertThat(lecture.rejets()).hasSize(2);
        assertThat(lecture.rejets()).extracting(BankCsvParser.Rejet::numeroLigne).containsExactly(3, 4);
        assertThat(lecture.rejets().get(0).raison()).contains("Date illisible");
    }

    @Test
    void debitEtCreditSurLaMemeLigne_estRejete() throws Exception {
        var lecture = BankCsvParser.lire(csv("Date;Libelle;Debit;Credit\n2027-01-05;X;100;200\n"), null);

        assertThat(lecture.valides()).isEmpty();
        assertThat(lecture.rejets().get(0).raison()).contains("Debit et credit");
    }

    @Test
    void encodageLatin1_estLuSansCasserLesAccents() throws Exception {
        byte[] latin1 = "date;libelle;montant\n2027-01-05;Règlement société;1000\n".getBytes(StandardCharsets.ISO_8859_1);

        var lecture = BankCsvParser.lire(latin1, null);

        assertThat(lecture.valides().get(0).libelle()).isEqualTo("Règlement société");
    }

    // ------------------------------------------------------------------------------------------ empreinte

    @Test
    void empreinte_estStable_etDistingueLibelleSensEtOccurrence() {
        UUID compte = UUID.randomUUID();
        LocalDate jour = LocalDate.of(2027, 1, 5);
        String base = BankFingerprint.calculer(compte, jour, new BigDecimal("1000"), "R1", "VIR CLIENT", 1);

        assertThat(BankFingerprint.calculer(compte, jour, new BigDecimal("1000.00"), "r1", "VIR CLIENT", 1)).isEqualTo(base); // stable
        assertThat(BankFingerprint.calculer(compte, jour, new BigDecimal("1000"), "R1", "VIR AUTRE", 1)).isNotEqualTo(base); // autre libelle
        assertThat(BankFingerprint.calculer(compte, jour, new BigDecimal("-1000"), "R1", "VIR CLIENT", 1)).isNotEqualTo(base); // autre sens
        assertThat(BankFingerprint.calculer(compte, jour, new BigDecimal("1000"), "R1", "VIR CLIENT", 2)).isNotEqualTo(base); // 2e occurrence
        assertThat(BankFingerprint.calculer(UUID.randomUUID(), jour, new BigDecimal("1000"), "R1", "VIR CLIENT", 1)).isNotEqualTo(base); // autre compte
        assertThat(base).hasSize(64);
    }
}
