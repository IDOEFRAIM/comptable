package com.example.comptable.dossier.controles;

import com.example.comptable.comptabilite.parametrage.domain.AccountingAccountMapping;
import com.example.comptable.comptabilite.parametrage.repository.AccountingAccountMappingRepository;
import com.example.comptable.comptabilite.repository.EcritureComptableRepository;
import com.example.comptable.dossier.web.dto.ControlItem;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** Coherence des ecritures de l'exercice et du parametrage comptable (lot 1.10) - controles certains. */
@Component
public class ControleEcrituresCloture implements ClotureControle {

    private final EcritureComptableRepository ecritureComptableRepository;
    private final AccountingAccountMappingRepository mappingRepository;

    public ControleEcrituresCloture(EcritureComptableRepository ecritureComptableRepository,
                                    AccountingAccountMappingRepository mappingRepository) {
        this.ecritureComptableRepository = ecritureComptableRepository;
        this.mappingRepository = mappingRepository;
    }

    @Override
    public List<ControlItem> evaluer(Contexte contexte) {
        return List.of(equilibre(contexte), comptesDesactives(contexte), mappingsInvalides(contexte));
    }

    /** Une ecriture dont debit != credit est une donnee corrompue : cloturer dessus fausserait la balance. */
    private ControlItem equilibre(Contexte c) {
        List<UUID> desequilibrees = ecritureComptableRepository.rechercherEcrituresDesequilibrees(c.exercice().getId());
        if (desequilibrees.isEmpty()) {
            return ClotureControle.ok("equilibre_ecritures", "Equilibre debit / credit",
                    "Toutes les ecritures de l'exercice sont equilibrees.", Map.of());
        }
        String message = desequilibrees.size() + " ecriture(s) desequilibree(s) (debit different du credit).";
        return ClotureControle.bloquant("equilibre_ecritures", "Equilibre debit / credit", message,
                message + " La balance de l'exercice est faussee tant qu'elles ne sont pas corrigees.",
                "ECRITURE", desequilibrees.get(0).toString(),
                desequilibrees.stream().map(UUID::toString).toList(),
                Map.of("nbEcrituresDesequilibrees", desequilibrees.size()));
    }

    /** Un compte desactive apres usage reste dans l'exercice : a verifier, pas necessairement une erreur. */
    private ControlItem comptesDesactives(Contexte c) {
        List<Object[]> lignes = ecritureComptableRepository.rechercherComptesDesactivesUtilises(c.exercice().getId());
        if (lignes.isEmpty()) {
            return ClotureControle.ok("comptes_desactives", "Comptes desactives utilises",
                    "Aucune ecriture de l'exercice n'utilise un compte desactive.", Map.of());
        }
        Map<String, Object> parCompte = new LinkedHashMap<>();
        List<String> numeros = new ArrayList<>();
        for (Object[] ligne : lignes) {
            parCompte.put(String.valueOf(ligne[0]), ((Number) ligne[1]).longValue());
            numeros.add(String.valueOf(ligne[0]));
        }
        String message = lignes.size() + " compte(s) desactive(s) utilise(s) dans l'exercice : " + String.join(", ", numeros) + ".";
        return ClotureControle.avertissement("comptes_desactives", "Comptes desactives utilises", message,
                message + " Reactiver le compte ou reimputer les ecritures concernees avant de cloturer.",
                "COMPTE", numeros.get(0), numeros, Map.of("ecrituresParCompte", parCompte));
    }

    /** Un mapping actif vers un compte desactive : la resolution renverrait INVALID a la prochaine operation. */
    private ControlItem mappingsInvalides(Contexte c) {
        List<AccountingAccountMapping> invalides = mappingRepository.findByEntrepriseId(c.entrepriseId()).stream()
                .filter(AccountingAccountMapping::isActif)
                .filter(m -> !m.getCompte().isActif())
                .toList();
        if (invalides.isEmpty()) {
            return ClotureControle.ok("mappings_parametrage", "Parametrage comptable",
                    "Aucun mapping actif ne designe un compte desactive.", Map.of());
        }
        List<String> detail = invalides.stream()
                .map(m -> m.getPurpose() + " -> " + m.getCompte().getNumero()).toList();
        String message = invalides.size() + " mapping(s) comptable(s) designent un compte desactive : " + String.join(", ", detail) + ".";
        return ClotureControle.avertissement("mappings_parametrage", "Parametrage comptable", message,
                message + " Les prochaines operations concernees seront refusees (compte INVALID) tant que le mapping n'est pas corrige.",
                "MAPPING", invalides.get(0).getId().toString(),
                invalides.stream().map(m -> m.getId().toString()).toList(),
                Map.of("mappings", detail));
    }
}
