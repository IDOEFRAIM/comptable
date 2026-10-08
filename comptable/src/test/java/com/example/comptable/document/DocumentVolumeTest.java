package com.example.comptable.document;

import com.example.comptable.agent.finding.domain.Finding;
import com.example.comptable.agent.finding.repository.FindingRepository;
import com.example.comptable.agent.whatsapp.dto.DonneesExtraitesDTO;
import com.example.comptable.comptabilite.domain.TypeTiers;
import com.example.comptable.comptabilite.service.TiersService;
import com.example.comptable.controle.ControlRunService;
import com.example.comptable.controle.revue.RevueComptableService;
import com.example.comptable.controle.revue.RevueDto;
import com.example.comptable.document.domain.CanalSource;
import com.example.comptable.document.domain.TypeDocument;
import com.example.comptable.document.hub.DocumentHubDto;
import com.example.comptable.document.hub.DocumentHubService;
import com.example.comptable.document.repository.DocumentRepository;
import com.example.comptable.document.service.DocumentService;
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
import java.math.RoundingMode;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.UUID;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 500 pieces realistes : 465 saines, 15 fichiers recus en double, 5 factures en double, 10 fournisseurs ambigus, 5 pieces
 * illisibles. L'Inbox ne doit recevoir QUE les 35 exceptions - jamais une ligne par piece saine - et le cout des lectures
 * ne doit pas dependre du volume (pas de N+1 sur le Hub ni sur un passage de controles).
 */
@SpringBootTest(properties = {"spring.docker.compose.skip.in-tests=false", "spring.jpa.properties.hibernate.generate_statistics=true"})
class DocumentVolumeTest {

    @Autowired private TenantRepository tenantRepository;
    @Autowired private UtilisateurRepository utilisateurRepository;
    @Autowired private RoleRepository roleRepository;
    @Autowired private EntrepriseService entrepriseService;
    @Autowired private EntrepriseRepository entrepriseRepository;
    @Autowired private TiersService tiersService;
    @Autowired private DocumentService documentService;
    @Autowired private DocumentRepository documentRepository;
    @Autowired private ControlRunService controlRunService;
    @Autowired private FindingRepository findingRepository;
    @Autowired private RevueComptableService revueService;
    @Autowired private DocumentHubService hubService;
    @Autowired private EntityManagerFactory entityManagerFactory;

