package com.example.comptable.fiscalite.paiement;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.env.Environment;
import org.springframework.core.env.Profiles;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;

/**
 * Le paiement fiscal en ligne n'a, à ce jour, aucune intégration réelle (e-Syntaxe et Faso Arzeka restent en mode « simulation ») : il produit des références fictives. En PRODUCTION, un utilisateur ne
 * doit donc jamais pouvoir « payer » un impôt fictif ni « confirmer à la main » un paiement : ces deux actions y sont refusées. Hors production (développement, test, formation, staging), elles restent
 * disponibles pour tester le parcours.
 */
@Component
public class PaiementEnLigneGarde {

    private final boolean production;
    private final boolean simule;

    public PaiementEnLigneGarde(Environment env, @Value("${esyntaxe.mode:simulation}") String modeEsyntaxe, @Value("${fasoarzeka.mode:simulation}") String modeFasoArzeka) {
        this.production = env.acceptsProfiles(Profiles.of("prod"));
        this.simule = !"reel".equalsIgnoreCase(modeEsyntaxe) || !"reel".equalsIgnoreCase(modeFasoArzeka);
    }

    /** Vrai en production tant qu'un des deux fournisseurs est en simulation : l'interface masque alors le paiement en ligne. */
    public boolean paiementEnLigneIndisponible() {
        return production && simule;
    }

    public boolean production() {
        return production;
    }

    public boolean simule() {
        return simule;
    }

    public void exigerInitiationPossible() {
        if (paiementEnLigneIndisponible()) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Le paiement en ligne n'est pas disponible : aucune intégration réelle avec la DGI ou La Poste n'est activée. Réglez l'impôt auprès de l'administration puis marquez la déclaration comme payée.");
        }
    }

    public void exigerHorsProduction() {
        if (production) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "La confirmation manuelle d'un paiement n'existe pas en production : seule la confirmation du fournisseur fait foi.");
        }
    }
}
