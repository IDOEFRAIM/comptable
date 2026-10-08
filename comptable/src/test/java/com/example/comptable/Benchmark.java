package com.example.comptable;

import org.springframework.jdbc.core.JdbcTemplate;

/**
 * Separation « test fonctionnel » / « benchmark de performance ». Par defaut, les tests de volume tournent a une echelle REDUITE et ne verifient que des invariants
 * deterministes (resultats, nombre de requetes SQL, taille de reponse, absence de N+1) : aucun test metier ne devient rouge parce que le planificateur PostgreSQL local a
 * des statistiques perimees ou que la machine est chargee. Les echelles et les bornes de DUREE ne s'appliquent qu'avec {@code -Dbenchmark=true} :
 *
 * <pre>
 *   ./mvnw test -Dtest='BanqueVolumeTest,BanquePaginationEchelleTest,RevisionVolumeTest' -Dbenchmark=true
 * </pre>
 *
 * Preparation d'un benchmark : {@link #preparer} lance {@code ANALYZE} apres chaque generation massive de donnees ; sur une base de developpement ancienne et volumineuse,
 * lancer aussi {@code VACUUM ANALYZE} une fois avant (voir docs/benchmarks.md).
 */
public final class Benchmark {

    private Benchmark() {
    }

    public static boolean actif() {
        return Boolean.getBoolean("benchmark");
    }

    /** Taille de benchmark si actif, sinon taille fonctionnelle reduite. */
    public static int taille(int benchmark, int fonctionnel) {
        return actif() ? benchmark : fonctionnel;
    }

    /** Statistiques du planificateur a jour apres une generation massive (condition d'un benchmark reproductible). */
    public static void preparer(JdbcTemplate jdbc) {
        jdbc.execute("ANALYZE");
    }
}
