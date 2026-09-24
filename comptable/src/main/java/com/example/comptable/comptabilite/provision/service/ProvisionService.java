package com.example.comptable.comptabilite.provision.service;

import com.example.comptable.comptabilite.domain.CompteComptable;
import com.example.comptable.comptabilite.domain.ExerciceComptable;
import com.example.comptable.comptabilite.operation.common.AccountingOperationEngine;
import com.example.comptable.comptabilite.operation.common.repository.AccountingOperationProposalRepository;
import com.example.comptable.comptabilite.operation.common.web.dto.AccountingOperationProposalDto;
import com.example.comptable.comptabilite.provision.domain.Provision;
import com.example.comptable.comptabilite.provision.domain.StatutProvision;
import com.example.comptable.comptabilite.provision.repository.ProvisionRepository;
import com.example.comptable.comptabilite.provision.web.dto.CreerProvisionRequest;
import com.example.comptable.comptabilite.provision.web.dto.ProvisionDto;
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

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

/** Provisions : creation (BROUILLON), proposition/validation/rejet delegues au moteur commun -
 * meme mecanisme que Regularisation/Inventaire, jamais une ecriture creee directement ici. */
@Service
@RequiredArgsConstructor
public class ProvisionService {

    private final EntrepriseAccessService entrepriseAccessService;
    private final ProvisionRepository provisionRepository;
    private final ExerciceComptableRepository exerciceComptableRepository;
    private final CompteComptableValidationService compteComptableValidationService;
    private final UtilisateurRepository utilisateurRepository;
    private final AccountingOperationEngine accountingOperationEngine;
    private final AccountingOperationProposalRepository accountingOperationProposalRepository;

    @Transactional
    public ProvisionDto creer(UUID entrepriseId, CreerProvisionRequest request, UtilisateurPrincipal principal) {
        Entreprise entreprise = entrepriseAccessService.verifierAcces(entrepriseId, principal);
        ExerciceComptable exercice = chargerExercice(entrepriseId, request.exerciceId());
        CompteComptable compteDotation = compteComptableValidationService
                .chargerCompteUtilisable(entrepriseId, request.compteDotationId(), true);
        CompteComptable compteProvision = compteComptableValidationService
                .chargerCompteUtilisable(entrepriseId, request.compteProvisionId(), true);
        CompteComptable compteReprise = compteComptableValidationService
                .chargerCompteUtilisable(entrepriseId, request.compteRepriseId(), true);

        Provision provision = new Provision();
        provision.setEntreprise(entreprise);
        provision.setExercice(exercice);
        provision.setNature(request.nature());
        provision.setObjet(request.objet());
        provision.setMontantPrecedent(request.montantPrecedent() != null ? request.montantPrecedent() : BigDecimal.ZERO);
        provision.setMontantNouveau(request.montantNouveau());
        provision.setJustification(request.justification());
        provision.setTypeMouvement(request.typeMouvement());
        provision.setCompteDotation(compteDotation);
        provision.setCompteProvision(compteProvision);
        provision.setCompteReprise(compteReprise);
        provision.setCreatedBy(utilisateurRepository.getReferenceById(principal.getId()));

        return ProvisionDto.from(provisionRepository.save(provision));
    }

    @Transactional(readOnly = true)
    public List<ProvisionDto> lister(UUID entrepriseId, UtilisateurPrincipal principal) {
        entrepriseAccessService.verifierAcces(entrepriseId, principal);
        return provisionRepository.findByEntrepriseIdOrderByCreatedAtDesc(entrepriseId).stream()
                .map(ProvisionDto::from)
                .toList();
    }

    @Transactional(readOnly = true)
    public ProvisionDto obtenir(UUID entrepriseId, UUID provisionId, UtilisateurPrincipal principal) {
        entrepriseAccessService.verifierAcces(entrepriseId, principal);
        return ProvisionDto.from(chargerEntite(entrepriseId, provisionId));
    }

    @Transactional
    public ProvisionDto proposer(UUID entrepriseId, UUID provisionId, UtilisateurPrincipal principal) {
        entrepriseAccessService.verifierAcces(entrepriseId, principal);
        Provision provision = chargerEntite(entrepriseId, provisionId);
        if (provision.getStatut() != StatutProvision.BROUILLON) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "Seule une provision en brouillon peut etre proposee (statut actuel " + provision.getStatut() + ")");
        }
        AccountingOperationProposalDto propositionDto =
                accountingOperationEngine.proposerProvision(entrepriseId, provision, principal);
        provision.setProposition(accountingOperationProposalRepository.getReferenceById(propositionDto.id()));
        provision.setStatut(StatutProvision.PROPOSEE);
        return ProvisionDto.from(provisionRepository.save(provision));
    }

    @Transactional
    public ProvisionDto valider(UUID entrepriseId, UUID provisionId, UtilisateurPrincipal principal) {
        entrepriseAccessService.verifierAcces(entrepriseId, principal);
        Provision provision = chargerEntite(entrepriseId, provisionId);
        if (provision.getProposition() == null) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Cette provision n'a pas encore ete proposee");
        }
        accountingOperationEngine.valider(entrepriseId, provision.getProposition().getId(), principal);
        provision.setStatut(StatutProvision.VALIDEE);
        return ProvisionDto.from(provisionRepository.save(provision));
    }

    @Transactional
    public ProvisionDto rejeter(UUID entrepriseId, UUID provisionId, String motif, UtilisateurPrincipal principal) {
        entrepriseAccessService.verifierAcces(entrepriseId, principal);
        Provision provision = chargerEntite(entrepriseId, provisionId);
        if (provision.getProposition() == null) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Cette provision n'a pas encore ete proposee");
        }
        accountingOperationEngine.rejeter(entrepriseId, provision.getProposition().getId(), motif, principal);
        provision.setStatut(StatutProvision.REJETEE);
        return ProvisionDto.from(provisionRepository.save(provision));
    }

    private ExerciceComptable chargerExercice(UUID entrepriseId, UUID exerciceId) {
        return exerciceComptableRepository.findById(exerciceId)
                .filter(e -> e.getEntreprise().getId().equals(entrepriseId))
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Exercice comptable introuvable"));
    }

    private Provision chargerEntite(UUID entrepriseId, UUID provisionId) {
        return provisionRepository.findById(provisionId)
                .filter(p -> p.getEntreprise().getId().equals(entrepriseId))
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Provision introuvable"));
    }
}
