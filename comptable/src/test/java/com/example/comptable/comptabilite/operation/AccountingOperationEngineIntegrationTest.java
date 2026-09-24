package com.example.comptable.comptabilite.operation;

import com.example.comptable.comptabilite.domain.CompteComptable;
import com.example.comptable.comptabilite.domain.TypeTiers;
import com.example.comptable.comptabilite.operation.achat.AchatOperationRequest;
import com.example.comptable.comptabilite.operation.achat.NatureAchat;
import com.example.comptable.comptabilite.operation.avoir.AvoirOperationRequest;
import com.example.comptable.comptabilite.operation.common.AccountingOperationEngine;
import com.example.comptable.comptabilite.operation.common.AccountingOperationType;
import com.example.comptable.comptabilite.operation.common.MoyenPaiement;
import com.example.comptable.comptabilite.operation.common.web.dto.AccountingOperationProposalDto;
import com.example.comptable.comptabilite.operation.reglement.ReglementOperationRequest;
import com.example.comptable.comptabilite.operation.vente.NatureVente;
import com.example.comptable.comptabilite.operation.vente.VenteOperationRequest;
import com.example.comptable.comptabilite.repository.CompteComptableRepository;
import com.example.comptable.comptabilite.web.dto.EcritureComptableDto;
import com.example.comptable.comptabilite.web.dto.TiersDto;
import com.example.comptable.comptabilite.service.EcritureComptableService;
import com.example.comptable.comptabilite.service.TiersService;
import com.example.comptable.entreprise.service.EntrepriseService;
import com.example.comptable.entreprise.web.dto.CreerEntrepriseRequest;
import com.example.comptable.entreprise.web.dto.EntrepriseDto;
import com.example.comptable.fiscalite.regles.domain.TaxRule;
import com.example.comptable.fiscalite.regles.repository.TaxRuleRepository;
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
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Verifie de bout en bout, sur PostgreSQL reel (meme convention que
 * {@link com.example.comptable.comptabilite.PlanComptableIntegrationTest}), le scenario complet
 * du moteur d'operations courantes : entreprise -&gt; PCE -&gt; tiers -&gt; proposition -&gt;
 * equilibre -&gt; validation humaine -&gt; ecriture persistee -&gt; solde de tresorerie, ainsi que
 * l'isolation stricte entre entreprises. La regle fiscale TVA 18% utilisee ici est une fixture de
 * demonstration explicitement marquee {@code FIXTURE_DEMO_NON_VERIFIE} (voir TvaRateResolver) :
 * elle ne pretend a aucune verification reglementaire, seule sa presence dans ce test le permet.
 */
@SpringBootTest(properties = "spring.docker.compose.skip.in-tests=false")
class AccountingOperationEngineIntegrationTest {

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
    private TaxRuleRepository taxRuleRepository;
    @Autowired
    private EcritureComptableService ecritureComptableService;
    @Autowired
    private AccountingOperationEngine engine;

