package com.example.comptable.collecte.delivery;

import com.example.comptable.agent.relance.WhatsAppCloudApiClient;
import com.example.comptable.collecte.domain.CanalMessage;
import com.example.comptable.collecte.domain.OutboundMessage;
import com.example.comptable.collecte.service.MessageTemplates;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.client.HttpClientErrorException;

/**
 * Envoi par WhatsApp (API Cloud de Meta, via ai-service) : uniquement un template approuve, jamais de texte libre.
 * Un refus HTTP 4xx (hors 408/429) est DEFINITIF ; le reste (timeout, 5xx, limite de debit, ai-service indisponible) est
 * reessayable.
 */
@Component
public class WhatsAppDeliveryAdapter implements MessageDeliveryAdapter {

    private final WhatsAppCloudApiClient client;
    private final MessageTemplates templates;
    private final String langue;

    public WhatsAppDeliveryAdapter(WhatsAppCloudApiClient client, MessageTemplates templates,
                                   @Value("${collecte.whatsapp.langue:fr}") String langue) {
        this.client = client;
        this.templates = templates;
        this.langue = langue;
    }

    @Override
    public CanalMessage canal() {
        return CanalMessage.WHATSAPP;
    }

    @Override
    public DeliveryResult send(OutboundMessage message) {
        try {
            MessageTemplates.Rendu rendu = templates.rendre(message);
            String id = client.envoyerTemplate(message.getRecipient(), rendu.templateFournisseur(), langue, rendu.parametres());
            return DeliveryResult.envoye(id);
        } catch (WhatsAppCloudApiClient.WhatsAppEnvoiIndisponibleException e) {
            if (e.getCause() instanceof HttpClientErrorException http && http.getStatusCode().value() != 408
                    && http.getStatusCode().value() != 429) {
                return DeliveryResult.definitif("Refus du fournisseur (" + http.getStatusCode().value() + ")");
            }
            return DeliveryResult.reessayable(tronquer(e.getMessage()));
        } catch (RuntimeException e) {
            return DeliveryResult.reessayable(tronquer(e.toString()));
        }
    }

    private static String tronquer(String texte) {
        return texte == null ? null : texte.length() > 400 ? texte.substring(0, 400) : texte;
    }
}
