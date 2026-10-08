package com.example.comptable.banque;

import com.example.comptable.common.domain.UuidEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Un import de releve, retracable : qui, quand, quel fichier (empreinte), combien de lignes lues / valides / rejetees / deja connues /
 * effectivement importees. Un import qui n'apporte rien de nouveau est tout de meme trace ({@code NOTHING_NEW}).
 */
@Entity
@Table(name = "bank_import_batch")
@Getter
@Setter
@NoArgsConstructor
public class BankImportBatch extends UuidEntity {

    public static final String IMPORTED = "IMPORTED";
    public static final String NOTHING_NEW = "NOTHING_NEW";

    @Column(name = "entreprise_id", nullable = false)
    private UUID entrepriseId;

    @Column(name = "compte_id", nullable = false)
    private UUID compteId;

    @Column(name = "file_name")
    private String fileName;

    @Column(name = "file_hash", nullable = false)
    private String fileHash;

    @Column(name = "imported_by")
    private UUID importedBy;

    @Column(name = "imported_at", nullable = false)
    private OffsetDateTime importedAt;

    @Column(name = "total_rows", nullable = false)
    private int totalRows;

    @Column(name = "valid_rows", nullable = false)
    private int validRows;

    @Column(name = "rejected_rows", nullable = false)
    private int rejectedRows;

    @Column(name = "exact_duplicates", nullable = false)
    private int exactDuplicates;

    @Column(name = "possible_duplicates", nullable = false)
    private int possibleDuplicates;

    @Column(name = "imported_rows", nullable = false)
    private int importedRows;

    @Column(nullable = false)
    private String currency = "XOF";

    @Column(name = "date_min")
    private LocalDate dateMin;

    @Column(name = "date_max")
    private LocalDate dateMax;

    @Column(nullable = false)
    private String status;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "rejections_json")
    private List<Map<String, Object>> rejections;

    @PrePersist
    private void onCreate() {
        if (importedAt == null) {
            importedAt = OffsetDateTime.now();
        }
    }
}
