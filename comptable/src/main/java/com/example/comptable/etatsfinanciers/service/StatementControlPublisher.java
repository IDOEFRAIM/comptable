package com.example.comptable.etatsfinanciers.service;

import com.example.comptable.controle.ControlCategory;
import com.example.comptable.controle.ControlCodes;
import com.example.comptable.controle.ControlResult;
import com.example.comptable.controle.ControlResultPublisher;
import com.example.comptable.controle.ControlSeverity;
import com.example.comptable.entreprise.domain.Entreprise;
import com.example.comptable.entreprise.service.EntrepriseAccessService;
import com.example.comptable.etatsfinanciers.definition.StatementType;
import com.example.comptable.security.UtilisateurPrincipal;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * Pont etat financier -&gt; Findings (reutilise {@link ControlResultPublisher}, donc l'idempotence, la deduplication et l'audit existants). Les constats ne sont
 * publies par la synchronisation proactive ({@code FinancialControlSynchronizationService}, declenchee par les operations metier) ou a la demande : chaque constat present est declare ; chaque constat du perimetre qui a disparu est resolu (source SYSTEM).
 */
@Component
@RequiredArgsConstructor
public class StatementControlPublisher {

    private static final List<String> CODES = List.of(ControlCodes.UNMAPPED_ACCOUNT, ControlCodes.AMBIGUOUS_MAPPING, ControlCodes.RESULT_MISMATCH,
            ControlCodes.BALANCE_SHEET_IMBALANCE, ControlCodes.CASH_FLOW_MISMATCH, ControlCodes.UNRESOLVED_CAFG_COMPONENT, ControlCodes.UNQUALIFIED_CASH_EVENT);

    private final EntrepriseAccessService entrepriseAccessService;
    private final com.example.comptable.entreprise.repository.EntrepriseRepository entrepriseRepository;
    private final ControlResultPublisher controlResultPublisher;
    private final IncomeStatementService incomeStatementService;
    private final BalanceSheetService balanceSheetService;
    private final CashFlowStatementService cashFlowStatementService;
    private final com.example.comptable.etatsfinanciers.notes.NoteWorkspaceService noteWorkspaceService;

    /** Calcule le compte de resultat et synchronise ses constats avec les Findings. */
    @Transactional
    public StatementResult publierCompteResultat(UUID entrepriseId, UUID exerciceId, UtilisateurPrincipal principal) {
        Entreprise entreprise = entreprise(entrepriseId, principal);
        StatementResult etat = incomeStatementService.calculer(entrepriseId, exerciceId);
        synchroniser(entreprise, exerciceId, StatementType.COMPTE_RESULTAT, etat.controls());
        return etat;
    }

    /** Calcule le Bilan (Actif + Passif) et synchronise ses constats avec les Findings (portes par l'etat BILAN_ACTIF). */
    @Transactional
    public BalanceSheetService.BilanResult publierBilan(UUID entrepriseId, UUID exerciceId, UtilisateurPrincipal principal) {
        Entreprise entreprise = entreprise(entrepriseId, principal);
        BalanceSheetService.BilanResult bilan = balanceSheetService.calculer(entrepriseId, exerciceId);
        synchroniser(entreprise, exerciceId, StatementType.BILAN_ACTIF, bilan.controls());
        return bilan;
    }

    /** Calcule le TFT et synchronise ses constats (comptes non classes, CASH_FLOW_MISMATCH) avec les Findings. */
    @Transactional
    public StatementResult publierTft(UUID entrepriseId, UUID exerciceId, UtilisateurPrincipal principal) {
        Entreprise entreprise = entreprise(entrepriseId, principal);
        StatementResult etat = cashFlowStatementService.calculer(entrepriseId, exerciceId);
        synchroniser(entreprise, exerciceId, StatementType.TFT, etat.controls());
        return etat;
    }

    /** Genere les Notes et synchronise un constat REQUIRED_NOTE_INCOMPLETE par note applicable incomplete (resolu quand la cause disparait). */
    @Transactional
    public NotesService.NotesResult publierNotes(UUID entrepriseId, UUID exerciceId, UtilisateurPrincipal principal) {
        Entreprise entreprise = entreprise(entrepriseId, principal);
        NotesService.NotesResult notes = principal == null ? noteWorkspaceService.resultatSysteme(entrepriseId, exerciceId) : noteWorkspaceService.resultat(entrepriseId, exerciceId, principal);
        Set<String> presents = new java.util.HashSet<>();
        for (ControlResult constat : notes.controls()) {
            presents.add(constat.entityId());
            controlResultPublisher.publier(entreprise, constat, ControlCodes.typeFinding(constat.code()));
        }
        for (NotesService.NoteResult note : notes.notes()) {
            String entityId = StatementControls.entityId(exerciceId, StatementType.NOTES_R4) + ":" + note.code();
            if (!presents.contains(entityId)) {
                controlResultPublisher.resoudre(entreprise.getId(), ControlResult.ouvert(ControlCodes.REQUIRED_NOTE_INCOMPLETE, ControlCategory.ACCOUNTING, ControlSeverity.WARNING,
                        entreprise.getId(), StatementControls.ENTITY_TYPE, entityId, "", "", List.of(), java.util.Map.of(), null));
            }
        }
        return notes;
    }

    /** Avec un utilisateur : controle d'acces habituel ; sans utilisateur (synchronisation proactive declenchee par une operation metier) : l'entreprise est chargee telle quelle. */
    private Entreprise entreprise(UUID entrepriseId, UtilisateurPrincipal principal) {
        return principal == null ? entrepriseRepository.findById(entrepriseId).orElseThrow(() -> new IllegalArgumentException("Entreprise introuvable : " + entrepriseId))
                : entrepriseAccessService.verifierAcces(entrepriseId, principal);
    }

    /** Les constats du TFT sur la CAFG et sur les evenements de tresorerie portent un identifiant d'entite suffixe (un constat par sujet) : l'auto-resolution doit viser le meme. */
    private static String suffixe(String code) {
        return switch (code) {
            case ControlCodes.UNRESOLVED_CAFG_COMPONENT -> ":CAFG";
            case ControlCodes.UNQUALIFIED_CASH_EVENT -> ":EVENEMENTS";
            default -> "";
        };
    }

    void synchroniser(Entreprise entreprise, UUID exerciceId, StatementType type, List<ControlResult> constats) {
        Set<String> presents = new java.util.HashSet<>();
        for (ControlResult constat : constats) {
            presents.add(constat.code());
            controlResultPublisher.publier(entreprise, constat, ControlCodes.typeFinding(constat.code()));
        }
        for (String code : CODES) {
            if (!presents.contains(code)) {
                controlResultPublisher.resoudre(entreprise.getId(), ControlResult.ouvert(code, ControlCategory.ACCOUNTING, ControlSeverity.ERROR, entreprise.getId(),
                        StatementControls.ENTITY_TYPE, StatementControls.entityId(exerciceId, type) + suffixe(code), "", "", List.of(), java.util.Map.of(), null));
            }
        }
    }
}
