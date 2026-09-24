package com.example.comptable.comptabilite.parametrage.domain;

import com.example.comptable.comptabilite.domain.CompteComptable;
import com.example.comptable.common.domain.UuidEntity;
import com.example.comptable.entreprise.domain.Entreprise;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * Parametrage comptable d'une entreprise : associe un {@link AccountPurpose} (+ un contexte
 * optionnel, ex. "ESPECES") a un compte du PCE de cette entreprise. {@code entrepriseId} est
 * toujours obligatoire (ManyToOne non-nullable) - le parametrage d'une entreprise n'est jamais
 * visible ni modifiable depuis une autre (mission Etape 6, section 6). {@code priorite} est un
 * champ reserve/informationnel : deux mappings actifs pour le meme (purpose, contextKey) sont
 * rendus impossibles par les index uniques partiels de la migration V47, jamais departages par ce
 * champ - departager arbitrairement serait exactement ce que la mission interdit.
 */
@Entity
@Table(name = "accounting_account_mapping")
@Getter
@Setter
@NoArgsConstructor
public class AccountingAccountMapping extends UuidEntity {

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "entreprise_id", nullable = false)
    private Entreprise entreprise;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private AccountPurpose purpose;

    @Column(name = "context_key")
    private String contextKey;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "compte_id", nullable = false)
    private CompteComptable compte;

    @Column(nullable = false)
    private boolean actif = true;

    @Column(nullable = false)
    private int priorite = 0;

    @Column(name = "created_at", nullable = false)
    private OffsetDateTime createdAt;

    @Column(name = "updated_at")
    private OffsetDateTime updatedAt;

    @Column(name = "created_by")
    private UUID createdBy;

    @PrePersist
    private void onCreate() {
        if (createdAt == null) {
            createdAt = OffsetDateTime.now();
        }
    }

    @PreUpdate
    private void onUpdate() {
        updatedAt = OffsetDateTime.now();
    }
}
