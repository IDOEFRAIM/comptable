package com.example.comptable.comptabilite;

import com.example.comptable.comptabilite.operation.common.AccountingSource;

import com.example.comptable.audit.domain.AuditEvent;
import com.example.comptable.audit.repository.AuditEventRepository;
import com.example.comptable.comptabilite.domain.CompteComptable;
import com.example.comptable.comptabilite.domain.EcritureComptable;
import com.example.comptable.comptabilite.domain.ModeAmortissement;
import com.example.comptable.comptabilite.domain.MotifSortie;
import com.example.comptable.comptabilite.domain.StatutEcriture;
import com.example.comptable.comptabilite.domain.TypeImmobilisation;
import com.example.comptable.comptabilite.domain.TypeTiers;
import com.example.comptable.comptabilite.operation.common.AccountingOperationEngine;
import com.example.comptable.comptabilite.operation.common.AccountingOperationType;
import com.example.comptable.comptabilite.operation.common.MoyenPaiement;
import com.example.comptable.comptabilite.operation.common.domain.AccountingOperationProposal;
import com.example.comptable.comptabilite.operation.common.repository.AccountingOperationProposalRepository;
import com.example.comptable.comptabilite.operation.common.web.dto.AccountingOperationProposalDto;
import com.example.comptable.comptabilite.operation.reglement.ReglementOperationRequest;
import com.example.comptable.comptabilite.parametrage.domain.AccountPurpose;
import com.example.comptable.comptabilite.parametrage.service.AccountingAccountMappingService;
import com.example.comptable.comptabilite.parametrage.web.dto.AccountingAccountMappingDto;
import com.example.comptable.comptabilite.parametrage.web.dto.CreerAccountingAccountMappingRequest;
import com.example.comptable.comptabilite.repository.CompteComptableRepository;
import com.example.comptable.comptabilite.repository.EcritureComptableRepository;
import com.example.comptable.comptabilite.service.ComptabilisationService;
import com.example.comptable.comptabilite.service.EcritureComptableService;
import com.example.comptable.comptabilite.service.ImmobilisationService;
import com.example.comptable.comptabilite.service.TiersService;
import com.example.comptable.comptabilite.web.dto.ComptabiliserAcquisitionRequest;
import com.example.comptable.comptabilite.web.dto.ComptabiliserDocumentRequest;
import com.example.comptable.comptabilite.web.dto.CreerImmobilisationRequest;
import com.example.comptable.comptabilite.web.dto.EcritureComptableDto;
import com.example.comptable.comptabilite.web.dto.GenererDotationsResultDto;
import com.example.comptable.comptabilite.web.dto.ImmobilisationDto;
import com.example.comptable.comptabilite.web.dto.MettreEnServiceRequest;
import com.example.comptable.comptabilite.web.dto.SortirImmobilisationRequest;
import com.example.comptable.comptabilite.web.dto.TiersDto;
import com.example.comptable.document.domain.CanalSource;
import com.example.comptable.document.domain.Document;
import com.example.comptable.document.domain.TypeDocument;
import com.example.comptable.document.repository.DocumentRepository;
import com.example.comptable.entreprise.repository.EntrepriseRepository;
import com.example.comptable.entreprise.service.EntrepriseService;
import com.example.comptable.entreprise.web.dto.CreerEntrepriseRequest;
import com.example.comptable.entreprise.web.dto.EntrepriseDto;
import com.example.comptable.fiscalite.service.DeclarationFiscaleService;
import com.example.comptable.fiscalite.web.dto.CalculTvaDto;
import com.example.comptable.identite.domain.Role;
import com.example.comptable.identite.domain.Tenant;
import com.example.comptable.identite.domain.Utilisateur;
import com.example.comptable.identite.repository.RoleRepository;
import com.example.comptable.identite.repository.TenantRepository;
import com.example.comptable.identite.repository.UtilisateurRepository;
import com.example.comptable.security.UtilisateurPrincipal;
import com.example.comptable.stock.domain.TypeArticle;
import com.example.comptable.stock.domain.TypeMouvementStock;
import com.example.comptable.stock.inventaire.service.InventaireService;
import com.example.comptable.stock.inventaire.web.dto.CreerInventaireRequest;
import com.example.comptable.stock.inventaire.web.dto.InventaireDto;
import com.example.comptable.stock.inventaire.web.dto.SaisirLigneInventaireRequest;
import com.example.comptable.stock.repository.ArticleRepository;
import com.example.comptable.stock.service.ArticleService;
import com.example.comptable.stock.service.MouvementStockService;
import com.example.comptable.stock.web.dto.ArticleDto;
import com.example.comptable.stock.web.dto.CreerArticleRequest;
import com.example.comptable.stock.web.dto.CreerMouvementStockRequest;
import com.example.comptable.stock.web.dto.MouvementStockDto;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.web.server.ResponseStatusException;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * PHASE A - preuve du coeur comptable unique sur PostgreSQL reel : achat, vente, reglement, stock,
 * inventaire, immobilisation (acquisition, mise en service, dotation), declaration de TVA ; chaque
 * ecriture produite provient du moteur (une proposition y est rattachee), l'audit raconte les decisions,
 * l'entreprise B reste inaccessible, un compte non configure bloque proprement et le rejeu d'une
 * commande ne cree jamais de double ecriture.
 */
