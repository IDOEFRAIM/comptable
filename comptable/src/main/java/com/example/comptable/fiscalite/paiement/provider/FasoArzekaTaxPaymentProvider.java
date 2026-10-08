package com.example.comptable.fiscalite.paiement.provider;

import com.example.comptable.fiscalite.paiement.domain.PaiementFournisseur;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

/**
 * Paiement par porte-monnaie electronique Faso Arzeka (La Poste Burkina Faso), utilise notamment
 * pour les cotisations CNPS et d'autres paiements de proximite en mobile money.
 * <p>
 * Meme situation que {@link EsyntaxeTaxPaymentProvider} : aucun acces a l'API Faso Arzeka a ce
 * jour. Mode simulation par defaut ({@code fasoarzeka.mode=simulation}) - voir la javadoc de
 * classe d'{@link EsyntaxeTaxPaymentProvider} pour la marche a suivre une fois l'API obtenue
 * (meme principe : {@code fasoarzeka.base-url}/{@code fasoarzeka.api-key}, puis
 * {@link #appellerApiReelle}, puis {@code fasoarzeka.mode=reel}).
 */
@Component
public class FasoArzekaTaxPaymentProvider implements TaxPaymentProvider {

    private static final Logger log = LoggerFactory.getLogger(FasoArzekaTaxPaymentProvider.class);

    private final RestClient restClient;
    private final String mode;

    public FasoArzekaTaxPaymentProvider(RestClient.Builder restClientBuilder,
                                         @Value("${fasoarzeka.base-url:}") String baseUrl,
                                         @Value("${fasoarzeka.api-key:}") String apiKey,
                                         @Value("${fasoarzeka.mode:simulation}") String mode) {
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
        return PaiementFournisseur.FASO_ARZEKA;
    }

    @Override
    public TaxPaymentInitiationResult initierPaiement(TaxPaymentRequest requete) {
        if (requete.numeroTelephonePayeur() == null || requete.numeroTelephonePayeur().isBlank()) {
            return TaxPaymentInitiationResult.echec(
                    "Numero de telephone Faso Arzeka du payeur requis pour ce mode de paiement.");
        }
        if (!"reel".equalsIgnoreCase(mode)) {
            String reference = "SIM-FASOARZEKA-" + requete.referenceInterne();
            log.info("[simulation] Paiement Faso Arzeka simule vers {} pour le contribuable {} : reference {}",
                    requete.numeroTelephonePayeur(), requete.numeroContribuable(), reference);
            return TaxPaymentInitiationResult.enAttente(reference,
                    "Simulation - l'API Faso Arzeka n'est pas encore branchee. Un flux reel enverrait ici une "
                            + "demande de validation USSD/PIN au " + requete.numeroTelephonePayeur() + ".");
        }
        return appellerApiReelle(requete);
    }

    /** TODO integration reelle - voir {@link EsyntaxeTaxPaymentProvider#appellerApiReelle} pour le patron a suivre. */
    private TaxPaymentInitiationResult appellerApiReelle(TaxPaymentRequest requete) {
        throw new UnsupportedOperationException(
                "Integration reelle Faso Arzeka non implementee - specifications API a obtenir aupres de La Poste BF. "
                        + "Repassez fasoarzeka.mode=simulation en attendant.");
    }
}
