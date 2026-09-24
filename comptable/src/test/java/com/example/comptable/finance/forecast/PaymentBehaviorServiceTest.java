package com.example.comptable.finance.forecast;

import com.example.comptable.comptabilite.domain.CompteComptable;
import com.example.comptable.comptabilite.domain.EcritureComptable;
import com.example.comptable.comptabilite.domain.Facture;
import com.example.comptable.comptabilite.domain.LigneEcriture;
import com.example.comptable.comptabilite.domain.StatutEcriture;
import com.example.comptable.comptabilite.domain.StatutFacture;
import com.example.comptable.comptabilite.domain.TypeFacture;
import com.example.comptable.comptabilite.repository.FactureRepository;
import com.example.comptable.comptabilite.repository.LigneEcritureRepository;
import com.example.comptable.finance.forecast.web.dto.PaymentBehaviorDto;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class PaymentBehaviorServiceTest {

    @Mock
    private LigneEcritureRepository ligneEcritureRepository;
    @Mock
    private FactureRepository factureRepository;

    @InjectMocks
    private PaymentBehaviorService paymentBehaviorService;

    private final UUID tiersId = UUID.randomUUID();
    private final CompteComptable compteClient = new CompteComptable();

    private LigneEcriture ligne(UUID ecritureId, LocalDate dateEcriture, BigDecimal debit, BigDecimal credit, String lettrage) {
        EcritureComptable ecriture = new EcritureComptable();
        ecriture.setId(ecritureId);
        ecriture.setDateEcriture(dateEcriture);
        ecriture.setStatut(StatutEcriture.VALIDEE);

        LigneEcriture ligne = new LigneEcriture();
        ligne.setId(UUID.randomUUID());
        ligne.setEcriture(ecriture);
        ligne.setCompte(compteClient);
        ligne.setDebit(debit);
        ligne.setCredit(credit);
        ligne.setLettrage(lettrage);
        return ligne;
    }

    private Facture facture(UUID ecritureId, LocalDate dateEcheance) {
        Facture facture = new Facture();
        facture.setId(UUID.randomUUID());
        facture.setType(TypeFacture.FACTURE);
        facture.setStatut(StatutFacture.PAYEE);
        facture.setDateEcheance(dateEcheance);
        facture.setEcritureId(ecritureId);
        return facture;
    }

    @Test
    void calcule_la_mediane_et_la_moyenne_quand_lechantillon_est_suffisant() {
        UUID ecFacture1 = UUID.randomUUID();
        UUID ecFacture2 = UUID.randomUUID();
        UUID ecFacture3 = UUID.randomUUID();

        // Facture 1 : echeance 2026-01-31, payee 2026-02-15 -> 15 jours de retard.
        // Facture 2 : echeance 2026-02-28, payee 2026-03-30 -> 30 jours de retard.
        // Facture 3 : echeance 2026-03-31, payee 2026-04-05 -> 5 jours de retard.
        List<LigneEcriture> lignesLettrees = List.of(
                ligne(ecFacture1, LocalDate.of(2026, 1, 31), new BigDecimal("100000"), BigDecimal.ZERO, "A"),
                ligne(UUID.randomUUID(), LocalDate.of(2026, 2, 15), BigDecimal.ZERO, new BigDecimal("100000"), "A"),
                ligne(ecFacture2, LocalDate.of(2026, 2, 28), new BigDecimal("200000"), BigDecimal.ZERO, "B"),
                ligne(UUID.randomUUID(), LocalDate.of(2026, 3, 30), BigDecimal.ZERO, new BigDecimal("200000"), "B"),
                ligne(ecFacture3, LocalDate.of(2026, 3, 31), new BigDecimal("50000"), BigDecimal.ZERO, "C"),
                ligne(UUID.randomUUID(), LocalDate.of(2026, 4, 5), BigDecimal.ZERO, new BigDecimal("50000"), "C")
        );
        when(ligneEcritureRepository.rechercherLignesLettreesPourTiers(tiersId)).thenReturn(lignesLettrees);
        when(factureRepository.findByEcritureIdIn(anyList())).thenReturn(List.of(
                facture(ecFacture1, LocalDate.of(2026, 1, 31)),
                facture(ecFacture2, LocalDate.of(2026, 2, 28)),
                facture(ecFacture3, LocalDate.of(2026, 3, 31))
        ));

        PaymentBehaviorDto resultat = paymentBehaviorService.analyser(tiersId, "Client Alpha");

        assertThat(resultat.sufficientHistory()).isTrue();
        assertThat(resultat.sampleSize()).isEqualTo(3);
        assertThat(resultat.medianDelayDays()).isEqualTo(15L); // mediane de [5, 15, 30]
        assertThat(resultat.averageDelayDays()).isEqualTo(50.0 / 3);
    }

    @Test
    void echantillon_insuffisant_ne_retourne_aucune_estimation() {
        UUID ecFacture1 = UUID.randomUUID();
        List<LigneEcriture> lignesLettrees = List.of(
                ligne(ecFacture1, LocalDate.of(2026, 1, 31), new BigDecimal("100000"), BigDecimal.ZERO, "A"),
                ligne(UUID.randomUUID(), LocalDate.of(2026, 2, 15), BigDecimal.ZERO, new BigDecimal("100000"), "A")
        );
        when(ligneEcritureRepository.rechercherLignesLettreesPourTiers(tiersId)).thenReturn(lignesLettrees);
        when(factureRepository.findByEcritureIdIn(anyList())).thenReturn(List.of(facture(ecFacture1, LocalDate.of(2026, 1, 31))));

        PaymentBehaviorDto resultat = paymentBehaviorService.analyser(tiersId, "Client Beta");

        assertThat(resultat.sufficientHistory()).isFalse();
        assertThat(resultat.sampleSize()).isEqualTo(1);
        assertThat(resultat.medianDelayDays()).isNull();
        assertThat(resultat.averageDelayDays()).isNull();
    }

    @Test
    void aucune_ligne_lettree_retourne_un_echantillon_vide() {
        when(ligneEcritureRepository.rechercherLignesLettreesPourTiers(tiersId)).thenReturn(List.of());

        PaymentBehaviorDto resultat = paymentBehaviorService.analyser(tiersId, "Client Gamma");

        assertThat(resultat.sufficientHistory()).isFalse();
        assertThat(resultat.sampleSize()).isZero();
    }

    @Test
    void un_groupe_de_lettrage_ambigu_est_ignore_jamais_devine() {
        // Deux debits + un credit sur le meme code : ambigu, aucune correlation fiable possible.
        UUID ecFacture1 = UUID.randomUUID();
        UUID ecFacture2 = UUID.randomUUID();
        List<LigneEcriture> lignesLettrees = List.of(
                ligne(ecFacture1, LocalDate.of(2026, 1, 31), new BigDecimal("50000"), BigDecimal.ZERO, "A"),
                ligne(ecFacture2, LocalDate.of(2026, 1, 31), new BigDecimal("50000"), BigDecimal.ZERO, "A"),
                ligne(UUID.randomUUID(), LocalDate.of(2026, 2, 15), BigDecimal.ZERO, new BigDecimal("100000"), "A")
        );
        when(ligneEcritureRepository.rechercherLignesLettreesPourTiers(tiersId)).thenReturn(lignesLettrees);

        PaymentBehaviorDto resultat = paymentBehaviorService.analyser(tiersId, "Client Delta");

        assertThat(resultat.sufficientHistory()).isFalse();
        assertThat(resultat.sampleSize()).isZero();
        org.mockito.Mockito.verifyNoInteractions(factureRepository);
    }
}
