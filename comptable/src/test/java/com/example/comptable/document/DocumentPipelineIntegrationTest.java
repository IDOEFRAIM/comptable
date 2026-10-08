package com.example.comptable.document;

import com.example.comptable.agent.finding.FindingService;
import com.example.comptable.agent.finding.domain.Finding;
import com.example.comptable.agent.finding.domain.StatutFinding;
import com.example.comptable.agent.finding.domain.TypeResolution;
import com.example.comptable.agent.finding.repository.FindingRepository;
import com.example.comptable.agent.whatsapp.dto.DonneesExtraitesDTO;
import com.example.comptable.audit.domain.AuditEvent;
import com.example.comptable.audit.repository.AuditEventRepository;
import com.example.comptable.comptabilite.domain.CompteComptable;
import com.example.comptable.comptabilite.domain.TypeTiers;
import com.example.comptable.comptabilite.lineage.AccountingLineageDto;
import com.example.comptable.comptabilite.lineage.AccountingLineageService;
import com.example.comptable.comptabilite.repository.CompteComptableRepository;
import com.example.comptable.comptabilite.service.ComptabilisationService;
import com.example.comptable.comptabilite.service.TiersService;
import com.example.comptable.comptabilite.web.dto.ComptabiliserDocumentRequest;
import com.example.comptable.comptabilite.web.dto.TiersDto;
import com.example.comptable.controle.ControlCodes;
import com.example.comptable.controle.ControlRunService;
import com.example.comptable.controle.revue.RevueComptableService;
import com.example.comptable.controle.revue.RevueDto;
import com.example.comptable.demande.domain.StatutDemandePiece;
import com.example.comptable.demande.repository.DemandePieceRepository;
import com.example.comptable.demande.service.DemandePieceService;
import com.example.comptable.demande.web.dto.DemandePieceDto;
import com.example.comptable.demande.web.dto.DemanderPieceRequest;
import com.example.comptable.document.domain.CanalSource;
import com.example.comptable.document.domain.Document;
import com.example.comptable.document.domain.OrigineRapprochement;
import com.example.comptable.document.domain.QualiteClassification;
import com.example.comptable.document.domain.StatutRapprochementDocument;
import com.example.comptable.document.domain.TypeDocument;
import com.example.comptable.document.hub.DocumentHubDto;
import com.example.comptable.document.hub.DocumentHubService;
import com.example.comptable.document.repository.DocumentRepository;
import com.example.comptable.document.service.ContentHasher;
import com.example.comptable.document.service.DocumentMatcher;
import com.example.comptable.document.service.DocumentService;
import com.example.comptable.document.service.DocumentTraitementService;
import com.example.comptable.document.web.dto.DocumentDto;
import com.example.comptable.entreprise.domain.Entreprise;
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
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.web.server.ResponseStatusException;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.function.Supplier;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Phase C sur PostgreSQL reel : une piece qui entre dans Comptable devient exploitable (hash, numero normalise, tiers),
 * deduplique, rapprochee ou clairement signalee comme exception - et jamais trancher a la place du comptable.
 */
@SpringBootTest(properties = "spring.docker.compose.skip.in-tests=false")
class DocumentPipelineIntegrationTest {

    @Autowired private TenantRepository tenantRepository;
    @Autowired private UtilisateurRepository utilisateurRepository;
    @Autowired private RoleRepository roleRepository;
    @Autowired private EntrepriseService entrepriseService;
    @Autowired private EntrepriseRepository entrepriseRepository;
    @Autowired private TiersService tiersService;
    @Autowired private ArticleService articleService;
    @Autowired private CommandeAchatService commandeAchatService;
    @Autowired private CompteComptableRepository compteComptableRepository;
    @Autowired private FactureFournisseurRepository factureFournisseurRepository;
    @Autowired private DocumentService documentService;
    @Autowired private DocumentRepository documentRepository;
    @Autowired private DocumentTraitementService traitementService;
    @Autowired private DocumentHubService hubService;
    @Autowired private ControlRunService controlRunService;
    @Autowired private FindingRepository findingRepository;
    @Autowired private FindingService findingService;
    @Autowired private RevueComptableService revueService;
    @Autowired private DemandePieceService demandePieceService;
    @Autowired private DemandePieceRepository demandePieceRepository;
    @Autowired private AccountingLineageService lineageService;
    @Autowired private ComptabilisationService comptabilisationService;
    @Autowired private AuditEventRepository auditEventRepository;

