package com.example.comptable.collecte;

import com.example.comptable.identite.domain.Utilisateur;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.crypto.password.PasswordEncoder;

/**
 * OUTIL DE DEVELOPPEMENT (desactive par defaut) : cabinet de demonstration avec un client joignable par WhatsApp et quatre operations
 * sans piece, pour verifier la collecte dans un vrai navigateur. Lancer avec {@code -Dseed.collecte=true -Dtest=CollecteDemoSeedTest}.
 * Identifiants : demo-collecte@test.ci / Demo12345!
 */
@EnabledIfSystemProperty(named = "seed.collecte", matches = "true")
class CollecteDemoSeedTest extends CollecteTestBase {

    @Autowired PasswordEncoder passwordEncoder;

    @Test
    void creerLeJeuDeDemonstration() {
        Dossier d = nouveauDossier(true);
        Utilisateur expert = utilisateurRepository.findById(d.expert().getId()).orElseThrow();
        utilisateurRepository.findByEmail("demo-collecte@test.ci").ifPresent(ancien -> {
            ancien.setEmail("ancien-" + ancien.getId() + "@test.ci");
            utilisateurRepository.save(ancien);
        });
        expert.setEmail("demo-collecte@test.ci");
        expert.setMotDePasse(passwordEncoder.encode("Demo12345!"));
        utilisateurRepository.save(expert);

        for (int montant : new int[] {45000, 120000, 8500, 232000}) {
            acheterSansPiece(d, montant);
        }
        controlRunService.executer(entrepriseRepository.findById(d.id()).orElseThrow());
        System.out.println("SEED-COLLECTE entrepriseId=" + d.id() + " telephoneClient=" + d.telephone());
    }
}
