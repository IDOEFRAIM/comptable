package com.example.comptable.fiscalite.paiement.web;

import com.example.comptable.fiscalite.paiement.domain.PaiementFournisseur;
import com.example.comptable.fiscalite.paiement.service.PaiementFiscalService;
import com.example.comptable.fiscalite.paiement.web.dto.PaiementFiscalDto;
import com.example.comptable.fiscalite.paiement.web.dto.WebhookPaiementRequest;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

/**
 * Point d'entree pour les notifications asynchrones (webhooks) des fournisseurs de paiement, une
 * fois les integrations reelles branchees : e-Syntaxe et Faso Arzeka confirment un paiement de
 * facon asynchrone (le payeur valide par PIN/USSD ou sur leur portail), jamais dans la reponse
 * HTTP de l'initiation - voir {@code EsyntaxeTaxPaymentProvider}/{@code FasoArzekaTaxPaymentProvider}.
 * <p>
 * Le format exact du corps (champs, encodage, methode de signature) n'est pas connu tant que la
 * documentation officielle de chaque fournisseur n'a pas ete obtenue : {@link WebhookPaiementRequest}
 * est un format generique provisoire, a adapter (renommer les champs, ajouter la verification de
 * signature propre au fournisseur) des que cette documentation est disponible. En attendant, la
 * protection est un simple secret partage (header {@code X-Webhook-Api-Key}), sur le meme principe
 * que {@code X-Internal-Api-Key} entre ce module et ai-service.
 */
@RestController
@RequestMapping("/api/webhooks/paiements/{fournisseur}")
public class PaiementWebhookController {

    private final PaiementFiscalService paiementFiscalService;
    private final String apiKeyAttendue;

    public PaiementWebhookController(PaiementFiscalService paiementFiscalService,
                                      @Value("${paiement.webhook.api-key:}") String apiKeyAttendue) {
        this.paiementFiscalService = paiementFiscalService;
        this.apiKeyAttendue = apiKeyAttendue;
    }

    @PostMapping
    public PaiementFiscalDto recevoir(@PathVariable PaiementFournisseur fournisseur,
                                       @RequestHeader(value = "X-Webhook-Api-Key", required = false) String apiKey,
                                       @RequestBody WebhookPaiementRequest requete) {
        if (apiKeyAttendue.isBlank() || !apiKeyAttendue.equals(apiKey)) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Cle de webhook invalide");
        }
        return paiementFiscalService.confirmer(fournisseur, requete.referenceExterne(), requete.reussite(), requete.message());
    }
}
