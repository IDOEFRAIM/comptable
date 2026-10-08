package com.example.comptable.dossier;

import com.example.comptable.agent.finding.FindingService;
import com.example.comptable.agent.finding.domain.Finding;
import com.example.comptable.agent.finding.domain.TypeFinding;
import com.example.comptable.agent.finding.web.dto.FindingDto;
import com.example.comptable.audit.domain.AuditEvent;
import com.example.comptable.audit.repository.AuditEventRepository;
import com.example.comptable.comptabilite.domain.TypeTiers;
import com.example.comptable.comptabilite.repository.CompteComptableRepository;
import com.example.comptable.comptabilite.repository.ExerciceComptableRepository;
import com.example.comptable.comptabilite.service.ClotureExerciceService;
import com.example.comptable.comptabilite.service.ComptabilisationService;
import com.example.comptable.comptabilite.service.EcritureComptableService;
import com.example.comptable.comptabilite.service.TiersService;
import com.example.comptable.comptabilite.web.dto.ComptabiliserDocumentRequest;
import com.example.comptable.comptabilite.web.dto.EcritureComptableDto;
import com.example.comptable.comptabilite.web.dto.TiersDto;
import com.example.comptable.controle.ControlCategory;
import com.example.comptable.controle.ControlResult;
import com.example.comptable.controle.ControlResultAdapters;
import com.example.comptable.controle.ControlResultPublisher;
import com.example.comptable.controle.ControlSeverity;
import com.example.comptable.document.domain.CanalSource;
import com.example.comptable.document.domain.Document;
import com.example.comptable.document.domain.StatutDocument;
import com.example.comptable.document.domain.TypeDocument;
import com.example.comptable.document.repository.DocumentRepository;
import com.example.comptable.dossier.web.dto.ClotureReadinessDto;
import com.example.comptable.dossier.web.dto.ControlItem;
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
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.web.server.ResponseStatusException;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Phase 1 - lots 1.10 a 1.13 sur PostgreSQL reel : pre-cloture composee des controles
 * deterministes, evenements d'audit des decisions importantes (y compris les refus, qui doivent
 * survivre au rollback), et isolation entre entreprises/tenants de chaque element introduit.
 */
@SpringBootTest(properties = "spring.docker.compose.skip.in-tests=false")
class Phase1PreClotureAuditIsolationTest {

    @Autowired private TenantRepository tenantRepository;
    @Autowired private UtilisateurRepository utilisateurRepository;
    @Autowired private RoleRepository roleRepository;
    @Autowired private EntrepriseService entrepriseService;
    @Autowired private EntrepriseRepository entrepriseRepository;
    @Autowired private TiersService tiersService;
    @Autowired private CompteComptableRepository compteComptableRepository;
    @Autowired private ExerciceComptableRepository exerciceComptableRepository;
    @Autowired private DocumentRepository documentRepository;
    @Autowired private ComptabilisationService comptabilisationService;
    @Autowired private EcritureComptableService ecritureComptableService;
    @Autowired private ClotureReadinessService clotureReadinessService;
    @Autowired private ClotureExerciceService clotureExerciceService;
    @Autowired private FindingService findingService;
    @Autowired private ControlResultPublisher publisher;
    @Autowired private AuditEventRepository auditEventRepository;

    private UtilisateurPrincipal principalA;
    private UtilisateurPrincipal principalB;
    private EntrepriseDto entrepriseA;
    private EntrepriseDto entrepriseB;

    @BeforeEach
    void amorcer() {
        principalA = creerPrincipal();
        principalB = creerPrincipal();
        entrepriseA = entrepriseService.creer(new CreerEntrepriseRequest("PME A " + UUID.randomUUID(), null, null, null, null, null, "CI", "XOF"), principalA);
        entrepriseB = entrepriseService.creer(new CreerEntrepriseRequest("PME B " + UUID.randomUUID(), null, null, null, null, null, "CI", "XOF"), principalB);
    }

    // ------------------------------------------------------------ pre-cloture : controles + explicabilite

    @Test
    void la_pre_cloture_expose_les_nouveaux_controles_et_ne_simule_jamais_un_OK() {
        ClotureReadinessDto checklist = clotureReadinessService.evaluer(entrepriseA.id(), principalA);

        List<String> codes = checklist.checklist().stream().map(ControlItem::code).toList();
        assertThat(codes).contains("equilibre_ecritures", "comptes_desactives", "mappings_parametrage",
                "documents_non_comptabilises", "pieces_manquantes", "banque", "lettrage", "fournisseurs", "clients",
                "stocks", "amortissements", "depreciations", "provisions", "fiscalite", "caisse", "rapprochement", "immobilisations");
        assertThat(codes).doesNotHaveDuplicates();

        // Entreprise vierge : rien a rapprocher / inventorier / amortir -> NOT_APPLICABLE, pas OK.
        assertThat(statut(checklist, "banque")).isEqualTo("NOT_APPLICABLE");
        assertThat(statut(checklist, "stocks")).isEqualTo("NOT_APPLICABLE");
        assertThat(statut(checklist, "amortissements")).isEqualTo("NOT_APPLICABLE");
        assertThat(statut(checklist, "caisse")).isEqualTo("NOT_APPLICABLE");
        // Controles certains sur donnees existantes : OK.
        assertThat(statut(checklist, "equilibre_ecritures")).isEqualTo("OK");
        assertThat(statut(checklist, "documents_non_comptabilises")).isEqualTo("OK");
        assertThat(checklist.blockingItems()).isEmpty();
    }

