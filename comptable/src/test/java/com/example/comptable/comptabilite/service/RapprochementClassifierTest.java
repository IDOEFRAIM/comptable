package com.example.comptable.comptabilite.service;

import com.example.comptable.comptabilite.domain.EcritureComptable;
import com.example.comptable.comptabilite.domain.LigneEcriture;
import com.example.comptable.comptabilite.domain.LigneReleve;
import com.example.comptable.comptabilite.service.RapprochementClassifier.Classement;
import com.example.comptable.comptabilite.service.RapprochementClassifier.Statut;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/** Classification deterministe EXACT / PROBABLE / AMBIGUOUS / UNMATCHED : fonction pure, jamais de choix arbitraire. */
class RapprochementClassifierTest {

    private static final LocalDate JOUR = LocalDate.of(2027, 3, 10);

    private LigneReleve releve(String montant, LocalDate date) {
        LigneReleve ligne = new LigneReleve(date, "Virement", new BigDecimal(montant));
        ligne.setId(UUID.randomUUID());
        return ligne;
    }

    private LigneEcriture mouvement(String debit, String credit, LocalDate date) {
        EcritureComptable ecriture = new EcritureComptable();
        ecriture.setId(UUID.randomUUID());
        ecriture.setNumeroPiece("BQ-1");
        ecriture.setDateEcriture(date);
        LigneEcriture ligne = new LigneEcriture();
        ligne.setId(UUID.randomUUID());
        ligne.setEcriture(ecriture);
        ligne.setDebit(new BigDecimal(debit));
        ligne.setCredit(new BigDecimal(credit));
        return ligne;
    }

    private Statut statut(List<Classement> classements, LigneReleve ligne) {
        return classements.stream().filter(c -> c.ligne() == ligne).findFirst().orElseThrow().statut();
    }

    @Test
    void un_seul_mouvement_de_meme_montant_a_quelques_jours_est_exact() {
        LigneReleve ligne = releve("-150000", JOUR);
        List<Classement> r = RapprochementClassifier.classer(List.of(ligne), List.of(mouvement("0", "150000", JOUR.minusDays(3))), Set.of());

        assertThat(statut(r, ligne)).isEqualTo(Statut.EXACT);
        assertThat(r.get(0).candidats()).hasSize(1);
    }

    @Test
    void un_seul_mouvement_mais_date_eloignee_est_probable_donc_a_confirmer() {
        LigneReleve ligne = releve("-150000", JOUR);
        List<Classement> r = RapprochementClassifier.classer(List.of(ligne), List.of(mouvement("0", "150000", JOUR.minusDays(12))), Set.of());

        assertThat(statut(r, ligne)).isEqualTo(Statut.PROBABLE);
    }

    @Test
    void plusieurs_mouvements_de_meme_montant_sont_ambigus_et_tous_listes() {
        LigneReleve ligne = releve("100000", JOUR);
        List<Classement> r = RapprochementClassifier.classer(List.of(ligne),
                List.of(mouvement("100000", "0", JOUR), mouvement("100000", "0", JOUR.plusDays(1))), Set.of());

        assertThat(statut(r, ligne)).isEqualTo(Statut.AMBIGUOUS);
        assertThat(r.get(0).candidats()).hasSize(2);
    }

    @Test
    void un_meme_mouvement_revendique_par_deux_transactions_est_ambigu_pour_les_deux() {
        LigneReleve a = releve("100000", JOUR);
        LigneReleve b = releve("100000", JOUR);
        List<Classement> r = RapprochementClassifier.classer(List.of(a, b), List.of(mouvement("100000", "0", JOUR)), Set.of());

        assertThat(statut(r, a)).isEqualTo(Statut.AMBIGUOUS);
        assertThat(statut(r, b)).isEqualTo(Statut.AMBIGUOUS);
    }

    @Test
    void aucun_mouvement_de_meme_montant_ou_sens_oppose_est_sans_correspondance() {
        LigneReleve sortie = releve("-150000", JOUR);
        // meme montant mais cote debit : une entree d'argent, pas la sortie du releve
        List<Classement> r = RapprochementClassifier.classer(List.of(sortie), List.of(mouvement("150000", "0", JOUR)), Set.of());

        assertThat(statut(r, sortie)).isEqualTo(Statut.UNMATCHED);
        assertThat(r.get(0).candidats()).isEmpty();
    }

    @Test
    void un_mouvement_deja_pointe_n_est_jamais_candidat() {
        LigneReleve ligne = releve("-150000", JOUR);
        LigneEcriture pris = mouvement("0", "150000", JOUR);

        List<Classement> r = RapprochementClassifier.classer(List.of(ligne), List.of(pris), Set.of(pris.getId()));

        assertThat(statut(r, ligne)).isEqualTo(Statut.UNMATCHED);
    }
}
