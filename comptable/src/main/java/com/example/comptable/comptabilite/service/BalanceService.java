package com.example.comptable.comptabilite.service;

import com.example.comptable.comptabilite.balance.AccountBalance;
import com.example.comptable.comptabilite.balance.AccountingBalanceEngine;
import com.example.comptable.comptabilite.balance.ReportingView;
import com.example.comptable.comptabilite.web.dto.BalanceDto;
import com.example.comptable.comptabilite.web.dto.BalanceLigneDto;
import com.example.comptable.comptabilite.web.dto.SoldeDto;
import com.example.comptable.entreprise.service.EntrepriseAccessService;
import com.example.comptable.security.UtilisateurPrincipal;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import java.util.function.Function;

/**
 * Balance generale OFFICIELLE (vue {@link ReportingView#LEGAL_LEDGER} : ecritures validees, toutes natures) : pour chaque compte mouvemente, solde
 * d'ouverture / mouvements de la periode / solde de cloture. Aucun calcul de solde ici : tout vient de {@link AccountingBalanceEngine} (une seule
 * agregation SQL). Les totaux debit et credit s'equilibrent a chaque colonne (invariant fondamental de la partie double).
 */
@Service
@RequiredArgsConstructor
public class BalanceService {

    private final EntrepriseAccessService entrepriseAccessService;
    private final AccountingBalanceEngine accountingBalanceEngine;

    @Transactional(readOnly = true)
    public BalanceDto generer(UUID entrepriseId, LocalDate dateDebut, LocalDate dateFin, UtilisateurPrincipal principal) {
        entrepriseAccessService.verifierAcces(entrepriseId, principal);

        List<BalanceLigneDto> lignes = accountingBalanceEngine.balances(entrepriseId, dateDebut, dateFin, ReportingView.LEGAL_LEDGER).stream()
                .map(BalanceService::enLigne)
                .toList();

        return new BalanceDto(dateDebut, dateFin, lignes,
                sommer(lignes, l -> l.soldeOuverture().debit()), sommer(lignes, l -> l.soldeOuverture().credit()),
                sommer(lignes, BalanceLigneDto::mouvementDebit), sommer(lignes, BalanceLigneDto::mouvementCredit),
                sommer(lignes, l -> l.soldeCloture().debit()), sommer(lignes, l -> l.soldeCloture().credit()));
    }

    private static BalanceLigneDto enLigne(AccountBalance solde) {
        return new BalanceLigneDto(solde.accountId(), solde.accountNumber(), solde.accountLabel(),
                new SoldeDto(solde.openingDebit(), solde.openingCredit()),
                solde.periodDebit(), solde.periodCredit(),
                new SoldeDto(solde.closingDebit(), solde.closingCredit()));
    }

    private static BigDecimal sommer(List<BalanceLigneDto> lignes, Function<BalanceLigneDto, BigDecimal> extracteur) {
        return lignes.stream().map(extracteur).reduce(BigDecimal.ZERO, BigDecimal::add);
    }
}
