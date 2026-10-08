package com.example.comptable.etatsfinanciers.dossier;

import com.example.comptable.comptabilite.domain.ExerciceComptable;
import com.example.comptable.comptabilite.domain.StatutEcriture;
import com.example.comptable.comptabilite.domain.StatutExercice;
import com.example.comptable.comptabilite.repository.EcritureComptableRepository;
import com.example.comptable.comptabilite.repository.ExerciceComptableRepository;
import com.example.comptable.controle.ControlCodes;
import com.example.comptable.controle.ControlResult;
import com.example.comptable.entreprise.service.EntrepriseAccessService;
import com.example.comptable.etatsfinanciers.definition.StatementType;
import com.example.comptable.etatsfinanciers.notes.NoteWorkspaceService;
import com.example.comptable.etatsfinanciers.notes.NoteWorkspaceService.NoteView;
import com.example.comptable.etatsfinanciers.service.BalanceSheetService;
import com.example.comptable.etatsfinanciers.service.CashFlowEventResolver;
import com.example.comptable.etatsfinanciers.service.CashFlowStatementService;
import com.example.comptable.etatsfinanciers.service.IncomeStatementService;
import com.example.comptable.etatsfinanciers.service.StatementResult;
import com.example.comptable.etatsfinanciers.service.StatementResult.Status;
import com.example.comptable.etatsfinanciers.snapshot.FinancialStatementSnapshot;
import com.example.comptable.etatsfinanciers.snapshot.FinancialStatementSnapshotRepository;
import com.example.comptable.identite.repository.UtilisateurRepository;
import com.example.comptable.security.UtilisateurPrincipal;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Agregation de la readiness du dossier financier d'un exercice : statuts reels des etats (jamais recalcules ni adoucis cote frontend), travail par exception, checklist de
 * pre-finalisation, informations du snapshot FINAL. {@code finalizable} est decide ICI : exercice cloture, Compte de resultat, Bilan, TFT et Notes READY, aucune exception
 * bloquante, pas encore finalise. Aucun contournement (ni force, ni ignoreWarnings) : les bloquants restent bloquants.
 */
@Service
@RequiredArgsConstructor
public class FinancialDossierService {

    private final EntrepriseAccessService entrepriseAccessService;
    private final ExerciceComptableRepository exerciceRepository;
    private final IncomeStatementService incomeStatementService;
    private final BalanceSheetService balanceSheetService;
    private final CashFlowStatementService cashFlowStatementService;
    private final NoteWorkspaceService noteWorkspaceService;
    private final FinancialStatementSnapshotRepository snapshotRepository;
    private final UtilisateurRepository utilisateurRepository;
    private final EcritureComptableRepository ecritureRepository;

    public record StateStatus(String status, List<String> reasons) {
    }

    public record BalanceCheck(BigDecimal totalActif, BigDecimal totalPassif, BigDecimal ecart, boolean equilibre) {
    }

    public record NotesProgress(int total, int decided, int toDecide, int candidates, int toComplete, int ready, int reviewed, int notApplicable, int blocked, String status,
                                List<String> reasons) {
    }

    public record ChecklistItem(String code, String label, String status, String detail) {
    }

    /** Un element necessitant une intervention : categorie, gravite (BLOCKING ou WARNING), onglet cible et cible precise (compte, ecriture, note). */
    public record ExceptionItem(String id, String code, String severity, String category, String title, String detail, String tab, Map<String, Object> target) {
    }

    public record SnapshotInfo(UUID id, OffsetDateTime createdAt, String createdBy, String framework, String definitionVersion, String payloadSha256, String definitionHash,
                               boolean liveChangedSinceFinal) {
    }

    public record Permissions(boolean prepare, boolean review, boolean finalise) {
    }

    public record DossierSummary(UUID exerciseId, LocalDate debut, LocalDate fin, String exerciseStatus, StateStatus incomeStatement, StateStatus balanceSheet,
                                 BalanceCheck balanceCheck, StateStatus cashFlow, NotesProgress notes, int blockingFindings, int warnings, boolean finalizable, boolean finalised,
                                 List<ChecklistItem> checklist, List<ExceptionItem> exceptions, SnapshotInfo snapshot, Permissions permissions, OffsetDateTime generatedAt) {
    }

