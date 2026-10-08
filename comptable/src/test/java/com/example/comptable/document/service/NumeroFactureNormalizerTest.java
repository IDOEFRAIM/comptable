package com.example.comptable.document.service;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class NumeroFactureNormalizerTest {

    @Test
    void separateurs_espaces_et_casse_sont_ecrases() {
        assertThat(NumeroFactureNormalizer.normaliser("FAC- 001/2026")).isEqualTo("FAC0012026");
        assertThat(NumeroFactureNormalizer.normaliser("fac-001")).isEqualTo(NumeroFactureNormalizer.normaliser("FAC 001")).isEqualTo("FAC001");
        assertThat(NumeroFactureNormalizer.normaliser("  N° 2026.0042 ")).isEqualTo("N20260042");
    }

    @Test
    void les_accents_sont_retires() {
        assertThat(NumeroFactureNormalizer.normaliser("Facture é-12")).isEqualTo("FACTUREE12");
    }

    @Test
    void vide_ou_sans_caractere_utile_donne_null() {
        assertThat(NumeroFactureNormalizer.normaliser(null)).isNull();
        assertThat(NumeroFactureNormalizer.normaliser("   ")).isNull();
        assertThat(NumeroFactureNormalizer.normaliser("---/ /")).isNull();
    }

    @Test
    void deux_numeros_differents_restent_differents() {
        assertThat(NumeroFactureNormalizer.normaliser("FAC-001")).isNotEqualTo(NumeroFactureNormalizer.normaliser("FAC-010"));
    }

    @Test
    void le_hash_est_stable_et_depend_du_contenu() {
        byte[] a = "contenu".getBytes();
        assertThat(ContentHasher.sha256(a)).hasSize(64).isEqualTo(ContentHasher.sha256("contenu".getBytes()));
        assertThat(ContentHasher.sha256(a)).isNotEqualTo(ContentHasher.sha256("contenu ".getBytes()));
    }
}
