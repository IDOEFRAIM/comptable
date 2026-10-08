package com.example.comptable.controle;

import com.example.comptable.agent.finding.FindingService;
import com.example.comptable.agent.finding.domain.Finding;
import com.example.comptable.agent.finding.domain.TypeFinding;
import com.example.comptable.entreprise.domain.Entreprise;
import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Pont ControlResult -> Finding (Phase 1, lots 1.7 et 1.9) : transforme un constat qui exige une
 * action humaine en element de travail de l'Inbox, en REUTILISANT {@link FindingService} (donc son
 * idempotence, ses notifications et son audit) - aucun nouvel objet persiste.
 * <p>
 * Deduplication : la cle est {@link ControlResult#sourceKey()} (objet + code de controle), de sorte
 * que deux controles differents sur le meme objet donnent deux Findings distincts, alors que
 * {@code FindingService.signaler} dedoublonne sur (source, OPEN) seulement.
 */
@Component
public class ControlResultPublisher {

    private final FindingService findingService;

    public ControlResultPublisher(FindingService findingService) {
        this.findingService = findingService;
    }

    /** @return le Finding cree, mis a jour ou deja actif pour ce constat ; {@code null} si le constat n'est pas OPEN
     * ou s'il a ete ecarte par un humain. */
    public Finding publier(Entreprise entreprise, ControlResult resultat, TypeFinding type) {
        if (resultat.status() != ControlStatus.OPEN) {
            return null;
        }
        return findingService.declarer(entreprise, resultat, type).finding();
    }

    /** Ferme le Finding d'un constat qui a disparu (le controle ne le detecte plus). */
    public void resoudre(UUID entrepriseId, ControlResult resultat) {
        findingService.resoudreParControle(entrepriseId, resultat.code(), resultat.entityType(), resultat.entityId());
    }
}
