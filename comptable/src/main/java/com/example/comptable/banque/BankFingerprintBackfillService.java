package com.example.comptable.banque;

import com.example.comptable.comptabilite.domain.LigneReleve;
import com.example.comptable.comptabilite.repository.LigneReleveRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Reprise de l'identite des transactions bancaires anterieures a la Phase D (sans empreinte). Une empreinte n'est posee que si les donnees la
 * rendent LEGITIME : jamais une valeur arbitraire, jamais le montant seul.
 *
 * <ul>
 *   <li><b>insuffisante</b> : libelle vide (date + montant + sens ne suffisent pas a identifier une operation) : aucune empreinte, rapportee ;</li>
 *   <li><b>collision</b> : la meme empreinte existe deja sur une AUTRE transaction (typiquement le meme fichier importe deux fois avec l'ancien code, dans
 *       deux releves) : rien n'est supprime ni modifie, la seconde garde sa place dans l'historique sans empreinte, et la collision est rapportee ;</li>
 *   <li><b>calculable</b> : empreinte posee. Le rang d'occurrence distingue deux lignes identiques d'un MEME releve (comme a l'import).</li>
 * </ul>
 *
 * <p>Idempotent : relancer ne traite que les lignes encore sans empreinte. Chaque execution consigne son rapport dans {@code migration_report}.</p>
 */
@Service
public class BankFingerprintBackfillService {

    private static final Logger log = LoggerFactory.getLogger(BankFingerprintBackfillService.class);

    public record Rapport(long total, long calculables, long posees, long collisions, long insuffisantes, boolean simulation,
                          List<Map<String, Object>> detailCollisions, List<Map<String, Object>> detailInsuffisantes) {
    }

    private final LigneReleveRepository ligneReleveRepository;
    private final JdbcTemplate jdbc;

    public BankFingerprintBackfillService(LigneReleveRepository ligneReleveRepository, JdbcTemplate jdbc) {
        this.ligneReleveRepository = ligneReleveRepository;
        this.jdbc = jdbc;
    }

    /** Evalue sans rien ecrire : ce qu'une execution ferait. */
    @Transactional(readOnly = true)
    public Rapport simuler() {
        return traiter(true);
    }

    /** Pose les empreintes legitimes et consigne le rapport. */
    @Transactional
    public Rapport appliquer() {
        Rapport rapport = traiter(false);
        consigner(rapport);
        log.info("Reprise des empreintes bancaires : {} lignes, {} posees, {} collisions, {} insuffisantes", rapport.total(), rapport.posees(),
                rapport.collisions(), rapport.insuffisantes());
        return rapport;
    }

    private Rapport traiter(boolean simulation) {
        List<LigneReleve> anciennes = ligneReleveRepository.sansEmpreinte();
        Map<String, UUID> empreintesPrises = new HashMap<>();
        for (Object[] ligne : ligneReleveRepository.empreintesExistantes()) {
            empreintesPrises.put(ligne[0] + "|" + ligne[1] + "|" + ligne[2], (UUID) ligne[3]);
        }
        Map<String, Integer> rangs = new HashMap<>();
        long calculables = 0;
        long posees = 0;
        List<Map<String, Object>> collisions = new ArrayList<>();
        List<Map<String, Object>> insuffisantes = new ArrayList<>();
        for (LigneReleve l : anciennes) {
            String normalise = BankLabelNormalizer.normaliser(l.getLibelle());
            if (normalise.isBlank() && (l.getReference() == null || l.getReference().isBlank())) {
                insuffisantes.add(detail(l, "Libelle vide : la date et le montant ne suffisent pas a identifier une operation"));
                continue;
            }
            calculables++;
            // Le rang d'occurrence ne distingue que des lignes identiques d'un MEME releve ; d'un releve a l'autre ce sont des doublons (collision).
            String cleRang = l.getReleve().getId() + "|" + l.getDateOperation() + "|" + l.getMontant().abs().setScale(2) + "|" + (l.getMontant().signum() >= 0)
                    + "|" + l.getReference() + "|" + normalise;
            int rang = rangs.merge(cleRang, 1, Integer::sum);
            String empreinte = BankFingerprint.calculer(l.getCompteId(), l.getDateOperation(), l.getMontant(), l.getReference(), normalise, rang);
            String cle = l.getEntrepriseId() + "|" + l.getCompteId() + "|" + empreinte;
            UUID autre = empreintesPrises.get(cle);
            if (autre != null && !autre.equals(l.getId())) {
                Map<String, Object> d = detail(l, "Meme empreinte qu'une autre transaction deja identifiee : probable doublon d'un ancien import");
                d.put("autreTransactionId", autre.toString());
                collisions.add(d);
                continue;
            }
            empreintesPrises.put(cle, l.getId());
            if (!simulation) {
                l.setContentFingerprint(empreinte);
                l.setLibelleNormalise(normalise);
                posees++;
            }
        }
        if (!simulation) {
            ligneReleveRepository.saveAll(anciennes.stream().filter(l -> l.getContentFingerprint() != null).toList());
        }
        return new Rapport(anciennes.size(), calculables, posees, collisions.size(), insuffisantes.size(), simulation, collisions, insuffisantes);
    }

    private static Map<String, Object> detail(LigneReleve l, String raison) {
        Map<String, Object> d = new LinkedHashMap<>();
        d.put("transactionId", l.getId().toString());
        d.put("releveId", l.getReleve().getId().toString());
        d.put("date", l.getDateOperation().toString());
        d.put("montant", l.getMontant());
        d.put("raison", raison);
        return d;
    }

    private void consigner(Rapport r) {
        Map<String, Object> synthese = new LinkedHashMap<>();
        synthese.put("total", r.total());
        synthese.put("calculables", r.calculables());
        synthese.put("posees", r.posees());
        synthese.put("collisions", r.collisions());
        synthese.put("insuffisantes", r.insuffisantes());
        jdbc.update("insert into migration_report (code, entity_type, details) values ('BANK_FINGERPRINT_BACKFILL', 'RAPPORT', ?::jsonb)", toJson(synthese));
        for (Map<String, Object> d : r.detailCollisions()) {
            jdbc.update("insert into migration_report (code, entity_type, entity_id, details) values ('BANK_FINGERPRINT_COLLISION', 'LIGNE_RELEVE', ?::uuid, ?::jsonb)",
                    d.get("transactionId"), toJson(d));
        }
        for (Map<String, Object> d : r.detailInsuffisantes()) {
            jdbc.update("insert into migration_report (code, entity_type, entity_id, details) values ('BANK_FINGERPRINT_INSUFFISANT', 'LIGNE_RELEVE', ?::uuid, ?::jsonb)",
                    d.get("transactionId"), toJson(d));
        }
    }

    private static String toJson(Map<String, Object> m) {
        StringBuilder sb = new StringBuilder("{");
        boolean premier = true;
        for (Map.Entry<String, Object> e : m.entrySet()) {
            if (!premier) {
                sb.append(',');
            }
            premier = false;
            sb.append('"').append(e.getKey()).append("\":");
            Object v = e.getValue();
            if (v instanceof Number || v instanceof BigDecimal || v instanceof Boolean) {
                sb.append(v);
            } else {
                sb.append('"').append(String.valueOf(v).replace("\\", "\\\\").replace("\"", "\\\"")).append('"');
            }
        }
        return sb.append('}').toString();
    }
}
