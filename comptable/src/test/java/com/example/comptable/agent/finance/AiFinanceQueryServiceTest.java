package com.example.comptable.agent.finance;

import com.example.comptable.agent.finance.web.dto.AiFinancialSnapshotDto;
import com.example.comptable.agent.finance.web.dto.PayablesDto;
import com.example.comptable.agent.finance.web.dto.ReceivablesDto;
import com.example.comptable.agent.finance.web.dto.WorkingCapitalDto;
import com.example.comptable.comptabilite.service.BalanceAgeeService;
import com.example.comptable.comptabilite.web.dto.BalanceAgeeDto;
import com.example.comptable.comptabilite.web.dto.BalanceAgeeLigneDto;
import com.example.comptable.finance.CashForecastService;
import com.example.comptable.finance.CashPositionService;
import com.example.comptable.finance.FinancialSnapshotService;
import com.example.comptable.finance.forecast.domain.ForecastConfidence;
import com.example.comptable.finance.forecast.web.dto.CashForecastDto;
import com.example.comptable.finance.web.dto.CashPositionDto;
import com.example.comptable.finance.web.dto.FinancialSnapshotDto;
import com.example.comptable.stock.domain.Article;
import com.example.comptable.stock.repository.ArticleRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

/**
 * Fixtures realistes XOF (mission STEP 8, section 27) : revenue 35M, expenses 30.8M, profit 4.2M,
 * cash 12.5M, receivables 21.8M, payables 9.4M - un scenario de sante financiere plausible pour
 * une PME ouest-africaine.
 */
@ExtendWith(MockitoExtension.class)
class AiFinanceQueryServiceTest {

    @Mock
    private CashPositionService cashPositionService;
    @Mock
    private FinancialSnapshotService financialSnapshotService;
    @Mock
    private BalanceAgeeService balanceAgeeService;
    @Mock
    private CashForecastService cashForecastService;
    @Mock
    private ArticleRepository articleRepository;

    @InjectMocks
    private AiFinanceQueryService aiFinanceQueryService;

    private final UUID entrepriseId = UUID.randomUUID();
    private final LocalDate asOf = LocalDate.of(2026, 8, 27);

    @Test
    void obtenirPositionTresorerie_delegue_integralement_a_cashPositionService() {
        CashPositionDto attendu = new CashPositionDto(asOf, new BigDecimal("12500000"), List.of());
        when(cashPositionService.genererSysteme(entrepriseId, asOf)).thenReturn(attendu);

        CashPositionDto resultat = aiFinanceQueryService.obtenirPositionTresorerie(entrepriseId, asOf);

        assertThat(resultat).isSameAs(attendu);
    }

    @Test
    void obtenirSnapshot_calcule_le_net_margin_a_partir_du_snapshot_java() {
        LocalDate debut = LocalDate.of(2026, 8, 1);
        FinancialSnapshotDto snapshot = new FinancialSnapshotDto(debut, asOf,
                new BigDecimal("12500000"), new BigDecimal("21800000"), new BigDecimal("9400000"),
                new BigDecimal("35000000"), new BigDecimal("30800000"), new BigDecimal("4200000"), BigDecimal.ZERO);
        when(financialSnapshotService.genererSysteme(entrepriseId, debut, asOf)).thenReturn(snapshot);

        AiFinancialSnapshotDto resultat = aiFinanceQueryService.obtenirSnapshot(entrepriseId, debut, asOf);

        assertThat(resultat.snapshot()).isSameAs(snapshot);
        // 4 200 000 / 35 000 000 = 0.12
        assertThat(resultat.netMargin()).isEqualByComparingTo("0.1200");
        assertThat(resultat.netMarginUnavailableReason()).isNull();
        // Gross margin volontairement jamais invente (aucun COGS distinct des achats/stock fiable).
        assertThat(resultat.grossMargin()).isNull();
        assertThat(resultat.grossMarginUnavailableReason()).isEqualTo("insufficient_data");
    }

