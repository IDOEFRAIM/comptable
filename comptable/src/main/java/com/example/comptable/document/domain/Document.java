package com.example.comptable.document.domain;

import com.example.comptable.common.domain.UuidEntity;
import com.example.comptable.entreprise.domain.Entreprise;
import com.example.comptable.identite.domain.Tenant;
import com.example.comptable.identite.domain.Utilisateur;
import jakarta.persistence.Column;
import jakarta.persistence.Enumerated;
import jakarta.persistence.EnumType;
import jakarta.persistence.Entity;
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

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.Map;
import java.util.UUID;

@Entity
@Table(name = "document")
@Getter
@Setter
@NoArgsConstructor
public class Document extends UuidEntity {

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "tenant_id", nullable = false)
    private Tenant tenant;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "entreprise_id", nullable = false)
    private Entreprise entreprise;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "depose_par_id")
    private Utilisateur deposePar;

    @Enumerated(EnumType.STRING)
    @Column(name = "type_document", nullable = false)
    private TypeDocument typeDocument;

    @Enumerated(EnumType.STRING)
    @Column(name = "canal_source", nullable = false)
    private CanalSource canalSource;

    @Column(name = "nom_fichier_original")
    private String nomFichierOriginal;

    @Column(name = "content_type")
    private String contentType;

    @Column(name = "taille_octets")
    private Long tailleOctets;

    @Column(name = "chemin_fichier", nullable = false)
    private String cheminFichier;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private StatutDocument statut = StatutDocument.EN_ATTENTE;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "donnees_ocr")
    private Map<String, Object> donneesOcr;

    /** Verdict de {@code agent.fraude.AnalyseFraudeService} (Sprint 7), serialise tel quel
     * (miroir JSON d'un {@code RapportFraude}). Meme convention que {@link #donneesOcr} : une
     * Map generique plutot que le type agent.fraude directement, pour ne pas faire dependre le
     * module document du module agent. */
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "rapport_fraude")
    private Map<String, Object> rapportFraude;

    @Column(name = "montant_ht")
    private BigDecimal montantHt;

    @Column(name = "montant_tva")
    private BigDecimal montantTva;

    @Column(name = "montant_ttc")
    private BigDecimal montantTtc;

    @Column(name = "date_piece")
    private LocalDate datePiece;

    /**
     * Simple UUID (pas de relation JPA) : l'entite EcritureComptable n'existe pas encore
     * dans le code, seule la contrainte FK existe en base. A relier au module comptabilite.
     */
    @Column(name = "ecriture_id")
    private UUID ecritureId;

    @Column(name = "created_at", nullable = false)
    private OffsetDateTime createdAt;

    @PrePersist
    private void onCreate() {
        if (createdAt == null) {
            createdAt = OffsetDateTime.now();
        }
    }

    /** Score de confiance du moteur d'extraction (0.0 si absent - moteurs anterieurs au VLM,
     * Sprint 1 - ou en cas d'echec). Cle libre dans {@link #donneesOcr}, cf. DocumentService. */
    public double confianceExtraction() {
        if (donneesOcr == null) {
            return 0.0;
        }
        Object confiance = donneesOcr.get("confiance");
        return (confiance instanceof Number nombre) ? nombre.doubleValue() : 0.0;
    }

    /** true si {@link #rapportFraude} porte un score de risque CRITIQUE. Lecture textuelle
     * simple de la Map JSON (cf. {@link #rapportFraude}) plutot qu'une deserialisation typee :
     * evite d'introduire une dependance du module document vers agent.fraude pour une seule
     * lecture booleenne. */
    public boolean fraudeCritique() {
        return rapportFraude != null && "CRITIQUE".equals(rapportFraude.get("scoreRisque"));
    }
}
