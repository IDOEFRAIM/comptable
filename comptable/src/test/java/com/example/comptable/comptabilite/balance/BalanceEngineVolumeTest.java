package com.example.comptable.comptabilite.balance;

import com.example.comptable.Benchmark;
import com.example.comptable.banque.BankTestBase;
import com.example.comptable.comptabilite.repository.ExerciceComptableRepository;
import com.example.comptable.comptabilite.service.BalanceService;
import com.example.comptable.comptabilite.web.dto.BalanceDto;
import jakarta.persistence.EntityManagerFactory;
import org.hibernate.SessionFactory;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Volume du moteur de balance (F1.5). Par defaut : echelle REDUITE, invariants deterministes uniquement (resultat, equilibre, nombre de requetes SQL independant du volume).
 * Avec {@code -Dbenchmark=true} : grandes echelles (100 puis 1 000 comptes ; 10 000, 50 000 puis 100 000 ecritures) et mesure de duree consignee dans docs/accounting-balance-engine.md.
 *
 * <pre>./mvnw test -Dtest=BalanceEngineVolumeTest -Dbenchmark=true</pre>
 */
class BalanceEngineVolumeTest extends BankTestBase {

    @Autowired AccountingBalanceEngine engineSoldes;
    @Autowired BalanceService balanceService;
    @Autowired ExerciceComptableRepository exerciceRepository;
    @Autowired EntityManagerFactory entityManagerFactory;

    private long statements() {
        return entityManagerFactory.unwrap(SessionFactory.class).getStatistics().getPrepareStatementCount();
    }

    /** Genere {@code comptes} comptes locaux et {@code ecritures} ecritures validees equilibrees (2 lignes chacune) dans l'exercice courant, en SQL massif. */
    private void generer(Dossier d, UUID exercice, int comptes, int ecritures) {
        UUID journal = jdbc.queryForObject("select id from journal where entreprise_id = ? order by code limit 1", UUID.class, d.id());
        jdbc.update("insert into compte_comptable (id, entreprise_id, numero, intitule, lettrable, niveau, actif) "
                + "select gen_random_uuid(), ?, 'ZV' || lpad(g::text, 6, '0'), 'Compte volume ' || g, false, 1, true from generate_series(1, ?) g", d.id(), comptes);
        LocalDate debut = LocalDate.now().withDayOfYear(1);
        jdbc.update("insert into ecriture_comptable (id, entreprise_id, exercice_id, journal_id, numero_piece, date_ecriture, libelle, statut, nature, created_at) "
                + "select gen_random_uuid(), ?, ?, ?, 'VOL-' || g, ?::date + (g % 365), 'Ecriture volume', 'VALIDEE', 'BUSINESS', now() from generate_series(1, ?) g",
                d.id(), exercice, journal, debut, ecritures);
        String cte = "with e as (select id, row_number() over (order by numero_piece) g from ecriture_comptable where entreprise_id = ? and numero_piece like 'VOL-%'), "
                + "c as (select id, row_number() over (order by numero) - 1 rang from compte_comptable where entreprise_id = ? and numero like 'ZV%') ";
        jdbc.update(cte + "insert into ligne_ecriture (id, ecriture_id, compte_id, libelle, debit, credit, numero_ligne) "
                + "select gen_random_uuid(), e.id, c.id, 'D', 10 + (e.g % 90), 0, 1 from e join c on c.rang = e.g % ?", d.id(), d.id(), comptes);
        jdbc.update(cte + "insert into ligne_ecriture (id, ecriture_id, compte_id, libelle, debit, credit, numero_ligne) "
                + "select gen_random_uuid(), e.id, c.id, 'C', 0, 10 + (e.g % 90), 2 from e join c on c.rang = (e.g + 1) % ?", d.id(), d.id(), comptes);
        Benchmark.preparer(jdbc);
    }

    private void mesurer(int comptes, int ecritures) {
        Dossier d = nouveauDossier(false);
        var ex = exerciceRepository.findByEntrepriseIdAndDate(d.id(), LocalDate.now()).orElseThrow();
        generer(d, ex.getId(), comptes, ecritures);

        balanceService.generer(d.id(), ex.getDateDebut(), ex.getDateFin(), d.expert());   // echauffement (plans, cache)
        long avant = statements();
        long t0 = System.nanoTime();
        BalanceDto balance = balanceService.generer(d.id(), ex.getDateDebut(), ex.getDateFin(), d.expert());
        long millis = (System.nanoTime() - t0) / 1_000_000;
        long requetes = statements() - avant;

        assertThat(balance.lignes()).hasSize(comptes);
        assertThat(balance.totalMouvementDebit()).isEqualByComparingTo(balance.totalMouvementCredit());
        assertThat(balance.totalSoldeClotureDebit()).isEqualByComparingTo(balance.totalSoldeClotureCredit());
        assertThat(balance.totalMouvementDebit()).isGreaterThan(BigDecimal.ZERO);
        assertThat(requetes).as("nombre de requetes independant du volume").isLessThanOrEqualTo(6);

        List<AccountBalance> soldes = engineSoldes.balances(d.id(), ex.getDateDebut(), ex.getDateFin(), ReportingView.PERIOD_ACTIVITY);
        assertThat(soldes).hasSize(comptes);
        System.out.printf("BENCH balance comptes=%d ecritures=%d requetes=%d duree=%d ms%n", comptes, ecritures, requetes, millis);
        if (Benchmark.actif()) {
            assertThat(millis).as("borne large de duree (benchmark uniquement)").isLessThan(10_000);
        }
    }

    @Test
    void petiteEchelle() {
        mesurer(Benchmark.taille(100, 100), Benchmark.taille(10_000, 2_000));
    }

    @Test
    void grandeEchelle() {
        mesurer(Benchmark.taille(1_000, 300), Benchmark.taille(50_000, 5_000));
    }

    @Test
    void tresGrandeEchelle() {
        mesurer(Benchmark.taille(1_000, 300), Benchmark.taille(100_000, 5_000));
    }
}
