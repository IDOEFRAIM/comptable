package com.example.comptable.comptabilite.depreciation.service;

import com.example.comptable.comptabilite.depreciation.domain.Depreciation;
import com.example.comptable.comptabilite.depreciation.domain.StatutDepreciation;
import com.example.comptable.comptabilite.depreciation.repository.DepreciationRepository;
import com.example.comptable.comptabilite.depreciation.web.dto.CreerDepreciationRequest;
import com.example.comptable.comptabilite.depreciation.web.dto.DepreciationDto;
import com.example.comptable.comptabilite.domain.CompteComptable;
import com.example.comptable.comptabilite.domain.ExerciceComptable;
import com.example.comptable.comptabilite.operation.common.AccountingOperationEngine;
import com.example.comptable.comptabilite.operation.common.repository.AccountingOperationProposalRepository;
import com.example.comptable.comptabilite.operation.common.web.dto.AccountingOperationProposalDto;
import com.example.comptable.comptabilite.repository.ExerciceComptableRepository;
import com.example.comptable.comptabilite.service.CompteComptableValidationService;
import com.example.comptable.entreprise.domain.Entreprise;
import com.example.comptable.entreprise.service.EntrepriseAccessService;
import com.example.comptable.identite.repository.UtilisateurRepository;
import com.example.comptable.security.UtilisateurPrincipal;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;
import java.util.UUID;

/** Depreciations : creation (BROUILLON), proposition/validation/rejet delegues au moteur commun -
 * meme mecanisme que Regularisation/Provision, jamais une ecriture creee directement ici. */
@Service
@RequiredArgsConstructor
public class DepreciationService {

    private final EntrepriseAccessService entrepriseAccessService;
    private final DepreciationRepository depreciationRepository;
    private final ExerciceComptableRepository exerciceComptableRepository;
    private final CompteComptableValidationService compteComptableValidationService;
    private final UtilisateurRepository utilisateurRepository;
    private final AccountingOperationEngine accountingOperationEngine;
    private final AccountingOperationProposalRepository accountingOperationProposalRepository;

    @Transactional
    public DepreciationDto creer(UUID entrepriseId, CreerDepreciationRequest request, UtilisateurPrincipal principal) {
        Entreprise entreprise = entrepriseAccessService.verifierAcces(entrepriseId, principal);
        ExerciceComptable exercice = chargerExercice(entrepriseId, request.exerciceId());
        CompteComptable compteDotation = compteComptableValidationService
                .chargerCompteUtilisable(entrepriseId, request.compteDotationId(), true);
        CompteComptable compteDepreciation = compteComptableValidationService
                .chargerCompteUtilisable(entrepriseId, request.compteDepreciationId(), true);

        Depreciation depreciation = new Depreciation();
        depreciation.setEntreprise(entreprise);
        depreciation.setExercice(exercice);
        depreciation.setObjetType(request.objetType());
        depreciation.setObjetId(request.objetId());
        depreciation.setValeurComptable(request.valeurComptable());
        depreciation.setValeurRecouvrable(request.valeurRecouvrable());
        depreciation.setJustification(request.justification());
        depreciation.setCompteDotation(compteDotation);
        depreciation.setCompteDepreciation(compteDepreciation);
        depreciation.setCreatedBy(utilisateurRepository.getReferenceById(principal.getId()));

        return DepreciationDto.from(depreciationRepository.save(depreciation));
    }

    @Transactional(readOnly = true)
    public List<DepreciationDto> lister(UUID entrepriseId, UtilisateurPrincipal principal) {
        entrepriseAccessService.verifierAcces(entrepriseId, principal);
        return depreciationRepository.findByEntrepriseIdOrderByCreatedAtDesc(entrepriseId).stream()
                .map(DepreciationDto::from)
                .toList();
    }

    @Transactional(readOnly = true)
    public DepreciationDto obtenir(UUID entrepriseId, UUID depreciationId, UtilisateurPrincipal principal) {
        entrepriseAccessService.verifierAcces(entrepriseId, principal);
        return DepreciationDto.from(chargerEntite(entrepriseId, depreciationId));
    }

    @Transactional
    public DepreciationDto proposer(UUID entrepriseId, UUID depreciationId, UtilisateurPrincipal principal) {
        entrepriseAccessService.verifierAcces(entrepriseId, principal);
        Depreciation depreciation = chargerEntite(entrepriseId, depreciationId);
        if (depreciation.getStatut() != StatutDepreciation.BROUILLON) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "Seule une depreciation en brouillon peut etre proposee (statut actuel " + depreciation.getStatut() + ")");
        }
        AccountingOperationProposalDto propositionDto =
                accountingOperationEngine.proposerDepreciation(entrepriseId, depreciation, principal);
        depreciation.setProposition(accountingOperationProposalRepository.getReferenceById(propositionDto.id()));
        depreciation.setStatut(StatutDepreciation.PROPOSEE);
        return DepreciationDto.from(depreciationRepository.save(depreciation));
    }

    @Transactional
    public DepreciationDto valider(UUID entrepriseId, UUID depreciationId, UtilisateurPrincipal principal) {
        entrepriseAccessService.verifierAcces(entrepriseId, principal);
        Depreciation depreciation = chargerEntite(entrepriseId, depreciationId);
        if (depreciation.getProposition() == null) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Cette depreciation n'a pas encore ete proposee");
        }
        accountingOperationEngine.valider(entrepriseId, depreciation.getProposition().getId(), principal);
        depreciation.setStatut(StatutDepreciation.VALIDEE);
        return DepreciationDto.from(depreciationRepository.save(depreciation));
    }

    @Transactional
    public DepreciationDto rejeter(UUID entrepriseId, UUID depreciationId, String motif, UtilisateurPrincipal principal) {
        entrepriseAccessService.verifierAcces(entrepriseId, principal);
        Depreciation depreciation = chargerEntite(entrepriseId, depreciationId);
        if (depreciation.getProposition() == null) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Cette depreciation n'a pas encore ete proposee");
        }
        accountingOperationEngine.rejeter(entrepriseId, depreciation.getProposition().getId(), motif, principal);
        depreciation.setStatut(StatutDepreciation.REJETEE);
        return DepreciationDto.from(depreciationRepository.save(depreciation));
    }

    private ExerciceComptable chargerExercice(UUID entrepriseId, UUID exerciceId) {
        return exerciceComptableRepository.findById(exerciceId)
                .filter(e -> e.getEntreprise().getId().equals(entrepriseId))
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Exercice comptable introuvable"));
    }

    private Depreciation chargerEntite(UUID entrepriseId, UUID depreciationId) {
        return depreciationRepository.findById(depreciationId)
                .filter(d -> d.getEntreprise().getId().equals(entrepriseId))
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Depreciation introuvable"));
    }
}
