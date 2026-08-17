package com.example.comptable.agent.matching;

import com.example.comptable.agent.matching.domain.AiMatchProposal;
import com.example.comptable.agent.matching.domain.SourceType;
import com.example.comptable.agent.matching.domain.StatutPropositionIA;
import com.example.comptable.agent.matching.repository.AiMatchProposalRepository;
import com.example.comptable.agent.matching.web.dto.AiMatchProposalDto;
import com.example.comptable.comptabilite.domain.LigneReleve;
import com.example.comptable.comptabilite.repository.LigneReleveRepository;
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
 * "Java Control" cote validation humaine (section 11 de la mission) : une proposition IA
 * (PROPOSED) ne devient un pointage effectif qu'apres acceptation explicite ici - jamais
 * directement depuis ai-service ni depuis {@link MatchDecisionEngine} pour un candidat
 * FACTURE/DOCUMENT (seul ACCOUNTING_ENTRY est auto-accepte, cf. sa javadoc). Accepter une
 * proposition FACTURE/DOCUMENT ici n'a PAS d'effet comptable automatique (ne marque pas une
 * facture payee, ne cree pas d'ecriture) : c'est une limite assumee de cette mission, cf. le
 * rapport de fin de mission.
 */
@Service
@RequiredArgsConstructor
public class AiMatchProposalService {

    private final AiMatchProposalRepository aiMatchProposalRepository;
    private final LigneReleveRepository ligneReleveRepository;
    private final EntrepriseAccessService entrepriseAccessService;
    private final UtilisateurRepository utilisateurRepository;

    @Transactional(readOnly = true)
    public Optional<AiMatchProposalDto> obtenirPourLigne(UUID entrepriseId, UUID ligneReleveId,
                                                           UtilisateurPrincipal principal) {
        entrepriseAccessService.verifierAcces(entrepriseId, principal);
        return aiMatchProposalRepository
                .findBySourceTypeAndSourceIdAndStatus(SourceType.BANK_TRANSACTION, ligneReleveId.toString(),
                        StatutPropositionIA.PROPOSED)
                .map(AiMatchProposalDto::from);
    }

    @Transactional
    public AiMatchProposalDto accepter(UUID entrepriseId, UUID propositionId, UtilisateurPrincipal principal) {
        Entreprise entreprise = entrepriseAccessService.verifierAcces(entrepriseId, principal);
        AiMatchProposal proposition = charger(entreprise, propositionId);

        // Seul un candidat ACCOUNTING_ENTRY a une consequence de pointage automatique -
        // meme restriction que MatchDecisionEngine pour MATCH_AUTO (sections 14/15/20).
        if (proposition.getCandidateType() == SourceType.ACCOUNTING_ENTRY && proposition.getCandidateId() != null) {
            UUID ligneReleveId = UUID.fromString(proposition.getSourceId());
            LigneReleve ligne = ligneReleveRepository.findById(ligneReleveId)
                    .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Ligne de releve introuvable"));
            ligne.setLigneEcritureId(UUID.fromString(proposition.getCandidateId()));
            ligneReleveRepository.save(ligne);
        }

        proposition.setStatus(StatutPropositionIA.ACCEPTED);
        proposition.setValidatedAt(OffsetDateTime.now());
        proposition.setValidatedBy(utilisateurRepository.getReferenceById(principal.getId()));
        return AiMatchProposalDto.from(aiMatchProposalRepository.save(proposition));
    }

    @Transactional
    public AiMatchProposalDto rejeter(UUID entrepriseId, UUID propositionId, String motif, UtilisateurPrincipal principal) {
        Entreprise entreprise = entrepriseAccessService.verifierAcces(entrepriseId, principal);
        AiMatchProposal proposition = charger(entreprise, propositionId);

        proposition.setStatus(StatutPropositionIA.REJECTED);
        proposition.setValidatedAt(OffsetDateTime.now());
        proposition.setValidatedBy(utilisateurRepository.getReferenceById(principal.getId()));
        proposition.setRejectionReason(motif);
        return AiMatchProposalDto.from(aiMatchProposalRepository.save(proposition));
    }

    private AiMatchProposal charger(Entreprise entreprise, UUID propositionId) {
        AiMatchProposal proposition = aiMatchProposalRepository.findById(propositionId)
                .filter(p -> p.getEntreprise().getId().equals(entreprise.getId()))
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Proposition introuvable"));
        if (proposition.getStatus() != StatutPropositionIA.PROPOSED) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Cette proposition a deja ete traitee");
        }
        return proposition;
    }
}
