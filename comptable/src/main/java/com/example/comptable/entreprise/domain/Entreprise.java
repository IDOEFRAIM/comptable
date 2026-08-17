package com.example.comptable.entreprise.domain;

import com.example.comptable.common.domain.UuidEntity;
import com.example.comptable.identite.domain.Tenant;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.OffsetDateTime;

@Entity
@Table(name = "entreprise")
@Getter
@Setter
@NoArgsConstructor
public class Entreprise extends UuidEntity {

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "tenant_id", nullable = false)
    private Tenant tenant;

    @Column(name = "raison_sociale", nullable = false)
    private String raisonSociale;

    @Column(name = "numero_contribuable")
    private String numeroContribuable;

    private String rccm;

    @Column(name = "forme_juridique")
    private String formeJuridique;

    @Column(name = "regime_fiscal")
    private String regimeFiscal;

    @Column(name = "secteur_activite")
    private String secteurActivite;

    @Column(nullable = false, length = 2)
    private String pays;

    @Column(nullable = false, length = 3)
    private String devise = "XOF";

    @Column(nullable = false)
    private String statut = "ACTIF";

    @Column(name = "created_at", nullable = false)
    private OffsetDateTime createdAt;

    @PrePersist
    private void onCreate() {
        if (createdAt == null) {
            createdAt = OffsetDateTime.now();
        }
    }
}
