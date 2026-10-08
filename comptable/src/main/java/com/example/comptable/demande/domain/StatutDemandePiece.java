package com.example.comptable.demande.domain;

/**
 * Cycle d'une demande de piece. L'absence de piece n'est pas un statut de document : elle est portee par le constat
 * {@code MISSING_DOCUMENT} ; la DEMANDE est ce qui est envoye au client.
 *
 * <pre>
 *  A_ENVOYER (CREATED : message en boite d'envoi) -> EN_ATTENTE (SENT/WAITING : message parti, on attend le client)
 *    -> RECU (RECEIVED : une vraie piece est deposee) -> RAPPROCHEE (MATCHED/RESOLVED : rattachee a l'operation)
 *  voies alternatives : ECHEC_ENVOI (FAILED : aucun contact / destinataire refuse / echecs repetes),
 *                       ANNULEE (CANCELLED), EXPIREE (EXPIRED : jamais de reponse)
 * </pre>
 * Le RETRY technique (le message n'est pas parti) vit sur le message ; la RELANCE client (parti, sans reponse) vit sur la
 * demande ({@code nbRelances}) : deux mecanismes distincts.
 */
public enum StatutDemandePiece {
    A_ENVOYER,
    EN_ATTENTE,
    RECU,
    RAPPROCHEE,
    ECHEC_ENVOI,
    ANNULEE,
    EXPIREE;

    /** Etats ou la demande est encore a traiter (la piece n'est pas rattachee et la demande n'est pas close). */
    public boolean estOuverte() {
        return this == A_ENVOYER || this == EN_ATTENTE || this == RECU || this == ECHEC_ENVOI;
    }
}
