package com.example.comptable.controle.revue;

import com.example.comptable.agent.finding.FindingService;
import com.example.comptable.agent.finding.domain.StatutFinding;
import com.example.comptable.agent.finding.web.dto.FindingDto;
import com.example.comptable.agent.finding.web.dto.RejeterFindingRequest;
import com.example.comptable.controle.ControlCategory;
import com.example.comptable.controle.ControlSeverity;
import com.example.comptable.controle.revue.RevueComptableService.Filtre;
import com.example.comptable.controle.revue.RevueComptableService.Tuile;
import com.example.comptable.security.UtilisateurPrincipal;
import lombok.RequiredArgsConstructor;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDate;
import java.util.UUID;

/** « Revue » : ce que le comptable doit traiter pour une entreprise (synthese cliquable + liste filtrable). */
@RestController
@RequestMapping("/api/entreprises/{entrepriseId}/revue")
@RequiredArgsConstructor
public class RevueComptableController {

    private final RevueComptableService revueService;
    private final FindingService findingService;

    @GetMapping("/synthese")
    public RevueDto.Synthese synthese(@PathVariable UUID entrepriseId, @AuthenticationPrincipal UtilisateurPrincipal principal) {
        return revueService.synthese(entrepriseId, principal);
    }

    /** Espace de revision : preparation a la cloture, comptes par domaine, resolus du jour. */
    @GetMapping("/espace")
    public RevueDto.Espace espace(@PathVariable UUID entrepriseId, @AuthenticationPrincipal UtilisateurPrincipal principal) {
        return revueService.espace(entrepriseId, principal);
    }

    @GetMapping("/constats")
    public RevueDto.Page constats(@PathVariable UUID entrepriseId,
                                  @RequestParam(required = false) Tuile tuile,
                                  @RequestParam(required = false) ControlSeverity severite,
                                  @RequestParam(required = false) ControlCategory categorie,
                                  @RequestParam(required = false) com.example.comptable.controle.ControlDomain domaine,
                                  @RequestParam(required = false) StatutFinding statut,
                                  @RequestParam(required = false) String entityType,
                                  @RequestParam(required = false) String code,
                                  @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate du,
                                  @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate au,
                                  @RequestParam(required = false) String q,
                                  @RequestParam(defaultValue = "0") int page,
                                  @RequestParam(defaultValue = "25") int taille,
                                  @AuthenticationPrincipal UtilisateurPrincipal principal) {
        return revueService.lister(entrepriseId, new Filtre(tuile, severite, categorie, statut, entityType, code, du, au, q, domaine),
                page, taille, principal);
    }

    @GetMapping("/constats/{findingId}")
    public RevueDto.Constat constat(@PathVariable UUID entrepriseId, @PathVariable UUID findingId,
                                    @AuthenticationPrincipal UtilisateurPrincipal principal) {
        return revueService.obtenir(entrepriseId, findingId, principal);
    }

    /** Relance les controles et le rapprochement automatique des cas certains. */
    @PostMapping("/executer")
    public RevueDto.Execution executer(@PathVariable UUID entrepriseId, @AuthenticationPrincipal UtilisateurPrincipal principal) {
        return revueService.relancer(entrepriseId, principal);
    }

    /** Ecarte un constat (jamais supprime) : motif obligatoire. */
    @PostMapping("/constats/{findingId}/ecarter")
    public FindingDto ecarter(@PathVariable UUID entrepriseId, @PathVariable UUID findingId,
                              @RequestBody RejeterFindingRequest requete,
                              @AuthenticationPrincipal UtilisateurPrincipal principal) {
        return findingService.rejeter(entrepriseId, findingId, requete.motif(), principal);
    }
}
