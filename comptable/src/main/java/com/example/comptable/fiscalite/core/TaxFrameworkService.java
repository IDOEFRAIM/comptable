package com.example.comptable.fiscalite.core;

import com.example.comptable.audit.AuditTrailService;
import com.example.comptable.etatsfinanciers.dossier.DossierRoles;
import com.example.comptable.security.UtilisateurPrincipal;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * Cadres fiscaux versionnés. Un cadre est créé DRAFT avec ses règles et ses références de source ; il ne devient VERIFIED que par une décision explicite d'un expert-comptable, qui exige au moins une
 * source citée. Les règles d'un cadre ne sont jamais modifiées : une correction est une nouvelle version (le hash de définition fige le contenu). Aucun cadre n'est semé par l'application.
 *
 * <p>Résolution : pour une juridiction, un type d'impôt et une date, le cadre VERIFIED du cabinet prime sur celui de la plateforme ; à défaut de cadre vérifié, le dernier DRAFT applicable est retourné
 * avec {@code verifie=false} (affichable, jamais utilisable pour une déclaration FINAL).</p>
 */
@Service
public class TaxFrameworkService {

    public static final String TVA = "TVA";

    private static final ObjectMapper MAPPER = new ObjectMapper().configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, true)
            .configure(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS, true);

    public record TaxFramework(UUID id, UUID tenantId, String jurisdiction, String taxType, String version, LocalDate effectiveFrom, LocalDate effectiveTo, String status, VatRules rules,
                               List<String> sourceReferences, String definitionHash, OffsetDateTime verifiedAt) {
        public boolean verifie() {
            return "VERIFIED".equals(status);
        }
    }

    public record Resolution(TaxFramework framework, boolean verifie) {
    }

    public record CreateRequest(String jurisdiction, String taxType, String version, LocalDate effectiveFrom, LocalDate effectiveTo, VatRules rules, List<String> sourceReferences) {
    }

    private final JdbcTemplate jdbc;
    private final AuditTrailService audit;

    public TaxFrameworkService(JdbcTemplate jdbc, AuditTrailService audit) {
        this.jdbc = jdbc;
        this.audit = audit;
    }

    @Transactional(readOnly = true)
    public List<TaxFramework> lister(UtilisateurPrincipal principal) {
        exigerCabinet(principal);
        return jdbc.query(SELECT + " where (tenant_id = ? or tenant_id is null) order by jurisdiction, tax_type, effective_from desc, version desc", (rs, i) -> lire(rs), principal.getTenantId());
    }

    @Transactional
    public TaxFramework creer(UtilisateurPrincipal principal, CreateRequest req) {
        exigerExpert(principal);
        String juridiction = req.jurisdiction() == null ? "" : req.jurisdiction().trim().toUpperCase();
        if (juridiction.length() != 2) {
            throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY, "La juridiction est un code pays à deux lettres");
        }
        String type = req.taxType() == null || req.taxType().isBlank() ? TVA : req.taxType().trim().toUpperCase();
        if (!TVA.equals(type)) {
            throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY, "Seule la TVA est supportée par cette version");
        }
        if (req.version() == null || req.version().isBlank() || req.effectiveFrom() == null) {
            throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY, "La version et la date d'entrée en vigueur sont obligatoires");
        }
        if (req.effectiveTo() != null && req.effectiveTo().isBefore(req.effectiveFrom())) {
            throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY, "La fin de validité précède son début");
        }
        validerRegles(req.rules());
        Integer existe = jdbc.queryForObject("select count(*) from tax_framework where tenant_id = ? and jurisdiction = ? and tax_type = ? and version = ?", Integer.class,
                principal.getTenantId(), juridiction, type, req.version().trim());
        if (existe != null && existe > 0) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Cette version existe déjà : une correction est une nouvelle version");
        }
        String regles = json(req.rules());
        List<String> sources = req.sourceReferences() == null ? List.of() : req.sourceReferences().stream().filter(s -> s != null && !s.isBlank()).map(String::trim).toList();
        UUID id = UUID.randomUUID();
        String hash = hash(juridiction, type, req.version().trim(), req.effectiveFrom(), req.effectiveTo(), regles);
        jdbc.update("""
                insert into tax_framework (id, tenant_id, jurisdiction, tax_type, version, effective_from, effective_to, status, rules, source_references, definition_hash, created_by, created_at)
                values (?, ?, ?, ?, ?, ?, ?, 'DRAFT', ?::jsonb, ?::jsonb, ?, ?, now())
                """, id, principal.getTenantId(), juridiction, type, req.version().trim(), req.effectiveFrom(), req.effectiveTo(), regles, json(sources), hash, principal.getId());
        audit.enregistrerActionHumaine(principal.getTenantId(), principal.getId(), "CADRE_FISCAL_CREE", "CADRE_FISCAL", id.toString(), Map.of("juridiction", juridiction, "type", type, "version", req.version().trim()));
        return charger(id);
    }

    /** DRAFT → VERIFIED : décision humaine tracée, au moins une source citée obligatoire. */
    @Transactional
    public TaxFramework verifier(UtilisateurPrincipal principal, UUID id) {
        exigerExpert(principal);
        TaxFramework f = chargerDuCabinet(principal, id);
        if (!"DRAFT".equals(f.status())) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Seul un cadre DRAFT peut être vérifié");
        }
        if (f.sourceReferences().isEmpty()) {
            throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY, "Un cadre fiscal ne peut être vérifié sans au moins une référence de source");
        }
        jdbc.update("update tax_framework set status = 'VERIFIED', verified_by = ?, verified_at = now() where id = ?", principal.getId(), id);
        audit.enregistrerActionHumaine(principal.getTenantId(), principal.getId(), "CADRE_FISCAL_VERIFIE", "CADRE_FISCAL", id.toString(), Map.of("version", f.version(), "hash", f.definitionHash()));
        return charger(id);
    }

    @Transactional
    public TaxFramework retirer(UtilisateurPrincipal principal, UUID id) {
        exigerExpert(principal);
        TaxFramework f = chargerDuCabinet(principal, id);
        if ("RETIRED".equals(f.status())) {
            return f;
        }
        jdbc.update("update tax_framework set status = 'RETIRED' where id = ?", id);
        audit.enregistrerActionHumaine(principal.getTenantId(), principal.getId(), "CADRE_FISCAL_RETIRE", "CADRE_FISCAL", id.toString(), Map.of("version", f.version()));
        return charger(id);
    }

    /** Cadre applicable à une date : VERIFIED d'abord (cabinet avant plateforme, le plus récent), sinon le dernier DRAFT ; jamais un cadre RETIRED. */
    @Transactional(readOnly = true)
    public Optional<Resolution> resoudre(UUID tenantId, String juridiction, String type, LocalDate date) {
        List<TaxFramework> candidats = jdbc.query(SELECT + """
                 where (tenant_id = ? or tenant_id is null) and jurisdiction = ? and tax_type = ? and status in ('VERIFIED', 'DRAFT')
                   and effective_from <= ? and (effective_to is null or effective_to >= ?)
                 order by (status = 'VERIFIED') desc, (tenant_id is not null) desc, effective_from desc, version desc
                """, (rs, i) -> lire(rs), tenantId, juridiction, type, date, date);
        return candidats.stream().findFirst().map(f -> new Resolution(f, f.verifie()));
    }

    @Transactional(readOnly = true)
    public Optional<TaxFramework> parId(UUID id) {
        List<TaxFramework> l = jdbc.query(SELECT + " where id = ?", (rs, i) -> lire(rs), id);
        return l.stream().findFirst();
    }

    // ------------------------------------------------------------------------------------------------------------------------------------------------------------ internes

    private static final String SELECT = "select id, tenant_id, jurisdiction, tax_type, version, effective_from, effective_to, status, rules::text rules, source_references::text sources, definition_hash, verified_at from tax_framework";

    private TaxFramework charger(UUID id) {
        return parId(id).orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Cadre fiscal introuvable"));
    }

    private TaxFramework chargerDuCabinet(UtilisateurPrincipal principal, UUID id) {
        return parId(id).filter(f -> principal.getTenantId().equals(f.tenantId())).orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Cadre fiscal introuvable"));
    }

    private TaxFramework lire(java.sql.ResultSet rs) throws java.sql.SQLException {
        try {
            VatRules regles = MAPPER.readValue(rs.getString("rules"), VatRules.class);
            List<String> sources = MAPPER.readValue(rs.getString("sources"), MAPPER.getTypeFactory().constructCollectionType(List.class, String.class));
            java.sql.Timestamp v = rs.getTimestamp("verified_at");
            return new TaxFramework(rs.getObject("id", UUID.class), rs.getObject("tenant_id", UUID.class), rs.getString("jurisdiction"), rs.getString("tax_type"), rs.getString("version"),
                    rs.getObject("effective_from", LocalDate.class), rs.getObject("effective_to", LocalDate.class), rs.getString("status"), regles, sources, rs.getString("definition_hash"),
                    v == null ? null : v.toInstant().atOffset(ZoneOffset.UTC));
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Règles du cadre fiscal illisibles : " + e.getOriginalMessage(), e);
        }
    }

    private static void validerRegles(VatRules r) {
        if (r == null || r.rates().isEmpty()) {
            throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY, "Un cadre TVA doit déclarer au moins un taux (issu de la source citée)");
        }
        for (VatRules.Rate t : r.rates()) {
            if (t.code() == null || t.code().isBlank() || t.percent() == null || t.percent().signum() < 0 || t.percent().compareTo(new BigDecimal("100")) > 0) {
                throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY, "Chaque taux a un code et un pourcentage entre 0 et 100");
            }
        }
        if (r.toleranceAmount().signum() < 0 || r.rateMatchTolerancePoints().signum() < 0) {
            throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY, "Les tolérances ne peuvent pas être négatives");
        }
        if (r.due() != null && (r.due().monthsAfterPeriodEnd() < 0 || r.due().dayOfMonth() < 1 || r.due().dayOfMonth() > 31)) {
            throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY, "Règle d'échéance invalide");
        }
    }

    private static String json(Object o) {
        try {
            return MAPPER.writeValueAsString(o);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException(e);
        }
    }

    private static String hash(String juridiction, String type, String version, LocalDate from, LocalDate to, String regles) {
        try {
            String contenu = String.join("|", juridiction, type, version, String.valueOf(from), String.valueOf(to), regles);
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(contenu.getBytes(StandardCharsets.UTF_8)));
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    private static void exigerCabinet(UtilisateurPrincipal p) {
        if (!DossierRoles.peutPreparer(p)) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Réservé aux collaborateurs et experts-comptables du cabinet");
        }
    }

    private static void exigerExpert(UtilisateurPrincipal p) {
        if (!DossierRoles.peutRevoir(p)) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "La gestion des cadres fiscaux est réservée à l'expert-comptable");
        }
    }
}