@SpringBootTest(properties = "spring.docker.compose.skip.in-tests=false")
class CoeurComptableUniqueTest {

    @Autowired private TenantRepository tenantRepository;
    @Autowired private UtilisateurRepository utilisateurRepository;
    @Autowired private RoleRepository roleRepository;
    @Autowired private EntrepriseService entrepriseService;
    @Autowired private EntrepriseRepository entrepriseRepository;
    @Autowired private TiersService tiersService;
    @Autowired private CompteComptableRepository compteComptableRepository;
    @Autowired private DocumentRepository documentRepository;
    @Autowired private ComptabilisationService comptabilisationService;
    @Autowired private EcritureComptableService ecritureComptableService;
    @Autowired private EcritureComptableRepository ecritureComptableRepository;
    @Autowired private AccountingOperationEngine engine;
    @Autowired private AccountingOperationProposalRepository proposalRepository;
    @Autowired private AccountingAccountMappingService mappingService;
    @Autowired private ArticleService articleService;
    @Autowired private ArticleRepository articleRepository;
    @Autowired private MouvementStockService mouvementStockService;
    @Autowired private InventaireService inventaireService;
    @Autowired private ImmobilisationService immobilisationService;
    @Autowired private DeclarationFiscaleService declarationFiscaleService;
    @Autowired private AuditEventRepository auditEventRepository;

    private UtilisateurPrincipal principalA;
    private UtilisateurPrincipal principalB;
    private EntrepriseDto entrepriseA;
    private EntrepriseDto entrepriseB;

    @BeforeEach
    void amorcer() {
        principalA = creerPrincipal();
        principalB = creerPrincipal();
        entrepriseA = creerEntreprise("PME A", principalA);
        entrepriseB = creerEntreprise("PME B", principalB);
    }

    // =================================================================================== scenario global

