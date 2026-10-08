package com.example.comptable.cabinet.workspace.web;

import com.example.comptable.cabinet.workspace.CabinetWorkspaceDtos.*;
import com.example.comptable.cabinet.workspace.CabinetEquipeService;
import com.example.comptable.cabinet.workspace.CabinetWorkspaceService;
import com.example.comptable.cabinet.workspace.CabinetWorkspaceService.DossierQuery;
import com.example.comptable.cabinet.workspace.CabinetWorkspaceService.Filtre;
import com.example.comptable.cabinet.workspace.CabinetWorkspaceService.Tri;
import com.example.comptable.cabinet.workspace.StatutOperationnel;
import com.example.comptable.security.UtilisateurPrincipal;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Cabinet Workspace : tableau de bord, portefeuille de dossiers, file « À traiter », échéances et affectations. Le périmètre est toujours le tenant du principal ; {@code SecurityConfig} réserve
 * {@code /api/cabinet/**} aux collaborateurs et experts-comptables (un CLIENT n'y accède jamais).
 */
@RestController
@RequestMapping("/api/cabinet")
@RequiredArgsConstructor
public class CabinetWorkspaceController {

    private final CabinetWorkspaceService service;
    private final CabinetEquipeService equipe;
    private final com.example.comptable.identite.service.CredentialService credentialService;
    private final com.example.comptable.etatsfinanciers.sync.FinancialControlSynchronizationService synchronisation;

    @GetMapping("/dashboard")
    public DashboardDto dashboard(@RequestParam(name = "mes", defaultValue = "false") boolean mes, @AuthenticationPrincipal UtilisateurPrincipal principal) {
        return service.dashboard(principal, mes);
    }

    @GetMapping("/dossiers")
    public PageDto<DossierSummaryDto> dossiers(@RequestParam(required = false) String filtre, @RequestParam(required = false) String q, @RequestParam(required = false) UUID collaborateurId,
                                               @RequestParam(required = false) UUID expertId, @RequestParam(required = false) Integer exercice, @RequestParam(required = false) String statut,
                                               @RequestParam(required = false) String tri, @RequestParam(name = "ordre", defaultValue = "asc") String ordre,
                                               @RequestParam(defaultValue = "0") int page, @RequestParam(defaultValue = "25") int taille,
                                               @AuthenticationPrincipal UtilisateurPrincipal principal) {
        DossierQuery query = new DossierQuery(parse(Filtre.class, filtre, "filtre"), q, collaborateurId, expertId, exercice, parse(StatutOperationnel.class, statut, "statut"),
                parse(Tri.class, tri, "tri"), "desc".equalsIgnoreCase(ordre), page, taille);
        return service.dossiers(principal, query);
    }

    @GetMapping("/work-queue")
    public PageDto<WorkItemDto> workQueue(@RequestParam(name = "mes", defaultValue = "false") boolean mes, @RequestParam(defaultValue = "0") int page,
                                          @RequestParam(defaultValue = "25") int taille, @AuthenticationPrincipal UtilisateurPrincipal principal) {
        return service.workQueue(principal, mes, page, taille);
    }

    @GetMapping("/deadlines")
    public EcheancesDto deadlines(@RequestParam(required = false) String periode, @RequestParam(name = "mes", defaultValue = "false") boolean mes,
                                  @AuthenticationPrincipal UtilisateurPrincipal principal) {
        return service.echeances(principal, periode, mes);
    }

    @GetMapping("/membres")
    public List<UtilisateurRef> membres(@AuthenticationPrincipal UtilisateurPrincipal principal) {
        return service.membres(principal);
    }

    /**
     * Réinitialisation du mot de passe d'un membre (expert-comptable seulement) : renvoie, UNE seule fois, un jeton à usage unique et son expiration à transmettre au membre. Aucun e-mail n'est supposé envoyé.
     * L'ancien mot de passe reste valable tant que le jeton n'est pas consommé ; la consommation révoque toutes les sessions du membre.
     */
    @PostMapping("/equipe/{id}/reinitialiser-mot-de-passe")
    public com.example.comptable.identite.service.CredentialService.ReinitialisationEmise reinitialiserMotDePasse(@PathVariable UUID id, @AuthenticationPrincipal UtilisateurPrincipal principal) {
        equipe.exigerExpertPourReinitialisation(principal);
        return credentialService.emettreReinitialisation(principal, id);
    }

    @GetMapping("/equipe")
    public List<CabinetEquipeService.MembreDto> equipe(@AuthenticationPrincipal UtilisateurPrincipal principal) {
        return equipe.equipe(principal);
    }

    @PostMapping("/membres")
    @org.springframework.web.bind.annotation.ResponseStatus(HttpStatus.CREATED)
    public CabinetEquipeService.MembreCreeDto ajouterMembre(@RequestBody CabinetEquipeService.MembreRequest request, @AuthenticationPrincipal UtilisateurPrincipal principal) {
        return equipe.ajouter(principal, request);
    }

    @PatchMapping("/membres/{id}")
    public CabinetEquipeService.MembreDto modifierMembre(@PathVariable UUID id, @RequestBody CabinetEquipeService.MembrePatch patch, @AuthenticationPrincipal UtilisateurPrincipal principal) {
        return equipe.modifier(principal, id, patch);
    }

    /** Backfill explicite des constats financiers du cabinet (dossiers existants avant la synchronisation proactive) : action volontaire de l'expert, jamais un cron. */
    @PostMapping("/controles/synchroniser")
    public Map<String, Integer> synchroniserControles(@AuthenticationPrincipal UtilisateurPrincipal principal) {
        if (!com.example.comptable.etatsfinanciers.dossier.DossierRoles.peutRevoir(principal)) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "La resynchronisation des contrôles est réservée à l'expert-comptable");
        }
        return Map.of("dossiers", synchronisation.synchroniserCabinet(principal.getTenantId()));
    }

    @PatchMapping("/dossiers/{entrepriseId}/{exerciceId}/affectation")
    public DossierSummaryDto affecter(@PathVariable UUID entrepriseId, @PathVariable UUID exerciceId, @RequestBody AffectationRequest request,
                                      @AuthenticationPrincipal UtilisateurPrincipal principal) {
        return service.affecter(principal, entrepriseId, exerciceId, request);
    }

    @PatchMapping("/dossiers/affectation")
    public Map<String, Integer> affecterEnMasse(@RequestBody AffectationEnMasseRequest request, @AuthenticationPrincipal UtilisateurPrincipal principal) {
        return Map.of("dossiers", service.affecterEnMasse(principal, request));
    }

    @PostMapping("/echeances")
    @org.springframework.web.bind.annotation.ResponseStatus(HttpStatus.CREATED)
    public EcheanceDto creerEcheance(@RequestBody EcheanceRequest request, @AuthenticationPrincipal UtilisateurPrincipal principal) {
        return service.creerEcheance(principal, request);
    }

    @PatchMapping("/echeances/{id}")
    public EcheanceDto modifierEcheance(@PathVariable UUID id, @RequestBody EcheancePatch patch, @AuthenticationPrincipal UtilisateurPrincipal principal) {
        return service.modifierEcheance(principal, id, patch);
    }

    private static <E extends Enum<E>> E parse(Class<E> type, String valeur, String nom) {
        if (valeur == null || valeur.isBlank()) {
            return null;
        }
        try {
            return Enum.valueOf(type, valeur.trim().toUpperCase());
        } catch (IllegalArgumentException e) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Valeur inconnue pour " + nom + " : " + valeur);
        }
    }
}
