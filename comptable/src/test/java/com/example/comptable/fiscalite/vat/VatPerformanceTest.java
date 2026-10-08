package com.example.comptable.fiscalite.vat;

import com.example.comptable.cabinet.workspace.CabinetWorkspaceService;
import com.example.comptable.collecte.CollecteTestBase;
import com.example.comptable.comptabilite.domain.CompteComptable;
import com.example.comptable.comptabilite.parametrage.domain.AccountPurpose;
import com.example.comptable.comptabilite.parametrage.service.AccountingAccountMappingService;
import com.example.comptable.comptabilite.parametrage.web.dto.CreerAccountingAccountMappingRequest;
import com.example.comptable.fiscalite.core.TaxFrameworkService;
import com.example.comptable.fiscalite.core.TaxFrameworkService.CreateRequest;
import com.example.comptable.fiscalite.core.VatRules;
import com.example.comptable.fiscalite.vat.VatDtos.OpenRequest;
import com.example.comptable.fiscalite.vat.VatDtos.PeriodView;
import jakarta.persistence.EntityManagerFactory;
import org.hibernate.SessionFactory;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import java.util.stream.Collectors;

import com.example.comptable.fiscalite.vat.VatWorkspaceService.OperationFilters;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Le nombre de requêtes de l'espace TVA ne dépend pas du nombre d'opérations (pas de N+1) : 100 opérations contre 10 000 (5 000 ventes + 5 000 achats insérées en masse). Critère stable : nombre de requêtes ;
 * les temps imprimés sont indicatifs (aucun seuil de durée).
 */
class VatPerformanceTest extends CollecteTestBase {

    @Autowired VatWorkspaceService tva;
    @Autowired TaxFrameworkService cadres;
    @Autowired AccountingAccountMappingService mappingService;
    @Autowired EntityManagerFactory entityManagerFactory;
    @Autowired CabinetWorkspaceService cabinet;
    @MockitoSpyBean JdbcTemplate jdbcSpy;

    private static final LocalDate DEBUT = LocalDate.now().withDayOfMonth(1);
    private static final LocalDate FIN = DEBUT.plusMonths(1).minusDays(1);

    private UUID compte(Dossier d, String numero) {
        return compteComptableRepository.findByEntrepriseIdAndNumero(d.id(), numero).orElseGet(() -> {
            CompteComptable c = new CompteComptable();
            c.setEntreprise(entrepriseRepository.getReferenceById(d.id()));
            c.setNumero(numero);
            c.setIntitule("Compte de test " + numero);
            c.setLettrable(false);
            return compteComptableRepository.save(c);
        }).getId();
    }

    /** Un dossier avec comptes de TVA configurés et un cadre FICTIF vérifié, puis {@code ventes} + {@code achats} écritures validées insérées en masse (TVA à 10 %, cohérente avec le cadre de test). */
    private Dossier dossierAvecOperations(int ventes, int achats) {
        Dossier d = nouveauDossier(false);
        UUID c443 = compte(d, "443900");
        UUID c445 = compte(d, "445900");
        mappingService.creer(d.id(), new CreerAccountingAccountMappingRequest(AccountPurpose.TVA_COLLECTEE, null, c443, null), d.expert());
        mappingService.creer(d.id(), new CreerAccountingAccountMappingRequest(AccountPurpose.TVA_RECUPERABLE, null, c445, null), d.expert());
        var f = cadres.creer(d.expert(), new CreateRequest("CI", "TVA", "TEST_ONLY-BENCH-" + UUID.randomUUID().toString().substring(0, 5), LocalDate.of(2000, 1, 1), null,
                new VatRules(List.of(new VatRules.Rate("TEST_ONLY_T10", "Taux fictif TEST_ONLY", new BigDecimal("10"))), "MONTHLY", null, new BigDecimal("1"), new BigDecimal("0.5"), List.of()), List.of("Source fictive de test")));
        cadres.verifier(d.expert(), f.id());
        UUID exercice = jdbc.queryForObject("select id from exercice_comptable where entreprise_id = ? order by date_fin limit 1", UUID.class, d.id());
        UUID journal = jdbc.queryForObject("select id from journal where entreprise_id = ? order by code limit 1", UUID.class, d.id());
        UUID banque = compte(d, "521900");
        UUID produit = compte(d, "701900");
        UUID charge = compte(d, "601900");
        inserer(d, exercice, journal, banque, produit, c443, ventes, true);
        inserer(d, exercice, journal, banque, charge, c445, achats, false);
        // l'insertion en masse laisse les statistiques du planificateur périmées (l'autovacuum n'a pas encore tourné) : on les rafraîchit, comme en production, pour que les mesures ne dépendent pas d'un mauvais plan
        jdbc.execute("analyze ecriture_comptable");
        jdbc.execute("analyze ligne_ecriture");
        return d;
    }

