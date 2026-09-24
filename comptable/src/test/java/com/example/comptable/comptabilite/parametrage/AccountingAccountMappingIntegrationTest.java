package com.example.comptable.comptabilite.parametrage;

import com.example.comptable.audit.domain.AuditEvent;
import com.example.comptable.audit.repository.AuditEventRepository;
import com.example.comptable.comptabilite.domain.CompteComptable;
import com.example.comptable.comptabilite.domain.TypeFacture;
import com.example.comptable.comptabilite.domain.TypeTiers;
import com.example.comptable.comptabilite.operation.achat.AchatMultiLigneOperationRequest;
import com.example.comptable.comptabilite.operation.achat.LigneAchatOperationRequest;
import com.example.comptable.comptabilite.operation.achat.NatureAchat;
import com.example.comptable.comptabilite.operation.common.AccountingOperationEngine;
import com.example.comptable.comptabilite.operation.common.AccountingOperationType;
import com.example.comptable.comptabilite.operation.common.MoyenPaiement;
import com.example.comptable.comptabilite.operation.common.web.dto.AccountingOperationProposalDto;
import com.example.comptable.comptabilite.operation.reglement.ReglementOperationRequest;
import com.example.comptable.comptabilite.parametrage.domain.AccountPurpose;
import com.example.comptable.comptabilite.parametrage.service.AccountingAccountMappingService;
import com.example.comptable.comptabilite.parametrage.web.dto.AccountingAccountMappingDto;
import com.example.comptable.comptabilite.parametrage.web.dto.CreerAccountingAccountMappingRequest;
import com.example.comptable.comptabilite.parametrage.web.dto.ModifierAccountingAccountMappingRequest;
import com.example.comptable.comptabilite.parametrage.web.dto.ResolvedAccountDto;
import com.example.comptable.comptabilite.parametrage.web.dto.TestResolutionRequest;
import com.example.comptable.comptabilite.repository.CompteComptableRepository;
import com.example.comptable.comptabilite.service.FacturationService;
import com.example.comptable.comptabilite.service.PlanComptableService;
import com.example.comptable.comptabilite.service.TiersService;
import com.example.comptable.comptabilite.service.EcritureComptableService;
import com.example.comptable.comptabilite.web.dto.CompteComptableDto;
import com.example.comptable.comptabilite.web.dto.CreerFactureRequest;
import com.example.comptable.comptabilite.web.dto.CreerSubdivisionCompteRequest;
import com.example.comptable.comptabilite.web.dto.EcritureComptableDto;
import com.example.comptable.comptabilite.web.dto.FactureDto;
import com.example.comptable.comptabilite.web.dto.LigneFactureRequest;
import com.example.comptable.comptabilite.web.dto.ModifierCompteRequest;
import com.example.comptable.comptabilite.web.dto.TiersDto;
import com.example.comptable.entreprise.service.EntrepriseService;
import com.example.comptable.entreprise.web.dto.CreerEntrepriseRequest;
import com.example.comptable.entreprise.web.dto.EntrepriseDto;
import com.example.comptable.identite.domain.Role;
import com.example.comptable.identite.domain.Tenant;
import com.example.comptable.identite.domain.Utilisateur;
import com.example.comptable.identite.repository.RoleRepository;
import com.example.comptable.identite.repository.TenantRepository;
import com.example.comptable.identite.repository.UtilisateurRepository;
import com.example.comptable.security.UtilisateurPrincipal;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.web.server.ResponseStatusException;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Verifie de bout en bout, sur PostgreSQL reel (meme convention que
 * {@link com.example.comptable.comptabilite.operation.AccountingOperationEngineIntegrationTest}),
 * le scenario complet du parametrage comptable par entreprise (mission Etape 6, section 28) :
 * deux entreprises avec des mappings differents pour le meme purpose, resolution effectivement
 * utilisee par un achat multi-ligne / une facture (TVA) / un reglement, desactivation d'un compte
 * mappe -&gt; resolution INVALID -&gt; operation refusee -&gt; reconfiguration -&gt; rejeu reussi, et
 * isolation stricte entre entreprises (mapping, resolution, et acces cross-tenant refuse).
 */
@SpringBootTest(properties = "spring.docker.compose.skip.in-tests=false")
class AccountingAccountMappingIntegrationTest {

