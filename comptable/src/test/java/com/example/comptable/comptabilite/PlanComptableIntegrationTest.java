package com.example.comptable.comptabilite;

import com.example.comptable.comptabilite.domain.CompteComptable;
import com.example.comptable.comptabilite.domain.ModeImportReference;
import com.example.comptable.comptabilite.repository.CompteComptableRepository;
import com.example.comptable.comptabilite.repository.JournalRepository;
import com.example.comptable.comptabilite.service.PlanComptableImportService;
import com.example.comptable.comptabilite.service.PlanComptableService;
import com.example.comptable.comptabilite.web.dto.CreerEcritureRequest;
import com.example.comptable.comptabilite.web.dto.EcritureComptableDto;
import com.example.comptable.comptabilite.web.dto.ImporterReferenceRequest;
import com.example.comptable.comptabilite.web.dto.ImporterReferenceResultDto;
import com.example.comptable.comptabilite.web.dto.LigneEcritureRequest;
import com.example.comptable.comptabilite.web.dto.ModifierCompteRequest;
import com.example.comptable.comptabilite.service.EcritureComptableService;
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
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Verifie de bout en bout, sur PostgreSQL reel (meme convention que
 * {@link ComptabilisationIntegrationTest}), les garanties centrales de la fondation plan
 * comptable : isolation stricte du PCE entre entreprises, idempotence de l'import, et
 * compatibilite complete avec le cycle de vie des ecritures existant.
 */
@SpringBootTest(properties = "spring.docker.compose.skip.in-tests=false")
class PlanComptableIntegrationTest {

    @Autowired
    private TenantRepository tenantRepository;
    @Autowired
    private UtilisateurRepository utilisateurRepository;
    @Autowired
    private RoleRepository roleRepository;
    @Autowired
    private EntrepriseService entrepriseService;
    @Autowired
    private CompteComptableRepository compteComptableRepository;
    @Autowired
    private JournalRepository journalRepository;
    @Autowired
    private PlanComptableService planComptableService;
    @Autowired
    private PlanComptableImportService planComptableImportService;
    @Autowired
    private EcritureComptableService ecritureComptableService;

    @Test
    void deux_entreprises_ont_des_pce_independants_et_les_personnalisations_ne_fuient_pas() {
        UtilisateurPrincipal principal = creerPrincipalExpertComptable();
        EntrepriseDto entrepriseA = creerEntreprise(principal, "PCE Entreprise A");
        EntrepriseDto entrepriseB = creerEntreprise(principal, "PCE Entreprise B");

        CompteComptable compteAchatsA = compteComptableRepository
                .findByEntrepriseIdAndNumero(entrepriseA.id(), "601").orElseThrow();
        CompteComptable compteAchatsB = compteComptableRepository
                .findByEntrepriseIdAndNumero(entrepriseB.id(), "601").orElseThrow();

        // Meme compte de reference SYSCOHADA, deux lignes CompteComptable distinctes.
        assertThat(compteAchatsA.getId()).isNotEqualTo(compteAchatsB.getId());
        assertThat(compteAchatsA.getReference().getId()).isEqualTo(compteAchatsB.getReference().getId());

        planComptableService.modifier(entrepriseA.id(), compteAchatsA.getId(),
                new ModifierCompteRequest("Achats renommes par le cabinet A", false), principal);

        CompteComptable rechargeA = compteComptableRepository.findById(compteAchatsA.getId()).orElseThrow();
        CompteComptable rechargeB = compteComptableRepository.findById(compteAchatsB.getId()).orElseThrow();

        assertThat(rechargeA.getIntitule()).isEqualTo("Achats renommes par le cabinet A");
        assertThat(rechargeA.isActif()).isFalse();
        // La personnalisation de A ne fuit jamais vers B, ni vers le referentiel global.
        assertThat(rechargeB.getIntitule()).isNotEqualTo("Achats renommes par le cabinet A");
        assertThat(rechargeB.isActif()).isTrue();
    }