    private void inserer(Dossier d, UUID exercice, UUID journal, UUID banque, UUID compteBase, UUID compteTva, int n, boolean vente) {
        if (n == 0) {
            return;
        }
        // HT = 1000 + (g mod 97) ; TVA = 10 % ; une écriture et trois lignes par opération
        jdbc.update("""
                with e as (
                    insert into ecriture_comptable (id, entreprise_id, exercice_id, journal_id, numero_piece, date_ecriture, libelle, statut)
                    select gen_random_uuid(), ?, ?, ?, ? || g, ?::date + (g %% 20), 'Opération ' || g, 'VALIDEE' from generate_series(1, ?) g
                    returning id, numero_piece)
                insert into ligne_ecriture (id, ecriture_id, compte_id, libelle, debit, credit, numero_ligne)
                select gen_random_uuid(), e.id, v.compte, 'ligne', v.debit, v.credit, v.n
                from e cross join lateral (
                    select (substring(e.numero_piece from 2)::int %% 97) + 1000 as ht) b
                cross join lateral (values
                    (1, ?::uuid, case when ? then round(b.ht * 1.10, 2) else 0 end, case when ? then 0 else round(b.ht * 1.10, 2) end),
                    (2, ?::uuid, case when ? then 0 else b.ht end, case when ? then b.ht else 0 end),
                    (3, ?::uuid, case when ? then 0 else round(b.ht * 0.10, 2) end, case when ? then round(b.ht * 0.10, 2) else 0 end)) v(n, compte, debit, credit)
                """.formatted().replace("%%", "%"), d.id(), exercice, journal, vente ? "V" : "A", DEBUT, n, banque, vente, vente, compteBase, vente, vente, compteTva, vente, vente);
    }

    private record Mesure(long requetesJdbc, long requetesHibernate, long millis) {
    }

    private Mesure mesurer(Runnable action) {
        SessionFactory sf = entityManagerFactory.unwrap(SessionFactory.class);
        action.run();                                                                             // échauffement
        Mockito.clearInvocations(jdbcSpy);
        sf.getStatistics().clear();
        long debut = System.nanoTime();
        action.run();
        long ms = (System.nanoTime() - debut) / 1_000_000;
        long jdbcN = Mockito.mockingDetails(jdbcSpy).getInvocations().stream()
                .filter(i -> i.getArguments().length > 0 && i.getArguments()[0] instanceof String && (i.getMethod().getName().startsWith("query") || i.getMethod().getName().equals("update"))).count();
        return new Mesure(jdbcN, sf.getStatistics().getPrepareStatementCount(), ms);
    }

