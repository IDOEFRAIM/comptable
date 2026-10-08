package com.example.comptable.document.hub;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

/** Formes de reponse du Hub documents. */
public final class DocumentHubDto {

    private DocumentHubDto() {
    }

    /**
     * Compteurs du mois. {@code piecesManquantes} compte des OPERATIONS sans justificatif (constats actifs), pas des documents :
     * l'absence de piece n'est pas un document.
     */
    public record Synthese(String periode, long recus, long traites, long aVerifier, long doublons, long nonRapproches,
                           long piecesManquantes, long aConfirmer, long rapprochesAutomatiquement) {
    }

    public record Association(String type, UUID id, String libelle) {
    }

    /** Une ligne du Hub : ce que le comptable doit voir pour decider, sans ouvrir la piece. */
    public record Ligne(
            UUID id, String type, String canal, OffsetDateTime recuLe, LocalDate datePiece,
            UUID tiersId, String tiersNom, String fournisseurExtrait, String numeroFacture,
            BigDecimal montantTtc, String devise, String statut,
            String statutRapprochement, Association association, UUID ecritureId,
            String qualiteTiers, Double confianceTiers, Double confianceRapprochement,
            boolean valide, boolean doublonConfirme, List<String> constats) {
    }

    public record Page(List<Ligne> contenu, int page, int taille, long total, int totalPages) {
    }

    public record DocumentSimple(UUID id, String nomFichier, OffsetDateTime recuLe, BigDecimal montantTtc, String numeroFacture, String raison) {
    }
}
