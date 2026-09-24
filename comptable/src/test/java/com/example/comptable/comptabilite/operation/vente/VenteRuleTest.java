package com.example.comptable.comptabilite.operation.vente;

import com.example.comptable.comptabilite.domain.CompteComptable;
import com.example.comptable.comptabilite.domain.RoleCompteSysteme;
import com.example.comptable.comptabilite.domain.Tiers;
import com.example.comptable.comptabilite.domain.TypeJournal;
import com.example.comptable.comptabilite.operation.common.FraisAccessoireRequest;
import com.example.comptable.comptabilite.operation.common.ReductionRequest;
import com.example.comptable.comptabilite.operation.common.ResultatRegle;
import com.example.comptable.comptabilite.operation.common.TypeReduction;
import com.example.comptable.comptabilite.operation.common.domain.AccountingOperationProposalLigne;
import com.example.comptable.comptabilite.operation.tva.TvaRateResolver;
import com.example.comptable.comptabilite.operation.tva.TvaResultat;
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
class VenteRuleTest {

    @Mock
    private CompteComptableValidationService compteComptableValidationService;
    @Mock
    private TvaRateResolver tvaRateResolver;

    @InjectMocks
    private VenteRule venteRule;

    private UUID entrepriseId;
    private Entreprise entreprise;
    private Tiers client;
    private CompteComptable compteClient;

