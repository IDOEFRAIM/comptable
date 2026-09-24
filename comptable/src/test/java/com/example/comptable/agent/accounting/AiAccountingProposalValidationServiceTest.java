package com.example.comptable.agent.accounting;

import com.example.comptable.agent.accounting.web.dto.AiAccountingProposalLineRequest;
import com.example.comptable.agent.accounting.web.dto.AiAccountingValidationResponseDto;
import com.example.comptable.agent.accounting.web.dto.ValidateAiAccountingProposalRequest;
import com.example.comptable.comptabilite.parametrage.AccountContext;
import com.example.comptable.comptabilite.parametrage.AccountResolutionSource;
import com.example.comptable.comptabilite.parametrage.AccountResolutionStatus;
import com.example.comptable.comptabilite.parametrage.AccountingAccountResolver;
import com.example.comptable.comptabilite.parametrage.NiveauConfiance;
import com.example.comptable.comptabilite.parametrage.ResolvedAccount;
import com.example.comptable.comptabilite.parametrage.domain.AccountPurpose;
import com.example.comptable.entreprise.repository.EntrepriseRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.web.server.ResponseStatusException;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class AiAccountingProposalValidationServiceTest {

    @Mock
    private EntrepriseRepository entrepriseRepository;
    @Mock
    private AccountingAccountResolver accountingAccountResolver;

    private AiAccountingProposalValidationService service;

    private final UUID entrepriseId = UUID.randomUUID();

    private void initService() {
        service = new AiAccountingProposalValidationService(entrepriseRepository, accountingAccountResolver);
    }

    private ResolvedAccount resolu(String numero) {
        return new ResolvedAccount(AccountResolutionStatus.RESOLVED, UUID.randomUUID(), numero, "Intitule " + numero,
                AccountResolutionSource.MAPPING_GENERIQUE, NiveauConfiance.CERTAIN, "Resolu via mapping.");
    }

    @Test
    void entreprise_introuvable_est_refusee_avant_toute_resolution() {
        initService();
        when(entrepriseRepository.existsById(entrepriseId)).thenReturn(false);

        ValidateAiAccountingProposalRequest requete = new ValidateAiAccountingProposalRequest(
                UUID.randomUUID(), UUID.randomUUID(), entrepriseId,
                List.of(new AiAccountingProposalLineRequest(AccountPurpose.ACHAT_SERVICE, new BigDecimal("100000"), "Presta")),
                new BigDecimal("18000"), new BigDecimal("118000"));

        assertThatThrownBy(() -> service.valider(requete))
                .isInstanceOfSatisfying(ResponseStatusException.class,
                        e -> assertThat(e.getStatusCode().value()).isEqualTo(404));
    }

    @Test
    void toutes_les_lignes_resolues_et_montants_coherents_donne_valid_sans_revue() {
        initService();
        when(entrepriseRepository.existsById(entrepriseId)).thenReturn(true);
        when(accountingAccountResolver.resolve(eq(entrepriseId), eq(AccountPurpose.ACHAT_SERVICE), any(AccountContext.class), eq(null)))
                .thenReturn(resolu("621"));

        ValidateAiAccountingProposalRequest requete = new ValidateAiAccountingProposalRequest(
                UUID.randomUUID(), UUID.randomUUID(), entrepriseId,
                List.of(new AiAccountingProposalLineRequest(AccountPurpose.ACHAT_SERVICE, new BigDecimal("100000"), "Presta")),
                new BigDecimal("18000"), new BigDecimal("118000"));

        AiAccountingValidationResponseDto reponse = service.valider(requete);

        assertThat(reponse.valid()).isTrue();
        assertThat(reponse.requiresReview()).isFalse();
        assertThat(reponse.errors()).isEmpty();
        assertThat(reponse.warnings()).isEmpty();
        assertThat(reponse.resolvedAccounts()).hasSize(1);
        assertThat(reponse.resolvedAccounts().get(0).compteNumero()).isEqualTo("621");
    }

    @Test
    void une_ligne_non_configuree_rend_la_proposition_invalide_et_a_revoir_jamais_devinee() {
        initService();
        when(entrepriseRepository.existsById(entrepriseId)).thenReturn(true);
        when(accountingAccountResolver.resolve(eq(entrepriseId), eq(AccountPurpose.ACHAT_SERVICE), any(AccountContext.class), eq(null)))
                .thenReturn(ResolvedAccount.nonConfigure("Aucun mapping ni role systeme configure."));

        ValidateAiAccountingProposalRequest requete = new ValidateAiAccountingProposalRequest(
                UUID.randomUUID(), UUID.randomUUID(), entrepriseId,
                List.of(new AiAccountingProposalLineRequest(AccountPurpose.ACHAT_SERVICE, new BigDecimal("100000"), "Presta")),
                null, new BigDecimal("100000"));

        AiAccountingValidationResponseDto reponse = service.valider(requete);

        assertThat(reponse.valid()).isFalse();
        assertThat(reponse.requiresReview()).isTrue();
        assertThat(reponse.errors()).anySatisfy(e -> assertThat(e).contains("Presta").contains("ACHAT_SERVICE"));
    }

    @Test
    void un_ecart_entre_le_ttc_declare_et_la_somme_des_lignes_produit_un_avertissement_et_une_revue() {
        initService();
        when(entrepriseRepository.existsById(entrepriseId)).thenReturn(true);
        when(accountingAccountResolver.resolve(eq(entrepriseId), eq(AccountPurpose.ACHAT_SERVICE), any(AccountContext.class), eq(null)))
                .thenReturn(resolu("621"));

        ValidateAiAccountingProposalRequest requete = new ValidateAiAccountingProposalRequest(
                UUID.randomUUID(), UUID.randomUUID(), entrepriseId,
                List.of(new AiAccountingProposalLineRequest(AccountPurpose.ACHAT_SERVICE, new BigDecimal("100000"), "Presta")),
                new BigDecimal("18000"), new BigDecimal("999999"));

        AiAccountingValidationResponseDto reponse = service.valider(requete);

        assertThat(reponse.valid()).isTrue(); // la ligne elle-meme est bien resolue
        assertThat(reponse.requiresReview()).isTrue(); // mais l'incoherence de montant force une revue
        assertThat(reponse.warnings()).anySatisfy(w -> assertThat(w).contains("999999"));
    }

    @Test
    void un_petit_ecart_dans_la_tolerance_ne_declenche_aucun_avertissement() {
        initService();
        when(entrepriseRepository.existsById(entrepriseId)).thenReturn(true);
        when(accountingAccountResolver.resolve(eq(entrepriseId), eq(AccountPurpose.ACHAT_SERVICE), any(AccountContext.class), eq(null)))
                .thenReturn(resolu("621"));

        ValidateAiAccountingProposalRequest requete = new ValidateAiAccountingProposalRequest(
                UUID.randomUUID(), UUID.randomUUID(), entrepriseId,
                List.of(new AiAccountingProposalLineRequest(AccountPurpose.ACHAT_SERVICE, new BigDecimal("100000"), "Presta")),
                new BigDecimal("18000"), new BigDecimal("118000.50"));

        AiAccountingValidationResponseDto reponse = service.valider(requete);

        assertThat(reponse.warnings()).isEmpty();
        assertThat(reponse.requiresReview()).isFalse();
    }
}