    private UtilisateurPrincipal principalA;
    private UtilisateurPrincipal principalB;
    private EntrepriseDto entrepriseA;
    private EntrepriseDto entrepriseB;
    private TiersDto fournisseurA;
    private ArticleDto articleA;

    @BeforeEach
    void amorcer() {
        principalA = creerPrincipal();
        principalB = creerPrincipal();
        entrepriseA = creerEntreprise(principalA);
        entrepriseB = creerEntreprise(principalB);
        fournisseurA = tiersService.creer(entrepriseA.id(), TypeTiers.FOURNISSEUR, "Cimenterie SARL", null, principalA);
        articleA = creerArticle(entrepriseA, principalA);
    }

    // =====================================================================================================
    // Scenario : de la piece brute a l'operation fiable (identite, doublons, rapprochement, lineage, manquants)
    // =====================================================================================================

    @Test
    void de_la_piece_brute_a_l_operation_fiable() {
        // 1-2. entreprises A et B, fournisseur de A
        assertThat(entrepriseA.id()).isNotEqualTo(entrepriseB.id());

        // 3-4. facture fournisseur recue : identite forte, numero normalise, fournisseur identifie avec certitude
        byte[] fichier1 = "facture cimenterie FAC-001 118000".getBytes(StandardCharsets.UTF_8);
        Document d1 = deposer(entrepriseA, fichier1, "fac-001.pdf", "Cimenterie SARL", "FAC-001", "118000", "18000", "100000");
        assertThat(d1.getContentHash()).isEqualTo(ContentHasher.sha256(fichier1)).hasSize(64);
        assertThat(d1.getNumeroFacture()).isEqualTo("FAC-001");
        assertThat(d1.getNumeroFactureNormalise()).isEqualTo("FAC001");
        assertThat(d1.getTiersId()).isEqualTo(fournisseurA.id());
        assertThat(d1.getStatutClassification()).isEqualTo(QualiteClassification.EXACT);
        assertThat(d1.getStatutRapprochement()).isEqualTo(StatutRapprochementDocument.NON_RAPPROCHE);

        // 5-6. exactement le meme fichier : doublon exact detecte ; l'original n'est jamais signale
        Document d2 = deposer(entrepriseA, fichier1, "fac-001-bis.pdf", "Cimenterie SARL", "FAC-001", "118000", "18000", "100000");
        assertThat(d2.getContentHash()).isEqualTo(d1.getContentHash());
        controlRunService.executer(entreprise(entrepriseA));
        assertThat(actifsDocument(ControlCodes.EXACT_DUPLICATE)).singleElement().satisfies(f -> {
            assertThat(f.getSourceId()).isEqualTo(d2.getId().toString());
            assertThat(f.getEvidenceJson().get("originalId")).isEqualTo(d1.getId().toString());
        });

        // 7-8. autre fichier, meme fournisseur, meme numero (ecrit « FAC- 001 ») : DUPLICATE_INVOICE
        Document d3 = deposer(entrepriseA, "autre scan FAC 001".getBytes(StandardCharsets.UTF_8), "scan.pdf", "Cimenterie SARL", "FAC- 001", "118000", "18000", "100000");
        assertThat(d3.getNumeroFactureNormalise()).isEqualTo("FAC001");
        controlRunService.executer(entreprise(entrepriseA));
        assertThat(actifsDocument(ControlCodes.DUPLICATE_INVOICE)).singleElement()
                .satisfies(f -> assertThat(f.getSourceId()).isEqualTo(d3.getId().toString()));
        assertThat(actifsDocument(ControlCodes.EXACT_DUPLICATE)).hasSize(1); // d3 n'est pas aussi un doublon de fichier
        assertThat(actifsDocument(ControlCodes.DUPLICATE_INVOICE).stream().map(Finding::getSourceId)).doesNotContain(d1.getId().toString());

        // 9-11. une facture fournisseur enregistree (23 600 TTC) ; la piece du fournisseur arrive SANS numero connu cote facture
        // -> PROBABLE : proposee, jamais appliquee d'office
        FactureFournisseurDto ff1 = facturer(entrepriseA, principalA, fournisseurA, 10);
        Document d4 = deposer(entrepriseA, "piece ff1".getBytes(StandardCharsets.UTF_8), "ff1.pdf", "Cimenterie SARL", "FA/778", "23600", "3600", "20000");
        assertThat(d4.getStatutRapprochement()).isEqualTo(StatutRapprochementDocument.A_CONFIRMER);
        assertThat(d4.getCibleId()).isEqualTo(ff1.id());
        assertThat(d4.getEcritureId()).isNull();
        assertThat(documentRepository.findById(d4.getId()).orElseThrow().getRapprochePar()).isNull();

        // le comptable confirme : association humaine, numero du fournisseur reporte sur la facture, piece = justificatif de l'ecriture
        traitementService.valider(entrepriseA.id(), d4.getId(), principalA);
        Document d4b = recharger(d4);
        assertThat(d4b.getStatutRapprochement()).isEqualTo(StatutRapprochementDocument.RAPPROCHE);
        assertThat(d4b.getRapprochePar()).isEqualTo(OrigineRapprochement.HUMAIN);
        assertThat(d4b.getValideAt()).isNotNull();
        assertThat(d4b.getEcritureId()).isEqualTo(ff1.ecritureId());
        FactureFournisseur ff1Reload = factureFournisseurRepository.findById(ff1.id()).orElseThrow();
        assertThat(ff1Reload.getNumeroFournisseur()).isEqualTo("FA/778");
        assertThat(ff1Reload.getNumeroFournisseurNormalise()).isEqualTo("FA778");

        // 12. lineage dans les deux sens, sans heuristique : Document -> Facture -> Proposition -> Ecriture et retour
        AccountingLineageDto.DepuisDocument chaine = lineageService.depuisDocument(entrepriseA.id(), d4.getId(), principalA);
        assertThat(chaine.cible().type()).isEqualTo("FACTURE_FOURNISSEUR");
        assertThat(chaine.cible().id()).isEqualTo(ff1.id());
        assertThat(chaine.consequences()).singleElement().satisfies(p -> {
            assertThat(p.type()).isEqualTo("ACHAT");
            assertThat(p.ecritureId()).isEqualTo(ff1.ecritureId());
        });
        AccountingLineageDto.DepuisEcriture retour = lineageService.depuisEcriture(entrepriseA.id(), ff1.ecritureId(), principalA);
        assertThat(retour.documents()).extracting(AccountingLineageDto.DocumentOrigine::id).contains(d4.getId());
        assertThat(lineageService.depuisSource(entrepriseA.id(), "FACTURE_FOURNISSEUR", ff1.id(), principalA).documents())
                .extracting(AccountingLineageDto.DocumentOrigine::id).contains(d4.getId());

        // rapprochement CERTAIN : meme fournisseur + meme numero + meme montant -> automatique, sans intervention
        FactureFournisseurDto ff2 = facturer(entrepriseA, principalA, fournisseurA, 5);
        definirNumeroFournisseur(ff2.id(), "FA-900");
        Document d5 = deposer(entrepriseA, "piece ff2".getBytes(StandardCharsets.UTF_8), "ff2.pdf", "Cimenterie SARL", "FA 900", "11800", "1800", "10000");
        assertThat(d5.getStatutRapprochement()).isEqualTo(StatutRapprochementDocument.RAPPROCHE);
        assertThat(d5.getRapprochePar()).isEqualTo(OrigineRapprochement.SYSTEME);
        assertThat(d5.getEcritureId()).isEqualTo(ff2.ecritureId());
        assertThat(d5.getValideAt()).isNull(); // automatique mais pas encore valide par un humain

        // 13-15. operation SANS piece -> MISSING_DOCUMENT (seulement elle)
        FactureFournisseurDto ff3 = facturer(entrepriseA, principalA, fournisseurA, 3);
        definirNumeroFournisseur(ff3.id(), "FA-300");
        controlRunService.executer(entreprise(entrepriseA));
        assertThat(actifs(ControlCodes.MISSING_DOCUMENT)).singleElement().satisfies(f -> {
            assertThat(f.getSourceType()).isEqualTo("ECRITURE_COMPTABLE");
            assertThat(f.getSourceId()).isEqualTo(ff3.ecritureId().toString());
        });

        // 16-19. la piece correspondante arrive, est rapprochee, et le constat est resolu par le SYSTEME (pas par un humain)
        Document d6 = deposer(entrepriseA, "piece ff3".getBytes(StandardCharsets.UTF_8), "ff3.pdf", "Cimenterie SARL", "FA-300", "7080", "1080", "6000");
        assertThat(d6.getStatutRapprochement()).isEqualTo(StatutRapprochementDocument.RAPPROCHE);
        controlRunService.executer(entreprise(entrepriseA));
        assertThat(actifs(ControlCodes.MISSING_DOCUMENT)).isEmpty();
        Finding manquant = tous(ControlCodes.MISSING_DOCUMENT).get(0);
        assertThat(manquant.getStatus()).isEqualTo(StatutFinding.RESOLVED);
        assertThat(manquant.getResolutionType()).isEqualTo(TypeResolution.SYSTEM);

        // 20-22. matching AMBIGU : deux factures de meme montant, sans numero de fournisseur -> aucun choix automatique
        FactureFournisseurDto ff4 = facturer(entrepriseA, principalA, fournisseurA, 4);
        FactureFournisseurDto ff5 = facturer(entrepriseA, principalA, fournisseurA, 4);
        Document d7 = deposer(entrepriseA, "piece ambigue".getBytes(StandardCharsets.UTF_8), "ambigu.pdf", "Cimenterie SARL", "AMB-1", "9440", "1440", "8000");
        assertThat(d7.getStatutRapprochement()).isEqualTo(StatutRapprochementDocument.AMBIGU);
        assertThat(d7.getCibleId()).isNull();
        assertThat(d7.getEcritureId()).isNull();
        List<DocumentMatcher.Candidat> candidats = traitementService.candidats(entrepriseA.id(), d7.getId(), principalA);
        assertThat(candidats).extracting(DocumentMatcher.Candidat::id).containsExactlyInAnyOrder(ff4.id(), ff5.id());
        controlRunService.executer(entreprise(entrepriseA));
        assertThat(actifsDocument(ControlCodes.DOCUMENT_AMBIGUOUS)).singleElement()
                .satisfies(f -> assertThat(f.getSourceId()).isEqualTo(d7.getId().toString()));

        // 23. l'Inbox ne contient QUE les exceptions : doublon exact, doublon de facture, piece ambigue, et les 2 operations sans piece
        RevueDto.Page inbox = revueService.lister(entrepriseA.id(), new RevueComptableService.Filtre(null, null, null, null, null, null, null, null, null),
                0, 100, principalA);
        // (les constats des etats financiers, ex. notes obligatoires incompletes, sont desormais publies proactivement et figurent aussi dans l'Inbox : hors sujet de ce scenario documentaire)
        assertThat(inbox.contenu().stream().map(RevueDto.Constat::code).filter(c -> !ControlCodes.REQUIRED_NOTE_INCOMPLETE.equals(c)).collect(Collectors.toList())).containsExactlyInAnyOrder(
                ControlCodes.EXACT_DUPLICATE, ControlCodes.DUPLICATE_INVOICE, ControlCodes.DOCUMENT_AMBIGUOUS,
                ControlCodes.MISSING_DOCUMENT, ControlCodes.MISSING_DOCUMENT);

        // le comptable tranche l'ambiguite : association humaine, constat ferme par le systeme, le MISSING_DOCUMENT de la facture choisie aussi
        traitementService.associer(entrepriseA.id(), d7.getId(), DocumentMatcher.CIBLE_FACTURE_FOURNISSEUR, ff4.id(), principalA);
        controlRunService.executer(entreprise(entrepriseA));
        assertThat(actifsDocument(ControlCodes.DOCUMENT_AMBIGUOUS)).isEmpty();
        assertThat(actifs(ControlCodes.MISSING_DOCUMENT)).singleElement()
                .satisfies(f -> assertThat(f.getSourceId()).isEqualTo(ff5.ecritureId().toString()));
        assertThat(statut(() -> traitementService.associer(entrepriseA.id(), d7.getId(), DocumentMatcher.CIBLE_FACTURE_FOURNISSEUR, ff5.id(), principalA)))
                .isEqualTo(409).as("l'ecriture de ff4 est deja justifiee ; la piece ne peut pas en justifier une autre");

        // collecte : demande de piece pour l'operation restante -> reponse -> rapprochee -> constat resolu par le systeme
        Finding manquantFf5 = actifs(ControlCodes.MISSING_DOCUMENT).get(0);
        DemandePieceDto demande = demandePieceService.demanderPourConstat(entrepriseA.id(), manquantFf5.getId(),
                new DemanderPieceRequest(LocalDate.now().plusDays(7), null, "WHATSAPP"), principalA);
        // Aucun client rattache a cette entreprise de test : la demande est BLOQUEE avec une raison explicite, aucun appel fournisseur.
        assertThat(demande.statut()).isEqualTo("ECHEC_ENVOI");
        assertThat(demande.motifBlocage()).isEqualTo("NO_CONTACT");
        assertThat(demande.ecritureId()).isEqualTo(ff5.ecritureId());
        assertThat(statut(() -> demandePieceService.demanderPourConstat(entrepriseA.id(), manquantFf5.getId(), null, principalA))).isEqualTo(409);
        demandePieceService.repondre(entrepriseA.id(), demande.id(), TypeDocument.FACTURE_ACHAT,
                new MockMultipartFile("fichier", "reponse.pdf", "application/pdf", "%PDF-1.4 reponse du client".getBytes(StandardCharsets.UTF_8)), principalA);
        assertThat(demandePieceRepository.findById(demande.id()).orElseThrow().getStatut()).isEqualTo(StatutDemandePiece.RAPPROCHEE);
        assertThat(actifs(ControlCodes.MISSING_DOCUMENT)).isEmpty();

        // doublon confirme par un humain : sort du travail, n'est pas supprime, ne revient pas
        traitementService.marquerDoublon(entrepriseA.id(), d2.getId(), d1.getId(), principalA);
        controlRunService.executer(entreprise(entrepriseA));
        assertThat(actifsDocument(ControlCodes.EXACT_DUPLICATE)).isEmpty();
        assertThat(recharger(d2).getDoublonDeId()).isEqualTo(d1.getId());
        assertThat(documentRepository.findById(d2.getId())).isPresent();

        // un constat ECARTE (avec motif) reste ecarte tant que la cause persiste
        UUID idConstatFacture = actifsDocument(ControlCodes.DUPLICATE_INVOICE).get(0).getId();
        findingService.rejeter(entrepriseA.id(), idConstatFacture, "Facture reemise a la demande du fournisseur", principalA);
        controlRunService.executer(entreprise(entrepriseA));
        assertThat(actifsDocument(ControlCodes.DUPLICATE_INVOICE)).isEmpty();
        assertThat(findingRepository.findById(idConstatFacture).orElseThrow().getStatus()).isEqualTo(StatutFinding.DISMISSED);

        // validation en masse : apercu obligatoire, uniquement les rapprochements automatiques non valides
        List<Document> apercu = traitementService.apercuValidationLot(entrepriseA.id(), principalA);
        assertThat(apercu).extracting(Document::getId).contains(d5.getId(), d6.getId()).doesNotContain(d4.getId(), d1.getId());
        DocumentTraitementService.LotResultat lot = traitementService.validerLot(entrepriseA.id(), List.of(d5.getId(), d1.getId()), principalA);
        assertThat(lot.valides()).containsExactly(d5.getId());
        assertThat(lot.ignores()).containsExactly(d1.getId());

        // une piece rapprochee a une facture deja comptabilisee ne se comptabilise pas une seconde fois
        CompteComptable compteAchat = compteComptableRepository.findByEntrepriseIdAndNumero(entrepriseA.id(), "601").orElseThrow();
        assertThat(statut(() -> comptabilisationService.comptabiliser(entrepriseA.id(), d5.getId(),
                new ComptabiliserDocumentRequest(fournisseurA.id(), compteAchat.getId(), null, null), principalA))).isEqualTo(409);

        // Hub : compteurs et liste coherents avec l'etat reel
        DocumentHubDto.Synthese synthese = hubService.synthese(entrepriseA.id(), null, principalA);
        assertThat(synthese.recus()).isEqualTo(documentRepository.count(
                (root, query, cb) -> cb.equal(root.get("entreprise").get("id"), entrepriseA.id())));
        assertThat(synthese.traites()).isGreaterThanOrEqualTo(4);
        DocumentHubDto.Page page = hubService.lister(entrepriseA.id(), new DocumentHubService.Filtre(null, null, null, null, null, "FA"), 0, 10, principalA);
        assertThat(page.contenu()).allSatisfy(l -> assertThat(l.numeroFacture() != null || l.id() != null).isTrue());
        assertThat(page.contenu()).anySatisfy(l -> {
            assertThat(l.association()).isNotNull();
            assertThat(l.tiersNom()).isEqualTo("Cimenterie SARL");
        });

        // piece avec un montant different de la facture portant le meme numero : DOCUMENT_AMOUNT_MISMATCH
        FactureFournisseurDto ff6 = facturer(entrepriseA, principalA, fournisseurA, 5);
        definirNumeroFournisseur(ff6.id(), "FA-600");
        Document d9 = deposer(entrepriseA, "piece ff6".getBytes(StandardCharsets.UTF_8), "ff6.pdf", "Cimenterie SARL", "FA 600", "12000", "2000", "10000");
        assertThat(d9.getStatutRapprochement()).isEqualTo(StatutRapprochementDocument.NON_RAPPROCHE);
        controlRunService.executer(entreprise(entrepriseA));
        assertThat(actifsDocument(ControlCodes.DOCUMENT_AMOUNT_MISMATCH)).singleElement().satisfies(f -> {
            assertThat(f.getSourceId()).isEqualTo(d9.getId().toString());
            assertThat(f.getDescription()).contains("12000").contains("11800");
        });

        // audit : toute la chaine est tracee
        for (String action : List.of("DOCUMENT_RECU", "DOCUMENT_EXTRAIT", "DOCUMENT_CLASSE", "DOCUMENT_RAPPROCHE", "DOCUMENT_RAPPROCHEMENT_PROPOSE",
                "DOCUMENT_VALIDE", "DOCUMENT_MARQUE_DOUBLON", "DOCUMENT_DEMANDE", "DOCUMENT_RECU_APRES_DEMANDE")) {
            assertThat(evenements(action)).as(action).isNotEmpty();
        }
    }

