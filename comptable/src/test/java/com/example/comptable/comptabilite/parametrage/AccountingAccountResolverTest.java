package com.example.comptable.comptabilite.parametrage;

import com.example.comptable.comptabilite.domain.CompteComptable;
import com.example.comptable.comptabilite.domain.RoleCompteSysteme;
import com.example.comptable.comptabilite.parametrage.domain.AccountPurpose;
import com.example.comptable.comptabilite.parametrage.domain.AccountingAccountMapping;
import com.example.comptable.comptabilite.parametrage.repository.AccountingAccountMappingRepository;
import com.example.comptable.comptabilite.service.CompteComptableValidationService;
import com.example.comptable.comptabilite.repository.CompteComptableRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Verifie la priorite deterministe de resolution (mission Etape 6, section 8-9) : explicite &gt;
 * mapping contextuel &gt; mapping generique &gt; role systeme &gt; NOT_CONFIGURED - jamais un choix
 * arbitraire en cas d'ambiguite, jamais une resolution silencieuse sur un compte desactive.
 */
@ExtendWith(MockitoExtension.class)
class AccountingAccountResolverTest {

    @Mock
    private AccountingAccountMappingRepository mappingRepository;
    @Mock
    private CompteComptableRepository compteComptableRepository;
    @Mock
    private CompteComptableValidationService compteComptableValidationService;

    @InjectMocks
    private AccountingAccountResolver resolver;

    private UUID entrepriseId;

    @BeforeEach
    void setUp() {
        entrepriseId = UUID.randomUUID();
    }

    private CompteComptable compte(String numero) {
        CompteComptable c = new CompteComptable();
        c.setId(UUID.randomUUID());
        c.setNumero(numero);
        c.setIntitule("Intitule " + numero);
        c.setActif(true);
        return c;
    }

    private AccountingAccountMapping mapping(CompteComptable compte, AccountPurpose purpose, String contextKey) {
        AccountingAccountMapping m = new AccountingAccountMapping();
        m.setId(UUID.randomUUID());
        m.setPurpose(purpose);
        m.setContextKey(contextKey);
        m.setCompte(compte);
        m.setActif(true);
        return m;
    }

    @Test
    void compte_explicite_gagne_toujours_sans_interroger_les_mappings() {
        CompteComptable compte = compte("601");
        when(compteComptableValidationService.chargerCompteUtilisable(entrepriseId, compte.getId(), true)).thenReturn(compte);

        ResolvedAccount resultat = resolver.resolve(entrepriseId, AccountPurpose.ACHAT_MARCHANDISE, AccountContext.vide(), compte.getId());

        assertThat(resultat.status()).isEqualTo(AccountResolutionStatus.RESOLVED);
        assertThat(resultat.source()).isEqualTo(AccountResolutionSource.EXPLICITE);
        assertThat(resultat.compteNumero()).isEqualTo("601");
        verify(mappingRepository, never()).findByEntrepriseIdAndPurposeAndContextKeyIsNullAndActifTrue(any(), any());
    }

    @Test
    void mapping_generique_entreprise_resout_le_compte() {
        CompteComptable compte = compte("601200");
        when(mappingRepository.findByEntrepriseIdAndPurposeAndContextKeyIsNullAndActifTrue(entrepriseId, AccountPurpose.ACHAT_MARCHANDISE))
                .thenReturn(List.of(mapping(compte, AccountPurpose.ACHAT_MARCHANDISE, null)));
        when(compteComptableValidationService.chargerCompteUtilisable(entrepriseId, compte.getId(), true)).thenReturn(compte);

        ResolvedAccount resultat = resolver.resolve(entrepriseId, AccountPurpose.ACHAT_MARCHANDISE, AccountContext.vide(), null);

        assertThat(resultat.status()).isEqualTo(AccountResolutionStatus.RESOLVED);
        assertThat(resultat.source()).isEqualTo(AccountResolutionSource.MAPPING_GENERIQUE);
        assertThat(resultat.compteNumero()).isEqualTo("601200");
    }

    @Test
    void mapping_specifique_au_contexte_prime_sur_le_mapping_generique() {
        CompteComptable compteGenerique = compte("571");
        CompteComptable compteEspeces = compte("571100");
        when(mappingRepository.findByEntrepriseIdAndPurposeAndContextKeyAndActifTrue(entrepriseId, AccountPurpose.CAISSE, "ESPECES"))
                .thenReturn(List.of(mapping(compteEspeces, AccountPurpose.CAISSE, "ESPECES")));
        when(compteComptableValidationService.chargerCompteUtilisable(entrepriseId, compteEspeces.getId(), true)).thenReturn(compteEspeces);

        ResolvedAccount resultat = resolver.resolve(entrepriseId, AccountPurpose.CAISSE, AccountContext.vide().avecContextKey("ESPECES"), null);

        assertThat(resultat.status()).isEqualTo(AccountResolutionStatus.RESOLVED);
        assertThat(resultat.source()).isEqualTo(AccountResolutionSource.MAPPING_CONTEXTUEL);
        assertThat(resultat.compteNumero()).isEqualTo("571100");
        verify(mappingRepository, never()).findByEntrepriseIdAndPurposeAndContextKeyIsNullAndActifTrue(any(), any());
    }