    @Test
    void obtenirSnapshot_net_margin_indisponible_quand_le_revenu_est_nul() {
        LocalDate debut = LocalDate.of(2026, 8, 1);
        FinancialSnapshotDto snapshot = new FinancialSnapshotDto(debut, asOf,
                BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO);
        when(financialSnapshotService.genererSysteme(entrepriseId, debut, asOf)).thenReturn(snapshot);

        AiFinancialSnapshotDto resultat = aiFinanceQueryService.obtenirSnapshot(entrepriseId, debut, asOf);

        assertThat(resultat.netMargin()).isNull();
        assertThat(resultat.netMarginUnavailableReason()).isEqualTo("insufficient_data");
    }

    private BalanceAgeeLigneDto client(String nom, BigDecimal t0, BigDecimal t31, BigDecimal t61, BigDecimal tPlus) {
        BigDecimal total = t0.add(t31).add(t61).add(tPlus);
        return new BalanceAgeeLigneDto(UUID.randomUUID(), nom, "CLIENT", t0, t31, t61, tPlus, total);
    }

    private BalanceAgeeLigneDto fournisseur(String nom, BigDecimal t0, BigDecimal t31, BigDecimal t61, BigDecimal tPlus) {
        // Convention BalanceAgeeLigneDto : une dette fournisseur est negative.
        BigDecimal total = t0.add(t31).add(t61).add(tPlus);
        return new BalanceAgeeLigneDto(UUID.randomUUID(), nom, "FOURNISSEUR", t0, t31, t61, tPlus, total);
    }

    @Test
    void obtenirCreances_agrege_le_total_lanciennete_et_les_plus_gros_debiteurs() {
        BalanceAgeeLigneDto alpha = client("Client Alpha", new BigDecimal("10000000"), new BigDecimal("5000000"),
                BigDecimal.ZERO, BigDecimal.ZERO);
        BalanceAgeeLigneDto beta = client("Client Beta", new BigDecimal("2000000"), BigDecimal.ZERO,
                new BigDecimal("3000000"), new BigDecimal("1800000"));
        BalanceAgeeLigneDto detteFournisseur = fournisseur("Fournisseur Gamma",
                new BigDecimal("-9400000"), BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO);
        when(balanceAgeeService.genererSysteme(entrepriseId, asOf))
                .thenReturn(new BalanceAgeeDto(asOf, List.of(alpha, beta, detteFournisseur), new BigDecimal("12400000")));

        ReceivablesDto resultat = aiFinanceQueryService.obtenirCreances(entrepriseId, asOf);

        assertThat(resultat.asOf()).isEqualTo(asOf);
        assertThat(resultat.total()).isEqualByComparingTo("21800000"); // 15M + 6.8M, jamais la dette fournisseur
        assertThat(resultat.current()).isEqualByComparingTo("12000000"); // tranche 0-30 : 10M + 2M
        assertThat(resultat.overdue()).isEqualByComparingTo("9800000"); // 5M + 3M + 1.8M
        assertThat(resultat.topDebtors()).hasSize(2);
        assertThat(resultat.topDebtors().get(0).nom()).isEqualTo("Client Alpha"); // 15M > 6.8M
        assertThat(resultat.topDebtors().get(0).montant()).isEqualByComparingTo("15000000");
    }

