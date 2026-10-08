package com.example.comptable.cabinet.workspace;

import com.example.comptable.cabinet.workspace.CabinetWorkspaceDtos.DashboardDto;
import com.example.comptable.cabinet.workspace.CabinetWorkspaceDtos.DossierSummaryDto;
import com.example.comptable.cabinet.workspace.CabinetWorkspaceDtos.PageDto;
import com.example.comptable.cabinet.workspace.CabinetWorkspaceService.DossierQuery;
import com.example.comptable.cabinet.workspace.CabinetWorkspaceService.Filtre;
import com.example.comptable.cabinet.workspace.CabinetWorkspaceService.Tri;
import com.example.comptable.collecte.CollecteTestBase;
import com.example.comptable.collecte.service.CollecteService;
import com.example.comptable.entreprise.web.dto.CreerEntrepriseRequest;
import com.example.comptable.entreprise.web.dto.EntrepriseDto;
import com.example.comptable.comptabilite.domain.TypeTiers;
import jakarta.persistence.EntityManagerFactory;
import org.hibernate.SessionFactory;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Le nombre de requêtes du Cabinet Workspace ne dépend pas du nombre de dossiers (pas de N+1) : mesuré sur 10 puis 100 entreprises, chacune avec une écriture, un constat de pièce manquante et,
 * pour la moitié, une demande de pièce envoyée au client. Critère stable : le nombre de requêtes ; les temps affichés sont indicatifs (aucun seuil de durée dans le test).
 */
class CabinetWorkspacePerformanceTest extends CollecteTestBase {

    @Autowired CabinetWorkspaceService workspace;
    @Autowired CollecteService collecteService;
    @Autowired EntityManagerFactory entityManagerFactory;
    @MockitoSpyBean JdbcTemplate jdbcSpy;

    private Dossier cabinet;
    private int crees;

    private void creerJusqua(int total) {
        while (crees < total) {
            EntrepriseDto e = crees == 0 ? cabinet.entreprise() : entrepriseService.creer(
                    new CreerEntrepriseRequest("Bench " + UUID.randomUUID().toString().substring(0, 8), null, null, null, null, null, "CI", "XOF"), cabinet.expert());
            Dossier d = new Dossier(cabinet.expert(), e, tiersService.creer(e.id(), TypeTiers.FOURNISSEUR, "Fournisseur " + crees, null, cabinet.expert()), null);
            acheterSansPiece(d, 1000 + crees);
            List<UUID> constats = piecesManquantes(d).stream().map(f -> f.getId()).toList();
            jdbc.update("update finding set status = 'RESOLVED', resolved_at = now() where entreprise_id = ? and source_type = 'ETAT_FINANCIER'", e.id());   // isole le scénario « pièces » des constats des états
            if (crees % 2 == 0) {
                collecteService.demander(d.id(), constats, d.expert());
            }
            crees++;
        }
    }

    private record Mesure(long requetesJdbc, long requetesHibernate, long millis) {
    }

    private Mesure mesurer(Runnable action) {
        SessionFactory sf = entityManagerFactory.unwrap(SessionFactory.class);
        action.run();                                                                                   // échauffement (plans, cache de métadonnées)
        Mockito.clearInvocations(jdbcSpy);
        sf.getStatistics().clear();
        long debut = System.nanoTime();
        action.run();
        long ms = (System.nanoTime() - debut) / 1_000_000;
        long jdbc = Mockito.mockingDetails(jdbcSpy).getInvocations().stream()
                .filter(i -> i.getArguments().length > 0 && i.getArguments()[0] instanceof String && i.getMethod().getName().startsWith("query")).count();
        return new Mesure(jdbc, sf.getStatistics().getPrepareStatementCount(), ms);
    }

    @Test
    void nombreDeRequetes_borne_entre10Et100Dossiers() {
        cabinet = nouveauDossier(true);
        creerJusqua(10);
        var q = new DossierQuery(Filtre.TOUS, null, null, null, null, null, Tri.PRIORITE, false, 0, 25);
        Mesure dash10 = mesurer(() -> workspace.dashboard(cabinet.expert(), false));
        Mesure liste10 = mesurer(() -> workspace.dossiers(cabinet.expert(), q));
        Mesure file10 = mesurer(() -> workspace.workQueue(cabinet.expert(), false, 0, 25));

        creerJusqua(100);
        Mesure dash100 = mesurer(() -> workspace.dashboard(cabinet.expert(), false));
        Mesure liste100 = mesurer(() -> workspace.dossiers(cabinet.expert(), q));
        Mesure file100 = mesurer(() -> workspace.workQueue(cabinet.expert(), false, 0, 25));

        System.out.printf("BENCH-CABINET dashboard   10: %s | 100: %s%n", dash10, dash100);
        System.out.printf("BENCH-CABINET dossiers    10: %s | 100: %s%n", liste10, liste100);
        System.out.printf("BENCH-CABINET work-queue  10: %s | 100: %s%n", file10, file100);

        assertThat(dash100.requetesJdbc()).isEqualTo(dash10.requetesJdbc());
        assertThat(dash100.requetesHibernate()).isEqualTo(dash10.requetesHibernate());
        assertThat(liste100.requetesJdbc()).isEqualTo(liste10.requetesJdbc());
        assertThat(liste100.requetesHibernate()).isEqualTo(liste10.requetesHibernate());
        assertThat(file100.requetesJdbc()).isEqualTo(file10.requetesJdbc());
        assertThat(dash100.requetesJdbc() + dash100.requetesHibernate()).isLessThan(80);

        // pagination côté serveur : jamais plus d'une page, quel que soit le portefeuille
        PageDto<DossierSummaryDto> page = workspace.dossiers(cabinet.expert(), q);
        assertThat(page.total()).isGreaterThanOrEqualTo(100);
        assertThat(page.items()).hasSize(25);
        DashboardDto dash = workspace.dashboard(cabinet.expert(), false);
        assertThat(dash.workQueue().items()).hasSizeLessThanOrEqualTo(8);
        assertThat(dash.summary().enAttenteClient()).isGreaterThanOrEqualTo(50);
    }
}