    @BeforeEach
    void setUp() {
        entrepriseId = UUID.randomUUID();
        entreprise = new Entreprise();
        entreprise.setId(entrepriseId);
        entreprise.setPays("CI");

        compteClient = compte("411001");
        client = new Tiers();
        client.setId(UUID.randomUUID());
        client.setEntreprise(entreprise);
        client.setCompteComptable(compteClient);
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

    private VenteOperationRequest request(NatureVente nature, BigDecimal montantHt, UUID compteProduitId,
            UUID tauxTvaId, BigDecimal montantTtcAttendu, List<FraisAccessoireRequest> frais,
            List<ReductionRequest> reductions) {
        return new VenteOperationRequest(nature, client.getId(), LocalDate.of(2026, 3, 1), "REF-1",
                "Vente test", montantHt, compteProduitId, tauxTvaId, montantTtcAttendu, frais, reductions);
    }

    @Test
    void vente_marchandise_avec_tva_produit_client_produit_et_tva() {
        CompteComptable compteProduit = compte("701");
        CompteComptable compteTva = compte("443");
        when(compteComptableValidationService.chargerCompteUtilisable(entrepriseId, compteProduit.getId(), true))
                .thenReturn(compteProduit);
        when(tvaRateResolver.resoudre(eq(entrepriseId), eq("CI"), any(), eq(new BigDecimal("100000")), eq(RoleCompteSysteme.TVA_COLLECTEE)))
                .thenReturn(new TvaResultat(new BigDecimal("18"), new BigDecimal("18000"), compteTva, UUID.randomUUID(), false, null, false));

        ResultatRegle resultat = venteRule.construire(entreprise, client,
                request(NatureVente.VENTE_MARCHANDISE, new BigDecimal("100000"), compteProduit.getId(), null, null, null, null));

        assertThat(resultat.typeJournalAttendu()).isEqualTo(TypeJournal.VENTES);
        assertThat(resultat.explications()).isEmpty();
        assertThat(resultat.lignes()).hasSize(3);
        assertThat(totalDebit(resultat)).isEqualByComparingTo(totalCredit(resultat));
        assertThat(resultat.lignes()).anySatisfy(l -> {
            assertThat(l.getCompte().getNumero()).isEqualTo("411001");
            assertThat(l.getDebit()).isEqualByComparingTo("118000");
        });
        assertThat(resultat.lignes()).anySatisfy(l -> {
            assertThat(l.getCompte().getNumero()).isEqualTo("701");
            assertThat(l.getCredit()).isEqualByComparingTo("100000");
        });
        assertThat(resultat.lignes()).anySatisfy(l -> {
            assertThat(l.getCompte().getNumero()).isEqualTo("443");
            assertThat(l.getCredit()).isEqualByComparingTo("18000");
        });
    }

    @Test
    void vente_service_sans_regle_tva_applicable_reste_incomplete() {
        CompteComptable compteProduit = compte("706");
        when(compteComptableValidationService.chargerCompteUtilisable(entrepriseId, compteProduit.getId(), true))
                .thenReturn(compteProduit);
        when(tvaRateResolver.resoudre(eq(entrepriseId), eq("CI"), any(), eq(new BigDecimal("50000")), eq(RoleCompteSysteme.TVA_COLLECTEE)))
                .thenReturn(TvaResultat.nonSupporte("Aucune regle TVA applicable"));

        ResultatRegle resultat = venteRule.construire(entreprise, client,
                request(NatureVente.VENTE_SERVICE, new BigDecimal("50000"), compteProduit.getId(), null, null, null, null));

        assertThat(resultat.explications()).anySatisfy(e -> assertThat(e).contains("TVA collectee non calculee"));
        assertThat(resultat.lignes()).hasSize(2);
    }

    @Test
    void montant_ht_negatif_est_rejete() {
        assertThatThrownBy(() -> venteRule.construire(entreprise, client,
                request(NatureVente.VENTE_MARCHANDISE, new BigDecimal("-1"), UUID.randomUUID(), null, null, null, null)))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("negatif");
    }

    @Test
    void frais_accessoire_hors_base_tva_avec_taux_dedie_fusionne_dans_lunique_ligne_tva() {
        CompteComptable compteProduit = compte("701");
        CompteComptable compteFrais = compte("7071");
        CompteComptable compteTva = compte("443");
        UUID tauxFrais = UUID.randomUUID();
        when(compteComptableValidationService.chargerCompteUtilisable(entrepriseId, compteProduit.getId(), true))
                .thenReturn(compteProduit);
        when(compteComptableValidationService.chargerCompteUtilisable(entrepriseId, compteFrais.getId(), true))
                .thenReturn(compteFrais);
        when(tvaRateResolver.resoudreParRegle(entrepriseId, tauxFrais, new BigDecimal("5000"), RoleCompteSysteme.TVA_COLLECTEE))
                .thenReturn(new TvaResultat(new BigDecimal("18"), new BigDecimal("900"), compteTva, tauxFrais, false, null, false));
        when(tvaRateResolver.resoudre(eq(entrepriseId), eq("CI"), any(), eq(new BigDecimal("100000")), eq(RoleCompteSysteme.TVA_COLLECTEE)))
                .thenReturn(new TvaResultat(new BigDecimal("18"), new BigDecimal("18000"), compteTva, UUID.randomUUID(), false, null, false));

        FraisAccessoireRequest frais = new FraisAccessoireRequest("Emballage", new BigDecimal("5000"), tauxFrais, compteFrais.getId(), false);
        ResultatRegle resultat = venteRule.construire(entreprise, client,
                request(NatureVente.VENTE_MARCHANDISE, new BigDecimal("100000"), compteProduit.getId(), null, null, List.of(frais), null));

        long lignesTva = resultat.lignes().stream().filter(l -> l.getCompte().getNumero().equals("443")).count();
        assertThat(lignesTva).isEqualTo(1);
        assertThat(resultat.lignes()).anySatisfy(l -> {
            assertThat(l.getCompte().getNumero()).isEqualTo("443");
            assertThat(l.getCredit()).isEqualByComparingTo("18900");
        });
    }

    @Test
    void escompte_declenche_une_explication_non_supporte_sans_calcul() {
        CompteComptable compteProduit = compte("701");
        when(compteComptableValidationService.chargerCompteUtilisable(entrepriseId, compteProduit.getId(), true))
                .thenReturn(compteProduit);
        when(tvaRateResolver.resoudre(eq(entrepriseId), eq("CI"), any(), eq(new BigDecimal("100000")), eq(RoleCompteSysteme.TVA_COLLECTEE)))
                .thenReturn(TvaResultat.nonSupporte("non teste"));

        ReductionRequest escompte = new ReductionRequest(TypeReduction.ESCOMPTE, null, new BigDecimal("2"), "reglement comptant");
        ResultatRegle resultat = venteRule.construire(entreprise, client,
                request(NatureVente.VENTE_MARCHANDISE, new BigDecimal("100000"), compteProduit.getId(), null, null, null, List.of(escompte)));

        assertThat(resultat.explications()).anySatisfy(e -> assertThat(e).containsIgnoringCase("escompte"));
    }

    private BigDecimal totalDebit(ResultatRegle resultat) {
        return resultat.lignes().stream().map(AccountingOperationProposalLigne::getDebit).reduce(BigDecimal.ZERO, BigDecimal::add);
    }

    private BigDecimal totalCredit(ResultatRegle resultat) {
        return resultat.lignes().stream().map(AccountingOperationProposalLigne::getCredit).reduce(BigDecimal.ZERO, BigDecimal::add);
    }
}