    @Test
    void limport_est_idempotent_sur_une_entreprise_deja_provisionnee() {
        UtilisateurPrincipal principal = creerPrincipalExpertComptable();
        EntrepriseDto entreprise = creerEntreprise(principal, "PCE Import Idempotent");

        long avant = compteComptableRepository.findByEntrepriseId(entreprise.id()).size();

        ImporterReferenceResultDto resultat = planComptableImportService.importer(entreprise.id(),
                new ImporterReferenceRequest(ModeImportReference.TOUT, null), principal);

        long apres = compteComptableRepository.findByEntrepriseId(entreprise.id()).size();

        // Tout le referentiel actif a deja ete clone a la creation : rejouer l'import n'ajoute rien.
        assertThat(resultat.comptesAjoutes()).isEmpty();
        assertThat(apres).isEqualTo(avant);
    }

    @Test
    void impossible_de_modifier_un_compte_dune_autre_entreprise() {
        UtilisateurPrincipal principal = creerPrincipalExpertComptable();
        EntrepriseDto entrepriseA = creerEntreprise(principal, "PCE Cross Tenant A");
        EntrepriseDto entrepriseB = creerEntreprise(principal, "PCE Cross Tenant B");

        CompteComptable compteDeB = compteComptableRepository
                .findByEntrepriseIdAndNumero(entrepriseB.id(), "601").orElseThrow();

        assertThatThrownBy(() -> planComptableService.modifier(entrepriseA.id(), compteDeB.getId(),
                new ModifierCompteRequest("Tentative cross-tenant", null), principal))
                .isInstanceOfSatisfying(ResponseStatusException.class,
                        e -> assertThat(e.getStatusCode().value()).isEqualTo(404));
    }

    @Test
    void une_ecriture_manuelle_reference_bien_un_compte_du_pce_et_fonctionne() {
        UtilisateurPrincipal principal = creerPrincipalExpertComptable();
        EntrepriseDto entreprise = creerEntreprise(principal, "PCE Ecriture Manuelle");

        CompteComptable compteAchats = compteComptableRepository
                .findByEntrepriseIdAndNumero(entreprise.id(), "601").orElseThrow();
        CompteComptable compteBanque = compteComptableRepository
                .findByEntrepriseIdAndNumero(entreprise.id(), "521").orElseThrow();
        UUID journalOdId = journalRepository.findByEntrepriseIdAndCode(entreprise.id(), "OD").orElseThrow().getId();

        CreerEcritureRequest request = new CreerEcritureRequest(
                journalOdId, LocalDate.now(), "Achat divers", null,
                java.util.List.of(
                        new LigneEcritureRequest(compteAchats.getId(), null, new BigDecimal("15000"), BigDecimal.ZERO, null),
                        new LigneEcritureRequest(compteBanque.getId(), null, BigDecimal.ZERO, new BigDecimal("15000"), null)
                ));

        EcritureComptableDto ecriture = ecritureComptableService.creerManuelle(entreprise.id(), request, principal);

        assertThat(ecriture.statut()).isEqualTo("BROUILLON");
        assertThat(ecriture.totalDebit()).isEqualByComparingTo("15000");
        assertThat(ecriture.totalCredit()).isEqualByComparingTo("15000");
    }

    private EntrepriseDto creerEntreprise(UtilisateurPrincipal principal, String raisonSociale) {
        return entrepriseService.creer(
                new CreerEntrepriseRequest(raisonSociale, null, null, null, null, null, "CI", "XOF"),
                principal);
    }

    private UtilisateurPrincipal creerPrincipalExpertComptable() {
        Tenant tenant = new Tenant();
        tenant.setRaisonSociale("Cabinet Plan Comptable Test");
        tenant.setSlug("cabinet-plan-comptable-" + UUID.randomUUID());
        tenant.setPays("CI");
        tenant = tenantRepository.save(tenant);

        Role role = roleRepository.findByCode("EXPERT_COMPTABLE").orElseThrow();

        Utilisateur utilisateur = new Utilisateur();
        utilisateur.setTenant(tenant);
        utilisateur.setEmail("plan-comptable-" + UUID.randomUUID() + "@test.ci");
        utilisateur.setMotDePasse("hash");
        utilisateur.setNom("Test");
        utilisateur.setPrenom("PlanComptable");
        utilisateur.setRoles(Set.of(role));
        utilisateur = utilisateurRepository.save(utilisateur);

        return new UtilisateurPrincipal(utilisateur);
    }
}
