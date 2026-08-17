package com.example.comptable.agent.pipeline.web.dto;

import com.example.comptable.document.domain.Document;
import com.example.comptable.document.web.dto.AlerteFraudeDto;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

/**
 * Piece en pause au noeud {@code attente_validation} du pipeline agentique, telle qu'affichee
 * dans l'inbox de validation (Sprint 4) : donnees extraites par le VLM + confiance + URL du
 * fichier original pour le volet gauche (visualiseur). Depuis le Sprint 7, porte aussi le
 * verdict du controleur de fraude ({@code scoreRisqueFraude}/{@code alertesFraude}) : une piece
 * peut apparaitre ici soit parce que la confiance d'extraction est insuffisante (Sprint 3), soit
 * parce qu'un score de risque CRITIQUE l'y a forcee - {@code scoreRisqueFraude} permet au
 * frontend de distinguer les deux (badge rouge) sans logique supplementaire.
 * <p>
 * Depuis le Sprint 8 (explicabilite pedagogique) : {@code niveauConfiance} evite de dupliquer le
 * seuil 95% cote frontend (meme valeur que {@code PieceJustificativeGraphConfig.SEUIL_CONFIANCE_AUTO},
 * qui decide du routage automatique - deux endroits, un seul concept, valeur dupliquee
 * volontairement plutot que couplee, ces deux classes n'ayant pas vocation a partager une
 * dependance). {@code messageExtraction} n'est PAS un texte genere pour l'occasion : c'est le
 * motif que le moteur d'extraction (VLM ou heuristique, cf. {@code OcrResult#message}) a deja
 * produit au moment de l'ingestion et qui dormait jusqu'ici, non expose par ce DTO.
 */
public record DocumentEnAttenteValidationDto(
        UUID id,
        UUID entrepriseId,
        String typeDocument,
        String canalSource,
        String nomFichierOriginal,
        String contentType,
        String urlFichier,
        BigDecimal montantHt,
        BigDecimal montantTva,
        BigDecimal montantTtc,
        LocalDate datePiece,
        double confiance,
        String niveauConfiance,
        String messageExtraction,
        String scoreRisqueFraude,
        List<AlerteFraudeDto> alertesFraude,
        OffsetDateTime createdAt
) {
    /** Seuil au-dela duquel l'extraction est jugee fiable sans reserve - identique au seuil
     * d'auto-validation du pipeline agentique (meme raisonnement metier, deux endroits). */
    private static final double SEUIL_CONFIANCE_HAUTE = 0.95;

    public static DocumentEnAttenteValidationDto from(Document document) {
        UUID entrepriseId = document.getEntreprise().getId();
        String urlFichier = "/api/entreprises/%s/documents/%s/fichier".formatted(entrepriseId, document.getId());
        double confiance = document.confianceExtraction();
        return new DocumentEnAttenteValidationDto(
                document.getId(),
                entrepriseId,
                document.getTypeDocument().name(),
                document.getCanalSource().name(),
                document.getNomFichierOriginal(),
                document.getContentType(),
                urlFichier,
                document.getMontantHt(),
                document.getMontantTva(),
                document.getMontantTtc(),
                document.getDatePiece(),
                confiance,
                confiance >= SEUIL_CONFIANCE_HAUTE ? "HAUTE" : "A_VERIFIER",
                messageExtraction(document),
                AlerteFraudeDto.scoreRisque(document),
                AlerteFraudeDto.alertes(document),
                document.getCreatedAt()
        );
    }

    private static String messageExtraction(Document document) {
        if (document.getDonneesOcr() == null) {
            return null;
        }
        Object message = document.getDonneesOcr().get("message");
        return message != null ? message.toString() : null;
    }
}
