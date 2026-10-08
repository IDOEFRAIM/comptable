package com.example.comptable.fiscalite.paiement.provider;

import com.example.comptable.fiscalite.domain.TypeImpot;
import org.junit.jupiter.api.Test;
import org.springframework.web.client.RestClient;

import java.math.BigDecimal;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class FasoArzekaTaxPaymentProviderTest {

    private final FasoArzekaTaxPaymentProvider provider =
            new FasoArzekaTaxPaymentProvider(RestClient.builder(), "", "", "simulation");

    private TaxPaymentRequest requete(String numeroTelephonePayeur) {
        return new TaxPaymentRequest(UUID.randomUUID(), "00123456A", TypeImpot.CNPS, "2026-03",
                new BigDecimal("15000"), "XOF", numeroTelephonePayeur);
    }

    @Test
    void refuse_sans_numero_de_telephone_payeur() {
        TaxPaymentInitiationResult resultat = provider.initierPaiement(requete(null));

        assertThat(resultat.statut()).isEqualTo(com.example.comptable.fiscalite.paiement.domain.StatutPaiement.ECHEC);
        assertThat(resultat.message()).contains("Numero de telephone");
    }

    @Test
    void simule_une_demande_en_attente_avec_un_numero_de_telephone() {
        TaxPaymentInitiationResult resultat = provider.initierPaiement(requete("70123456"));

        assertThat(resultat.statut())
                .isEqualTo(com.example.comptable.fiscalite.paiement.domain.StatutPaiement.EN_ATTENTE_CONFIRMATION);
        assertThat(resultat.referenceExterne()).startsWith("SIM-FASOARZEKA-");
    }
}
