package com.example.comptable.agent.imputation;

import com.example.comptable.agent.imputation.domain.ImputationSuggestion;
import com.example.comptable.agent.imputation.domain.StatutImputationSuggestion;
import com.example.comptable.agent.imputation.repository.ImputationSuggestionRepository;
import com.example.comptable.agent.imputation.web.dto.ImputationSuggestionDto;
import com.example.comptable.entreprise.domain.Entreprise;
import com.example.comptable.entreprise.service.EntrepriseAccessService;
import com.example.comptable.identite.repository.UtilisateurRepository;
import com.example.comptable.security.UtilisateurPrincipal;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.time.OffsetDateTime;
import java.util.Optional;
import java.util.UUID;

/**
 * Validation humaine des {@link ImputationSuggestion} (mission P0, section 14/15) - meme principe
 * que {@code agent.matching.AiMatchProposalService}. "Accepter" ne comptabilise JAMAIS
 * directement : cela reste une pre-selection du tiers/compte proposes, le comptable confirme
 * ensuite via l'ecran Documents habituel ({@code ComptabilisationService.comptabiliser}, qui
 * redemande ces informations independamment). Seul le Niveau 3 (verifie uniquement par
 * {@code agent.pipeline.EcritureNode}) applique reellement une ecriture sans ce geste humain.
 */
@Service
@RequiredArgsConstructor
public class ImputationSuggestionService {

    private final ImputationSuggestionRepository imputationSuggestionRepository;
    private final EntrepriseAccessService entrepriseAccessService;
    private final UtilisateurRepository utilisateurRepository;

    @Transactional(readOnly = true)
    public Optional<ImputationSuggestionDto> obtenirPourDocument(UUID entrepriseId, UUID documentId,
                                                                 UtilisateurPrincipal principal) {
        entrepriseAccessService.verifierAcces(entrepriseId, principal);
        return imputationSuggestionRepository.findByDocumentIdAndStatus(documentId, StatutImputationSuggestion.PROPOSED)
                .map(ImputationSuggestionDto::from);
    }

    @Transactional
    public ImputationSuggestionDto accepter(UUID entrepriseId, UUID suggestionId, UtilisateurPrincipal principal) {
        Entreprise entreprise = entrepriseAccessService.verifierAcces(entrepriseId, principal);
        ImputationSuggestion proposition = charger(entreprise, suggestionId);
        proposition.setStatus(StatutImputationSuggestion.ACCEPTED);
        proposition.setValidatedAt(OffsetDateTime.now());
        proposition.setValidatedBy(utilisateurRepository.getReferenceById(principal.getId()));
        return ImputationSuggestionDto.from(imputationSuggestionRepository.save(proposition));
    }

    @Transactional
    public ImputationSuggestionDto rejeter(UUID entrepriseId, UUID suggestionId, String motif, UtilisateurPrincipal principal) {
        Entreprise entreprise = entrepriseAccessService.verifierAcces(entrepriseId, principal);
        ImputationSuggestion proposition = charger(entreprise, suggestionId);
        proposition.setStatus(StatutImputationSuggestion.REJECTED);
        proposition.setValidatedAt(OffsetDateTime.now());
        proposition.setValidatedBy(utilisateurRepository.getReferenceById(principal.getId()));
        proposition.setRejectionReason(motif);
        return ImputationSuggestionDto.from(imputationSuggestionRepository.save(proposition));
    }

    private ImputationSuggestion charger(Entreprise entreprise, UUID suggestionId) {
        ImputationSuggestion proposition = imputationSuggestionRepository.findById(suggestionId)
                .filter(p -> p.getEntreprise().getId().equals(entreprise.getId()))
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Proposition introuvable"));
        if (proposition.getStatus() != StatutImputationSuggestion.PROPOSED) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Cette suggestion a deja ete traitee");
        }
        return proposition;
    }
}
