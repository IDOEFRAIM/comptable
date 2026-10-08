package com.example.comptable.comptabilite.service;

import com.example.comptable.comptabilite.operation.common.AccountingSource;

import com.example.comptable.comptabilite.domain.CompteComptable;
import com.example.comptable.comptabilite.domain.EcritureComptable;
import com.example.comptable.comptabilite.domain.ExerciceComptable;
import com.example.comptable.comptabilite.domain.Journal;
import com.example.comptable.comptabilite.domain.NatureCompte;
import com.example.comptable.comptabilite.domain.PlanComptableReference;
import com.example.comptable.comptabilite.domain.StatutExercice;
import com.example.comptable.comptabilite.domain.Tiers;
import com.example.comptable.comptabilite.domain.TypeTiers;
import com.example.comptable.comptabilite.domain.StatutEcriture;
import com.example.comptable.comptabilite.operation.achat.AchatRule;
import com.example.comptable.comptabilite.operation.common.AccountingOperationEngine;
import com.example.comptable.comptabilite.operation.common.AccountingOperationType;
import com.example.comptable.comptabilite.operation.common.ResultatRegle;
import com.example.comptable.comptabilite.operation.common.domain.AccountingOperationProposalLigne;
import com.example.comptable.comptabilite.operation.common.web.dto.AccountingOperationProposalDto;
import com.example.comptable.comptabilite.operation.vente.VenteRule;
import com.example.comptable.comptabilite.parametrage.AccountingAccountResolver;
import com.example.comptable.comptabilite.parametrage.ResolvedAccount;
import com.example.comptable.comptabilite.parametrage.domain.AccountPurpose;
import com.example.comptable.comptabilite.repository.CompteComptableRepository;
import com.example.comptable.comptabilite.repository.EcritureComptableRepository;
import com.example.comptable.comptabilite.repository.TiersRepository;
import com.example.comptable.comptabilite.web.dto.ComptabiliserDocumentRequest;
import com.example.comptable.comptabilite.web.dto.EcritureComptableDto;
import com.example.comptable.document.domain.Document;
import com.example.comptable.document.domain.TypeDocument;
import com.example.comptable.document.repository.DocumentRepository;
import com.example.comptable.entreprise.domain.Entreprise;
import com.example.comptable.entreprise.service.EntrepriseAccessService;
import com.example.comptable.identite.domain.Role;
import com.example.comptable.identite.domain.Utilisateur;
import com.example.comptable.security.UtilisateurPrincipal;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.web.server.ResponseStatusException;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ComptabilisationServiceTest {

    @Mock
    private EntrepriseAccessService entrepriseAccessService;
    @Mock
    private DocumentRepository documentRepository;
    @Mock
    private TiersRepository tiersRepository;
    @Mock
    private CompteComptableRepository compteComptableRepository;
    @Mock
    private EcritureComptableRepository ecritureComptableRepository;
    @Mock
    private AccountingAccountResolver accountingAccountResolver;
    @Mock
    private AccountingOperationEngine accountingOperationEngine;
    @Mock
    private AchatRule achatRule;
    @Mock
    private VenteRule venteRule;

    @Mock
    private com.example.comptable.audit.AuditTrailService auditTrailService;

    @InjectMocks
    private ComptabilisationService comptabilisationService;

    private UUID entrepriseId;
    private UUID documentId;
    private Entreprise entreprise;
    private UtilisateurPrincipal principal;
    private Utilisateur utilisateur;

    @BeforeEach
    void setUp() {
        entrepriseId = UUID.randomUUID();
        documentId = UUID.randomUUID();

        entreprise = new Entreprise();
        entreprise.setId(entrepriseId);

        Role role = new Role();
        role.setCode("EXPERT_COMPTABLE");
        utilisateur = new Utilisateur();
        utilisateur.setId(UUID.randomUUID());
        utilisateur.setEmail("test@cabinet.ci");
        utilisateur.setMotDePasse("hash");
        utilisateur.setNom("Nom");
        utilisateur.setPrenom("Prenom");
        utilisateur.setRoles(Set.of(role));
        principal = new UtilisateurPrincipal(utilisateur);

        when(entrepriseAccessService.verifierAcces(entrepriseId, principal)).thenReturn(entreprise);
    }

    private Document documentFactureAchat(BigDecimal ht, BigDecimal tva, BigDecimal ttc) {
        Document document = new Document();
        document.setId(documentId);
        document.setEntreprise(entreprise);
        document.setTypeDocument(TypeDocument.FACTURE_ACHAT);
        document.setMontantHt(ht);
        document.setMontantTva(tva);
        document.setMontantTtc(ttc);
        document.setDatePiece(LocalDate.of(2026, 3, 15));
        return document;
    }

    private CompteComptable compteAvecNature(String numero, NatureCompte nature) {
        CompteComptable compte = new CompteComptable();
        compte.setId(UUID.randomUUID());
        compte.setEntreprise(entreprise);
        compte.setNumero(numero);
        compte.setIntitule("Compte " + numero);
        if (nature != null) {
            PlanComptableReference reference = new PlanComptableReference();
            reference.setNature(nature);
            compte.setReference(reference);
        }
        return compte;
    }

    private Tiers tiersFournisseur(CompteComptable sousCompte) {
        Tiers tiers = new Tiers();
        tiers.setId(UUID.randomUUID());
        tiers.setEntreprise(entreprise);
        tiers.setType(TypeTiers.FOURNISSEUR);
        tiers.setNom("Papeterie Abidjanaise");
        tiers.setCompteComptable(sousCompte);
        return tiers;
    }

    private ExerciceComptable exerciceOuvert() {
        ExerciceComptable exercice = new ExerciceComptable();
        exercice.setId(UUID.randomUUID());
        exercice.setEntreprise(entreprise);
        exercice.setDateDebut(LocalDate.of(2026, 1, 1));
        exercice.setDateFin(LocalDate.of(2026, 12, 31));
        exercice.setStatut(StatutExercice.OUVERT);
        return exercice;
    }

    private Journal journalAchats() {
        Journal journal = new Journal();
        journal.setId(UUID.randomUUID());
        journal.setEntreprise(entreprise);
        journal.setCode("ACH");
        return journal;
    }

    /**
     * Migre (Phase 1, lot 1.2) : l'ancien test verifiait la construction directe de l'ecriture par ce
     * service. Il verifie desormais l'ORCHESTRATION - le service ne cree plus d'EcritureComptable,
     * il resout la TVA par purpose et confie la proposition au moteur en BROUILLON. Le rendu detaille
     * (lignes, comptes, journal, numerotation) est fige par ComptabilisationDocumentCaracterisationTest.
     */
    @Test
    void confie_la_creation_de_l_ecriture_au_moteur_en_brouillon_avec_la_tva_resolue_par_purpose() {
        Document document = documentFactureAchat(new BigDecimal("100000"), new BigDecimal("18000"), new BigDecimal("118000"));
        CompteComptable compteCharge = compteAvecNature("601", NatureCompte.CHARGE);
        CompteComptable compteFournisseur = compteAvecNature("401001", null);
        CompteComptable compteTva = compteAvecNature("4456", null);
        Tiers fournisseur = tiersFournisseur(compteFournisseur);
        UUID ecritureId = UUID.randomUUID();

        when(documentRepository.findById(documentId)).thenReturn(Optional.of(document));
        when(tiersRepository.findById(fournisseur.getId())).thenReturn(Optional.of(fournisseur));
        when(compteComptableRepository.findById(compteCharge.getId())).thenReturn(Optional.of(compteCharge));
        when(compteComptableRepository.findById(compteTva.getId())).thenReturn(Optional.of(compteTva));
        when(accountingAccountResolver.resolve(eq(entrepriseId), eq(AccountPurpose.TVA_RECUPERABLE), any(), isNull()))
                .thenReturn(new ResolvedAccount(com.example.comptable.comptabilite.parametrage.AccountResolutionStatus.RESOLVED,
                        compteTva.getId(), "4456", "TVA", com.example.comptable.comptabilite.parametrage.AccountResolutionSource.ROLE_SYSTEME,
                        com.example.comptable.comptabilite.parametrage.NiveauConfiance.CERTAIN, "test"));
        List<AccountingOperationProposalLigne> lignes = List.of(AccountingOperationProposalLigne.debit(compteCharge, new BigDecimal("100000"), "x"));
        when(achatRule.construireLignesAchatDepuisMontantsPrecalcules(eq(fournisseur), eq(compteCharge), any(), eq(compteTva), any(), any(), any()))
                .thenReturn(lignes);
        AccountingOperationProposalDto proposition = org.mockito.Mockito.mock(AccountingOperationProposalDto.class);
        when(proposition.ecritureId()).thenReturn(ecritureId);
        when(accountingOperationEngine.proposerEtValider(eq(entrepriseId), eq(AccountingOperationType.ACHAT), eq(fournisseur), eq(document.getDatePiece()), any(), isNull(), any(ResultatRegle.class), eq(AccountingSource.de("DOCUMENT", documentId, "COMPTABILISATION")), eq(StatutEcriture.BROUILLON), eq(principal))).thenReturn(proposition);
        EcritureComptable ecriture = new EcritureComptable();
        ecriture.setId(ecritureId);
        ecriture.setEntreprise(entreprise);
        Journal journal = new Journal();
        journal.setCode("ACH");
        ecriture.setJournal(journal);
        ecriture.setNumeroPiece("ACH-2026-0001");
        ecriture.setDateEcriture(document.getDatePiece());
        ecriture.setLibelle("x");
        ecriture.setStatut(StatutEcriture.BROUILLON);
        when(ecritureComptableRepository.findById(ecritureId)).thenReturn(Optional.of(ecriture));
        when(documentRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        EcritureComptableDto resultat = comptabilisationService.comptabiliser(entrepriseId, documentId,
                new ComptabiliserDocumentRequest(fournisseur.getId(), compteCharge.getId(), null, null), principal);

        assertThat(resultat.id()).isEqualTo(ecritureId);
        assertThat(resultat.statut()).isEqualTo("BROUILLON");
        assertThat(document.getEcritureId()).isEqualTo(ecritureId);
        // Le service ne construit ni ne sauvegarde plus d'ecriture lui-meme.
        org.mockito.Mockito.verify(ecritureComptableRepository, org.mockito.Mockito.never()).save(any());
    }

    @Test
    void refuse_si_ht_plus_tva_ne_correspond_pas_au_ttc() {
        Document document = documentFactureAchat(new BigDecimal("100000"), new BigDecimal("18000"), new BigDecimal("999999"));
        when(documentRepository.findById(documentId)).thenReturn(Optional.of(document));

        ComptabiliserDocumentRequest request = new ComptabiliserDocumentRequest(UUID.randomUUID(), UUID.randomUUID(), null, null);

        assertThatThrownBy(() -> comptabilisationService.comptabiliser(entrepriseId, documentId, request, principal))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("HT + TVA");
    }

    @Test
    void refuse_si_le_document_est_deja_comptabilise() {
        Document document = documentFactureAchat(new BigDecimal("100000"), new BigDecimal("18000"), new BigDecimal("118000"));
        document.setEcritureId(UUID.randomUUID());
        when(documentRepository.findById(documentId)).thenReturn(Optional.of(document));

        ComptabiliserDocumentRequest request = new ComptabiliserDocumentRequest(UUID.randomUUID(), UUID.randomUUID(), null, null);

        assertThatThrownBy(() -> comptabilisationService.comptabiliser(entrepriseId, documentId, request, principal))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("deja ete comptabilise");
    }

    @Test
    void refuse_les_types_de_document_non_supportes() {
        Document document = documentFactureAchat(new BigDecimal("100000"), new BigDecimal("18000"), new BigDecimal("118000"));
        document.setTypeDocument(TypeDocument.RECU);
        when(documentRepository.findById(documentId)).thenReturn(Optional.of(document));

        ComptabiliserDocumentRequest request = new ComptabiliserDocumentRequest(UUID.randomUUID(), UUID.randomUUID(), null, null);

        assertThatThrownBy(() -> comptabilisationService.comptabiliser(entrepriseId, documentId, request, principal))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("non disponible");
    }

    @Test
    void refuse_un_tiers_du_mauvais_type() {
        Document document = documentFactureAchat(new BigDecimal("100000"), new BigDecimal("18000"), new BigDecimal("118000"));
        Tiers client = new Tiers();
        client.setId(UUID.randomUUID());
        client.setEntreprise(entreprise);
        client.setType(TypeTiers.CLIENT);
        client.setNom("Un client, pas un fournisseur");
        client.setCompteComptable(compteAvecNature("411001", null));

        when(documentRepository.findById(documentId)).thenReturn(Optional.of(document));
        when(tiersRepository.findById(client.getId())).thenReturn(Optional.of(client));

        ComptabiliserDocumentRequest request = new ComptabiliserDocumentRequest(client.getId(), UUID.randomUUID(), null, null);

        assertThatThrownBy(() -> comptabilisationService.comptabiliser(entrepriseId, documentId, request, principal))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("fournisseur");
    }

    @Test
    void refuse_un_compte_de_mauvaise_nature() {
        Document document = documentFactureAchat(new BigDecimal("100000"), new BigDecimal("18000"), new BigDecimal("118000"));
        Tiers fournisseur = tiersFournisseur(compteAvecNature("401001", null));
        CompteComptable compteProduit = compteAvecNature("701", NatureCompte.PRODUIT);

        when(documentRepository.findById(documentId)).thenReturn(Optional.of(document));
        when(tiersRepository.findById(fournisseur.getId())).thenReturn(Optional.of(fournisseur));
        when(compteComptableRepository.findById(compteProduit.getId())).thenReturn(Optional.of(compteProduit));

        ComptabiliserDocumentRequest request = new ComptabiliserDocumentRequest(fournisseur.getId(), compteProduit.getId(), null, null);

        assertThatThrownBy(() -> comptabilisationService.comptabiliser(entrepriseId, documentId, request, principal))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("CHARGE");
    }
}
