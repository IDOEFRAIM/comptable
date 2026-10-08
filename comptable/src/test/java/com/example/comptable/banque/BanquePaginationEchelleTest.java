package com.example.comptable.banque;

import com.example.comptable.Benchmark;
import com.example.comptable.banque.BanqueDto.StatutTransaction;
import jakarta.persistence.EntityManagerFactory;
import org.hibernate.SessionFactory;
import org.hibernate.stat.Statistics;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.time.LocalDate;
import java.util.UUID;
import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Pagination, filtres et tri cote serveur de l'ecran Banque, MESURES a 5 000, 20 000 et 50 000 transactions (90 % rapprochees, 10 % a traiter) :
 * duree, nombre de requetes SQL, memoire retenue et taille de la reponse JSON. Les chiffres sont affiches (aucun seuil arbitraire : seules des derives
 * grossieres, comme un balayage de toute la periode pour une page de 25 lignes, font echouer le test).
 */
class BanquePaginationEchelleTest extends BankTestBase {

    @Autowired BankTransactionQueryService queryService;
    @Autowired EntityManagerFactory entityManagerFactory;
    @Autowired tools.jackson.databind.json.JsonMapper json;

    private Statistics statistiques() {
        return entityManagerFactory.unwrap(SessionFactory.class).getStatistics();
    }

    private record Mesure(String nom, long ms, long requetes, long octets, long memoireKo) {
    }

    private Mesure mesurer(String nom, Supplier<Object> action) {
        if (Benchmark.actif()) {
            System.gc();
        }
        long avant = Runtime.getRuntime().totalMemory() - Runtime.getRuntime().freeMemory();
        statistiques().clear();
        long t = System.nanoTime();
        Object resultat = action.get();
        long ms = (System.nanoTime() - t) / 1_000_000;
        long requetes = statistiques().getPrepareStatementCount();
        long apres = Runtime.getRuntime().totalMemory() - Runtime.getRuntime().freeMemory();
        long octets = json.writeValueAsBytes(resultat).length;
        return new Mesure(nom, ms, requetes, octets, Math.max(0, (apres - avant) / 1024));
    }

    private UUID peupler(Dossier d, UUID compte, UUID ligneEcriture, int debut, int n) {
        UUID releve = UUID.randomUUID();
        jdbc.update("insert into releve_bancaire (id, entreprise_id, compte_id, libelle, date_debut, date_fin, solde_debut, solde_fin, created_at) "
                + "values (?, ?, ?, 'Releve echelle', ?, ?, 0, 0, now())", releve, d.id(), compte, LocalDate.now().minusDays(30), LocalDate.now());
        jdbc.update("insert into ligne_releve (id, releve_id, date_operation, libelle, montant, entreprise_id, compte_id, currency, direction, libelle_normalise, "
                + "reference, source, content_fingerprint, duplicate_status, ligne_ecriture_id) select gen_random_uuid(), ?, current_date - (i % 30), "
                + "'VIR FOURNISSEUR ' || i, -(5000000 + i * 7), ?, ?, 'XOF', 'DEBIT', 'VIR FOURNISSEUR ' || i, null, 'CSV', md5(? || i), 'NONE', "
                + "case when i % 10 = 0 then null else ? end from generate_series(?, ?) i", releve, d.id(), compte, d.id().toString(), ligneEcriture, debut + 1, debut + n);
        return releve;
    }

    @Test
    void paginationServeur_mesureeA5000_20000_50000Transactions() throws Exception {
        Dossier d = nouveauDossier(false);
        Reglement modele = reglement(d, 1, LocalDate.now(), "Modele");
        UUID ligneEcriture = jdbc.queryForObject("select id from ligne_ecriture where ecriture_id = ? limit 1", UUID.class, modele.ecritureId());
        int dejaPeuple = 0;
        StringBuilder rapport = new StringBuilder();
        for (int n : Benchmark.actif() ? new int[] {5000, 20000, 50000} : new int[] {500, 1000, 2000}) {
            peupler(d, modele.compteBanqueId(), ligneEcriture, dejaPeuple, n - dejaPeuple);
            dejaPeuple = n;
            Benchmark.preparer(jdbc);
            long total = jdbc.queryForObject("select count(*) from ligne_releve where entreprise_id = ?", Long.class, d.id());
            Mesure[] mesures = {
                    mesurer("page 25 (sans filtre)", () -> queryService.lister(d.id(), null, null, null, null, 0, 25, d.expert())),
                    mesurer("page 25 (tri montant)", () -> queryService.lister(d.id(), null, null, null, null, "montant", false, 0, 25, d.expert())),
                    mesurer("page profonde (derniere)", () -> queryService.lister(d.id(), null, null, null, null, null, true, (int) (total / 25) - 1, 25, d.expert())),
                    mesurer("filtre RAPPROCHEE", () -> queryService.lister(d.id(), null, null, StatutTransaction.RAPPROCHEE, null, 0, 25, d.expert())),
                    mesurer("recherche texte", () -> queryService.lister(d.id(), null, null, null, "fournisseur 4242", 0, 25, d.expert())),
                    mesurer("filtre SANS_CORRESPONDANCE", () -> queryService.lister(d.id(), null, null, StatutTransaction.SANS_CORRESPONDANCE, null, 0, 25, d.expert())),
                    mesurer("synthese", () -> queryService.synthese(d.id(), null, null, d.expert())),
            };
            for (Mesure m : mesures) {
                rapport.append(String.format("PAGINATION-BANQUE n=%d | %-28s | %6d ms | %4d SQL | %8d o JSON | ~%d Ko memoire%n", total, m.nom(), m.ms(), m.requetes(),
                        m.octets(), m.memoireKo()));
            }
            // Une page de 25 lignes reste petite et ne depend pas du volume de l'historique rapproche.
            assertThat(mesures[0].octets()).isLessThan(60_000);
            assertThat(mesures[0].requetes()).isLessThan(40);
            assertThat(mesures[3].requetes()).isLessThan(40);
            var synthese = queryService.synthese(d.id(), null, null, d.expert());
            assertThat(synthese.transactions()).isEqualTo(total);
            assertThat(synthese.rapprochees()).isEqualTo(total - total / 10);
        }
        System.out.print(rapport);
    }
}
