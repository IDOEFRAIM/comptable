package com.example.comptable.agent.finance;

import com.example.comptable.agent.finance.web.dto.AiFinancialSnapshotDto;
import com.example.comptable.agent.finance.web.dto.PayablesDto;
import com.example.comptable.agent.finance.web.dto.ReceivablesDto;
import com.example.comptable.agent.finance.web.dto.TopTiersDto;
import com.example.comptable.agent.finance.web.dto.WorkingCapitalDto;
import com.example.comptable.comptabilite.service.BalanceAgeeService;
import com.example.comptable.comptabilite.web.dto.BalanceAgeeDto;
import com.example.comptable.comptabilite.web.dto.BalanceAgeeLigneDto;
import com.example.comptable.finance.CashForecastService;
import com.example.comptable.finance.CashPositionService;
import com.example.comptable.finance.FinancialSnapshotService;
import com.example.comptable.finance.forecast.web.dto.CashForecastDto;
import com.example.comptable.finance.web.dto.CashPositionDto;
import com.example.comptable.finance.web.dto.FinancialSnapshotDto;
import com.example.comptable.stock.domain.Article;
import com.example.comptable.stock.repository.ArticleRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import java.util.function.Function;

/**
 * Financial Intelligence Layer (STEP 8 du plan d'implementation) - couche de capabilities
 * financieres deterministes pour le futur AI CFO. Reutilise integralement les services deja
 * existants ({@link CashPositionService}, {@link FinancialSnapshotService},
 * {@link BalanceAgeeService}, {@link ArticleRepository}) - AUCUN calcul financier n'est refait
 * ici, uniquement de la composition/projection vers des contrats stables destines a Python/MCP.
 * Jamais de logique fiscale/comptable propre a cette classe.
 */
@Service
@RequiredArgsConstructor
public class AiFinanceQueryService {

    private static final int TOP_TIERS_LIMIT = 5;
    private static final int ECHELLE_MARGE = 4;

    private final CashPositionService cashPositionService;
    private final FinancialSnapshotService financialSnapshotService;
    private final BalanceAgeeService balanceAgeeService;
    private final CashForecastService cashForecastService;
    private final ArticleRepository articleRepository;

    @Transactional(readOnly = true)
    public CashPositionDto obtenirPositionTresorerie(UUID entrepriseId, LocalDate date) {
        return cashPositionService.genererSysteme(entrepriseId, date);
    }

    @Transactional(readOnly = true)
    public CashForecastDto obtenirPrevisionTresorerie(UUID entrepriseId, LocalDate dateDebut, Integer nombreSemaines,
                                                        BigDecimal seuilTresorerieMinimum) {
        return cashForecastService.genererPrevision13SemainesSysteme(entrepriseId, dateDebut, nombreSemaines, seuilTresorerieMinimum);
    }

    @Transactional(readOnly = true)
    public AiFinancialSnapshotDto obtenirSnapshot(UUID entrepriseId, LocalDate periodeDebut, LocalDate periodeFin) {
        FinancialSnapshotDto snapshot = financialSnapshotService.genererSysteme(entrepriseId, periodeDebut, periodeFin);

        BigDecimal netMargin = null;
        String netMarginIndisponible = null;
        if (snapshot.revenue() == null || snapshot.revenue().signum() == 0) {
            netMarginIndisponible = "insufficient_data";
        } else {
            netMargin = snapshot.profit().divide(snapshot.revenue(), ECHELLE_MARGE, RoundingMode.HALF_UP);
        }

        // grossMargin volontairement toujours indisponible ici - voir AiFinancialSnapshotDto.
        String grossMarginIndisponible = "insufficient_data";

        return new AiFinancialSnapshotDto(snapshot, netMargin, netMarginIndisponible, null, grossMarginIndisponible);
    }

    @Transactional(readOnly = true)
    public ReceivablesDto obtenirCreances(UUID entrepriseId, LocalDate date) {
        BalanceAgeeDto balance = balanceAgeeService.genererSysteme(entrepriseId, date);
        List<BalanceAgeeLigneDto> clients = balance.lignes().stream()
                .filter(l -> "CLIENT".equals(l.typeTiers()) && l.total().signum() > 0)
                .toList();

        BigDecimal aging0a30 = sommer(clients, BalanceAgeeLigneDto::tranche0a30);
        BigDecimal aging31a60 = sommer(clients, BalanceAgeeLigneDto::tranche31a60);
        BigDecimal aging61a90 = sommer(clients, BalanceAgeeLigneDto::tranche61a90);
        BigDecimal agingPlus90 = sommer(clients, BalanceAgeeLigneDto::tranchePlus90);
        BigDecimal total = sommer(clients, BalanceAgeeLigneDto::total);
        BigDecimal overdue = aging31a60.add(aging61a90).add(agingPlus90);

        List<TopTiersDto> topDebtors = topTiers(clients, Comparator.comparing(BalanceAgeeLigneDto::total).reversed());

        return new ReceivablesDto(balance.dateArrete(), total, aging0a30, overdue,
                aging0a30, aging31a60, aging61a90, agingPlus90, topDebtors);
    }

