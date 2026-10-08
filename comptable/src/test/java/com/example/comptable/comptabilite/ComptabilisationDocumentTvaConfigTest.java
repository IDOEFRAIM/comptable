package com.example.comptable.comptabilite;

import com.example.comptable.comptabilite.domain.CompteComptable;
import com.example.comptable.comptabilite.domain.TypeTiers;
import com.example.comptable.comptabilite.operation.common.AccountingOperationType;
import com.example.comptable.comptabilite.operation.common.domain.AccountingOperationProposal;
import com.example.comptable.comptabilite.operation.common.repository.AccountingOperationProposalRepository;
import com.example.comptable.comptabilite.parametrage.domain.AccountPurpose;
import com.example.comptable.comptabilite.parametrage.service.AccountingAccountMappingService;
import com.example.comptable.comptabilite.parametrage.web.dto.CreerAccountingAccountMappingRequest;
import com.example.comptable.comptabilite.repository.CompteComptableRepository;
import com.example.comptable.comptabilite.service.ComptabilisationService;
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
import org.junit.jupiter.api.BeforeEach;
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
 * LOT 1.3 (Phase 1) - le compte de TVA du flux Documents vient du PARAMETRAGE de l'entreprise
 * ({@code AccountingAccountResolver}, purposes TVA_RECUPERABLE / TVA_COLLECTEE), jamais d'un numero
 * en dur. Une configuration absente, ambigue ou invalide bloque proprement (422 explicite, aucune
 * ecriture, aucune proposition residuelle) au lieu de retomber sur "4456"/"443".
 */
@SpringBootTest(properties = "spring.docker.compose.skip.in-tests=false")
class ComptabilisationDocumentTvaConfigTest {

    @Autowired private TenantRepository tenantRepository;
    @Autowired private UtilisateurRepository utilisateurRepository;
    @Autowired private RoleRepository roleRepository;
    @Autowired private EntrepriseService entrepriseService;
    @Autowired private EntrepriseRepository entrepriseRepository;
    @Autowired private TiersService tiersService;
    @Autowired private CompteComptableRepository compteComptableRepository;
    @Autowired private DocumentRepository documentRepository;
    @Autowired private ComptabilisationService comptabilisationService;
    @Autowired private AccountingAccountMappingService mappingService;
    @Autowired private AccountingOperationProposalRepository proposalRepository;

    private UtilisateurPrincipal principal;
    private EntrepriseDto entreprise;
    private TiersDto fournisseur;
    private TiersDto client;
    private UUID compte601;
    private UUID compte701;

    @BeforeEach
    void amorcer() {
        principal = creerPrincipal();
        entreprise = entrepriseService.creer(
                new CreerEntrepriseRequest("PME TVA Config " + UUID.randomUUID(), null, null, null, null, null, "CI", "XOF"), principal);
        fournisseur = tiersService.creer(entreprise.id(), TypeTiers.FOURNISSEUR, "Fournisseur TVA", null, principal);
        client = tiersService.creer(entreprise.id(), TypeTiers.CLIENT, "Client TVA", null, principal);
        compte601 = compte("601").getId();
        compte701 = compte("701").getId();
    }

    @Test
    void un_mapping_TVA_recuperable_de_l_entreprise_est_prefere_au_role_systeme() {
        CompteComptable alternatif = autreCompteDeTva();
        mappingService.creer(entreprise.id(),
                new CreerAccountingAccountMappingRequest(AccountPurpose.TVA_RECUPERABLE, null, alternatif.getId(), null), principal);
        Document document = documentRepository.save(document(TypeDocument.FACTURE_ACHAT, "100000", "18000", "118000"));

        EcritureComptableDto ecriture = comptabilisationService.comptabiliser(entreprise.id(), document.getId(),
                new ComptabiliserDocumentRequest(fournisseur.id(), compte601, null, null), principal);

        assertThat(ecriture.lignes()).anyMatch(l -> alternatif.getNumero().equals(l.compteNumero())
                && l.debit().compareTo(new BigDecimal("18000")) == 0);
        assertThat(ecriture.lignes()).noneMatch(l -> "4456".equals(l.compteNumero()));
    }

    @Test
    void un_mapping_TVA_collectee_de_l_entreprise_est_utilise_pour_une_vente() {
        CompteComptable alternatif = autreCompteDeTva();
        mappingService.creer(entreprise.id(),
                new CreerAccountingAccountMappingRequest(AccountPurpose.TVA_COLLECTEE, null, alternatif.getId(), null), principal);
        Document document = documentRepository.save(document(TypeDocument.FACTURE_VENTE, "100000", "18000", "118000"));

        EcritureComptableDto ecriture = comptabilisationService.comptabiliser(entreprise.id(), document.getId(),
                new ComptabiliserDocumentRequest(client.id(), compte701, null, null), principal);

        assertThat(ecriture.lignes()).anyMatch(l -> alternatif.getNumero().equals(l.compteNumero())
                && l.credit().compareTo(new BigDecimal("18000")) == 0);
        assertThat(ecriture.lignes()).noneMatch(l -> "443".equals(l.compteNumero()));
    }

