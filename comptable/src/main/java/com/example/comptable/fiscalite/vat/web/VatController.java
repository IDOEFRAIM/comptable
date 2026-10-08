package com.example.comptable.fiscalite.vat.web;

import com.example.comptable.fiscalite.vat.VatDtos.*;
import com.example.comptable.fiscalite.vat.VatModel.Line;
import com.example.comptable.fiscalite.vat.VatSettlementService;
import com.example.comptable.fiscalite.vat.VatWorkspaceService;
import com.example.comptable.security.UtilisateurPrincipal;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.time.LocalDate;

import java.util.List;
import java.util.UUID;

/**
 * Espace TVA d'une entreprise. COLLABORATEUR et EXPERT_COMPTABLE seulement (un CLIENT n'a aucun accès fiscal) ; préparation par les deux, revue et finalisation par l'expert ; une entreprise d'un autre cabinet est
 * introuvable (404).
 */
@RestController
@RequestMapping("/api/entreprises/{entrepriseId}/fiscalite/tva")
@RequiredArgsConstructor
public class VatController {

    private final VatWorkspaceService service;
    private final VatSettlementService reglement;

    @GetMapping("/periodes")
    public List<PeriodSummary> periodes(@PathVariable UUID entrepriseId, @RequestParam(required = false) String statut, @RequestParam(required = false) String frameworkVersion,
                                        @AuthenticationPrincipal UtilisateurPrincipal principal) {
        return service.lister(principal, entrepriseId, statut, frameworkVersion);
    }

    @PostMapping("/periodes")
    @ResponseStatus(HttpStatus.CREATED)
    public PeriodView ouvrir(@PathVariable UUID entrepriseId, @RequestBody OpenRequest request, @AuthenticationPrincipal UtilisateurPrincipal principal) {
        return service.ouvrir(principal, entrepriseId, request);
    }

    @GetMapping("/periodes/{periodeId}")
    public PeriodView detail(@PathVariable UUID entrepriseId, @PathVariable UUID periodeId, @AuthenticationPrincipal UtilisateurPrincipal principal) {
        return service.detail(principal, entrepriseId, periodeId);
    }

    /** Liste paginée côté base : {@code cote} (SALES / PURCHASES), {@code statut} (TO_REVIEW / OK), {@code traitement}, {@code constatActif}, dates, recherche {@code q}, tri {@code tri} (date, document, amount, status, tiers) et {@code sens} (asc / desc). */
    @GetMapping("/periodes/{periodeId}/operations")
    public Page<Line> operations(@PathVariable UUID entrepriseId, @PathVariable UUID periodeId, @RequestParam(required = false) String cote, @RequestParam(required = false) String statut,
                                 @RequestParam(required = false) String traitement, @RequestParam(defaultValue = "false") boolean constatActif, @RequestParam(required = false) LocalDate dateDebut,
                                 @RequestParam(required = false) LocalDate dateFin, @RequestParam(required = false) String q, @RequestParam(defaultValue = "date") String tri,
                                 @RequestParam(defaultValue = "asc") String sens, @RequestParam(defaultValue = "0") int page, @RequestParam(defaultValue = "25") int taille,
                                 @AuthenticationPrincipal UtilisateurPrincipal principal) {
        return service.operations(principal, entrepriseId, periodeId, new VatWorkspaceService.OperationFilters(cote, statut, traitement, constatActif, dateDebut, dateFin, q, tri, !"desc".equalsIgnoreCase(sens)), page, taille);
    }

    @PostMapping("/periodes/{periodeId}/operations/traitement-groupe")
    public BulkTreatmentResult traitementGroupe(@PathVariable UUID entrepriseId, @PathVariable UUID periodeId, @RequestBody BulkTreatmentRequest request, @AuthenticationPrincipal UtilisateurPrincipal principal) {
        exiger(request.expectedVersion());
        return service.traiterEnMasse(principal, entrepriseId, periodeId, request);
    }

    @GetMapping("/periodes/{periodeId}/operations/{ecritureId}/origine")
    public LineageView origine(@PathVariable UUID entrepriseId, @PathVariable UUID periodeId, @PathVariable UUID ecritureId, @AuthenticationPrincipal UtilisateurPrincipal principal) {
        return service.origine(principal, entrepriseId, periodeId, ecritureId);
    }

    @PutMapping("/periodes/{periodeId}/operations/{ecritureId}/traitement")
    public PeriodView decider(@PathVariable UUID entrepriseId, @PathVariable UUID periodeId, @PathVariable UUID ecritureId, @RequestBody TreatmentRequest request,
                              @AuthenticationPrincipal UtilisateurPrincipal principal) {
        exiger(request.expectedVersion());
        return service.decider(principal, entrepriseId, periodeId, ecritureId, request);
    }

    @GetMapping("/periodes/{periodeId}/ajustements")
    public List<AdjustmentView> ajustements(@PathVariable UUID entrepriseId, @PathVariable UUID periodeId, @AuthenticationPrincipal UtilisateurPrincipal principal) {
        return service.ajustements(principal, entrepriseId, periodeId);
    }

    @PostMapping("/periodes/{periodeId}/ajustements")
    @ResponseStatus(HttpStatus.CREATED)
    public PeriodView ajouterAjustement(@PathVariable UUID entrepriseId, @PathVariable UUID periodeId, @RequestBody AdjustmentRequest request, @AuthenticationPrincipal UtilisateurPrincipal principal) {
        exiger(request.expectedVersion());
        return service.ajouterAjustement(principal, entrepriseId, periodeId, request);
    }

