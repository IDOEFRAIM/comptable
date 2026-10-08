package com.example.comptable.banque;

import com.example.comptable.identite.domain.Utilisateur;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.crypto.password.PasswordEncoder;

import java.time.LocalDate;

/**
 * OUTIL DE DEVELOPPEMENT (desactive par defaut) : cabinet de demonstration pour verifier l'ecran Banque dans un vrai navigateur. Cree la
 * comptabilite (reglements fournisseur et factures d'achat) ; le releve CSV est importe depuis l'interface. Lancer avec
 * {@code -Dseed.banque=true -Dtest=BanqueDemoSeedTest}. Identifiants : demo-banque@test.ci / Demo12345!
 */
@EnabledIfSystemProperty(named = "seed.banque", matches = "true")
class BanqueDemoSeedTest extends BankTestBase {

    @Autowired PasswordEncoder passwordEncoder;

    @Test
    void creerLeJeuDeDemonstration() {
        Dossier d = nouveauDossier(false);
        Utilisateur expert = utilisateurRepository.findById(d.expert().getId()).orElseThrow();
        utilisateurRepository.findByEmail("demo-banque@test.ci").ifPresent(ancien -> {
            ancien.setEmail("ancien-" + ancien.getId() + "@test.ci");
            utilisateurRepository.save(ancien);
        });
        expert.setEmail("demo-banque@test.ci");
        expert.setMotDePasse(passwordEncoder.encode("Demo12345!"));
        utilisateurRepository.save(expert);

        LocalDate aujourdhui = LocalDate.now();
        Reglement exact = reglement(d, 1000, aujourdhui, "Reglement Station XYZ");
        reglement(d, 2000, aujourdhui, "Reglement Station XYZ acompte");
        reglement(d, 3000, aujourdhui, "Reglement lot A");
        reglement(d, 3000, aujourdhui, "Reglement lot B");
        factureAchat(d, 7000);
        reglement(d, 2500, aujourdhui, "Acompte facture");
        reglement(d, 4500, aujourdhui, "Solde facture");
        factureAchat(d, 1500);
        reglement(d, 1500, aujourdhui, "Reglement facture certaine");
        controlRunService.executer(entrepriseRepository.findById(d.id()).orElseThrow());
        System.out.println("SEED-BANQUE entrepriseId=" + d.id() + " compteBanque=" + exact.compteBanqueId());
    }
}