    @Test
    void fallback_role_systeme_utilise_quand_aucun_mapping_entreprise_nexiste() {
        CompteComptable compteRole = compte("4456");
        when(mappingRepository.findByEntrepriseIdAndPurposeAndContextKeyIsNullAndActifTrue(entrepriseId, AccountPurpose.TVA_RECUPERABLE))
                .thenReturn(List.of());
        when(compteComptableRepository.findByEntrepriseIdAndReferenceRoleSysteme(entrepriseId, RoleCompteSysteme.TVA_DEDUCTIBLE)).thenReturn(Optional.of(compteRole));
        when(compteComptableValidationService.chargerCompteUtilisable(entrepriseId, compteRole.getId(), true)).thenReturn(compteRole);

        ResolvedAccount resultat = resolver.resolve(entrepriseId, AccountPurpose.TVA_RECUPERABLE, AccountContext.vide(), null);

        assertThat(resultat.status()).isEqualTo(AccountResolutionStatus.RESOLVED);
        assertThat(resultat.source()).isEqualTo(AccountResolutionSource.ROLE_SYSTEME);
        assertThat(resultat.compteNumero()).isEqualTo("4456");
    }

    @Test
    void aucun_mapping_ni_role_systeme_est_not_configured_jamais_devine() {
        when(mappingRepository.findByEntrepriseIdAndPurposeAndContextKeyIsNullAndActifTrue(entrepriseId, AccountPurpose.ACHAT_SERVICE))
                .thenReturn(List.of());

        ResolvedAccount resultat = resolver.resolve(entrepriseId, AccountPurpose.ACHAT_SERVICE, AccountContext.vide(), null);

        assertThat(resultat.status()).isEqualTo(AccountResolutionStatus.NOT_CONFIGURED);
        assertThat(resultat.compteId()).isNull();
        assertThat(resultat.confiance()).isEqualTo(NiveauConfiance.NON_APPLICABLE);
    }

    @Test
    void plusieurs_mappings_actifs_de_meme_rang_produisent_ambiguous_jamais_un_choix_arbitraire() {
        CompteComptable compteA = compte("601100");
        CompteComptable compteB = compte("601200");
        when(mappingRepository.findByEntrepriseIdAndPurposeAndContextKeyIsNullAndActifTrue(entrepriseId, AccountPurpose.ACHAT_MARCHANDISE))
                .thenReturn(List.of(
                        mapping(compteA, AccountPurpose.ACHAT_MARCHANDISE, null),
                        mapping(compteB, AccountPurpose.ACHAT_MARCHANDISE, null)));

        ResolvedAccount resultat = resolver.resolve(entrepriseId, AccountPurpose.ACHAT_MARCHANDISE, AccountContext.vide(), null);

        assertThat(resultat.status()).isEqualTo(AccountResolutionStatus.AMBIGUOUS);
        assertThat(resultat.compteId()).isNull();
        verify(compteComptableValidationService, never()).chargerCompteUtilisable(any(), any(), eq(true));
    }

    @Test
    void mapping_pointant_vers_un_compte_desactive_est_invalid_jamais_ignore_silencieusement() {
        CompteComptable compte = compte("601");
        when(mappingRepository.findByEntrepriseIdAndPurposeAndContextKeyIsNullAndActifTrue(entrepriseId, AccountPurpose.ACHAT_MARCHANDISE))
                .thenReturn(List.of(mapping(compte, AccountPurpose.ACHAT_MARCHANDISE, null)));
        when(compteComptableValidationService.chargerCompteUtilisable(entrepriseId, compte.getId(), true))
                .thenThrow(new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY, "Le compte 601 est desactive"));

        ResolvedAccount resultat = resolver.resolve(entrepriseId, AccountPurpose.ACHAT_MARCHANDISE, AccountContext.vide(), null);

        assertThat(resultat.status()).isEqualTo(AccountResolutionStatus.INVALID);
        assertThat(resultat.compteId()).isNull();
    }

    @Test
    void la_resolution_est_toujours_scopee_a_lentreprise_demandee() {
        UUID autreEntrepriseId = UUID.randomUUID();
        when(mappingRepository.findByEntrepriseIdAndPurposeAndContextKeyIsNullAndActifTrue(entrepriseId, AccountPurpose.ACHAT_MARCHANDISE))
                .thenReturn(List.of());

        resolver.resolve(entrepriseId, AccountPurpose.ACHAT_MARCHANDISE, AccountContext.vide(), null);

        verify(mappingRepository).findByEntrepriseIdAndPurposeAndContextKeyIsNullAndActifTrue(entrepriseId, AccountPurpose.ACHAT_MARCHANDISE);
        verify(mappingRepository, never()).findByEntrepriseIdAndPurposeAndContextKeyIsNullAndActifTrue(eq(autreEntrepriseId), any());
    }
}
