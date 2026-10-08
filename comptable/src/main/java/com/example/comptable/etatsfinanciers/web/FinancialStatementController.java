package com.example.comptable.etatsfinanciers.web;

import com.example.comptable.entreprise.service.EntrepriseAccessService;
import com.example.comptable.etatsfinanciers.definition.FinancialStatementDefinition;
import com.example.comptable.etatsfinanciers.definition.FinancialStatementDefinitionRegistry;
import com.example.comptable.etatsfinanciers.definition.FinancialStatementDefinitionRegistry.NotesCatalogue;
import com.example.comptable.etatsfinanciers.definition.StatementType;
import com.example.comptable.etatsfinanciers.service.BalanceSheetService;
import com.example.comptable.etatsfinanciers.service.CashFlowStatementService;
import com.example.comptable.etatsfinanciers.service.IncomeStatementService;
import com.example.comptable.comptabilite.domain.CashFlowEvent;
import com.example.comptable.etatsfinanciers.dossier.CashFlowQualificationService;
import com.example.comptable.etatsfinanciers.dossier.FinancialDossierExportService;
import com.example.comptable.etatsfinanciers.dossier.FinancialDossierService;
import com.example.comptable.etatsfinanciers.notes.NoteWorkspaceService;
import com.example.comptable.etatsfinanciers.service.NotesCatalogueService;
import com.example.comptable.etatsfinanciers.service.NotesService;
import com.example.comptable.etatsfinanciers.service.StatementControlPublisher;
import com.example.comptable.etatsfinanciers.service.StatementDrillDownService;
import com.example.comptable.etatsfinanciers.service.StatementResult;
import com.example.comptable.etatsfinanciers.snapshot.FinancialStatementSnapshotService;
import com.example.comptable.etatsfinanciers.snapshot.FinancialStatementSnapshotService.SnapshotDto;
import com.example.comptable.security.UtilisateurPrincipal;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

/**
 * Etats financiers SYSCOHADA du Systeme normal, calcules a partir des definitions reglementaires versionnees (phase F). Remplace progressivement l'ancien reporting
 * (`/etats-financiers`, deprecie). Les etats {@code INCOMPLETE} (Bilan, TFT) l'indiquent explicitement et ne se finalisent jamais.
 */
@RestController
@RequestMapping("/api/entreprises/{entrepriseId}/etats-financiers-syscohada")
@RequiredArgsConstructor
public class FinancialStatementController {

    private final EntrepriseAccessService entrepriseAccessService;
    private final FinancialStatementDefinitionRegistry registry;
    private final IncomeStatementService incomeStatementService;
    private final BalanceSheetService balanceSheetService;
    private final CashFlowStatementService cashFlowStatementService;
    private final NotesCatalogueService notesCatalogueService;
    private final NoteWorkspaceService noteWorkspaceService;
    private final FinancialDossierService dossierService;
    private final FinancialDossierExportService exportService;
    private final CashFlowQualificationService qualificationService;
    private final StatementControlPublisher controlPublisher;
    private final StatementDrillDownService drillDownService;
    private final FinancialStatementSnapshotService snapshotService;

    /**
     * Contrat HTTP d'un snapshot : le contenu fige est expose comme structure JSON native (map, liste, BigDecimal) et non comme {@code JsonNode} Jackson 2, que le serialiseur HTTP de
     * l'application ne sait pas ecrire (il l'exposerait comme un bean : getters array, bigDecimal...).
     */
    public record SnapshotView(UUID id, UUID exerciceId, StatementType statementType, String framework, String definitionVersion, String definitionHash, String status,
                               String datasetFingerprint, String payloadSha256, java.time.OffsetDateTime createdAt, Object payload) {
        private static final com.fasterxml.jackson.databind.ObjectMapper LECTEUR = new com.fasterxml.jackson.databind.ObjectMapper()
                .enable(com.fasterxml.jackson.databind.DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS);

        static SnapshotView de(SnapshotDto s) {
            return new SnapshotView(s.id(), s.exerciceId(), s.statementType(), s.framework(), s.definitionVersion(), s.definitionHash(), s.status(), s.datasetFingerprint(),
                    s.payloadSha256(), s.createdAt(), LECTEUR.convertValue(s.payload(), Object.class));
        }
    }

    @GetMapping("/definitions/{type}")
    public FinancialStatementDefinition definition(@PathVariable UUID entrepriseId, @PathVariable StatementType type, @AuthenticationPrincipal UtilisateurPrincipal principal) {
        entrepriseAccessService.verifierAcces(entrepriseId, principal);
        return registry.courante(type);
    }

    @GetMapping("/compte-resultat")
    public StatementResult compteResultat(@PathVariable UUID entrepriseId, @RequestParam UUID exerciceId, @AuthenticationPrincipal UtilisateurPrincipal principal) {
        return incomeStatementService.generer(entrepriseId, exerciceId, principal);
    }

