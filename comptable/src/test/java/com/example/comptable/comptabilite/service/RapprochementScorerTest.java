package com.example.comptable.comptabilite.service;

import com.example.comptable.comptabilite.domain.CompteComptable;
import com.example.comptable.comptabilite.domain.EcritureComptable;
import com.example.comptable.comptabilite.domain.LigneEcriture;
import com.example.comptable.comptabilite.domain.LigneReleve;
import com.example.comptable.comptabilite.service.RapprochementClassifier.Statut;
import com.example.comptable.comptabilite.service.RapprochementScorer.Candidat;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/** Score EXPLICABLE : chaque point a une raison lisible ; la reference departage legitimement, jamais « le premier ». */
class RapprochementScorerTest {

    private static final LocalDate JOUR = LocalDate.of(2027, 1, 10);

    private static LigneEcriture mouvement(String piece, String libelle, int credit, LocalDate date) {
        EcritureComptable e = new EcritureComptable();
        e.setId(UUID.randomUUID());
        e.setNumeroPiece(piece);
        e.setLibelle(libelle);
        e.setDateEcriture(date);
        LigneEcriture l = new LigneEcriture();
        l.setId(UUID.randomUUID());
        l.setEcriture(e);
        l.setCompte(new CompteComptable());
        l.setDebit(BigDecimal.ZERO);
        l.setCredit(new BigDecimal(credit));
        l.setLibelle(libelle);
        return l;
    }

    private static LigneReleve transaction(String libelle, int montant, LocalDate date) {
        LigneReleve t = new LigneReleve(date, libelle, new BigDecimal(montant));
        t.setId(UUID.randomUUID());
        return t;
    }

    @Test
    void montantSensEtDateMemeJour_donnentSoixantecinqPoints_etChaquePointSExplique() {
        LigneReleve t = transaction("VIR DIVERS", -5000, JOUR);
        Candidat c = RapprochementScorer.noter(t, mouvement("BQ-1", "Reglement", 5000, JOUR));

        assertThat(c.score()).isEqualTo(65);
        assertThat(c.criteres()).extracting(RapprochementScorer.Critere::code).contains("MONTANT", "SENS", "DATE", "REFERENCE", "TIERS", "LIBELLE");
        assertThat(c.criteres().stream().filter(RapprochementScorer.Critere::satisfait).mapToInt(RapprochementScorer.Critere::points).sum()).isEqualTo(65);
        assertThat(c.explication()).contains("Montant exact").contains("Meme date").contains("Reference absente");
    }

    @Test
    void referenceTiersEtLibelle_font_monterLeScore() {
        LigneReleve t = transaction("VIR RECU DE STATION XYZ REF FAC-2026-184", -5000, JOUR);
        Candidat c = RapprochementScorer.noter(t, mouvement("BQ-2", "Reglement Station XYZ facture FAC-2026-184", 5000, JOUR));

        assertThat(c.score()).isEqualTo(100);
        assertThat(c.aLaReference()).isTrue();
        assertThat(c.explication()).contains("Reference FAC-2026-184 retrouvee").contains("Meme tiers");
    }

    @Test
    void ecartDeDate_reduitLesPoints() {
        LigneReleve t = transaction("VIR", -5000, JOUR);

        assertThat(RapprochementScorer.noter(t, mouvement("A", "x", 5000, JOUR.plusDays(2))).score()).isEqualTo(65);
        assertThat(RapprochementScorer.noter(t, mouvement("B", "x", 5000, JOUR.plusDays(4))).score()).isEqualTo(60);
        assertThat(RapprochementScorer.noter(t, mouvement("C", "x", 5000, JOUR.plusDays(12))).score()).isEqualTo(55);
        assertThat(RapprochementScorer.noter(t, mouvement("D", "x", 5000, JOUR.plusDays(40))).score()).isEqualTo(50);
    }

