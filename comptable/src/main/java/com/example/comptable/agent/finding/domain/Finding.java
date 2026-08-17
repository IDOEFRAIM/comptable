package com.example.comptable.agent.finding.domain;

import com.example.comptable.common.domain.UuidEntity;
import com.example.comptable.entreprise.domain.Entreprise;
import com.example.comptable.identite.domain.Utilisateur;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.Map;

/**
 * Element generique necessitant l'attention du comptable (mission P0 "Core Intelligent de
 * Production Comptable", sections 7/9/18) : rapprochement ambigu, preuve manquante, anomalie,
 * document a revoir, controle fiscal, lettrage ambigu, ecriture ancienne non validee, blocage de
 * cloture. Fusionne volontairement les concepts "WorkItem" et "AccountingFinding/AiFinding" de la
 * mission en un seul modele - aucune table generique de ce type n'existait dans le schema, et en
 * creer deux aurait duplique la meme plomberie (creation, lecture, resolution) pour rien.
 * <p>
 * Un Finding ne decide jamais rien seul : c'est le moteur qui le cree (MissingEvidenceAnalyzer,
 * ControlEngine...) qui a deja applique ses propres regles Java ; le Finding trace le constat pour
 * l'Inbox et le Dossier Comptable Intelligent, jamais une action automatique.
 */
@Entity
@Table(name = "finding")
@Getter
@Setter
@NoArgsConstructor
public class Finding extends UuidEntity {

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "entreprise_id", nullable = false)
    private Entreprise entreprise;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private TypeFinding type;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private SeveriteFinding severity;

    /** Type de l'objet a l'origine du finding (LIGNE_RELEVE, DOCUMENT, DECLARATION_FISCALE,
     * AI_MATCH_PROPOSAL, ECRITURE_COMPTABLE, COMPTE_COMPTABLE...) - chaine libre volontairement
     * (pas de reference directe a agent.matching.domain.SourceType, qui ne couvre que le
     * vocabulaire du matching) pour ne pas coupler ce modele transversal a un seul moteur. */
    @Column(name = "source_type", nullable = false)
    private String sourceType;

    @Column(name = "source_id", nullable = false)
    private String sourceId;

    @Column(nullable = false)
    private String title;

    private String description;

    /** Null pour un finding purement deterministe (ex: declaration manquante) - la confiance n'a
     * de sens que pour les findings issus d'un signal IA. */
    private Double confidence;

    /** La "PREUVE" (mission section 2) : donnees factuelles qui justifient le finding, affichees
     * telles quelles dans l'ecran de detail (montants, dates, candidats consideres...). */
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "evidence_json")
    private Map<String, Object> evidenceJson;

    @Column(name = "recommended_action")
    private String recommendedAction;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private StatutFinding status = StatutFinding.OPEN;

    @Column(name = "created_at", nullable = false)
    private OffsetDateTime createdAt;

    @Column(name = "resolved_at")
    private OffsetDateTime resolvedAt;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "resolved_by_id")
    private Utilisateur resolvedBy;

    /** Mission P1 "Cabinet Operating System" (section 11-13) : un Finding non resolu EST la
     * tache a traiter, assignee directement plutot que dupliquee dans une table Task separee. */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "assigned_to_id")
    private Utilisateur assignedTo;

    @Column(name = "due_date")
    private LocalDate dueDate;

    /** Mission P1.9 (section 31) : motif libre-choix (pas une table de reference) pour garder le
     * modele simple - alimente le KPI "False Auto-Match"/qualite du rejet sans en faire une
     * nouvelle relation. Jamais rempli automatiquement, uniquement fourni par le comptable qui
     * rejette. */
    @Column(name = "rejection_reason")
    private String rejectionReason;

    @PrePersist
    private void onCreate() {
        if (createdAt == null) {
            createdAt = OffsetDateTime.now();
        }
    }
}