    @Autowired
    private TenantRepository tenantRepository;
    @Autowired
    private UtilisateurRepository utilisateurRepository;
    @Autowired
    private RoleRepository roleRepository;
    @Autowired
    private EntrepriseService entrepriseService;
    @Autowired
    private TiersService tiersService;
    @Autowired
    private CompteComptableRepository compteComptableRepository;
    @Autowired
    private PlanComptableService planComptableService;
    @Autowired
    private AccountingAccountMappingService accountingAccountMappingService;
    @Autowired
    private AccountingOperationEngine accountingOperationEngine;
    @Autowired
    private FacturationService facturationService;
    @Autowired
    private EcritureComptableService ecritureComptableService;
    @Autowired
    private AuditEventRepository auditEventRepository;

    @Test
    void scenario_complet_deux_entreprises_mappings_differents_desactivation_et_isolation() {
        UtilisateurPrincipal principal = creerPrincipalExpertComptable();

        // 1-3. Deux entreprises, chacune avec son PCE auto-provisionne depuis le referentiel.
        EntrepriseDto entrepriseA = creerEntreprise(principal, "Parametrage Scenario A");
        EntrepriseDto entrepriseB = creerEntreprise(principal, "Parametrage Scenario B");

        // 4. Comptes reels du PCE de chaque entreprise (deja importes, aucun compte invente).
        CompteComptable compteServiceA = compte(entrepriseA.id(), "621");
        CompteComptable compteServiceB = compte(entrepriseB.id(), "611");
        CompteComptable compteTvaAlternatifA = compte(entrepriseA.id(), "441");
        CompteComptable compteBanqueA = compte(entrepriseA.id(), "521");
        CompteComptable compteVenteA = compte(entrepriseA.id(), "701");

        CompteComptableDto banqueSecondaireA = planComptableService.creerSubdivision(entrepriseA.id(),
                new CreerSubdivisionCompteRequest("5212", "Banque secondaire", false, compteBanqueA.getId()), principal);

        // 5. Mappings de l'entreprise A : achat service, TVA collectee et banque surcharges.
        AccountingAccountMappingDto mappingServiceA = accountingAccountMappingService.creer(entrepriseA.id(),
                new CreerAccountingAccountMappingRequest(AccountPurpose.ACHAT_SERVICE, null, compteServiceA.getId(), null), principal);
        accountingAccountMappingService.creer(entrepriseA.id(),
                new CreerAccountingAccountMappingRequest(AccountPurpose.TVA_COLLECTEE, null, compteTvaAlternatifA.getId(), null), principal);
        accountingAccountMappingService.creer(entrepriseA.id(),
                new CreerAccountingAccountMappingRequest(AccountPurpose.BANQUE, null, banqueSecondaireA.id(), null), principal);

        // 6. Mapping de l'entreprise B pour le meme purpose, vers un compte different.
        accountingAccountMappingService.creer(entrepriseB.id(),
                new CreerAccountingAccountMappingRequest(AccountPurpose.ACHAT_SERVICE, null, compteServiceB.getId(), null), principal);

        // 7-8. Resolution testee pour chaque entreprise : chacune obtient son propre compte.
        ResolvedAccountDto resolutionServiceA = testerResolution(entrepriseA.id(), AccountPurpose.ACHAT_SERVICE, principal);
        assertThat(resolutionServiceA.status().name()).isEqualTo("RESOLVED");
        assertThat(resolutionServiceA.compteNumero()).isEqualTo("621");
        assertThat(resolutionServiceA.source().name()).isEqualTo("MAPPING_GENERIQUE");

        ResolvedAccountDto resolutionServiceB = testerResolution(entrepriseB.id(), AccountPurpose.ACHAT_SERVICE, principal);
        assertThat(resolutionServiceB.compteNumero()).isEqualTo("611");

        // B n'a jamais configure TVA_COLLECTEE : retombe sur le role systeme historique (443),
        // jamais sur le mapping de A - preuve directe d'isolation, pas seulement une absence d'erreur.
        ResolvedAccountDto resolutionTvaB = testerResolution(entrepriseB.id(), AccountPurpose.TVA_COLLECTEE, principal);
        assertThat(resolutionTvaB.compteNumero()).isEqualTo("443");
        assertThat(resolutionTvaB.source().name()).isEqualTo("ROLE_SYSTEME");

        // 9. Achat multi-ligne A, ligne SERVICE sans compte fourni -> resolu via le mapping (621).
        TiersDto fournisseurA = tiersService.creer(entrepriseA.id(), TypeTiers.FOURNISSEUR, "Prestataire A", null, principal);
        AccountingOperationProposalDto achatA = accountingOperationEngine.proposerAchatEtValiderImmediatement(entrepriseA.id(),
                new AchatMultiLigneOperationRequest(fournisseurA.id(), LocalDate.now(), "FA-PARAM-A",
                        "Prestation de conseil", List.of(new LigneAchatOperationRequest(
                                "Conseil", NatureAchat.SERVICE, new BigDecimal("100000"), new BigDecimal("18"), null, null)),
                        null, "TEST_PARAM", UUID.randomUUID()),
                principal);
        assertThat(achatA.statut()).isEqualTo("APPROVED");
        assertThat(achatA.lignes()).anySatisfy(l -> assertThat(l.compteNumero()).isEqualTo("621"));

        // 10. Vente A (facture avec TVA) -> la ligne de TVA collectee utilise le mapping (441),
        // jamais le compte systeme par defaut (443).
        TiersDto clientA = tiersService.creer(entrepriseA.id(), TypeTiers.CLIENT, "Client A", null, principal);
        FactureDto facture = facturationService.creer(entrepriseA.id(), TypeFacture.FACTURE,
                new CreerFactureRequest(clientA.id(), LocalDate.now(), null, "Prestation",
                        List.of(new LigneFactureRequest("Prestation", BigDecimal.ONE, new BigDecimal("50000"),
                                new BigDecimal("18"), null))),
                principal);
        facture = facturationService.validerFacture(entrepriseA.id(), facture.id(), compteVenteA.getId(), principal);
        assertThat(facture.statut()).isEqualTo("VALIDEE");
        EcritureComptableDto ecritureVente = ecritureComptableService.obtenir(entrepriseA.id(), facture.ecritureId(), principal);
        assertThat(ecritureVente.lignes()).anySatisfy(l -> assertThat(l.compteNumero()).isEqualTo("441"));
        assertThat(ecritureVente.lignes()).noneSatisfy(l -> assertThat(l.compteNumero()).isEqualTo("443"));

        // 11. Reglement client A par virement -> la tresorerie utilise la banque secondaire mappee (5212).
        AccountingOperationProposalDto reglementPropose = accountingOperationEngine.proposerReglement(entrepriseA.id(),
                new ReglementOperationRequest(AccountingOperationType.REGLEMENT_CLIENT, clientA.id(), LocalDate.now(),
                        "REG-PARAM-A", "Reglement prestation", facture.montantTtc(), MoyenPaiement.VIREMENT),
                principal);
        assertThat(reglementPropose.lignes()).anySatisfy(l -> assertThat(l.compteNumero()).isEqualTo("5212"));
        AccountingOperationProposalDto reglementValide = accountingOperationEngine.valider(entrepriseA.id(), reglementPropose.id(), principal);
        assertThat(reglementValide.statut()).isEqualTo("APPROVED");

        // 12. Desactivation du compte 621 (utilise par le mapping ACHAT_SERVICE de A) : jamais
        // bloquee, mais le nombre de mappings actifs concernes est renvoye pour avertir le cabinet.
        CompteComptableDto compteDesactive = planComptableService.modifier(entrepriseA.id(), compteServiceA.getId(),
                new ModifierCompteRequest(null, false), principal);
        assertThat(compteDesactive.actif()).isFalse();
        assertThat(compteDesactive.mappingsActifsCount()).isEqualTo(1);

        // 13. Le mapping n'est jamais supprime silencieusement : la resolution devient INVALID.
        ResolvedAccountDto resolutionApresDesactivation = testerResolution(entrepriseA.id(), AccountPurpose.ACHAT_SERVICE, principal);
        assertThat(resolutionApresDesactivation.status().name()).isEqualTo("INVALID");

        // 14. Tant que le compte reste desactive, l'operation qui en depend est refusee (jamais
        // silencieusement acceptee avec un compte invalide, jamais un compte devine a la place).
        assertThatThrownBy(() -> accountingOperationEngine.proposerAchatEtValiderImmediatement(entrepriseA.id(),
                new AchatMultiLigneOperationRequest(fournisseurA.id(), LocalDate.now(), "FA-PARAM-A-2",
                        "Prestation de conseil (compte desactive)", List.of(new LigneAchatOperationRequest(
                                "Conseil", NatureAchat.SERVICE, new BigDecimal("50000"), new BigDecimal("18"), null, null)),
                        null, "TEST_PARAM", UUID.randomUUID()),
                principal))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("Proposition incomplete");

        // 15. Reconfiguration : le cabinet reactive le compte -> le mapping redevient utilisable.
        CompteComptableDto compteReactive = planComptableService.modifier(entrepriseA.id(), compteServiceA.getId(),
                new ModifierCompteRequest(null, true), principal);
        assertThat(compteReactive.actif()).isTrue();
        ResolvedAccountDto resolutionApresReactivation = testerResolution(entrepriseA.id(), AccountPurpose.ACHAT_SERVICE, principal);
        assertThat(resolutionApresReactivation.status().name()).isEqualTo("RESOLVED");
        assertThat(resolutionApresReactivation.compteNumero()).isEqualTo("621");

        // 16. Rejeu de la meme operation : elle reussit desormais.
        AccountingOperationProposalDto achatRejoue = accountingOperationEngine.proposerAchatEtValiderImmediatement(entrepriseA.id(),
                new AchatMultiLigneOperationRequest(fournisseurA.id(), LocalDate.now(), "FA-PARAM-A-3",
                        "Prestation de conseil (rejeu)", List.of(new LigneAchatOperationRequest(
                                "Conseil", NatureAchat.SERVICE, new BigDecimal("50000"), new BigDecimal("18"), null, null)),
                        null, "TEST_PARAM", UUID.randomUUID()),
                principal);
        assertThat(achatRejoue.statut()).isEqualTo("APPROVED");
        assertThat(achatRejoue.lignes()).anySatisfy(l -> assertThat(l.compteNumero()).isEqualTo("621"));

        // 17. Isolation stricte : la liste des mappings de B ne contient jamais ceux de A.
        List<AccountingAccountMappingDto> mappingsB = accountingAccountMappingService.lister(entrepriseB.id(), null, principal);
        assertThat(mappingsB).extracting(AccountingAccountMappingDto::compteNumero).doesNotContain("5212", "441");

        // 18. Un mapping de B ne peut jamais pointer vers un compte de A (refuse, jamais accepte
        // silencieusement) : meme discipline que le reste du moteur (tiers/comptes cross-tenant).
        assertThatThrownBy(() -> accountingAccountMappingService.creer(entrepriseB.id(),
                new CreerAccountingAccountMappingRequest(AccountPurpose.BANQUE, null, banqueSecondaireA.id(), null), principal))
                .isInstanceOfSatisfying(ResponseStatusException.class,
                        e -> assertThat(e.getStatusCode().value()).isEqualTo(404));

        // 19. Validation section 22 : un compte incompatible avec la classe attendue du purpose
        // est refuse (422), jamais enregistre.
        CompteComptable compteVenteAPourBanque = compte(entrepriseA.id(), "701");
        assertThatThrownBy(() -> accountingAccountMappingService.creer(entrepriseA.id(),
                new CreerAccountingAccountMappingRequest(AccountPurpose.BANQUE, null, compteVenteAPourBanque.getId(), null), principal))
                .isInstanceOfSatisfying(ResponseStatusException.class,
                        e -> assertThat(e.getStatusCode().value()).isEqualTo(422));

        // 20. Un second mapping actif generique pour le meme purpose est refuse (409) : jamais
        // deux comptes actifs en concurrence pour la meme resolution.
        assertThatThrownBy(() -> accountingAccountMappingService.creer(entrepriseA.id(),
                new CreerAccountingAccountMappingRequest(AccountPurpose.ACHAT_SERVICE, null, compteServiceA.getId(), null), principal))
                .isInstanceOfSatisfying(ResponseStatusException.class,
                        e -> assertThat(e.getStatusCode().value()).isEqualTo(409));

        // 21. Retrait explicite d'un mapping (jamais une simple desactivation de compte) :
        // la resolution retombe alors sur ce que le compte systeme historique offre (ou NOT_CONFIGURED).
        accountingAccountMappingService.supprimer(entrepriseA.id(), mappingServiceA.id(), principal);
        ResolvedAccountDto resolutionApresRetrait = testerResolution(entrepriseA.id(), AccountPurpose.ACHAT_SERVICE, principal);
        assertThat(resolutionApresRetrait.status().name()).isEqualTo("NOT_CONFIGURED");

        // 22. Chaque changement de parametrage est trace (jamais un second systeme d'audit).
        List<AuditEvent> evenements = auditEventRepository
                .findByEntiteAndEntiteIdOrderByCreatedAtDesc("AccountingAccountMapping", mappingServiceA.id().toString());
        assertThat(evenements).extracting(AuditEvent::getAction).contains("CREATE_MAPPING", "DELETE_MAPPING");
    }