    @Test
    void scenario_complet_toutes_les_ecritures_viennent_du_moteur_et_sont_auditees() {
        // --- 3/4/5 : mapping, client, fournisseur
        mappingService.creer(entrepriseA.id(), new CreerAccountingAccountMappingRequest(AccountPurpose.BANQUE, null, compte(entrepriseA, "521"), null), principalA);
        TiersDto fournisseur = tiersService.creer(entrepriseA.id(), TypeTiers.FOURNISSEUR, "Fournisseur A", null, principalA);
        TiersDto client = tiersService.creer(entrepriseA.id(), TypeTiers.CLIENT, "Client A", null, principalA);
        assertThat(compteNumeroDuTiers(fournisseur)).startsWith("401");   // racine resolue par le parametrage/role, sous-compte derive
        assertThat(compteNumeroDuTiers(client)).startsWith("411");

        // --- 6/7 : achat et vente (flux Documents)
        EcritureComptableDto achat = comptabilisationService.comptabiliser(entrepriseA.id(),
                documentRepository.save(document(entrepriseA, TypeDocument.FACTURE_ACHAT, "100000", "18000", "118000")).getId(),
                new ComptabiliserDocumentRequest(fournisseur.id(), compte(entrepriseA, "601"), null, null), principalA);
        EcritureComptableDto vente = comptabilisationService.comptabiliser(entrepriseA.id(),
                documentRepository.save(document(entrepriseA, TypeDocument.FACTURE_VENTE, "200000", "36000", "236000")).getId(),
                new ComptabiliserDocumentRequest(client.id(), compte(entrepriseA, "701"), null, null), principalA);

        // --- 8 : reglement fournisseur par virement (proposition puis validation humaine)
        AccountingOperationProposalDto reglement = engine.proposerReglement(entrepriseA.id(), new ReglementOperationRequest(
                AccountingOperationType.REGLEMENT_FOURNISSEUR, fournisseur.id(), LocalDate.now(), "VIR-1", "Reglement fournisseur",
                new BigDecimal("118000"), MoyenPaiement.VIREMENT), principalA);
        assertThat(reglement.statut()).isEqualTo("PROPOSED");
        UUID reglementEcriture = engine.valider(entrepriseA.id(), reglement.id(), principalA).ecritureId();

        // --- 9/10/11 : entree, sortie, inventaire
        ArticleDto article = articleService.creer(entrepriseA.id(), new CreerArticleRequest("ART-1", "Ciment", null, TypeArticle.MARCHANDISE,
                "sac", new BigDecimal("6500"), new BigDecimal("18"), compte(entrepriseA, "311"), compte(entrepriseA, "601"),
                compte(entrepriseA, "701"), compte(entrepriseA, "6031"), null, null, null), principalA);
        MouvementStockDto entree = mouvementStockService.enregistrer(entrepriseA.id(), new CreerMouvementStockRequest(article.id(),
                TypeMouvementStock.ENTREE, new BigDecimal("20"), new BigDecimal("5000"), LocalDate.now(), "Entree"), principalA);
        MouvementStockDto sortie = mouvementStockService.enregistrer(entrepriseA.id(), new CreerMouvementStockRequest(article.id(),
                TypeMouvementStock.SORTIE, new BigDecimal("5"), null, LocalDate.now(), "Sortie"), principalA);
        assertThat(entree.ecritureId()).isNotNull();
        assertThat(sortie.ecritureId()).isNotNull();
        assertThat(sortie.valeurMouvement()).isEqualByComparingTo("25000");   // CUMP inchange par la refonte : 5 x 5000

        InventaireDto inventaire = inventaireService.creer(entrepriseA.id(), new CreerInventaireRequest(LocalDate.now(), "Fin de mois"), principalA);
        inventaireService.saisirLigne(entrepriseA.id(), inventaire.id(), new SaisirLigneInventaireRequest(article.id(), new BigDecimal("14"), null), principalA);
        InventaireDto valide = inventaireService.valider(entrepriseA.id(), inventaire.id(), principalA);
        UUID propositionInventaire = valide.propositionId();
        assertThat(propositionInventaire).isNotNull();
        engine.valider(entrepriseA.id(), propositionInventaire, principalA);
        inventaireService.cloturer(entrepriseA.id(), inventaire.id(), principalA);

        // --- 12 a 15 : immobilisation
        ImmobilisationDto immo = immobilisationService.creer(entrepriseA.id(), new CreerImmobilisationRequest(compte(entrepriseA, "244"),
                compte(entrepriseA, "281"), "Camion", TypeImmobilisation.CORPORELLE, LocalDate.now().withDayOfYear(1),
                new BigDecimal("5000000"), 5, ModeAmortissement.LINEAIRE), principalA);
        assertThat(immo.statut()).isEqualTo("ACQUISE");
        immo = immobilisationService.comptabiliserAcquisition(entrepriseA.id(), immo.id(),
                new ComptabiliserAcquisitionRequest(fournisseur.id(), new BigDecimal("900000"), null), principalA);
        assertThat(immo.acquisitionEcritureId()).isNotNull();
        immo = immobilisationService.mettreEnService(entrepriseA.id(), immo.id(), new MettreEnServiceRequest(LocalDate.now().withDayOfYear(1)), principalA);
        assertThat(immo.statut()).isEqualTo("EN_SERVICE");
        GenererDotationsResultDto dotations = immobilisationService.genererDotations(entrepriseA.id(), LocalDate.now().getYear(), principalA);
        assertThat(dotations.nombreImmobilisations()).isEqualTo(1);
        assertThat(dotations.totalDotations()).isEqualByComparingTo("1000000");

        // --- 16 : declaration de TVA (comptes lus par le resolveur) ; seules les ecritures validees comptent
        ecritureComptableService.valider(entrepriseA.id(), achat.id(), principalA);
        ecritureComptableService.valider(entrepriseA.id(), vente.id(), principalA);
        CalculTvaDto tva = declarationFiscaleService.calculerTva(entrepriseA.id(), YearMonth.now().toString(), principalA);
        assertThat(tva.montantCollecte()).isEqualByComparingTo("36000");
        boolean janvier = LocalDate.now().getMonthValue() == 1;
        assertThat(tva.montantDeductible()).isEqualByComparingTo(janvier ? "918000" : "18000"); // achat (+ acquisition datee du 1er janvier)
        CalculTvaDto tvaJanvier = declarationFiscaleService.calculerTva(entrepriseA.id(), LocalDate.now().getYear() + "-01", principalA);
        assertThat(tvaJanvier.montantDeductible()).isEqualByComparingTo(janvier ? "918000" : "900000"); // TVA de l'acquisition, portee par le compte resolu

        // --- 17/18 : toutes les ecritures de A sont equilibrees ET portent une proposition du moteur
        List<EcritureComptableDto> ecritures = ecritureComptableService.lister(entrepriseA.id(), principalA);
        assertThat(ecritures).hasSizeGreaterThanOrEqualTo(8);
        for (EcritureComptableDto ecriture : ecritures) {
            List<AccountingOperationProposal> origine = proposalRepository.findByEcritureId(ecriture.id());
            assertThat(origine).as("ecriture %s sans proposition du moteur", ecriture.numeroPiece()).hasSize(1);
            assertThat(ecriture.totalDebit()).as("ecriture %s desequilibree", ecriture.numeroPiece()).isEqualByComparingTo(ecriture.totalCredit());
        }
        assertThat(types(ecritures)).contains(AccountingOperationType.ACHAT, AccountingOperationType.VENTE,
                AccountingOperationType.REGLEMENT_FOURNISSEUR, AccountingOperationType.STOCK_ENTREE, AccountingOperationType.STOCK_SORTIE,
                AccountingOperationType.REGULARISATION_INVENTAIRE, AccountingOperationType.IMMOBILISATION_ACQUISITION,
                AccountingOperationType.AMORTISSEMENT_DOTATION);
        assertThat(ecritures).extracting(EcritureComptableDto::id).contains(achat.id(), vente.id(), reglementEcriture);

        // --- 19 : l'audit raconte les decisions
        List<String> actions = evenements(principalA).stream().map(AuditEvent::getAction).toList();
        assertThat(actions).contains("PROPOSITION_COMPTABLE_CREEE", "PROPOSITION_COMPTABLE_VALIDEE", "ECRITURE_CREEE",
                "IMMOBILISATION_ENREGISTREE", "IMMOBILISATION_ACQUISE", "IMMOBILISATION_MISE_EN_SERVICE", "AMORTISSEMENT_COMPTABILISE");
        assertThat(evenements(principalB)).extracting(AuditEvent::getAction).doesNotContain("IMMOBILISATION_ACQUISE", "AMORTISSEMENT_COMPTABILISE");
    }

