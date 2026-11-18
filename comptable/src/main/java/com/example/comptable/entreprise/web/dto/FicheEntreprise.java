package com.example.comptable.entreprise.web.dto;

import com.example.comptable.entreprise.domain.Entreprise;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

import java.util.ArrayList;
import java.util.List;

/**
 * Fiche légale de l'entreprise : ce qui figure sur ses devis et factures. {@code manquants} liste, en clair, ce qu'il faut encore renseigner pour qu'un document soit présentable
 * (l'IFU, le RCCM, l'adresse) ; il est calculé par le serveur, jamais saisi.
 */
public record FicheEntreprise(
        @NotBlank @Size(max = 200) String raisonSociale,
        @Size(max = 60) String numeroContribuable,
        @Size(max = 60) String rccm,
        @Size(max = 60) String formeJuridique,
        @Size(max = 60) String regimeFiscal,
        @Size(max = 300) String adresse,
        @Size(max = 40) String telephone,
        @Size(max = 150) String email,
        @Size(max = 300) String coordonneesBancaires,
        @Size(max = 500) String mentionsFacture,
        List<String> manquants) {

    public static FicheEntreprise from(Entreprise e) {
        List<String> manquants = new ArrayList<>();
        if (vide(e.getNumeroContribuable())) {
            manquants.add("IFU (numéro d'identification fiscale)");
        }
        if (vide(e.getRccm())) {
            manquants.add("RCCM");
        }
        if (vide(e.getAdresse())) {
            manquants.add("Adresse");
        }
        return new FicheEntreprise(e.getRaisonSociale(), e.getNumeroContribuable(), e.getRccm(), e.getFormeJuridique(), e.getRegimeFiscal(), e.getAdresse(), e.getTelephone(), e.getEmail(),
                e.getCoordonneesBancaires(), e.getMentionsFacture(), manquants);
    }

    private static boolean vide(String s) {
        return s == null || s.isBlank();
    }
}
