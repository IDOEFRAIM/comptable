package com.example.comptable.comptabilite.reprise;

import com.example.comptable.comptabilite.web.dto.EcritureComptableDto;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/** Objets de l'import de reprise (balance d'ouverture). */
public final class RepriseDtos {

    private RepriseDtos() {
    }

    /**
     * Une ligne du fichier, telle que le serveur la comprend. {@code statut} : OK, ERREUR, ou TIERS_A_CREER (le client ou fournisseur n'existe pas encore : il sera créé à l'import).
     * {@code compteRetenu} : le compte du dossier sur lequel la ligne sera passée.
     */
    public record LigneApercu(int ligne, String compte, String libelle, String tiers, BigDecimal debit, BigDecimal credit, String statut, String message, String compteRetenu) {
    }

    public record ApercuReprise(UUID exerciceId, LocalDate dateOuverture, List<LigneApercu> lignes, BigDecimal totalDebit, BigDecimal totalCredit, BigDecimal ecart, long erreurs, int tiersACreer,
                                boolean importable, String blocage, List<String> avertissements) {
    }

    public record ResultatReprise(EcritureComptableDto ecriture, int lignes, int tiersCrees) {
    }
}
