package com.example.comptable.agent.pipeline;

import com.example.comptable.agent.comptabilisation.AccountingProposalBuilder;
import com.example.comptable.agent.comptabilisation.domain.AccountingProposal;
import com.example.comptable.comptabilite.service.ComptabilisationAutomatiqueIndisponibleException;
import com.example.comptable.comptabilite.service.ComptabilisationService;
import com.example.comptable.document.domain.Document;
import com.example.comptable.document.repository.DocumentRepository;
import com.example.comptable.entreprise.domain.Entreprise;
import com.example.comptable.entreprise.repository.EntrepriseRepository;
import org.bsc.langgraph4j.action.NodeAction;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.Optional;

/**
 * Dernier noeud du graphe : tente de comptabiliser definitivement la piece via
 * {@link ComptabilisationService}. Construit d'abord une {@link AccountingProposal} (mission P0,
 * section 14) via {@link AccountingProposalBuilder} ; seul le "Niveau 3" (tiers ET compte ET
 * journal resolus sans ambiguite par un historique coherent, cf.
 * {@link AccountingProposalBuilder#eligibleNiveau3}) declenche une comptabilisation reelle et
 * automatique. Dans tous les autres cas (Niveau 0/1/2), la proposition reste visible/validable a
 * la main (ecran Documents ou future Inbox), et le document reste simplement au statut TRAITE -
 * ce n'est jamais traite comme une erreur de pipeline.
 */
@Component
public class EcritureNode implements NodeAction<PieceJustificativeState> {

    private static final Logger log = LoggerFactory.getLogger(EcritureNode.class);

    private final ComptabilisationService comptabilisationService;
    private final AccountingProposalBuilder accountingProposalBuilder;
    private final DocumentRepository documentRepository;
    private final EntrepriseRepository entrepriseRepository;

    public EcritureNode(ComptabilisationService comptabilisationService,
                         AccountingProposalBuilder accountingProposalBuilder,
                         DocumentRepository documentRepository,
                         EntrepriseRepository entrepriseRepository) {
        this.comptabilisationService = comptabilisationService;
        this.accountingProposalBuilder = accountingProposalBuilder;
        this.documentRepository = documentRepository;
        this.entrepriseRepository = entrepriseRepository;
    }

    @Override
    public Map<String, Object> apply(PieceJustificativeState state) {
        Optional<Document> document = documentRepository.findById(state.documentId());
        Optional<Entreprise> entreprise = entrepriseRepository.findById(state.entrepriseId());
        if (document.isEmpty() || entreprise.isEmpty()) {
            log.warn("Document {} ou entreprise {} introuvable, comptabilisation automatique ignoree",
                    state.documentId(), state.entrepriseId());
            return Map.of();
        }

        Optional<AccountingProposal> proposition = accountingProposalBuilder.proposer(entreprise.get(), document.get());
        if (proposition.isEmpty() || !accountingProposalBuilder.eligibleNiveau3(proposition.get())) {
            log.info("Document {} : proposition de comptabilisation non eligible au Niveau 3, "
                            + "comptabilisation manuelle requise", state.documentId());
            return Map.of();
        }

        try {
            comptabilisationService.comptabiliserAutomatiquement(state.entrepriseId(), state.documentId(),
                    proposition.get().getTiers().getId(), proposition.get().getCompteChargeProduit().getId());
            accountingProposalBuilder.marquerAppliquee(proposition.get());
            log.info("Document {} comptabilise automatiquement (Niveau 3, entreprise {})",
                    state.documentId(), state.entrepriseId());
        } catch (ComptabilisationAutomatiqueIndisponibleException e) {
            log.info("Document {} non comptabilise automatiquement malgre une proposition Niveau 3, "
                            + "comptabilisation manuelle requise : {}",
                    state.documentId(), e.getMessage());
        }
        return Map.of();
    }
}
