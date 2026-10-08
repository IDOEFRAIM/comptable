package com.example.comptable.comptabilite;

import com.example.comptable.comptabilite.domain.CompteComptable;
import com.example.comptable.comptabilite.domain.EcritureComptable;
import com.example.comptable.comptabilite.domain.ExerciceComptable;
import com.example.comptable.comptabilite.domain.StatutExercice;
import com.example.comptable.comptabilite.domain.TypeTiers;
import com.example.comptable.comptabilite.repository.CompteComptableRepository;
import com.example.comptable.comptabilite.repository.EcritureComptableRepository;
import com.example.comptable.comptabilite.repository.ExerciceComptableRepository;
import com.example.comptable.comptabilite.service.ComptabilisationAutomatiqueIndisponibleException;
import com.example.comptable.comptabilite.service.ComptabilisationService;
import com.example.comptable.comptabilite.service.EcritureComptableService;
import com.example.comptable.comptabilite.service.TiersService;
import com.example.comptable.comptabilite.web.dto.ComptabiliserDocumentRequest;
import com.example.comptable.comptabilite.web.dto.EcritureComptableDto;
import com.example.comptable.comptabilite.web.dto.LigneEcritureDto;
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
 * LOT 1.1 (Phase 1) - tests de CARACTERISATION du flux Documents -> ecriture, ecrits AVANT toute
 * modification de {@link ComptabilisationService}. Ils figent le comportement observable historique
 * (statut BROUILLON, lignes, comptes, journal, liens document/ecriture, codes HTTP et isolation
 * entre entreprises) pour que la migration vers AccountingOperationEngine (lots 1.2/1.3) ne puisse
 * pas le changer sans que ces tests ne le signalent.
 *
 * Documentation du flux caracterise (etat AVANT migration) :
 * <pre>
 * ENTREE     ComptabiliserDocumentRequest(tiersId, compteChargeOuProduitId, libelle?, dateEcriture?)
 * METHODES   comptabiliser -> chargerDocument -> determinerOperation -> controles montants/date
 *            -> chargerTiers -> chargerCompteChargeOuProduit -> chargerExerciceOuvert -> chargerJournal
 *            -> chargerCompteTva (numero en dur 4456 achat / 443 vente, seulement si TVA > 0)
 *            -> genererLignes -> save(ecriture) -> document.ecritureId
 * COMPTES    achat : D charge(HT) / D TVA 4456 (TVA) / C compte du tiers (TTC, ligne.tiers renseigne)
 *            vente : D compte du tiers (TTC, ligne.tiers) / C produit (HT) / C TVA 443 (TVA)
 * ECRITURE   BROUILLON, journal ACH (achat) ou VTE (vente), numeroPiece "{journal}-{annee}-{seq 4 chiffres}"
 * SIDE-EFFECT document.ecritureId renseigne ; supprimer le brouillon remet ecritureId a null
 * </pre>
 */
