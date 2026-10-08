package com.example.comptable.agent.imputation.web;

import com.example.comptable.agent.imputation.ImputationSuggestionService;
import com.example.comptable.agent.imputation.web.dto.ImputationSuggestionDto;
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
@RequestMapping("/api/entreprises/{entrepriseId}/imputation")
@RequiredArgsConstructor
public class ImputationSuggestionController {

    private final ImputationSuggestionService imputationSuggestionService;

    @GetMapping("/documents/{documentId}/suggestion")
    public ImputationSuggestionDto obtenirPropositionPourDocument(@PathVariable UUID entrepriseId,
                                                                  @PathVariable UUID documentId,
                                                                  @AuthenticationPrincipal UtilisateurPrincipal principal) {
        return imputationSuggestionService.obtenirPourDocument(entrepriseId, documentId, principal)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Aucune suggestion pour ce document"));
    }

    @PostMapping("/suggestions/{suggestionId}/accepter")
    public ImputationSuggestionDto accepter(@PathVariable UUID entrepriseId, @PathVariable UUID suggestionId,
                                           @AuthenticationPrincipal UtilisateurPrincipal principal) {
        return imputationSuggestionService.accepter(entrepriseId, suggestionId, principal);
    }

    @PostMapping("/suggestions/{suggestionId}/rejeter")
    public ImputationSuggestionDto rejeter(@PathVariable UUID entrepriseId, @PathVariable UUID suggestionId,
                                          @RequestParam(required = false) String motif,
                                          @AuthenticationPrincipal UtilisateurPrincipal principal) {
        return imputationSuggestionService.rejeter(entrepriseId, suggestionId, motif, principal);
    }
}
