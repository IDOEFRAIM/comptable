package com.example.comptable.document.storage;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayInputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Stockage des pièces : noms de fichiers hostiles neutralisés, traversée de répertoires impossible, isolation par cabinet et entreprise. */
class StockageDocumentsSecuriteTest {

    @TempDir Path racine;

    @Test
    void nomsHostilesNeutralises() {
        assertThat(LocalDocumentStorageService.assainir("../../etc/passwd")).isEqualTo("passwd");
        assertThat(LocalDocumentStorageService.assainir("..\\..\\Windows\\system32\\cmd.exe")).isEqualTo("cmd.exe");
        assertThat(LocalDocumentStorageService.assainir("/absolu/chemin/facture.pdf")).isEqualTo("facture.pdf");
        assertThat(LocalDocumentStorageService.assainir(".htaccess")).doesNotStartWith(".");
        assertThat(LocalDocumentStorageService.assainir("a<b>c|d?.png")).matches("[a-zA-Z0-9._-]+");
        assertThat(LocalDocumentStorageService.assainir("facture\u0000.pdf.exe")).doesNotContain("\u0000");
        assertThat(LocalDocumentStorageService.assainir(null)).isEqualTo("document");
        assertThat(LocalDocumentStorageService.assainir("")).isEqualTo("document");
        assertThat(LocalDocumentStorageService.assainir("///")).isEqualTo("document");
        assertThat(LocalDocumentStorageService.assainir("é".repeat(300) + ".png").length()).isLessThanOrEqualTo(100);
    }

    @Test
    void unFichierNomme_DotDot_resteDansLeDossierDeLEntreprise() throws Exception {
        var stockage = new LocalDocumentStorageService(racine.toString());
        UUID tenant = UUID.randomUUID();
        UUID entreprise = UUID.randomUUID();
        var stocke = stockage.stocker(tenant, entreprise, "../../../evasion.png", new ByteArrayInputStream(new byte[] {1}));
        Path attendu = racine.resolve(tenant.toString()).resolve(entreprise.toString());
        assertThat(Files.list(attendu).count()).isEqualTo(1);
        assertThat(stocke.cheminFichier()).startsWith(tenant + "/" + entreprise + "/");
        assertThat(Files.exists(racine.getParent().resolve("evasion.png"))).isFalse();
    }

    @Test
    void lectureHorsDeLaRacineRefusee() {
        var stockage = new LocalDocumentStorageService(racine.toString());
        assertThatThrownBy(() -> stockage.charger("../../../../etc/passwd")).isInstanceOf(SecurityException.class);
        assertThatThrownBy(() -> stockage.charger("..\\..\\secret")).satisfiesAnyOf(e -> assertThat(e).isInstanceOf(SecurityException.class), e -> assertThat(e).isNotNull());
    }
}
