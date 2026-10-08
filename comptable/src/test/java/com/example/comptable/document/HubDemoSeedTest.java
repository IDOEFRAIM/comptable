package com.example.comptable.document;

import com.example.comptable.agent.whatsapp.dto.DonneesExtraitesDTO;
import com.example.comptable.comptabilite.domain.TypeTiers;
import com.example.comptable.comptabilite.repository.CompteComptableRepository;
import com.example.comptable.comptabilite.service.TiersService;
import com.example.comptable.comptabilite.web.dto.TiersDto;
import com.example.comptable.controle.ControlRunService;
import com.example.comptable.document.domain.CanalSource;
import com.example.comptable.document.domain.TypeDocument;
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
import com.example.comptable.stock.domain.FactureFournisseur;
import com.example.comptable.stock.domain.ModeGestionStock;
import com.example.comptable.stock.domain.PolitiqueStockNegatif;
import com.example.comptable.stock.domain.TypeArticle;
import com.example.comptable.stock.repository.FactureFournisseurRepository;
import com.example.comptable.stock.service.ArticleService;
import com.example.comptable.stock.service.CommandeAchatService;
import com.example.comptable.stock.web.dto.ArticleDto;
import com.example.comptable.stock.web.dto.CommandeAchatDto;
import com.example.comptable.stock.web.dto.CreerArticleRequest;
import com.example.comptable.stock.web.dto.CreerCommandeAchatRequest;
import com.example.comptable.stock.web.dto.FactureFournisseurDto;
import com.example.comptable.stock.web.dto.LigneCommandeAchatRequest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.security.crypto.password.PasswordEncoder;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * OUTIL DE DEVELOPPEMENT (desactive par defaut) : cree un cabinet de demonstration avec un dossier « documents » varie
 * (pieces certaines, a confirmer, ambigues, doublons, illisibles, operations sans piece) pour verifier les ecrans a la
 * main. Lancer avec {@code -Dseed.hub=true -Dtest=HubDemoSeedTest}. Identifiants : demo-hub@test.ci / Demo12345!
 */
@EnabledIfSystemProperty(named = "seed.hub", matches = "true")
@SpringBootTest(properties = "spring.docker.compose.skip.in-tests=false")
class HubDemoSeedTest {

    @Autowired private TenantRepository tenantRepository;
    @Autowired private UtilisateurRepository utilisateurRepository;
    @Autowired private RoleRepository roleRepository;
    @Autowired private PasswordEncoder passwordEncoder;
    @Autowired private EntrepriseService entrepriseService;
    @Autowired private EntrepriseRepository entrepriseRepository;
    @Autowired private TiersService tiersService;
    @Autowired private ArticleService articleService;
    @Autowired private CommandeAchatService commandeAchatService;
    @Autowired private CompteComptableRepository compteComptableRepository;
    @Autowired private FactureFournisseurRepository factureFournisseurRepository;
    @Autowired private DocumentService documentService;
    @Autowired private ControlRunService controlRunService;