    @Test
    void scenario_complet_achat_vente_reglement_avoir_avec_validation_humaine() {
        UtilisateurPrincipal principal = creerPrincipalExpertComptable();
        EntrepriseDto entreprise = creerEntreprise(principal, "Operations Scenario Complet");
        seedTvaDemo18Pourcent();

        TiersDto fournisseur = tiersService.creer(entreprise.id(), TypeTiers.FOURNISSEUR, "Fournisseur Test", null, principal);
        TiersDto client = tiersService.creer(entreprise.id(), TypeTiers.CLIENT, "Client Test", null, principal);

        CompteComptable compteAchats = compteComptableRepository.findByEntrepriseIdAndNumero(entreprise.id(), "601").orElseThrow();
        CompteComptable compteTvaDeductible = compteComptableRepository.findByEntrepriseIdAndNumero(entreprise.id(), "4456").orElseThrow();
        CompteComptable compteVentes = compteComptableRepository.findByEntrepriseIdAndNumero(entreprise.id(), "701").orElseThrow();
        CompteComptable compteTvaCollectee = compteComptableRepository.findByEntrepriseIdAndNumero(entreprise.id(), "443").orElseThrow();
        CompteComptable compteBanque = compteComptableRepository.findByEntrepriseIdAndNumero(entreprise.id(), "521").orElseThrow();

        // 1. Achat de marchandises avec TVA deductible -> proposition equilibree et complete.
        AccountingOperationProposalDto achatPropose = engine.proposerAchat(entreprise.id(),
                new AchatOperationRequest(NatureAchat.MARCHANDISE, fournisseur.id(), LocalDate.now(), "FA-2026-001",
                        "Achat marchandises test", new BigDecimal("100000"), compteAchats.getId(), null, null, null, null),
                principal);

        assertThat(achatPropose.statut()).isEqualTo("PROPOSED");
        assertThat(achatPropose.equilibree()).isTrue();
        assertThat(achatPropose.totalDebit()).isEqualByComparingTo("118000");
        assertThat(achatPropose.lignes()).anySatisfy(l -> assertThat(l.compteNumero()).isEqualTo(compteTvaDeductible.getNumero()));

        // 2. Validation humaine -> ecriture VALIDEE reellement persistee.
        AccountingOperationProposalDto achatValide = engine.valider(entreprise.id(), achatPropose.id(), principal);
        assertThat(achatValide.statut()).isEqualTo("APPROVED");
        assertThat(achatValide.ecritureId()).isNotNull();

        EcritureComptableDto ecritureAchat = ecritureComptableService.obtenir(entreprise.id(), achatValide.ecritureId(), principal);
        assertThat(ecritureAchat.totalDebit()).isEqualByComparingTo(ecritureAchat.totalCredit());
        assertThat(ecritureAchat.lignes()).hasSize(3);

        // 3. Vente de marchandises avec TVA collectee.
        AccountingOperationProposalDto ventePropose = engine.proposerVente(entreprise.id(),
                new VenteOperationRequest(NatureVente.VENTE_MARCHANDISE, client.id(), LocalDate.now(), "FV-2026-001",
                        "Vente marchandises test", new BigDecimal("200000"), compteVentes.getId(), null, null, null, null),
                principal);

        assertThat(ventePropose.statut()).isEqualTo("PROPOSED");
        assertThat(ventePropose.lignes()).anySatisfy(l -> assertThat(l.compteNumero()).isEqualTo(compteTvaCollectee.getNumero()));

        AccountingOperationProposalDto venteValide = engine.valider(entreprise.id(), ventePropose.id(), principal);
        assertThat(venteValide.statut()).isEqualTo("APPROVED");

        // 4. Reglement client par banque, sur le montant TTC de la vente.
        AccountingOperationProposalDto reglementPropose = engine.proposerReglement(entreprise.id(),
                new ReglementOperationRequest(AccountingOperationType.REGLEMENT_CLIENT, client.id(), LocalDate.now(),
                        "REG-2026-001", "Reglement client test", new BigDecimal("236000"), MoyenPaiement.VIREMENT),
                principal);

        assertThat(reglementPropose.statut()).isEqualTo("PROPOSED");
        assertThat(reglementPropose.lignes()).anySatisfy(l -> assertThat(l.compteNumero()).isEqualTo(compteBanque.getNumero()));

        AccountingOperationProposalDto reglementValide = engine.valider(entreprise.id(), reglementPropose.id(), principal);
        assertThat(reglementValide.statut()).isEqualTo("APPROVED");

        // 5. Avoir sur la vente (retour partiel de marchandises) -> lignes inversees, equilibrees.
        AccountingOperationProposalDto avoirPropose = engine.proposerAvoir(entreprise.id(),
                new AvoirOperationRequest(AccountingOperationType.AVOIR_VENTE, client.id(), LocalDate.now(), "AV-2026-001",
                        "Avoir sur vente test", new BigDecimal("20000"), compteVentes.getId(), null, null),
                principal);

        assertThat(avoirPropose.statut()).isEqualTo("PROPOSED");
        assertThat(avoirPropose.equilibree()).isTrue();
        assertThat(avoirPropose.lignes()).anySatisfy(l -> {
            assertThat(l.compteNumero()).isEqualTo(compteVentes.getNumero());
            assertThat(l.debit()).isEqualByComparingTo("20000");
        });

        AccountingOperationProposalDto avoirValide = engine.valider(entreprise.id(), avoirPropose.id(), principal);
        assertThat(avoirValide.statut()).isEqualTo("APPROVED");

        EcritureComptableDto ecritureAvoir = ecritureComptableService.obtenir(entreprise.id(), avoirValide.ecritureId(), principal);
        assertThat(ecritureAvoir.totalDebit()).isEqualByComparingTo(ecritureAvoir.totalCredit());
    }