    @Test
    void tva_non_configuree_bloque_422_NOT_CONFIGURED_sans_ecriture_ni_proposition_residuelle() {
        // Ni mapping, ni compte portant le role systeme TVA_DEDUCTIBLE dans cette entreprise.
        compteComptableRepository.delete(compte("4456"));
        Document document = documentRepository.save(document(TypeDocument.FACTURE_ACHAT, "100000", "18000", "118000"));

        assertThatThrownBy(() -> comptabilisationService.comptabiliser(entreprise.id(), document.getId(),
                new ComptabiliserDocumentRequest(fournisseur.id(), compte601, null, null), principal))
                .isInstanceOfSatisfying(ResponseStatusException.class, e -> {
                    assertThat(e.getStatusCode().value()).isEqualTo(422);
                    assertThat(e.getReason()).contains("TVA recuperable").contains("NOT_CONFIGURED");
                });

        assertThat(documentRepository.findById(document.getId()).orElseThrow().getEcritureId()).isNull();
        assertThat(propositionsDuDocument(document)).isEmpty();
    }

    @Test
    void compte_de_tva_desactive_bloque_422_invalide_sans_repli_sur_un_numero_en_dur() {
        CompteComptable tva = compte("4456");
        tva.setActif(false);
        compteComptableRepository.save(tva);
        Document document = documentRepository.save(document(TypeDocument.FACTURE_ACHAT, "100000", "18000", "118000"));

        assertThatThrownBy(() -> comptabilisationService.comptabiliser(entreprise.id(), document.getId(),
                new ComptabiliserDocumentRequest(fournisseur.id(), compte601, null, null), principal))
                .isInstanceOfSatisfying(ResponseStatusException.class, e -> {
                    assertThat(e.getStatusCode().value()).isEqualTo(422);
                    assertThat(e.getReason()).contains("INVALID").contains("TVA recuperable");
                });
        assertThat(documentRepository.findById(document.getId()).orElseThrow().getEcritureId()).isNull();
    }

    @Test
    void sans_tva_aucune_configuration_de_tva_n_est_necessaire() {
        CompteComptable tva = compte("4456");
        tva.setActif(false);
        compteComptableRepository.save(tva);
        Document document = documentRepository.save(document(TypeDocument.FACTURE_ACHAT, "50000", "0", "50000"));

        EcritureComptableDto ecriture = comptabilisationService.comptabiliser(entreprise.id(), document.getId(),
                new ComptabiliserDocumentRequest(fournisseur.id(), compte601, null, null), principal);

        assertThat(ecriture.lignes()).hasSize(2);
    }

    @Test
    void la_proposition_du_moteur_trace_la_source_document() {
        Document document = documentRepository.save(document(TypeDocument.FACTURE_ACHAT, "100000", "18000", "118000"));
        EcritureComptableDto ecriture = comptabilisationService.comptabiliser(entreprise.id(), document.getId(),
                new ComptabiliserDocumentRequest(fournisseur.id(), compte601, null, null), principal);

        List<AccountingOperationProposal> propositions = propositionsDuDocument(document);

        assertThat(propositions).hasSize(1);
        AccountingOperationProposal proposition = propositions.get(0);
        assertThat(proposition.getType()).isEqualTo(AccountingOperationType.ACHAT);
        assertThat(proposition.getSourceType()).isEqualTo("DOCUMENT");
        assertThat(proposition.getEcriture().getId()).isEqualTo(ecriture.id());
        assertThat(proposition.getValidatedBy()).isNotNull();
    }

    // ------------------------------------------------------------ utilitaires

    private List<AccountingOperationProposal> propositionsDuDocument(Document document) {
        return proposalRepository.findAll().stream()
                .filter(p -> document.getId().equals(p.getSourceId()))
                .toList();
    }

    /** Un compte de classe 4 utilisable en detail, different des roles systeme 4456/443 : le test
     * prouve que le parametrage de l'entreprise (et non un numero) determine le compte de TVA. */
    private CompteComptable autreCompteDeTva() {
        return compte("441");
    }

    private CompteComptable compte(String numero) {
        return compteComptableRepository.findByEntrepriseIdAndNumero(entreprise.id(), numero).orElseThrow();
    }

    private Document document(TypeDocument type, String ht, String tva, String ttc) {
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

    private UtilisateurPrincipal creerPrincipal() {
        Tenant tenant = new Tenant();
        tenant.setRaisonSociale("Cabinet TVA Config");
        tenant.setSlug("cabinet-tva-" + UUID.randomUUID());
        tenant.setPays("CI");
        tenant = tenantRepository.save(tenant);

        Role role = roleRepository.findByCode("EXPERT_COMPTABLE").orElseThrow();
        Utilisateur utilisateur = new Utilisateur();
        utilisateur.setTenant(tenant);
        utilisateur.setEmail("tva-" + UUID.randomUUID() + "@test.ci");
        utilisateur.setMotDePasse("hash");
        utilisateur.setNom("Test");
        utilisateur.setPrenom("Tva");
        utilisateur.setRoles(Set.of(role));
        return new UtilisateurPrincipal(utilisateurRepository.save(utilisateur));
    }
}