    @Test
    void deuxMouvementsIdentiques_sansReference_restentAmbigus_etAucunN_EstChoisi() {
        LigneReleve t = transaction("VIR DIVERS", -5000, JOUR);
        List<RapprochementScorer.ClassementNote> notes = RapprochementScorer.classer(List.of(t),
                List.of(mouvement("BQ-1", "Reglement A", 5000, JOUR), mouvement("BQ-2", "Reglement B", 5000, JOUR)), Set.of());

        assertThat(notes.get(0).statut()).isEqualTo(Statut.AMBIGUOUS);
        assertThat(notes.get(0).candidats()).hasSize(2);
    }

    @Test
    void deuxMouvementsIdentiques_unSeulPorteLaReference_departageLegitimement() {
        LigneReleve t = transaction("VIR STATION XYZ REF FAC-2026-184", -5000, JOUR);
        LigneEcriture bon = mouvement("BQ-1", "Reglement FAC-2026-184", 5000, JOUR);
        List<RapprochementScorer.ClassementNote> notes = RapprochementScorer.classer(List.of(t),
                List.of(bon, mouvement("BQ-2", "Reglement FAC-2026-185", 5000, JOUR)), Set.of());

        assertThat(notes.get(0).statut()).isEqualTo(Statut.EXACT);
        assertThat(notes.get(0).meilleur().ligne()).isSameAs(bon);
        assertThat(notes.get(0).candidats()).hasSize(1);
    }

    @Test
    void deuxTransactionsDesignentLeMemeMouvementParReference_l_ambiguiteResteEntiere() {
        LigneEcriture bon = mouvement("BQ-1", "Reglement FAC-2026-184", 5000, JOUR);
        LigneEcriture autre = mouvement("BQ-2", "Reglement FAC-2026-185", 5000, JOUR);
        LigneReleve t1 = transaction("VIR REF FAC-2026-184", -5000, JOUR);
        LigneReleve t2 = transaction("VIR REF FAC-2026-184", -5000, JOUR);

        var notes = RapprochementScorer.classer(List.of(t1, t2), List.of(bon, autre), Set.of());

        assertThat(notes).extracting(RapprochementScorer.ClassementNote::statut).containsOnly(Statut.AMBIGUOUS);
    }

    @Test
    void rechercheLibre_noteMemeUnMouvementDeMontantDifferent() {
        LigneReleve t = transaction("VIR", -5000, JOUR);
        Candidat c = RapprochementScorer.noterLibre(t, mouvement("BQ-9", "autre", 4000, JOUR));

        assertThat(c.score()).isLessThan(50);
        assertThat(c.criteres().get(0).satisfait()).isFalse();
        assertThat(c.explication()).contains("Montant different");
    }

    @Test
    void fraisBancaires_faceAUnReglementSansLienDeLibelle_nEstJamaisAutomatique() {
        LigneReleve frais = transaction("FRAIS TENUE DE COMPTE", -1500, JOUR);
        List<RapprochementScorer.ClassementNote> notes = RapprochementScorer.classer(List.of(frais),
                List.of(mouvement("BQ-7", "Reglement facture fournisseur", 1500, JOUR)), Set.of());

        assertThat(notes.get(0).statut()).isEqualTo(Statut.PROBABLE); // propose, jamais confirme seul
        assertThat(notes.get(0).meilleur().explication()).contains("frais bancaires");
    }

    @Test
    void fraisBancaires_faceAUneEcritureDeFrais_resteCertain() {
        LigneReleve frais = transaction("FRAIS TENUE DE COMPTE", -1500, JOUR);
        List<RapprochementScorer.ClassementNote> notes = RapprochementScorer.classer(List.of(frais),
                List.of(mouvement("BQ-8", "Frais de tenue de compte octobre", 1500, JOUR)), Set.of());

        assertThat(notes.get(0).statut()).isEqualTo(Statut.EXACT);
    }

    @Test
    void retraitEnEspeces_neSeConfondJamaisAvecUnReglementDeMemeMontant() {
        LigneReleve retrait = transaction("RETRAIT DAB PLATEAU", -50000, JOUR);
        List<RapprochementScorer.ClassementNote> notes = RapprochementScorer.classer(List.of(retrait),
                List.of(mouvement("BQ-9", "Reglement fournisseur", 50000, JOUR)), Set.of());

        assertThat(notes.get(0).statut()).isEqualTo(Statut.PROBABLE);
    }
}
