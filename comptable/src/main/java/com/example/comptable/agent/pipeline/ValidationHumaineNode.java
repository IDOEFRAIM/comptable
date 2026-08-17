package com.example.comptable.agent.pipeline;

import org.bsc.langgraph4j.action.NodeAction;
import org.springframework.stereotype.Component;

import java.util.Map;

/**
 * Materialise le point de pause "attente_validation". Grace a
 * {@code CompileConfig.interruptBefore("attente_validation")} (cf. {@link PieceJustificativeGraphConfig}),
 * l'action de ce noeud ne s'execute JAMAIS au premier passage : le graphe s'arrete juste avant et
 * persiste son etat via le checkpointer. Elle ne s'execute qu'au moment ou quelqu'un reprend
 * explicitement l'execution (ex: le comptable valide la piece dans l'inbox, potentiellement
 * plusieurs jours plus tard) - a ce moment-la, "avoir atteint ce noeud" signifie par construction
 * qu'une validation humaine a eu lieu.
 */
@Component
public class ValidationHumaineNode implements NodeAction<PieceJustificativeState> {

    @Override
    public Map<String, Object> apply(PieceJustificativeState state) {
        return Map.of(PieceJustificativeState.VALIDE_PAR_HUMAIN, true);
    }
}
