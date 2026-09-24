package com.example.comptable.agent.accounting;

import com.example.comptable.agent.accounting.web.dto.AiAccountingPostingResponseDto;
import com.example.comptable.agent.accounting.web.dto.AiAccountingProposalLineRequest;
import com.example.comptable.agent.accounting.web.dto.AiAccountingValidationResponseDto;
import com.example.comptable.agent.accounting.web.dto.PostAiAccountingProposalRequest;
import com.example.comptable.comptabilite.domain.CompteComptable;
import com.example.comptable.comptabilite.operation.common.AccountingOperationEngine;
import com.example.comptable.comptabilite.operation.common.AccountingOperationType;
import com.example.comptable.comptabilite.operation.common.ResultatRegle;
import com.example.comptable.comptabilite.operation.common.web.dto.AccountingOperationProposalDto;
import com.example.comptable.comptabilite.parametrage.AccountContext;
import com.example.comptable.comptabilite.parametrage.AccountResolutionSource;
import com.example.comptable.comptabilite.parametrage.AccountResolutionStatus;
import com.example.comptable.comptabilite.parametrage.AccountingAccountResolver;
import com.example.comptable.comptabilite.parametrage.NiveauConfiance;
import com.example.comptable.comptabilite.parametrage.ResolvedAccount;
import com.example.comptable.comptabilite.parametrage.domain.AccountPurpose;
import com.example.comptable.comptabilite.domain.EcritureComptable;
import com.example.comptable.comptabilite.repository.EcritureComptableRepository;
import com.example.comptable.comptabilite.service.CompteComptableValidationService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class AiAccountingPostingServiceTest {

    @Mock
    private AiAccountingProposalValidationService validationService;
    @Mock
    private AccountingAccountResolver accountingAccountResolver;
    @Mock
    private CompteComptableValidationService compteComptableValidationService;
    @Mock
    private AccountingOperationEngine accountingOperationEngine;
    @Mock
    private EcritureComptableRepository ecritureComptableRepository;

    private final UUID entrepriseId = UUID.randomUUID();
    private final UUID documentId = UUID.randomUUID();

    private AiAccountingPostingService service() {
        return new AiAccountingPostingService(
                validationService, accountingAccountResolver, compteComptableValidationService,
                accountingOperationEngine, ecritureComptableRepository);
    }

    private PostAiAccountingProposalRequest requete() {
        return new PostAiAccountingProposalRequest(
                UUID.randomUUID(), documentId, entrepriseId,
                List.of(new AiAccountingProposalLineRequest(AccountPurpose.ACHAT_SERVICE, new BigDecimal("100000"), "Presta")),
                new BigDecimal("18000"), new BigDecimal("118000"), LocalDate.of(2026, 8, 1), "Facture fournisseur");
    }

    private CompteComptable compte(String numero) {
        CompteComptable compte = new CompteComptable();
        compte.setNumero(numero);
        compte.setIntitule("Intitule " + numero);
        return compte;
    }

    private ResolvedAccount resolu(String numero) {
        return new ResolvedAccount(AccountResolutionStatus.RESOLVED, UUID.randomUUID(), numero, "Intitule " + numero,
                AccountResolutionSource.MAPPING_GENERIQUE, NiveauConfiance.CERTAIN, "Resolu.");
    }

    @Test
    void proposition_invalide_nappelle_jamais_le_moteur_comptable() {
        when(validationService.valider(any())).thenReturn(
                new AiAccountingValidationResponseDto(false, true, List.of(), List.of("Compte non configure"), List.of()));

        AiAccountingPostingResponseDto reponse = service().poster(requete());

        assertThat(reponse.posted()).isFalse();
        assertThat(reponse.errors()).contains("Compte non configure");
        verify(accountingOperationEngine, never()).proposerEtValiderImmediatementSysteme(
                any(), any(), any(), any(), anyString(), any(), any(), anyString(), any());
    }

    @Test
    void proposition_valide_resout_tva_et_fournisseur_puis_poste_via_le_moteur() {
        when(validationService.valider(any())).thenReturn(
                new AiAccountingValidationResponseDto(true, false, List.of(), List.of(), List.of()));
        when(accountingAccountResolver.resolve(eq(entrepriseId), eq(AccountPurpose.ACHAT_SERVICE), any(AccountContext.class), eq(null)))
                .thenReturn(resolu("621"));
        when(accountingAccountResolver.resolve(eq(entrepriseId), eq(AccountPurpose.TVA_RECUPERABLE), any(AccountContext.class), eq(null)))
                .thenReturn(resolu("4456"));
        when(accountingAccountResolver.resolve(eq(entrepriseId), eq(AccountPurpose.FOURNISSEUR), any(AccountContext.class), eq(null)))
                .thenReturn(resolu("401"));
        when(compteComptableValidationService.chargerCompteUtilisable(eq(entrepriseId), any(), eq(true)))
                .thenReturn(compte("621")).thenReturn(compte("4456")).thenReturn(compte("401"));

        UUID proposalId = UUID.randomUUID();
        UUID ecritureId = UUID.randomUUID();
        OffsetDateTime validatedAt = OffsetDateTime.now();
        AccountingOperationProposalDto dto = new AccountingOperationProposalDto(
                proposalId, entrepriseId, "ACHAT", "APPROVED", UUID.randomUUID(), "ACH", null,
                LocalDate.of(2026, 8, 1), "Facture fournisseur", documentId.toString(), List.of(),
                new BigDecimal("118000"), new BigDecimal("118000"), true, List.of(), List.of(),
                OffsetDateTime.now(), validatedAt, null, null, ecritureId, "AI_ACCOUNTING_PROPOSAL", documentId);
        when(accountingOperationEngine.proposerEtValiderImmediatementSysteme(
                eq(entrepriseId), eq(AccountingOperationType.ACHAT), eq(null), eq(LocalDate.of(2026, 8, 1)),
                eq("Facture fournisseur"), eq(documentId.toString()), any(ResultatRegle.class),
                eq("AI_ACCOUNTING_PROPOSAL"), eq(documentId)))
                .thenReturn(dto);

        EcritureComptable ecriture = new EcritureComptable();
        ecriture.setNumeroPiece("ACH-2026-0001");
        when(ecritureComptableRepository.findById(ecritureId)).thenReturn(Optional.of(ecriture));

        AiAccountingPostingResponseDto reponse = service().poster(requete());

        assertThat(reponse.posted()).isTrue();
        assertThat(reponse.proposalId()).isEqualTo(proposalId);
        assertThat(reponse.entryId()).isEqualTo(ecritureId);
        assertThat(reponse.entryNumber()).isEqualTo("ACH-2026-0001");
        assertThat(reponse.journal()).isEqualTo("ACH");
        assertThat(reponse.errors()).isEmpty();
    }

    @Test
    void fournisseur_non_configure_empeche_le_posting_jamais_devine() {
        when(validationService.valider(any())).thenReturn(
                new AiAccountingValidationResponseDto(true, false, List.of(), List.of(), List.of()));
        when(accountingAccountResolver.resolve(eq(entrepriseId), eq(AccountPurpose.ACHAT_SERVICE), any(AccountContext.class), eq(null)))
                .thenReturn(resolu("621"));
        when(accountingAccountResolver.resolve(eq(entrepriseId), eq(AccountPurpose.TVA_RECUPERABLE), any(AccountContext.class), eq(null)))
                .thenReturn(resolu("4456"));
        when(accountingAccountResolver.resolve(eq(entrepriseId), eq(AccountPurpose.FOURNISSEUR), any(AccountContext.class), eq(null)))
                .thenReturn(ResolvedAccount.nonConfigure("Aucun mapping ni role systeme configure."));
        when(compteComptableValidationService.chargerCompteUtilisable(eq(entrepriseId), any(), eq(true)))
                .thenReturn(compte("621")).thenReturn(compte("4456"));

        AiAccountingPostingResponseDto reponse = service().poster(requete());

        assertThat(reponse.posted()).isFalse();
        assertThat(reponse.errors()).anySatisfy(e -> assertThat(e).contains("fournisseur"));
        verify(accountingOperationEngine, never()).proposerEtValiderImmediatementSysteme(
                any(), any(), any(), any(), anyString(), any(), any(), anyString(), any());
    }
}
