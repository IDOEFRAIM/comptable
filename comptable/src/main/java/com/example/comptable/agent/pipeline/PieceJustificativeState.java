package com.example.comptable.agent.pipeline;

import com.example.comptable.agent.fraude.RapportFraude;
import org.bsc.langgraph4j.state.AgentState;

import java.util.Map;
import java.util.UUID;

/**
 * Etat qui voyage de noeud en noeud dans le graphe de traitement d'une piece justificative.
 * <p>
 * LangGraph4j n'autorise pas un record ou une classe immuable arbitraire comme etat : le
 * framework exige une sous-classe d'{@link AgentState} (une enveloppe autour d'une
 * {@code Map<String,Object>}, avec merge partiel entre noeuds - cf. {@link AgentState#value(String)}).
 * Les six champs demandes sont exposes ici comme des accesseurs types, en gardant les cles de la
 * map comme constantes publiques pour que chaque noeud puisse construire ses mises a jour
 * partielles ({@code Map.of(KEY, valeur)}) sans dupliquer des chaines de caracteres.
 */
public class PieceJustificativeState extends AgentState {

    public static final String DOCUMENT_ID = "documentId";
    public static final String ENTREPRISE_ID = "entrepriseId";
    public static final String EXTRACTION = "extraction";
    public static final String CONFORMITE_OK = "conformiteOk";
    public static final String MOTIF_ALERTE_CONFORMITE = "motifAlerteConformite";
    public static final String VALIDE_PAR_HUMAIN = "valideParHumain";
    /** Ajoute au Sprint 7 (agent de controle de fraude) : septieme champ, en plus des six
     * d'origine - necessaire pour que {@code PieceJustificativeGraphConfig} puisse router vers
     * la validation humaine sur un score CRITIQUE sans reinterroger {@code AnalyseFraudeService}
     * a chaque noeud (cf. {@link AnalyseFraudeNode}). */
    public static final String RAPPORT_FRAUDE = "rapportFraude";

    public PieceJustificativeState(Map<String, Object> initData) {
        super(initData);
    }

    public UUID documentId() {
        return this.<UUID>value(DOCUMENT_ID).orElseThrow(() -> manquant(DOCUMENT_ID));
    }

    public UUID entrepriseId() {
        return this.<UUID>value(ENTREPRISE_ID).orElseThrow(() -> manquant(ENTREPRISE_ID));
    }

    public ExtractionPieceResult extraction() {
        return this.<ExtractionPieceResult>value(EXTRACTION).orElse(null);
    }

    public boolean conformiteOk() {
        return this.<Boolean>value(CONFORMITE_OK).orElse(false);
    }

    public String motifAlerteConformite() {
        return this.<String>value(MOTIF_ALERTE_CONFORMITE).orElse(null);
    }

    public boolean valideParHumain() {
        return this.<Boolean>value(VALIDE_PAR_HUMAIN).orElse(false);
    }

    public RapportFraude rapportFraude() {
        return this.<RapportFraude>value(RAPPORT_FRAUDE).orElse(null);
    }

    /** Constructeur d'etat initial, a fournir a {@code CompiledGraph.invoke(...)} : seuls
     * documentId/entrepriseId sont connus au demarrage, le reste est rempli par les noeuds. */
    public static Map<String, Object> initial(UUID documentId, UUID entrepriseId) {
        return Map.of(DOCUMENT_ID, documentId, ENTREPRISE_ID, entrepriseId);
    }

    private static IllegalStateException manquant(String champ) {
        return new IllegalStateException("Etat du graphe invalide : '" + champ + "' manquant");
    }
}
