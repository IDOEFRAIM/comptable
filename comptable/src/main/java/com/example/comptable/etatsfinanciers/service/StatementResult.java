package com.example.comptable.etatsfinanciers.service;

import com.example.comptable.comptabilite.balance.DatasetVersion;
import com.example.comptable.controle.ControlResult;
import com.example.comptable.etatsfinanciers.definition.CalculationType;
import com.example.comptable.etatsfinanciers.definition.MappingRole;
import com.example.comptable.etatsfinanciers.definition.ProvenanceType;
import com.example.comptable.etatsfinanciers.definition.StatementType;
import com.example.comptable.etatsfinanciers.definition.VerificationStatus;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

/**
 * Resultat calcule d'un etat financier : chaque ligne porte ses montants N et N-1 (calcules sur les exercices REELS), sa provenance, et les comptes qui la
 * composent (premier niveau du drill-down). Un etat est {@code READY} seulement si tout est mappe, coherent et verifie ; {@code INCOMPLETE} quand des donnees
 * reglementaires manquent (Passif, TFT) : il n'est alors jamais presente comme complet. {@code FINAL} n'existe que dans un snapshot persiste.
 */
public record StatementResult(StatementType statementType, String framework, String version, String definitionHash,
                              UUID exerciceId, LocalDate exerciceDebut, LocalDate exerciceFin,
                              UUID exercicePrecedentId, LocalDate precedentDebut, LocalDate precedentFin,
                              Status status, List<String> statusReasons, List<Line> lines, List<AccountRef> unmapped, List<Ambiguous> ambiguous,
                              List<ControlResult> controls, DatasetVersion dataset, OffsetDateTime generatedAt) {

    public enum Status { DRAFT, READY, INCOMPLETE, FINAL }

    public record AccountRef(UUID accountId, String number, String label, BigDecimal balanceN) {
    }

    public record Ambiguous(UUID accountId, String number, String label, List<String> candidateLines) {
    }

    public record LineAccount(UUID accountId, String number, String label, MappingRole role, BigDecimal amountN, BigDecimal amountNMinus1) {
    }

    /**
     * @param amountN        montant de la ligne en N (net pour l'Actif) ; {@code null} si non calculable
     * @param amountNMinus1  montant correspondant de l'exercice precedent reel ; {@code null} si pas d'exercice precedent ou non calculable
     * @param grossN         (Actif) brut N ; {@code depreciationN} amortissements/depreciations N
     * @param notCalculableReason pourquoi la ligne n'a pas de montant, le cas echeant
     * @param complete       faux si la ligne (ou une ligne dont elle depend) a une composante reglementaire non codee ou non classifiee : son montant est alors PARTIEL
     * @param incompleteReasons raisons explicites de l'incompletude
     */
    public record Line(String code, String label, int order, CalculationType calculationType, VerificationStatus verificationStatus, ProvenanceType provenanceType,
                       BigDecimal amountN, BigDecimal amountNMinus1, BigDecimal grossN, BigDecimal depreciationN, String notCalculableReason,
                       List<LineAccount> accounts, boolean complete, List<String> incompleteReasons) {

        public Line {
            incompleteReasons = incompleteReasons == null ? List.of() : List.copyOf(incompleteReasons);
        }

        /** Variation N - N-1, calculee en BigDecimal ; {@code null} si une des deux valeurs est inconnue (jamais zero par defaut). Le frontend l'affiche sans rien calculer. */
        @com.fasterxml.jackson.annotation.JsonProperty("variation")
        public BigDecimal variation() {
            return amountN == null || amountNMinus1 == null ? null : amountN.subtract(amountNMinus1);
        }
    }

    public StatementResult avecStatut(Status nouveau) {
        return new StatementResult(statementType, framework, version, definitionHash, exerciceId, exerciceDebut, exerciceFin, exercicePrecedentId, precedentDebut, precedentFin,
                nouveau, statusReasons, lines, unmapped, ambiguous, controls, dataset, generatedAt);
    }
}
