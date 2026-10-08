package com.example.comptable.comptabilite.operation.common;

import java.util.UUID;

/**
 * Cause metier d'une proposition comptable (Phase B, lot B0) : « quelle operation a provoque cette
 * consequence comptable ? ». Le triplet {@code (entreprise, type, id, event)} IDENTIFIE une consequence
 * comptable : la base garantit qu'il n'en existe qu'une active. Ce n'est pas de l'event sourcing, juste
 * « une cause metier -> une consequence comptable identifiable ».
 *
 * <p>Exemples : {@code DOCUMENT/<uuid>/COMPTABILISATION}, {@code FACTURE/<uuid>/VALIDATION},
 * {@code MOUVEMENT_STOCK/<uuid>/SORTIE}, {@code IMMOBILISATION/<uuid>/ACQUISITION},
 * {@code IMMOBILISATION/<uuid>/DOTATION_2026}, {@code EXERCICE/<uuid>/CLOTURE}.</p>
 *
 * @param type  objet metier a l'origine (jamais l'identifiant d'une proposition)
 * @param id    identifiant de cet objet
 * @param event evenement precis de la vie de l'objet (une meme source peut avoir plusieurs evenements,
 *              chacun avec sa propre consequence : acquisition, puis une dotation par annee)
 */
public record AccountingSource(String type, UUID id, String event) {

    /** Type de source d'une operation saisie directement (API de proposition) sans objet metier persiste. */
    public static final String OPERATION_SAISIE = "OPERATION_SAISIE";

    public AccountingSource {
        if (type == null || type.isBlank() || id == null || event == null || event.isBlank()) {
            throw new IllegalArgumentException("Une source comptable exige type, id et evenement");
        }
    }

    public static AccountingSource de(String type, UUID id, String event) {
        return new AccountingSource(type, id, event);
    }

    /** Un document comptabilise : le meme que le flux manuel ou le flux IA l'utilise (un seul des deux peut aboutir). */
    public static AccountingSource document(UUID documentId) {
        return new AccountingSource("DOCUMENT", documentId, "COMPTABILISATION");
    }

    /** Operation saisie sans objet metier : sa propre cause, identifiee par un identifiant d'operation neuf. */
    public static AccountingSource operationSaisie() {
        return new AccountingSource(OPERATION_SAISIE, UUID.randomUUID(), "PROPOSITION");
    }
}
