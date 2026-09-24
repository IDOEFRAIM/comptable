package com.example.comptable.comptabilite.operation.regularisation;

import com.example.comptable.comptabilite.domain.CompteComptable;
import com.example.comptable.comptabilite.domain.ExerciceComptable;
import com.example.comptable.comptabilite.operation.common.ResultatRegle;
import com.example.comptable.comptabilite.regularisation.domain.Regularisation;
import com.example.comptable.comptabilite.regularisation.domain.RegularisationType;
import com.example.comptable.comptabilite.service.CompteComptableValidationService;
import com.example.comptable.entreprise.domain.Entreprise;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class RegularisationRuleTest {

    @Mock
    private CompteComptableValidationService compteComptableValidationService;

    @InjectMocks
    private RegularisationRule rule;

    private Entreprise entreprise;
    private CompteComptable compteOrigine;
    private CompteComptable compteContrepartie;

    @BeforeEach
    void setUp() {
        entreprise = new Entreprise();
        entreprise.setId(UUID.randomUUID());

        compteOrigine = compte("601");
        compteContrepartie = compte("476");

        when(compteComptableValidationService.chargerCompteUtilisable(entreprise.getId(), compteOrigine.getId(), true))
                .thenReturn(compteOrigine);
        when(compteComptableValidationService.chargerCompteUtilisable(entreprise.getId(), compteContrepartie.getId(), true))
                .thenReturn(compteContrepartie);
    }

    private CompteComptable compte(String numero) {
        CompteComptable c = new CompteComptable();
        c.setId(UUID.randomUUID());
        c.setEntreprise(entreprise);
        c.setNumero(numero);
        return c;
    }

    private Regularisation regularisation(RegularisationType type, BigDecimal montant, BigDecimal montantFutur) {
        Regularisation r = new Regularisation();
        r.setEntreprise(entreprise);
        ExerciceComptable exercice = new ExerciceComptable();
        exercice.setDateDebut(LocalDate.of(2026, 1, 1));
        exercice.setDateFin(LocalDate.of(2026, 12, 31));
        r.setExercice(exercice);
        r.setType(type);
        r.setDateOrigine(LocalDate.of(2026, 11, 1));
        r.setMontant(montant);
        r.setMontantExercice(montant.subtract(montantFutur));
        r.setMontantFutur(montantFutur);
        r.setLibelle("Assurance annuelle");
        r.setCompteOrigine(compteOrigine);
        r.setCompteContrepartie(compteContrepartie);
        return r;
    }

    @Test
    void cca_debite_la_contrepartie_et_credite_lorigine_pour_la_part_future() {
        Regularisation r = regularisation(RegularisationType.CHARGE_CONSTATEE_AVANCE, new BigDecimal("1200"), new BigDecimal("800"));

        ResultatRegle resultat = rule.construire(entreprise, r);

        assertThat(resultat.explications()).isEmpty();
        assertThat(resultat.lignes()).anySatisfy(l -> {
            assertThat(l.getCompte()).isEqualTo(compteContrepartie);
            assertThat(l.getDebit()).isEqualByComparingTo("800");
        });
        assertThat(resultat.lignes()).anySatisfy(l -> {
            assertThat(l.getCompte()).isEqualTo(compteOrigine);
            assertThat(l.getCredit()).isEqualByComparingTo("800");
        });
    }

    @Test
    void pca_debite_lorigine_et_credite_la_contrepartie_pour_la_part_future() {
        Regularisation r = regularisation(RegularisationType.PRODUIT_CONSTATE_AVANCE, new BigDecimal("1200"), new BigDecimal("800"));

        ResultatRegle resultat = rule.construire(entreprise, r);

        assertThat(resultat.lignes()).anySatisfy(l -> {
            assertThat(l.getCompte()).isEqualTo(compteOrigine);
            assertThat(l.getDebit()).isEqualByComparingTo("800");
        });
        assertThat(resultat.lignes()).anySatisfy(l -> {
            assertThat(l.getCompte()).isEqualTo(compteContrepartie);
            assertThat(l.getCredit()).isEqualByComparingTo("800");
        });
    }

    @Test
    void produit_a_reporter_partage_la_mecanique_du_pca() {
        Regularisation pca = regularisation(RegularisationType.PRODUIT_CONSTATE_AVANCE, new BigDecimal("1200"), new BigDecimal("800"));
        Regularisation par = regularisation(RegularisationType.PRODUIT_A_REPORTER, new BigDecimal("1200"), new BigDecimal("800"));

        ResultatRegle resultatPca = rule.construire(entreprise, pca);
        ResultatRegle resultatPar = rule.construire(entreprise, par);

        assertThat(resultatPar.lignes()).hasSameSizeAs(resultatPca.lignes());
        assertThat(resultatPar.lignes().get(0).getDebit()).isEqualByComparingTo(resultatPca.lignes().get(0).getDebit());
        assertThat(resultatPar.lignes().get(0).getCompte()).isEqualTo(resultatPca.lignes().get(0).getCompte());
    }

    @Test
    void fnp_debite_la_charge_et_credite_le_compte_dattente_pour_le_montant_entier() {
        Regularisation r = regularisation(RegularisationType.FACTURE_NON_PARVENUE, new BigDecimal("5000"), BigDecimal.ZERO);

        ResultatRegle resultat = rule.construire(entreprise, r);

        assertThat(resultat.lignes()).anySatisfy(l -> {
            assertThat(l.getCompte()).isEqualTo(compteOrigine);
            assertThat(l.getDebit()).isEqualByComparingTo("5000");
        });
        assertThat(resultat.lignes()).anySatisfy(l -> {
            assertThat(l.getCompte()).isEqualTo(compteContrepartie);
            assertThat(l.getCredit()).isEqualByComparingTo("5000");
        });
    }

    @Test
    void charge_a_payer_partage_la_mecanique_du_fnp() {
        Regularisation r = regularisation(RegularisationType.CHARGE_A_PAYER, new BigDecimal("3000"), BigDecimal.ZERO);

        ResultatRegle resultat = rule.construire(entreprise, r);

        assertThat(resultat.lignes()).anySatisfy(l -> assertThat(l.getDebit()).isEqualByComparingTo("3000"));
    }

    @Test
    void produit_a_recevoir_debite_lattente_et_credite_le_produit_pour_le_montant_entier() {
        Regularisation r = regularisation(RegularisationType.PRODUIT_A_RECEVOIR, new BigDecimal("4000"), BigDecimal.ZERO);

        ResultatRegle resultat = rule.construire(entreprise, r);

        assertThat(resultat.lignes()).anySatisfy(l -> {
            assertThat(l.getCompte()).isEqualTo(compteContrepartie);
            assertThat(l.getDebit()).isEqualByComparingTo("4000");
        });
        assertThat(resultat.lignes()).anySatisfy(l -> {
            assertThat(l.getCompte()).isEqualTo(compteOrigine);
            assertThat(l.getCredit()).isEqualByComparingTo("4000");
        });
    }

    @Test
    void aucune_ligne_si_le_montant_a_regulariser_est_nul() {
        Regularisation r = regularisation(RegularisationType.CHARGE_CONSTATEE_AVANCE, new BigDecimal("1200"), BigDecimal.ZERO);

        ResultatRegle resultat = rule.construire(entreprise, r);

        assertThat(resultat.lignes()).isEmpty();
        assertThat(resultat.explications()).isNotEmpty();
    }

    @Test
    void compte_inactif_produit_une_explication_plutot_quune_ligne_incorrecte() {
        Regularisation r = regularisation(RegularisationType.FACTURE_NON_PARVENUE, new BigDecimal("5000"), BigDecimal.ZERO);
        org.mockito.Mockito.reset(compteComptableValidationService);
        when(compteComptableValidationService.chargerCompteUtilisable(entreprise.getId(), compteOrigine.getId(), true))
                .thenThrow(new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY, "Le compte 601 est desactive"));

        ResultatRegle resultat = rule.construire(entreprise, r);

        assertThat(resultat.lignes()).isEmpty();
        assertThat(resultat.explications()).isNotEmpty();
    }

    @Test
    void la_reprise_inverse_le_sens_de_lecriture_dorigine() {
        Regularisation r = regularisation(RegularisationType.FACTURE_NON_PARVENUE, new BigDecimal("5000"), BigDecimal.ZERO);

        ResultatRegle reprise = rule.construireReprise(entreprise, r);

        assertThat(reprise.lignes()).anySatisfy(l -> {
            assertThat(l.getCompte()).isEqualTo(compteOrigine);
            assertThat(l.getCredit()).isEqualByComparingTo("5000");
        });
        assertThat(reprise.lignes()).anySatisfy(l -> {
            assertThat(l.getCompte()).isEqualTo(compteContrepartie);
            assertThat(l.getDebit()).isEqualByComparingTo("5000");
        });
    }
}