    @Transactional
    public DossierSummary synthese(UUID entrepriseId, UUID exerciceId, UtilisateurPrincipal principal) {
        entrepriseAccessService.verifierAcces(entrepriseId, principal);
        ExerciceComptable exercice = exerciceRepository.findById(exerciceId).filter(e -> e.getEntreprise().getId().equals(entrepriseId))
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Exercice introuvable"));

        StatementResult cr = incomeStatementService.generer(entrepriseId, exerciceId, principal);
        BalanceSheetService.BilanResult bilan = balanceSheetService.bilan(entrepriseId, exerciceId, principal);
        StatementResult tft = cashFlowStatementService.generer(entrepriseId, exerciceId, principal);
        List<CashFlowEventResolver.EcritureNonQualifiee> nonQualifiees = cashFlowStatementService.ecrituresNonQualifiees(entrepriseId, exerciceId, principal);
        NoteWorkspaceService.WorkspaceView notes = noteWorkspaceService.lister(entrepriseId, exerciceId, principal);

        boolean cloture = exercice.getStatut() == StatutExercice.CLOTURE;
        NotesProgress progression = progression(notes);
        List<ExceptionItem> exceptions = new ArrayList<>();
        if (!cloture) {
            exceptions.add(new ExceptionItem("exercice", "EXERCICE_OUVERT", "BLOCKING", "EXERCICE", "Exercice non cloture",
                    "L'exercice doit etre cloture avant la finalisation du dossier financier.", "VERSION_FINALE", Map.of()));
        }
        exceptionsEtat(exceptions, cr, "COMPTE_RESULTAT");
        exceptionsBilan(exceptions, bilan);
        exceptionsTft(exceptions, tft, nonQualifiees);
        exceptionsNotes(exceptions, notes);
        long brouillons = ecritureRepository.countByExercice_IdAndStatut(exerciceId, StatutEcriture.BROUILLON);
        if (brouillons > 0) {
            exceptions.add(new ExceptionItem("brouillons", "ECRITURES_BROUILLON", "WARNING", "COMPTABILITE", brouillons + " ecriture(s) en brouillon",
                    "Les ecritures en brouillon ne sont pas prises en compte dans les etats : les valider ou les supprimer.", "VUE_ENSEMBLE", Map.of("nombre", brouillons)));
        }
        int bloquants = (int) exceptions.stream().filter(e -> e.severity().equals("BLOCKING")).count();
        int avertissements = exceptions.size() - bloquants;

        List<FinancialStatementSnapshot> snapshots = snapshotRepository.findByEntrepriseIdAndExerciceIdOrderByCreatedAtDesc(entrepriseId, exerciceId).stream()
                .filter(s -> s.getStatementType() == StatementType.DOSSIER_COMPLET).toList();
        SnapshotInfo info = snapshots.isEmpty() ? null : snapshotInfo(snapshots.get(0), cr);
        boolean finalise = info != null;

        boolean tousReady = cr.status() == Status.READY && bilan.status() == Status.READY && tft.status() == Status.READY && notes.status() == Status.READY;
        boolean finalisable = cloture && tousReady && bloquants == 0 && !finalise;

        BigDecimal ecart = bilan.totalActifN().subtract(bilan.totalPassifN());
        List<ChecklistItem> checklist = List.of(
                new ChecklistItem("EXERCICE", "Exercice cloture", cloture ? "OK" : "BLOCKED", cloture ? "Cloture" : "Ouvert"),
                new ChecklistItem("COMPTE_RESULTAT", "Compte de resultat", cr.status() == Status.READY ? "OK" : "BLOCKED", cr.status().name()),
                new ChecklistItem("BILAN", "Bilan", bilan.status() == Status.READY ? "OK" : "BLOCKED", bilan.status().name()),
                new ChecklistItem("TFT", "Tableau des flux de tresorerie", tft.status() == Status.READY ? "OK" : "BLOCKED", tft.status().name()),
                new ChecklistItem("NOTES", "Notes annexes", notes.status() == Status.READY ? "OK" : "BLOCKED",
                        progression.toDecide() + progression.candidates() + " a decider ou valider, " + progression.toComplete() + " a completer"),
                new ChecklistItem("CONTROLES", "Aucun controle bloquant", bloquants == 0 ? "OK" : "BLOCKED", bloquants + " element(s) bloquant(s)"),
                new ChecklistItem("BROUILLONS", "Aucune ecriture en brouillon", brouillons == 0 ? "OK" : "WARNING", brouillons + " brouillon(s)"));

        return new DossierSummary(exerciceId, exercice.getDateDebut(), exercice.getDateFin(), exercice.getStatut().name(), new StateStatus(cr.status().name(), cr.statusReasons()),
                new StateStatus(bilan.status().name(), bilan.reasons()), new BalanceCheck(bilan.totalActifN(), bilan.totalPassifN(), ecart, ecart.signum() == 0),
                new StateStatus(tft.status().name(), tft.statusReasons()), progression, bloquants, avertissements, finalisable, finalise, checklist, exceptions, info,
                new Permissions(DossierRoles.peutPreparer(principal), DossierRoles.peutRevoir(principal), DossierRoles.peutFinaliser(principal)), OffsetDateTime.now());
    }