    // =================================================================================== isolation (20)

    @Test
    void entreprise_B_est_inaccessible_stock_immobilisation_tiers_compte_et_proposition() {
        TiersDto fournisseurB = tiersService.creer(entrepriseB.id(), TypeTiers.FOURNISSEUR, "Fournisseur B", null, principalB);
        ArticleDto articleB = articleService.creer(entrepriseB.id(), new CreerArticleRequest("ART-B", "Article B", null, TypeArticle.MARCHANDISE,
                "u", BigDecimal.TEN, BigDecimal.ZERO, compte(entrepriseB, "311"), compte(entrepriseB, "601"), compte(entrepriseB, "701"),
                compte(entrepriseB, "6031"), null, null, null), principalB);
        ImmobilisationDto immoB = immobilisationService.creer(entrepriseB.id(), new CreerImmobilisationRequest(compte(entrepriseB, "244"),
                compte(entrepriseB, "281"), "Machine B", TypeImmobilisation.CORPORELLE, LocalDate.now(), new BigDecimal("100000"), 5,
                ModeAmortissement.LINEAIRE), principalB);
        AccountingOperationProposalDto propositionB = engine.proposerReglement(entrepriseB.id(), new ReglementOperationRequest(
                AccountingOperationType.REGLEMENT_FOURNISSEUR, fournisseurB.id(), LocalDate.now(), "V", "Reglement B",
                new BigDecimal("1000"), MoyenPaiement.VIREMENT), principalB);
        TiersDto fournisseurA = tiersService.creer(entrepriseA.id(), TypeTiers.FOURNISSEUR, "Fournisseur A", null, principalA);

        // L'utilisateur A, meme avec le bon identifiant, ne peut rien faire sur les objets de B via son entreprise.
        assertThat(statut(() -> immobilisationService.mettreEnService(entrepriseA.id(), immoB.id(), new MettreEnServiceRequest(LocalDate.now()), principalA))).isEqualTo(404);
        assertThat(statut(() -> immobilisationService.sortir(entrepriseA.id(), immoB.id(), new SortirImmobilisationRequest(LocalDate.now(), MotifSortie.MISE_AU_REBUT, null), principalA))).isEqualTo(404);
        assertThat(statut(() -> immobilisationService.comptabiliserAcquisition(entrepriseA.id(), immoB.id(), new ComptabiliserAcquisitionRequest(fournisseurA.id(), null, null), principalA))).isEqualTo(404);
        assertThat(statut(() -> mouvementStockService.enregistrer(entrepriseA.id(), new CreerMouvementStockRequest(articleB.id(), TypeMouvementStock.ENTREE,
                BigDecimal.ONE, BigDecimal.TEN, LocalDate.now(), "x"), principalA))).isEqualTo(404);
        assertThat(statut(() -> engine.valider(entrepriseA.id(), propositionB.id(), principalA))).isEqualTo(404);
        // ... ni utiliser le tiers ou le compte de B dans les operations de A
        assertThat(statut(() -> immobilisationService.comptabiliserAcquisition(entrepriseA.id(),
                immobilisationA().id(), new ComptabiliserAcquisitionRequest(fournisseurB.id(), null, null), principalA))).isEqualTo(404);
        assertThat(statut(() -> immobilisationService.creer(entrepriseA.id(), new CreerImmobilisationRequest(compte(entrepriseB, "244"),
                compte(entrepriseA, "281"), "Intrus", TypeImmobilisation.CORPORELLE, LocalDate.now(), BigDecimal.TEN, 5, ModeAmortissement.LINEAIRE), principalA))).isEqualTo(404);
        // Un autre tenant n'accede meme pas a l'entreprise
        assertThat(statut(() -> immobilisationService.lister(entrepriseB.id(), principalA))).isEqualTo(404);
        assertThat(immobilisationService.lister(entrepriseA.id(), principalA)).extracting(ImmobilisationDto::libelle).doesNotContain("Machine B");
    }