    // =====================================================================================================
    // Multi-tenant : rien ne fuit entre entreprises, y compris pour un meme fichier
    // =====================================================================================================

    @Test
    void les_pieces_les_demandes_et_les_constats_de_A_restent_invisibles_et_intouchables_depuis_B() {
        byte[] fichier = "facture partagee".getBytes(StandardCharsets.UTF_8);
        FactureFournisseurDto ffA = facturer(entrepriseA, principalA, fournisseurA, 2);
        TiersDto fournisseurB = tiersService.creer(entrepriseB.id(), TypeTiers.FOURNISSEUR, "Cimenterie SARL", null, principalB);
        Document dA = deposer(entrepriseA, fichier, "partage.pdf", "Cimenterie SARL", "F-1", "5900", "900", "5000");
        controlRunService.executer(entreprise(entrepriseA));
        Finding manquantA = actifs(ControlCodes.MISSING_DOCUMENT).get(0);

        // le meme fichier chez B n'est PAS un doublon (le hash est par entreprise) et ne revele rien de A
        Document dB = deposer(entrepriseB, fichier, "partage.pdf", "Cimenterie SARL", "F-1", "5900", "900", "5000");
        assertThat(dB.getContentHash()).isEqualTo(dA.getContentHash());
        assertThat(dB.getTiersId()).isEqualTo(fournisseurB.id());
        controlRunService.executer(entreprise(entrepriseB));
        assertThat(findingRepository.findActifsParCodes(entrepriseB.id(), List.of(ControlCodes.EXACT_DUPLICATE, ControlCodes.DUPLICATE_INVOICE))).isEmpty();
        assertThat(findingRepository.findActifsParCodes(entrepriseA.id(), List.of(ControlCodes.EXACT_DUPLICATE, ControlCodes.DUPLICATE_INVOICE))).isEmpty();

        // B ne voit pas la piece de A, ne la traite pas, ne la rapproche pas, ne voit pas sa chaine comptable
        assertThat(statut(() -> traitementService.candidats(entrepriseB.id(), dA.getId(), principalB))).isEqualTo(404);
        assertThat(statut(() -> traitementService.valider(entrepriseB.id(), dA.getId(), principalB))).isEqualTo(404);
        assertThat(statut(() -> traitementService.marquerDoublon(entrepriseB.id(), dA.getId(), dB.getId(), principalB))).isEqualTo(404);
        assertThat(statut(() -> lineageService.depuisDocument(entrepriseB.id(), dA.getId(), principalB))).isEqualTo(404);
        assertThat(statut(() -> hubService.synthese(entrepriseA.id(), null, principalB))).isEqualTo(404);
        assertThat(hubService.lister(entrepriseB.id(), new DocumentHubService.Filtre(null, null, null, null, null, null), 0, 50, principalB).contenu())
                .extracting(DocumentHubDto.Ligne::id).containsExactly(dB.getId());

        // matching impossible avec la facture d'une autre entreprise
        assertThat(statut(() -> traitementService.associer(entrepriseB.id(), dB.getId(), DocumentMatcher.CIBLE_FACTURE_FOURNISSEUR, ffA.id(), principalB))).isEqualTo(404);

        // demande de piece et constat documentaire de A : inaccessibles
        assertThat(statut(() -> demandePieceService.demanderPourConstat(entrepriseB.id(), manquantA.getId(), null, principalB))).isEqualTo(404);
        DemandePieceDto demandeA = demandePieceService.demanderPourConstat(entrepriseA.id(), manquantA.getId(), null, principalA);
        assertThat(statut(() -> demandePieceService.annuler(entrepriseB.id(), demandeA.id(), principalB))).isEqualTo(404);
        assertThat(statut(() -> revueService.obtenir(entrepriseB.id(), manquantA.getId(), principalB))).isEqualTo(404);
        assertThat(statut(() -> findingService.rejeter(entrepriseB.id(), manquantA.getId(), "motif", principalB))).isEqualTo(404);
    }