@SpringBootTest(properties = "spring.docker.compose.skip.in-tests=false")
class ComptabilisationDocumentCaracterisationTest {

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
    @Autowired private EcritureComptableRepository ecritureComptableRepository;

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
                new CreerEntrepriseRequest("PME Caracterisation " + UUID.randomUUID(), null, null, null, null, null, "CI", "XOF"),
                principal);
        fournisseur = tiersService.creer(entreprise.id(), TypeTiers.FOURNISSEUR, "Fournisseur Carac", null, principal);
        client = tiersService.creer(entreprise.id(), TypeTiers.CLIENT, "Client Carac", null, principal);
        compte601 = compte(entreprise.id(), "601");
        compte701 = compte(entreprise.id(), "701");
    }

    // ---------------------------------------------------------------- achat

    @Test
    void achat_avec_tva_produit_trois_lignes_BROUILLON_journal_ACH_et_lie_le_document() {
        Document document = documentRepository.save(document(entreprise.id(), TypeDocument.FACTURE_ACHAT, "100000", "18000", "118000"));

        EcritureComptableDto ecriture = comptabilisationService.comptabiliser(entreprise.id(), document.getId(),
                new ComptabiliserDocumentRequest(fournisseur.id(), compte601, null, null), principal);

        assertThat(ecriture.statut()).isEqualTo("BROUILLON");
        assertThat(ecriture.journalCode()).isEqualTo("ACH");
        assertThat(ecriture.numeroPiece()).matches("ACH-\\d{4}-\\d{4}");
        assertThat(ecriture.dateEcriture()).isEqualTo(document.getDatePiece());
        assertThat(ecriture.libelle()).isEqualTo("FACTURE_ACHAT - Fournisseur Carac");
        assertThat(ecriture.lignes()).hasSize(3);

        LigneEcritureDto charge = ligne(ecriture, "601");
        assertThat(charge.debit()).isEqualByComparingTo("100000");
        assertThat(charge.credit()).isEqualByComparingTo("0");

        LigneEcritureDto tva = ligne(ecriture, "4456");
        assertThat(tva.debit()).isEqualByComparingTo("18000");

        LigneEcritureDto tiers = ecriture.lignes().stream().filter(l -> l.credit().signum() > 0).findFirst().orElseThrow();
        assertThat(tiers.credit()).isEqualByComparingTo("118000");
        assertThat(tiers.compteNumero()).startsWith("401");
        assertThat(tiers.tiersId()).isEqualTo(fournisseur.id());

        assertThat(ecriture.totalDebit()).isEqualByComparingTo(ecriture.totalCredit());
        assertThat(documentRepository.findById(document.getId()).orElseThrow().getEcritureId()).isEqualTo(ecriture.id());
    }

    @Test
    void achat_sans_tva_produit_deux_lignes_sans_compte_de_tva() {
        Document document = documentRepository.save(document(entreprise.id(), TypeDocument.FACTURE_ACHAT, "50000", "0", "50000"));

        EcritureComptableDto ecriture = comptabilisationService.comptabiliser(entreprise.id(), document.getId(),
                new ComptabiliserDocumentRequest(fournisseur.id(), compte601, null, null), principal);

        assertThat(ecriture.lignes()).hasSize(2);
        assertThat(ecriture.lignes()).noneMatch(l -> l.compteNumero().startsWith("445") || l.compteNumero().startsWith("443"));
        assertThat(ligne(ecriture, "601").debit()).isEqualByComparingTo("50000");
    }

    @Test
    void achat_sans_montant_tva_renseigne_est_traite_comme_tva_nulle() {
        Document document = documentRepository.save(document(entreprise.id(), TypeDocument.FACTURE_ACHAT, "50000", null, "50000"));

        EcritureComptableDto ecriture = comptabilisationService.comptabiliser(entreprise.id(), document.getId(),
                new ComptabiliserDocumentRequest(fournisseur.id(), compte601, null, null), principal);

        assertThat(ecriture.lignes()).hasSize(2);
    }

    @Test
    void libelle_et_date_explicites_de_la_requete_sont_utilises() {
        Document document = documentRepository.save(document(entreprise.id(), TypeDocument.FACTURE_ACHAT, "1000", "0", "1000"));
        LocalDate date = LocalDate.now().withDayOfYear(10);

        EcritureComptableDto ecriture = comptabilisationService.comptabiliser(entreprise.id(), document.getId(),
                new ComptabiliserDocumentRequest(fournisseur.id(), compte601, "Libelle choisi", date), principal);

        assertThat(ecriture.libelle()).isEqualTo("Libelle choisi");
        assertThat(ecriture.dateEcriture()).isEqualTo(date);
    }

    // ---------------------------------------------------------------- vente

    @Test
    void vente_avec_tva_debite_le_client_et_credite_produit_et_tva_collectee_journal_VTE() {
        Document document = documentRepository.save(document(entreprise.id(), TypeDocument.FACTURE_VENTE, "100000", "18000", "118000"));

        EcritureComptableDto ecriture = comptabilisationService.comptabiliser(entreprise.id(), document.getId(),
                new ComptabiliserDocumentRequest(client.id(), compte701, null, null), principal);

        assertThat(ecriture.statut()).isEqualTo("BROUILLON");
        assertThat(ecriture.journalCode()).isEqualTo("VTE");
        assertThat(ecriture.numeroPiece()).matches("VTE-\\d{4}-\\d{4}");
        assertThat(ecriture.lignes()).hasSize(3);

        LigneEcritureDto tiers = ecriture.lignes().stream().filter(l -> l.debit().signum() > 0).findFirst().orElseThrow();
        assertThat(tiers.debit()).isEqualByComparingTo("118000");
        assertThat(tiers.compteNumero()).startsWith("411");
        assertThat(tiers.tiersId()).isEqualTo(client.id());
        assertThat(ligne(ecriture, "701").credit()).isEqualByComparingTo("100000");
        assertThat(ligne(ecriture, "443").credit()).isEqualByComparingTo("18000");
    }

    // ---------------------------------------------------------------- refus / codes HTTP

    @Test
    void refuse_un_document_deja_comptabilise_409() {
        Document document = documentRepository.save(document(entreprise.id(), TypeDocument.FACTURE_ACHAT, "1000", "0", "1000"));
        ComptabiliserDocumentRequest requete = new ComptabiliserDocumentRequest(fournisseur.id(), compte601, null, null);
        comptabilisationService.comptabiliser(entreprise.id(), document.getId(), requete, principal);

        assertThat(statut(() -> comptabilisationService.comptabiliser(entreprise.id(), document.getId(), requete, principal)))
                .isEqualTo(409);
        assertThat(ecrituresDuDocument(document)).hasSize(1);
    }

    @Test
    void refuse_ht_plus_tva_different_du_ttc_422_sans_ecriture() {
        Document document = documentRepository.save(document(entreprise.id(), TypeDocument.FACTURE_ACHAT, "1", "0", "637259"));

        assertThat(statut(() -> comptabilisationService.comptabiliser(entreprise.id(), document.getId(),
                new ComptabiliserDocumentRequest(fournisseur.id(), compte601, null, null), principal))).isEqualTo(422);
        assertThat(documentRepository.findById(document.getId()).orElseThrow().getEcritureId()).isNull();
    }

    @Test
    void refuse_les_montants_manquants_422() {
        Document document = documentRepository.save(document(entreprise.id(), TypeDocument.FACTURE_ACHAT, null, null, "1000"));

        assertThat(statut(() -> comptabilisationService.comptabiliser(entreprise.id(), document.getId(),
                new ComptabiliserDocumentRequest(fournisseur.id(), compte601, null, null), principal))).isEqualTo(422);
    }

    @Test
    void refuse_un_type_de_document_non_supporte_422() {
        Document document = documentRepository.save(document(entreprise.id(), TypeDocument.RECU, "1000", "0", "1000"));

        assertThat(statut(() -> comptabilisationService.comptabiliser(entreprise.id(), document.getId(),
                new ComptabiliserDocumentRequest(fournisseur.id(), compte601, null, null), principal))).isEqualTo(422);
    }

    @Test
    void refuse_une_date_hors_exercice_422_et_un_exercice_cloture_422() {
        Document horsExercice = document(entreprise.id(), TypeDocument.FACTURE_ACHAT, "1000", "0", "1000");
        horsExercice.setDatePiece(LocalDate.now().minusYears(8));
        horsExercice = documentRepository.save(horsExercice);
        UUID idHors = horsExercice.getId();
        ComptabiliserDocumentRequest requete = new ComptabiliserDocumentRequest(fournisseur.id(), compte601, null, null);
        assertThat(statut(() -> comptabilisationService.comptabiliser(entreprise.id(), idHors, requete, principal))).isEqualTo(422);

        ExerciceComptable cloture = new ExerciceComptable();
        cloture.setEntreprise(entrepriseRepository.getReferenceById(entreprise.id()));
        LocalDate debut = LocalDate.now().minusYears(3).withDayOfYear(1);
        cloture.setDateDebut(debut);
        cloture.setDateFin(debut.withMonth(12).withDayOfMonth(31));
        cloture.setStatut(StatutExercice.CLOTURE);
        exerciceComptableRepository.save(cloture);
        Document dansExerciceCloture = document(entreprise.id(), TypeDocument.FACTURE_ACHAT, "1000", "0", "1000");
        dansExerciceCloture.setDatePiece(debut.plusDays(10));
        UUID idCloture = documentRepository.save(dansExerciceCloture).getId();
        assertThat(statut(() -> comptabilisationService.comptabiliser(entreprise.id(), idCloture, requete, principal))).isEqualTo(422);
    }

    @Test
    void refuse_un_tiers_du_mauvais_type_400_et_un_compte_de_mauvaise_nature_400() {
        Document document = documentRepository.save(document(entreprise.id(), TypeDocument.FACTURE_ACHAT, "1000", "0", "1000"));
        UUID id = document.getId();

        assertThat(statut(() -> comptabilisationService.comptabiliser(entreprise.id(), id,
                new ComptabiliserDocumentRequest(client.id(), compte601, null, null), principal))).isEqualTo(400);
        assertThat(statut(() -> comptabilisationService.comptabiliser(entreprise.id(), id,
                new ComptabiliserDocumentRequest(fournisseur.id(), compte701, null, null), principal))).isEqualTo(400);
        assertThat(documentRepository.findById(id).orElseThrow().getEcritureId()).isNull();
    }

    // ---------------------------------------------------------------- suppression du brouillon

    @Test
    void supprimer_le_brouillon_libere_le_document_qui_peut_etre_recomptabilise() {
        Document document = documentRepository.save(document(entreprise.id(), TypeDocument.FACTURE_ACHAT, "100000", "18000", "118000"));
        ComptabiliserDocumentRequest requete = new ComptabiliserDocumentRequest(fournisseur.id(), compte601, null, null);

        EcritureComptableDto premiere = comptabilisationService.comptabiliser(entreprise.id(), document.getId(), requete, principal);
        ecritureComptableService.supprimer(entreprise.id(), premiere.id(), principal);
        assertThat(documentRepository.findById(document.getId()).orElseThrow().getEcritureId()).isNull();

        EcritureComptableDto seconde = comptabilisationService.comptabiliser(entreprise.id(), document.getId(), requete, principal);

        assertThat(seconde.id()).isNotEqualTo(premiere.id());
        assertThat(seconde.statut()).isEqualTo("BROUILLON");
        assertThat(seconde.lignes()).hasSize(3);
        assertThat(documentRepository.findById(document.getId()).orElseThrow().getEcritureId()).isEqualTo(seconde.id());
    }

    @Test
    void une_ecriture_validee_est_intangible_409() {
        Document document = documentRepository.save(document(entreprise.id(), TypeDocument.FACTURE_ACHAT, "1000", "0", "1000"));
        EcritureComptableDto ecriture = comptabilisationService.comptabiliser(entreprise.id(), document.getId(),
                new ComptabiliserDocumentRequest(fournisseur.id(), compte601, null, null), principal);

        assertThat(ecritureComptableService.valider(entreprise.id(), ecriture.id(), principal).statut()).isEqualTo("VALIDEE");
        assertThat(statut(() -> ecritureComptableService.supprimer(entreprise.id(), ecriture.id(), principal))).isEqualTo(409);
    }

    // ---------------------------------------------------------------- numerotation

    @Test
    void les_numeros_de_piece_d_un_meme_journal_s_incrementent() {
        ComptabiliserDocumentRequest requete = new ComptabiliserDocumentRequest(fournisseur.id(), compte601, null, null);
        Document d1 = documentRepository.save(document(entreprise.id(), TypeDocument.FACTURE_ACHAT, "1000", "0", "1000"));
        Document d2 = documentRepository.save(document(entreprise.id(), TypeDocument.FACTURE_ACHAT, "2000", "0", "2000"));

        String n1 = comptabilisationService.comptabiliser(entreprise.id(), d1.getId(), requete, principal).numeroPiece();
        String n2 = comptabilisationService.comptabiliser(entreprise.id(), d2.getId(), requete, principal).numeroPiece();

        assertThat(n1).isNotEqualTo(n2);
        assertThat(n1).endsWith("-0001");
        assertThat(n2).endsWith("-0002");
    }

    // ---------------------------------------------------------------- voie automatique (pipeline)

    @Test
    void voie_automatique_cree_un_brouillon_sans_createdBy() {
        Document document = documentRepository.save(document(entreprise.id(), TypeDocument.FACTURE_ACHAT, "100000", "18000", "118000"));

        EcritureComptableDto dto = comptabilisationService.comptabiliserAutomatiquement(
                entreprise.id(), document.getId(), fournisseur.id(), compte601);

        assertThat(dto.statut()).isEqualTo("BROUILLON");
        assertThat(dto.lignes()).hasSize(3);
        EcritureComptable persistee = ecritureComptableRepository.findById(dto.id()).orElseThrow();
        assertThat(persistee.getCreatedBy()).isNull();
        assertThat(documentRepository.findById(document.getId()).orElseThrow().getEcritureId()).isEqualTo(dto.id());
    }

    @Test
    void voie_automatique_degrade_proprement_sans_propager_de_ResponseStatusException() {
        Document incoherent = documentRepository.save(document(entreprise.id(), TypeDocument.FACTURE_ACHAT, "1", "0", "637259"));
        assertThatThrownBy(() -> comptabilisationService.comptabiliserAutomatiquement(
                entreprise.id(), incoherent.getId(), fournisseur.id(), compte601))
                .isInstanceOf(ComptabilisationAutomatiqueIndisponibleException.class);

        Document mauvaisTiers = documentRepository.save(document(entreprise.id(), TypeDocument.FACTURE_ACHAT, "1000", "0", "1000"));
        assertThatThrownBy(() -> comptabilisationService.comptabiliserAutomatiquement(
                entreprise.id(), mauvaisTiers.getId(), client.id(), compte601))
                .isInstanceOf(ComptabilisationAutomatiqueIndisponibleException.class);
    }

    // ---------------------------------------------------------------- isolation entre entreprises

    @Test
    void entreprise_A_ne_peut_pas_comptabiliser_un_document_de_B_ni_utiliser_ses_tiers_ou_comptes() {
        UtilisateurPrincipal autrePrincipal = creerPrincipal();
        EntrepriseDto entrepriseB = entrepriseService.creer(
                new CreerEntrepriseRequest("PME B " + UUID.randomUUID(), null, null, null, null, null, "CI", "XOF"), autrePrincipal);
        TiersDto fournisseurB = tiersService.creer(entrepriseB.id(), TypeTiers.FOURNISSEUR, "Fournisseur B", null, autrePrincipal);
        UUID compte601B = compte(entrepriseB.id(), "601");
        Document documentB = documentRepository.save(document(entrepriseB.id(), TypeDocument.FACTURE_ACHAT, "1000", "0", "1000"));
        Document documentA = documentRepository.save(document(entreprise.id(), TypeDocument.FACTURE_ACHAT, "1000", "0", "1000"));

        // document de B via l'entreprise A (meme tenant ou non) : introuvable
        assertThat(statut(() -> comptabilisationService.comptabiliser(entreprise.id(), documentB.getId(),
                new ComptabiliserDocumentRequest(fournisseur.id(), compte601, null, null), principal))).isEqualTo(404);
        // acces a l'entreprise B avec le principal de A (autre tenant) : 404
        assertThat(statut(() -> comptabilisationService.comptabiliser(entrepriseB.id(), documentB.getId(),
                new ComptabiliserDocumentRequest(fournisseurB.id(), compte601B, null, null), principal))).isEqualTo(404);
        // tiers ou compte d'une autre entreprise sur un document de A : 404
        assertThat(statut(() -> comptabilisationService.comptabiliser(entreprise.id(), documentA.getId(),
                new ComptabiliserDocumentRequest(fournisseurB.id(), compte601, null, null), principal))).isEqualTo(404);
        assertThat(statut(() -> comptabilisationService.comptabiliser(entreprise.id(), documentA.getId(),
                new ComptabiliserDocumentRequest(fournisseur.id(), compte601B, null, null), principal))).isEqualTo(404);

        assertThat(documentRepository.findById(documentA.getId()).orElseThrow().getEcritureId()).isNull();
        assertThat(documentRepository.findById(documentB.getId()).orElseThrow().getEcritureId()).isNull();
    }

    // ---------------------------------------------------------------- utilitaires

    private int statut(Runnable action) {
        try {
            action.run();
        } catch (ResponseStatusException e) {
            return e.getStatusCode().value();
        }
        throw new AssertionError("Une ResponseStatusException etait attendue");
    }

    private LigneEcritureDto ligne(EcritureComptableDto ecriture, String numeroCompte) {
        return ecriture.lignes().stream().filter(l -> numeroCompte.equals(l.compteNumero())).findFirst()
                .orElseThrow(() -> new AssertionError("Aucune ligne sur le compte " + numeroCompte + " : " + ecriture.lignes()));
    }

    private List<EcritureComptable> ecrituresDuDocument(Document document) {
        return ecritureComptableRepository.findAll().stream()
                .filter(e -> e.getEntreprise().getId().equals(document.getEntreprise().getId()))
                .toList();
    }

    private UUID compte(UUID entrepriseId, String numero) {
        CompteComptable compte = compteComptableRepository.findByEntrepriseIdAndNumero(entrepriseId, numero).orElseThrow();
        return compte.getId();
    }

    private Document document(UUID entrepriseId, TypeDocument type, String ht, String tva, String ttc) {
        Document document = new Document();
        document.setTenant(entrepriseRepository.findById(entrepriseId).orElseThrow().getTenant());
        document.setEntreprise(entrepriseRepository.getReferenceById(entrepriseId));
        document.setTypeDocument(type);
        document.setCanalSource(CanalSource.WEB);
        document.setCheminFichier("test/facture.pdf");
        document.setMontantHt(ht != null ? new BigDecimal(ht) : null);
        document.setMontantTva(tva != null ? new BigDecimal(tva) : null);
        document.setMontantTtc(ttc != null ? new BigDecimal(ttc) : null);
        document.setDatePiece(LocalDate.now());
        return document;
    }

    private UtilisateurPrincipal creerPrincipal() {
        Tenant tenant = new Tenant();
        tenant.setRaisonSociale("Cabinet Carac");
        tenant.setSlug("cabinet-carac-" + UUID.randomUUID());
        tenant.setPays("CI");
        tenant = tenantRepository.save(tenant);

        Role role = roleRepository.findByCode("EXPERT_COMPTABLE").orElseThrow();
        Utilisateur utilisateur = new Utilisateur();
        utilisateur.setTenant(tenant);
        utilisateur.setEmail("carac-" + UUID.randomUUID() + "@test.ci");
        utilisateur.setMotDePasse("hash");
        utilisateur.setNom("Test");
        utilisateur.setPrenom("Carac");
        utilisateur.setRoles(Set.of(role));
        return new UtilisateurPrincipal(utilisateurRepository.save(utilisateur));
    }
}