    @Test
    void nombreDeRequetes_borne_entre100Et10000Operations() {
        Dossier petit = dossierAvecOperations(50, 50);
        Dossier gros = dossierAvecOperations(5000, 5000);
        PeriodView pp = tva.ouvrir(petit.expert(), petit.id(), new OpenRequest(DEBUT, FIN));
        PeriodView pg = tva.ouvrir(gros.expert(), gros.id(), new OpenRequest(DEBUT, FIN));

        assertThat(pp.totals().operations()).isEqualTo(100);
        assertThat(pg.totals().operations()).isEqualTo(10_000);
        assertThat(pg.reconciliation().ledgerGap()).isEqualByComparingTo("0");
        assertThat(pg.anomalies()).isZero();                                                       // 10 % partout : cohérent avec le cadre de test
        assertThat(pg.totals().outputVatFiscal().signum()).isPositive();

        Mesure detailPetit = mesurer(() -> tva.detail(petit.expert(), petit.id(), pp.id()));
        Mesure detailGros = mesurer(() -> tva.detail(gros.expert(), gros.id(), pg.id()));
        Mesure pagePetit = mesurer(() -> tva.operations(petit.expert(), petit.id(), pp.id(), "OUTPUT", null, 0, 25));
        Mesure pageGros = mesurer(() -> tva.operations(gros.expert(), gros.id(), pg.id(), "OUTPUT", null, 0, 25));
        Mesure recalculPetit = mesurer(() -> tva.recalculer(petit.expert(), petit.id(), pp.id()));
        Mesure recalculGros = mesurer(() -> tva.recalculer(gros.expert(), gros.id(), pg.id()));

        System.out.printf("BENCH-TVA detail    100: %s | 10000: %s%n", detailPetit, detailGros);
        System.out.printf("BENCH-TVA page      100: %s | 10000: %s%n", pagePetit, pageGros);
        System.out.printf("BENCH-TVA recalcul  100: %s | 10000: %s%n", recalculPetit, recalculGros);

        assertThat(detailGros.requetesJdbc()).isEqualTo(detailPetit.requetesJdbc());
        assertThat(detailGros.requetesHibernate()).isEqualTo(detailPetit.requetesHibernate());
        assertThat(pageGros.requetesJdbc()).isEqualTo(pagePetit.requetesJdbc());
        assertThat(recalculGros.requetesJdbc()).isEqualTo(recalculPetit.requetesJdbc());
        assertThat(recalculGros.requetesHibernate()).isEqualTo(recalculPetit.requetesHibernate());
        assertThat(detailGros.requetesJdbc()).isLessThan(60);

        // la page ne renvoie jamais plus que demandé, quel que soit le volume
        assertThat(tva.operations(gros.expert(), gros.id(), pg.id(), "OUTPUT", null, 0, 25).items()).hasSize(25);
        assertThat(tva.operations(gros.expert(), gros.id(), pg.id(), "OUTPUT", null, 0, 25).total()).isEqualTo(5000);
        assertThat(tva.operations(gros.expert(), gros.id(), pg.id(), "INPUT", null, 199, 25).items()).hasSize(25);       // dernière page de 5 000 achats
    }

    private List<String> requetesSql(Runnable action) {
        Mockito.clearInvocations(jdbcSpy);
        action.run();
        return Mockito.mockingDetails(jdbcSpy).getInvocations().stream().filter(i -> i.getArguments().length > 0 && i.getArguments()[0] instanceof String && i.getMethod().getName().startsWith("query"))
                .map(i -> (String) i.getArguments()[0]).collect(Collectors.toList());
    }

