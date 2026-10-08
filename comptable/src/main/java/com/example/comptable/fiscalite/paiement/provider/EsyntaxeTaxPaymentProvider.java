package com.example.comptable.fiscalite.paiement.provider;

import com.example.comptable.fiscalite.paiement.domain.PaiementFournisseur;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

/**
 * Telepaiement des impots via e-Syntaxe, la plateforme de teledeclaration/telepaiement de la
 * Direction Generale des Impots (DGI) du Burkina Faso.
 * <p>
 * A ce jour, l'application n'a pas d'acces (URL, cle API, format d'echange) a l'API e-Syntaxe -
 * elle n'est pas publique et suppose une convention avec la DGI. Tant que {@code esyntaxe.mode}
 * vaut "simulation" (valeur par defaut, voir application.properties), {@link #initierPaiement} ne
 * contacte aucun service externe : il fabrique une reference locale et renvoie un statut
 * EN_ATTENTE_CONFIRMATION, a confirmer soit via l'endpoint de simulation
 * ({@code POST .../paiements/{id}/simuler-confirmation}), soit plus tard via le vrai webhook
 * e-Syntaxe une fois branche (voir {@code PaiementWebhookController}).
 * <p>
 * Une fois l'acces obtenu aupres de la DGI, il suffit de : (1) renseigner {@code esyntaxe.base-url}
 * et {@code esyntaxe.api-key}, (2) implementer {@link #appellerApiReelle}, (3) passer
 * {@code esyntaxe.mode=reel}. Le reste du module (entite, service, controleurs, frontend) n'a pas
 * besoin de changer.
 */
@Component
public class EsyntaxeTaxPaymentProvider implements TaxPaymentProvider {

    private static final Logger log = LoggerFactory.getLogger(EsyntaxeTaxPaymentProvider.class);

    private final RestClient restClient;
    private final String mode;

    public EsyntaxeTaxPaymentProvider(RestClient.Builder restClientBuilder,
                                       @Value("${esyntaxe.base-url:}") String baseUrl,
                                       @Value("${esyntaxe.api-key:}") String apiKey,
                                       @Value("${esyntaxe.mode:simulation}") String mode) {
        this.mode = mode;
        RestClient.Builder builder = restClientBuilder;
        if (!baseUrl.isBlank()) {
            builder = builder.baseUrl(baseUrl);
        }
        if (!apiKey.isBlank()) {
            builder = builder.defaultHeader("Authorization", "Bearer " + apiKey);
        }
        this.restClient = builder.build();
    }

    @Override
    public PaiementFournisseur fournisseur() {
        return PaiementFournisseur.E_SYNTAXE;
    }

    @Override
    public TaxPaymentInitiationResult initierPaiement(TaxPaymentRequest requete) {
        if (!"reel".equalsIgnoreCase(mode)) {
            String reference = "SIM-ESYNTAXE-" + requete.referenceInterne();
            log.info("[simulation] Paiement e-Syntaxe simule pour le contribuable {} ({} {}) : reference {}",
                    requete.numeroContribuable(), requete.typeImpot(), requete.periode(), reference);
            return TaxPaymentInitiationResult.enAttente(reference,
                    "Simulation - l'API e-Syntaxe n'est pas encore branchee, aucun paiement reel n'a ete "
                            + "effectue. Confirmez une fois le paiement verifie sur le site de la DGI.");
        }
        return appellerApiReelle(requete);
    }

    /**
     * TODO integration reelle : une fois l'acces e-Syntaxe obtenu aupres de la DGI, remplacer ce
     * corps par l'appel HTTP reel, par exemple :
     * <pre>{@code
     * EsyntaxeReponse reponse = restClient.post()
     *         .uri("/a-definir-selon-doc-dgi")
     *         .body(new EsyntaxeRequete(requete.numeroContribuable(), requete.typeImpot().name(),
     *                 requete.periode(), requete.montant()))
     *         .retrieve()
     *         .body(EsyntaxeReponse.class);
     * return TaxPaymentInitiationResult.enAttente(reponse.reference(), reponse.instructions());
     * }</pre>
     * en adaptant les noms de champs/endpoint au format reellement documente par la DGI, et en
     * attrapant {@link org.springframework.web.client.RestClientException} pour la traduire en
     * {@link TaxPaymentInitiationResult#echec}.
     */
    private TaxPaymentInitiationResult appellerApiReelle(TaxPaymentRequest requete) {
        throw new UnsupportedOperationException(
                "Integration reelle e-Syntaxe non implementee - specifications API a obtenir aupres de la DGI. "
                        + "Repassez esyntaxe.mode=simulation en attendant.");
    }
}