    @PostMapping("/compte-resultat/controles")
    public StatementResult controlerCompteResultat(@PathVariable UUID entrepriseId, @RequestParam UUID exerciceId, @AuthenticationPrincipal UtilisateurPrincipal principal) {
        return controlPublisher.publierCompteResultat(entrepriseId, exerciceId, principal);
    }

    @GetMapping("/bilan/actif")
    public StatementResult bilanActif(@PathVariable UUID entrepriseId, @RequestParam UUID exerciceId, @AuthenticationPrincipal UtilisateurPrincipal principal) {
        return balanceSheetService.actif(entrepriseId, exerciceId, principal);
    }

    /** Bilan complet (Actif + Passif, statut, controles BZ = DZ et CJ = XI). */
    @GetMapping("/bilan")
    public BalanceSheetService.BilanResult bilan(@PathVariable UUID entrepriseId, @RequestParam UUID exerciceId, @AuthenticationPrincipal UtilisateurPrincipal principal) {
        return balanceSheetService.bilan(entrepriseId, exerciceId, principal);
    }

    @PostMapping({"/bilan/controles", "/bilan/actif/controles"})
    public BalanceSheetService.BilanResult controlerBilan(@PathVariable UUID entrepriseId, @RequestParam UUID exerciceId, @AuthenticationPrincipal UtilisateurPrincipal principal) {
        return controlPublisher.publierBilan(entrepriseId, exerciceId, principal);
    }

    @GetMapping("/bilan/passif")
    public StatementResult bilanPassif(@PathVariable UUID entrepriseId, @RequestParam UUID exerciceId, @AuthenticationPrincipal UtilisateurPrincipal principal) {
        return balanceSheetService.passif(entrepriseId, exerciceId, principal);
    }

    @GetMapping("/tft")
    public StatementResult tft(@PathVariable UUID entrepriseId, @RequestParam UUID exerciceId, @AuthenticationPrincipal UtilisateurPrincipal principal) {
        return cashFlowStatementService.generer(entrepriseId, exerciceId, principal);
    }

    @PostMapping("/tft/controles")
    public StatementResult controlerTft(@PathVariable UUID entrepriseId, @RequestParam UUID exerciceId, @AuthenticationPrincipal UtilisateurPrincipal principal) {
        return controlPublisher.publierTft(entrepriseId, exerciceId, principal);
    }

    /** Synchronise les Findings des Notes (REQUIRED_NOTE_INCOMPLETE : noteCode, section, champs manquants) avec l'etat courant ; auto-resolution. */
    @PostMapping("/notes/controles")
    public NotesService.NotesResult controlerNotes(@PathVariable UUID entrepriseId, @RequestParam UUID exerciceId, @AuthenticationPrincipal UtilisateurPrincipal principal) {
        return controlPublisher.publierNotes(entrepriseId, exerciceId, principal);
    }

    // ------------------------------------------------------------------ espace de travail des Notes (F8.1)

    @GetMapping("/notes")
    public NoteWorkspaceService.WorkspaceView notes(@PathVariable UUID entrepriseId, @RequestParam UUID exerciceId, @AuthenticationPrincipal UtilisateurPrincipal principal) {
        return noteWorkspaceService.lister(entrepriseId, exerciceId, principal);
    }

    @GetMapping("/notes/{code}")
    public NoteWorkspaceService.NoteView note(@PathVariable UUID entrepriseId, @PathVariable String code, @RequestParam UUID exerciceId,
                                              @AuthenticationPrincipal UtilisateurPrincipal principal) {
        return noteWorkspaceService.lire(entrepriseId, exerciceId, code, principal);
    }

    @PatchMapping("/notes/{code}/manual-data")
    public NoteWorkspaceService.NoteView modifierDonneesManuelles(@PathVariable UUID entrepriseId, @PathVariable String code, @RequestParam UUID exerciceId,
                                                                  @RequestBody NoteWorkspaceService.ManualPatch patch, @AuthenticationPrincipal UtilisateurPrincipal principal) {
        return noteWorkspaceService.modifierDonneesManuelles(entrepriseId, exerciceId, code, patch, principal);
    }

    @PatchMapping("/notes/{code}/applicability")
    public NoteWorkspaceService.NoteView modifierApplicabilite(@PathVariable UUID entrepriseId, @PathVariable String code, @RequestParam UUID exerciceId,
                                                               @RequestBody NoteWorkspaceService.ApplicabilityPatch patch, @AuthenticationPrincipal UtilisateurPrincipal principal) {
        return noteWorkspaceService.modifierApplicabilite(entrepriseId, exerciceId, code, patch, principal);
    }

    @PostMapping("/notes/validate-candidates")
    public NoteWorkspaceService.WorkspaceView validerCandidates(@PathVariable UUID entrepriseId, @RequestParam UUID exerciceId, @AuthenticationPrincipal UtilisateurPrincipal principal) {
        return noteWorkspaceService.validerCandidates(entrepriseId, exerciceId, principal);
    }

