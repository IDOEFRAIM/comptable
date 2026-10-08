package com.example.comptable.comptabilite;

import com.example.comptable.banque.BankTestBase;
import com.example.comptable.comptabilite.repository.ExerciceComptableRepository;
import com.example.comptable.comptabilite.service.ClotureExerciceService;
import com.example.comptable.comptabilite.service.EcritureComptableService;
import com.example.comptable.comptabilite.web.dto.CreerEcritureRequest;
import com.example.comptable.comptabilite.web.dto.LigneEcritureRequest;
import com.example.comptable.dossier.ClotureReadinessService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.server.ResponseStatusException;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Atomicite pre-cloture -> cloture (F0.2), sur un vrai PostgreSQL et de vrais threads. Invariant : il est impossible que T1 voie « READY », que T2 ajoute une ecriture
 * problematique, puis que T1 cloture sans l'avoir vue. Mecanisme : la cloture prend le verrou EXCLUSIF de la ligne d'exercice puis recalcule ses controles ; toute ecriture
 * se passe sous le verrou PARTAGE de cette meme ligne (ExerciceOuvertGuard).
 */
class ClotureAtomiciteTest extends BankTestBase {

    @Autowired ClotureReadinessService readinessService;
    @Autowired ClotureExerciceService clotureService;
    @Autowired EcritureComptableService ecritureService;
    @Autowired ExerciceComptableRepository exerciceRepository;
    @Autowired PlatformTransactionManager transactionManager;

    private CreerEcritureRequest brouillon(Dossier d) {
        UUID journal = jdbc.queryForObject("select id from journal where entreprise_id = ? order by code limit 1", UUID.class, d.id());
        UUID charge = compteComptableRepository.findByEntrepriseIdAndNumero(d.id(), "601").orElseThrow().getId();
        UUID produit = compteComptableRepository.findByEntrepriseIdAndNumero(d.id(), "701").orElseThrow().getId();
        return new CreerEcritureRequest(journal, LocalDate.now(), "Ecriture concurrente", null, List.of(
                new LigneEcritureRequest(charge, "Charge", new BigDecimal("1000"), BigDecimal.ZERO, null),
                new LigneEcritureRequest(produit, "Produit", BigDecimal.ZERO, new BigDecimal("1000"), null)));
    }

    private UUID exercice(Dossier d) {
        return exerciceRepository.findByEntrepriseIdAndDate(d.id(), LocalDate.now()).orElseThrow().getId();
    }

    @Test
    void readyPuisNouvelleEcritureEnCours_laClotureLaVoitEtRefuse() throws Exception {
        Dossier d = nouveauDossier(false);
        // T1 : le dossier est READY.
        assertThat(readinessService.evaluer(d.id(), d.expert()).statut()).isEqualTo("READY");
        UUID exercice = exercice(d);

        ExecutorService pool = Executors.newFixedThreadPool(2);
        CountDownLatch ecritureCreee = new CountDownLatch(1);
        CountDownLatch autoriserCommit = new CountDownLatch(1);
        TransactionTemplate tx = new TransactionTemplate(transactionManager);

        // T2 : une ecriture est creee (sous verrou partage de l'exercice) mais PAS encore validee en base.
        Future<?> t2 = pool.submit(() -> tx.executeWithoutResult(status -> {
            ecritureService.creerManuelle(d.id(), brouillon(d), d.expert());
            ecritureCreee.countDown();
            try {
                autoriserCommit.await(30, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }));
        assertThat(ecritureCreee.await(30, TimeUnit.SECONDS)).isTrue();

        // T1 : tente la cloture, alors que T2 est en cours. Elle doit ATTENDRE (verrou exclusif de l'exercice), pas conclure sur un etat perime.
        Future<Object> t1 = pool.submit(() -> clotureService.cloturerAvecConfirmation(d.id(), exercice, d.expert(), null));
        Thread.sleep(1500);
        assertThat(t1.isDone()).as("la cloture attend la fin de l'ecriture en cours").isFalse();

        autoriserCommit.countDown();
        t2.get(30, TimeUnit.SECONDS);

        // T1 voit maintenant l'ecriture (brouillon = bloquant) : cloture REFUSEE, exercice toujours ouvert.
        Throwable cause = null;
        try {
            t1.get(60, TimeUnit.SECONDS);
        } catch (ExecutionException e) {
            cause = e.getCause();
        }
        pool.shutdown();
        assertThat(cause).isInstanceOf(ResponseStatusException.class);
        assertThat(((ResponseStatusException) cause).getStatusCode().value()).isEqualTo(422);
        assertThat(((ResponseStatusException) cause).getReason()).containsIgnoringCase("brouillon");
        assertThat(exerciceRepository.findById(exercice).orElseThrow().getStatut().name()).isEqualTo("OUVERT");
    }

    @Test
    void ecritureArrivantPendantLaCloture_attendPuisVoitL_exerciceCloture() throws Exception {
        Dossier d = nouveauDossier(false);
        UUID exercice = exercice(d);
        ExecutorService pool = Executors.newFixedThreadPool(2);
        CountDownLatch verrouPris = new CountDownLatch(1);
        CountDownLatch autoriserCommit = new CountDownLatch(1);
        TransactionTemplate tx = new TransactionTemplate(transactionManager);

        // T1 : detient le verrou exclusif de l'exercice et le passe a CLOTURE (comme le fait la cloture), commit retarde.
        Future<?> t1 = pool.submit(() -> tx.executeWithoutResult(status -> {
            exerciceRepository.verrouiller(exercice);
            jdbc.update("update exercice_comptable set statut = 'CLOTURE' where id = ?", exercice);
            verrouPris.countDown();
            try {
                autoriserCommit.await(30, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }));
        assertThat(verrouPris.await(30, TimeUnit.SECONDS)).isTrue();

        // T2 : cree une ecriture pendant la cloture : elle doit attendre, puis etre refusee (l'exercice est cloture).
        Future<Object> t2 = pool.submit(() -> ecritureService.creerManuelle(d.id(), brouillon(d), d.expert()));
        Thread.sleep(1500);
        assertThat(t2.isDone()).as("l'ecriture attend la fin de la cloture").isFalse();

        autoriserCommit.countDown();
        t1.get(30, TimeUnit.SECONDS);
        Throwable cause = null;
        try {
            t2.get(60, TimeUnit.SECONDS);
        } catch (ExecutionException e) {
            cause = e.getCause();
        }
        pool.shutdown();
        assertThat(cause).isInstanceOf(ResponseStatusException.class);
        assertThat(((ResponseStatusException) cause).getReason()).contains("cloture");
    }

    @Test
    void deuxCloturesSimultanees_uneSeuleReussit() throws Exception {
        Dossier d = nouveauDossier(false);
        UUID exercice = exercice(d);
        ExecutorService pool = Executors.newFixedThreadPool(2);
        Future<Integer> a = pool.submit(() -> statut(() -> clotureService.cloturerAvecConfirmation(d.id(), exercice, d.expert(), null)));
        Future<Integer> b = pool.submit(() -> statut(() -> clotureService.cloturerAvecConfirmation(d.id(), exercice, d.expert(), null)));
        List<Integer> statuts = List.of(a.get(120, TimeUnit.SECONDS), b.get(120, TimeUnit.SECONDS));
        pool.shutdown();
        assertThat(statuts).containsExactlyInAnyOrder(200, 409);
    }

    private int statut(java.util.function.Supplier<?> action) {
        try {
            action.get();
            return 200;
        } catch (ResponseStatusException e) {
            return e.getStatusCode().value();
        }
    }
}
