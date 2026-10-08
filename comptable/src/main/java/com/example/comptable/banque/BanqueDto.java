package com.example.comptable.banque;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** Representations de l'ecran Banque : jamais d'identifiant a saisir, tout ce qu'il faut pour decider est dans la ligne. */
public final class BanqueDto {

    private BanqueDto() {
    }

    public enum StatutTransaction { RAPPROCHEE, A_CONFIRMER, AMBIGUE, SANS_CORRESPONDANCE, VIREMENT_INTERNE }

    public record CritereDto(String code, String libelle, boolean satisfait, int points) {
    }

    /** Le rapprochement propose ou confirme d'une transaction, avec ce qui le justifie. */
    public record PropositionDto(UUID reconciliationId, UUID ligneEcritureId, String piece, LocalDate dateEcriture, String libelleEcriture,
                                 Integer confiance, String explication, List<CritereDto> criteres, String source, String statut, int nbCandidats,
                                 String type, UUID transfertId, String contrepartie) {
        /** Rapprochement avec une ecriture (cas courant) : ni virement interne, ni contrepartie. */
        public PropositionDto(UUID reconciliationId, UUID ligneEcritureId, String piece, LocalDate dateEcriture, String libelleEcriture, Integer confiance,
                              String explication, List<CritereDto> criteres, String source, String statut, int nbCandidats) {
            this(reconciliationId, ligneEcritureId, piece, dateEcriture, libelleEcriture, confiance, explication, criteres, source, statut, nbCandidats,
                    "ECRITURE", null, null);
        }
    }

    public record TransactionDto(UUID id, UUID releveId, String compteNumero, LocalDate date, String libelle, BigDecimal montant, String sens,
                                 String statut, boolean doublonPossible, String moyenPaiement, String tiersProbable, String referenceProbable,
                                 PropositionDto proposition) {
    }

    public record PageTransactions(List<TransactionDto> lignes, long total, int page, int taille, int totalPages) {
    }

    public record SyntheseDto(String periode, long transactions, long rapprochees, long aConfirmer, long ambigues, long sansCorrespondance,
                              long doublonsPossibles, double tauxRapproche, BigDecimal montantNonRapproche, long virementsInternes) {
    }

    public record CandidatDto(UUID ligneEcritureId, String piece, LocalDate date, String libelle, BigDecimal montant, int score,
                              List<CritereDto> criteres, boolean propose, boolean dejaRapprochee) {
    }

    public record PropositionLotDto(UUID reconciliationId, UUID transactionId, LocalDate date, String libelle, BigDecimal montant, String piece,
                                    int confiance, String explication) {
    }

    public record ResultatLot(int confirmees, int ignorees, List<String> motifs) {
    }

    public record MetriquesDto(long transactions, double pctRapprochesAutomatiquement, double pctNecessitantConfirmation, double pctAmbigues,
                               double pctSansCorrespondance, Double tempsMoyenRapprochementHeures, long facturesOuvertes,
                               BigDecimal montantNonRapproche, Map<String, Long> ancienneteDesEcarts, Long plusAncienEcartJours) {
    }

    public record LotImportDto(UUID id, String fichier, OffsetDateTime importeLe, int lignesLues, int valides, int rejetees, int doublonsExacts,
                               int doublonsPossibles, int importees, String statut) {
    }
}
