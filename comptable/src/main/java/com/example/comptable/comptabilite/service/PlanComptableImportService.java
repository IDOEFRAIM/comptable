package com.example.comptable.comptabilite.service;

import com.example.comptable.comptabilite.domain.CompteComptable;
import com.example.comptable.comptabilite.domain.ModeImportReference;
import com.example.comptable.comptabilite.domain.PlanComptableReference;
import com.example.comptable.comptabilite.repository.CompteComptableRepository;
import com.example.comptable.comptabilite.repository.PlanComptableReferenceRepository;
import com.example.comptable.comptabilite.web.dto.CompteIgnoreDto;
import com.example.comptable.comptabilite.web.dto.ImporterReferenceRequest;
import com.example.comptable.comptabilite.web.dto.ImporterReferenceResultDto;
import com.example.comptable.entreprise.domain.Entreprise;
import com.example.comptable.entreprise.service.EntrepriseAccessService;
import com.example.comptable.security.UtilisateurPrincipal;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * Initialise/etend le PCE d'une entreprise depuis le referentiel SYSCOHADA global. Idempotent :
 * la cle d'idempotence est {@code reference_id} (pas {@code numero}, qui pourrait entrer en
 * collision avec un compte local sans lien vers le referentiel) - un compte deja importe n'est
 * jamais duplique ni ecrase, preservant toute personnalisation locale par construction (l'import
 * ne fait qu'inserer, jamais de UPDATE sur un compte existant).
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class PlanComptableImportService {

    private final EntrepriseAccessService entrepriseAccessService;
    private final PlanComptableReferenceRepository planComptableReferenceRepository;
    private final CompteComptableRepository compteComptableRepository;

    @Transactional
    public ImporterReferenceResultDto importer(UUID entrepriseId, ImporterReferenceRequest request,
                                                UtilisateurPrincipal principal) {
        Entreprise entreprise = entrepriseAccessService.verifierAcces(entrepriseId, principal);
        List<PlanComptableReference> cibles = resoudreCibles(request);
        ImporterReferenceResultDto resultat = importerReferences(entreprise, cibles);
        log.info("plan_comptable_import entreprise={} mode={} ajoutes={} ignores={}",
                entrepriseId, request.mode(), resultat.comptesAjoutes().size(), resultat.comptesIgnores().size());
        return resultat;
    }

    /**
     * Utilise a la creation d'une entreprise ({@code EntrepriseService.creer}) : importe tout le
     * referentiel actif. Pas de verification d'acces ici - l'entreprise vient d'etre creee dans
     * la meme transaction, hors contexte d'une requete HTTP scopee par entrepriseId.
     */
    @Transactional
    public void importerTout(Entreprise entreprise) {
        importerReferences(entreprise, planComptableReferenceRepository.findByActifTrue());
    }

    private List<PlanComptableReference> resoudreCibles(ImporterReferenceRequest request) {
        if (request.mode() == ModeImportReference.TOUT) {
            return planComptableReferenceRepository.findByActifTrue();
        }
        List<String> codes = request.codes() == null ? List.of() : request.codes();
        Set<PlanComptableReference> cibles = new LinkedHashSet<>();
        for (String code : codes) {
            planComptableReferenceRepository.findByNumero(code).ifPresent(reference -> ajouterAvecAncetres(reference, cibles));
        }
        return List.copyOf(cibles);
    }

    /** Inclut automatiquement les ancetres non encore selectionnes : une entreprise ne doit
     * jamais se retrouver avec un compte enfant sans son parent. */
    private void ajouterAvecAncetres(PlanComptableReference reference, Set<PlanComptableReference> cibles) {
        if (reference.getParent() != null && !cibles.contains(reference.getParent())) {
            ajouterAvecAncetres(reference.getParent(), cibles);
        }
        cibles.add(reference);
    }

    private ImporterReferenceResultDto importerReferences(Entreprise entreprise, List<PlanComptableReference> cibles) {
        // Trie par niveau croissant : garantit que le compte local du parent existe deja au
        // moment de creer l'enfant, sans avoir a faire plusieurs passes.
        List<PlanComptableReference> triees = cibles.stream()
                .sorted(Comparator.comparing(PlanComptableReference::getNiveau))
                .toList();

        List<String> ajoutes = new ArrayList<>();
        List<CompteIgnoreDto> ignores = new ArrayList<>();
        Map<Integer, CompteComptable> creesDansCetImport = new HashMap<>();

        for (PlanComptableReference reference : triees) {
            Optional<CompteComptable> dejaImporte = compteComptableRepository
                    .findByEntrepriseIdAndReferenceId(entreprise.getId(), reference.getId());
            if (dejaImporte.isPresent()) {
                creesDansCetImport.put(reference.getId(), dejaImporte.get());
                continue;
            }

            Optional<CompteComptable> collisionLocale = compteComptableRepository
                    .findByEntrepriseIdAndNumero(entreprise.getId(), reference.getNumero());
            if (collisionLocale.isPresent()) {
                ignores.add(new CompteIgnoreDto(reference.getNumero(),
                        "Un compte local existe deja avec ce numero, sans lien vers le referentiel"));
                continue;
            }

            CompteComptable compte = new CompteComptable();
            compte.setEntreprise(entreprise);
            compte.setReference(reference);
            compte.setNumero(reference.getNumero());
            compte.setIntitule(reference.getIntitule());
            compte.setLettrable(reference.isLettrable());
            compte.setNiveau(reference.getNiveau());
            compte.setActif(true);
            if (reference.getParent() != null) {
                CompteComptable parentLocal = creesDansCetImport.get(reference.getParent().getId());
                if (parentLocal == null) {
                    parentLocal = compteComptableRepository
                            .findByEntrepriseIdAndReferenceId(entreprise.getId(), reference.getParent().getId())
                            .orElse(null);
                }
                compte.setParent(parentLocal);
            }

            CompteComptable enregistre = compteComptableRepository.save(compte);
            creesDansCetImport.put(reference.getId(), enregistre);
            ajoutes.add(reference.getNumero());
        }

        return new ImporterReferenceResultDto(ajoutes, ignores);
    }
}
