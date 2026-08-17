package com.example.comptable.entreprise.domain;

import jakarta.persistence.EmbeddedId;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/** Donne acces a un utilisateur CLIENT sur une entreprise precise (portail client). */
@Entity
@Table(name = "utilisateur_entreprise")
@Getter
@Setter
@NoArgsConstructor
public class UtilisateurEntreprise {

    @EmbeddedId
    private UtilisateurEntrepriseId id;

    public UtilisateurEntreprise(java.util.UUID utilisateurId, java.util.UUID entrepriseId) {
        this.id = new UtilisateurEntrepriseId(utilisateurId, entrepriseId);
    }
}