    @Test
    void obtenirDettes_exprime_les_montants_en_positif_et_ignore_les_creances() {
        BalanceAgeeLigneDto fournisseurA = fournisseur("Fournisseur A",
                new BigDecimal("-4000000"), new BigDecimal("-5400000"), BigDecimal.ZERO, BigDecimal.ZERO);
        BalanceAgeeLigneDto creanceClient = client("Client ignore", new BigDecimal("1000000"),
                BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO);
        when(balanceAgeeService.genererSysteme(entrepriseId, asOf))
                .thenReturn(new BalanceAgeeDto(asOf, List.of(fournisseurA, creanceClient), new BigDecimal("-8400000")));

        PayablesDto resultat = aiFinanceQueryService.obtenirDettes(entrepriseId, asOf);

        assertThat(resultat.total()).isEqualByComparingTo("9400000");
        assertThat(resultat.current()).isEqualByComparingTo("4000000");
        assertThat(resultat.overdue()).isEqualByComparingTo("5400000");
        assertThat(resultat.topCreditors()).hasSize(1);
        assertThat(resultat.topCreditors().get(0).montant()).isEqualByComparingTo("9400000"); // positif, jamais -9400000
    }

    private Article article(BigDecimal valeurStock) {
        Article a = new Article();
        a.setValeurStockTotale(valeurStock);
        return a;
    }

    @Test
    void obtenirFondsDeRoulement_combine_receivables_inventory_et_payables() {
        BalanceAgeeLigneDto client = client("Client Alpha", new BigDecimal("21800000"), BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO);
        BalanceAgeeLigneDto fournisseur = fournisseur("Fournisseur A", new BigDecimal("-9400000"), BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO);
        when(balanceAgeeService.genererSysteme(entrepriseId, asOf))
                .thenReturn(new BalanceAgeeDto(asOf, List.of(client, fournisseur), new BigDecimal("12400000")));
        when(articleRepository.findByEntrepriseIdOrderByDesignationAsc(entrepriseId))
                .thenReturn(List.of(article(new BigDecimal("3000000")), article(new BigDecimal("2000000"))));

        WorkingCapitalDto resultat = aiFinanceQueryService.obtenirFondsDeRoulement(entrepriseId, asOf);

        assertThat(resultat.status()).isEqualTo(WorkingCapitalDto.STATUT_DISPONIBLE);
        assertThat(resultat.receivables()).isEqualByComparingTo("21800000");
        assertThat(resultat.payables()).isEqualByComparingTo("9400000");
        assertThat(resultat.inventory()).isEqualByComparingTo("5000000");
        // 21.8M + 5M - 9.4M = 17.4M
        assertThat(resultat.workingCapital()).isEqualByComparingTo("17400000");
        assertThat(resultat.missingComponents()).isEmpty();
    }

    @Test
    void obtenirFondsDeRoulement_zero_stock_est_un_fait_reel_jamais_indisponible() {
        when(balanceAgeeService.genererSysteme(any(), any()))
                .thenReturn(new BalanceAgeeDto(asOf, List.of(), BigDecimal.ZERO));
        when(articleRepository.findByEntrepriseIdOrderByDesignationAsc(entrepriseId)).thenReturn(List.of());

        WorkingCapitalDto resultat = aiFinanceQueryService.obtenirFondsDeRoulement(entrepriseId, asOf);

        assertThat(resultat.inventory()).isEqualByComparingTo(BigDecimal.ZERO);
        assertThat(resultat.status()).isEqualTo(WorkingCapitalDto.STATUT_DISPONIBLE);
    }

    @Test
    void obtenirPrevisionTresorerie_delegue_integralement_a_cashForecastService() {
        CashForecastDto attendu = new CashForecastDto(asOf, asOf, asOf.plusWeeks(13).minusDays(1), "XOF",
                new BigDecimal("12500000"), new BigDecimal("11000000"), new BigDecimal("5000000"),
                ForecastConfidence.MEDIUM, List.of(), List.of(), List.of(), List.of());
        when(cashForecastService.genererPrevision13SemainesSysteme(entrepriseId, asOf, 13, new BigDecimal("5000000")))
                .thenReturn(attendu);

        CashForecastDto resultat = aiFinanceQueryService.obtenirPrevisionTresorerie(entrepriseId, asOf, 13, new BigDecimal("5000000"));

        assertThat(resultat).isSameAs(attendu);
    }
}
