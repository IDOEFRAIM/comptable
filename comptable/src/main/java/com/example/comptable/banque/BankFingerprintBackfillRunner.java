package com.example.comptable.banque;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;

/** Lance UNE FOIS la reprise des empreintes bancaires (propriete {@code banque.backfill-empreintes=true}) ; inactive par defaut. */
@Component
public class BankFingerprintBackfillRunner implements ApplicationRunner {

    private final BankFingerprintBackfillService service;
    private final boolean actif;

    public BankFingerprintBackfillRunner(BankFingerprintBackfillService service, @Value("${banque.backfill-empreintes:false}") boolean actif) {
        this.service = service;
        this.actif = actif;
    }

    @Override
    public void run(ApplicationArguments args) {
        if (actif) {
            service.appliquer();
        }
    }
}
