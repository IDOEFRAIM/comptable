package com.example.comptable.etatsfinanciers.dossier;

import com.example.comptable.audit.AuditTrailService;
import com.example.comptable.comptabilite.domain.CashFlowEvent;
import com.example.comptable.comptabilite.domain.EcritureComptable;
import com.example.comptable.comptabilite.repository.EcritureComptableRepository;
import com.example.comptable.entreprise.service.EntrepriseAccessService;
import com.example.comptable.etatsfinanciers.definition.FinancialStatementDefinitionRegistry;
import com.example.comptable.etatsfinanciers.definition.StatementType;
import com.example.comptable.etatsfinanciers.service.CashFlowEventResolver;
import com.example.comptable.etatsfinanciers.snapshot.FinancialStatementSnapshotRepository;
import com.example.comptable.security.UtilisateurPrincipal;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Qualification de l'evenement de tresorerie d'une ecriture existante (annotation, pas une modification comptable : montants, comptes et sens ne changent jamais). Le frontend envoie
 * un {@link CashFlowEvent} ; le backend le valide (comptes, sens, presence de tresorerie, montant) via {@link CashFlowEventResolver#incoherence} : une selection ne suffit jamais.
 * Refusee si un snapshot FINAL existe pour l'exercice (le dossier fige ne se modifie pas).
 */
@Service
@RequiredArgsConstructor
public class CashFlowQualificationService {

    private final EntrepriseAccessService entrepriseAccessService;
    private final EcritureComptableRepository ecritureRepository;
    private final FinancialStatementDefinitionRegistry registry;
    private final CashFlowEventResolver resolver;
    private final FinancialStatementSnapshotRepository snapshotRepository;
    private final AuditTrailService auditTrailService;

    public record Qualification(UUID ecritureId, String numeroPiece, String cashFlowEvent) {
    }

    public record EventOption(String code, String libelle, String ligneTft, boolean encaissement) {
    }

    /** Evenements proposables (enum backend reel, libelles francais) : le frontend n'en invente aucun. */
    public List<EventOption> evenements() {
        return Arrays.stream(CashFlowEvent.values()).map(e -> new EventOption(e.name(), e.libelle(), e.ligneTft(), e.encaissement())).toList();
    }

    @Transactional
    public Qualification qualifier(UUID entrepriseId, UUID ecritureId, CashFlowEvent evenement, UtilisateurPrincipal principal) {
        entrepriseAccessService.verifierAcces(entrepriseId, principal);
        DossierRoles.exigerPreparation(principal);
        EcritureComptable ecriture = ecritureRepository.findById(ecritureId).filter(e -> e.getEntreprise().getId().equals(entrepriseId))
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Ecriture introuvable"));
        boolean fige = snapshotRepository.findByEntrepriseIdAndExerciceIdOrderByCreatedAtDesc(entrepriseId, ecriture.getExercice().getId()).stream()
                .anyMatch(s -> s.getStatementType() == StatementType.DOSSIER_COMPLET);
        if (fige) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Le dossier financier de cet exercice est finalise : l'ecriture ne peut plus etre qualifiee");
        }
        String refus = resolver.incoherence(ecriture, evenement, registry.courante(StatementType.BILAN_ACTIF), registry.courante(StatementType.BILAN_PASSIF)).orElse(null);
        if (refus != null) {
            throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY, "Evenement refuse : " + refus);
        }
        CashFlowEvent avant = ecriture.getCashFlowEvent();
        ecriture.setCashFlowEvent(evenement);
        ecritureRepository.saveAndFlush(ecriture);
        Map<String, Object> details = new LinkedHashMap<>();
        details.put("entreprise", entrepriseId.toString());
        details.put("exercice", ecriture.getExercice().getId().toString());
        details.put("numeroPiece", ecriture.getNumeroPiece());
        details.put("avant", avant == null ? "" : avant.name());
        details.put("apres", evenement == null ? "" : evenement.name());
        auditTrailService.enregistrerActionHumaine(principal.getTenantId(), principal.getId(), "ECRITURE_FLUX_TRESORERIE_QUALIFIE", "ECRITURE", ecritureId.toString(), details);
        return new Qualification(ecritureId, ecriture.getNumeroPiece(), evenement == null ? null : evenement.name());
    }
}
