package com.example.comptable.config;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Les données de démonstration de la migration V8 (deux cabinets et trois entreprises fictifs, sans aucun utilisateur) existent dans toute base créée par Flyway, production comprise. Cette garde les reconnaît
 * par leurs identifiants fixes et les retire <b>uniquement si elles sont vierges</b> (aucun utilisateur, aucune écriture, aucun document) ; sinon elle refuse de continuer et le dit. Elle ne touche jamais à une autre donnée.
 */
@Component
public class DemoDataGuard {

    private static final Logger LOG = LoggerFactory.getLogger(DemoDataGuard.class);

    static final List<UUID> TENANTS = List.of(UUID.fromString("11111111-1111-1111-1111-111111111111"), UUID.fromString("22222222-2222-2222-2222-222222222222"));
    static final List<UUID> ENTREPRISES = List.of(UUID.fromString("aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa"), UUID.fromString("bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb"),
            UUID.fromString("cccccccc-cccc-cccc-cccc-cccccccccccc"));

    private final JdbcTemplate jdbc;

    public DemoDataGuard(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public record Constat(boolean presentes, boolean vierges) {
    }

    private static String in(List<UUID> ids) {
        return "(" + String.join(",", ids.stream().map(i -> "'" + i + "'").toList()) + ")";
    }

    public Constat constater() {
        long n = nombre("select count(*) from entreprise where id in " + in(ENTREPRISES)) + nombre("select count(*) from tenant where id in " + in(TENANTS));
        if (n == 0) {
            return new Constat(false, true);
        }
        long activite = nombre("select count(*) from utilisateur where tenant_id in " + in(TENANTS))
                + nombre("select count(*) from ecriture_comptable where entreprise_id in " + in(ENTREPRISES))
                + nombre("select count(*) from document where entreprise_id in " + in(ENTREPRISES));
        return new Constat(true, activite == 0);
    }

    /** Retire les données de démonstration vierges. @return le nombre de lignes supprimées, ou -1 si elles ne sont pas vierges (rien n'est alors modifié) */
    public int purgerSiVierges() {
        Constat c = constater();
        if (!c.presentes()) {
            return 0;
        }
        if (!c.vierges()) {
            return -1;
        }
        List<String> tables = jdbc.queryForList("select table_name from information_schema.columns where table_schema = current_schema() and column_name = 'entreprise_id' and table_name <> 'entreprise' order by table_name", String.class);
        int supprimees = 0;
        List<String> restantes = new ArrayList<>(tables);
        for (int passe = 0; passe < 12 && !restantes.isEmpty(); passe++) {            // les clés étrangères imposent un ordre : on réessaie les tables bloquées après les autres
            List<String> bloquees = new ArrayList<>();
            for (String table : restantes) {
                try {
                    supprimees += jdbc.update("delete from \"" + table + "\" where entreprise_id in " + in(ENTREPRISES));
                } catch (org.springframework.dao.DataAccessException e) {
                    bloquees.add(table);
                }
            }
            restantes = bloquees;
        }
        if (!restantes.isEmpty()) {
            throw new IllegalStateException("Purge des données de démonstration impossible (tables liées : " + restantes + ")");
        }
        supprimees += jdbc.update("delete from entreprise where id in " + in(ENTREPRISES));
        supprimees += jdbc.update("delete from tenant where id in " + in(TENANTS));
        LOG.warn("Données de démonstration de la migration V8 retirées ({} lignes) : elles n'ont pas leur place dans une base de production.", supprimees);
        return supprimees;
    }

    private long nombre(String sql) {
        Long n = jdbc.queryForObject(sql, Long.class);
        return n == null ? 0 : n;
    }
}
