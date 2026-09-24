package com.example.comptable.comptabilite.operation.cloture;

import com.example.comptable.comptabilite.domain.CompteComptable;
import com.example.comptable.comptabilite.operation.common.ResultatRegle;
import com.example.comptable.comptabilite.operation.common.domain.AccountingOperationProposalLigne;
import com.example.comptable.comptabilite.parametrage.AccountResolutionSource;
import com.example.comptable.comptabilite.parametrage.AccountResolutionStatus;
import com.example.comptable.comptabilite.parametrage.AccountingAccountResolver;
import com.example.comptable.comptabilite.parametrage.NiveauConfiance;
import com.example.comptable.comptabilite.parametrage.ResolvedAccount;
import com.example.comptable.comptabilite.parametrage.domain.AccountPurpose;
import com.example.comptable.comptabilite.service.CompteComptableValidationService;
import com.example.comptable.entreprise.domain.Entreprise;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ClotureRuleTest {

    @Mock
    private AccountingAccountResolver accountingAccountResolver;
    @Mock
    private CompteComptableValidationService compteComptableValidationService;

    @InjectMocks
    private ClotureRule clotureRule;

    private Entreprise entreprise;
    private CompteComptable compte120;
    private CompteComptable compte129;

    @BeforeEach
    void setUp() {
        entreprise = new Entreprise();
        entreprise.setId(UUID.randomUUID());

        compte120 = new CompteComptable();
        compte120.setId(UUID.randomUUID());
        compte120.setNumero("120");

        compte129 = new CompteComptable();
        compte129.setId(UUID.randomUUID());
        compte129.setNumero("129");
    }

    private ResolvedAccount resolu(CompteComptable compte) {
        return new ResolvedAccount(AccountResolutionStatus.RESOLVED, compte.getId(), compte.getNumero(), compte.getIntitule(),
                AccountResolutionSource.ROLE_SYSTEME, NiveauConfiance.CERTAIN, "resolu (test)");
    }

    private void mockerResolution(AccountPurpose purpose, CompteComptable compte) {
        when(accountingAccountResolver.resolve(eq(entreprise.getId()), eq(purpose), any(), isNull())).thenReturn(resolu(compte));
        when(compteComptableValidationService.chargerCompteUtilisable(entreprise.getId(), compte.getId(), true)).thenReturn(compte);
    }

    @Test
    void credite_le_compte_resultat_benefice_resolu() {
        mockerResolution(AccountPurpose.RESULTAT_BENEFICE, compte120);

        ResultatRegle resultat = clotureRule.construire(entreprise, List.of(), new BigDecimal("10000"));

        assertThat(resultat.explications()).isEmpty();
        assertThat(resultat.lignes()).hasSize(1);
        AccountingOperationProposalLigne ligne = resultat.lignes().get(0);
        assertThat(ligne.getCompte()).isEqualTo(compte120);
        assertThat(ligne.getCredit()).isEqualByComparingTo("10000");
        assertThat(ligne.getDebit()).isEqualByComparingTo("0");
    }

    @Test
    void debite_le_compte_resultat_perte_resolu() {
        mockerResolution(AccountPurpose.RESULTAT_PERTE, compte129);

        ResultatRegle resultat = clotureRule.construire(entreprise, List.of(), new BigDecimal("-5000"));

        assertThat(resultat.explications()).isEmpty();
        AccountingOperationProposalLigne ligne = resultat.lignes().get(0);
        assertThat(ligne.getCompte()).isEqualTo(compte129);
        assertThat(ligne.getDebit()).isEqualByComparingTo("5000");
        assertThat(ligne.getCredit()).isEqualByComparingTo("0");
    }

    @Test
    void produit_une_explication_si_aucun_compte_resultat_configure() {
        when(accountingAccountResolver.resolve(eq(entreprise.getId()), eq(AccountPurpose.RESULTAT_BENEFICE), any(), isNull()))
                .thenReturn(ResolvedAccount.nonConfigure("aucun mapping ni role systeme (test)"));

        ResultatRegle resultat = clotureRule.construire(entreprise, List.of(), new BigDecimal("10000"));

        assertThat(resultat.lignes()).isEmpty();
        assertThat(resultat.explications()).isNotEmpty();
    }

    @Test
    void combine_les_lignes_a_solder_deja_fournies_avec_la_ligne_de_resultat() {
        mockerResolution(AccountPurpose.RESULTAT_BENEFICE, compte120);
        CompteComptable compteCharge = new CompteComptable();
        compteCharge.setId(UUID.randomUUID());
        compteCharge.setNumero("601");
        List<AccountingOperationProposalLigne> lignesASolder = List.of(
                AccountingOperationProposalLigne.credit(compteCharge, new BigDecimal("30000"), "Solde de cloture"));

        ResultatRegle resultat = clotureRule.construire(entreprise, lignesASolder, new BigDecimal("10000"));

        assertThat(resultat.lignes()).hasSize(2);
    }
}