    @Test
    void facture_non_comptabilisee_est_expliquee_et_navigable_puis_disparait_une_fois_comptabilisee() {
        Document document = documentRepository.save(documentTraite(entrepriseA.id(), "100000", "18000", "118000"));

        ControlItem avant = item(clotureReadinessService.evaluer(entrepriseA.id(), principalA), "documents_non_comptabilises");
        assertThat(avant.statut()).isEqualTo("WARNING");
        assertThat(avant.message()).contains("1 facture(s)").contains("118000");
        assertThat(avant.entityType()).isEqualTo("DOCUMENT");
        assertThat(avant.entityId()).isEqualTo(document.getId().toString());
        assertThat(avant.relatedEntityIds()).containsExactly(document.getId().toString());

        comptabiliser(entrepriseA, principalA, document);

        ControlItem apres = item(clotureReadinessService.evaluer(entrepriseA.id(), principalA), "documents_non_comptabilises");
        assertThat(apres.statut()).isEqualTo("OK");
    }

    // ------------------------------------------------------------ audit trail

    @Test
    void le_flux_documents_trace_proposition_validation_et_ecriture() {
        Document document = documentRepository.save(documentTraite(entrepriseA.id(), "100000", "18000", "118000"));
        EcritureComptableDto ecriture = comptabiliser(entrepriseA, principalA, document);

        List<AuditEvent> evenements = evenements(principalA, null);

        assertThat(evenements).extracting(AuditEvent::getAction)
                .contains("PROPOSITION_COMPTABLE_CREEE", "PROPOSITION_COMPTABLE_VALIDEE", "ECRITURE_CREEE");
        AuditEvent creation = evenements.stream().filter(e -> e.getAction().equals("ECRITURE_CREEE")).findFirst().orElseThrow();
        assertThat(creation.getEntiteId()).isEqualTo(ecriture.id().toString());
        assertThat(creation.getUtilisateurId()).isEqualTo(principalA.getId());
        assertThat(creation.getDetails()).containsEntry("source", "HUMAIN").containsEntry("statutEcriture", "BROUILLON")
                .containsEntry("sourceType", "DOCUMENT").containsEntry("sourceId", document.getId().toString());
    }

    @Test
    void un_refus_de_compte_non_configure_est_audite_malgre_le_rollback_et_reste_dans_le_tenant_concerne() {
        compteComptableRepository.delete(compteComptableRepository.findByEntrepriseIdAndNumero(entrepriseB.id(), "4456").orElseThrow());
        Document documentB = documentRepository.save(documentTraite(entrepriseB.id(), "100000", "18000", "118000"));
        TiersDto fournisseurB = tiersService.creer(entrepriseB.id(), TypeTiers.FOURNISSEUR, "Fournisseur B", null, principalB);

        assertThat(statutHttp(() -> comptabilisationService.comptabiliser(entrepriseB.id(), documentB.getId(),
                new ComptabiliserDocumentRequest(fournisseurB.id(), compte(entrepriseB, "601"), null, null), principalB))).isEqualTo(422);

        List<AuditEvent> chezB = evenements(principalB, "COMPTE_NON_CONFIGURE");
        assertThat(chezB).hasSize(1);
        assertThat(chezB.get(0).getEntiteId()).isEqualTo(documentB.getId().toString());
        assertThat(chezB.get(0).getDetails()).containsEntry("purpose", "TVA_RECUPERABLE").containsEntry("resultat", "REFUSE");
        assertThat(evenements(principalB, "PROPOSITION_COMPTABLE_A_REVOIR")).isNotEmpty();
        // Rien de tout cela chez l'autre tenant.
        assertThat(evenements(principalA, "COMPTE_NON_CONFIGURE")).isEmpty();
        assertThat(evenements(principalA, "PROPOSITION_COMPTABLE_A_REVOIR")).isEmpty();
    }