    @Test
    void une_proposition_rejetee_n_est_plus_reproposee_et_un_fournisseur_inconnu_n_avance_pas() {
        FactureFournisseurDto ff = facturer(entrepriseA, principalA, fournisseurA, 10);
        Document probable = deposer(entrepriseA, "p1".getBytes(StandardCharsets.UTF_8), "p1.pdf", "Cimenterie SARL", "X-1", "23600", "3600", "20000");
        assertThat(probable.getStatutRapprochement()).isEqualTo(StatutRapprochementDocument.A_CONFIRMER);

        traitementService.rejeterProposition(entrepriseA.id(), probable.getId(), principalA);
        assertThat(recharger(probable).getStatutRapprochement()).isEqualTo(StatutRapprochementDocument.NON_RAPPROCHE);
        traitementService.retraiter(entrepriseA.id(), probable.getId(), principalA);
        assertThat(recharger(probable).getStatutRapprochement()).as("la candidate refusee n'est pas reproposee").isEqualTo(StatutRapprochementDocument.NON_RAPPROCHE);
        assertThat(recharger(probable).getCibleRejeteeId()).isEqualTo(ff.id());
        assertThat(evenements("DOCUMENT_RAPPROCHEMENT_REJETE")).isNotEmpty();

        // fournisseur inconnu : jamais de rapprochement automatique, la piece attend un tiers
        Document inconnu = deposer(entrepriseA, "p2".getBytes(StandardCharsets.UTF_8), "p2.pdf", "Fournisseur Fantome", "Z-9", "23600", "3600", "20000");
        assertThat(inconnu.getStatutClassification()).isEqualTo(QualiteClassification.INCONNU);
        assertThat(inconnu.getTiersId()).isNull();
        assertThat(inconnu.getStatutRapprochement()).isEqualTo(StatutRapprochementDocument.NON_RAPPROCHE);
        assertThat(ff.id()).isNotNull();
    }

