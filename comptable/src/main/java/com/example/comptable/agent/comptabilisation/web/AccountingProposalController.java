package com.example.comptable.agent.comptabilisation.web;

import com.example.comptable.agent.comptabilisation.AccountingProposalService;
import com.example.comptable.agent.comptabilisation.web.dto.AccountingProposalDto;
import com.example.comptable.security.UtilisateurPrincipal;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.util.UUID;

@RestController
@RequestMapping("/api/entreprises/{entrepriseId}/comptabilisation")
@RequiredArgsConstructor
public class AccountingProposalController {

    private final AccountingProposalService accountingProposalService;

    @GetMapping("/documents/{documentId}/proposition")
    public AccountingProposalDto obtenirPropositionPourDocument(@PathVariable UUID entrepriseId,
                                                                  @PathVariable UUID documentId,
                                                                  @AuthenticationPrincipal UtilisateurPrincipal principal) {
        return accountingProposalService.obtenirPourDocument(entrepriseId, documentId, principal)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Aucune proposition pour ce document"));
    }

    @PostMapping("/propositions/{propositionId}/accepter")
    public AccountingProposalDto accepter(@PathVariable UUID entrepriseId, @PathVariable UUID propositionId,
                                           @AuthenticationPrincipal UtilisateurPrincipal principal) {
        return accountingProposalService.accepter(entrepriseId, propositionId, principal);
    }

    @PostMapping("/propositions/{propositionId}/rejeter")
    public AccountingProposalDto rejeter(@PathVariable UUID entrepriseId, @PathVariable UUID propositionId,
                                          @RequestParam(required = false) String motif,
                                          @AuthenticationPrincipal UtilisateurPrincipal principal) {
        return accountingProposalService.rejeter(entrepriseId, propositionId, motif, principal);
    }
}