    @Test
    void la_cloture_refusee_puis_terminee_est_auditee() {
        Document document = documentRepository.save(documentTraite(entrepriseA.id(), "100000", "18000", "118000"));
        EcritureComptableDto brouillon = comptabiliser(entrepriseA, principalA, document);
        UUID exerciceId = exerciceComptableRepository.findByEntrepriseIdAndDate(entrepriseA.id(), LocalDate.now()).orElseThrow().getId();

        assertThat(statutHttp(() -> clotureExerciceService.cloturer(entrepriseA.id(), exerciceId, principalA))).isEqualTo(422);
        List<AuditEvent> refus = evenements(principalA, "CLOTURE_REFUSEE");
        assertThat(refus).hasSize(1);
        assertThat(refus.get(0).getDetails().get("bloquants").toString()).contains("brouillon");

        ecritureComptableService.valider(entrepriseA.id(), brouillon.id(), principalA);
        clotureExerciceService.cloturer(entrepriseA.id(), exerciceId, principalA);

        assertThat(evenements(principalA, "CLOTURE_TERMINEE")).hasSize(1);
        assertThat(evenements(principalB, "CLOTURE_TERMINEE")).isEmpty();
        assertThat(evenements(principalB, "CLOTURE_REFUSEE")).isEmpty();
    }

    @Test
    void consulter_la_pre_cloture_est_une_action_tracee_du_bon_tenant() {
        clotureReadinessService.evaluer(entrepriseA.id(), principalA);

        List<AuditEvent> evenements = evenements(principalA, "CLOTURE_CONTROLEE");
        assertThat(evenements).hasSize(1);
        assertThat(evenements.get(0).getEntiteId()).isEqualTo(entrepriseA.id().toString());
        assertThat(evenements(principalB, "CLOTURE_CONTROLEE")).isEmpty();
    }

    // ------------------------------------------------------------ isolation multi-tenant

    @Test
    void la_pre_cloture_ne_montre_que_les_documents_de_l_entreprise_consultee() {
        Document docA = documentRepository.save(documentTraite(entrepriseA.id(), "1000", "0", "1000"));
        Document docB1 = documentRepository.save(documentTraite(entrepriseB.id(), "2000", "0", "2000"));
        Document docB2 = documentRepository.save(documentTraite(entrepriseB.id(), "3000", "0", "3000"));

        ControlItem pourA = item(clotureReadinessService.evaluer(entrepriseA.id(), principalA), "documents_non_comptabilises");
        ControlItem pourB = item(clotureReadinessService.evaluer(entrepriseB.id(), principalB), "documents_non_comptabilises");

        assertThat(pourA.relatedEntityIds()).containsExactly(docA.getId().toString());
        assertThat(pourB.relatedEntityIds()).containsExactlyInAnyOrder(docB1.getId().toString(), docB2.getId().toString());
        assertThat(pourA.metadata()).containsEntry("nbDocuments", 1);
    }

    @Test
    void un_utilisateur_d_un_autre_tenant_ne_peut_ni_evaluer_ni_cloturer_l_entreprise() {
        UUID exerciceA = exerciceComptableRepository.findByEntrepriseIdAndDate(entrepriseA.id(), LocalDate.now()).orElseThrow().getId();

        assertThat(statutHttp(() -> clotureReadinessService.evaluer(entrepriseA.id(), principalB))).isEqualTo(404);
        assertThat(statutHttp(() -> clotureExerciceService.cloturer(entrepriseA.id(), exerciceA, principalB))).isEqualTo(404);
        assertThat(evenements(principalB, "CLOTURE_CONTROLEE")).isEmpty();
        assertThat(evenements(principalB, "CLOTURE_REFUSEE")).isEmpty();
    }

    @Test
    void un_constat_publie_devient_un_finding_visible_uniquement_dans_son_entreprise() {
        Entreprise entreprise = entrepriseRepository.findById(entrepriseA.id()).orElseThrow();
        ControlResult constat = ControlResult.ouvert("BANK_RECONCILIATION_INCOMPLETE", ControlCategory.RECONCILIATION, ControlSeverity.ERROR,
                entrepriseA.id(), "RELEVE", UUID.randomUUID().toString(), "1 ligne non pointee",
                "Compte 521 : 124500 FCFA non rapproches", List.of("ligne-1"), Map.of("montantNonPointe", 124500), "Pointer la ligne");

        Finding finding = publier(entreprise, constat);
        assertThat(finding).isNotNull();
        assertThat(publier(entreprise, constat).getId()).isEqualTo(finding.getId()); // idempotent

        List<FindingDto> chezA = findingService.listerOuverts(entrepriseA.id(), principalA);
        assertThat(chezA).extracting(FindingDto::id).contains(finding.getId());
        assertThat(findingService.listerOuverts(entrepriseB.id(), principalB)).extracting(FindingDto::id).doesNotContain(finding.getId());
        assertThat(statutHttp(() -> findingService.listerOuverts(entrepriseA.id(), principalB))).isEqualTo(404);
        assertThat(statutHttp(() -> findingService.resoudre(entrepriseB.id(), finding.getId(), principalB))).isEqualTo(404);

        assertThat(evenements(principalA, "FINDING_CREE")).hasSize(1);

        ControlResult relu = ControlResultAdapters.depuisFinding(finding);
        assertThat(relu.severity()).isEqualTo(ControlSeverity.ERROR);
        assertThat(relu.code()).isEqualTo("BANK_RECONCILIATION_INCOMPLETE");
        assertThat(relu.entrepriseId()).isEqualTo(entrepriseA.id());
    }

