package com.example.comptable.document.service;

import com.example.comptable.comptabilite.domain.Tiers;
import com.example.comptable.document.domain.QualiteClassification;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/** L'identification du tiers est une regle deterministe : seul un nom normalise identique est EXACT. */
class DocumentClassifierTest {

    private Tiers tiers(String nom) {
        Tiers t = new Tiers();
        t.setId(UUID.randomUUID());
        t.setNom(nom);
        return t;
    }

    @Test
    void nom_identique_apres_normalisation_est_exact_et_confiant() {
        Tiers cimenterie = tiers("Cimenterie SARL");
        DocumentClassifier.Resultat r = DocumentClassifier.identifier("CIMENTERIE  S.A.R.L.", List.of(cimenterie, tiers("Quincaillerie Diallo")));

        assertThat(r.qualite()).isEqualTo(QualiteClassification.EXACT);
        assertThat(r.tiers()).isSameAs(cimenterie);
        assertThat(r.confiance()).isGreaterThanOrEqualTo(0.95);
    }

    @Test
    void les_accents_et_les_formes_juridiques_ne_comptent_pas() {
        Tiers t = tiers("Établissements Traoré & Fils");
        assertThat(DocumentClassifier.identifier("ETS TRAORE ET FILS", List.of(t)).qualite()).isEqualTo(QualiteClassification.EXACT);
    }

    @Test
    void nom_seulement_proche_est_probable_jamais_exact() {
        Tiers t = tiers("Quincaillerie Diallo Frères");
        DocumentClassifier.Resultat r = DocumentClassifier.identifier("Quincaillerie Diallo", List.of(t, tiers("Boulangerie Moderne")));

        assertThat(r.qualite()).isEqualTo(QualiteClassification.PROBABLE);
        assertThat(r.tiers()).isSameAs(t);
        assertThat(r.confiance()).isLessThan(0.9);
    }

    @Test
    void deux_tiers_egalement_plausibles_donnent_ambigu_sans_choisir() {
        DocumentClassifier.Resultat r = DocumentClassifier.identifier("Dupont", List.of(tiers("Dupont Aluminium"), tiers("Dupont Verre")));

        assertThat(r.qualite()).isEqualTo(QualiteClassification.AMBIGU);
        assertThat(r.tiers()).isNull();
    }

    @Test
    void deux_tiers_de_meme_nom_normalise_sont_ambigus() {
        DocumentClassifier.Resultat r = DocumentClassifier.identifier("Cimenterie", List.of(tiers("Cimenterie SARL"), tiers("Cimenterie SA")));

        assertThat(r.qualite()).isEqualTo(QualiteClassification.AMBIGU);
    }

    @Test
    void aucun_tiers_ou_nom_vide_donne_inconnu() {
        assertThat(DocumentClassifier.identifier("Fournisseur Fantome", List.of(tiers("Cimenterie SARL"))).qualite()).isEqualTo(QualiteClassification.INCONNU);
        assertThat(DocumentClassifier.identifier("   ", List.of(tiers("Cimenterie SARL"))).qualite()).isEqualTo(QualiteClassification.INCONNU);
        assertThat(DocumentClassifier.identifier("SARL", List.of(tiers("Cimenterie SARL"))).qualite()).isEqualTo(QualiteClassification.INCONNU);
    }
}
