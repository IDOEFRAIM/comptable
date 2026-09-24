package com.example.comptable.comptabilite.operation.regularisation;

import com.example.comptable.comptabilite.depreciation.domain.Depreciation;
import com.example.comptable.comptabilite.depreciation.domain.ObjetDepreciation;
import com.example.comptable.comptabilite.domain.CompteComptable;
import com.example.comptable.comptabilite.operation.common.ResultatRegle;
import com.example.comptable.comptabilite.service.CompteComptableValidationService;
import com.example.comptable.entreprise.domain.Entreprise;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class DepreciationRuleTest {

    @Mock
    private CompteComptableValidationService compteComptableValidationService;

    @InjectMocks
    private DepreciationRule rule;

    private Entreprise entreprise;
    private CompteComptable compteDotation;
    private CompteComptable compteDepreciation;

    @BeforeEach
    void setUp() {
        entreprise = new Entreprise();
        entreprise.setId(UUID.randomUUID());

        compteDotation = compte("6817");
        compteDepreciation = compte("491");
    }

    private CompteComptable compte(String numero) {
        CompteComptable c = new CompteComptable();
        c.setId(UUID.randomUUID());
        c.setEntreprise(entreprise);
        c.setNumero(numero);
        return c;
    }

    private Depreciation depreciation(BigDecimal valeurComptable, BigDecimal valeurRecouvrable) {
        Depreciation d = new Depreciation();
        d.setEntreprise(entreprise);
        d.setObjetType(ObjetDepreciation.CREANCE);
        d.setObjetId(UUID.randomUUID());
        d.setValeurComptable(valeurComptable);
        d.setValeurRecouvrable(valeurRecouvrable);
        d.setCompteDotation(compteDotation);
        d.setCompteDepreciation(compteDepreciation);
        return d;
    }

    @Test
    void debite_la_dotation_et_credite_la_depreciation_pour_la_perte_constatee() {
        when(compteComptableValidationService.chargerCompteUtilisable(entreprise.getId(), compteDotation.getId(), true))
                .thenReturn(compteDotation);
        when(compteComptableValidationService.chargerCompteUtilisable(entreprise.getId(), compteDepreciation.getId(), true))
                .thenReturn(compteDepreciation);
        Depreciation d = depreciation(new BigDecimal("10000"), new BigDecimal("6000"));

        ResultatRegle resultat = rule.construire(entreprise, d);

        assertThat(resultat.explications()).isEmpty();
        assertThat(resultat.lignes()).anySatisfy(l -> {
            assertThat(l.getCompte()).isEqualTo(compteDotation);
            assertThat(l.getDebit()).isEqualByComparingTo("4000");
        });
        assertThat(resultat.lignes()).anySatisfy(l -> {
            assertThat(l.getCompte()).isEqualTo(compteDepreciation);
            assertThat(l.getCredit()).isEqualByComparingTo("4000");
        });
    }

    @Test
    void aucune_perte_constatee_ne_produit_aucune_ligne() {
        Depreciation d = depreciation(new BigDecimal("6000"), new BigDecimal("6000"));

        ResultatRegle resultat = rule.construire(entreprise, d);

        assertThat(resultat.lignes()).isEmpty();
        assertThat(resultat.explications()).isNotEmpty();
    }

    @Test
    void valeur_recouvrable_superieure_ne_produit_aucune_ligne() {
        Depreciation d = depreciation(new BigDecimal("6000"), new BigDecimal("8000"));

        ResultatRegle resultat = rule.construire(entreprise, d);

        assertThat(resultat.lignes()).isEmpty();
    }
}
