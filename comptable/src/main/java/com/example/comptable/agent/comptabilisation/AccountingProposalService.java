package com.example.comptable.agent.comptabilisation;

import com.example.comptable.agent.comptabilisation.domain.AccountingProposal;
import com.example.comptable.agent.comptabilisation.domain.StatutAccountingProposal;
import com.example.comptable.agent.comptabilisation.repository.AccountingProposalRepository;
import com.example.comptable.agent.comptabilisation.web.dto.AccountingProposalDto;
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
 * Validation humaine des {@link AccountingProposal} (mission P0, section 14/15) - meme principe
 * que {@code agent.matching.AiMatchProposalService}. "Accepter" ne comptabilise JAMAIS
 * directement : cela reste une pre-selection du tiers/compte proposes, le comptable confirme
 * ensuite via l'ecran Documents habituel ({@code ComptabilisationService.comptabiliser}, qui
 * redemande ces informations independamment). Seul le Niveau 3 (verifie uniquement par
 * {@code agent.pipeline.EcritureNode}) applique reellement une ecriture sans ce geste humain.
 */
@Service
@RequiredArgsConstructor
public class AccountingProposalService {

    private final AccountingProposalRepository accountingProposalRepository;
    private final EntrepriseAccessService entrepriseAccessService;
    private final UtilisateurRepository utilisateurRepository;

    @Transactional(readOnly = true)
    public Optional<AccountingProposalDto> obtenirPourDocument(UUID entrepriseId, UUID documentId,
                                                                 UtilisateurPrincipal principal) {
        entrepriseAccessService.verifierAcces(entrepriseId, principal);
        return accountingProposalRepository.findByDocumentIdAndStatus(documentId, StatutAccountingProposal.PROPOSED)
                .map(AccountingProposalDto::from);
    }

    @Transactional
    public AccountingProposalDto accepter(UUID entrepriseId, UUID propositionId, UtilisateurPrincipal principal) {
        Entreprise entreprise = entrepriseAccessService.verifierAcces(entrepriseId, principal);
        AccountingProposal proposition = charger(entreprise, propositionId);
        proposition.setStatus(StatutAccountingProposal.ACCEPTED);
        proposition.setValidatedAt(OffsetDateTime.now());
        proposition.setValidatedBy(utilisateurRepository.getReferenceById(principal.getId()));
        return AccountingProposalDto.from(accountingProposalRepository.save(proposition));
    }

    @Transactional
    public AccountingProposalDto rejeter(UUID entrepriseId, UUID propositionId, String motif, UtilisateurPrincipal principal) {
        Entreprise entreprise = entrepriseAccessService.verifierAcces(entrepriseId, principal);
        AccountingProposal proposition = charger(entreprise, propositionId);
        proposition.setStatus(StatutAccountingProposal.REJECTED);
        proposition.setValidatedAt(OffsetDateTime.now());
        proposition.setValidatedBy(utilisateurRepository.getReferenceById(principal.getId()));
        proposition.setRejectionReason(motif);
        return AccountingProposalDto.from(accountingProposalRepository.save(proposition));
    }

    private AccountingProposal charger(Entreprise entreprise, UUID propositionId) {
        AccountingProposal proposition = accountingProposalRepository.findById(propositionId)
                .filter(p -> p.getEntreprise().getId().equals(entreprise.getId()))
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Proposition introuvable"));
        if (proposition.getStatus() != StatutAccountingProposal.PROPOSED) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Cette proposition a deja ete traitee");
        }
        return proposition;
    }
}
