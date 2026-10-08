package com.example.comptable.etatsfinanciers.service;

import com.example.comptable.comptabilite.balance.AccountBalance;
import com.example.comptable.comptabilite.balance.AccountingBalanceEngine;
import com.example.comptable.comptabilite.balance.ReportingView;
import com.example.comptable.comptabilite.domain.LigneEcriture;
import com.example.comptable.comptabilite.operation.common.domain.AccountingOperationProposal;
import com.example.comptable.comptabilite.operation.common.repository.AccountingOperationProposalRepository;
import com.example.comptable.comptabilite.repository.LigneEcritureRepository;
import com.example.comptable.entreprise.service.EntrepriseAccessService;
import com.example.comptable.etatsfinanciers.definition.StatementType;
import com.example.comptable.etatsfinanciers.service.StatementResult.Line;
import com.example.comptable.etatsfinanciers.service.StatementResult.LineAccount;
import com.example.comptable.security.UtilisateurPrincipal;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * Drill-down d'un montant reglementaire : rubrique -&gt; comptes -&gt; solde -&gt; ecritures -&gt; lineage (cause metier : facture, document, banque...). Reutilise la
 * meme vue de reporting que le calcul de l'etat (le chemin ne peut pas diverger du montant affiche) et le lineage des propositions comptables.
 */
@Service
@RequiredArgsConstructor
public class StatementDrillDownService {

    private final EntrepriseAccessService entrepriseAccessService;
    private final IncomeStatementService incomeStatementService;
    private final BalanceSheetService balanceSheetService;
    private final CashFlowStatementService cashFlowStatementService;
    private final AccountingBalanceEngine balanceEngine;
    private final LigneEcritureRepository ligneEcritureRepository;
    private final AccountingOperationProposalRepository proposalRepository;

    /** Comptes d'une ligne avec leur montant et leur solde ; {@code compteId} (optionnel) ajoute les ecritures de ce compte. */
    public record Detail(StatementType statementType, String lineCode, UUID exerciceId, List<LineAccount> accounts, AccountDetail account,
                         List<CashFlowEventResolver.Composant> components) {
    }

    public record AccountDetail(UUID accountId, String number, String label, BigDecimal opening, BigDecimal movementDebit, BigDecimal movementCredit, BigDecimal closing,
                                List<Entry> entries) {
    }

    public record Entry(UUID ecritureId, UUID ligneId, LocalDate date, String journal, String numeroPiece, String libelle, String nature, BigDecimal debit, BigDecimal credit,
                        List<Lineage> lineage) {
    }

    public record Lineage(String operationType, String sourceType, UUID sourceId, String sourceEvent) {
    }

    @Transactional(readOnly = true)
    public Detail detailler(UUID entrepriseId, UUID exerciceId, StatementType type, String ligneCode, UUID compteId, UtilisateurPrincipal principal) {
        entrepriseAccessService.verifierAcces(entrepriseId, principal);
        StatementResult etat;
        ReportingView vue;
        if (type == StatementType.COMPTE_RESULTAT) {
            etat = incomeStatementService.calculer(entrepriseId, exerciceId);
            vue = ReportingView.PERIOD_ACTIVITY;
        } else if (type == StatementType.BILAN_ACTIF || type == StatementType.BILAN_PASSIF) {
            BalanceSheetService.BilanResult bilan = balanceSheetService.calculer(entrepriseId, exerciceId);
            etat = type == StatementType.BILAN_ACTIF ? bilan.actif() : bilan.passif();
            vue = ReportingView.LEGAL_LEDGER;
        } else if (type == StatementType.TFT) {
            StatementResult tft = cashFlowStatementService.calculer(entrepriseId, exerciceId);
            Line l = tft.lines().stream().filter(x -> x.code().equals(ligneCode)).findFirst()
                    .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Ligne introuvable : " + ligneCode));
            // FA a FE : comptes composant la variation ; FF a FQ : ecritures et evenements de tresorerie (avec leur lineage)
            return new Detail(type, ligneCode, exerciceId, l.accounts(), null, cashFlowStatementService.composants(entrepriseId, exerciceId, ligneCode, principal));
        } else {
            throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY, "Pas de drill-down pour l'etat " + type + " (aucun montant calcule par comptes)");
        }
        Line ligne = etat.lines().stream().filter(l -> l.code().equals(ligneCode)).findFirst()
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Ligne introuvable : " + ligneCode));
        if (compteId == null) {
            return new Detail(type, ligneCode, exerciceId, ligne.accounts(), null, List.of());
        }
        if (ligne.accounts().stream().noneMatch(a -> a.accountId().equals(compteId))) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Ce compte ne compose pas la ligne " + ligneCode);
        }
        AccountBalance solde = balanceEngine.balance(entrepriseId, compteId, etat.exerciceDebut(), etat.exerciceFin(), vue).orElseThrow();
        List<LigneEcriture> lignes = ligneEcritureRepository.rechercherLignesDuCompteDansLaVue(entrepriseId, compteId, etat.exerciceDebut(), etat.exerciceFin(), vue.statuts(), vue.natures());
        Map<UUID, List<Lineage>> lineage = lineage(lignes.stream().map(l -> l.getEcriture().getId()).collect(Collectors.toSet()));
        List<Entry> entrees = new ArrayList<>();
        for (LigneEcriture l : lignes) {
            entrees.add(new Entry(l.getEcriture().getId(), l.getId(), l.getEcriture().getDateEcriture(), l.getEcriture().getJournal().getCode(), l.getEcriture().getNumeroPiece(),
                    l.getLibelle(), l.getEcriture().getNature().name(), l.getDebit(), l.getCredit(), lineage.getOrDefault(l.getEcriture().getId(), List.of())));
        }
        return new Detail(type, ligneCode, exerciceId, ligne.accounts(),
                new AccountDetail(solde.accountId(), solde.accountNumber(), solde.accountLabel(), solde.openingNet(), solde.periodDebit(), solde.periodCredit(), solde.netBalance(), entrees), List.of());
    }

    private Map<UUID, List<Lineage>> lineage(Collection<UUID> ecritureIds) {
        if (ecritureIds.isEmpty()) {
            return Map.of();
        }
        Map<UUID, List<Lineage>> resultat = new HashMap<>();
        for (AccountingOperationProposal p : proposalRepository.findByEcritureIdIn(ecritureIds)) {
            resultat.computeIfAbsent(p.getEcriture().getId(), k -> new ArrayList<>()).add(new Lineage(p.getType().name(), p.getSourceType(), p.getSourceId(), p.getSourceEvent()));
        }
        return resultat;
    }
}
