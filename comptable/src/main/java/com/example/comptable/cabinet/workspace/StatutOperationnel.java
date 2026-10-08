package com.example.comptable.cabinet.workspace;

/** Statut opérationnel d'un dossier (entreprise + exercice), toujours dérivé de l'état réel par {@link DossierOperationalStatusEvaluator}. */
public enum StatutOperationnel {
    A_DEMARRER("À démarrer"),
    EN_COURS("En cours"),
    ATTENTION_REQUISE("Attention requise"),
    EN_ATTENTE_CLIENT("En attente du client"),
    PRET_POUR_REVUE("Prêt pour revue"),
    EN_REVUE("En revue"),
    PRET_A_CLOTURER("Prêt à clôturer"),
    CLOTURE("Clôturé"),
    FINALISE("Finalisé");

    private final String libelle;

    StatutOperationnel(String libelle) {
        this.libelle = libelle;
    }

    public String libelle() {
        return libelle;
    }

    /** Dossier encore à travailler (ni clôturé ni finalisé). */
    public boolean actif() {
        return this != CLOTURE && this != FINALISE;
    }
}