    // ----------------------------------------------------------------------------------------- utilitaires

    private List<Finding> actifs(String code) {
        return findingRepository.findActifsParCodes(entrepriseA.id(), List.of(code));
    }

    private List<Finding> actifsDocument(String code) {
        return actifs(code);
    }

    private List<Finding> tous(String code) {
        return findingRepository.findAll().stream()
                .filter(f -> f.getEntreprise().getId().equals(entrepriseA.id()) && f.getControlCode().equals(code)).toList();
    }

    private List<AuditEvent> evenements(String action) {
        return auditEventRepository.findAll().stream().filter(e -> action.equals(e.getAction())).toList();
    }

    private Entreprise entreprise(EntrepriseDto dto) {
        return entrepriseRepository.findById(dto.id()).orElseThrow();
    }

    private Document recharger(Document d) {
        return documentRepository.findById(d.getId()).orElseThrow();
    }

    /** Depot par un canal externe (API) avec une extraction maitrisee : traverse hash, tiers, rapprochement. */
    private Document deposer(EntrepriseDto entreprise, byte[] contenu, String nom, String fournisseur, String numero,
                             String ttc, String tva, String ht) {
        DonneesExtraitesDTO donnees = new DonneesExtraitesDTO(fournisseur, new BigDecimal(ht), new BigDecimal(tva), new BigDecimal(ttc),
                "XOF", null, LocalDate.now(), 0.95, numero);
        DocumentDto dto = documentService.ingererAvecExtractionExterne(entreprise.id(), TypeDocument.FACTURE_ACHAT, CanalSource.API,
                contenu, nom, "application/pdf", donnees, List.of());
        return documentRepository.findById(dto.id()).orElseThrow();
    }

