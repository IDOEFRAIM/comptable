package com.example.comptable.comptabilite.domain;

import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/** Referentiel global du plan comptable SYSCOHADA revise, partage entre tous les tenants. */
@Entity
@Table(name = "plan_comptable_reference")
@Getter
@Setter
@NoArgsConstructor
public class PlanComptableReference {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Integer id;

    private String numero;

    private String intitule;

    private Short classe;

    @Enumerated(EnumType.STRING)
    private NatureCompte nature;
}