    @Test
    void modifier_un_mapping_pour_le_reactiver_revalide_labsence_de_doublon() {
        UtilisateurPrincipal principal = creerPrincipalExpertComptable();
        EntrepriseDto entreprise = creerEntreprise(principal, "Parametrage Reactivation");
        CompteComptable compteA = compte(entreprise.id(), "621");
        CompteComptable compteB = compte(entreprise.id(), "611");

        AccountingAccountMappingDto mappingA = accountingAccountMappingService.creer(entreprise.id(),
                new CreerAccountingAccountMappingRequest(AccountPurpose.ACHAT_SERVICE, null, compteA.getId(), null), principal);
        accountingAccountMappingService.modifier(entreprise.id(), mappingA.id(),
                new ModifierAccountingAccountMappingRequest(null, null, false), principal);

        AccountingAccountMappingDto mappingB = accountingAccountMappingService.creer(entreprise.id(),
                new CreerAccountingAccountMappingRequest(AccountPurpose.ACHAT_SERVICE, null, compteB.getId(), null), principal);

        // Reactiver A alors que B est deja actif pour le meme purpose doit etre refuse (409) -
        // jamais deux mappings actifs en concurrence, meme via une reactivation plutot qu'une creation.
        assertThatThrownBy(() -> accountingAccountMappingService.modifier(entreprise.id(), mappingA.id(),
                new ModifierAccountingAccountMappingRequest(null, null, true), principal))
                .isInstanceOfSatisfying(ResponseStatusException.class,
                        e -> assertThat(e.getStatusCode().value()).isEqualTo(409));

        ResolvedAccountDto resolution = testerResolution(entreprise.id(), AccountPurpose.ACHAT_SERVICE, principal);
        assertThat(resolution.compteNumero()).isEqualTo(mappingB.compteNumero());
    }

