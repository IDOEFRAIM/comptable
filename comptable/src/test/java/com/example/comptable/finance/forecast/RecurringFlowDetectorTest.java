package com.example.comptable.finance.forecast;

import com.example.comptable.comptabilite.domain.CompteComptable;
import com.example.comptable.comptabilite.domain.EcritureComptable;
import com.example.comptable.comptabilite.domain.LigneEcriture;
import com.example.comptable.comptabilite.domain.StatutEcriture;
import com.example.comptable.finance.forecast.web.dto.RecurringFlowDto;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class RecurringFlowDetectorTest {

    private LigneEcriture ligneCharge(CompteComptable compte, LocalDate date, BigDecimal montant) {
        EcritureComptable ecriture = new EcritureComptable();
        ecriture.setId(UUID.randomUUID());
        ecriture.setDateEcriture(date);
        ecriture.setStatut(StatutEcriture.VALIDEE);

        LigneEcriture ligne = new LigneEcriture();
        ligne.setId(UUID.randomUUID());
        ligne.setEcriture(ecriture);
        ligne.setCompte(compte);
        ligne.setDebit(montant);
        ligne.setCredit(BigDecimal.ZERO);
        return ligne;
    }

    private CompteComptable compte(String numero, String intitule) {
        CompteComptable c = new CompteComptable();
        c.setId(UUID.randomUUID());
        c.setNumero(numero);
        c.setIntitule(intitule);
        return c;
    }

    @Test
    void un_loyer_stable_sur_quatre_mois_est_detecte_comme_recurrent() {
        CompteComptable loyer = compte("613000", "Loyers");
        List<LigneEcriture> lignes = List.of(
                ligneCharge(loyer, LocalDate.of(2026, 5, 5), new BigDecimal("250000")),
                ligneCharge(loyer, LocalDate.of(2026, 6, 5), new BigDecimal("250000")),
                ligneCharge(loyer, LocalDate.of(2026, 7, 5), new BigDecimal("250000")),
                ligneCharge(loyer, LocalDate.of(2026, 8, 5), new BigDecimal("250000"))
        );

        List<RecurringFlowDto> resultats = RecurringFlowDetector.detecter(lignes);

        assertThat(resultats).hasSize(1);
        RecurringFlowDto detection = resultats.get(0);
        assertThat(detection.compteNumero()).isEqualTo("613000");
        assertThat(detection.averageAmount()).isEqualByComparingTo("250000");
        assertThat(detection.occurrences()).isEqualTo(4);
        assertThat(detection.lastOccurrenceDate()).isEqualTo(LocalDate.of(2026, 8, 5));
        assertThat(detection.nextExpectedDate()).isEqualTo(LocalDate.of(2026, 9, 5));
    }

    @Test
    void moins_de_trois_occurrences_nest_jamais_qualifie_de_recurrent() {
        CompteComptable telecom = compte("626000", "Telecom");
        List<LigneEcriture> lignes = List.of(
                ligneCharge(telecom, LocalDate.of(2026, 7, 10), new BigDecimal("50000")),
                ligneCharge(telecom, LocalDate.of(2026, 8, 10), new BigDecimal("50000"))
        );

        List<RecurringFlowDto> resultats = RecurringFlowDetector.detecter(lignes);

        assertThat(resultats).isEmpty();
    }

    @Test
    void des_montants_trop_instables_ne_sont_jamais_qualifies_de_recurrents() {
        CompteComptable chargesDiverses = compte("628000", "Charges diverses");
        List<LigneEcriture> lignes = List.of(
                ligneCharge(chargesDiverses, LocalDate.of(2026, 5, 15), new BigDecimal("10000")),
                ligneCharge(chargesDiverses, LocalDate.of(2026, 6, 15), new BigDecimal("900000")),
                ligneCharge(chargesDiverses, LocalDate.of(2026, 7, 15), new BigDecimal("15000"))
        );

        List<RecurringFlowDto> resultats = RecurringFlowDetector.detecter(lignes);

        assertThat(resultats).isEmpty();
    }

    @Test
    void deux_comptes_distincts_sont_detectes_independamment() {
        CompteComptable loyer = compte("613000", "Loyers");
        CompteComptable assurance = compte("616000", "Assurances");
        List<LigneEcriture> lignes = List.of(
                ligneCharge(loyer, LocalDate.of(2026, 6, 5), new BigDecimal("250000")),
                ligneCharge(loyer, LocalDate.of(2026, 7, 5), new BigDecimal("250000")),
                ligneCharge(loyer, LocalDate.of(2026, 8, 5), new BigDecimal("250000")),
                ligneCharge(assurance, LocalDate.of(2026, 6, 20), new BigDecimal("80000")),
                ligneCharge(assurance, LocalDate.of(2026, 7, 20), new BigDecimal("80000")),
                ligneCharge(assurance, LocalDate.of(2026, 8, 20), new BigDecimal("80000"))
        );

        List<RecurringFlowDto> resultats = RecurringFlowDetector.detecter(lignes);

        assertThat(resultats).hasSize(2);
        assertThat(resultats).extracting(RecurringFlowDto::compteNumero).containsExactly("613000", "616000");
    }

    @Test
    void aucune_ligne_ne_produit_aucune_detection() {
        assertThat(RecurringFlowDetector.detecter(List.of())).isEmpty();
    }
}