    // ------------------------------------------------------------------------------------------------ exceptions

    private void exceptionsEtat(List<ExceptionItem> exceptions, StatementResult etat, String onglet) {
        for (StatementResult.AccountRef a : etat.unmapped()) {
            exceptions.add(new ExceptionItem("unmapped-" + onglet + "-" + a.number(), ControlCodes.UNMAPPED_ACCOUNT, "BLOCKING", "COMPTE_NON_MAPPE", "Compte " + a.number() + " non mappe",
                    a.label() + " - solde : " + a.balanceN().toPlainString(), onglet, Map.of("accountId", a.accountId(), "number", a.number(), "balance", a.balanceN())));
        }
        for (StatementResult.Ambiguous a : etat.ambiguous()) {
            exceptions.add(new ExceptionItem("ambiguous-" + onglet + "-" + a.number(), ControlCodes.AMBIGUOUS_MAPPING, "BLOCKING", "MAPPING_AMBIGU", "Compte " + a.number() + " a mapping ambigu",
                    a.label() + " - lignes candidates : " + String.join(", ", a.candidateLines()), onglet, Map.of("accountId", a.accountId(), "number", a.number())));
        }
        for (ControlResult c : etat.controls()) {
            if (c.code().equals(ControlCodes.RESULT_MISMATCH)) {
                exceptions.add(controle(c, "ECART_RESULTAT", onglet));
            }
        }
    }

    private void exceptionsBilan(List<ExceptionItem> exceptions, BalanceSheetService.BilanResult bilan) {
        exceptionsEtat(exceptions, bilan.actif(), "BILAN");
        for (ControlResult c : bilan.controls()) {
            if (c.code().equals(ControlCodes.BALANCE_SHEET_IMBALANCE)) {
                exceptions.add(controle(c, "BILAN_DESEQUILIBRE", "BILAN"));
            } else if (c.code().equals(ControlCodes.RESULT_MISMATCH)) {
                exceptions.add(controle(c, "ECART_RESULTAT", "BILAN"));
            }
        }
        for (String raison : bilan.reasons()) {
            if (raison.startsWith("Actif ")) {
                exceptions.add(new ExceptionItem("ligne-" + raison.hashCode(), "BILAN_LIGNE_INCOMPLETE", "BLOCKING", "LIGNE_PARTIELLE", raison.substring(0, Math.min(60, raison.length())),
                        raison, "BILAN", Map.of()));
            }
        }
    }

    private void exceptionsTft(List<ExceptionItem> exceptions, StatementResult tft, List<CashFlowEventResolver.EcritureNonQualifiee> nonQualifiees) {
        for (ControlResult c : tft.controls()) {
            switch (c.code()) {
                case ControlCodes.CASH_FLOW_MISMATCH -> exceptions.add(controle(c, "ECART_TFT", "TFT"));
                case ControlCodes.UNRESOLVED_CAFG_COMPONENT -> exceptions.add(controle(c, "CAFG_NON_RESOLUE", "TFT"));
                case ControlCodes.UNMAPPED_ACCOUNT -> exceptions.add(controle(c, "COMPTE_NON_MAPPE", "TFT"));
                default -> {
                }
            }
        }
        for (CashFlowEventResolver.EcritureNonQualifiee e : nonQualifiees) {
            Map<String, Object> cible = new LinkedHashMap<>();
            cible.put("ecritureId", e.ecritureId());
            cible.put("numeroPiece", e.numeroPiece());
            cible.put("date", e.date());
            cible.put("libelle", e.libelle());
            cible.put("tresorerie", e.tresorerie());
            cible.put("lignesTft", e.lignesTft());
            cible.put("evenementDeclare", e.evenementDeclare());
            cible.put("lignes", e.lignes());
            exceptions.add(new ExceptionItem("cash-" + e.ecritureId(), ControlCodes.UNQUALIFIED_CASH_EVENT, "BLOCKING", "FLUX_NON_QUALIFIE",
                    "Flux de tresorerie non qualifie - ecriture " + e.numeroPiece(), String.join(" ; ", e.raisons()), "TFT", cible));
        }
    }

