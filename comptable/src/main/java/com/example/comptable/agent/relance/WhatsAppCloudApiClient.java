package com.example.comptable.agent.relance;

import com.example.comptable.agent.client.AiServiceClient;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClientException;

import java.util.List;

/**
 * Envoi de messages sortants via l'API Cloud WhatsApp de Meta. Expose volontairement une seule
 * methode, {@link #envoyerTemplate}, qui n'accepte qu'un template pre-approuve - jamais de texte
 * libre. Ce n'est pas une limitation technique arbitraire : en dehors de la fenetre de 24h suivant
 * le dernier message du client (systematiquement le cas pour une relance de fin de mois), l'API
 * Meta rejette tout message qui n'est pas un template approuve. Restreindre l'API Java a cette
 * seule capacite rend l'erreur impossible a commettre par erreur ailleurs dans le code, plutot que
 * de compter sur une simple convention documentee.
 * <p>
 * Delegue au microservice IA (ai-service, {@code POST /api/v1/whatsapp/send-template}) via
 * {@link AiServiceClient#envoyerTemplate} : l'integration directe avec l'API Graph de Meta
 * (config {@code whatsapp.access-token}/{@code whatsapp.phone-number-id}/etc.) vit desormais
 * cote Python, qui possede deja la meme logique pour l'ingestion entrante (memes identifiants
 * Meta). Ce client garde la meme signature publique et la meme exception qu'avant migration,
 * donc {@link RelancePieceService} n'a besoin d'aucun changement.
 */
@Component
public class WhatsAppCloudApiClient {

    private final AiServiceClient aiServiceClient;

    public WhatsAppCloudApiClient(AiServiceClient aiServiceClient) {
        this.aiServiceClient = aiServiceClient;
    }

    /**
     * @param numeroDestinataire format E.164 sans "+" (ex: "2250102030405"), deja normalise par l'appelant
     * @param nomTemplate nom exact du template approuve dans Meta Business Manager
     * @param langue code langue du template tel qu'approuve (ex: "fr")
     * @param parametresCorps valeurs positionnelles des {{1}}, {{2}}... du corps du template
     * @return l'identifiant du message WhatsApp (wamid), a conserver pour l'audit
     * @throws WhatsAppEnvoiIndisponibleException si ai-service est indisponible ou si l'envoi echoue
     */
    public String envoyerTemplate(String numeroDestinataire, String nomTemplate, String langue,
                                   List<String> parametresCorps) {
        try {
            return aiServiceClient.envoyerTemplate(numeroDestinataire, nomTemplate, langue, parametresCorps);
        } catch (RestClientException e) {
            throw new WhatsAppEnvoiIndisponibleException(
                    "Echec de l'envoi WhatsApp vers " + numeroDestinataire + " (template " + nomTemplate + ") : "
                            + e.getMessage(), e);
        }
    }

    public static class WhatsAppEnvoiIndisponibleException extends RuntimeException {
        public WhatsAppEnvoiIndisponibleException(String message) {
            super(message);
        }

        /** Conserve la cause : le code HTTP distingue un refus definitif (destinataire/template) d'une panne passagere. */
        public WhatsAppEnvoiIndisponibleException(String message, Throwable cause) {
            super(message, cause);
        }
    }
}
