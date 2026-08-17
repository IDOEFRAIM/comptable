package com.example.comptable.agent.fraude;

/**
 * Score de risque global d'un {@link RapportFraude}. L'ordre de declaration fait foi : c'est
 * lui qui determine l'agregation "pire alerte gagne" dans {@link RapportFraude#depuisAlertes}
 * (cf. {@code NiveauRisqueFraude::ordinal}), donc ne jamais reordonner ces constantes sans
 * revoir cette methode.
 */
public enum NiveauRisqueFraude {
    FAIBLE,
    MOYEN,
    CRITIQUE
}