    private void exceptionsNotes(List<ExceptionItem> exceptions, NoteWorkspaceService.WorkspaceView notes) {
        int aDecider = 0;
        int candidates = 0;
        for (NoteView n : notes.notes()) {
            if (n.attached() && !n.completionStatus().equals("READY")) {
                String detail = n.gaps().stream().map(g -> g.section() + " : " + g.missingInformation()).reduce((a, b) -> a + " ; " + b).orElse("");
                exceptions.add(new ExceptionItem("note-" + n.noteCode(), ControlCodes.REQUIRED_NOTE_INCOMPLETE, "BLOCKING", "NOTE_INCOMPLETE",
                        "Note " + n.noteCode() + " incomplete - " + n.title(), detail, "NOTES", Map.of("noteCode", n.noteCode(), "completionStatus", n.completionStatus())));
            } else if (n.applicabilityStatus().equals("NOT_APPLICABLE_CANDIDATE")) {
                candidates++;
            } else if (n.applicabilityStatus().equals("TO_DETERMINE")) {
                aDecider++;
            }
        }
        if (candidates > 0) {
            exceptions.add(new ExceptionItem("notes-candidates", "NOTES_CANDIDATES", "BLOCKING", "NOTE_A_DECIDER", candidates + " suggestion(s) de non-applicabilite a valider",
                    "Le moteur a prouve par les comptes l'absence d'objet de ces notes ; la validation reste une decision humaine.", "NOTES", Map.of("nombre", candidates)));
        }
        if (aDecider > 0) {
            exceptions.add(new ExceptionItem("notes-a-decider", "NOTES_A_DECIDER", "BLOCKING", "NOTE_A_DECIDER", aDecider + " note(s) dont l'applicabilite reste a decider",
                    "Aucune preuve comptable ne permet de conclure : decision humaine requise.", "NOTES", Map.of("nombre", aDecider)));
        }
    }

    private static ExceptionItem controle(ControlResult c, String categorie, String onglet) {
        Map<String, Object> cible = new LinkedHashMap<>(c.metadata() == null ? Map.of() : c.metadata());
        return new ExceptionItem(c.code() + "-" + onglet, c.code(), "BLOCKING", categorie, c.message(), c.explanation(), onglet, cible);
    }

    private NotesProgress progression(NoteWorkspaceService.WorkspaceView vue) {
        int decided = 0;
        int toDecide = 0;
        int candidates = 0;
        int toComplete = 0;
        int ready = 0;
        int reviewed = 0;
        int na = 0;
        int blocked = 0;
        for (NoteView n : vue.notes()) {
            switch (n.applicabilityStatus()) {
                case "APPLICABLE", "NOT_APPLICABLE" -> decided++;
                case "NOT_APPLICABLE_CANDIDATE" -> candidates++;
                default -> toDecide++;
            }
            switch (n.completionStatus()) {
                case "READY" -> {
                    ready++;
                    if (n.review().valid()) {
                        reviewed++;
                    }
                }
                case "NOT_APPLICABLE" -> na++;
                default -> {
                    if (n.attached()) {
                        toComplete++;
                    }
                }
            }
            if (n.schemaStatus().equals("PARTIAL") && n.attached()) {
                blocked++;
            }
        }
        return new NotesProgress(vue.notes().size(), decided, toDecide, candidates, toComplete, ready, reviewed, na, blocked, vue.status().name(), vue.statusReasons());
    }

    private SnapshotInfo snapshotInfo(FinancialStatementSnapshot s, StatementResult crLive) {
        String auteur = s.getCreatedBy() == null ? null : utilisateurRepository.findById(s.getCreatedBy()).map(u -> (u.getPrenom() + " " + u.getNom()).trim()).orElse(null);
        boolean modifie = crLive.dataset() != null && !crLive.dataset().fingerprint().equals(s.getDatasetFingerprint());
        return new SnapshotInfo(s.getId(), s.getCreatedAt(), auteur, s.getFramework(), s.getDefinitionVersion(), s.getPayloadSha256(), s.getDefinitionHash(), modifie);
    }
}
