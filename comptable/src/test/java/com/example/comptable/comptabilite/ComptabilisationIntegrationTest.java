package com.example.comptable.comptabilite;

import com.example.comptable.comptabilite.domain.CompteComptable;
import com.example.comptable.comptabilite.domain.TypeTiers;
import com.example.comptable.comptabilite.repository.CompteComptableRepository;
import com.example.comptable.comptabilite.repository.EcritureComptableRepository;
import com.example.comptable.comptabilite.service.ComptabilisationService;
import com.example.comptable.comptabilite.service.EcritureComptableService;
import com.example.comptable.comptabilite.service.TiersService;
import com.example.comptable.comptabilite.web.dto.ComptabiliserDocumentRequest;
import com.example.comptable.comptabilite.web.dto.EcritureComptableDto;
import com.example.comptable.comptabilite.web.dto.TiersDto;
import com.example.comptable.document.domain.CanalSource;
import com.example.comptable.document.domain.Document;
import com.example.comptable.document.domain.TypeDocument;
import com.example.comptable.document.repository.DocumentRepository;
import com.example.comptable.entreprise.repository.EntrepriseRepository;
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
 * Test d'integration bout en bout sur PostgreSQL reel (via docker-compose, non
 * saute en test ici) : amorcage d'une entreprise, comptabilisation d'une facture
 * d'achat, validation, puis verification de l'intangibilite d'une ecriture validee.
 */
@SpringBootTest(properties = "spring.docker.compose.skip.in-tests=false")
class ComptabilisationIntegrationTest {

    @Autowired
    private TenantRepository tenantRepository;
    @Autowired
    private UtilisateurRepository utilisateurRepository;
    @Autowired
    private RoleRepository roleRepository;
    @Autowired
    private EntrepriseService entrepriseService;
    @Autowired
    private EntrepriseRepository entrepriseRepository;
    @Autowired
    private TiersService tiersService;
    @Autowired
    private CompteComptableRepository compteComptableRepository;
    @Autowired
    private DocumentRepository documentRepository;
    @Autowired
    private ComptabilisationService comptabilisationService;
    @Autowired
    private EcritureComptableService ecritureComptableService;
    @Autowired
    private EcritureComptableRepository ecritureComptableRepository;

    @Test
    void comptabilise_une_facture_dachat_puis_verifie_lintangibilite_apres_validation() {
        UtilisateurPrincipal principal = creerPrincipalExpertComptable();

        EntrepriseDto entreprise = entrepriseService.creer(
                new CreerEntrepriseRequest("PME Integration Test", null, null, null, null, null, "CI", "XOF"),
                principal);

        TiersDto fournisseur = tiersService.creer(entreprise.id(), TypeTiers.FOURNISSEUR,
                "Fournisseur Integration", null, principal);

        CompteComptable compteCharge = compteComptableRepository
                .findByEntrepriseIdAndNumero(entreprise.id(), "601")
                .orElseThrow();

        Document document = documentAchat(entreprise.id(), new BigDecimal("100000"), new BigDecimal("18000"),
                new BigDecimal("118000"));
        document = documentRepository.save(document);

        ComptabiliserDocumentRequest requete = new ComptabiliserDocumentRequest(
                fournisseur.id(), compteCharge.getId(), null, null);

        EcritureComptableDto ecriture = comptabilisationService.comptabiliser(
                entreprise.id(), document.getId(), requete, principal);

        assertThat(ecriture.statut()).isEqualTo("BROUILLON");
        assertThat(ecriture.lignes()).hasSize(3);
        assertThat(ecriture.totalDebit()).isEqualByComparingTo("118000");
        assertThat(ecriture.totalCredit()).isEqualByComparingTo("118000");

        EcritureComptableDto valide = ecritureComptableService.valider(entreprise.id(), ecriture.id(), principal);
        assertThat(valide.statut()).isEqualTo("VALIDEE");

        assertThatThrownBy(() -> ecritureComptableService.supprimer(entreprise.id(), ecriture.id(), principal))
                .isInstanceOfSatisfying(ResponseStatusException.class,
                        ex -> assertThat(ex.getStatusCode().value()).isEqualTo(409));

        assertThat(ecritureComptableRepository.findById(ecriture.id())).isPresent();
    }

    @Test
    void un_brouillon_peut_etre_supprime_et_libere_le_document() {
        UtilisateurPrincipal principal = creerPrincipalExpertComptable();

        EntrepriseDto entreprise = entrepriseService.creer(
                new CreerEntrepriseRequest("PME Integration Brouillon", null, null, null, null, null, "CI", "XOF"),
                principal);

        TiersDto fournisseur = tiersService.creer(entreprise.id(), TypeTiers.FOURNISSEUR,
                "Fournisseur Brouillon", null, principal);
        CompteComptable compteCharge = compteComptableRepository
                .findByEntrepriseIdAndNumero(entreprise.id(), "601")
                .orElseThrow();

        Document document = documentAchat(entreprise.id(), new BigDecimal("50000"), BigDecimal.ZERO, new BigDecimal("50000"));
        document = documentRepository.save(document);

        EcritureComptableDto ecriture = comptabilisationService.comptabiliser(
                entreprise.id(), document.getId(),
                new ComptabiliserDocumentRequest(fournisseur.id(), compteCharge.getId(), null, null),
                principal);

        ecritureComptableService.supprimer(entreprise.id(), ecriture.id(), principal);

        assertThat(ecritureComptableRepository.findById(ecriture.id())).isEmpty();
        assertThat(documentRepository.findById(document.getId()).orElseThrow().getEcritureId()).isNull();
    }

    private UtilisateurPrincipal creerPrincipalExpertComptable() {
        Tenant tenant = new Tenant();
        tenant.setRaisonSociale("Cabinet Integration Test");
        tenant.setSlug("cabinet-integration-" + UUID.randomUUID());
        tenant.setPays("CI");
        tenant = tenantRepository.save(tenant);

        Role role = roleRepository.findByCode("EXPERT_COMPTABLE").orElseThrow();

        Utilisateur utilisateur = new Utilisateur();
        utilisateur.setTenant(tenant);
        utilisateur.setEmail("integration-" + UUID.randomUUID() + "@test.ci");
        utilisateur.setMotDePasse("hash");
        utilisateur.setNom("Test");
        utilisateur.setPrenom("Integration");
        utilisateur.setRoles(Set.of(role));
        utilisateur = utilisateurRepository.save(utilisateur);

        return new UtilisateurPrincipal(utilisateur);
    }

    private Document documentAchat(UUID entrepriseId, BigDecimal ht, BigDecimal tva, BigDecimal ttc) {
        Document document = new Document();
        document.setTenant(entrepriseRepository.findById(entrepriseId).orElseThrow().getTenant());
        document.setEntreprise(entrepriseRepository.getReferenceById(entrepriseId));
        document.setTypeDocument(TypeDocument.FACTURE_ACHAT);
        document.setCanalSource(CanalSource.WEB);
        document.setCheminFichier("test/facture.pdf");
        document.setMontantHt(ht);
        document.setMontantTva(tva);
        document.setMontantTtc(ttc);
        document.setDatePiece(LocalDate.now());
        return document;
    }
}
