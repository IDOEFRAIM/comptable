package com.example.comptable.controle;

import com.example.comptable.agent.finding.FindingService;
import com.example.comptable.agent.finding.domain.Finding;
import com.example.comptable.agent.finding.repository.FindingRepository;
import com.example.comptable.entreprise.domain.Entreprise;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Aligne les Findings sur les constats detectes pour un perimetre de codes : declare (cree ou met a
 * jour) chaque constat OPEN, puis ferme automatiquement (resolution SYSTEM) les Findings actifs du
 * perimetre qui n'ont plus de constat. Rien n'est supprime ; un Finding ecarte par un humain n'est pas recree.
 */
@Component
public class ControlReconciler {

    public record Bilan(int detectes, int crees, int misAJour, int resolus) {
        public static Bilan vide() {
            return new Bilan(0, 0, 0, 0);
        }

        public Bilan plus(Bilan autre) {
            return new Bilan(detectes + autre.detectes, crees + autre.crees, misAJour + autre.misAJour, resolus + autre.resolus);
        }
    }

    private final FindingService findingService;
    private final FindingRepository findingRepository;

    public ControlReconciler(FindingService findingService, FindingRepository findingRepository) {
        this.findingService = findingService;
        this.findingRepository = findingRepository;
    }

    @Transactional
    public Bilan reconcilier(Entreprise entreprise, Collection<String> codesCouverts, List<ControlResult> detectes) {
        Set<String> clesDetectees = new HashSet<>();
        int crees = 0;
        int misAJour = 0;
        int ouverts = 0;
        for (ControlResult resultat : detectes) {
            if (resultat.status() != ControlStatus.OPEN) {
                continue;
            }
            ouverts++;
            clesDetectees.add(cle(resultat.code(), resultat.entityType(), resultat.entityId()));
            switch (findingService.declarer(entreprise, resultat, ControlCodes.typeFinding(resultat.code())).effet()) {
                case CREE -> crees++;
                case MIS_A_JOUR -> misAJour++;
                default -> {
                }
            }
        }
        int resolus = 0;
        if (!codesCouverts.isEmpty()) {
            for (Finding actif : findingRepository.findActifsParCodes(entreprise.getId(), codesCouverts)) {
                if (!clesDetectees.contains(cle(actif.getControlCode(), actif.getSourceType(), actif.getSourceId()))) {
                    findingService.resoudreAutomatiquement(actif);
                    resolus++;
                }
            }
        }
        return new Bilan(ouverts, crees, misAJour, resolus);
    }

    private static String cle(String code, String entityType, String entityId) {
        return code + '|' + entityType + '|' + entityId;
    }
}
