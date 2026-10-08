package com.example.comptable.comptabilite.allocation;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.UUID;

/** Representations des paiements et allocations : le comptable choisit une facture ou un reglement par ses libelles, jamais par un identifiant. */
public final class AllocationDto {

    private AllocationDto() {
    }

    /** Un reglement qui attend d'etre affecte (en tout ou partie). */
    public record ReglementARepartir(UUID paymentLineId, String piece, LocalDate date, String libelle, String tiers, BigDecimal montant,
                                     BigDecimal alloue, BigDecimal nonAffecte) {
    }

    /** Une facture pouvant recevoir un reglement. */
    public record FactureCandidate(UUID invoiceLineId, String piece, LocalDate date, String libelle, String tiers, BigDecimal montant,
                                   BigDecimal alloue, BigDecimal reste, String statut) {
    }

    public record Allocation(UUID id, UUID paymentLineId, UUID invoiceLineId, String piecePaiement, String pieceFacture, String tiers,
                             BigDecimal montant, String statut, String source, OffsetDateTime creeLe, OffsetDateTime annuleLe, String motifAnnulation) {
    }
}
