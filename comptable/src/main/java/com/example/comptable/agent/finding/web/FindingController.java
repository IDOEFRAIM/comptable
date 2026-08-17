package com.example.comptable.agent.finding.web;

import com.example.comptable.agent.finding.FindingService;
import com.example.comptable.agent.finding.web.dto.AssignerFindingRequest;
import com.example.comptable.agent.finding.web.dto.FindingDto;
import com.example.comptable.agent.finding.web.dto.RejeterFindingRequest;
import com.example.comptable.security.UtilisateurPrincipal;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

/** Inbox comptable (mission P0, section 9) : ce que le comptable doit traiter aujourd'hui, trie
 * par severite. Alimentee par les moteurs de la phase 2 (ControlEngine, MissingEvidenceAnalyzer) -
 * cette couche ne fait que lister/resoudre/rejeter, jamais generer de finding elle-meme. */
@RestController
@RequestMapping("/api/entreprises/{entrepriseId}/findings")
@RequiredArgsConstructor
public class FindingController {

    private final FindingService findingService;

    @GetMapping
    public List<FindingDto> lister(@PathVariable UUID entrepriseId,
                                    @AuthenticationPrincipal UtilisateurPrincipal principal) {
        return findingService.listerOuverts(entrepriseId, principal);
    }

    @PostMapping("/{findingId}/resoudre")
    public FindingDto resoudre(@PathVariable UUID entrepriseId, @PathVariable UUID findingId,
                                @AuthenticationPrincipal UtilisateurPrincipal principal) {
        return findingService.resoudre(entrepriseId, findingId, principal);
    }

    @PostMapping("/{findingId}/rejeter")
    public FindingDto rejeter(@PathVariable UUID entrepriseId, @PathVariable UUID findingId,
                               @RequestBody(required = false) RejeterFindingRequest requete,
                               @AuthenticationPrincipal UtilisateurPrincipal principal) {
        String motif = requete != null ? requete.motif() : null;
        return findingService.rejeter(entrepriseId, findingId, motif, principal);
    }

    @PostMapping("/{findingId}/assigner")
    public FindingDto assigner(@PathVariable UUID entrepriseId, @PathVariable UUID findingId,
                                @RequestBody AssignerFindingRequest requete,
                                @AuthenticationPrincipal UtilisateurPrincipal principal) {
        return findingService.assigner(entrepriseId, findingId, requete.assignedToId(), requete.dueDate(), principal);
    }
}