    @Test
    void un_tiers_dune_autre_entreprise_est_refuse_par_le_moteur() {
        UtilisateurPrincipal principal = creerPrincipalExpertComptable();
        EntrepriseDto entrepriseA = creerEntreprise(principal, "Isolation Tenant A");
        EntrepriseDto entrepriseB = creerEntreprise(principal, "Isolation Tenant B");

        TiersDto fournisseurDeB = tiersService.creer(entrepriseB.id(), TypeTiers.FOURNISSEUR, "Fournisseur B", null, principal);
        CompteComptable compteAchatsA = compteComptableRepository.findByEntrepriseIdAndNumero(entrepriseA.id(), "601").orElseThrow();

        assertThatThrownBy(() -> engine.proposerAchat(entrepriseA.id(),
                new AchatOperationRequest(NatureAchat.MARCHANDISE, fournisseurDeB.id(), LocalDate.now(), "FA-X",
                        "Achat cross-tenant", new BigDecimal("1000"), compteAchatsA.getId(), null, null, null, null),
                principal))
                .isInstanceOfSatisfying(ResponseStatusException.class,
                        e -> assertThat(e.getStatusCode().value()).isEqualTo(404));
    }

    @Test
    void une_proposition_dune_autre_entreprise_est_invisible_meme_par_id_direct() {
        UtilisateurPrincipal principal = creerPrincipalExpertComptable();
        EntrepriseDto entrepriseA = creerEntreprise(principal, "Isolation Proposition A");
        EntrepriseDto entrepriseB = creerEntreprise(principal, "Isolation Proposition B");
        seedTvaDemo18Pourcent();

        TiersDto fournisseurA = tiersService.creer(entrepriseA.id(), TypeTiers.FOURNISSEUR, "Fournisseur A", null, principal);
        CompteComptable compteAchatsA = compteComptableRepository.findByEntrepriseIdAndNumero(entrepriseA.id(), "601").orElseThrow();

        AccountingOperationProposalDto proposition = engine.proposerAchat(entrepriseA.id(),
                new AchatOperationRequest(NatureAchat.MARCHANDISE, fournisseurA.id(), LocalDate.now(), "FA-Y",
                        "Achat entreprise A", new BigDecimal("1000"), compteAchatsA.getId(), null, null, null, null),
                principal);

        assertThatThrownBy(() -> engine.obtenir(entrepriseB.id(), proposition.id(), principal))
                .isInstanceOfSatisfying(ResponseStatusException.class,
                        e -> assertThat(e.getStatusCode().value()).isEqualTo(404));
    }

    private void seedTvaDemo18Pourcent() {
        TaxRule regle = new TaxRule();
        regle.setPays("CI");
        regle.setRegime("REEL_NORMAL");
        regle.setCode("TVA");
        regle.setLibelle("TVA normale (fixture de test, non verifiee reglementairement)");
        regle.setTaux(new BigDecimal("18"));
        regle.setEffectiveFrom(LocalDate.of(2000, 1, 1));
        regle.setSource("FIXTURE_DEMO_NON_VERIFIE");
        taxRuleRepository.save(regle);
    }

    private EntrepriseDto creerEntreprise(UtilisateurPrincipal principal, String raisonSociale) {
        return entrepriseService.creer(
                new CreerEntrepriseRequest(raisonSociale, null, null, null, null, null, "CI", "XOF"),
                principal);
    }

    private UtilisateurPrincipal creerPrincipalExpertComptable() {
        Tenant tenant = new Tenant();
        tenant.setRaisonSociale("Cabinet Operations Test");
        tenant.setSlug("cabinet-operations-" + UUID.randomUUID());
        tenant.setPays("CI");
        tenant = tenantRepository.save(tenant);

        Role role = roleRepository.findByCode("EXPERT_COMPTABLE").orElseThrow();

        Utilisateur utilisateur = new Utilisateur();
        utilisateur.setTenant(tenant);
        utilisateur.setEmail("operations-" + UUID.randomUUID() + "@test.ci");
        utilisateur.setMotDePasse("hash");
        utilisateur.setNom("Test");
        utilisateur.setPrenom("Operations");
        utilisateur.setRoles(Set.of(role));
        utilisateur = utilisateurRepository.save(utilisateur);

        return new UtilisateurPrincipal(utilisateur);
    }
}
