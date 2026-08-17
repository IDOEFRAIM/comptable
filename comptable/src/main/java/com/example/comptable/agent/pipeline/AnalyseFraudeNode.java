package com.example.comptable.agent.pipeline;

import com.example.comptable.agent.fraude.RapportFraude;
import com.example.comptable.document.domain.Document;
import com.example.comptable.document.repository.DocumentRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.bsc.langgraph4j.action.NodeAction;
import org.springframework.stereotype.Component;

import java.util.Map;

/**
 * Ne relance AUCUNE analyse : le controle de fraude (Sprint 7,
 * {@code agent.fraude.AnalyseFraudeService}) tourne une seule fois, de facon synchrone, au
 * moment de l'ingestion du document ({@code DocumentService.appliquerExtraction}) - pour les
 * deux canaux (upload web et WhatsApp), pas seulement pour celui qui passe par ce graphe. Ce
 * noeud se contente de relire le verdict deja persiste sur {@link Document#getRapportFraude()}
 * et de l'exposer dans l'etat du graphe, pour que {@link PieceJustificativeGraphConfig} puisse
 * router vers la validation humaine sans reinterroger le modele (cout/latence).
 */
@Component
public class AnalyseFraudeNode implements NodeAction<PieceJustificativeState> {

    private final DocumentRepository documentRepository;
    private final ObjectMapper objectMapper;

    public AnalyseFraudeNode(DocumentRepository documentRepository, ObjectMapper objectMapper) {
        this.documentRepository = documentRepository;
        this.objectMapper = objectMapper;
    }

    @Override
    public Map<String, Object> apply(PieceJustificativeState state) {
        Document document = documentRepository.findById(state.documentId())
                .orElseThrow(() -> new IllegalStateException("Document introuvable : " + state.documentId()));
        if (document.getRapportFraude() == null) {
            return Map.of();
        }
        RapportFraude rapport = objectMapper.convertValue(document.getRapportFraude(), RapportFraude.class);
        return Map.of(PieceJustificativeState.RAPPORT_FRAUDE, rapport);
    }
}
