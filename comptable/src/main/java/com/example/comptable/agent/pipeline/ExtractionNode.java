package com.example.comptable.agent.pipeline;

import com.example.comptable.document.domain.Document;
import com.example.comptable.document.repository.DocumentRepository;
import org.bsc.langgraph4j.action.NodeAction;
import org.springframework.stereotype.Component;

import java.util.Map;

/**
 * Premier noeud du graphe : ne relance pas d'extraction, il rejoue les donnees deja produites
 * par le VLM au moment de l'ingestion ({@code DocumentService.appliquerExtraction}, Sprint 1) et
 * deja persistees sur le {@link Document}. Le pipeline agentique demarre donc une fois qu'un
 * Document existe deja (WhatsApp, Sprint 2, ou upload web), pas avant.
 */
@Component
public class ExtractionNode implements NodeAction<PieceJustificativeState> {

    private final DocumentRepository documentRepository;

    public ExtractionNode(DocumentRepository documentRepository) {
        this.documentRepository = documentRepository;
    }

    @Override
    public Map<String, Object> apply(PieceJustificativeState state) {
        Document document = documentRepository.findById(state.documentId())
                .orElseThrow(() -> new IllegalStateException("Document introuvable : " + state.documentId()));
        if (!document.getEntreprise().getId().equals(state.entrepriseId())) {
            // Filet de securite multi-tenant : l'entrepriseId de l'etat doit toujours correspondre
            // au document reellement charge, jamais faire confiance a l'un sans verifier l'autre.
            throw new IllegalStateException(
                    "Document " + state.documentId() + " n'appartient pas a l'entreprise " + state.entrepriseId());
        }

        ExtractionPieceResult extraction = new ExtractionPieceResult(
                document.getMontantHt(), document.getMontantTva(), document.getMontantTtc(),
                document.getDatePiece(), document.confianceExtraction());

        return Map.of(PieceJustificativeState.EXTRACTION, extraction);
    }
}
