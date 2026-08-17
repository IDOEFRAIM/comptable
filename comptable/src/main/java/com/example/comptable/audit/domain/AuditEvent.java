package com.example.comptable.audit.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.OffsetDateTime;
import java.util.Map;
import java.util.UUID;

/**
 * Mission P1 "Cabinet Operating System" (section 34-35) : piste d'audit reelle des decisions du
 * systeme. Mappe la table {@code audit_log} deja creee par la migration V1 mais jamais ecrite
 * jusqu'ici (aucune entite/service ne la referencait) - reutilisee telle quelle plutot que de
 * creer une deuxieme table d'audit. La colonne {@code details} porte {@code source} (HUMAIN/IA)
 * et, pour une decision IA, {@code model}/{@code promptVersion}/{@code decision}/
 * {@code userValidation} (section 35) - jamais melanges dans le meme champ qu'une decision
 * humaine.
 */
@Entity
@Table(name = "audit_log")
@Getter
@Setter
@NoArgsConstructor
public class AuditEvent {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "tenant_id")
    private UUID tenantId;

    @Column(name = "utilisateur_id")
    private UUID utilisateurId;

    @Column(nullable = false, length = 50)
    private String action;

    @Column(nullable = false, length = 50)
    private String entite;

    @Column(name = "entite_id", length = 60)
    private String entiteId;

    @JdbcTypeCode(SqlTypes.JSON)
    private Map<String, Object> details;

    @Column(name = "created_at", nullable = false)
    private OffsetDateTime createdAt;

    @PrePersist
    private void onCreate() {
        if (createdAt == null) {
            createdAt = OffsetDateTime.now();
        }
    }
}
