package com.example.comptable.comptabilite.web;

import com.example.comptable.comptabilite.domain.PlanComptableReference;
import com.example.comptable.comptabilite.repository.PlanComptableReferenceRepository;
import com.example.comptable.comptabilite.web.dto.PlanComptableReferenceDto;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;

/** Referentiel SYSCOHADA global : lecture seule, sans scope tenant (memes comptes partages par
 * tous les cabinets) - meme pattern que {@code TaxRuleController} (entite globale, acces direct
 * au repository, pas de couche service puisqu'il n'y a aucun scope tenant a faire respecter). */
@RestController
@RequestMapping("/api/plan-comptable-reference")
@RequiredArgsConstructor
public class PlanComptableReferenceController {

    private final PlanComptableReferenceRepository planComptableReferenceRepository;

    @GetMapping
    public List<PlanComptableReferenceDto> lister(@RequestParam(required = false) Short classe,
                                                    @RequestParam(required = false) Boolean actif) {
        List<PlanComptableReference> references = classe != null
                ? planComptableReferenceRepository.findByClasse(classe)
                : planComptableReferenceRepository.findAll();
        return references.stream()
                .filter(r -> actif == null || r.isActif() == actif)
                .map(PlanComptableReferenceDto::from)
                .toList();
    }

    @GetMapping("/{code}")
    public PlanComptableReferenceDto obtenir(@PathVariable String code) {
        return planComptableReferenceRepository.findByNumero(code)
                .map(PlanComptableReferenceDto::from)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Compte de reference introuvable"));
    }
}
