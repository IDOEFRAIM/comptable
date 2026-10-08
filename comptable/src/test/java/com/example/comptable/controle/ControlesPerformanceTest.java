package com.example.comptable.controle;

import com.example.comptable.comptabilite.operation.achat.AchatMultiLigneOperationRequest;
import com.example.comptable.comptabilite.operation.achat.LigneAchatOperationRequest;
import com.example.comptable.comptabilite.operation.achat.NatureAchat;
import com.example.comptable.comptabilite.operation.common.AccountingOperationEngine;
import com.example.comptable.comptabilite.operation.common.AccountingSource;
import com.example.comptable.comptabilite.domain.TypeTiers;
import com.example.comptable.comptabilite.repository.CompteComptableRepository;
import com.example.comptable.comptabilite.service.TiersService;
import com.example.comptable.comptabilite.web.dto.TiersDto;
import com.example.comptable.document.domain.CanalSource;
import com.example.comptable.document.domain.Document;
import com.example.comptable.document.domain.TypeDocument;
import com.example.comptable.document.repository.DocumentRepository;
import com.example.comptable.document.service.DocumentLiaisonService;
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
import jakarta.persistence.EntityManagerFactory;
import org.hibernate.SessionFactory;
import org.hibernate.stat.Statistics;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Performance des controles : le cout d'un passage ne depend pas du nombre d'operations SAINES (pas de N+1,
 * aucune ecriture chargee une par une). Le nombre de requetes SQL d'un passage complet est identique pour
 * 5 ou 40 factures justifiees, et borne.
 */
@SpringBootTest(properties = {"spring.docker.compose.skip.in-tests=false", "spring.jpa.properties.hibernate.generate_statistics=true"})
class ControlesPerformanceTest {

    @Autowired private TenantRepository tenantRepository;
    @Autowired private UtilisateurRepository utilisateurRepository;
    @Autowired private RoleRepository roleRepository;
    @Autowired private EntrepriseService entrepriseService;
    @Autowired private EntrepriseRepository entrepriseRepository;
    @Autowired private TiersService tiersService;
    @Autowired private CompteComptableRepository compteComptableRepository;
    @Autowired private AccountingOperationEngine engine;
    @Autowired private DocumentRepository documentRepository;
    @Autowired private DocumentLiaisonService documentLiaisonService;
    @Autowired private ControlRunService controlRunService;
    @Autowired private EntityManagerFactory entityManagerFactory;

    @Test
    void le_nombre_de_requetes_d_un_passage_ne_depend_pas_du_nombre_d_operations_saines() {
        long petit = requetesPourDossierSain(5);
        long grand = requetesPourDossierSain(40);

        assertThat(grand).as("requetes pour 40 factures vs 5").isEqualTo(petit);
        assertThat(grand).as("passage complet des detecteurs").isLessThan(80);
    }

    private long requetesPourDossierSain(int nbFactures) {
        UtilisateurPrincipal principal = principal();
        EntrepriseDto entreprise = entrepriseService.creer(new CreerEntrepriseRequest("PME Perf " + UUID.randomUUID(), null, null, null, null, null, "CI", "XOF"), principal);
        TiersDto fournisseur = tiersService.creer(entreprise.id(), TypeTiers.FOURNISSEUR, "Fournisseur", null, principal);
        UUID compteCharge = compteComptableRepository.findByEntrepriseIdAndNumero(entreprise.id(), "601").orElseThrow().getId();
        for (int i = 0; i < nbFactures; i++) {
            var proposition = engine.proposerAchatEtValiderImmediatement(entreprise.id(), new AchatMultiLigneOperationRequest(fournisseur.id(),
                    LocalDate.now(), "F-" + i, "Achat " + i, List.of(new LigneAchatOperationRequest("Marchandises", NatureAchat.MARCHANDISE,
                    new BigDecimal("10000"), BigDecimal.ZERO, compteCharge, null)), null,
                    AccountingSource.de("FACTURE_FOURNISSEUR", UUID.randomUUID(), "VALIDATION")), principal);
            Document piece = new Document();
            piece.setTenant(entrepriseRepository.findById(entreprise.id()).orElseThrow().getTenant());
            piece.setEntreprise(entrepriseRepository.getReferenceById(entreprise.id()));
            piece.setTypeDocument(TypeDocument.FACTURE_ACHAT);
            piece.setCanalSource(CanalSource.WEB);
            piece.setCheminFichier("test/p.pdf");
            piece.setNomFichierOriginal("p-" + i + ".pdf");
            piece.setMontantHt(new BigDecimal("10000"));
            piece.setMontantTva(BigDecimal.ZERO);
            piece.setMontantTtc(new BigDecimal("10000"));
            piece.setDatePiece(LocalDate.now());
            piece = documentRepository.save(piece);
            documentLiaisonService.lierAEcriture(entreprise.id(), piece.getId(), proposition.ecritureId(), principal);
        }
        controlRunService.executer(entrepriseRepository.findById(entreprise.id()).orElseThrow()); // premier passage : amorce
        Statistics statistiques = entityManagerFactory.unwrap(SessionFactory.class).getStatistics();
        statistiques.clear();
        long debut = System.nanoTime();
        controlRunService.executer(entrepriseRepository.findById(entreprise.id()).orElseThrow());
        long ms = (System.nanoTime() - debut) / 1_000_000;
        long requetes = statistiques.getPrepareStatementCount();
        System.out.println("[perf] " + nbFactures + " factures justifiees : " + requetes + " requetes, " + ms + " ms");
        return requetes;
    }

    private UtilisateurPrincipal principal() {
        Tenant tenant = new Tenant();
        tenant.setRaisonSociale("Cabinet Perf");
        tenant.setSlug("cabinet-perf-" + UUID.randomUUID());
        tenant.setPays("CI");
        tenant = tenantRepository.save(tenant);
        Role role = roleRepository.findByCode("EXPERT_COMPTABLE").orElseThrow();
        Utilisateur utilisateur = new Utilisateur();
        utilisateur.setTenant(tenant);
        utilisateur.setEmail("perf-" + UUID.randomUUID() + "@test.ci");
        utilisateur.setMotDePasse("hash");
        utilisateur.setNom("Test");
        utilisateur.setPrenom("Perf");
        utilisateur.setRoles(Set.of(role));
        return new UtilisateurPrincipal(utilisateurRepository.save(utilisateur));
    }
}