    @PostMapping("/periodes/{periodeId}/ajustements/{ajustementId}/annuler")
    public PeriodView annulerAjustement(@PathVariable UUID entrepriseId, @PathVariable UUID periodeId, @PathVariable UUID ajustementId, @RequestBody CancelRequest request,
                                        @AuthenticationPrincipal UtilisateurPrincipal principal) {
        exiger(request.expectedVersion());
        return service.annulerAjustement(principal, entrepriseId, periodeId, ajustementId, request);
    }

    @GetMapping("/periodes/{periodeId}/controles")
    public ControlsView controles(@PathVariable UUID entrepriseId, @PathVariable UUID periodeId, @AuthenticationPrincipal UtilisateurPrincipal principal) {
        return service.controles(principal, entrepriseId, periodeId);
    }

    @PostMapping("/periodes/{periodeId}/recalculer")
    public PeriodView recalculer(@PathVariable UUID entrepriseId, @PathVariable UUID periodeId, @RequestBody(required = false) VersionRequest request, @AuthenticationPrincipal UtilisateurPrincipal principal) {
        return service.recalculer(principal, entrepriseId, periodeId, request == null ? null : request.expectedVersion());
    }

    @PostMapping("/periodes/{periodeId}/preparer")
    public PeriodView preparer(@PathVariable UUID entrepriseId, @PathVariable UUID periodeId, @RequestBody VersionRequest request, @AuthenticationPrincipal UtilisateurPrincipal principal) {
        return service.preparer(principal, entrepriseId, periodeId, exiger(request.expectedVersion()));
    }

    @PostMapping("/periodes/{periodeId}/revoir")
    public PeriodView revoir(@PathVariable UUID entrepriseId, @PathVariable UUID periodeId, @RequestBody VersionRequest request, @AuthenticationPrincipal UtilisateurPrincipal principal) {
        return service.revoir(principal, entrepriseId, periodeId, exiger(request.expectedVersion()));
    }

    @PostMapping("/periodes/{periodeId}/rouvrir")
    public PeriodView rouvrir(@PathVariable UUID entrepriseId, @PathVariable UUID periodeId, @RequestBody VersionRequest request, @AuthenticationPrincipal UtilisateurPrincipal principal) {
        return service.rouvrir(principal, entrepriseId, periodeId, exiger(request.expectedVersion()));
    }

    @PostMapping("/periodes/{periodeId}/finaliser")
    public PeriodView finaliser(@PathVariable UUID entrepriseId, @PathVariable UUID periodeId, @RequestBody VersionRequest request, @AuthenticationPrincipal UtilisateurPrincipal principal) {
        return service.finaliser(principal, entrepriseId, periodeId, exiger(request.expectedVersion()));
    }

    @GetMapping("/periodes/{periodeId}/declaration")
    public DeclarationView declaration(@PathVariable UUID entrepriseId, @PathVariable UUID periodeId, @AuthenticationPrincipal UtilisateurPrincipal principal) {
        return service.declaration(principal, entrepriseId, periodeId);
    }

    @GetMapping("/periodes/{periodeId}/snapshot")
    public SnapshotView snapshot(@PathVariable UUID entrepriseId, @PathVariable UUID periodeId, @AuthenticationPrincipal UtilisateurPrincipal principal) {
        return service.snapshot(principal, entrepriseId, periodeId);
    }

    // ---------------------------------------------------------------------------------------------------------------------------------------------- règlement interne et crédit d'ouverture

    @GetMapping("/periodes/{periodeId}/paiements")
    public PaymentSummary paiements(@PathVariable UUID entrepriseId, @PathVariable UUID periodeId, @AuthenticationPrincipal UtilisateurPrincipal principal) {
        return reglement.paiements(principal, entrepriseId, periodeId);
    }

    @PostMapping("/periodes/{periodeId}/paiements")
    @ResponseStatus(HttpStatus.CREATED)
    public PaymentSummary enregistrerPaiement(@PathVariable UUID entrepriseId, @PathVariable UUID periodeId, @RequestBody PaymentRequest request, @AuthenticationPrincipal UtilisateurPrincipal principal) {
        exiger(request.expectedVersion());
        return reglement.enregistrer(principal, entrepriseId, periodeId, request);
    }

    @PostMapping("/periodes/{periodeId}/paiements/{paiementId}/annuler")
    public PaymentSummary annulerPaiement(@PathVariable UUID entrepriseId, @PathVariable UUID periodeId, @PathVariable UUID paiementId, @RequestBody ReverseRequest request,
                                          @AuthenticationPrincipal UtilisateurPrincipal principal) {
        exiger(request.expectedVersion());
        return reglement.annuler(principal, entrepriseId, periodeId, paiementId, request);
    }

    @GetMapping("/periodes/{periodeId}/credit-ouverture")
    public CreditView credit(@PathVariable UUID entrepriseId, @PathVariable UUID periodeId, @AuthenticationPrincipal UtilisateurPrincipal principal) {
        return reglement.credit(principal, entrepriseId, periodeId);
    }

    @PutMapping("/periodes/{periodeId}/credit-ouverture")
    public CreditView saisirCredit(@PathVariable UUID entrepriseId, @PathVariable UUID periodeId, @RequestBody CreditRequest request, @AuthenticationPrincipal UtilisateurPrincipal principal) {
        exiger(request.expectedVersion());
        return reglement.saisirCredit(principal, entrepriseId, periodeId, request);
    }

    /**
     * Toute mutation utilisateur d'une période exige la version lue (optimistic locking) : sans elle, une écriture pourrait en écraser une autre sans que personne ne le sache. Absence : 422 ; version périmée :
     * 409 (levé par le service). Les appels internes (tâches système, tests de service) ne passent pas par ce contrôleur.
     */
    private static Long exiger(Long expectedVersion) {
        if (expectedVersion == null) {
            throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY, "expectedVersion est obligatoire : lisez la période avant de la modifier");
        }
        return expectedVersion;
    }
}