    @Test
    void les_documents_les_tiers_et_les_comptes_d_une_autre_entreprise_restent_inaccessibles_au_flux_documents() {
        Document documentB = documentRepository.save(documentTraite(entrepriseB.id(), "1000", "0", "1000"));
        TiersDto fournisseurA = tiersService.creer(entrepriseA.id(), TypeTiers.FOURNISSEUR, "Fournisseur A", null, principalA);

        assertThat(statutHttp(() -> comptabilisationService.comptabiliser(entrepriseA.id(), documentB.getId(),
                new ComptabiliserDocumentRequest(fournisseurA.id(), compte(entrepriseA, "601"), null, null), principalA))).isEqualTo(404);
        assertThat(documentRepository.findById(documentB.getId()).orElseThrow().getEcritureId()).isNull();
    }

    // ------------------------------------------------------------ utilitaires

    private Finding publier(Entreprise entreprise, ControlResult constat) {
        return publisher.publier(entreprise, constat, TypeFinding.BANK_DIFFERENCE);
    }

    private EcritureComptableDto comptabiliser(EntrepriseDto entreprise, UtilisateurPrincipal principal, Document document) {
        TiersDto fournisseur = tiersService.creer(entreprise.id(), TypeTiers.FOURNISSEUR, "Fournisseur " + UUID.randomUUID(), null, principal);
        return comptabilisationService.comptabiliser(entreprise.id(), document.getId(),
                new ComptabiliserDocumentRequest(fournisseur.id(), compte(entreprise, "601"), null, null), principal);
    }

    private UUID compte(EntrepriseDto entreprise, String numero) {
        return compteComptableRepository.findByEntrepriseIdAndNumero(entreprise.id(), numero).orElseThrow().getId();
    }

    private List<AuditEvent> evenements(UtilisateurPrincipal principal, String action) {
        return auditEventRepository.findAll().stream()
                .filter(e -> principal.getTenantId().equals(e.getTenantId()))
                .filter(e -> action == null || action.equals(e.getAction()))
                .toList();
    }

    private static String statut(ClotureReadinessDto dto, String code) {
        return item(dto, code).statut();
    }

    private static ControlItem item(ClotureReadinessDto dto, String code) {
        return dto.checklist().stream().filter(i -> i.code().equals(code)).findFirst()
                .orElseThrow(() -> new AssertionError("Point absent : " + code));
    }

    private int statutHttp(Supplier<?> action) {
        try {
            action.get();
        } catch (ResponseStatusException e) {
            return e.getStatusCode().value();
        }
        throw new AssertionError("Une ResponseStatusException etait attendue");
    }

    private Document documentTraite(UUID entrepriseId, String ht, String tva, String ttc) {
        Document document = new Document();
        document.setTenant(entrepriseRepository.findById(entrepriseId).orElseThrow().getTenant());
        document.setEntreprise(entrepriseRepository.getReferenceById(entrepriseId));
        document.setTypeDocument(TypeDocument.FACTURE_ACHAT);
        document.setCanalSource(CanalSource.WEB);
        document.setCheminFichier("test/facture.pdf");
        document.setStatut(StatutDocument.TRAITE);
        document.setMontantHt(new BigDecimal(ht));
        document.setMontantTva(new BigDecimal(tva));
        document.setMontantTtc(new BigDecimal(ttc));
        document.setDatePiece(LocalDate.now());
        return document;
    }

    private UtilisateurPrincipal creerPrincipal() {
        Tenant tenant = new Tenant();
        tenant.setRaisonSociale("Cabinet Phase1");
        tenant.setSlug("cabinet-p1-" + UUID.randomUUID());
        tenant.setPays("CI");
        tenant = tenantRepository.save(tenant);

        Role role = roleRepository.findByCode("EXPERT_COMPTABLE").orElseThrow();
        Utilisateur utilisateur = new Utilisateur();
        utilisateur.setTenant(tenant);
        utilisateur.setEmail("p1-" + UUID.randomUUID() + "@test.ci");
        utilisateur.setMotDePasse("hash");
        utilisateur.setNom("Test");
        utilisateur.setPrenom("Phase1");
        utilisateur.setRoles(Set.of(role));
        return new UtilisateurPrincipal(utilisateurRepository.save(utilisateur));
    }
}
