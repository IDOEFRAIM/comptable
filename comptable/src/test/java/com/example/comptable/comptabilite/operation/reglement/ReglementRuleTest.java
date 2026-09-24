package com.example.comptable.comptabilite.operation.reglement;

import com.example.comptable.comptabilite.domain.CompteComptable;
import com.example.comptable.comptabilite.domain.Tiers;
import com.example.comptable.comptabilite.domain.TypeJournal;
import com.example.comptable.comptabilite.operation.common.AccountingOperationType;
import com.example.comptable.comptabilite.operation.common.MoyenPaiement;
import com.example.comptable.comptabilite.operation.common.ResultatRegle;
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
import org.springframework.web.server.ResponseStatusException;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ReglementRuleTest {

    @Mock
    private AccountingAccountResolver accountingAccountResolver;
    @Mock
    private CompteComptableValidationService compteComptableValidationService;

    @InjectMocks
    private ReglementRule reglementRule;

    private UUID entrepriseId;
    private Entreprise entreprise;
    private Tiers client;
    private Tiers fournisseur;
    private CompteComptable compteClient;
    private CompteComptable compteFournisseur;

    @BeforeEach
    void setUp() {
        entrepriseId = UUID.randomUUID();
        entreprise = new Entreprise();
        entreprise.setId(entrepriseId);

        compteClient = compte("411001");
        client = new Tiers();
        client.setId(UUID.randomUUID());
        client.setEntreprise(entreprise);
        client.setCompteComptable(compteClient);

        compteFournisseur = compte("401001");
        fournisseur = new Tiers();
        fournisseur.setId(UUID.randomUUID());
        fournisseur.setEntreprise(entreprise);
        fournisseur.setCompteComptable(compteFournisseur);
    }

    private CompteComptable compte(String numero) {
        CompteComptable c = new CompteComptable();
        c.setId(UUID.randomUUID());
        c.setEntreprise(entreprise);
        c.setNumero(numero);
        c.setIntitule(numero);
        c.setActif(true);
        return c;
    }

    private ReglementOperationRequest request(AccountingOperationType type, Tiers tiers, MoyenPaiement moyen, BigDecimal montant) {
        return new ReglementOperationRequest(type, tiers.getId(), LocalDate.of(2026, 3, 1), "REF-1", "Reglement test", montant, moyen);
    }

    private ResolvedAccount resolu(CompteComptable compte) {
        return new ResolvedAccount(AccountResolutionStatus.RESOLVED, compte.getId(), compte.getNumero(), compte.getIntitule(),
                AccountResolutionSource.ROLE_SYSTEME, NiveauConfiance.CERTAIN, "resolu (test)");
    }

    private void mockerResolutionTresorerie(AccountPurpose purpose, CompteComptable compte) {
        when(accountingAccountResolver.resolve(eq(entrepriseId), eq(purpose), any(), isNull())).thenReturn(resolu(compte));
        when(compteComptableValidationService.chargerCompteUtilisable(entrepriseId, compte.getId(), true)).thenReturn(compte);
    }

    @Test
    void reglement_client_par_banque_debite_la_banque_et_credite_le_client() {
        CompteComptable compteBanque = compte("521");
        mockerResolutionTresorerie(AccountPurpose.BANQUE, compteBanque);

        ResultatRegle resultat = reglementRule.construire(entreprise, client,
                request(AccountingOperationType.REGLEMENT_CLIENT, client, MoyenPaiement.VIREMENT, new BigDecimal("50000")));

        assertThat(resultat.typeJournalAttendu()).isEqualTo(TypeJournal.BANQUE);
        assertThat(resultat.explications()).isEmpty();
        assertThat(resultat.lignes()).hasSize(2);
        assertThat(resultat.lignes()).anySatisfy(l -> {
            assertThat(l.getCompte().getNumero()).isEqualTo("521");
            assertThat(l.getDebit()).isEqualByComparingTo("50000");
        });
        assertThat(resultat.lignes()).anySatisfy(l -> {
            assertThat(l.getCompte().getNumero()).isEqualTo("411001");
            assertThat(l.getCredit()).isEqualByComparingTo("50000");
        });
    }

    @Test
    void reglement_client_en_especes_utilise_la_caisse_et_le_journal_caisse() {
        CompteComptable compteCaisse = compte("571");
        mockerResolutionTresorerie(AccountPurpose.CAISSE, compteCaisse);

        ResultatRegle resultat = reglementRule.construire(entreprise, client,
                request(AccountingOperationType.REGLEMENT_CLIENT, client, MoyenPaiement.ESPECES, new BigDecimal("15000")));

        assertThat(resultat.typeJournalAttendu()).isEqualTo(TypeJournal.CAISSE);
        assertThat(resultat.lignes()).anySatisfy(l -> assertThat(l.getCompte().getNumero()).isEqualTo("571"));
    }

    @Test
    void reglement_fournisseur_par_banque_debite_le_fournisseur_et_credite_la_banque() {
        CompteComptable compteBanque = compte("521");
        mockerResolutionTresorerie(AccountPurpose.BANQUE, compteBanque);

        ResultatRegle resultat = reglementRule.construire(entreprise, fournisseur,
                request(AccountingOperationType.REGLEMENT_FOURNISSEUR, fournisseur, MoyenPaiement.CHEQUE, new BigDecimal("30000")));

        assertThat(resultat.lignes()).anySatisfy(l -> {
            assertThat(l.getCompte().getNumero()).isEqualTo("401001");
            assertThat(l.getDebit()).isEqualByComparingTo("30000");
        });
        assertThat(resultat.lignes()).anySatisfy(l -> {
            assertThat(l.getCompte().getNumero()).isEqualTo("521");
            assertThat(l.getCredit()).isEqualByComparingTo("30000");
        });
    }

    @Test
    void compte_tresorerie_non_configure_reste_incomplet_sans_lignes() {
        when(accountingAccountResolver.resolve(eq(entrepriseId), eq(AccountPurpose.BANQUE), any(), isNull()))
                .thenReturn(ResolvedAccount.nonConfigure("aucun mapping ni role systeme (test)"));

        ResultatRegle resultat = reglementRule.construire(entreprise, client,
                request(AccountingOperationType.REGLEMENT_CLIENT, client, MoyenPaiement.VIREMENT, new BigDecimal("50000")));

        assertThat(resultat.lignes()).isEmpty();
        assertThat(resultat.explications()).anySatisfy(e -> assertThat(e).contains("BANQUE"));
    }

    @Test
    void mapping_specifique_au_moyen_de_paiement_est_transmis_comme_contexte() {
        CompteComptable compteBanque = compte("521200");
        mockerResolutionTresorerie(AccountPurpose.BANQUE, compteBanque);

        reglementRule.construire(entreprise, client,
                request(AccountingOperationType.REGLEMENT_CLIENT, client, MoyenPaiement.VIREMENT, new BigDecimal("50000")));

        org.mockito.Mockito.verify(accountingAccountResolver).resolve(eq(entrepriseId), eq(AccountPurpose.BANQUE),
                org.mockito.ArgumentMatchers.argThat(ctx -> "VIREMENT".equals(ctx.contextKey())), isNull());
    }

    @Test
    void montant_negatif_est_rejete() {
        assertThatThrownBy(() -> reglementRule.construire(entreprise, client,
                request(AccountingOperationType.REGLEMENT_CLIENT, client, MoyenPaiement.VIREMENT, new BigDecimal("-1"))))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("negatif");
    }

    @Test
    void type_invalide_pour_un_reglement_est_rejete() {
        assertThatThrownBy(() -> reglementRule.construire(entreprise, client,
                request(AccountingOperationType.VENTE, client, MoyenPaiement.VIREMENT, new BigDecimal("100"))))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("invalide");
    }
}
