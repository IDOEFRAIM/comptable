package com.example.comptable.comptabilite.reprise;

import com.example.comptable.comptabilite.reprise.BalanceOuvertureParser.Lecture;
import org.junit.jupiter.api.Test;
import org.springframework.web.server.ResponseStatusException;

import java.io.ByteArrayOutputStream;
import java.math.BigDecimal;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class BalanceOuvertureParserTest {

    private static Lecture csv(String texte) {
        return BalanceOuvertureParser.lire(texte.getBytes(StandardCharsets.UTF_8), "balance.csv");
    }

    @Test
    void csvAPointVirgule_titresReconnusPeuImporteLOrdre_etMontantsALaFrancaise() {
        Lecture l = csv("Libellé;Crédit;Compte;Débit\nBanque Coris;;521;1 250 000,50\nCapital social;1 250 000,50;101;\n");
        assertThat(l.lignes()).hasSize(2);
        assertThat(l.lignes().get(0).compte()).isEqualTo("521");
        assertThat(l.lignes().get(0).debit()).isEqualByComparingTo("1250000.50");
        assertThat(l.lignes().get(0).credit()).isEqualByComparingTo("0");
        assertThat(l.lignes().get(1).credit()).isEqualByComparingTo("1250000.50");
        assertThat(l.lignes().get(1).libelle()).isEqualTo("Capital social");
    }

    @Test
    void sansTitres_ordreCompteLibelleDebitCredit() {
        Lecture l = csv("521;Banque;500000;0\n101;Capital;0;500000\n");
        assertThat(l.lignes()).hasSize(2);
        assertThat(l.lignes().get(1).credit()).isEqualByComparingTo("500000");
    }

    @Test
    void virguleOuTabulation_etAccentsISO88591_deExcelFrancais() {
        byte[] octets = "Compte\tIntitulé\tDébit\tCrédit\n571\tCaisse é\t12000\t\n".getBytes(Charset.forName("windows-1252"));
        Lecture l = BalanceOuvertureParser.lire(octets, "export.csv");
        assertThat(l.lignes()).hasSize(1);
        assertThat(l.lignes().get(0).libelle()).isEqualTo("Caisse é");
        assertThat(l.lignes().get(0).debit()).isEqualByComparingTo("12000");
    }

    @Test
    void lignesDeTotalIgnorees_etColonneTiersLue() {
        Lecture l = csv("Compte;Tiers;Débit;Crédit\n411;Client Alpha;150000;\nTotal;;150000;0\n");
        assertThat(l.lignes()).hasSize(1);
        assertThat(l.lignes().get(0).tiers()).isEqualTo("Client Alpha");
        assertThat(l.ignorees()).hasSize(1);
    }

    @Test
    void formatsDeMontants() {
        assertThat(BalanceOuvertureParser.montant("1 234 567")).isEqualByComparingTo("1234567");
        assertThat(BalanceOuvertureParser.montant("1.234.567")).isEqualByComparingTo("1234567");
        assertThat(BalanceOuvertureParser.montant("1.234.567,25")).isEqualByComparingTo("1234567.25");
        assertThat(BalanceOuvertureParser.montant("1,234,567.25")).isEqualByComparingTo("1234567.25");
        assertThat(BalanceOuvertureParser.montant("1234,5")).isEqualByComparingTo("1234.5");
        assertThat(BalanceOuvertureParser.montant("15 000 FCFA")).isEqualByComparingTo("15000");
        assertThat(BalanceOuvertureParser.montant("")).isEqualByComparingTo(BigDecimal.ZERO);
        assertThatThrownBy(() -> BalanceOuvertureParser.montant("-500")).hasMessageContaining("négatif");
        assertThatThrownBy(() -> BalanceOuvertureParser.montant("abc")).hasMessageContaining("illisible");
        assertThatThrownBy(() -> BalanceOuvertureParser.montant("1,2345")).hasMessageContaining("décimales");
    }

    @Test
    void ligneInvalideSignalee_sansEmpecherLesAutres() {
        Lecture l = csv("Compte;Débit;Crédit\n521;100;\nABC;50;\n-;;\n");
        assertThat(l.lignes().get(0).erreur()).isNull();
        assertThat(l.lignes().get(1).erreur()).contains("invalide");
    }

    @Test
    void fichierSansColonnesReconnues_ouVide_ouAncienExcel_refuse() {
        assertThatThrownBy(() -> csv("a;b;c\n1;2;3\n")).isInstanceOf(ResponseStatusException.class).hasMessageContaining("Colonnes introuvables");
        assertThatThrownBy(() -> BalanceOuvertureParser.lire(new byte[0], "x.csv")).hasMessageContaining("vide");
        assertThatThrownBy(() -> BalanceOuvertureParser.lire(new byte[] {1, 2, 3}, "ancien.xls")).hasMessageContaining(".xlsx");
    }

    @Test
    void classeurXlsx_premiereFeuille_textesPartages_nombres_etCellulesVides() throws Exception {
        String ns = "xmlns=\"http://schemas.openxmlformats.org/spreadsheetml/2006/main\"";
        String partages = "<sst " + ns + "><si><t>Compte</t></si><si><t>Libellé</t></si><si><t>Débit</t></si><si><t>Crédit</t></si><si><t>Banque Coris</t></si><si><t>Capital</t></si></sst>";
        String feuille = "<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?><worksheet " + ns + "><sheetData>"
                + "<row r=\"1\"><c r=\"A1\" t=\"s\"><v>0</v></c><c r=\"B1\" t=\"s\"><v>1</v></c><c r=\"C1\" t=\"s\"><v>2</v></c><c r=\"D1\" t=\"s\"><v>3</v></c></row>"
                + "<row r=\"2\"><c r=\"A2\"><v>521</v></c><c r=\"B2\" t=\"s\"><v>4</v></c><c r=\"C2\"><v>1.5E7</v></c><c r=\"D2\"/></row>"
                + "<row r=\"3\"><c r=\"A3\"><v>101</v></c><c r=\"B3\" t=\"s\"><v>5</v></c><c r=\"C3\"/><c r=\"D3\"><v>15000000</v></c></row>"
                + "</sheetData></worksheet>";
        ByteArrayOutputStream sortie = new ByteArrayOutputStream();
        try (ZipOutputStream zip = new ZipOutputStream(sortie)) {
            zip.putNextEntry(new ZipEntry("xl/sharedStrings.xml"));
            zip.write(partages.getBytes(StandardCharsets.UTF_8));
            zip.closeEntry();
            zip.putNextEntry(new ZipEntry("xl/worksheets/sheet1.xml"));
            zip.write(feuille.getBytes(StandardCharsets.UTF_8));
            zip.closeEntry();
        }
        Lecture l = BalanceOuvertureParser.lire(sortie.toByteArray(), "balance.xlsx");
        assertThat(l.lignes()).hasSize(2);
        assertThat(l.lignes().get(0).compte()).isEqualTo("521");
        assertThat(l.lignes().get(0).libelle()).isEqualTo("Banque Coris");
        assertThat(l.lignes().get(0).debit()).as("1.5E7 est lu comme 15 000 000").isEqualByComparingTo("15000000");
        assertThat(l.lignes().get(1).credit()).isEqualByComparingTo("15000000");
    }

    @Test
    void xlsxRefuseLesEntitesExternes() throws Exception {
        String feuille = "<?xml version=\"1.0\"?><!DOCTYPE x [<!ENTITY e SYSTEM \"file:///etc/passwd\">]><worksheet><sheetData><row r=\"1\"><c r=\"A1\" t=\"inlineStr\"><is><t>&e;</t></is></c></row></sheetData></worksheet>";
        ByteArrayOutputStream sortie = new ByteArrayOutputStream();
        try (ZipOutputStream zip = new ZipOutputStream(sortie)) {
            zip.putNextEntry(new ZipEntry("xl/worksheets/sheet1.xml"));
            zip.write(feuille.getBytes(StandardCharsets.UTF_8));
            zip.closeEntry();
        }
        assertThatThrownBy(() -> BalanceOuvertureParser.lire(sortie.toByteArray(), "piege.xlsx")).isInstanceOf(ResponseStatusException.class);
    }
}
