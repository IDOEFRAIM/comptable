package com.example.comptable.controle;

import com.example.comptable.comptabilite.domain.TypeJournal;
import com.example.comptable.comptabilite.operation.common.Blocages;
import com.example.comptable.comptabilite.operation.common.ResultatRegle;
import com.example.comptable.comptabilite.parametrage.AccountResolutionCode;
import com.example.comptable.comptabilite.parametrage.AccountResolutionSource;
import com.example.comptable.comptabilite.parametrage.AccountResolutionStatus;
import com.example.comptable.comptabilite.parametrage.AccountingAccountResolver.CompteResolu;
import com.example.comptable.comptabilite.parametrage.CompteNonUtilisableException;
import com.example.comptable.comptabilite.parametrage.NiveauConfiance;
import com.example.comptable.comptabilite.parametrage.ResolvedAccount;
import com.example.comptable.comptabilite.service.CompteComptableValidationService;
import com.example.comptable.comptabilite.repository.CompteComptableRepository;
import com.example.comptable.comptabilite.domain.CompteComptable;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** Les codes machine de refus d'imputation viennent de la resolution de compte, jamais du texte de l'explication. */
class CodesRefusStructuresTest {

    @Test
    void chaque_code_de_resolution_se_rattache_a_un_code_de_controle() {
        assertThat(AccountResolutionCode.ACCOUNT_NOT_CONFIGURED.controlCode()).isEqualTo(ControlCodes.ACCOUNT_NOT_CONFIGURED);
        assertThat(AccountResolutionCode.JOURNAL_NOT_CONFIGURED.controlCode()).isEqualTo(ControlCodes.ACCOUNT_NOT_CONFIGURED);
        assertThat(AccountResolutionCode.ACCOUNT_MAPPING_AMBIGUOUS.controlCode()).isEqualTo(ControlCodes.ACCOUNT_AMBIGUOUS);
        for (AccountResolutionCode c : List.of(AccountResolutionCode.ACCOUNT_INACTIVE, AccountResolutionCode.ACCOUNT_NOT_IN_PCE,
                AccountResolutionCode.ACCOUNT_CONTEXT_INVALID, AccountResolutionCode.ACCOUNT_RESOLUTION_FAILED)) {
            assertThat(c.controlCode()).isEqualTo(ControlCodes.ACCOUNT_INVALID);
        }
    }

    @Test
    void la_resolution_porte_son_code_independamment_du_texte() {
        assertThat(ResolvedAccount.nonConfigure("texte quelconque").code()).isEqualTo(AccountResolutionCode.ACCOUNT_NOT_CONFIGURED);
        assertThat(ResolvedAccount.ambigu("texte quelconque").code()).isEqualTo(AccountResolutionCode.ACCOUNT_MAPPING_AMBIGUOUS);
        assertThat(ResolvedAccount.invalide("x", AccountResolutionCode.ACCOUNT_INACTIVE).code()).isEqualTo(AccountResolutionCode.ACCOUNT_INACTIVE);
        assertThat(new ResolvedAccount(AccountResolutionStatus.RESOLVED, UUID.randomUUID(), "601", "Achats", AccountResolutionSource.MAPPING_GENERIQUE,
                NiveauConfiance.CERTAIN, "ok").code()).isNull();
    }

    @Test
    void un_compte_desactive_ou_inconnu_leve_une_exception_typee_qui_reste_une_ResponseStatusException() {
        CompteComptableRepository repository = mock(CompteComptableRepository.class);
        CompteComptableValidationService service = new CompteComptableValidationService(repository);
        UUID entreprise = UUID.randomUUID();
        when(repository.findById(any())).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.chargerCompteUtilisable(entreprise, UUID.randomUUID(), true))
                .isInstanceOfSatisfying(CompteNonUtilisableException.class, e -> {
                    assertThat(e.code()).isEqualTo(AccountResolutionCode.ACCOUNT_NOT_IN_PCE);
                    assertThat(e.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
                });
    }

    @Test
    void les_explications_conservent_un_code_aligne_et_les_autres_n_en_ont_pas() {
        Blocages b = new Blocages();
        b.add("TVA non calculee");
        b.ajouter(AccountResolutionCode.ACCOUNT_MAPPING_AMBIGUOUS, "Compte (AMBIGUOUS) bla");
        b.add("Escompte non gere");

        ResultatRegle resultat = new ResultatRegle(TypeJournal.ACHATS, List.of(), b, List.of());

        assertThat(resultat.explications()).hasSize(3);
        assertThat(resultat.codes()).containsExactly(null, AccountResolutionCode.ACCOUNT_MAPPING_AMBIGUOUS, null);
        // une liste ordinaire reste valide : aucun code, jamais deduit du texte
        assertThat(new ResultatRegle(TypeJournal.ACHATS, List.of(), List.of("Compte (AMBIGUOUS) bla"), List.of()).codes()).containsExactly((AccountResolutionCode) null);
    }
}
