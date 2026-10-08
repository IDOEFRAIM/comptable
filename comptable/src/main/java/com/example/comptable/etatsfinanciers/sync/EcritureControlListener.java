package com.example.comptable.etatsfinanciers.sync;

import com.example.comptable.comptabilite.domain.EcritureComptable;
import com.example.comptable.comptabilite.domain.StatutEcriture;
import jakarta.persistence.PostPersist;
import jakarta.persistence.PostUpdate;
import org.springframework.stereotype.Component;

import java.util.EnumSet;
import java.util.Set;

/**
 * Toute écriture VALIDÉE créée ou modifiée (validation, qualification d'un flux de trésorerie, correction) change le compte de résultat, le bilan, le TFT et les notes de son exercice : le dossier est signalé
 * au {@link FinancialControlChangeTracker}. Un brouillon n'entre dans aucun état : il ne déclenche rien. Placé sur l'entité pour couvrir TOUS les chemins d'écriture (saisie, moteur d'opérations,
 * banque, facturation, clôture) sans modifier chacun d'eux.
 */
@Component
public class EcritureControlListener {

    private static final Set<FinancialControlDomain> DOMAINES = EnumSet.allOf(FinancialControlDomain.class);

    private final FinancialControlChangeTracker tracker;

    public EcritureControlListener(FinancialControlChangeTracker tracker) {
        this.tracker = tracker;
    }

    @PostPersist
    @PostUpdate
    void apresEcriture(EcritureComptable e) {
        if (e.getStatut() == StatutEcriture.VALIDEE && e.getEntreprise() != null && e.getExercice() != null) {
            tracker.marquer(e.getEntreprise().getId(), e.getExercice().getId(), DOMAINES);
        }
    }
}
