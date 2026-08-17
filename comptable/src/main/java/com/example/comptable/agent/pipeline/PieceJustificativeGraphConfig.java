package com.example.comptable.agent.pipeline;

import com.example.comptable.agent.fraude.RapportFraude;
import org.bsc.langgraph4j.CompiledGraph;
import org.bsc.langgraph4j.CompileConfig;
import org.bsc.langgraph4j.GraphStateException;
import org.bsc.langgraph4j.StateGraph;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.Map;

import static org.bsc.langgraph4j.StateGraph.END;
import static org.bsc.langgraph4j.StateGraph.START;
import static org.bsc.langgraph4j.action.AsyncEdgeAction.edge_async;
import static org.bsc.langgraph4j.action.AsyncNodeAction.node_async;

/**
 * Assemble le graphe d'etat du traitement d'une piece justificative :
 * <pre>
 *   START -&gt; extraction -&gt; conformite -&gt; analyse_fraude -+-(auto)----------&gt; ecriture -&gt; END
 *                                                          +-(manuel)-&gt; attente_validation -&gt; ecriture -&gt; END
 * </pre>
 * "auto" n'est emprunte que si la confiance VLM est >= 95% ET que la conformite est OK ET que le
 * controle de fraude (Sprint 7, {@link AnalyseFraudeNode}) n'a pas releve de score CRITIQUE -
 * cette derniere condition est prioritaire sur les deux autres : une piece a risque CRITIQUE
 * passe TOUJOURS par la revue humaine, quelle que soit la confiance d'extraction. Dans tous les
 * autres cas la route "manuel" passe par le point de pause {@code attente_validation}
 * ({@link CompileConfig#interruptBefore}), qui persiste l'etat via {@link JdbcCheckpointSaver} et
 * suspend l'execution jusqu'a reprise explicite (potentiellement plusieurs jours plus tard).
 */
@Configuration
public class PieceJustificativeGraphConfig {

    /** Seuil au-dela duquel la piece est postee sans revue humaine prealable - identique a celui
     * annonce dans le prompt VLM (Sprint 1) : la confiance du modele et le seuil d'auto-validation
     * doivent rester coherents entre l'extraction et le pipeline. */
    private static final double SEUIL_CONFIANCE_AUTO = 0.95;

    static final String NOEUD_EXTRACTION = "extraction";
    static final String NOEUD_CONFORMITE = "conformite";
    static final String NOEUD_ANALYSE_FRAUDE = "analyse_fraude";
    static final String NOEUD_ATTENTE_VALIDATION = "attente_validation";
    static final String NOEUD_ECRITURE = "ecriture";

    @Bean
    public CompiledGraph<PieceJustificativeState> pieceJustificativeGraph(
            ExtractionNode extractionNode,
            ConformiteNode conformiteNode,
            AnalyseFraudeNode analyseFraudeNode,
            ValidationHumaineNode validationHumaineNode,
            EcritureNode ecritureNode,
            JdbcCheckpointSaver checkpointSaver) throws GraphStateException {

        StateGraph<PieceJustificativeState> graph = new StateGraph<>(PieceJustificativeState::new)
                .addNode(NOEUD_EXTRACTION, node_async(extractionNode))
                .addNode(NOEUD_CONFORMITE, node_async(conformiteNode))
                .addNode(NOEUD_ANALYSE_FRAUDE, node_async(analyseFraudeNode))
                .addNode(NOEUD_ATTENTE_VALIDATION, node_async(validationHumaineNode))
                .addNode(NOEUD_ECRITURE, node_async(ecritureNode))

                .addEdge(START, NOEUD_EXTRACTION)
                .addEdge(NOEUD_EXTRACTION, NOEUD_CONFORMITE)
                .addEdge(NOEUD_CONFORMITE, NOEUD_ANALYSE_FRAUDE)

                .addConditionalEdges(NOEUD_ANALYSE_FRAUDE, edge_async(this::routerApresAnalyseFraude),
                        Map.of("auto", NOEUD_ECRITURE, "manuel", NOEUD_ATTENTE_VALIDATION))

                .addEdge(NOEUD_ATTENTE_VALIDATION, NOEUD_ECRITURE)
                .addEdge(NOEUD_ECRITURE, END);

        CompileConfig config = CompileConfig.builder()
                .checkpointSaver(checkpointSaver)
                .interruptBefore(NOEUD_ATTENTE_VALIDATION)
                .build();

        return graph.compile(config);
    }

    private String routerApresAnalyseFraude(PieceJustificativeState state) {
        RapportFraude rapportFraude = state.rapportFraude();
        if (rapportFraude != null && rapportFraude.estCritique()) {
            return "manuel";
        }
        ExtractionPieceResult extraction = state.extraction();
        boolean confianceHaute = extraction != null && extraction.confiance() >= SEUIL_CONFIANCE_AUTO;
        return (confianceHaute && state.conformiteOk()) ? "auto" : "manuel";
    }
}
