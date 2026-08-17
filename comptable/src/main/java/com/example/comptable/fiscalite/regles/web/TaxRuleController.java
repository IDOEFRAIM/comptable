package com.example.comptable.fiscalite.regles.web;

import com.example.comptable.fiscalite.regles.repository.TaxRuleRepository;
import com.example.comptable.fiscalite.regles.web.dto.TaxRuleDto;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/** Mission P1 (section 31) : lecture des regles fiscales versionnees. Retourne aujourd'hui une
 * liste vide pour tout pays - voir la javadoc de {@code TaxRule} pour la raison (aucune source
 * reglementaire verifiee disponible pour peupler la table dans ce chantier). */
@RestController
@RequestMapping("/api/fiscalite/tax-rules")
@RequiredArgsConstructor
public class TaxRuleController {

    private final TaxRuleRepository taxRuleRepository;

    @GetMapping
    public List<TaxRuleDto> lister(@RequestParam String pays) {
        return taxRuleRepository.findByPays(pays).stream().map(TaxRuleDto::from).toList();
    }
}
