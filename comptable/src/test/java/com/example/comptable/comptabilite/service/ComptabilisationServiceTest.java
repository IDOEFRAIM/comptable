package com.example.comptable.comptabilite.service;

import com.example.comptable.comptabilite.domain.CompteComptable;
import com.example.comptable.comptabilite.domain.EcritureComptable;
import com.example.comptable.comptabilite.domain.ExerciceComptable;
import com.example.comptable.comptabilite.domain.Journal;
import com.example.comptable.comptabilite.domain.NatureCompte;
import com.example.comptable.comptabilite.domain.PlanComptableReference;
import com.example.comptable.comptabilite.domain.StatutExercice;
import com.example.comptable.comptabilite.domain.Tiers;
import com.example.comptable.comptabilite.domain.TypeTiers;
import com.example.comptable.comptabilite.repository.CompteComptableRepository;
import com.example.comptable.comptabilite.repository.EcritureComptableRepository;
import com.example.comptable.comptabilite.repository.ExerciceComptableRepository;
import com.example.comptable.comptabilite.repository.JournalRepository;
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
import com.example.comptable.identite.repository.UtilisateurRepository;
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
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
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
    private ExerciceComptableRepository exerciceComptableRepository;
    @Mock
    private JournalRepository journalRepository;
    @Mock
    private EcritureComptableRepository ecritureComptableRepository;
    @Mock
    private UtilisateurRepository utilisateurRepository;

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

    @Test
    void genere_une_ecriture_dachat_equilibree_avec_tva() {
        Document document = documentFactureAchat(new BigDecimal("100000"), new BigDecimal("18000"), new BigDecimal("118000"));
        CompteComptable compteCharge = compteAvecNature("601", NatureCompte.CHARGE);
        CompteComptable compteFournisseur = compteAvecNature("401001", null);
        CompteComptable compteTva = compteAvecNature("4456", null);
        Tiers fournisseur = tiersFournisseur(compteFournisseur);
        ExerciceComptable exercice = exerciceOuvert();
        Journal journal = journalAchats();

        when(documentRepository.findById(documentId)).thenReturn(Optional.of(document));
        when(tiersRepository.findById(fournisseur.getId())).thenReturn(Optional.of(fournisseur));
        when(compteComptableRepository.findById(compteCharge.getId())).thenReturn(Optional.of(compteCharge));
        when(exerciceComptableRepository.findByEntrepriseIdAndDate(entrepriseId, document.getDatePiece()))
                .thenReturn(Optional.of(exercice));
        when(journalRepository.findByEntrepriseIdAndCode(entrepriseId, "ACH")).thenReturn(Optional.of(journal));
        when(compteComptableRepository.findByEntrepriseIdAndNumero(entrepriseId, "4456"))
                .thenReturn(Optional.of(compteTva));
        when(ecritureComptableRepository.countByJournalId(journal.getId())).thenReturn(0L);
        when(utilisateurRepository.getReferenceById(principal.getId())).thenReturn(utilisateur);
        when(ecritureComptableRepository.save(any())).thenAnswer(inv -> {
            EcritureComptable ecriture = inv.getArgument(0);
            if (ecriture.getId() == null) {
                ecriture.setId(UUID.randomUUID());
            }
            return ecriture;
        });
        when(documentRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        ComptabiliserDocumentRequest request = new ComptabiliserDocumentRequest(fournisseur.getId(), compteCharge.getId(), null, null);

        EcritureComptableDto resultat = comptabilisationService.comptabiliser(entrepriseId, documentId, request, principal);

        assertThat(resultat.statut()).isEqualTo("BROUILLON");
        assertThat(resultat.lignes()).hasSize(3);
        assertThat(resultat.totalDebit()).isEqualByComparingTo("118000");
        assertThat(resultat.totalCredit()).isEqualByComparingTo("118000");
        assertThat(document.getEcritureId()).isNotNull();
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