    /**
     * G3A : 10 000 puis 50 000 opérations. Mesures : synthèse, page, recherche, filtre TO_REVIEW, recalcul, file de travail du cabinet. Critère stable : le NOMBRE de requêtes ne dépend pas du volume ;
     * la liste est paginée EN SQL (la requête de page porte LIMIT/OFFSET et la requête de chargement complet de la période n'est jamais utilisée pour une page). Les temps sont indicatifs.
     */
    @Test
    void scalabilite_10000_et_50000_operations_pagination_sql_sans_chargement_complet() {
        Dossier moyen = dossierAvecOperations(5000, 5000);
        Dossier grand = dossierAvecOperations(25_000, 25_000);
        PeriodView pm = tva.ouvrir(moyen.expert(), moyen.id(), new OpenRequest(DEBUT, FIN));
        PeriodView pg = tva.ouvrir(grand.expert(), grand.id(), new OpenRequest(DEBUT, FIN));
        assertThat(pm.totals().operations()).isEqualTo(10_000);
        assertThat(pg.totals().operations()).isEqualTo(50_000);
        assertThat(pg.reconciliation().ledgerGap()).isEqualByComparingTo("0");

        OperationFilters aucun = new OperationFilters("SALES", null, null, false, null, null, null, "date", true);
        OperationFilters recherche = new OperationFilters(null, null, null, false, null, null, "V1234", "date", true);
        OperationFilters aRevoir = new OperationFilters(null, "TO_REVIEW", null, false, null, null, null, "date", true);

        String[] noms = {"synthèse", "page 0", "page profonde", "recherche", "filtre TO_REVIEW", "recalcul", "file de travail"};
        Mesure[] m10 = new Mesure[noms.length];
        Mesure[] m50 = new Mesure[noms.length];
        for (int k = 0; k < 2; k++) {
            Dossier d = k == 0 ? moyen : grand;
            UUID p = k == 0 ? pm.id() : pg.id();
            Mesure[] cible = k == 0 ? m10 : m50;
            cible[0] = mesurer(() -> tva.detail(d.expert(), d.id(), p));
            cible[1] = mesurer(() -> tva.operations(d.expert(), d.id(), p, aucun, 0, 50));
            cible[2] = mesurer(() -> tva.operations(d.expert(), d.id(), p, aucun, 99, 50));
            cible[3] = mesurer(() -> tva.operations(d.expert(), d.id(), p, recherche, 0, 50));
            cible[4] = mesurer(() -> tva.operations(d.expert(), d.id(), p, aRevoir, 0, 50));
            cible[5] = mesurer(() -> tva.recalculer(d.expert(), d.id(), p));
            cible[6] = mesurer(() -> cabinet.workQueue(d.expert(), false, 0, 25));
        }
        for (int i = 0; i < noms.length; i++) {
            System.out.printf("BENCH-TVA-G3A %-16s 10000: %s | 50000: %s%n", noms[i], m10[i], m50[i]);
            assertThat(m50[i].requetesJdbc()).as(noms[i] + " : requêtes JDBC indépendantes du volume").isEqualTo(m10[i].requetesJdbc());
            assertThat(m50[i].requetesHibernate()).as(noms[i] + " : requêtes Hibernate indépendantes du volume").isEqualTo(m10[i].requetesHibernate());
        }

        // la page est paginée EN SQL : LIMIT/OFFSET dans la requête, et aucune requête de chargement complet de la période
        List<String> sqlPage = requetesSql(() -> tva.operations(grand.expert(), grand.id(), pg.id(), aucun, 3, 50));
        assertThat(sqlPage.stream().filter(s -> s.contains("limit ? offset ?")).count()).isGreaterThanOrEqualTo(1);       // (le proxy de test enregistre aussi l'appel délégué)
        assertThat(sqlPage).noneMatch(s -> s.contains("order by e.date_ecriture, e.numero_piece nulls last, e.id"));        // ancienne extraction complète de la période
        var page = tva.operations(grand.expert(), grand.id(), pg.id(), aucun, 3, 50);
        assertThat(page.items()).hasSize(50);
        assertThat(page.total()).isEqualTo(25_000);
        assertThat(tva.operations(grand.expert(), grand.id(), pg.id(), recherche, 0, 50).total()).isEqualTo(11);       // V1234, V12340 … V12349
        assertThat(tva.operations(grand.expert(), grand.id(), pg.id(), aRevoir, 0, 50).total()).isZero();
        assertThat(tva.operations(grand.expert(), grand.id(), pg.id(), new OperationFilters("PURCHASES", null, null, false, null, null, null, "amount", false), 499, 50).items()).hasSize(50);
    }
}
