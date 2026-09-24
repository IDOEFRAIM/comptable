package com.example.comptable.comptabilite.operation.avoir;

import com.example.comptable.comptabilite.domain.CompteComptable;
import com.example.comptable.comptabilite.domain.Tiers;
import com.example.comptable.comptabilite.domain.TypeJournal;
import com.example.comptable.comptabilite.operation.achat.AchatRule;
import com.example.comptable.comptabilite.operation.common.AccountingOperationType;
import com.example.comptable.comptabilite.operation.common.ResultatRegle;
import com.example.comptable.comptabilite.operation.common.domain.AccountingOperationProposalLigne;
import com.example.comptable.comptabilite.operation.vente.VenteRule;
import com.example.comptable.comptabilite.service.CompteComptableValidationService;
import com.example.comptable.entreprise.domain.Entreprise;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.web.server.ResponseStatusException;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class AvoirRuleTest {

    @Mock
    private CompteComptableValidationService compteComptableValidationService;
    @Mock
    private AchatRule achatRule;
    @Mock
    private VenteRule venteRule;

    @InjectMocks
    private AvoirRule avoirRule;

    private UUID entrepriseId;
    private Entreprise entreprise;
    private Tiers client;
    private Tiers fournisseur;
    private CompteComptable compteClient;
    private CompteComptable compteFournisseur;

    @BeforeEach
    void setUp() {
        entrepriseId = UUID.randomUUID();
        entreprise = new Entreprise();
        entreprise.setId(entrepriseId);

        compteClient = compte("411001");
        client = new Tiers();
        client.setId(UUID.randomUUID());
        client.setEntreprise(entreprise);
        client.setCompteComptable(compteClient);

        compteFournisseur = compte("401001");
        fournisseur = new Tiers();
        fournisseur.setId(UUID.randomUUID());
        fournisseur.setEntreprise(entreprise);
        fournisseur.setCompteComptable(compteFournisseur);
    }

    private CompteComptable compte(String numero) {
        CompteComptable c = new CompteComptable();
        c.setId(UUID.randomUUID());
        c.setEntreprise(entreprise);
        c.setNumero(numero);
        c.setIntitule(numero);
        c.setActif(true);
        return c;
    }

    private AvoirOperationRequest request(AccountingOperationType type, Tiers tiers, UUID compteId) {
        return new AvoirOperationRequest(type, tiers.getId(), LocalDate.of(2026, 3, 1), "REF-1", "Avoir test",
                new BigDecimal("100000"), compteId, null, null);
    }

    @Test
    void avoir_vente_inverse_les_lignes_core_de_ventrule() {
        CompteComptable compteProduit = compte("701");
        CompteComptable compteTva = compte("443");
        when(compteComptableValidationService.chargerCompteUtilisable(entrepriseId, compteProduit.getId(), true))
                .thenReturn(compteProduit);

        List<AccountingOperationProposalLigne> lignesCore = List.of(
                AccountingOperationProposalLigne.debit(compteClient, new BigDecimal("118000"), "Vente test"),
                AccountingOperationProposalLigne.credit(compteProduit, new BigDecimal("100000"), "Vente test"),
                AccountingOperationProposalLigne.credit(compteTva, new BigDecimal("18000"), "TVA collectee - Vente test"));
        when(venteRule.construireLignesCore(eq(entreprise), eq(client), eq(compteProduit), eq(new BigDecimal("100000")),
                eq((UUID) null), any(), any(), any(), any())).thenReturn(lignesCore);

        ResultatRegle resultat = avoirRule.construire(entreprise, client,
                request(AccountingOperationType.AVOIR_VENTE, client, compteProduit.getId()));

        assertThat(resultat.typeJournalAttendu()).isEqualTo(TypeJournal.VENTES);
        assertThat(resultat.lignes()).hasSize(3);
        assertThat(resultat.lignes()).anySatisfy(l -> {
            assertThat(l.getCompte().getNumero()).isEqualTo("411001");
            assertThat(l.getCredit()).isEqualByComparingTo("118000");
            assertThat(l.getDebit()).isEqualByComparingTo("0");
        });
        assertThat(resultat.lignes()).anySatisfy(l -> {
            assertThat(l.getCompte().getNumero()).isEqualTo("701");
            assertThat(l.getDebit()).isEqualByComparingTo("100000");
        });
        assertThat(resultat.lignes()).anySatisfy(l -> {
            assertThat(l.getCompte().getNumero()).isEqualTo("443");
            assertThat(l.getDebit()).isEqualByComparingTo("18000");
        });
    }

    @Test
    void avoir_achat_inverse_les_lignes_core_dachatrule() {
        CompteComptable compteCharge = compte("601");
        CompteComptable compteTva = compte("445");
        when(compteComptableValidationService.chargerCompteUtilisable(entrepriseId, compteCharge.getId(), true))
                .thenReturn(compteCharge);

        List<AccountingOperationProposalLigne> lignesCore = List.of(
                AccountingOperationProposalLigne.debit(compteCharge, new BigDecimal("100000"), "Achat test"),
                AccountingOperationProposalLigne.debit(compteTva, new BigDecimal("18000"), "TVA deductible - Achat test"),
                AccountingOperationProposalLigne.credit(compteFournisseur, new BigDecimal("118000"), "Achat test"));
        when(achatRule.construireLignesCore(eq(entreprise), eq(fournisseur), eq(compteCharge), eq(new BigDecimal("100000")),
                eq((UUID) null), any(), any(), any(), any())).thenReturn(lignesCore);

        ResultatRegle resultat = avoirRule.construire(entreprise, fournisseur,
                request(AccountingOperationType.AVOIR_ACHAT, fournisseur, compteCharge.getId()));

        assertThat(resultat.typeJournalAttendu()).isEqualTo(TypeJournal.ACHATS);
        assertThat(resultat.lignes()).anySatisfy(l -> {
            assertThat(l.getCompte().getNumero()).isEqualTo("601");
            assertThat(l.getCredit()).isEqualByComparingTo("100000");
        });
        assertThat(resultat.lignes()).anySatisfy(l -> {
            assertThat(l.getCompte().getNumero()).isEqualTo("401001");
            assertThat(l.getDebit()).isEqualByComparingTo("118000");
        });
    }

    @Test
    void montant_ht_negatif_est_rejete() {
        AvoirOperationRequest request = new AvoirOperationRequest(AccountingOperationType.AVOIR_VENTE, client.getId(),
                LocalDate.of(2026, 3, 1), "REF-1", "Avoir test", new BigDecimal("-1"), UUID.randomUUID(), null, null);

        assertThatThrownBy(() -> avoirRule.construire(entreprise, client, request))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("negatif");
    }

    @Test
    void type_invalide_pour_un_avoir_est_rejete() {
        assertThatThrownBy(() -> avoirRule.construire(entreprise, client,
                request(AccountingOperationType.VENTE, client, UUID.randomUUID())))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("invalide");
    }
}