    private void definirNumeroFournisseur(UUID factureId, String numero) {
        FactureFournisseur f = factureFournisseurRepository.findById(factureId).orElseThrow();
        f.setNumeroFournisseur(numero);
        factureFournisseurRepository.save(f);
    }

    private ArticleDto creerArticle(EntrepriseDto entreprise, UtilisateurPrincipal principal) {
        UUID stock = compte(entreprise, "311");
        return articleService.creer(entreprise.id(), new CreerArticleRequest("ART-" + UUID.randomUUID().toString().substring(0, 6), "Sac de ciment",
                "Materiaux", TypeArticle.MARCHANDISE, "unite", new BigDecimal("3000"), new BigDecimal("18"), stock, compte(entreprise, "601"),
                compte(entreprise, "701"), compte(entreprise, "6031"), null, PolitiqueStockNegatif.INTERDIT, ModeGestionStock.PERMANENT), principal);
    }

    /** Facture fournisseur reelle (commande receptionnee puis facturee par le moteur) : quantite x 2 000 HT, TVA 18 %. */
    private FactureFournisseurDto facturer(EntrepriseDto entreprise, UtilisateurPrincipal principal, TiersDto fournisseur, int quantite) {
        ArticleDto article = entreprise.id().equals(entrepriseA.id()) ? articleA : creerArticle(entreprise, principal);
        CommandeAchatDto commande = commandeAchatService.creer(entreprise.id(), new CreerCommandeAchatRequest(fournisseur.id(), LocalDate.now(),
                List.of(new LigneCommandeAchatRequest(article.id(), new BigDecimal(quantite), new BigDecimal("2000")))), principal);
        commandeAchatService.validerReception(entreprise.id(), commande.id(), principal);
        return commandeAchatService.genererFactureFournisseur(entreprise.id(), commande.id(), principal);
    }

