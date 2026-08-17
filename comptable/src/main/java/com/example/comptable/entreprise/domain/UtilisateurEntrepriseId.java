package com.example.comptable.entreprise.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Embeddable;
import lombok.AllArgsConstructor;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.io.Serializable;
import java.util.UUID;

@Embeddable
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@EqualsAndHashCode
public class UtilisateurEntrepriseId implements Serializable {

    @Column(name = "utilisateur_id")
    private UUID utilisateurId;

    @Column(name = "entreprise_id")
    private UUID entrepriseId;
}
