package com.example.comptable.investigation;

import com.example.comptable.banque.BankTestBase;
import com.example.comptable.comptabilite.domain.TypeTiers;
import com.example.comptable.comptabilite.operation.common.AccountingOperationType;
import com.example.comptable.comptabilite.operation.common.MoyenPaiement;
import com.example.comptable.comptabilite.operation.reglement.ReglementOperationRequest;
import com.example.comptable.comptabilite.web.dto.TiersDto;
import com.example.comptable.controle.ControlCodes;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Deux ecrans qui s'ouvrent en meme temps (revue + pre-cloture) declenchent chacun l'execution des controles : sans serialisation, les deux declaraient le
 * meme constat et l'un des deux tombait en violation d'unicite (HTTP 500, constate au test navigateur de la phase E).
 */
class ControleConcurrenceTest extends BankTestBase {

    @Autowired com.example.comptable.controle.ControlRunService controlRunService;

    @Test
    void executionsSimultanees_neDeclarentJamaisLeMemeConstatDeuxFois() throws Exception {
        Dossier d = nouveauDossier(false);
        TiersDto client = tiersService.creer(d.id(), TypeTiers.CLIENT, "Client Concurrent", null, d.expert());
        for (int i = 0; i < 3; i++) {
            engine.valider(d.id(), engine.proposerReglement(d.id(), new ReglementOperationRequest(AccountingOperationType.REGLEMENT_CLIENT, client.id(), LocalDate.now(),
                    "ENC-C" + i, "Encaissement " + i, new BigDecimal(10_000 + i), MoyenPaiement.VIREMENT), d.expert()).id(), d.expert());
        }
        var entreprise = entrepriseRepository.findById(d.id()).orElseThrow();

        ExecutorService pool = Executors.newFixedThreadPool(4);
        List<Future<Boolean>> resultats = new ArrayList<>();
        for (int i = 0; i < 4; i++) {
            Callable<Boolean> appel = i % 2 == 0 ? () -> {
                controlRunService.executer(entreprise);
                return true;
            } : () -> controlRunService.executerSiPerime(entreprise);
            resultats.add(pool.submit(appel));
        }
        for (Future<Boolean> f : resultats) {
            f.get(); // une exception d'une execution fait echouer le test
        }
        pool.shutdown();

        assertThat(findingRepository.findActifsParCodes(d.id(), List.of(ControlCodes.PAYMENT_WITHOUT_INVOICE, ControlCodes.PAYMENT_ALLOCATION_NEEDS_REVIEW, ControlCodes.OVERPAYMENT)))
                .isNotEmpty();
        Long doublons = jdbc.queryForObject("select count(*) from (select control_code, source_type, source_id from finding where entreprise_id = ? and status in ('OPEN', 'IN_PROGRESS') "
                + "group by control_code, source_type, source_id having count(*) > 1) t", Long.class, d.id());
        assertThat(doublons).isZero();
    }
}
