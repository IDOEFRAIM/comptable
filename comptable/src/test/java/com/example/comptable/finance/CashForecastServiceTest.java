package com.example.comptable.finance;

import com.example.comptable.comptabilite.domain.Facture;
import com.example.comptable.comptabilite.domain.LigneFacture;
import com.example.comptable.comptabilite.domain.StatutFacture;
import com.example.comptable.comptabilite.domain.Tiers;
import com.example.comptable.comptabilite.domain.TypeFacture;
import com.example.comptable.comptabilite.repository.FactureRepository;
import com.example.comptable.comptabilite.repository.LigneEcritureRepository;
import com.example.comptable.entreprise.domain.Entreprise;
import com.example.comptable.entreprise.repository.EntrepriseRepository;
import com.example.comptable.entreprise.service.EntrepriseAccessService;
import com.example.comptable.finance.forecast.PaymentBehaviorService;
import com.example.comptable.finance.forecast.domain.ForecastConfidence;
import com.example.comptable.finance.forecast.domain.LiquidityEventType;
import com.example.comptable.finance.forecast.domain.LiquiditySeverity;
import com.example.comptable.finance.forecast.web.dto.CashForecastDto;
import com.example.comptable.finance.forecast.web.dto.CashForecastWeekDto;
import com.example.comptable.finance.forecast.web.dto.PaymentBehaviorDto;
import com.example.comptable.finance.web.dto.PrevisionTresorerieDto;
import com.example.comptable.fiscalite.repository.DeclarationFiscaleRepository;
import com.example.comptable.identite.domain.Role;
import com.example.comptable.identite.domain.Utilisateur;
import com.example.comptable.security.UtilisateurPrincipal;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class CashForecastServiceTest {

    @Mock
    private EntrepriseAccessService entrepriseAccessService;
    @Mock
    private EntrepriseRepository entrepriseRepository;
    @Mock
    private LigneEcritureRepository ligneEcritureRepository;
    @Mock
    private FactureRepository factureRepository;
    @Mock
    private DeclarationFiscaleRepository declarationFiscaleRepository;
    @Mock
    private PaymentBehaviorService paymentBehaviorService;

    @Mock
    private com.example.comptable.comptabilite.allocation.StatutPaiementFactureService statutPaiementFactureService;

    @InjectMocks
    private CashForecastService cashForecastService;

    private UUID entrepriseId;
    private UtilisateurPrincipal principal;

    @BeforeEach
    void setUp() {
        lenient().when(statutPaiementFactureService.ouvertes(org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.anyCollection()))
                .thenAnswer(inv -> ((java.util.Collection<com.example.comptable.comptabilite.domain.Facture>) inv.getArgument(1)).stream()
                        .map(f -> new com.example.comptable.comptabilite.allocation.StatutPaiementFactureService.FactureOuverte(f, f.montantTtc(), java.math.BigDecimal.ZERO, f.montantTtc(),
                                com.example.comptable.comptabilite.allocation.StatutPaiement.NON_PAYEE)).toList());
        entrepriseId = UUID.randomUUID();
        Entreprise entreprise = new Entreprise();
        entreprise.setId(entrepriseId);

        Role role = new Role();
        role.setCode("EXPERT_COMPTABLE");
        Utilisateur utilisateur = new Utilisateur();
        utilisateur.setId(UUID.randomUUID());
        utilisateur.setEmail("expert@cabinet.ci");
        utilisateur.setMotDePasse("hash");
        utilisateur.setNom("Nikiema");
        utilisateur.setPrenom("Adama");
        utilisateur.setRoles(Set.of(role));
        principal = new UtilisateurPrincipal(utilisateur);

        lenient().when(entrepriseAccessService.verifierAcces(entrepriseId, principal)).thenReturn(entreprise);
        lenient().when(ligneEcritureRepository.soldeTresorerie(any(), any())).thenReturn(new BigDecimal("10000000"));
        lenient().when(declarationFiscaleRepository.findByEntrepriseIdAndDateEcheanceBetween(any(), any(), any()))
                .thenReturn(List.of());
    }

    private Facture facture(LocalDate dateEcheance, BigDecimal montant) {
        Facture f = new Facture();
        f.setId(UUID.randomUUID());
        Tiers tiers = new Tiers();
        tiers.setNom("Client Test");
        f.setTiers(tiers);
        f.setDateEcheance(dateEcheance);
        LigneFacture ligne = new LigneFacture();
        ligne.setQuantite(BigDecimal.ONE);
        ligne.setPrixUnitaire(montant);
        ligne.setTauxTva(BigDecimal.ZERO);
        ligne.setNumeroLigne(1);
        f.setLignes(List.of(ligne));
        return f;
    }

    @Test
    void produit_les_trois_horizons_avec_known_et_expected_separes() {
        LocalDate aujourdHui = LocalDate.now();
        Facture connue = facture(aujourdHui.plusDays(5), new BigDecimal("1000000"));
        Facture enRetard30j = facture(aujourdHui.minusDays(15), new BigDecimal("500000"));

        when(factureRepository.findByEntrepriseIdAndTypeAndStatutAndDateEcheanceBeforeOrderByDateEcheanceAsc(
                eq(entrepriseId), eq(TypeFacture.FACTURE), eq(StatutFacture.VALIDEE), any()))
                .thenReturn(List.of(enRetard30j));
        when(factureRepository.findByEntrepriseIdAndTypeAndStatutAndDateEcheanceBetweenOrderByDateEcheanceAsc(
                eq(entrepriseId), eq(TypeFacture.FACTURE), eq(StatutFacture.VALIDEE), any(), any()))
                .thenAnswer(inv -> {
                    LocalDate debut = inv.getArgument(3);
                    LocalDate fin = inv.getArgument(4);
                    return !connue.getDateEcheance().isBefore(debut) && !connue.getDateEcheance().isAfter(fin)
                            ? List.of(connue) : List.of();
                });

        List<PrevisionTresorerieDto> previsions = cashForecastService.generer(entrepriseId, principal);

        assertThat(previsions).hasSize(3);
        PrevisionTresorerieDto horizon7 = previsions.stream().filter(p -> p.horizonJours() == 7).findFirst().orElseThrow();
        assertThat(horizon7.soldeActuel()).isEqualByComparingTo("10000000");
        assertThat(horizon7.encaissementsConnus()).isEqualByComparingTo("1000000");
        // Retard de 15j -> tranche 1 (<=30j) -> probabilite 0.90 -> 500000 * 0.90 = 450000
        assertThat(horizon7.encaissementsAttendus()).isEqualByComparingTo("450000");
        assertThat(horizon7.decaissementsConnus()).isEqualByComparingTo(BigDecimal.ZERO);
        assertThat(horizon7.soldePrevisionnel()).isEqualByComparingTo("11450000");

        PrevisionTresorerieDto horizon90 = previsions.stream().filter(p -> p.horizonJours() == 90).findFirst().orElseThrow();
        assertThat(horizon90.encaissementsConnus()).isEqualByComparingTo("1000000");
        assertThat(horizon90.encaissementsAttendus()).isEqualByComparingTo("450000");
    }

    // =========================================================================================
    // 13-WEEK CASH FORECAST (mission P2)
    // =========================================================================================

    private static final LocalDate AS_OF = LocalDate.of(2026, 8, 1);

    private Facture factureEnRetard(UUID tiersId, String nomTiers, LocalDate dateEcheance, BigDecimal montant) {
        Facture f = new Facture();
        f.setId(UUID.randomUUID());
        f.setType(TypeFacture.FACTURE);
        Tiers tiers = new Tiers();
        tiers.setId(tiersId);
        tiers.setNom(nomTiers);
        f.setTiers(tiers);
        f.setDateEcheance(dateEcheance);
        LigneFacture ligne = new LigneFacture();
        ligne.setQuantite(BigDecimal.ONE);
        ligne.setPrixUnitaire(montant);
        ligne.setTauxTva(BigDecimal.ZERO);
        ligne.setNumeroLigne(1);
        f.setLignes(List.of(ligne));
        return f;
    }

    private void aucuneFactureEtAucuneDeclaration() {
        lenient().when(factureRepository.findByEntrepriseIdAndTypeAndStatutAndDateEcheanceBeforeOrderByDateEcheanceAsc(
                any(), any(), any(), any())).thenReturn(List.of());
        lenient().when(factureRepository.findByEntrepriseIdAndTypeAndStatutAndDateEcheanceBetweenOrderByDateEcheanceAsc(
                any(), any(), any(), any(), any())).thenReturn(List.of());
        lenient().when(declarationFiscaleRepository.findByEntrepriseIdAndDateEcheanceBetween(any(), any(), any()))
                .thenReturn(List.of());
    }

    @Test
    void chaque_semaine_respecte_linvariant_ouverture_plus_net_egale_cloture() {
        aucuneFactureEtAucuneDeclaration();

        CashForecastDto forecast = cashForecastService.genererPrevision13Semaines(entrepriseId, AS_OF, 13, null, principal);

        assertThat(forecast.weeks()).hasSize(13);
        for (CashForecastWeekDto semaine : forecast.weeks()) {
            assertThat(semaine.openingCash().add(semaine.netCashFlow()))
                    .as("semaine %d", semaine.weekIndex())
                    .isEqualByComparingTo(semaine.closingCash());
            assertThat(semaine.inflows().subtract(semaine.outflows())).isEqualByComparingTo(semaine.netCashFlow());
        }
    }

    @Test
    void la_cloture_dune_semaine_est_louverture_de_la_suivante() {
        aucuneFactureEtAucuneDeclaration();

        CashForecastDto forecast = cashForecastService.genererPrevision13Semaines(entrepriseId, AS_OF, 13, null, principal);

        for (int i = 0; i < forecast.weeks().size() - 1; i++) {
            assertThat(forecast.weeks().get(i).closingCash())
                    .isEqualByComparingTo(forecast.weeks().get(i + 1).openingCash());
        }
    }

    @Test
    void un_flux_connu_est_place_dans_la_semaine_correspondant_a_son_echeance() {
        aucuneFactureEtAucuneDeclaration();
        // Semaine 2 : du 2026-08-15 au 2026-08-21.
        LocalDate echeance = LocalDate.of(2026, 8, 18);
        Facture connue = factureEnRetard(UUID.randomUUID(), "Client Connu", echeance, new BigDecimal("1000000"));
        when(factureRepository.findByEntrepriseIdAndTypeAndStatutAndDateEcheanceBetweenOrderByDateEcheanceAsc(
                eq(entrepriseId), eq(TypeFacture.FACTURE), eq(StatutFacture.VALIDEE), any(), any()))
                .thenReturn(List.of(connue));

        CashForecastDto forecast = cashForecastService.genererPrevision13Semaines(entrepriseId, AS_OF, 13, null, principal);

        CashForecastWeekDto semaine2 = forecast.weeks().get(2);
        assertThat(semaine2.knownInflows()).isEqualByComparingTo("1000000");
        for (CashForecastWeekDto autre : forecast.weeks()) {
            if (autre.weekIndex() != 2) {
                assertThat(autre.knownInflows()).isEqualByComparingTo(BigDecimal.ZERO);
            }
        }
    }

    @Test
    void une_creance_en_retard_avec_historique_suffisant_est_projetee_a_la_date_ajustee() {
        aucuneFactureEtAucuneDeclaration();
        UUID tiersId = UUID.randomUUID();
        // Echeance 2026-07-01 (deja en retard au 2026-08-01) + retard median de 20j -> 2026-07-21 -> semaine 3.
        LocalDate echeance = LocalDate.of(2026, 7, 1);
        Facture enRetard = factureEnRetard(tiersId, "Client Fiable", echeance, new BigDecimal("2000000"));
        when(factureRepository.findByEntrepriseIdAndTypeAndStatutAndDateEcheanceBeforeOrderByDateEcheanceAsc(
                any(), any(), any(), any())).thenReturn(List.of(enRetard));
        when(paymentBehaviorService.analyser(tiersId, "Client Fiable"))
                .thenReturn(new PaymentBehaviorDto(tiersId, "Client Fiable", 5, 20L, 21.0, true));

        CashForecastDto forecast = cashForecastService.genererPrevision13Semaines(entrepriseId, AS_OF, 13, null, principal);

        LocalDate dateAjustee = echeance.plusDays(20); // 2026-07-21
        // 2026-07-21 est avant AS_OF (2026-08-01) -> clampe a AS_OF -> semaine 0.
        assertThat(dateAjustee).isBefore(AS_OF);
        assertThat(forecast.weeks().get(0).expectedInflows()).isEqualByComparingTo("2000000");
    }

    @Test
    void une_creance_en_retard_sans_historique_suffisant_utilise_le_delai_par_defaut() {
        aucuneFactureEtAucuneDeclaration();
        UUID tiersId = UUID.randomUUID();
        // Echeance dans la semaine 1 (asOf + 10j) ; sans historique, delai par defaut = 30j -> semaine 5.
        LocalDate echeance = AS_OF.plusDays(10);
        Facture enRetard = factureEnRetard(tiersId, "Client Nouveau", echeance, new BigDecimal("300000"));
        when(factureRepository.findByEntrepriseIdAndTypeAndStatutAndDateEcheanceBeforeOrderByDateEcheanceAsc(
                any(), any(), any(), any())).thenReturn(List.of(enRetard));
        when(paymentBehaviorService.analyser(tiersId, "Client Nouveau"))
                .thenReturn(new PaymentBehaviorDto(tiersId, "Client Nouveau", 1, null, null, false));

        CashForecastDto forecast = cashForecastService.genererPrevision13Semaines(entrepriseId, AS_OF, 13, null, principal);

        LocalDate dateAttendue = echeance.plusDays(30);
        int indexSemaineAttendue = (int) java.time.temporal.ChronoUnit.DAYS.between(AS_OF, dateAttendue) / 7;
        assertThat(forecast.weeks().get(indexSemaineAttendue).expectedInflows()).isEqualByComparingTo("300000");
    }

    @Test
    void historique_insuffisant_donne_une_confiance_low_et_un_avertissement_explicite() {
        aucuneFactureEtAucuneDeclaration();
        when(ligneEcritureRepository.trouverDatePlusAncienneEcritureValidee(entrepriseId)).thenReturn(null);

        CashForecastDto forecast = cashForecastService.genererPrevision13Semaines(entrepriseId, AS_OF, 13, null, principal);

        assertThat(forecast.confidence()).isEqualTo(ForecastConfidence.LOW);
        assertThat(forecast.dataQualityWarnings()).anyMatch(w -> w.contains("insufficient_history"));
    }

    @Test
    void un_historique_de_26_semaines_ou_plus_donne_une_confiance_high() {
        aucuneFactureEtAucuneDeclaration();
        when(ligneEcritureRepository.trouverDatePlusAncienneEcritureValidee(entrepriseId)).thenReturn(AS_OF.minusWeeks(30));

        CashForecastDto forecast = cashForecastService.genererPrevision13Semaines(entrepriseId, AS_OF, 13, null, principal);

        assertThat(forecast.confidence()).isEqualTo(ForecastConfidence.HIGH);
    }

    @Test
    void un_solde_sous_le_seuil_declenche_un_evenement_cash_below_threshold() {
        aucuneFactureEtAucuneDeclaration();
        when(ligneEcritureRepository.soldeTresorerie(entrepriseId, AS_OF.minusDays(1))).thenReturn(new BigDecimal("3000000"));

        CashForecastDto forecast = cashForecastService.genererPrevision13Semaines(
                entrepriseId, AS_OF, 13, new BigDecimal("5000000"), principal);

        assertThat(forecast.liquidityEvents()).anyMatch(e -> e.type() == LiquidityEventType.CASH_BELOW_THRESHOLD);
    }

    @Test
    void deux_semaines_consecutives_sous_le_seuil_declenchent_un_liquidity_gap() {
        aucuneFactureEtAucuneDeclaration();
        when(ligneEcritureRepository.soldeTresorerie(entrepriseId, AS_OF.minusDays(1))).thenReturn(new BigDecimal("1000000"));

        CashForecastDto forecast = cashForecastService.genererPrevision13Semaines(
                entrepriseId, AS_OF, 13, new BigDecimal("5000000"), principal);

        assertThat(forecast.liquidityEvents()).anyMatch(e -> e.type() == LiquidityEventType.LIQUIDITY_GAP
                && e.severity() == LiquiditySeverity.HIGH);
    }

    @Test
    void un_solde_de_cloture_negatif_declenche_un_evenement_negative_cash_de_severite_high() {
        aucuneFactureEtAucuneDeclaration();
        when(ligneEcritureRepository.soldeTresorerie(entrepriseId, AS_OF.minusDays(1))).thenReturn(new BigDecimal("-100000"));

        CashForecastDto forecast = cashForecastService.genererPrevision13Semaines(entrepriseId, AS_OF, 13, null, principal);

        assertThat(forecast.liquidityEvents()).anyMatch(e -> e.type() == LiquidityEventType.NEGATIVE_CASH
                && e.severity() == LiquiditySeverity.HIGH);
    }

    @Test
    void aucune_donnee_ne_produit_un_forecast_plat_jamais_un_chiffre_invente() {
        aucuneFactureEtAucuneDeclaration();

        CashForecastDto forecast = cashForecastService.genererPrevision13Semaines(entrepriseId, AS_OF, 13, null, principal);

        for (CashForecastWeekDto semaine : forecast.weeks()) {
            assertThat(semaine.inflows()).isEqualByComparingTo(BigDecimal.ZERO);
            assertThat(semaine.outflows()).isEqualByComparingTo(BigDecimal.ZERO);
            assertThat(semaine.closingCash()).isEqualByComparingTo("10000000");
        }
        assertThat(forecast.limitations()).isNotEmpty();
    }

    @Test
    void genererPrevision13SemainesSysteme_refuse_une_entreprise_introuvable() {
        when(entrepriseRepository.existsById(entrepriseId)).thenReturn(false);

        assertThatThrownBy(() -> cashForecastService.genererPrevision13SemainesSysteme(entrepriseId, AS_OF, 13, null))
                .isInstanceOf(org.springframework.web.server.ResponseStatusException.class);
    }

    @Test
    void genererPrevision13SemainesSysteme_calcule_sans_principal_quand_lentreprise_existe() {
        aucuneFactureEtAucuneDeclaration();
        when(entrepriseRepository.existsById(entrepriseId)).thenReturn(true);

        CashForecastDto forecast = cashForecastService.genererPrevision13SemainesSysteme(entrepriseId, AS_OF, 13, null);

        assertThat(forecast.weeks()).hasSize(13);
    }
}
