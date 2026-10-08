package com.example.comptable.comptabilite.parametrage.domain;

import java.util.Set;

/**
 * Role metier d'un compte, independant de tout numero SYSCOHADA precis - la liste minimale
 * couvrant les comptes reellement consommes par les modules existants (achats, ventes, TVA,
 * stock, tresorerie, immobilisations, resultat), pas une liste exhaustive de tous les roles
 * imaginables (mission Etape 6, section 4). {@link #classesAttendues()} sert uniquement a valider
 * un mapping a sa creation (AccountingAccountMappingService) - jamais a deviner un compte a la
 * resolution.
 */
public enum AccountPurpose {
    ACHAT_MARCHANDISE(6),
    ACHAT_MATIERE(6),
    ACHAT_CONSOMMABLE(6),
    ACHAT_SERVICE(6),
    ACHAT_FRAIS_ACCESSOIRE(6),

    TVA_COLLECTEE(4),
    TVA_RECUPERABLE(4),

    STOCK_MARCHANDISE(3),
    STOCK_MATIERE(3),
    STOCK_PRODUIT(3),
    STOCK_AUTRE(3),
    VARIATION_STOCK(6),

    CLIENT(4),
    FOURNISSEUR(4),

    BANQUE(5),
    CAISSE(5),

    IMMOBILISATION_CORPORELLE(2),
    IMMOBILISATION_INCORPORELLE(2),
    AMORTISSEMENT(2),
    DOTATION_AMORTISSEMENT(6),

    RESULTAT_BENEFICE(1),
    RESULTAT_PERTE(1);

    private final int classeAttendue;

    AccountPurpose(int classeAttendue) {
        this.classeAttendue = classeAttendue;
    }

    /** Classe SYSCOHADA attendue pour un compte affecte a ce purpose - utilise uniquement a la
     * validation d'un mapping, jamais pour deviner un compte. */
    public Set<Integer> classesAttendues() {
        return Set.of(classeAttendue);
    }
}
