package com.example.comptable.comptabilite.operation.immobilisation;

import com.example.comptable.comptabilite.domain.CompteComptable;
import com.example.comptable.comptabilite.domain.Immobilisation;
import com.example.comptable.comptabilite.domain.Tiers;
import com.example.comptable.comptabilite.domain.TypeImmobilisation;
import com.example.comptable.comptabilite.domain.TypeJournal;
import com.example.comptable.comptabilite.operation.common.ResultatRegle;
import com.example.comptable.comptabilite.parametrage.AccountingAccountResolver;
import com.example.comptable.comptabilite.parametrage.AccountingAccountResolver.CompteResolu;
import com.example.comptable.comptabilite.parametrage.ResolvedAccount;
import com.example.comptable.comptabilite.parametrage.domain.AccountPurpose;
import com.example.comptable.entreprise.domain.Entreprise;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** Phase A : acquisition et dotation demandent leurs comptes par purpose ; aucune ligne si un compte n'est pas resolu. */
@ExtendWith(MockitoExtension.class)
class ImmobilisationRuleTest {

    @Mock
    private AccountingAccountResolver accountingAccountResolver;

    private ImmobilisationRule rule;
    private Entreprise entreprise;
    private Immobilisation immobilisation;
    private Tiers fournisseur;
    private CompteComptable actif;
    private CompteComptable amortissement;
    private CompteComptable compteFournisseur;
    private CompteComptable tva;
    private CompteComptable dotation;

    @BeforeEach
    void setUp() {
        rule = new ImmobilisationRule(accountingAccountResolver);
        entreprise = new Entreprise();
        entreprise.setId(UUID.randomUUID());
        entreprise.setPays("CI");
        actif = compte("ACTIF");
        amortissement = compte("AMORT");
        compteFournisseur = compte("FOURN");
        tva = compte("TVA");
        dotation = compte("DOTATION");

        immobilisation = new Immobilisation();
        immobilisation.setId(UUID.randomUUID());
        immobilisation.setEntreprise(entreprise);
        immobilisation.setLibelle("Camion");
        immobilisation.setType(TypeImmobilisation.CORPORELLE);
        immobilisation.setValeurOrigine(new BigDecimal("5000000"));
        immobilisation.setCompte(actif);
        immobilisation.setCompteAmortissement(amortissement);

        fournisseur = new Tiers();
        fournisseur.setId(UUID.randomUUID());
        fournisseur.setEntreprise(entreprise);
        fournisseur.setNom("Garage");
        fournisseur.setCompteComptable(compteFournisseur);
    }

    private CompteComptable compte(String numero) {
        CompteComptable c = new CompteComptable();
        c.setId(UUID.randomUUID());
        c.setNumero(numero);
        return c;
    }

    private void resoudre(AccountPurpose purpose, UUID explicite, CompteResolu resultat) {
        when(accountingAccountResolver.resoudreCompte(eq(entreprise.getId()), eq(purpose), any(),
                explicite == null ? isNull() : eq(explicite))).thenReturn(resultat);
    }

    @Test
    void acquisition_debite_l_actif_et_la_tva_recuperable_et_credite_le_fournisseur_ttc() {
        resoudre(AccountPurpose.IMMOBILISATION_CORPORELLE, actif.getId(), new CompteResolu(actif, null));
        resoudre(AccountPurpose.FOURNISSEUR, compteFournisseur.getId(), new CompteResolu(compteFournisseur, null));
        resoudre(AccountPurpose.TVA_RECUPERABLE, null, new CompteResolu(tva, null));

        ResultatRegle resultat = rule.construireAcquisition(entreprise, immobilisation, fournisseur, new BigDecimal("900000"), "Acquisition");

        assertThat(resultat.explications()).isEmpty();
        assertThat(resultat.typeJournalAttendu()).isEqualTo(TypeJournal.ACHATS);
        assertThat(resultat.lignes()).hasSize(3);
        assertThat(resultat.lignes()).anySatisfy(l -> {
            assertThat(l.getCompte()).isSameAs(actif);
            assertThat(l.getDebit()).isEqualByComparingTo("5000000");
        });
        assertThat(resultat.lignes()).anySatisfy(l -> {
            assertThat(l.getCompte()).isSameAs(tva);
            assertThat(l.getDebit()).isEqualByComparingTo("900000");
        });
        assertThat(resultat.lignes()).anySatisfy(l -> {
            assertThat(l.getCompte()).isSameAs(compteFournisseur);
            assertThat(l.getCredit()).isEqualByComparingTo("5900000");
            assertThat(l.getTiers()).isSameAs(fournisseur);
        });
    }