    // =================================================================================== NOT_CONFIGURED puis reconfiguration (21 a 26) et rejeu (22, 28)

    @Test
    void mapping_supprime_aucune_ecriture_NOT_CONFIGURED_puis_reconfiguration_et_rejeu_sans_doublon() {
        TiersDto fournisseur = tiersService.creer(entrepriseA.id(), TypeTiers.FOURNISSEUR, "Fournisseur", null, principalA);
        // Sans compte portant le role TVA recuperable, seul un mapping peut le fournir.
        compteComptableRepository.delete(compteComptableRepository.findByEntrepriseIdAndNumero(entrepriseA.id(), "4456").orElseThrow());
        AccountingAccountMappingDto mapping = mappingService.creer(entrepriseA.id(),
                new CreerAccountingAccountMappingRequest(AccountPurpose.TVA_RECUPERABLE, null, compte(entrepriseA, "441"), null), principalA);

        ImmobilisationDto premiere = creerImmobilisation("Premiere");
        ImmobilisationDto avecMapping = immobilisationService.comptabiliserAcquisition(entrepriseA.id(), premiere.id(),
                new ComptabiliserAcquisitionRequest(fournisseur.id(), new BigDecimal("18000"), null), principalA);
        assertThat(avecMapping.acquisitionEcritureId()).isNotNull();   // resolu par le mapping
        assertThat(ecriture(avecMapping.acquisitionEcritureId()).lignes())
                .anyMatch(l -> "441".equals(l.compteNumero()) && l.debit().compareTo(new BigDecimal("18000")) == 0);

        // 21/23 : suppression du mapping -> NOT_CONFIGURED, aucune ecriture, refus explicite et audite
        mappingService.supprimer(entrepriseA.id(), mapping.id(), principalA);
        ImmobilisationDto seconde = creerImmobilisation("Seconde");
        long avant = nombreEcritures(entrepriseA);
        ResponseStatusException refus = refus(() -> immobilisationService.comptabiliserAcquisition(entrepriseA.id(), seconde.id(),
                new ComptabiliserAcquisitionRequest(fournisseur.id(), new BigDecimal("18000"), null), principalA));
        assertThat(refus.getStatusCode().value()).isEqualTo(422);
        assertThat(refus.getReason()).contains("TVA recuperable").contains("NOT_CONFIGURED");
        assertThat(nombreEcritures(entrepriseA)).isEqualTo(avant);
        assertThat(immobilisationService.lister(entrepriseA.id(), principalA)).filteredOn(i -> i.id().equals(seconde.id()))
                .allMatch(i -> i.acquisitionEcritureId() == null);
        assertThat(evenements(principalA)).extracting(AuditEvent::getAction).contains("PROPOSITION_COMPTABLE_A_REVOIR");

        // 24/25/26 : reconfiguration puis rejeu -> succes
        mappingService.creer(entrepriseA.id(), new CreerAccountingAccountMappingRequest(AccountPurpose.TVA_RECUPERABLE, null, compte(entrepriseA, "441"), null), principalA);
        ImmobilisationDto rejouee = immobilisationService.comptabiliserAcquisition(entrepriseA.id(), seconde.id(),
                new ComptabiliserAcquisitionRequest(fournisseur.id(), new BigDecimal("18000"), null), principalA);
        assertThat(rejouee.acquisitionEcritureId()).isNotNull();
        assertThat(nombreEcritures(entrepriseA)).isEqualTo(avant + 1);

        // 28 : rejouer encore la meme commande ne cree jamais de seconde ecriture
        assertThat(statut(() -> immobilisationService.comptabiliserAcquisition(entrepriseA.id(), seconde.id(),
                new ComptabiliserAcquisitionRequest(fournisseur.id(), new BigDecimal("18000"), null), principalA))).isEqualTo(409);
        assertThat(nombreEcritures(entrepriseA)).isEqualTo(avant + 1);
    }