    @PostMapping("/notes/{code}/review")
    public NoteWorkspaceService.NoteView relire(@PathVariable UUID entrepriseId, @PathVariable String code, @RequestParam UUID exerciceId,
                                                @RequestParam(required = false) Long expectedVersion, @AuthenticationPrincipal UtilisateurPrincipal principal) {
        return noteWorkspaceService.relire(entrepriseId, exerciceId, code, expectedVersion, principal);
    }

    @PostMapping("/notes/{code}/reopen")
    public NoteWorkspaceService.NoteView rouvrir(@PathVariable UUID entrepriseId, @PathVariable String code, @RequestParam UUID exerciceId,
                                                 @RequestParam(required = false) Long expectedVersion, @AuthenticationPrincipal UtilisateurPrincipal principal) {
        return noteWorkspaceService.rouvrir(entrepriseId, exerciceId, code, expectedVersion, principal);
    }

    @GetMapping("/notes/catalogue")
    public NotesCatalogue catalogueNotes(@PathVariable UUID entrepriseId, @AuthenticationPrincipal UtilisateurPrincipal principal) {
        entrepriseAccessService.verifierAcces(entrepriseId, principal);
        return notesCatalogueService.catalogue();
    }

    @GetMapping("/{type}/lignes/{ligneCode}")
    public StatementDrillDownService.Detail detail(@PathVariable UUID entrepriseId, @PathVariable StatementType type, @PathVariable String ligneCode,
                                                   @RequestParam UUID exerciceId, @RequestParam(required = false) UUID compteId,
                                                   @AuthenticationPrincipal UtilisateurPrincipal principal) {
        return drillDownService.detailler(entrepriseId, exerciceId, type, ligneCode, compteId, principal);
    }

    @PostMapping("/{type}/finaliser")
    public SnapshotView finaliser(@PathVariable UUID entrepriseId, @PathVariable StatementType type, @RequestParam UUID exerciceId,
                                  @AuthenticationPrincipal UtilisateurPrincipal principal) {
        return SnapshotView.de(snapshotService.finaliser(entrepriseId, exerciceId, type, principal));
    }

    // ------------------------------------------------------------------ dossier financier (F9/F10)

    /** Readiness, exceptions, checklist de pre-finalisation et snapshot du dossier : agregation unique, le frontend n'en recalcule rien. */
    @GetMapping("/dossier-financier")
    public FinancialDossierService.DossierSummary dossier(@PathVariable UUID entrepriseId, @RequestParam UUID exerciceId, @AuthenticationPrincipal UtilisateurPrincipal principal) {
        return dossierService.synthese(entrepriseId, exerciceId, principal);
    }

    /** Export imprimable BROUILLON (filigrane) du dossier courant. */
    @GetMapping(value = "/dossier-financier/export", produces = "text/html;charset=UTF-8")
    public String exportBrouillon(@PathVariable UUID entrepriseId, @RequestParam UUID exerciceId, @AuthenticationPrincipal UtilisateurPrincipal principal) {
        return exportService.exporterBrouillon(entrepriseId, exerciceId, principal);
    }

    /** Export imprimable FINAL, rendu uniquement depuis le snapshot immuable. */
    @GetMapping(value = "/snapshots/{snapshotId}/export", produces = "text/html;charset=UTF-8")
    public String exportFinal(@PathVariable UUID entrepriseId, @PathVariable UUID snapshotId, @AuthenticationPrincipal UtilisateurPrincipal principal) {
        return exportService.exporterFinal(entrepriseId, snapshotId, principal);
    }

    @GetMapping("/cash-flow-events")
    public List<CashFlowQualificationService.EventOption> evenementsTresorerie(@PathVariable UUID entrepriseId, @AuthenticationPrincipal UtilisateurPrincipal principal) {
        entrepriseAccessService.verifierAcces(entrepriseId, principal);
        return qualificationService.evenements();
    }

    public record QualificationRequest(CashFlowEvent event) {
    }

    /** Qualifie l'evenement de tresorerie d'une ecriture ; le backend valide la coherence (comptes, sens, tresorerie, montant). */
    @PutMapping("/ecritures/{ecritureId}/cash-flow-event")
    public CashFlowQualificationService.Qualification qualifier(@PathVariable UUID entrepriseId, @PathVariable UUID ecritureId, @RequestBody QualificationRequest request,
                                                                @AuthenticationPrincipal UtilisateurPrincipal principal) {
        return qualificationService.qualifier(entrepriseId, ecritureId, request.event(), principal);
    }

    @GetMapping("/snapshots")
    public List<SnapshotView> snapshots(@PathVariable UUID entrepriseId, @RequestParam UUID exerciceId, @AuthenticationPrincipal UtilisateurPrincipal principal) {
        return snapshotService.lister(entrepriseId, exerciceId, principal).stream().map(SnapshotView::de).toList();
    }

    @GetMapping("/snapshots/{snapshotId}")
    public SnapshotView snapshot(@PathVariable UUID entrepriseId, @PathVariable UUID snapshotId, @AuthenticationPrincipal UtilisateurPrincipal principal) {
        return SnapshotView.de(snapshotService.lire(entrepriseId, snapshotId, principal));
    }
}
