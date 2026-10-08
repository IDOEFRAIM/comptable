package com.example.comptable.cabinet.workspace;

/** Étapes métier d'un dossier : la progression est « n étapes prêtes sur 7 », jamais un pourcentage inventé. */
public enum EtapeDossier {
    PIECES("Pièces"),
    BANQUE("Banque"),
    COMPTABILITE("Comptabilité"),
    CONTROLES("Contrôles"),
    PRE_CLOTURE("Pré-clôture"),
    ETATS_FINANCIERS("États financiers"),
    FINALISATION("Finalisation");

    private final String libelle;

    EtapeDossier(String libelle) {
        this.libelle = libelle;
    }

    public String libelle() {
        return libelle;
    }
}
