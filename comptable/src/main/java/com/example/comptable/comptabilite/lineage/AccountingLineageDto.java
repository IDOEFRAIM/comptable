package com.example.comptable.comptabilite.lineage;

import java.util.List;
import java.util.UUID;

/**
 * Chaine de causalite comptable, lisible dans les deux sens, sans aucune heuristique : tout vient de liens
 * enregistres (source de la proposition, proposition de l'ecriture, document lie a l'ecriture).
 */
public final class AccountingLineageDto {

    private AccountingLineageDto() {
    }

    public record Source(String type, UUID id, String event) {
    }

    public record Proposition(UUID id, String type, String statut, Source source, UUID ecritureId) {
    }

    /** @param lien DIRECT (la piece justifie l'ecriture), VIA_FACTURE (rapprochee a la facture qui porte l'ecriture) ou RAPPROCHEE (cible de la piece). */
    public record DocumentOrigine(UUID id, String nomFichier, String typeDocument, String lien) {
    }

    /** Ecriture -> proposition -> source (-> document). {@code origine} vaut MOTEUR ou SAISIE_MANUELLE. */
    public record DepuisEcriture(UUID ecritureId, String numeroPiece, String statut, String origine,
                                 Proposition proposition, DocumentOrigine document, List<DocumentOrigine> documents) {
    }

    /** Source -> toutes ses consequences comptables (une par evenement, plus d'eventuelles propositions rejetees). */
    public record DepuisSource(String sourceType, UUID sourceId, List<Proposition> consequences, List<DocumentOrigine> documents) {
    }

    /** Facture visee par une piece. */
    public record CibleFacture(String type, UUID id) {
    }

    /** Document -> facture -> propositions -> ecritures (et les ecritures issues directement de la piece). */
    public record DepuisDocument(UUID documentId, String nomFichier, String typeDocument, String statutRapprochement,
                                 CibleFacture cible, UUID ecritureDirecteId, List<Proposition> consequences) {
    }
}
