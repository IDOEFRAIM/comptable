package com.example.comptable.comptabilite.service;

import com.example.comptable.comptabilite.domain.ExerciceComptable;
import com.example.comptable.comptabilite.domain.StatutExercice;
import com.example.comptable.comptabilite.repository.ExerciceComptableRepository;
import org.junit.jupiter.api.Test;
import org.springframework.web.server.ResponseStatusException;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** Phase 1, lot 1.8 : les deux regles mutualisees, avec leurs politiques de tolerance distinctes. */
class RegleCommuneControleTest {

    // ------------------------------------------------------------ CoherenceMontants

    @Test
    void montants_coherents_ne_produisent_aucun_ecart() {
        assertThat(CoherenceMontants.verifier(new BigDecimal("100000"), new BigDecimal("18000"), new BigDecimal("118000"), BigDecimal.ZERO)).isEmpty();
    }

    @Test
    void l_explication_cite_toutes_les_valeurs_comme_dans_l_exemple_de_la_mission() {
        var ecart = CoherenceMontants.verifier(new BigDecimal("100000"), new BigDecimal("16500"), new BigDecimal("118000"), BigDecimal.ZERO);

        assertThat(ecart).isPresent();
        assertThat(ecart.get().somme()).isEqualByComparingTo("116500");
        assertThat(ecart.get().ecart()).isEqualByComparingTo("1500");
        assertThat(ecart.get().explication())
                .contains("HT + TVA").contains("100000 + 16500 = 116500").contains("TTC du document (118000)").contains("ecart de 1500");
    }

    @Test
    void la_tolerance_est_une_politique_de_l_appelant() {
        BigDecimal ht = new BigDecimal("100.00");
        BigDecimal tva = new BigDecimal("18.00");
        BigDecimal ttc = new BigDecimal("118.01");

        assertThat(CoherenceMontants.verifier(ht, tva, ttc, BigDecimal.ZERO)).isPresent();          // flux Documents : exact
        assertThat(CoherenceMontants.verifier(ht, tva, ttc, new BigDecimal("0.02"))).isEmpty();      // pipeline : arrondi tolere
        assertThat(CoherenceMontants.verifier(ht, tva, new BigDecimal("118.05"), new BigDecimal("0.02"))).isPresent();
    }

    @Test
    void tva_absente_vaut_zero_et_montants_incomplets_ne_sont_pas_une_incoherence_arithmetique() {
        assertThat(CoherenceMontants.verifier(new BigDecimal("1000"), null, new BigDecimal("1000"), BigDecimal.ZERO)).isEmpty();
        assertThat(CoherenceMontants.verifier(new BigDecimal("1"), null, new BigDecimal("637259"), BigDecimal.ZERO)).isPresent();
        assertThat(CoherenceMontants.verifier(null, new BigDecimal("1"), new BigDecimal("1"), BigDecimal.ZERO)).isEmpty();
        assertThat(CoherenceMontants.verifier(new BigDecimal("1"), null, null, BigDecimal.ZERO)).isEmpty();
    }

    // ------------------------------------------------------------ ExerciceOuvertGuard

    @Test
    void exercice_ouvert_est_renvoye() {
        ExerciceComptableRepository repository = mock(ExerciceComptableRepository.class);
        UUID entrepriseId = UUID.randomUUID();
        LocalDate date = LocalDate.of(2026, 5, 1);
        ExerciceComptable ouvert = new ExerciceComptable();
        ouvert.setStatut(StatutExercice.OUVERT);
        when(repository.findByEntrepriseIdAndDate(entrepriseId, date)).thenReturn(Optional.of(ouvert));

        assertThat(ExerciceOuvertGuard.exigerOuvert(repository, entrepriseId, date)).isSameAs(ouvert);
    }

    @Test
    void exercice_absent_ou_cloture_donne_422_avec_un_message_explicite() {
        ExerciceComptableRepository repository = mock(ExerciceComptableRepository.class);
        UUID entrepriseId = UUID.randomUUID();
        LocalDate date = LocalDate.of(2020, 5, 1);

        assertThatThrownBy(() -> ExerciceOuvertGuard.exigerOuvert(repository, entrepriseId, date))
                .isInstanceOfSatisfying(ResponseStatusException.class, e -> {
                    assertThat(e.getStatusCode().value()).isEqualTo(422);
                    assertThat(e.getReason()).isEqualTo("Aucun exercice comptable ne couvre la date 2020-05-01");
                });

        ExerciceComptable cloture = new ExerciceComptable();
        cloture.setStatut(StatutExercice.CLOTURE);
        when(repository.findByEntrepriseIdAndDate(entrepriseId, date)).thenReturn(Optional.of(cloture));
        assertThatThrownBy(() -> ExerciceOuvertGuard.exigerOuvert(repository, entrepriseId, date))
                .isInstanceOfSatisfying(ResponseStatusException.class, e -> {
                    assertThat(e.getStatusCode().value()).isEqualTo(422);
                    assertThat(e.getReason()).isEqualTo("L'exercice couvrant cette date est cloture");
                });
    }
}