    // =================================================================================== stock : configuration

    @Test
    void stock_compte_desactive_est_INVALID_le_mouvement_est_refuse_et_rien_n_est_enregistre() {
        ArticleDto article = articleService.creer(entrepriseA.id(), new CreerArticleRequest("ART-X", "Article X", null, TypeArticle.MARCHANDISE,
                "u", BigDecimal.TEN, BigDecimal.ZERO, compte(entrepriseA, "311"), compte(entrepriseA, "601"), compte(entrepriseA, "701"),
                compte(entrepriseA, "6031"), null, null, null), principalA);
        CompteComptable variation = compteComptableRepository.findById(compte(entrepriseA, "6031")).orElseThrow();
        variation.setActif(false);
        compteComptableRepository.save(variation);
        long avant = nombreEcritures(entrepriseA);

        ResponseStatusException refus = refus(() -> mouvementStockService.enregistrer(entrepriseA.id(), new CreerMouvementStockRequest(
                article.id(), TypeMouvementStock.ENTREE, new BigDecimal("10"), new BigDecimal("1000"), LocalDate.now(), "Entree"), principalA));

        assertThat(refus.getStatusCode().value()).isEqualTo(422);
        assertThat(refus.getReason()).contains("variation de stock").contains("INVALID");
        assertThat(nombreEcritures(entrepriseA)).isEqualTo(avant);
        assertThat(articleRepository.findById(article.id()).orElseThrow().getQuantiteEnStock()).isEqualByComparingTo("0"); // CUMP et quantite intacts
        assertThat(mouvementStockService.lister(entrepriseA.id(), article.id(), principalA)).isEmpty();
    }

    @Test
    void stock_un_ajustement_negatif_et_un_positif_sont_comptabilises_equilibres_via_le_moteur() {
        ArticleDto article = articleService.creer(entrepriseA.id(), new CreerArticleRequest("ART-Y", "Article Y", null, TypeArticle.MARCHANDISE,
                "u", BigDecimal.TEN, BigDecimal.ZERO, compte(entrepriseA, "311"), compte(entrepriseA, "601"), compte(entrepriseA, "701"),
                compte(entrepriseA, "6031"), null, null, null), principalA);
        mouvementStockService.enregistrer(entrepriseA.id(), new CreerMouvementStockRequest(article.id(), TypeMouvementStock.ENTREE,
                new BigDecimal("10"), new BigDecimal("1000"), LocalDate.now(), "Entree"), principalA);

        MouvementStockDto perte = mouvementStockService.enregistrer(entrepriseA.id(), new CreerMouvementStockRequest(article.id(),
                TypeMouvementStock.AJUSTEMENT, new BigDecimal("-2"), null, LocalDate.now(), "Casse"), principalA);
        MouvementStockDto surplus = mouvementStockService.enregistrer(entrepriseA.id(), new CreerMouvementStockRequest(article.id(),
                TypeMouvementStock.AJUSTEMENT, new BigDecimal("1"), null, LocalDate.now(), "Retrouve"), principalA);

        EcritureComptableDto ecriturePerte = ecriture(perte.ecritureId());
        assertThat(ecriturePerte.totalDebit()).isEqualByComparingTo(ecriturePerte.totalCredit());
        assertThat(ecriturePerte.lignes()).anyMatch(l -> "6031".equals(l.compteNumero()) && l.debit().compareTo(new BigDecimal("2000")) == 0);
        assertThat(ecriturePerte.lignes()).anyMatch(l -> "311".equals(l.compteNumero()) && l.credit().compareTo(new BigDecimal("2000")) == 0);
        EcritureComptableDto ecritureSurplus = ecriture(surplus.ecritureId());
        assertThat(ecritureSurplus.lignes()).anyMatch(l -> "311".equals(l.compteNumero()) && l.debit().compareTo(new BigDecimal("1000")) == 0);
        assertThat(proposalRepository.findByEcritureId(ecriturePerte.id()).get(0).getType()).isEqualTo(AccountingOperationType.STOCK_AJUSTEMENT);
        assertThat(ecriturePerte.statut()).isEqualTo(StatutEcriture.VALIDEE.name());
    }

    // =================================================================================== tiers

