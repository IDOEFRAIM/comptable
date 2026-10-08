package com.example.comptable.controle;

import com.example.comptable.entreprise.domain.Entreprise;

import java.util.List;
import java.util.Set;

/**
 * Un detecteur lit l'etat du dossier et produit des {@link ControlResult} ; il ne cree jamais de
 * Finding lui-meme. {@link ControlRunService} les execute et {@link ControlReconciler} aligne les
 * Findings sur le resultat (creation, mise a jour, resolution automatique quand la cause disparait).
 *
 * <p>Contrat : le detecteur retourne TOUS les constats actuellement vrais pour les codes qu'il
 * declare dans {@link #codes()} - c'est ce qui permet de fermer un Finding dont le constat ne
 * revient plus. Il agrege (un constat = une decision), il ne cree jamais un constat par operation
 * saine.</p>
 */
public interface ControlDetector {

    /** Nom court pour le journal d'execution. */
    String nom();

    /** Codes de controle dont ce detecteur est l'unique autorite (perimetre de la resolution automatique). */
    Set<String> codes();

    List<ControlResult> detecter(Entreprise entreprise);
}