    @Transactional(readOnly = true)
    public PayablesDto obtenirDettes(UUID entrepriseId, LocalDate date) {
        BalanceAgeeDto balance = balanceAgeeService.genererSysteme(entrepriseId, date);
        List<BalanceAgeeLigneDto> fournisseurs = balance.lignes().stream()
                .filter(l -> "FOURNISSEUR".equals(l.typeTiers()) && l.total().signum() < 0)
                .toList();

        // BalanceAgeeLigneDto exprime une dette fournisseur en negatif (convention entreprise) -
        // negation systematique ici pour exposer des montants de dette positifs (PayablesDto).
        BigDecimal aging0a30 = sommer(fournisseurs, BalanceAgeeLigneDto::tranche0a30).negate();
        BigDecimal aging31a60 = sommer(fournisseurs, BalanceAgeeLigneDto::tranche31a60).negate();
        BigDecimal aging61a90 = sommer(fournisseurs, BalanceAgeeLigneDto::tranche61a90).negate();
        BigDecimal agingPlus90 = sommer(fournisseurs, BalanceAgeeLigneDto::tranchePlus90).negate();
        BigDecimal total = sommer(fournisseurs, BalanceAgeeLigneDto::total).negate();
        BigDecimal overdue = aging31a60.add(aging61a90).add(agingPlus90);

        List<TopTiersDto> topCreditors = fournisseurs.stream()
                .sorted(Comparator.comparing(BalanceAgeeLigneDto::total))
                .limit(TOP_TIERS_LIMIT)
                .map(l -> new TopTiersDto(l.tiersId(), l.tiersNom(), l.total().negate()))
                .toList();

        return new PayablesDto(balance.dateArrete(), total, aging0a30, overdue,
                aging0a30, aging31a60, aging61a90, agingPlus90, topCreditors);
    }

    @Transactional(readOnly = true)
    public WorkingCapitalDto obtenirFondsDeRoulement(UUID entrepriseId, LocalDate date) {
        LocalDate dateReference = date != null ? date : LocalDate.now();
        ReceivablesDto receivables = obtenirCreances(entrepriseId, dateReference);
        PayablesDto payables = obtenirDettes(entrepriseId, dateReference);
        BigDecimal inventory = valeurStockTotale(entrepriseId);

        BigDecimal workingCapital = receivables.total().add(inventory).subtract(payables.total());
        return new WorkingCapitalDto(dateReference, WorkingCapitalDto.STATUT_DISPONIBLE, workingCapital,
                receivables.total(), payables.total(), inventory, List.of());
    }

    /** {@code Article.valeurStockTotale} est deja maintenu par le module stock (CUMP,
     * {@code CumpValuationService}/{@code MouvementStockService}) - jamais recalcule ici, aucune
     * hypothese sur le compte comptable de stock (classe 3) qui pourrait sur/sous-compter des
     * elements non lies a un article catalogue. */
    private BigDecimal valeurStockTotale(UUID entrepriseId) {
        return articleRepository.findByEntrepriseIdOrderByDesignationAsc(entrepriseId).stream()
                .map(Article::getValeurStockTotale)
                .filter(Objects::nonNull)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
    }

    private BigDecimal sommer(List<BalanceAgeeLigneDto> lignes, Function<BalanceAgeeLigneDto, BigDecimal> extracteur) {
        return lignes.stream().map(extracteur).reduce(BigDecimal.ZERO, BigDecimal::add);
    }

    private List<TopTiersDto> topTiers(List<BalanceAgeeLigneDto> lignes, Comparator<BalanceAgeeLigneDto> ordre) {
        return lignes.stream()
                .sorted(ordre)
                .limit(TOP_TIERS_LIMIT)
                .map(l -> new TopTiersDto(l.tiersId(), l.tiersNom(), l.total()))
                .toList();
    }
}
