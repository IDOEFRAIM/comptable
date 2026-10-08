package com.example.comptable.collecte.delivery;

import com.example.comptable.collecte.domain.CanalMessage;
import com.example.comptable.collecte.domain.OutboundMessage;

/** Un canal d'envoi. Une seule implementation reellement utilisee pour l'instant (WhatsApp) ; EMAIL viendra se brancher ici. */
public interface MessageDeliveryAdapter {

    CanalMessage canal();

    /** Ne leve jamais : toute panne est traduite en {@link DeliveryResult}. */
    DeliveryResult send(OutboundMessage message);
}
