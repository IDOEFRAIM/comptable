package com.example.comptable.config;

import com.example.comptable.fiscalite.paiement.PaiementEnLigneGarde;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Réglages d'affichage que l'interface doit connaître AVANT la connexion (lien « Créer un compte ») ou pour masquer des outils de test (paiement fictif). Rien de secret : trois booléens. L'application
 * serveur reste l'autorité (les routes concernées refusent aussi les appels, voir {@link PaiementEnLigneGarde} et le mode d'inscription).
 */
@RestController
@RequestMapping("/api/config")
public class ConfigurationPubliqueController {

    public record ConfigurationPublique(boolean inscriptionOuverte, boolean production, boolean paiementEnLigneIndisponible) {
    }

    private final boolean inscriptionOuverte;
    private final PaiementEnLigneGarde paiementGarde;

    public ConfigurationPubliqueController(@Value("${comptable.registration.mode:OPEN}") String modeInscription, PaiementEnLigneGarde paiementGarde) {
        this.inscriptionOuverte = "OPEN".equalsIgnoreCase(modeInscription);
        this.paiementGarde = paiementGarde;
    }

    @GetMapping
    public ConfigurationPublique configuration() {
        return new ConfigurationPublique(inscriptionOuverte, paiementGarde.production(), paiementGarde.paiementEnLigneIndisponible());
    }
}
