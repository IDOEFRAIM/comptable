package com.example.comptable.agent.pipeline;

import com.example.comptable.document.domain.Document;
import com.example.comptable.document.domain.StatutDocument;
import com.example.comptable.document.repository.DocumentRepository;
import org.bsc.langgraph4j.CompiledGraph;
import org.bsc.langgraph4j.GraphInput;
import org.bsc.langgraph4j.NodeOutput;
import org.bsc.langgraph4j.RunnableConfig;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.Optional;
import java.util.UUID;

/**
 * Point d'entree pour demarrer ou reprendre l'execution du graphe {@link PieceJustificativeState}.
 * Le {@code threadId} LangGraph4j est le {@code documentId} lui-meme : un document = une execution
 * de graphe = un fil de checkpoints, ce qui rend {@link #reprendreApresValidation} trivial (pas de
 * table de correspondance a maintenir entre document et thread).
 * <p>
 * Repercute aussi l'issue de chaque execution sur {@link Document#getStatut()} : c'est ce qui rend
 * la piece visible (ou non) dans l'inbox de validation (Sprint 4) - {@link NodeOutput#isEND()}
 * distingue une execution qui s'est terminee normalement (l'ecriture, ou l'echec gere par
 * {@link EcritureNode}) d'une execution interrompue avant {@code attente_validation}.
 */
@Service
public class PieceJustificativeAgentService {

    private static final Logger log = LoggerFactory.getLogger(PieceJustificativeAgentService.class);

    private final CompiledGraph<PieceJustificativeState> graph;
    private final DocumentRepository documentRepository;

    public PieceJustificativeAgentService(CompiledGraph<PieceJustificativeState> graph,
                                           DocumentRepository documentRepository) {
        this.graph = graph;
        this.documentRepository = documentRepository;
    }

    /** Demarre le pipeline pour un document deja ingere (Document existant, deja extrait).
     * Appele automatiquement par {@code WhatsAppIngestionService} apres ingestion. */
    public Optional<PieceJustificativeState> demarrer(UUID documentId, UUID entrepriseId) {
        return executer(documentId, GraphInput.args(PieceJustificativeState.initial(documentId, entrepriseId)));
    }

    /** Reprend un pipeline en pause a {@code attente_validation}, apres qu'un comptable a validé
     * la piece depuis l'inbox de validation (Sprint 4). */
    public Optional<PieceJustificativeState> reprendreApresValidation(UUID documentId) {
        return executer(documentId, GraphInput.resume());
    }

    private Optional<PieceJustificativeState> executer(UUID documentId, GraphInput input) {
        RunnableConfig config = RunnableConfig.builder().threadId(documentId.toString()).build();
        try {
            Optional<NodeOutput<PieceJustificativeState>> sortie = graph.invokeFinal(input, config);
            if (sortie.isEmpty()) {
                log.warn("Graphe pour le document {} n'a produit aucune sortie", documentId);
                return Optional.empty();
            }
            NodeOutput<PieceJustificativeState> resultat = sortie.get();
            appliquerStatutDocument(documentId, enPause(resultat));
            log.info("Pipeline pour le document {} : noeud='{}', en_pause={}, conformiteOk={}, valideParHumain={}",
                    documentId, resultat.node(), enPause(resultat),
                    resultat.state().conformiteOk(), resultat.state().valideParHumain());
            return Optional.of(resultat.state());
        } catch (Exception e) {
            log.error("Echec d'execution du pipeline pour le document {} : {}", documentId, e.getMessage(), e);
            return Optional.empty();
        }
    }

    /** Le graphe s'arrete soit a END (termine), soit juste avant attente_validation (pause) :
     * {@code isEND()==false} couvre exactement ce second cas ici, vu qu'aucune autre pause n'est
     * configuree dans {@link PieceJustificativeGraphConfig}. */
    private boolean enPause(NodeOutput<PieceJustificativeState> resultat) {
        return !resultat.isEND();
    }

    /** Pas de {@code @Transactional} : cette methode est appelee en interne (self-invocation),
     * ce qui rendrait l'annotation inoperante via le proxy Spring ; {@code save()} d'un repository
     * Spring Data est deja transactionnel par lui-meme. */
    private void appliquerStatutDocument(UUID documentId, boolean enPause) {
        documentRepository.findById(documentId).ifPresentOrElse(document -> {
            document.setStatut(enPause ? StatutDocument.EN_ATTENTE_VALIDATION_HUMAINE : StatutDocument.TRAITE);
            documentRepository.save(document);
        }, () -> log.warn("Document {} introuvable, statut non mis a jour apres execution du pipeline", documentId));
    }
}