    private UUID compte(EntrepriseDto entreprise, String numero) {
        return compteComptableRepository.findByEntrepriseIdAndNumero(entreprise.id(), numero).orElseThrow().getId();
    }

    private int statut(Supplier<?> action) {
        try {
            action.get();
        } catch (ResponseStatusException e) {
            return e.getStatusCode().value();
        }
        throw new AssertionError("Une ResponseStatusException etait attendue");
    }

    private EntrepriseDto creerEntreprise(UtilisateurPrincipal principal) {
        return entrepriseService.creer(new CreerEntrepriseRequest("PME Pieces " + UUID.randomUUID(), null, null, null, null, null, "CI", "XOF"), principal);
    }

    private UtilisateurPrincipal creerPrincipal() {
        Tenant tenant = new Tenant();
        tenant.setRaisonSociale("Cabinet Pieces");
        tenant.setSlug("cabinet-pieces-" + UUID.randomUUID());
        tenant.setPays("CI");
        tenant = tenantRepository.save(tenant);
        Role role = roleRepository.findByCode("EXPERT_COMPTABLE").orElseThrow();
        Utilisateur utilisateur = new Utilisateur();
        utilisateur.setTenant(tenant);
        utilisateur.setEmail("pieces-" + UUID.randomUUID() + "@test.ci");
        utilisateur.setMotDePasse("hash");
        utilisateur.setNom("Test");
        utilisateur.setPrenom("Pieces");
        utilisateur.setRoles(Set.of(role));
        return new UtilisateurPrincipal(utilisateurRepository.save(utilisateur));
    }
}
