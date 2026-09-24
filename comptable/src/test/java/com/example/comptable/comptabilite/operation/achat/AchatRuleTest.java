package com.example.comptable.comptabilite.operation.achat;

import com.example.comptable.comptabilite.domain.CompteComptable;
import com.example.comptable.comptabilite.domain.RoleCompteSysteme;
import com.example.comptable.comptabilite.domain.Tiers;
import com.example.comptable.comptabilite.domain.TypeJournal;
import com.example.comptable.comptabilite.operation.common.FraisAccessoireRequest;
import com.example.comptable.comptabilite.operation.common.ReductionRequest;
import com.example.comptable.comptabilite.operation.common.ResultatRegle;
import com.example.comptable.comptabilite.operation.common.TypeReduction;
import com.example.comptable.comptabilite.operation.common.domain.AccountingOperationProposalLigne;
import com.example.comptable.comptabilite.operation.tva.TvaRateResolver;
import com.example.comptable.comptabilite.operation.tva.TvaResultat;
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
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class AchatRuleTest {

    @Mock
    private CompteComptableValidationService compteComptableValidationService;
    @Mock
    private TvaRateResolver tvaRateResolver;
    @Mock
    private AccountingAccountResolver accountingAccountResolver;

    @InjectMocks
    private AchatRule achatRule;

    private UUID entrepriseId;
    private Entreprise entreprise;
    private Tiers fournisseur;
    private CompteComptable compteFournisseur;

    @BeforeEach
    void setUp() {
        entrepriseId = UUID.randomUUID();
        entreprise = new Entreprise();
        entreprise.setId(entrepriseId);
        entreprise.setPays("CI");

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

    private AchatOperationRequest request(NatureAchat nature, BigDecimal montantHt, UUID compteChargeId,
            UUID tauxTvaId, BigDecimal montantTtcAttendu, List<FraisAccessoireRequest> frais,
            List<ReductionRequest> reductions) {
        return new AchatOperationRequest(nature, fournisseur.getId(), LocalDate.of(2026, 3, 1), "REF-1",
                "Achat test", montantHt, compteChargeId, tauxTvaId, montantTtcAttendu, frais, reductions);
    }

    @Test
    void achat_marchandise_avec_tva_produit_charge_tva_et_fournisseur() {
        CompteComptable compteCharge = compte("601");
        CompteComptable compteTva = compte("445");
        when(compteComptableValidationService.chargerCompteUtilisable(entrepriseId, compteCharge.getId(), true))
                .thenReturn(compteCharge);
        when(tvaRateResolver.resoudre(eq(entrepriseId), eq("CI"), any(), eq(new BigDecimal("100000")), eq(RoleCompteSysteme.TVA_DEDUCTIBLE)))
                .thenReturn(new TvaResultat(new BigDecimal("18"), new BigDecimal("18000"), compteTva, UUID.randomUUID(), false, null, false));

        ResultatRegle resultat = achatRule.construire(entreprise, fournisseur,
                request(NatureAchat.MARCHANDISE, new BigDecimal("100000"), compteCharge.getId(), null, null, null, null));

        assertThat(resultat.typeJournalAttendu()).isEqualTo(TypeJournal.ACHATS);
        assertThat(resultat.explications()).isEmpty();
        assertThat(resultat.lignes()).hasSize(3);
        assertThat(totalDebit(resultat)).isEqualByComparingTo(totalCredit(resultat));
        assertThat(resultat.lignes()).anySatisfy(l -> {
            assertThat(l.getCompte().getNumero()).isEqualTo("601");
            assertThat(l.getDebit()).isEqualByComparingTo("100000");
        });
        assertThat(resultat.lignes()).anySatisfy(l -> {
            assertThat(l.getCompte().getNumero()).isEqualTo("445");
            assertThat(l.getDebit()).isEqualByComparingTo("18000");
        });
        assertThat(resultat.lignes()).anySatisfy(l -> {
            assertThat(l.getCompte().getNumero()).isEqualTo("401001");
            assertThat(l.getCredit()).isEqualByComparingTo("118000");
        });
    }

    @Test
    void achat_service_sans_regle_tva_applicable_reste_incomplet() {
        CompteComptable compteCharge = compte("622");
        when(compteComptableValidationService.chargerCompteUtilisable(entrepriseId, compteCharge.getId(), true))
                .thenReturn(compteCharge);
        when(tvaRateResolver.resoudre(eq(entrepriseId), eq("CI"), any(), eq(new BigDecimal("50000")), eq(RoleCompteSysteme.TVA_DEDUCTIBLE)))
                .thenReturn(TvaResultat.nonSupporte("Aucune regle TVA applicable pour le pays CI a la date 2026-03-01"));

        ResultatRegle resultat = achatRule.construire(entreprise, fournisseur,
                request(NatureAchat.SERVICE, new BigDecimal("50000"), compteCharge.getId(), null, null, null, null));

        assertThat(resultat.explications()).anySatisfy(e -> assertThat(e).contains("TVA deductible non calculee"));
        assertThat(resultat.lignes()).hasSize(2);
        assertThat(resultat.lignes()).noneMatch(l -> l.getCompte().getNumero().equals("445"));
    }

    @Test
    void immobilisation_sur_compte_classe_2_est_acceptee_sans_generer_le_schema_achat_classique() {
        CompteComptable compteImmo = compte("2411");
        when(compteComptableValidationService.chargerCompteUtilisable(entrepriseId, compteImmo.getId(), true))
                .thenReturn(compteImmo);
        when(tvaRateResolver.resoudre(eq(entrepriseId), eq("CI"), any(), eq(new BigDecimal("500000")), eq(RoleCompteSysteme.TVA_DEDUCTIBLE)))
                .thenReturn(TvaResultat.nonSupporte("non calcule pour ce test"));

        ResultatRegle resultat = achatRule.construire(entreprise, fournisseur,
                request(NatureAchat.IMMOBILISATION, new BigDecimal("500000"), compteImmo.getId(), null, null, null, null));

        assertThat(resultat.lignes()).anySatisfy(l -> assertThat(l.getCompte().getNumero()).isEqualTo("2411"));
    }

    @Test
    void immobilisation_sur_compte_de_charge_est_rejetee_avant_toute_construction_de_ligne() {
        CompteComptable compteCharge = compte("601");
        when(compteComptableValidationService.chargerCompteUtilisable(entrepriseId, compteCharge.getId(), true))
                .thenReturn(compteCharge);

        assertThatThrownBy(() -> achatRule.construire(entreprise, fournisseur,
                request(NatureAchat.IMMOBILISATION, new BigDecimal("500000"), compteCharge.getId(), null, null, null, null)))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("IMMOBILISATION");
    }

    @Test
    void nature_marchandise_sur_compte_dimmobilisation_est_rejetee() {
        CompteComptable compteImmo = compte("2411");
        when(compteComptableValidationService.chargerCompteUtilisable(entrepriseId, compteImmo.getId(), true))
                .thenReturn(compteImmo);

        assertThatThrownBy(() -> achatRule.construire(entreprise, fournisseur,
                request(NatureAchat.MARCHANDISE, new BigDecimal("500000"), compteImmo.getId(), null, null, null, null)))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("classe 6");
    }

    @Test
    void montant_ht_negatif_est_rejete() {
        assertThatThrownBy(() -> achatRule.construire(entreprise, fournisseur,
                request(NatureAchat.MARCHANDISE, new BigDecimal("-1"), UUID.randomUUID(), null, null, null, null)))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("negatif");
    }

    @Test
    void frais_accessoire_inclus_dans_la_base_tva_ajoute_son_ht_a_la_base_consolidee() {
        CompteComptable compteCharge = compte("601");
        CompteComptable compteFrais = compte("6241");
        CompteComptable compteTva = compte("445");
        when(compteComptableValidationService.chargerCompteUtilisable(entrepriseId, compteCharge.getId(), true))
                .thenReturn(compteCharge);
        when(compteComptableValidationService.chargerCompteUtilisable(entrepriseId, compteFrais.getId(), true))
                .thenReturn(compteFrais);
        when(tvaRateResolver.resoudre(eq(entrepriseId), eq("CI"), any(), eq(new BigDecimal("110000")), eq(RoleCompteSysteme.TVA_DEDUCTIBLE)))
                .thenReturn(new TvaResultat(new BigDecimal("18"), new BigDecimal("19800"), compteTva, UUID.randomUUID(), false, null, false));

        FraisAccessoireRequest frais = new FraisAccessoireRequest("Transport", new BigDecimal("10000"), null, compteFrais.getId(), true);
        ResultatRegle resultat = achatRule.construire(entreprise, fournisseur,
                request(NatureAchat.MARCHANDISE, new BigDecimal("100000"), compteCharge.getId(), null, null, List.of(frais), null));

        assertThat(resultat.lignes()).hasSize(4);
        assertThat(resultat.lignes()).anySatisfy(l -> {
            assertThat(l.getCompte().getNumero()).isEqualTo("6241");
            assertThat(l.getDebit()).isEqualByComparingTo("10000");
        });
        assertThat(resultat.lignes()).anySatisfy(l -> {
            assertThat(l.getCompte().getNumero()).isEqualTo("445");
            assertThat(l.getDebit()).isEqualByComparingTo("19800");
        });
        assertThat(totalDebit(resultat)).isEqualByComparingTo(totalCredit(resultat));
    }

    @Test
    void remise_est_nettee_dans_la_base_ht_avant_tva() {
        CompteComptable compteCharge = compte("601");
        CompteComptable compteTva = compte("445");
        when(compteComptableValidationService.chargerCompteUtilisable(entrepriseId, compteCharge.getId(), true))
                .thenReturn(compteCharge);
        when(tvaRateResolver.resoudre(eq(entrepriseId), eq("CI"), any(), eq(new BigDecimal("90000")), eq(RoleCompteSysteme.TVA_DEDUCTIBLE)))
                .thenReturn(new TvaResultat(new BigDecimal("18"), new BigDecimal("16200"), compteTva, UUID.randomUUID(), false, null, false));

        ReductionRequest remise = new ReductionRequest(TypeReduction.REMISE, new BigDecimal("10000"), null, null);
        ResultatRegle resultat = achatRule.construire(entreprise, fournisseur,
                request(NatureAchat.MARCHANDISE, new BigDecimal("100000"), compteCharge.getId(), null, null, null, List.of(remise)));

        assertThat(resultat.lignes()).anySatisfy(l -> {
            assertThat(l.getCompte().getNumero()).isEqualTo("601");
            assertThat(l.getDebit()).isEqualByComparingTo("90000");
        });
    }

    @Test
    void escompte_declenche_une_explication_non_supporte_sans_calcul() {
        CompteComptable compteCharge = compte("601");
        when(compteComptableValidationService.chargerCompteUtilisable(entrepriseId, compteCharge.getId(), true))
                .thenReturn(compteCharge);
        when(tvaRateResolver.resoudre(eq(entrepriseId), eq("CI"), any(), eq(new BigDecimal("100000")), eq(RoleCompteSysteme.TVA_DEDUCTIBLE)))
                .thenReturn(TvaResultat.nonSupporte("non teste"));

        ReductionRequest escompte = new ReductionRequest(TypeReduction.ESCOMPTE, null, new BigDecimal("2"), "paiement sous 8 jours");
        ResultatRegle resultat = achatRule.construire(entreprise, fournisseur,
                request(NatureAchat.MARCHANDISE, new BigDecimal("100000"), compteCharge.getId(), null, null, null, List.of(escompte)));

        assertThat(resultat.explications()).anySatisfy(e -> assertThat(e).containsIgnoringCase("escompte"));
        assertThat(resultat.lignes()).anySatisfy(l -> {
            assertThat(l.getCompte().getNumero()).isEqualTo("601");
            assertThat(l.getDebit()).isEqualByComparingTo("100000");
        });
    }

    @Test
    void ecart_ttc_hors_tolerance_ajoute_un_warning_non_bloquant() {
        CompteComptable compteCharge = compte("601");
        CompteComptable compteTva = compte("445");
        when(compteComptableValidationService.chargerCompteUtilisable(entrepriseId, compteCharge.getId(), true))
                .thenReturn(compteCharge);
        when(tvaRateResolver.resoudre(eq(entrepriseId), eq("CI"), any(), eq(new BigDecimal("100000")), eq(RoleCompteSysteme.TVA_DEDUCTIBLE)))
                .thenReturn(new TvaResultat(new BigDecimal("18"), new BigDecimal("18000"), compteTva, UUID.randomUUID(), false, null, false));

        ResultatRegle resultat = achatRule.construire(entreprise, fournisseur,
                request(NatureAchat.MARCHANDISE, new BigDecimal("100000"), compteCharge.getId(), null,
                        new BigDecimal("999999"), null, null));

        assertThat(resultat.explications()).isEmpty();
        assertThat(resultat.warnings()).anySatisfy(w -> assertThat(w).contains("differe"));
    }

    private LigneAchatOperationRequest ligne(String designation, NatureAchat nature, BigDecimal montantHt,
            BigDecimal tauxTva, UUID compteId) {
        return new LigneAchatOperationRequest(designation, nature, montantHt, tauxTva, compteId, null);
    }

    private AchatMultiLigneOperationRequest requeteMultiLigne(List<LigneAchatOperationRequest> lignes) {
        return new AchatMultiLigneOperationRequest(fournisseur.getId(), LocalDate.of(2026, 3, 1), "REF-1",
                "Facture fournisseur test", lignes, null, "FACTURE_FOURNISSEUR", UUID.randomUUID());
    }

    @Test
    void multi_ligne_une_seule_ligne_marchandise() {
        CompteComptable compteAchat = compte("601");
        CompteComptable compteTva = compte("4456");
        when(compteComptableValidationService.chargerCompteUtilisable(entrepriseId, compteAchat.getId(), true)).thenReturn(compteAchat);
        when(compteComptableValidationService.chargerCompteUtilisable(entrepriseId, compteTva.getId(), true)).thenReturn(compteTva);
        when(accountingAccountResolver.resolve(eq(entrepriseId), eq(AccountPurpose.TVA_RECUPERABLE), any(), isNull()))
                .thenReturn(resolved(compteTva));

        ResultatRegle resultat = achatRule.construireMultiLigne(entreprise, fournisseur, requeteMultiLigne(List.of(
                ligne("Ciment", NatureAchat.MARCHANDISE, new BigDecimal("100000"), new BigDecimal("18"), compteAchat.getId()))));

        assertThat(resultat.explications()).isEmpty();
        assertThat(resultat.typeJournalAttendu()).isEqualTo(TypeJournal.ACHATS);
        assertThat(totalDebit(resultat)).isEqualByComparingTo(totalCredit(resultat));
        assertThat(resultat.lignes()).anySatisfy(l -> {
            assertThat(l.getCompte().getNumero()).isEqualTo("601");
            assertThat(l.getDebit()).isEqualByComparingTo("100000");
        });
        assertThat(resultat.lignes()).anySatisfy(l -> {
            assertThat(l.getCompte().getNumero()).isEqualTo("4456");
            assertThat(l.getDebit()).isEqualByComparingTo("18000");
        });
        assertThat(resultat.lignes()).anySatisfy(l -> {
            assertThat(l.getCompte().getNumero()).isEqualTo("401001");
            assertThat(l.getCredit()).isEqualByComparingTo("118000");
        });
    }

    @Test
    void multi_ligne_une_seule_ligne_service() {
        CompteComptable compteService = compte("622");
        when(compteComptableValidationService.chargerCompteUtilisable(entrepriseId, compteService.getId(), true)).thenReturn(compteService);

        ResultatRegle resultat = achatRule.construireMultiLigne(entreprise, fournisseur, requeteMultiLigne(List.of(
                ligne("Prestation", NatureAchat.SERVICE, new BigDecimal("50000"), null, compteService.getId()))));

        assertThat(resultat.explications()).isEmpty();
        assertThat(resultat.lignes()).hasSize(2);
        assertThat(totalDebit(resultat)).isEqualByComparingTo(totalCredit(resultat));
    }

    @Test
    void multi_ligne_une_seule_ligne_consommable() {
        CompteComptable compteConsommable = compte("6041");
        when(compteComptableValidationService.chargerCompteUtilisable(entrepriseId, compteConsommable.getId(), true)).thenReturn(compteConsommable);

        ResultatRegle resultat = achatRule.construireMultiLigne(entreprise, fournisseur, requeteMultiLigne(List.of(
                ligne("Fournitures", NatureAchat.CONSOMMABLE, new BigDecimal("20000"), null, compteConsommable.getId()))));

        assertThat(resultat.explications()).isEmpty();
        assertThat(resultat.lignes()).anySatisfy(l -> assertThat(l.getCompte().getNumero()).isEqualTo("6041"));
    }

    @Test
    void multi_ligne_une_seule_ligne_immobilisation_sur_compte_classe_2() {
        CompteComptable compteImmo = compte("2411");
        when(compteComptableValidationService.chargerCompteUtilisable(entrepriseId, compteImmo.getId(), true)).thenReturn(compteImmo);

        ResultatRegle resultat = achatRule.construireMultiLigne(entreprise, fournisseur, requeteMultiLigne(List.of(
                ligne("Ordinateur", NatureAchat.IMMOBILISATION, new BigDecimal("300000"), null, compteImmo.getId()))));

        assertThat(resultat.explications()).isEmpty();
        assertThat(resultat.lignes()).anySatisfy(l -> assertThat(l.getCompte().getNumero()).isEqualTo("2411"));
    }

    @Test
    void multi_ligne_immobilisation_sur_compte_de_charge_est_ecartee_avec_explication() {
        CompteComptable compteCharge = compte("601");
        when(compteComptableValidationService.chargerCompteUtilisable(entrepriseId, compteCharge.getId(), true)).thenReturn(compteCharge);

        ResultatRegle resultat = achatRule.construireMultiLigne(entreprise, fournisseur, requeteMultiLigne(List.of(
                ligne("Materiel", NatureAchat.IMMOBILISATION, new BigDecimal("300000"), null, compteCharge.getId()))));

        assertThat(resultat.explications()).anySatisfy(e -> assertThat(e).contains("Materiel"));
        assertThat(resultat.lignes()).noneMatch(l -> l.getCompte().getNumero().equals("601"));
    }

    @Test
    void multi_ligne_plusieurs_natures_avec_taux_tva_heterogenes_et_ligne_exoneree() {
        CompteComptable compteMarchandise = compte("601");
        CompteComptable compteService = compte("622");
        CompteComptable compteTransport = compte("6241");
        CompteComptable compteTva = compte("4456");
        when(compteComptableValidationService.chargerCompteUtilisable(entrepriseId, compteMarchandise.getId(), true)).thenReturn(compteMarchandise);
        when(compteComptableValidationService.chargerCompteUtilisable(entrepriseId, compteService.getId(), true)).thenReturn(compteService);
        when(compteComptableValidationService.chargerCompteUtilisable(entrepriseId, compteTransport.getId(), true)).thenReturn(compteTransport);
        when(compteComptableValidationService.chargerCompteUtilisable(entrepriseId, compteTva.getId(), true)).thenReturn(compteTva);
        when(accountingAccountResolver.resolve(eq(entrepriseId), eq(AccountPurpose.TVA_RECUPERABLE), any(), isNull()))
                .thenReturn(resolved(compteTva));

        ResultatRegle resultat = achatRule.construireMultiLigne(entreprise, fournisseur, requeteMultiLigne(List.of(
                ligne("Marchandises", NatureAchat.MARCHANDISE, new BigDecimal("100000"), new BigDecimal("18"), compteMarchandise.getId()),
                ligne("Service exonere", NatureAchat.SERVICE, new BigDecimal("50000"), null, compteService.getId()),
                ligne("Transport", NatureAchat.FRAIS_ACCESSOIRE, new BigDecimal("20000"), new BigDecimal("18"), compteTransport.getId()))));

        assertThat(resultat.explications()).isEmpty();
        assertThat(totalDebit(resultat)).isEqualByComparingTo(totalCredit(resultat));
        // TVA = 18% de (100000 + 20000) = 21600, la ligne service exoneree ne contribue pas.
        assertThat(resultat.lignes()).anySatisfy(l -> {
            assertThat(l.getCompte().getNumero()).isEqualTo("4456");
            assertThat(l.getDebit()).isEqualByComparingTo("21600");
        });
        assertThat(resultat.lignes()).anySatisfy(l -> {
            assertThat(l.getCompte().getNumero()).isEqualTo("601");
            assertThat(l.getDebit()).isEqualByComparingTo("100000");
        });
        assertThat(resultat.lignes()).anySatisfy(l -> {
            assertThat(l.getCompte().getNumero()).isEqualTo("622");
            assertThat(l.getDebit()).isEqualByComparingTo("50000");
        });
        assertThat(resultat.lignes()).anySatisfy(l -> {
            assertThat(l.getCompte().getNumero()).isEqualTo("6241");
            assertThat(l.getDebit()).isEqualByComparingTo("20000");
        });
        assertThat(resultat.lignes()).anySatisfy(l -> {
            assertThat(l.getCompte().getNumero()).isEqualTo("401001");
            assertThat(l.getCredit()).isEqualByComparingTo("191600");
        });
    }

    @Test
    void multi_ligne_deux_comptes_marchandise_distincts_ne_sont_jamais_agreges_ensemble() {
        CompteComptable compte601 = compte("601");
        CompteComptable compte604 = compte("604");
        when(compteComptableValidationService.chargerCompteUtilisable(entrepriseId, compte601.getId(), true)).thenReturn(compte601);
        when(compteComptableValidationService.chargerCompteUtilisable(entrepriseId, compte604.getId(), true)).thenReturn(compte604);

        ResultatRegle resultat = achatRule.construireMultiLigne(entreprise, fournisseur, requeteMultiLigne(List.of(
                ligne("Marchandise", NatureAchat.MARCHANDISE, new BigDecimal("100000"), null, compte601.getId()),
                ligne("Matiere premiere", NatureAchat.MATIERE_PREMIERE, new BigDecimal("30000"), null, compte604.getId()))));

        assertThat(resultat.lignes()).hasSize(3);
        assertThat(resultat.lignes()).anySatisfy(l -> {
            assertThat(l.getCompte().getNumero()).isEqualTo("601");
            assertThat(l.getDebit()).isEqualByComparingTo("100000");
        });
        assertThat(resultat.lignes()).anySatisfy(l -> {
            assertThat(l.getCompte().getNumero()).isEqualTo("604");
            assertThat(l.getDebit()).isEqualByComparingTo("30000");
        });
    }

    @Test
    void multi_ligne_sans_compte_fourni_est_ecartee_avec_explication_needs_review() {
        ResultatRegle resultat = achatRule.construireMultiLigne(entreprise, fournisseur, requeteMultiLigne(List.of(
                ligne("Ligne sans compte", NatureAchat.AUTRE, new BigDecimal("10000"), null, null))));

        assertThat(resultat.explications()).anySatisfy(e -> assertThat(e).contains("Ligne sans compte"));
        assertThat(resultat.lignes()).isEmpty();
    }

    @Test
    void multi_ligne_sans_compte_fourni_resolue_via_le_resolveur_de_comptes() {
        CompteComptable compteService = compte("622");
        when(accountingAccountResolver.resolve(eq(entrepriseId), eq(AccountPurpose.ACHAT_SERVICE), any(), isNull()))
                .thenReturn(resolved(compteService));
        when(compteComptableValidationService.chargerCompteUtilisable(entrepriseId, compteService.getId(), true)).thenReturn(compteService);

        ResultatRegle resultat = achatRule.construireMultiLigne(entreprise, fournisseur, requeteMultiLigne(List.of(
                ligne("Prestation", NatureAchat.SERVICE, new BigDecimal("50000"), null, null))));

        assertThat(resultat.explications()).isEmpty();
        assertThat(resultat.lignes()).anySatisfy(l -> {
            assertThat(l.getCompte().getNumero()).isEqualTo("622");
            assertThat(l.getDebit()).isEqualByComparingTo("50000");
        });
    }

    @Test
    void multi_ligne_sans_compte_fourni_et_non_configure_dans_le_resolveur_reste_needs_review() {
        when(accountingAccountResolver.resolve(eq(entrepriseId), eq(AccountPurpose.ACHAT_SERVICE), any(), isNull()))
                .thenReturn(ResolvedAccount.nonConfigure("aucun mapping (test)"));

        ResultatRegle resultat = achatRule.construireMultiLigne(entreprise, fournisseur, requeteMultiLigne(List.of(
                ligne("Prestation", NatureAchat.SERVICE, new BigDecimal("50000"), null, null))));

        assertThat(resultat.explications()).anySatisfy(e -> assertThat(e).contains("Prestation"));
        assertThat(resultat.lignes()).isEmpty();
    }

    private ResolvedAccount resolved(CompteComptable compte) {
        return new ResolvedAccount(AccountResolutionStatus.RESOLVED, compte.getId(), compte.getNumero(), compte.getIntitule(),
                AccountResolutionSource.MAPPING_GENERIQUE, NiveauConfiance.CERTAIN, "resolu (test)");
    }

    @Test
    void multi_ligne_avec_reduction_nette_le_montant_ht_de_la_ligne() {
        CompteComptable compteAchat = compte("601");
        when(compteComptableValidationService.chargerCompteUtilisable(entrepriseId, compteAchat.getId(), true)).thenReturn(compteAchat);

        ReductionRequest remise = new ReductionRequest(TypeReduction.REMISE, new BigDecimal("10000"), null, null);
        LigneAchatOperationRequest ligneAvecReduction = new LigneAchatOperationRequest("Marchandise", NatureAchat.MARCHANDISE,
                new BigDecimal("100000"), null, compteAchat.getId(), List.of(remise));

        ResultatRegle resultat = achatRule.construireMultiLigne(entreprise, fournisseur, requeteMultiLigne(List.of(ligneAvecReduction)));

        assertThat(resultat.lignes()).anySatisfy(l -> assertThat(l.getDebit()).isEqualByComparingTo("90000"));
    }

    private BigDecimal totalDebit(ResultatRegle resultat) {
        return resultat.lignes().stream().map(AccountingOperationProposalLigne::getDebit).reduce(BigDecimal.ZERO, BigDecimal::add);
    }

    private BigDecimal totalCredit(ResultatRegle resultat) {
        return resultat.lignes().stream().map(AccountingOperationProposalLigne::getCredit).reduce(BigDecimal.ZERO, BigDecimal::add);
    }
}
