package com.example.comptable.collecte.service;

import com.example.comptable.identite.domain.Utilisateur;
import com.example.comptable.identite.repository.UtilisateurRepository;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * A qui adresser une demande ? Les interlocuteurs sont les utilisateurs du portail client rattaches a l'entreprise ET ayant un
 * telephone WhatsApp exploitable. Aucun contact est un resultat EXPLICITE ({@code NO_CONTACT}), un numero inexploitable aussi
 * ({@code INVALID_CONTACT}) : on ne masque jamais ces cas et on n'envoie jamais « au hasard ».
 */
@Component
public class ContactResolver {

    public static final String NO_CONTACT = "NO_CONTACT";
    public static final String INVALID_CONTACT = "INVALID_CONTACT";

    public record Contact(UUID utilisateurId, String nom, String telephone) {
    }

    /** {@code motif} est {@code null} quand au moins un contact est utilisable. */
    public record Resolution(List<Contact> contacts, String motif) {
        public boolean utilisable() {
            return motif == null && !contacts.isEmpty();
        }
    }

    private final UtilisateurRepository utilisateurRepository;
    private final CollecteConfig config;

    public ContactResolver(UtilisateurRepository utilisateurRepository, CollecteConfig config) {
        this.utilisateurRepository = utilisateurRepository;
        this.config = config;
    }

    public Resolution resoudre(UUID entrepriseId) {
        List<Utilisateur> interlocuteurs = utilisateurRepository.findInterlocuteursDeLEntreprise(entrepriseId);
        List<Contact> valides = new ArrayList<>();
        boolean aUnNumero = false;
        for (Utilisateur u : interlocuteurs) {
            if (u.getTelephone() == null || u.getTelephone().isBlank()) {
                continue;
            }
            aUnNumero = true;
            String numero = normaliser(u.getTelephone());
            if (numero != null && valides.stream().noneMatch(c -> c.telephone().equals(numero))) {
                valides.add(new Contact(u.getId(), u.getPrenom() + " " + u.getNom(), numero));
            }
        }
        if (valides.isEmpty()) {
            return new Resolution(List.of(), aUnNumero ? INVALID_CONTACT : NO_CONTACT);
        }
        return new Resolution(config.tousLesDestinataires() ? valides : List.of(valides.get(0)), null);
    }

    /** Format E.164 sans « + » : 9 a 15 chiffres, sans zero initial (un numero local sans indicatif pays est refuse par WhatsApp). */
    static String normaliser(String telephone) {
        String chiffres = telephone.replaceAll("[^0-9]", "");
        if (chiffres.startsWith("00")) {
            chiffres = chiffres.substring(2);
        }
        return chiffres.length() >= 9 && chiffres.length() <= 15 && !chiffres.startsWith("0") ? chiffres : null;
    }
}