    @Test
    void creer_le_jeu_de_demonstration() {
        Tenant tenant = new Tenant();
        tenant.setRaisonSociale("Cabinet Demo Hub");
        tenant.setSlug("cabinet-demo-hub-" + UUID.randomUUID());
        tenant.setPays("CI");
        tenant = tenantRepository.save(tenant);
        Role role = roleRepository.findByCode("EXPERT_COMPTABLE").orElseThrow();
        Utilisateur u = new Utilisateur();
        u.setTenant(tenant);
        u.setEmail("demo-hub-" + System.currentTimeMillis() + "@test.ci");
        u.setMotDePasse(passwordEncoder.encode("Demo12345!"));
        u.setNom("Demo");
        u.setPrenom("Hub");
        u.setRoles(Set.of(role));
        u = utilisateurRepository.save(u);
        UtilisateurPrincipal principal = new UtilisateurPrincipal(u);

        EntrepriseDto entreprise = entrepriseService.creer(new CreerEntrepriseRequest("Quincaillerie Demo SARL", null, null, null, null, null, "CI", "XOF"), principal);
        TiersDto cimenterie = tiersService.creer(entreprise.id(), TypeTiers.FOURNISSEUR, "Cimenterie SARL", null, principal);
        tiersService.creer(entreprise.id(), TypeTiers.FOURNISSEUR, "Dupont Aluminium", null, principal);
        tiersService.creer(entreprise.id(), TypeTiers.FOURNISSEUR, "Dupont Verre", null, principal);
        ArticleDto article = articleService.creer(entreprise.id(), new CreerArticleRequest("ART-DEMO", "Sac de ciment", "Materiaux", TypeArticle.MARCHANDISE,
                "unite", new BigDecimal("3000"), new BigDecimal("18"), compte(entreprise, "311"), compte(entreprise, "601"), compte(entreprise, "701"),
                compte(entreprise, "6031"), null, PolitiqueStockNegatif.INTERDIT, ModeGestionStock.PERMANENT), principal);

        FactureFournisseurDto ff1 = facturer(entreprise, principal, cimenterie, article, 10);
        FactureFournisseurDto ff2 = facturer(entreprise, principal, cimenterie, article, 5);
        FactureFournisseurDto ff3 = facturer(entreprise, principal, cimenterie, article, 3);
        facturer(entreprise, principal, cimenterie, article, 4);
        facturer(entreprise, principal, cimenterie, article, 4);
        FactureFournisseur f2 = factureFournisseurRepository.findById(ff2.id()).orElseThrow();
        f2.setNumeroFournisseur("FA-900");
        factureFournisseurRepository.save(f2);
        FactureFournisseur f3 = factureFournisseurRepository.findById(ff3.id()).orElseThrow();
        f3.setNumeroFournisseur("FA-300");
        factureFournisseurRepository.save(f3);

        byte[] original = "facture FAC-001".getBytes(StandardCharsets.UTF_8);
        deposer(entreprise, original, "fac-001.pdf", "Cimenterie SARL", "FAC-001", "118000", "18000", "100000");
        deposer(entreprise, original, "fac-001-renvoi.pdf", "Cimenterie SARL", "FAC-001", "118000", "18000", "100000");
        deposer(entreprise, "autre scan".getBytes(StandardCharsets.UTF_8), "scan-fac-001.pdf", "Cimenterie SARL", "FAC- 001", "118000", "18000", "100000");
        deposer(entreprise, "probable".getBytes(StandardCharsets.UTF_8), "ff1.pdf", "Cimenterie SARL", "FA/778", "23600", "3600", "20000");
        deposer(entreprise, "certain".getBytes(StandardCharsets.UTF_8), "ff2.pdf", "Cimenterie SARL", "FA 900", "11800", "1800", "10000");
        deposer(entreprise, "ambigu".getBytes(StandardCharsets.UTF_8), "ambigu.pdf", "Cimenterie SARL", "AMB-1", "9440", "1440", "8000");
        deposer(entreprise, "dupont".getBytes(StandardCharsets.UTF_8), "dupont.pdf", "Dupont", "D-1", "59000", "9000", "50000");
        deposer(entreprise, "fantome".getBytes(StandardCharsets.UTF_8), "fantome.pdf", "Fournisseur Fantome", "Z-9", "35400", "5400", "30000");
        deposer(entreprise, "mismatch".getBytes(StandardCharsets.UTF_8), "ff3-montant.pdf", "Cimenterie SARL", "FA 300", "9999", "1525", "8474");
        documentService.ingererAvecExtractionExterne(entreprise.id(), TypeDocument.FACTURE_ACHAT, CanalSource.WHATSAPP,
                "flou".getBytes(StandardCharsets.UTF_8), "photo-floue.pdf", "application/pdf",
                new DonneesExtraitesDTO(null, null, null, null, "XOF", null, LocalDate.now(), 0.0, null), List.of());

        controlRunService.executer(entrepriseRepository.findById(entreprise.id()).orElseThrow());
        System.out.println("[seed] identifiants : " + u.getEmail() + " / Demo12345! ; entreprise " + entreprise.id());
    }

    private void deposer(EntrepriseDto entreprise, byte[] contenu, String nom, String fournisseur, String numero, String ttc, String tva, String ht) {
        documentService.ingererAvecExtractionExterne(entreprise.id(), TypeDocument.FACTURE_ACHAT, CanalSource.WHATSAPP, contenu, nom, "application/pdf",
                new DonneesExtraitesDTO(fournisseur, new BigDecimal(ht), new BigDecimal(tva), new BigDecimal(ttc), "XOF", null, LocalDate.now(), 0.95, numero),
                List.of());
    }

    private FactureFournisseurDto facturer(EntrepriseDto entreprise, UtilisateurPrincipal principal, TiersDto fournisseur, ArticleDto article, int quantite) {
        CommandeAchatDto commande = commandeAchatService.creer(entreprise.id(), new CreerCommandeAchatRequest(fournisseur.id(), LocalDate.now(),
                List.of(new LigneCommandeAchatRequest(article.id(), new BigDecimal(quantite), new BigDecimal("2000")))), principal);
        commandeAchatService.validerReception(entreprise.id(), commande.id(), principal);
        return commandeAchatService.genererFactureFournisseur(entreprise.id(), commande.id(), principal);
    }

    private UUID compte(EntrepriseDto entreprise, String numero) {
        return compteComptableRepository.findByEntrepriseIdAndNumero(entreprise.id(), numero).orElseThrow().getId();
    }
}
