package com.example.comptable.banque;

import com.example.comptable.banque.InternalTransferMatcher.Appariement;
import com.example.comptable.banque.InternalTransferMatcher.Niveau;
import com.example.comptable.comptabilite.domain.LigneReleve;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/** Appariement des deux jambes d'un virement interne : certain seulement avec une preuve, jamais « la premiere ». */
class InternalTransferMatcherTest {

    private static final UUID COMPTE_A = UUID.randomUUID();
    private static final UUID COMPTE_B = UUID.randomUUID();
    private static final LocalDate JOUR = LocalDate.of(2027, 3, 10);

    private static LigneReleve ligne(UUID compte, String libelle, int montant, LocalDate date, String reference) {
        LigneReleve l = new LigneReleve(date, libelle, new BigDecimal(montant));
        l.setId(UUID.randomUUID());
        l.setCompteId(compte);
        l.setReference(reference);
        return l;
    }

    @Test
    void libelleDeVirementInterne_memeMontant_comptesDifferents_estExact() {
        LigneReleve sortie = ligne(COMPTE_A, "VIREMENT INTERNE VERS BANQUE B", -50000, JOUR, null);
        LigneReleve entree = ligne(COMPTE_B, "VIREMENT INTERNE DE BANQUE A", 50000, JOUR.plusDays(1), null);

        List<Appariement> r = InternalTransferMatcher.apparier(List.of(sortie, entree), Set.of());

        assertThat(r).hasSize(1);
        assertThat(r.get(0).niveau()).isEqualTo(Niveau.EXACT);
        assertThat(r.get(0).sortie()).isSameAs(sortie);
        assertThat(r.get(0).entree()).isSameAs(entree);
        assertThat(r.get(0).explication()).contains("Les deux libelles evoquent un virement interne");
    }

    @Test
    void sansAucunePreuveDeTransfert_memeMontantEtDate_resteSeulementProbable() {
        List<Appariement> r = InternalTransferMatcher.apparier(List.of(ligne(COMPTE_A, "VIR 80000", -80000, JOUR, null),
                ligne(COMPTE_B, "VIR RECU 80000", 80000, JOUR, null)), Set.of());

        assertThat(r).singleElement().satisfies(a -> assertThat(a.niveau()).isEqualTo(Niveau.PROBABLE));
    }

    @Test
    void referenceCommune_estUnePreuve() {
        List<Appariement> r = InternalTransferMatcher.apparier(List.of(ligne(COMPTE_A, "VIR", -12000, JOUR, "REF-77881"),
                ligne(COMPTE_B, "VIR", 12000, JOUR, "ref 77881".replace(" ", "-"))), Set.of());

        assertThat(r.get(0).niveau()).isEqualTo(Niveau.EXACT);
    }

    @Test
    void dateTropEloignee_neSuffitPasPourEtreExact() {
        List<Appariement> r = InternalTransferMatcher.apparier(List.of(ligne(COMPTE_A, "VIREMENT INTERNE", -9000, JOUR, null),
                ligne(COMPTE_B, "VIREMENT INTERNE", 9000, JOUR.plusDays(3), null)), Set.of());

        assertThat(r.get(0).niveau()).isEqualTo(Niveau.PROBABLE); // 3 jours : dans la fenetre, mais pas assez pour etre certain
        assertThat(InternalTransferMatcher.apparier(List.of(ligne(COMPTE_A, "VIREMENT INTERNE", -9000, JOUR, null),
                ligne(COMPTE_B, "VIREMENT INTERNE", 9000, JOUR.plusDays(4), null)), Set.of())).isEmpty(); // hors fenetre
    }

    @Test
    void deuxEntreesPossibles_estAmbigu_etAucuneN_EstChoisie() {
        List<Appariement> r = InternalTransferMatcher.apparier(List.of(ligne(COMPTE_A, "VIREMENT INTERNE", -30000, JOUR, null),
                ligne(COMPTE_B, "VIREMENT INTERNE A", 30000, JOUR, null), ligne(COMPTE_B, "VIREMENT INTERNE B", 30000, JOUR, null)), Set.of());

        assertThat(r).hasSize(1);
        assertThat(r.get(0).niveau()).isEqualTo(Niveau.AMBIGUOUS);
        assertThat(r.get(0).entree()).isNull();
        assertThat(r.get(0).alternatives()).hasSize(2);
    }

    @Test
    void deuxSortiesPourUneEntree_estAmbigu() {
        List<Appariement> r = InternalTransferMatcher.apparier(List.of(ligne(COMPTE_A, "VIREMENT INTERNE", -30000, JOUR, null),
                ligne(COMPTE_A, "VIREMENT INTERNE", -30000, JOUR, null), ligne(COMPTE_B, "VIREMENT INTERNE", 30000, JOUR, null)), Set.of());

        assertThat(r).extracting(Appariement::niveau).containsOnly(Niveau.AMBIGUOUS);
    }

    @Test
    void memeCompte_nEstJamaisUnVirementInterne() {
        assertThat(InternalTransferMatcher.apparier(List.of(ligne(COMPTE_A, "VIREMENT INTERNE", -5000, JOUR, null),
                ligne(COMPTE_A, "VIREMENT INTERNE", 5000, JOUR, null)), Set.of())).isEmpty();
    }

    @Test
    void montantsDifferents_nonApparies() {
        assertThat(InternalTransferMatcher.apparier(List.of(ligne(COMPTE_A, "VIREMENT INTERNE", -5000, JOUR, null),
                ligne(COMPTE_B, "VIREMENT INTERNE", 5001, JOUR, null)), Set.of())).isEmpty();
    }

    @Test
    void unCoupleRejeteOuAnnule_neRevientJamais() {
        LigneReleve sortie = ligne(COMPTE_A, "VIREMENT INTERNE", -5000, JOUR, null);
        LigneReleve entree = ligne(COMPTE_B, "VIREMENT INTERNE", 5000, JOUR, null);

        assertThat(InternalTransferMatcher.apparier(List.of(sortie, entree), Set.of(sortie.getId() + "|" + entree.getId()))).isEmpty();
    }
}
