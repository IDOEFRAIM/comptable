package com.example.comptable.comptabilite.operation.common;

import com.example.comptable.comptabilite.domain.CompteComptable;
import com.example.comptable.comptabilite.domain.EcritureComptable;
import com.example.comptable.comptabilite.domain.ExerciceComptable;
import com.example.comptable.comptabilite.domain.Journal;
import com.example.comptable.comptabilite.domain.StatutExercice;
import com.example.comptable.comptabilite.domain.Tiers;
import com.example.comptable.comptabilite.domain.TypeJournal;
import com.example.comptable.comptabilite.domain.TypeTiers;
import com.example.comptable.comptabilite.operation.achat.AchatOperationRequest;
import com.example.comptable.comptabilite.operation.achat.AchatRule;
import com.example.comptable.comptabilite.operation.achat.NatureAchat;
import com.example.comptable.comptabilite.operation.avoir.AvoirRule;
import com.example.comptable.comptabilite.operation.common.domain.AccountingOperationProposal;
import com.example.comptable.comptabilite.operation.common.domain.AccountingOperationProposalLigne;
import com.example.comptable.comptabilite.operation.common.domain.StatutOperationProposal;
import com.example.comptable.comptabilite.operation.common.repository.AccountingOperationProposalRepository;
import com.example.comptable.comptabilite.operation.common.web.dto.AccountingOperationProposalDto;
import com.example.comptable.comptabilite.operation.reglement.ReglementRule;
import com.example.comptable.comptabilite.operation.vente.VenteRule;
import com.example.comptable.comptabilite.repository.EcritureComptableRepository;
import com.example.comptable.comptabilite.repository.ExerciceComptableRepository;
import com.example.comptable.comptabilite.repository.TiersRepository;
import com.example.comptable.comptabilite.service.CompteComptableValidationService;
import com.example.comptable.comptabilite.service.NumeroPieceGenerator;
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
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class AccountingOperationEngineTest {

    @Mock
    private EntrepriseAccessService entrepriseAccessService;
    @Mock
    private TiersRepository tiersRepository;
    @Mock
    private ExerciceComptableRepository exerciceComptableRepository;
    @Mock
    private EcritureComptableRepository ecritureComptableRepository;
    @Mock
    private UtilisateurRepository utilisateurRepository;
    @Mock
    private AccountingOperationProposalRepository accountingOperationProposalRepository;
    @Mock
    private CompteComptableValidationService compteComptableValidationService;
    @Mock
    private JournalResolver journalResolver;
    @Mock
    private NumeroPieceGenerator numeroPieceGenerator;
    @Mock
    private AchatRule achatRule;
    @Mock
    private VenteRule venteRule;
    @Mock
    private ReglementRule reglementRule;
    @Mock
    private AvoirRule avoirRule;

    @Mock
    private com.example.comptable.audit.AuditTrailService auditTrailService;

    @Mock
    private com.example.comptable.controle.RefusComptablePublisher refusComptablePublisher;

    @InjectMocks
    private AccountingOperationEngine engine;

    private UUID entrepriseId;
    private Entreprise entreprise;
    private UtilisateurPrincipal principal;
    private Utilisateur utilisateur;
    private Tiers fournisseur;
    private CompteComptable compteFournisseur;
    private CompteComptable compteCharge;
    private Journal journalAchats;
    private ExerciceComptable exerciceOuvert;

    @BeforeEach
    void setUp() {
        entrepriseId = UUID.randomUUID();
        entreprise = new Entreprise();
        entreprise.setId(entrepriseId);

        Role role = new Role();
        role.setCode("COLLABORATEUR");
        utilisateur = new Utilisateur();
        utilisateur.setId(UUID.randomUUID());
        utilisateur.setEmail("test@cabinet.ci");
        utilisateur.setMotDePasse("hash");
        utilisateur.setNom("Nom");
        utilisateur.setPrenom("Prenom");
        utilisateur.setRoles(Set.of(role));
        principal = new UtilisateurPrincipal(utilisateur);

        compteFournisseur = compte("401001");
        fournisseur = new Tiers();
        fournisseur.setId(UUID.randomUUID());
        fournisseur.setEntreprise(entreprise);
        fournisseur.setType(TypeTiers.FOURNISSEUR);
        fournisseur.setCompteComptable(compteFournisseur);

        compteCharge = compte("601");

        journalAchats = new Journal();
        journalAchats.setId(UUID.randomUUID());
        journalAchats.setCode("ACH");
        journalAchats.setType(TypeJournal.ACHATS);

        exerciceOuvert = new ExerciceComptable();
        exerciceOuvert.setId(UUID.randomUUID());
        exerciceOuvert.setDateDebut(LocalDate.of(2026, 1, 1));
        exerciceOuvert.setDateFin(LocalDate.of(2026, 12, 31));
        exerciceOuvert.setStatut(StatutExercice.OUVERT);
    }

    private CompteComptable compte(String numero) {
        CompteComptable c = new CompteComptable();
        c.setId(UUID.randomUUID());
        c.setEntreprise(entreprise);
        c.setNumero(numero);
        c.setIntitule(numero);
        c.setActif(true);
        return c;
    }

    private AchatOperationRequest achatRequest() {
        return new AchatOperationRequest(NatureAchat.MARCHANDISE, fournisseur.getId(), LocalDate.of(2026, 3, 1),
                "REF-1", "Achat test", new BigDecimal("100000"), compteCharge.getId(), null, null, null, null);
    }

    private ResultatRegle resultatEquilibre() {
        List<AccountingOperationProposalLigne> lignes = List.of(
                AccountingOperationProposalLigne.debit(compteCharge, new BigDecimal("100000"), "Achat test"),
                AccountingOperationProposalLigne.credit(compteFournisseur, new BigDecimal("100000"), "Achat test"));
        return new ResultatRegle(TypeJournal.ACHATS, lignes, List.of(), List.of());
    }

    @Test
    void proposer_achat_avec_journal_resolu_et_lignes_equilibrees_reste_proposed() {
        when(entrepriseAccessService.verifierAcces(entrepriseId, principal)).thenReturn(entreprise);
        when(tiersRepository.findById(fournisseur.getId())).thenReturn(Optional.of(fournisseur));
        when(exerciceComptableRepository.findByEntrepriseIdAndDate(entrepriseId, LocalDate.of(2026, 3, 1)))
                .thenReturn(Optional.of(exerciceOuvert));
        when(achatRule.construire(entreprise, fournisseur, achatRequest())).thenReturn(resultatEquilibre());
        when(journalResolver.resoudre(entrepriseId, TypeJournal.ACHATS)).thenReturn(Optional.of(journalAchats));
        when(accountingOperationProposalRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        AccountingOperationProposalDto dto = engine.proposerAchat(entrepriseId, achatRequest(), principal);

        assertThat(dto.statut()).isEqualTo("PROPOSED");
        assertThat(dto.equilibree()).isTrue();
        assertThat(dto.totalDebit()).isEqualByComparingTo("100000");
        assertThat(dto.totalCredit()).isEqualByComparingTo("100000");
        assertThat(dto.journalCode()).isEqualTo("ACH");
        assertThat(dto.explications()).isEmpty();
    }

    @Test
    void proposer_achat_sans_journal_configure_devient_needs_review() {
        when(entrepriseAccessService.verifierAcces(entrepriseId, principal)).thenReturn(entreprise);
        when(tiersRepository.findById(fournisseur.getId())).thenReturn(Optional.of(fournisseur));
        when(exerciceComptableRepository.findByEntrepriseIdAndDate(entrepriseId, LocalDate.of(2026, 3, 1)))
                .thenReturn(Optional.of(exerciceOuvert));
        when(achatRule.construire(entreprise, fournisseur, achatRequest())).thenReturn(resultatEquilibre());
        when(journalResolver.resoudre(entrepriseId, TypeJournal.ACHATS)).thenReturn(Optional.empty());
        when(accountingOperationProposalRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        AccountingOperationProposalDto dto = engine.proposerAchat(entrepriseId, achatRequest(), principal);

        assertThat(dto.statut()).isEqualTo("NEEDS_REVIEW");
        assertThat(dto.explications()).anySatisfy(e -> assertThat(e).contains("Aucun journal"));
    }

    @Test
    void proposer_achat_avec_regle_incomplete_devient_needs_review() {
        when(entrepriseAccessService.verifierAcces(entrepriseId, principal)).thenReturn(entreprise);
        when(tiersRepository.findById(fournisseur.getId())).thenReturn(Optional.of(fournisseur));
        when(exerciceComptableRepository.findByEntrepriseIdAndDate(entrepriseId, LocalDate.of(2026, 3, 1)))
                .thenReturn(Optional.of(exerciceOuvert));
        ResultatRegle incomplet = new ResultatRegle(TypeJournal.ACHATS,
                List.of(AccountingOperationProposalLigne.debit(compteCharge, new BigDecimal("100000"), "Achat test")),
                List.of("TVA deductible non calculee : aucune regle"), List.of());
        when(achatRule.construire(entreprise, fournisseur, achatRequest())).thenReturn(incomplet);
        when(journalResolver.resoudre(entrepriseId, TypeJournal.ACHATS)).thenReturn(Optional.of(journalAchats));
        when(accountingOperationProposalRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        AccountingOperationProposalDto dto = engine.proposerAchat(entrepriseId, achatRequest(), principal);

        assertThat(dto.statut()).isEqualTo("NEEDS_REVIEW");
        assertThat(dto.equilibree()).isFalse();
    }

    @Test
    void tiers_dune_autre_entreprise_est_refuse_avec_404() {
        Entreprise autre = new Entreprise();
        autre.setId(UUID.randomUUID());
        fournisseur.setEntreprise(autre);

        when(entrepriseAccessService.verifierAcces(entrepriseId, principal)).thenReturn(entreprise);
        when(tiersRepository.findById(fournisseur.getId())).thenReturn(Optional.of(fournisseur));

        assertThatThrownBy(() -> engine.proposerAchat(entrepriseId, achatRequest(), principal))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("Tiers introuvable");
    }

    @Test
    void tiers_du_mauvais_type_est_refuse() {
        fournisseur.setType(TypeTiers.CLIENT);

        when(entrepriseAccessService.verifierAcces(entrepriseId, principal)).thenReturn(entreprise);
        when(tiersRepository.findById(fournisseur.getId())).thenReturn(Optional.of(fournisseur));

        assertThatThrownBy(() -> engine.proposerAchat(entrepriseId, achatRequest(), principal))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("fournisseur");
    }

    @Test
    void exercice_cloture_est_refuse() {
        exerciceOuvert.setStatut(StatutExercice.CLOTURE);
        when(entrepriseAccessService.verifierAcces(entrepriseId, principal)).thenReturn(entreprise);
        when(tiersRepository.findById(fournisseur.getId())).thenReturn(Optional.of(fournisseur));
        when(exerciceComptableRepository.findByEntrepriseIdAndDate(entrepriseId, LocalDate.of(2026, 3, 1)))
                .thenReturn(Optional.of(exerciceOuvert));

        assertThatThrownBy(() -> engine.proposerAchat(entrepriseId, achatRequest(), principal))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("cloture");
    }

    @Test
    void aucun_exercice_pour_la_date_est_refuse() {
        when(entrepriseAccessService.verifierAcces(entrepriseId, principal)).thenReturn(entreprise);
        when(tiersRepository.findById(fournisseur.getId())).thenReturn(Optional.of(fournisseur));
        when(exerciceComptableRepository.findByEntrepriseIdAndDate(entrepriseId, LocalDate.of(2026, 3, 1)))
                .thenReturn(Optional.empty());

        assertThatThrownBy(() -> engine.proposerAchat(entrepriseId, achatRequest(), principal))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("Aucun exercice");
    }

    private AccountingOperationProposal propositionPersistee(StatutOperationProposal statut) {
        AccountingOperationProposal proposition = new AccountingOperationProposal();
        proposition.setId(UUID.randomUUID());
        proposition.setEntreprise(entreprise);
        proposition.setType(AccountingOperationType.ACHAT);
        proposition.setTiers(fournisseur);
        proposition.setJournal(journalAchats);
        proposition.setDateOperation(LocalDate.of(2026, 3, 1));
        proposition.setLibelle("Achat test");
        proposition.setStatut(statut);
        proposition.ajouterLigne(AccountingOperationProposalLigne.debit(compteCharge, new BigDecimal("100000"), "Achat test"));
        proposition.ajouterLigne(AccountingOperationProposalLigne.credit(compteFournisseur, new BigDecimal("100000"), "Achat test"));
        proposition.setMontantTotalDebit(new BigDecimal("100000"));
        proposition.setMontantTotalCredit(new BigDecimal("100000"));
        proposition.setEquilibree(true);
        return proposition;
    }

    @Test
    void valider_une_proposition_proposed_cree_une_ecriture_validee() {
        AccountingOperationProposal proposition = propositionPersistee(StatutOperationProposal.PROPOSED);
        when(entrepriseAccessService.verifierAcces(entrepriseId, principal)).thenReturn(entreprise);
        when(accountingOperationProposalRepository.findById(proposition.getId())).thenReturn(Optional.of(proposition));
        when(compteComptableValidationService.chargerCompteUtilisable(entrepriseId, compteCharge.getId(), true)).thenReturn(compteCharge);
        when(compteComptableValidationService.chargerCompteUtilisable(entrepriseId, compteFournisseur.getId(), true)).thenReturn(compteFournisseur);
        when(exerciceComptableRepository.findByEntrepriseIdAndDate(entrepriseId, proposition.getDateOperation()))
                .thenReturn(Optional.of(exerciceOuvert));
        when(utilisateurRepository.getReferenceById(principal.getId())).thenReturn(utilisateur);
        when(numeroPieceGenerator.genererNumeroPiece(journalAchats)).thenReturn("ACH-2026-0001");
        when(ecritureComptableRepository.save(any())).thenAnswer(inv -> {
            EcritureComptable e = inv.getArgument(0);
            e.setId(UUID.randomUUID());
            return e;
        });
        when(accountingOperationProposalRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        AccountingOperationProposalDto dto = engine.valider(entrepriseId, proposition.getId(), principal);

        assertThat(dto.statut()).isEqualTo("APPROVED");
        assertThat(dto.ecritureId()).isNotNull();
    }

    @Test
    void valider_une_proposition_needs_review_est_refuse() {
        AccountingOperationProposal proposition = propositionPersistee(StatutOperationProposal.NEEDS_REVIEW);
        when(entrepriseAccessService.verifierAcces(entrepriseId, principal)).thenReturn(entreprise);
        when(accountingOperationProposalRepository.findById(proposition.getId())).thenReturn(Optional.of(proposition));

        assertThatThrownBy(() -> engine.valider(entrepriseId, proposition.getId(), principal))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("n'est pas complete");
    }

    @Test
    void valider_une_proposition_dont_les_lignes_ne_sont_plus_equilibrees_est_refuse() {
        AccountingOperationProposal proposition = propositionPersistee(StatutOperationProposal.PROPOSED);
        // Corruption simulee : une ligne supplementaire desequilibre l'ecriture reconstruite au
        // moment de la validation (defense en profondeur), meme si la proposition avait ete
        // marquee equilibree a la creation.
        proposition.ajouterLigne(AccountingOperationProposalLigne.debit(compteCharge, new BigDecimal("5000"), "Correction"));

        when(entrepriseAccessService.verifierAcces(entrepriseId, principal)).thenReturn(entreprise);
        when(accountingOperationProposalRepository.findById(proposition.getId())).thenReturn(Optional.of(proposition));
        when(compteComptableValidationService.chargerCompteUtilisable(entrepriseId, compteCharge.getId(), true)).thenReturn(compteCharge);
        when(compteComptableValidationService.chargerCompteUtilisable(entrepriseId, compteFournisseur.getId(), true)).thenReturn(compteFournisseur);
        when(exerciceComptableRepository.findByEntrepriseIdAndDate(entrepriseId, proposition.getDateOperation()))
                .thenReturn(Optional.of(exerciceOuvert));
        when(utilisateurRepository.getReferenceById(principal.getId())).thenReturn(utilisateur);

        assertThatThrownBy(() -> engine.valider(entrepriseId, proposition.getId(), principal))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("desequilibree");
    }

    @Test
    void rejeter_une_proposition_proposed_la_marque_rejected_avec_motif() {
        AccountingOperationProposal proposition = propositionPersistee(StatutOperationProposal.PROPOSED);
        when(entrepriseAccessService.verifierAcces(entrepriseId, principal)).thenReturn(entreprise);
        when(accountingOperationProposalRepository.findById(proposition.getId())).thenReturn(Optional.of(proposition));
        when(utilisateurRepository.getReferenceById(principal.getId())).thenReturn(utilisateur);
        when(accountingOperationProposalRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        AccountingOperationProposalDto dto = engine.rejeter(entrepriseId, proposition.getId(), "Erreur de saisie", principal);

        assertThat(dto.statut()).isEqualTo("REJECTED");
        assertThat(dto.rejectionReason()).isEqualTo("Erreur de saisie");
    }

    @Test
    void rejeter_une_proposition_deja_traitee_est_refuse() {
        AccountingOperationProposal proposition = propositionPersistee(StatutOperationProposal.APPROVED);
        when(entrepriseAccessService.verifierAcces(entrepriseId, principal)).thenReturn(entreprise);
        when(accountingOperationProposalRepository.findById(proposition.getId())).thenReturn(Optional.of(proposition));

        assertThatThrownBy(() -> engine.rejeter(entrepriseId, proposition.getId(), "motif", principal))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("deja ete traitee");
    }

    @Test
    void idempotence_une_source_deja_proposee_ne_cree_pas_une_seconde_proposition() {
        AccountingOperationProposal existante = propositionPersistee(StatutOperationProposal.APPROVED);
        UUID sourceId = UUID.randomUUID();
        existante.setSourceType("FACTURE");
        existante.setSourceId(sourceId);

        when(entrepriseAccessService.verifierAcces(entrepriseId, principal)).thenReturn(entreprise);
        when(accountingOperationProposalRepository.findFirstByEntrepriseIdAndSourceTypeAndSourceIdAndSourceEventAndStatutNot(
                entrepriseId, "FACTURE", sourceId, "VALIDATION", StatutOperationProposal.REJECTED))
                .thenReturn(Optional.of(existante));

        AccountingOperationProposalDto dto = engine.proposerEtValiderImmediatement(entrepriseId, AccountingOperationType.ACHAT,
                fournisseur, LocalDate.of(2026, 3, 1), "libelle", null, resultatEquilibre(), AccountingSource.de("FACTURE", sourceId, "VALIDATION"), principal);

        assertThat(dto.id()).isEqualTo(existante.getId());
        assertThat(dto.statut()).isEqualTo("APPROVED");
        org.mockito.Mockito.verify(accountingOperationProposalRepository, org.mockito.Mockito.never()).save(any());
    }

    @Test
    void idempotence_une_source_rejetee_autorise_une_nouvelle_proposition() {
        AccountingOperationProposal rejetee = propositionPersistee(StatutOperationProposal.REJECTED);
        UUID sourceId = UUID.randomUUID();
        rejetee.setSourceType("FACTURE");
        rejetee.setSourceId(sourceId);

        when(entrepriseAccessService.verifierAcces(entrepriseId, principal)).thenReturn(entreprise);
        when(accountingOperationProposalRepository.findFirstByEntrepriseIdAndSourceTypeAndSourceIdAndSourceEventAndStatutNot(
                entrepriseId, "FACTURE", sourceId, "VALIDATION", StatutOperationProposal.REJECTED))
                .thenReturn(Optional.empty()); // une proposition rejetee libere la cause : la requete l'exclut
        when(journalResolver.resoudre(entrepriseId, TypeJournal.ACHATS)).thenReturn(Optional.of(journalAchats));
        java.util.concurrent.atomic.AtomicReference<AccountingOperationProposal> nouvelleProposition = new java.util.concurrent.atomic.AtomicReference<>();
        when(accountingOperationProposalRepository.save(any())).thenAnswer(inv -> {
            AccountingOperationProposal p = inv.getArgument(0);
            if (p.getId() == null) {
                p.setId(UUID.randomUUID());
            }
            nouvelleProposition.set(p);
            return p;
        });
        when(accountingOperationProposalRepository.findById(any())).thenAnswer(inv -> {
            UUID id = inv.getArgument(0);
            AccountingOperationProposal p = nouvelleProposition.get();
            return p != null && p.getId().equals(id) ? Optional.of(p) : Optional.empty();
        });
        when(compteComptableValidationService.chargerCompteUtilisable(entrepriseId, compteCharge.getId(), true)).thenReturn(compteCharge);
        when(compteComptableValidationService.chargerCompteUtilisable(entrepriseId, compteFournisseur.getId(), true)).thenReturn(compteFournisseur);
        when(exerciceComptableRepository.findByEntrepriseIdAndDate(entrepriseId, LocalDate.of(2026, 3, 1)))
                .thenReturn(Optional.of(exerciceOuvert));
        when(utilisateurRepository.getReferenceById(principal.getId())).thenReturn(utilisateur);
        when(numeroPieceGenerator.genererNumeroPiece(journalAchats)).thenReturn("ACH-2026-0002");
        when(ecritureComptableRepository.save(any())).thenAnswer(inv -> {
            EcritureComptable e = inv.getArgument(0);
            e.setId(UUID.randomUUID());
            return e;
        });

        AccountingOperationProposalDto dto = engine.proposerEtValiderImmediatement(entrepriseId, AccountingOperationType.ACHAT,
                fournisseur, LocalDate.of(2026, 3, 1), "libelle", null, resultatEquilibre(), AccountingSource.de("FACTURE", sourceId, "VALIDATION"), principal);

        assertThat(dto.id()).isNotEqualTo(rejetee.getId());
        assertThat(dto.statut()).isEqualTo("APPROVED");
    }

    @Test
    void proposerEtValiderImmediatement_avec_regle_incomplete_echoue_sans_valider() {
        when(entrepriseAccessService.verifierAcces(entrepriseId, principal)).thenReturn(entreprise);
        when(accountingOperationProposalRepository.findFirstByEntrepriseIdAndSourceTypeAndSourceIdAndSourceEventAndStatutNot(any(), any(), any(), any(), any()))
                .thenReturn(Optional.empty());
        when(journalResolver.resoudre(entrepriseId, TypeJournal.ACHATS)).thenReturn(Optional.of(journalAchats));
        when(accountingOperationProposalRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));
        ResultatRegle incomplet = new ResultatRegle(TypeJournal.ACHATS, List.of(), List.of("TVA non calculee"), List.of());

        assertThatThrownBy(() -> engine.proposerEtValiderImmediatement(entrepriseId, AccountingOperationType.ACHAT, fournisseur,
                LocalDate.of(2026, 3, 1), "libelle", null, incomplet, AccountingSource.de("FACTURE", UUID.randomUUID(), "VALIDATION"), principal))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("incomplete");
    }

    @Test
    void obtenir_une_proposition_dune_autre_entreprise_est_refuse_avec_404() {
        AccountingOperationProposal proposition = propositionPersistee(StatutOperationProposal.PROPOSED);
        Entreprise autre = new Entreprise();
        autre.setId(UUID.randomUUID());
        proposition.setEntreprise(autre);

        when(entrepriseAccessService.verifierAcces(entrepriseId, principal)).thenReturn(entreprise);
        when(accountingOperationProposalRepository.findById(proposition.getId())).thenReturn(Optional.of(proposition));

        assertThatThrownBy(() -> engine.obtenir(entrepriseId, proposition.getId(), principal))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("Proposition introuvable");
    }
}