    @Test
    void creation_de_tiers_sans_compte_collectif_configure_est_refusee_explicitement() {
        // Aucun mapping CLIENT et le compte portant le role RACINE_CLIENT est desactive dans cette entreprise.
        CompteComptable racine = compteComptableRepository.findByEntrepriseIdAndNumero(entrepriseA.id(), "411").orElseThrow();
        racine.setActif(false);
        compteComptableRepository.save(racine);

        ResponseStatusException refus = refus(() -> tiersService.creer(entrepriseA.id(), TypeTiers.CLIENT, "Client sans racine", null, principalA));

        assertThat(refus.getStatusCode().value()).isEqualTo(422);
        assertThat(refus.getReason()).contains("clients").contains("INVALID");
        // le fournisseur reste possible : le refus est propre a l'objectif CLIENT
        assertThat(tiersService.creer(entrepriseA.id(), TypeTiers.FOURNISSEUR, "Fournisseur ok", null, principalA).id()).isNotNull();
    }

    // =================================================================================== immobilisation : cycle de vie et rejeu

    @Test
    void immobilisation_cycle_de_vie_dotations_rejouables_sans_doublon_et_sortie_sans_ecriture() {
        int annee = LocalDate.now().getYear();
        ImmobilisationDto immo = creerImmobilisation("Cycle");

        // pas d'amortissement tant que l'immobilisation n'est pas mise en service
        assertThat(immobilisationService.genererDotations(entrepriseA.id(), annee, principalA).nombreImmobilisations()).isZero();

        immobilisationService.mettreEnService(entrepriseA.id(), immo.id(), new MettreEnServiceRequest(LocalDate.now().withDayOfYear(1)), principalA);
        GenererDotationsResultDto premiere = immobilisationService.genererDotations(entrepriseA.id(), annee, principalA);
        long ecrituresApres = nombreEcritures(entrepriseA);
        GenererDotationsResultDto rejeu = immobilisationService.genererDotations(entrepriseA.id(), annee, principalA);

        assertThat(premiere.nombreImmobilisations()).isEqualTo(1);
        assertThat(rejeu.nombreImmobilisations()).isZero();                       // double dotation impossible
        assertThat(nombreEcritures(entrepriseA)).isEqualTo(ecrituresApres);
        EcritureComptableDto dotation = ecriture(premiere.ecritureIds().get(0));
        assertThat(dotation.lignes()).anyMatch(l -> "681".equals(l.compteNumero()) && l.debit().signum() > 0);
        assertThat(dotation.lignes()).anyMatch(l -> "281".equals(l.compteNumero()) && l.credit().signum() > 0);

        // sortie : le cycle est coherent, aucune ecriture inventee, plus de dotation apres l'annee de sortie
        long avantSortie = nombreEcritures(entrepriseA);
        ImmobilisationDto sortie = immobilisationService.sortir(entrepriseA.id(), immo.id(),
                new SortirImmobilisationRequest(LocalDate.now(), MotifSortie.CESSION, new BigDecimal("1500000")), principalA);
        assertThat(sortie.statut()).isEqualTo("SORTIE");
        assertThat(nombreEcritures(entrepriseA)).isEqualTo(avantSortie);
        assertThat(statut(() -> immobilisationService.mettreEnService(entrepriseA.id(), immo.id(), new MettreEnServiceRequest(LocalDate.now()), principalA))).isEqualTo(409);
        assertThat(evenements(principalA)).extracting(AuditEvent::getAction).contains("IMMOBILISATION_SORTIE");
    }

    @Test
    void rejeu_d_une_commande_stock_pour_la_meme_source_retrouve_la_meme_ecriture() {
        ArticleDto article = articleService.creer(entrepriseA.id(), new CreerArticleRequest("ART-Z", "Article Z", null, TypeArticle.MARCHANDISE,
                "u", BigDecimal.TEN, BigDecimal.ZERO, compte(entrepriseA, "311"), compte(entrepriseA, "601"), compte(entrepriseA, "701"),
                compte(entrepriseA, "6031"), null, null, null), principalA);
        MouvementStockDto mouvement = mouvementStockService.enregistrer(entrepriseA.id(), new CreerMouvementStockRequest(article.id(),
                TypeMouvementStock.ENTREE, new BigDecimal("4"), new BigDecimal("500"), LocalDate.now(), "Entree"), principalA);

        // Le meme evenement economique (meme mouvement) rejoue vers le moteur ne cree pas de seconde ecriture.
        long avant = nombreEcritures(entrepriseA);
        AccountingOperationProposal origine = proposalRepository.findByEcritureId(mouvement.ecritureId()).get(0);
        AccountingOperationProposalDto rejeu = engine.proposerEtValider(entrepriseA.id(), AccountingOperationType.STOCK_ENTREE, null, LocalDate.now(), "rejeu", null, new com.example.comptable.comptabilite.operation.common.ResultatRegle(null, List.of(), List.of(), List.of()), AccountingSource.de(MouvementStockService.SOURCE_TYPE_MOUVEMENT, mouvement.id(), "ENTREE"), StatutEcriture.VALIDEE, principalA);

        assertThat(rejeu.id()).isEqualTo(origine.getId());
        assertThat(rejeu.ecritureId()).isEqualTo(mouvement.ecritureId());
        assertThat(nombreEcritures(entrepriseA)).isEqualTo(avant);
    }