    @Test
    void acquisition_sans_tva_ne_demande_aucun_compte_de_tva() {
        resoudre(AccountPurpose.IMMOBILISATION_CORPORELLE, actif.getId(), new CompteResolu(actif, null));
        resoudre(AccountPurpose.FOURNISSEUR, compteFournisseur.getId(), new CompteResolu(compteFournisseur, null));

        ResultatRegle resultat = rule.construireAcquisition(entreprise, immobilisation, fournisseur, BigDecimal.ZERO, "Acquisition");

        assertThat(resultat.lignes()).hasSize(2);
        verify(accountingAccountResolver, never()).resoudreCompte(any(), eq(AccountPurpose.TVA_RECUPERABLE), any(), any());
    }

    @Test
    void une_immobilisation_incorporelle_demande_le_purpose_incorporelle() {
        immobilisation.setType(TypeImmobilisation.INCORPORELLE);
        resoudre(AccountPurpose.IMMOBILISATION_INCORPORELLE, actif.getId(), new CompteResolu(actif, null));
        resoudre(AccountPurpose.FOURNISSEUR, compteFournisseur.getId(), new CompteResolu(compteFournisseur, null));

        assertThat(rule.construireAcquisition(entreprise, immobilisation, fournisseur, BigDecimal.ZERO, "Logiciel").explications()).isEmpty();
    }

    @Test
    void tva_non_configuree_donne_une_explication_et_aucune_ligne() {
        resoudre(AccountPurpose.IMMOBILISATION_CORPORELLE, actif.getId(), new CompteResolu(actif, null));
        resoudre(AccountPurpose.FOURNISSEUR, compteFournisseur.getId(), new CompteResolu(compteFournisseur, null));
        resoudre(AccountPurpose.TVA_RECUPERABLE, null, new CompteResolu(null, ResolvedAccount.nonConfigure("Aucun mapping")));

        ResultatRegle resultat = rule.construireAcquisition(entreprise, immobilisation, fournisseur, new BigDecimal("900000"), "Acquisition");

        assertThat(resultat.lignes()).isEmpty();
        assertThat(resultat.explications()).singleElement().satisfies(e -> assertThat(e).contains("TVA recuperable").contains("NOT_CONFIGURED"));
    }

    @Test
    void dotation_debite_la_dotation_et_credite_l_amortissement_de_l_immobilisation() {
        resoudre(AccountPurpose.DOTATION_AMORTISSEMENT, null, new CompteResolu(dotation, null));
        resoudre(AccountPurpose.AMORTISSEMENT, amortissement.getId(), new CompteResolu(amortissement, null));

        ResultatRegle resultat = rule.construireDotation(entreprise, immobilisation, new BigDecimal("1000000"), "Dotation 2027");

        assertThat(resultat.typeJournalAttendu()).isEqualTo(TypeJournal.OPERATIONS_DIVERSES);
        assertThat(resultat.lignes()).hasSize(2);
        assertThat(resultat.lignes()).anySatisfy(l -> {
            assertThat(l.getCompte()).isSameAs(dotation);
            assertThat(l.getDebit()).isEqualByComparingTo("1000000");
        });
        assertThat(resultat.lignes()).anySatisfy(l -> {
            assertThat(l.getCompte()).isSameAs(amortissement);
            assertThat(l.getCredit()).isEqualByComparingTo("1000000");
        });
    }

    @Test
    void dotation_sans_compte_de_dotation_configure_ou_avec_amortissement_invalide_est_expliquee() {
        resoudre(AccountPurpose.DOTATION_AMORTISSEMENT, null, new CompteResolu(null, ResolvedAccount.nonConfigure("Aucun mapping ni role")));
        resoudre(AccountPurpose.AMORTISSEMENT, amortissement.getId(), new CompteResolu(null, ResolvedAccount.invalide("desactive")));

        ResultatRegle resultat = rule.construireDotation(entreprise, immobilisation, new BigDecimal("1000000"), "Dotation 2027");

        assertThat(resultat.lignes()).isEmpty();
        assertThat(resultat.explications()).hasSize(2);
        assertThat(resultat.explications().get(0)).contains("dotation").contains("NOT_CONFIGURED");
        assertThat(resultat.explications().get(1)).contains("Camion").contains("INVALID");
    }
}
