package com.example.comptable.finance;

import com.example.comptable.comptabilite.repository.LigneEcritureRepository;
import com.example.comptable.comptabilite.service.BalanceAgeeService;
import com.example.comptable.comptabilite.web.dto.BalanceAgeeDto;
import com.example.comptable.entreprise.service.EntrepriseAccessService;
import com.example.comptable.finance.web.dto.ComparaisonFinancialSnapshotDto;
import com.example.comptable.finance.web.dto.FinancialSnapshotDto;
import com.example.comptable.fiscalite.domain.DeclarationFiscale;
import com.example.comptable.fiscalite.repository.DeclarationFiscaleRepository;
import com.example.comptable.security.UtilisateurPrincipal;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.Objects;
import java.util.UUID;

/**
 * "Financial Snapshot" (mission P1, section 20) : synthese cash/receivables/payables/revenue/
 * expenses/profit/taxes pour une periode - entierement recalculee a partir de moteurs deja
 * existants en P0/P1 ({@link LigneEcritureRepository}, {@link BalanceAgeeService},
 * {@link DeclarationFiscaleRepository}), jamais persistee (meme principe que
 * {@code DossierComptableDto}).
 */
@Service
@RequiredArgsConstructor
public class FinancialSnapshotService {

    private final EntrepriseAccessService entrepriseAccessService;
    private final LigneEcritureRepository ligneEcritureRepository;
    private final BalanceAgeeService balanceAgeeService;
    private final DeclarationFiscaleRepository declarationFiscaleRepository;

    @Transactional(readOnly = true)
    public FinancialSnapshotDto generer(UUID entrepriseId, LocalDate periodeDebut, LocalDate periodeFin,
                                         UtilisateurPrincipal principal) {
        entrepriseAccessService.verifierAcces(entrepriseId, principal);

        BigDecimal cash = ligneEcritureRepository.soldeTresorerie(entrepriseId, periodeFin);

        BalanceAgeeDto balanceAgee = balanceAgeeService.generer(entrepriseId, periodeFin, principal);
        BigDecimal receivables = sommer(balanceAgee, "CLIENT");
        BigDecimal payables = sommer(balanceAgee, "FOURNISSEUR").negate();

        BigDecimal expenses = ligneEcritureRepository.mouvementNetClasseSurPeriode(entrepriseId, "6%", periodeDebut, periodeFin);
        BigDecimal revenue = ligneEcritureRepository.mouvementNetClasseSurPeriode(entrepriseId, "7%", periodeDebut, periodeFin).negate();
        BigDecimal profit = revenue.subtract(expenses);

        BigDecimal taxes = declarationFiscaleRepository
                .findByEntrepriseIdAndDateEcheanceBetween(entrepriseId, periodeDebut, periodeFin).stream()
                .map(DeclarationFiscale::getMontant)
                .filter(Objects::nonNull)
                .reduce(BigDecimal.ZERO, BigDecimal::add);

        return new FinancialSnapshotDto(periodeDebut, periodeFin, cash, receivables, payables, revenue, expenses, profit, taxes);
    }

    /** Mission P1 (section 20) : comparaisons mois-vs-mois-precedent ou annee-vs-annee-N-1, sur
     * une periode de meme longueur pour rester comparable. */
    @Transactional(readOnly = true)
    public ComparaisonFinancialSnapshotDto comparer(UUID entrepriseId, LocalDate periodeDebut, LocalDate periodeFin,
                                                      String typeComparaison, UtilisateurPrincipal principal) {
        FinancialSnapshotDto actuelle = generer(entrepriseId, periodeDebut, periodeFin, principal);

        LocalDate debutComparaison;
        LocalDate finComparaison;
        if ("AN_PRECEDENT".equals(typeComparaison)) {
            debutComparaison = periodeDebut.minusYears(1);
            finComparaison = periodeFin.minusYears(1);
        } else {
            long joursPeriode = ChronoUnit.DAYS.between(periodeDebut, periodeFin) + 1;
            finComparaison = periodeDebut.minusDays(1);
            debutComparaison = finComparaison.minusDays(joursPeriode - 1);
        }
        FinancialSnapshotDto precedente = generer(entrepriseId, debutComparaison, finComparaison, principal);

        return new ComparaisonFinancialSnapshotDto(actuelle, precedente, typeComparaison);
    }

    private BigDecimal sommer(BalanceAgeeDto balanceAgee, String typeTiers) {
        return balanceAgee.lignes().stream()
                .filter(l -> typeTiers.equals(l.typeTiers()))
                .map(l -> l.total())
                .reduce(BigDecimal.ZERO, BigDecimal::add);
    }
}
