package com.example.comptable.comptabilite.operation.common.web;

import com.example.comptable.comptabilite.operation.achat.AchatOperationRequest;
import com.example.comptable.comptabilite.operation.avoir.AvoirOperationRequest;
import com.example.comptable.comptabilite.operation.common.AccountingOperationEngine;
import com.example.comptable.comptabilite.operation.common.web.dto.AccountingOperationProposalDto;
import com.example.comptable.comptabilite.operation.common.web.dto.RejeterPropositionRequest;
import com.example.comptable.comptabilite.operation.reglement.ReglementOperationRequest;
import com.example.comptable.comptabilite.operation.vente.VenteOperationRequest;
import com.example.comptable.security.UtilisateurPrincipal;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

/** Operations metier courantes (achat/vente/reglement/avoir) -&gt; proposition d'ecriture -&gt;
 * validation humaine. Coexiste avec les routes existantes (ecritures, factures,
 * comptabilisation), aucune n'est modifiee ni supprimee. */
@RestController
@RequestMapping("/api/entreprises/{entrepriseId}/operations")
@RequiredArgsConstructor
public class AccountingOperationController {

    private final AccountingOperationEngine accountingOperationEngine;

    @PostMapping("/achat/proposition")
    public AccountingOperationProposalDto proposerAchat(@PathVariable UUID entrepriseId,
                                                         @Valid @RequestBody AchatOperationRequest request,
                                                         @AuthenticationPrincipal UtilisateurPrincipal principal) {
        return accountingOperationEngine.proposerAchat(entrepriseId, request, principal);
    }

    @PostMapping("/vente/proposition")
    public AccountingOperationProposalDto proposerVente(@PathVariable UUID entrepriseId,
                                                         @Valid @RequestBody VenteOperationRequest request,
                                                         @AuthenticationPrincipal UtilisateurPrincipal principal) {
        return accountingOperationEngine.proposerVente(entrepriseId, request, principal);
    }

    @PostMapping("/reglement/proposition")
    public AccountingOperationProposalDto proposerReglement(@PathVariable UUID entrepriseId,
                                                             @Valid @RequestBody ReglementOperationRequest request,
                                                             @AuthenticationPrincipal UtilisateurPrincipal principal) {
        return accountingOperationEngine.proposerReglement(entrepriseId, request, principal);
    }

    @PostMapping("/avoir/proposition")
    public AccountingOperationProposalDto proposerAvoir(@PathVariable UUID entrepriseId,
                                                         @Valid @RequestBody AvoirOperationRequest request,
                                                         @AuthenticationPrincipal UtilisateurPrincipal principal) {
        return accountingOperationEngine.proposerAvoir(entrepriseId, request, principal);
    }

    @GetMapping("/propositions/{propositionId}")
    public AccountingOperationProposalDto obtenir(@PathVariable UUID entrepriseId, @PathVariable UUID propositionId,
                                                   @AuthenticationPrincipal UtilisateurPrincipal principal) {
        return accountingOperationEngine.obtenir(entrepriseId, propositionId, principal);
    }

    @PostMapping("/propositions/{propositionId}/valider")
    public AccountingOperationProposalDto valider(@PathVariable UUID entrepriseId, @PathVariable UUID propositionId,
                                                   @AuthenticationPrincipal UtilisateurPrincipal principal) {
        return accountingOperationEngine.valider(entrepriseId, propositionId, principal);
    }

    @PostMapping("/propositions/{propositionId}/rejeter")
    public AccountingOperationProposalDto rejeter(@PathVariable UUID entrepriseId, @PathVariable UUID propositionId,
                                                   @Valid @RequestBody RejeterPropositionRequest request,
                                                   @AuthenticationPrincipal UtilisateurPrincipal principal) {
        return accountingOperationEngine.rejeter(entrepriseId, propositionId, request.motif(), principal);
    }
}