    @Test
    void cinq_cents_pieces_donnent_trente_cinq_exceptions_et_rien_d_autre() {
        UtilisateurPrincipal principal = creerPrincipal();
        EntrepriseDto entreprise = entrepriseService.creer(new CreerEntrepriseRequest("PME Volume " + UUID.randomUUID(), null, null, null, null, null, "CI", "XOF"), principal);
        List<String> fournisseurs = new ArrayList<>();
        for (int i = 0; i < 8; i++) {
            String nom = "Fournisseur" + (char) ('A' + i) + " Distribution";
            tiersService.creer(entreprise.id(), TypeTiers.FOURNISSEUR, nom, null, principal);
            fournisseurs.add(nom);
        }
        tiersService.creer(entreprise.id(), TypeTiers.FOURNISSEUR, "Dupont Aluminium", null, principal);
        tiersService.creer(entreprise.id(), TypeTiers.FOURNISSEUR, "Dupont Verre", null, principal);

        long debut = System.currentTimeMillis();
        List<byte[]> contenus = new ArrayList<>();
        // 465 pieces saines : fournisseur identifie, numero et montant uniques
        for (int i = 0; i < 465; i++) {
            byte[] contenu = ("piece saine " + i).getBytes(StandardCharsets.UTF_8);
            contenus.add(contenu);
            deposer(entreprise, contenu, "saine-" + i + ".pdf", fournisseurs.get(i % 8), "F-" + i, 10070 + 70 * i, 0.95);
        }
        // 15 fichiers recus une seconde fois a l'identique
        for (int i = 0; i < 15; i++) {
            deposer(entreprise, contenus.get(i), "renvoi-" + i + ".pdf", fournisseurs.get(i % 8), "F-" + i, 10070 + 70 * i, 0.95);
        }
        // 5 factures deja recues mais scannees autrement (autre fichier, meme fournisseur et meme numero)
        for (int i = 20; i < 25; i++) {
            deposer(entreprise, ("autre scan " + i).getBytes(StandardCharsets.UTF_8), "scan-" + i + ".pdf", fournisseurs.get(i % 8), "F " + i, 10070 + 70 * i, 0.95);
        }
        // 10 pieces dont le fournisseur lu (« Dupont ») correspond a deux tiers
        for (int i = 0; i < 10; i++) {
            deposer(entreprise, ("ambigu " + i).getBytes(StandardCharsets.UTF_8), "dupont-" + i + ".pdf", "Dupont", "D-" + i, 400000 + 70 * i, 0.95);
        }
        // 5 pieces illisibles (le moteur ne lit rien : confiance 0)
        for (int i = 0; i < 5; i++) {
            deposer(entreprise, ("illisible " + i).getBytes(StandardCharsets.UTF_8), "flou-" + i + ".pdf", null, null, 0, 0.0);
        }
        long dureeDepot = System.currentTimeMillis() - debut;
        assertThat(documentRepository.count((root, query, cb) -> cb.equal(root.get("entreprise").get("id"), entreprise.id()))).isEqualTo(500);

        // un passage de controles : cout mesure
        Statistics statistiques = entityManagerFactory.unwrap(SessionFactory.class).getStatistics();
        statistiques.clear();
        long t0 = System.nanoTime();
        controlRunService.executer(entrepriseRepository.findById(entreprise.id()).orElseThrow());
        long dureeControles = (System.nanoTime() - t0) / 1_000_000;
        long requetesControles = statistiques.getPrepareStatementCount();
        // Garde-fou : une entite Finding modifiee a chaque flush (JSON non relisible a l'identique) multiplierait les UPDATE.
        assertThat(statistiques.getEntityUpdateCount()).as("aucun UPDATE parasite sur les constats").isLessThanOrEqualTo(3);

        // l'Inbox : 35 exceptions exactement, par code
        List<Finding> actifs = findingRepository.findActifsParCodes(entreprise.id(), Set.of("EXACT_DUPLICATE", "DUPLICATE_INVOICE",
                "POSSIBLE_DUPLICATE", "DOCUMENT_UNREADABLE", "DOCUMENT_AMBIGUOUS", "DOCUMENT_UNMATCHED", "DOCUMENT_AMOUNT_MISMATCH", "DOCUMENT_AT_RISK",
                "MISSING_DOCUMENT"));
        Map<String, Long> parCode = new TreeMap<>(actifs.stream().collect(Collectors.groupingBy(Finding::getControlCode, Collectors.counting())));
        assertThat(parCode).containsExactlyInAnyOrderEntriesOf(Map.of("EXACT_DUPLICATE", 15L, "DUPLICATE_INVOICE", 5L,
                "DOCUMENT_AMBIGUOUS", 10L, "DOCUMENT_UNREADABLE", 5L));
        RevueDto.Page inbox = revueService.lister(entreprise.id(), new RevueComptableService.Filtre(null, null, null, null, null, null, null, null, null),
                0, 100, principal);
        assertThat(inbox.total()).as("l'Inbox ne recoit que les exceptions").isEqualTo(35);

        // Hub : compteurs et page, sans N+1
        DocumentHubDto.Synthese synthese = hubService.synthese(entreprise.id(), null, principal);
        assertThat(synthese.recus()).isEqualTo(500);
        assertThat(synthese.doublons()).isEqualTo(20);
        assertThat(synthese.aVerifier()).isGreaterThanOrEqualTo(15);
        statistiques.clear();
        DocumentHubDto.Page page = hubService.lister(entreprise.id(), new DocumentHubService.Filtre(null, null, null, null, null, null), 0, 25, principal);
        long requetesPage = statistiques.getPrepareStatementCount();
        assertThat(page.contenu()).hasSize(25);
        assertThat(page.total()).isEqualTo(500);
        assertThat(page.contenu()).allSatisfy(l -> assertThat(l.id()).isNotNull());

        System.out.println("[perf] 500 pieces deposees en " + dureeDepot + " ms ; passage de controles : " + requetesControles
                + " requetes, " + dureeControles + " ms ; page du Hub (25 lignes) : " + requetesPage + " requetes");
        assertThat(requetesPage).as("page du Hub : requetes (pas de N+1)").isLessThanOrEqualTo(12);
        assertThat(requetesControles).as("passage complet des detecteurs sur 500 pieces").isLessThanOrEqualTo(220);
    }

    private void deposer(EntrepriseDto entreprise, byte[] contenu, String nom, String fournisseur, String numero, int ht, double confiance) {
        BigDecimal montantHt = new BigDecimal(ht);
        BigDecimal tva = montantHt.multiply(new BigDecimal("0.18")).setScale(0, RoundingMode.HALF_UP);
        DonneesExtraitesDTO donnees = new DonneesExtraitesDTO(fournisseur, montantHt, tva, montantHt.add(tva), "XOF", null, LocalDate.now(), confiance, numero);
        documentService.ingererAvecExtractionExterne(entreprise.id(), TypeDocument.FACTURE_ACHAT, CanalSource.API, contenu, nom,
                "application/pdf", donnees, List.of());
    }

    private UtilisateurPrincipal creerPrincipal() {
        Tenant tenant = new Tenant();
        tenant.setRaisonSociale("Cabinet Volume");
        tenant.setSlug("cabinet-volume-" + UUID.randomUUID());
        tenant.setPays("CI");
        tenant = tenantRepository.save(tenant);
        Role role = roleRepository.findByCode("EXPERT_COMPTABLE").orElseThrow();
        Utilisateur utilisateur = new Utilisateur();
        utilisateur.setTenant(tenant);
        utilisateur.setEmail("volume-" + UUID.randomUUID() + "@test.ci");
        utilisateur.setMotDePasse("hash");
        utilisateur.setNom("Test");
        utilisateur.setPrenom("Volume");
        utilisateur.setRoles(Set.of(role));
        return new UtilisateurPrincipal(utilisateurRepository.save(utilisateur));
    }
}
