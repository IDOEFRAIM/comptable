package com.example.comptable.document.service;

import org.junit.jupiter.api.Test;
import org.springframework.web.server.ResponseStatusException;

import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Sécurité des dépôts de fichiers : le contenu réel doit être celui du type annoncé (un exécutable renommé en .png ou une page HTML en .pdf sont refusés avec 415). */
class UploadSecurityTest {

    private static final byte[] PNG = {(byte) 0x89, 'P', 'N', 'G', 0x0D, 0x0A, 0x1A, 0x0A, 0, 0, 0, 0x0D};
    private static final byte[] JPEG = {(byte) 0xFF, (byte) 0xD8, (byte) 0xFF, (byte) 0xE0, 0, 0x10};

    private static int statut(byte[] contenu, String type) {
        try {
            DocumentService.validerSignature(contenu, type);
            return 200;
        } catch (ResponseStatusException e) {
            return e.getStatusCode().value();
        }
    }

    @Test
    void lesVraisFichiersPassent() {
        assertThat(statut(PNG, "image/png")).isEqualTo(200);
        assertThat(statut(JPEG, "image/jpeg")).isEqualTo(200);
        assertThat(statut("%PDF-1.7\n1 0 obj".getBytes(StandardCharsets.ISO_8859_1), "application/pdf")).isEqualTo(200);
        assertThat(statut("﻿   %PDF-1.4".getBytes(StandardCharsets.ISO_8859_1), "application/pdf")).as("en-tête décalé toléré par la spécification PDF").isEqualTo(200);
    }

    @Test
    void unExecutableUneHtmlOuUnScriptRenommesSontRefuses() {
        byte[] exe = "MZ\u0090\u0000 pas une image".getBytes(StandardCharsets.ISO_8859_1);
        byte[] html = "<html><script>alert(1)</script></html>".getBytes(StandardCharsets.UTF_8);
        for (String type : new String[] {"image/png", "image/jpeg", "application/pdf"}) {
            assertThat(statut(exe, type)).as("exe annoncé " + type).isEqualTo(415);
            assertThat(statut(html, type)).as("html annoncé " + type).isEqualTo(415);
        }
    }

    @Test
    void unTypeInconnuOuUnContenuDUnAutreFormatSontRefuses() {
        assertThat(statut(PNG, "image/jpeg")).as("PNG annoncé JPEG").isEqualTo(415);
        assertThat(statut(JPEG, "application/pdf")).as("JPEG annoncé PDF").isEqualTo(415);
        assertThat(statut(PNG, "image/svg+xml")).isEqualTo(415);
        assertThat(statut(PNG, null)).isEqualTo(415);
        assertThat(statut(new byte[0], "image/png")).as("vide").isEqualTo(415);
        assertThat(statut(new byte[] {(byte) 0x89, 'P'}, "image/png")).as("tronqué").isEqualTo(415);
        assertThatCode(() -> DocumentService.validerSignature(null, "image/png")).isInstanceOf(ResponseStatusException.class);
    }

    @Test
    void unPdfTropLoinDansLeFichierNEstPasUnPdf() {
        byte[] texte = ("x".repeat(2000) + "%PDF-1.4").getBytes(StandardCharsets.ISO_8859_1);
        assertThatThrownBy(() -> DocumentService.validerSignature(texte, "application/pdf")).isInstanceOf(ResponseStatusException.class);
    }
}