    private ResolvedAccountDto testerResolution(UUID entrepriseId, AccountPurpose purpose, UtilisateurPrincipal principal) {
        return accountingAccountMappingService.testerResolution(entrepriseId,
                new TestResolutionRequest(purpose, null, null, null), principal);
    }

    private CompteComptable compte(UUID entrepriseId, String numero) {
        return compteComptableRepository.findByEntrepriseIdAndNumero(entrepriseId, numero).orElseThrow();
    }

    private EntrepriseDto creerEntreprise(UtilisateurPrincipal principal, String raisonSociale) {
        return entrepriseService.creer(
                new CreerEntrepriseRequest(raisonSociale, null, null, null, null, null, "CI", "XOF"),
                principal);
    }

    private UtilisateurPrincipal creerPrincipalExpertComptable() {
        Tenant tenant = new Tenant();
        tenant.setRaisonSociale("Cabinet Parametrage Test");
        tenant.setSlug("cabinet-parametrage-" + UUID.randomUUID());
        tenant.setPays("CI");
        tenant = tenantRepository.save(tenant);

        Role role = roleRepository.findByCode("EXPERT_COMPTABLE").orElseThrow();

        Utilisateur utilisateur = new Utilisateur();
        utilisateur.setTenant(tenant);
        utilisateur.setEmail("parametrage-" + UUID.randomUUID() + "@test.ci");
        utilisateur.setMotDePasse("hash");
        utilisateur.setNom("Test");
        utilisateur.setPrenom("Parametrage");
        utilisateur.setRoles(Set.of(role));
        utilisateur = utilisateurRepository.save(utilisateur);

        return new UtilisateurPrincipal(utilisateur);
    }
}
