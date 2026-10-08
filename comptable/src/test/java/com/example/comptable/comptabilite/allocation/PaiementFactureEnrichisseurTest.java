package com.example.comptable.comptabilite.allocation;

import com.example.comptable.comptabilite.domain.CompteComptable;
import com.example.comptable.comptabilite.domain.EcritureComptable;
import com.example.comptable.comptabilite.domain.LigneEcriture;
import com.example.comptable.comptabilite.repository.LigneEcritureRepository;
import com.example.comptable.comptabilite.web.dto.FactureDto;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** Le statut de paiement d'une facture est CALCULE depuis les allocations ; une facture non comptabilisee n'en a pas. */
class PaiementFactureEnrichisseurTest {

    private static FactureDto facture(UUID ecritureId) {
        return new FactureDto(UUID.randomUUID(), "FACTURE", "FAC-1", UUID.randomUUID(), "Client", LocalDate.now(), null, "VALIDEE", null,
                new BigDecimal("1000"), BigDecimal.ZERO, new BigDecimal("1000"), null, ecritureId, List.of(), null, null, null);
    }

    private static LigneEcriture ligneClient(UUID ecritureId, int debit, int credit) {
        EcritureComptable e = new EcritureComptable();
        e.setId(ecritureId);
        LigneEcriture l = new LigneEcriture();
        l.setId(UUID.randomUUID());
        l.setEcriture(e);
        l.setCompte(new CompteComptable());
        l.setDebit(new BigDecimal(debit));
        l.setCredit(new BigDecimal(credit));
        return l;
    }

    @Test
    void ajouteLeStatutCalcule_etLaisseIntacteUneFactureNonComptabilisee() {
        StatutPaiementFactureService service = mock(StatutPaiementFactureService.class);
        UUID entreprise = UUID.randomUUID();
        UUID ecriture = UUID.randomUUID();
        when(service.situationsParEcriture(any(), anyCollection())).thenReturn(Map.of(ecriture,
                new PaymentAllocationService.Situation(UUID.randomUUID(), new BigDecimal("1000"), new BigDecimal("400"), new BigDecimal("600"),
                        StatutPaiement.PARTIELLEMENT_PAYEE)));
        PaiementFactureEnrichisseur enrichisseur = new PaiementFactureEnrichisseur(service);

        List<FactureDto> resultat = enrichisseur.enrichir(entreprise, List.of(facture(ecriture), facture(null)));

        assertThat(resultat.get(0).statutPaiement()).isEqualTo("PARTIELLEMENT_PAYEE");
        assertThat(resultat.get(0).montantRegle()).isEqualByComparingTo("400");
        assertThat(resultat.get(0).resteAPayer()).isEqualByComparingTo("600");
        assertThat(resultat.get(1).statutPaiement()).isNull(); // brouillon : pas de paiement calculable
    }

    @Test
    void sansAucuneSituation_lesFacturesSontRenduesTellesQuelles() {
        StatutPaiementFactureService service = mock(StatutPaiementFactureService.class);
        when(service.situationsParEcriture(any(), anyCollection())).thenReturn(Map.of());
        List<FactureDto> factures = List.of(facture(null));

        assertThat(new PaiementFactureEnrichisseur(service).enrichir(UUID.randomUUID(), factures)).isSameAs(factures);
    }
}
