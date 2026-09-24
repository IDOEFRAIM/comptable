package com.example.comptable.comptabilite.operation.regularisation;

import com.example.comptable.comptabilite.domain.CompteComptable;
import com.example.comptable.comptabilite.operation.common.ResultatRegle;
import com.example.comptable.comptabilite.provision.domain.Provision;
import com.example.comptable.comptabilite.provision.domain.TypeMouvementProvision;
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
class ProvisionRuleTest {

    @Mock
    private CompteComptableValidationService compteComptableValidationService;

    @InjectMocks
    private ProvisionRule rule;

    private Entreprise entreprise;
    private CompteComptable compteDotation;
    private CompteComptable compteProvision;
    private CompteComptable compteReprise;

    @BeforeEach
    void setUp() {
        entreprise = new Entreprise();
        entreprise.setId(UUID.randomUUID());

        compteDotation = compte("6815");
        compteProvision = compte("491");
        compteReprise = compte("7815");

        when(compteComptableValidationService.chargerCompteUtilisable(entreprise.getId(), compteDotation.getId(), true))
                .thenReturn(compteDotation);
        when(compteComptableValidationService.chargerCompteUtilisable(entreprise.getId(), compteProvision.getId(), true))
                .thenReturn(compteProvision);
        when(compteComptableValidationService.chargerCompteUtilisable(entreprise.getId(), compteReprise.getId(), true))
                .thenReturn(compteReprise);
    }

    private CompteComptable compte(String numero) {
        CompteComptable c = new CompteComptable();
        c.setId(UUID.randomUUID());
        c.setEntreprise(entreprise);
        c.setNumero(numero);
        return c;
    }

    private Provision provision(TypeMouvementProvision type, BigDecimal montantPrecedent, BigDecimal montantNouveau) {
        Provision p = new Provision();
        p.setEntreprise(entreprise);
        p.setNature("Litige client");
        p.setTypeMouvement(type);
        p.setMontantPrecedent(montantPrecedent);
        p.setMontantNouveau(montantNouveau);
        p.setCompteDotation(compteDotation);
        p.setCompteProvision(compteProvision);
        p.setCompteReprise(compteReprise);
        return p;
    }

    @Test
    void dotation_debite_la_charge_et_credite_la_provision_pour_laugmentation() {
        Provision p = provision(TypeMouvementProvision.DOTATION, new BigDecimal("1000"), new BigDecimal("3000"));

        ResultatRegle resultat = rule.construire(entreprise, p);

        assertThat(resultat.explications()).isEmpty();
        assertThat(resultat.lignes()).anySatisfy(l -> {
            assertThat(l.getCompte()).isEqualTo(compteDotation);
            assertThat(l.getDebit()).isEqualByComparingTo("2000");
        });
        assertThat(resultat.lignes()).anySatisfy(l -> {
            assertThat(l.getCompte()).isEqualTo(compteProvision);
            assertThat(l.getCredit()).isEqualByComparingTo("2000");
        });
    }

    @Test
    void reprise_totale_debite_la_provision_et_credite_la_reprise_pour_le_montant_precedent_entier() {
        Provision p = provision(TypeMouvementProvision.REPRISE_TOTALE, new BigDecimal("3000"), BigDecimal.ZERO);

        ResultatRegle resultat = rule.construire(entreprise, p);

        assertThat(resultat.lignes()).anySatisfy(l -> {
            assertThat(l.getCompte()).isEqualTo(compteProvision);
            assertThat(l.getDebit()).isEqualByComparingTo("3000");
        });
        assertThat(resultat.lignes()).anySatisfy(l -> {
            assertThat(l.getCompte()).isEqualTo(compteReprise);
            assertThat(l.getCredit()).isEqualByComparingTo("3000");
        });
    }

    @Test
    void reprise_partielle_ne_reprend_que_la_diminution() {
        Provision p = provision(TypeMouvementProvision.REPRISE_PARTIELLE, new BigDecimal("3000"), new BigDecimal("2000"));

        ResultatRegle resultat = rule.construire(entreprise, p);

        assertThat(resultat.lignes()).anySatisfy(l -> assertThat(l.getDebit()).isEqualByComparingTo("1000"));
        assertThat(resultat.lignes()).anySatisfy(l -> assertThat(l.getCredit()).isEqualByComparingTo("1000"));
    }

    @Test
    void dotation_sans_augmentation_reelle_ne_produit_aucune_ligne() {
        Provision p = provision(TypeMouvementProvision.DOTATION, new BigDecimal("3000"), new BigDecimal("3000"));

        ResultatRegle resultat = rule.construire(entreprise, p);

        assertThat(resultat.lignes()).isEmpty();
        assertThat(resultat.explications()).isNotEmpty();
    }
}