    // =================================================================================== utilitaires

    private ImmobilisationDto creerImmobilisation(String libelle) {
        return immobilisationService.creer(entrepriseA.id(), new CreerImmobilisationRequest(compte(entrepriseA, "244"),
                compte(entrepriseA, "281"), libelle, TypeImmobilisation.CORPORELLE, LocalDate.now().withDayOfYear(1),
                new BigDecimal("5000000"), 5, ModeAmortissement.LINEAIRE), principalA);
    }

    private ImmobilisationDto immobilisationA() {
        return creerImmobilisation("Immobilisation A " + UUID.randomUUID());
    }

    private String compteNumeroDuTiers(TiersDto tiers) {
        return compteComptableRepository.findById(tiers.compteComptableId()).orElseThrow().getNumero();
    }

    private List<AccountingOperationType> types(List<EcritureComptableDto> ecritures) {
        return ecritures.stream().flatMap(e -> proposalRepository.findByEcritureId(e.id()).stream())
                .map(AccountingOperationProposal::getType).toList();
    }

    private EcritureComptableDto ecriture(UUID id) {
        return ecritureComptableService.obtenir(entrepriseA.id(), id, principalA);
    }

    private long nombreEcritures(EntrepriseDto entreprise) {
        return ecritureComptableRepository.findAll().stream().filter(e -> e.getEntreprise().getId().equals(entreprise.id())).count();
    }

    private List<AuditEvent> evenements(UtilisateurPrincipal principal) {
        return auditEventRepository.findAll().stream().filter(e -> principal.getTenantId().equals(e.getTenantId())).toList();
    }

    private UUID compte(EntrepriseDto entreprise, String numero) {
        return compteComptableRepository.findByEntrepriseIdAndNumero(entreprise.id(), numero).orElseThrow().getId();
    }

    private ResponseStatusException refus(Runnable action) {
        try {
            action.run();
        } catch (ResponseStatusException e) {
            return e;
        }
        throw new AssertionError("Une ResponseStatusException etait attendue");
    }

    private int statut(Supplier<?> action) {
        try {
            action.get();
        } catch (ResponseStatusException e) {
            return e.getStatusCode().value();
        }
        throw new AssertionError("Une ResponseStatusException etait attendue");
    }

    private Document document(EntrepriseDto entreprise, TypeDocument type, String ht, String tva, String ttc) {
        Document document = new Document();
        document.setTenant(entrepriseRepository.findById(entreprise.id()).orElseThrow().getTenant());
        document.setEntreprise(entrepriseRepository.getReferenceById(entreprise.id()));
        document.setTypeDocument(type);
        document.setCanalSource(CanalSource.WEB);
        document.setCheminFichier("test/facture.pdf");
        document.setMontantHt(new BigDecimal(ht));
        document.setMontantTva(new BigDecimal(tva));
        document.setMontantTtc(new BigDecimal(ttc));
        document.setDatePiece(LocalDate.now());
        return document;
    }

    private EntrepriseDto creerEntreprise(String nom, UtilisateurPrincipal principal) {
        return entrepriseService.creer(new CreerEntrepriseRequest(nom + " " + UUID.randomUUID(), null, null, null, null, null, "CI", "XOF"), principal);
    }

    private UtilisateurPrincipal creerPrincipal() {
        Tenant tenant = new Tenant();
        tenant.setRaisonSociale("Cabinet Phase A");
        tenant.setSlug("cabinet-phase-a-" + UUID.randomUUID());
        tenant.setPays("CI");
        tenant = tenantRepository.save(tenant);

        Role role = roleRepository.findByCode("EXPERT_COMPTABLE").orElseThrow();
        Utilisateur utilisateur = new Utilisateur();
        utilisateur.setTenant(tenant);
        utilisateur.setEmail("phase-a-" + UUID.randomUUID() + "@test.ci");
        utilisateur.setMotDePasse("hash");
        utilisateur.setNom("Test");
        utilisateur.setPrenom("PhaseA");
        utilisateur.setRoles(Set.of(role));
        return new UtilisateurPrincipal(utilisateurRepository.save(utilisateur));
    }
}
