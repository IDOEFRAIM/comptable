package com.example.comptable.comptabilite.balance;

import com.example.comptable.comptabilite.domain.NatureEcriture;
import com.example.comptable.comptabilite.domain.StatutEcriture;

import java.util.EnumSet;
import java.util.Set;

/**
 * Vue de reporting : quelles ecritures entrent dans un calcul de soldes. Une seule table de verite (voir {@code docs/reporting-view-policy.md}),
 * jamais de regle implicite dispersee dans les services.
 */
public enum ReportingView {

    /** Grand livre / balance officiels : ecritures VALIDEE, toutes natures (la cloture est une vraie ecriture legale). */
    LEGAL_LEDGER(EnumSet.of(StatutEcriture.VALIDEE), EnumSet.allOf(NatureEcriture.class)),

    /**
     * Mesure de l'activite economique d'une periode : ecritures VALIDEE hors CLOSING et OPENING (techniques). Les comptes de gestion y gardent leurs
     * mouvements de l'exercice meme apres la cloture. Pour les comptes de bilan, c'est un solde AVANT determination du resultat.
     */
    PERIOD_ACTIVITY(EnumSet.of(StatutEcriture.VALIDEE), EnumSet.of(NatureEcriture.BUSINESS, NatureEcriture.ADJUSTMENT)),

    /** Vue de travail : comme LEGAL_LEDGER mais les BROUILLONS sont inclus. Jamais utilisee pour un document officiel. */
    WORKING_INCLUDING_DRAFTS(EnumSet.of(StatutEcriture.VALIDEE, StatutEcriture.BROUILLON), EnumSet.allOf(NatureEcriture.class));

    private final Set<StatutEcriture> statuts;
    private final Set<NatureEcriture> natures;

    ReportingView(Set<StatutEcriture> statuts, Set<NatureEcriture> natures) {
        this.statuts = statuts;
        this.natures = natures;
    }

    public Set<StatutEcriture> statuts() {
        return statuts;
    }

    public Set<NatureEcriture> natures() {
        return natures;
    }

    /** Vrai si la vue ne contient que des ecritures validees (candidate a un document officiel). */
    public boolean officielle() {
        return !statuts.contains(StatutEcriture.BROUILLON);
    }
}
