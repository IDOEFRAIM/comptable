package com.example.comptable.comptabilite.service;

import com.example.comptable.comptabilite.domain.CompteComptable;
import com.example.comptable.comptabilite.domain.ExerciceComptable;
import com.example.comptable.comptabilite.domain.PlanComptableReference;
import com.example.comptable.comptabilite.domain.StatutExercice;
import com.example.comptable.comptabilite.operation.cloture.ClotureRule;
import com.example.comptable.comptabilite.operation.common.AccountingOperationEngine;
import com.example.comptable.comptabilite.operation.common.AccountingOperationType;
import com.example.comptable.comptabilite.operation.common.ResultatRegle;
import com.example.comptable.comptabilite.operation.common.domain.AccountingOperationProposalLigne;
import com.example.comptable.comptabilite.operation.common.web.dto.AccountingOperationProposalDto;
import com.example.comptable.comptabilite.repository.CompteComptableRepository;
import com.example.comptable.comptabilite.repository.ExerciceComptableRepository;
import com.example.comptable.comptabilite.repository.LigneEcritureRepository;
import com.example.comptable.comptabilite.web.dto.CompteResultatDto;
import com.example.comptable.comptabilite.web.dto.ExerciceComptableDto;
import com.example.comptable.comptabilite.web.dto.SectionFinanciereDto;
import com.example.comptable.dossier.ClotureReadinessService;
import com.example.comptable.dossier.web.dto.ClotureReadinessDto;
import com.example.comptable.entreprise.domain.Entreprise;
import com.example.comptable.entreprise.service.EntrepriseAccessService;
import com.example.comptable.identite.domain.Role;
import com.example.comptable.identite.domain.Utilisateur;
import com.example.comptable.security.UtilisateurPrincipal;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
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
import static org.mockito.Mockito.when;

/**
 * ClotureExerciceService route desormais l'ecriture de determination du resultat via
 * ClotureRule + AccountingOperationEngine.proposerEtValiderImmediatement (mission cloture Etape 4,
 * section 17) plutot que de construire une EcritureComptable a la main - ces deux collaborateurs
 * sont donc mockes ici comme des unites a part entiere (chacun a ses propres tests dedies :
 * AccountingOperationEngineTest, et un test dedie a ClotureRule) plutot que re-testes en detail
 * depuis ce fichier. La checklist de cloture (ClotureReadinessService) est le seul gardien
 * consulte pour bloquer une cloture (section 15) : le controle brouillon "en dur" precedemment
 * duplique ici a ete retire au profit de cette source unique.
 */
@ExtendWith(MockitoExtension.class)
class ClotureExerciceServiceTest {

    @Mock
    private EntrepriseAccessService entrepriseAccessService;
    @Mock
    private ExerciceComptableRepository exerciceComptableRepository;
    @Mock
    private CompteComptableRepository compteComptableRepository;
    @Mock
    private LigneEcritureRepository ligneEcritureRepository;
    @Mock
    private EtatsFinanciersService etatsFinanciersService;
    @Mock
    private ClotureReadinessService clotureReadinessService;
    @Mock
    private ClotureRule clotureRule;
    @Mock
    private AccountingOperationEngine accountingOperationEngine;

    @InjectMocks
    private ClotureExerciceService clotureExerciceService;

    private UUID entrepriseId;
    private UUID exerciceId;
    private Entreprise entreprise;
    private ExerciceComptable exercice;
    private UtilisateurPrincipal principal;
    private CompteComptable achats;
    private CompteComptable ventes;

    @BeforeEach
    void setUp() {
        entrepriseId = UUID.randomUUID();
        exerciceId = UUID.randomUUID();
        entreprise = new Entreprise();
        entreprise.setId(entrepriseId);

        exercice = new ExerciceComptable();
        exercice.setId(exerciceId);
        exercice.setEntreprise(entreprise);
        exercice.setDateDebut(LocalDate.of(2026, 1, 1));
        exercice.setDateFin(LocalDate.of(2026, 12, 31));
        exercice.setStatut(StatutExercice.OUVERT);

        Role role = new Role();
        role.setCode("EXPERT_COMPTABLE");
        Utilisateur utilisateur = new Utilisateur();
        utilisateur.setId(UUID.randomUUID());
        utilisateur.setEmail("test@cabinet.ci");
        utilisateur.setMotDePasse("hash");
        utilisateur.setNom("Nom");
        utilisateur.setPrenom("Prenom");
        utilisateur.setRoles(Set.of(role));
        principal = new UtilisateurPrincipal(utilisateur);

        achats = compte("601", 6);
        ventes = compte("701", 7);

        when(entrepriseAccessService.verifierAcces(entrepriseId, principal)).thenReturn(entreprise);
        when(exerciceComptableRepository.findById(exerciceId)).thenReturn(Optional.of(exercice));
    }

    private CompteComptable compte(String numero, int classe) {
        PlanComptableReference reference = new PlanComptableReference();
        reference.setClasse((short) classe);
        CompteComptable c = new CompteComptable();
        c.setId(UUID.randomUUID());
        c.setEntreprise(entreprise);
        c.setNumero(numero);
        c.setIntitule("Compte " + numero);
        c.setReference(reference);
        return c;
    }

    private void checklistPrete() {
        when(clotureReadinessService.construireChecklist(eq(entrepriseId), any(ExerciceComptable.class)))
                .thenReturn(new ClotureReadinessDto("READY", List.of(), List.of(), List.of(), List.of()));
    }

    @Test
    void refuse_de_cloturer_un_exercice_deja_cloture() {
        exercice.setStatut(StatutExercice.CLOTURE);

        assertThatThrownBy(() -> clotureExerciceService.cloturer(entrepriseId, exerciceId, principal))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("deja cloture");
    }

    @Test
    void refuse_de_cloturer_si_la_checklist_contient_un_blocage() {
        when(clotureReadinessService.construireChecklist(eq(entrepriseId), any(ExerciceComptable.class))).thenReturn(
                new ClotureReadinessDto("NOT_READY", List.of("1 ecriture(s) en brouillon doivent etre validees avant cloture."),
                        List.of(), List.of(), List.of()));

        assertThatThrownBy(() -> clotureExerciceService.cloturer(entrepriseId, exerciceId, principal))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("brouillon");
    }

    @Test
    void genere_lecriture_de_determination_du_resultat_benefice() {
        checklistPrete();
        when(compteComptableRepository.findByEntrepriseId(entrepriseId)).thenReturn(List.of(achats, ventes));
        when(etatsFinanciersService.genererCompteResultatInterne(exercice)).thenReturn(compteResultat(new BigDecimal("10000")));
        when(ligneEcritureRepository.mouvementNetPeriode(achats.getId(), exercice.getDateDebut(), exercice.getDateFin()))
                .thenReturn(new BigDecimal("-30000"));
        when(ligneEcritureRepository.mouvementNetPeriode(ventes.getId(), exercice.getDateDebut(), exercice.getDateFin()))
                .thenReturn(new BigDecimal("40000"));
        when(clotureRule.construire(eq(entreprise), any(), eq(new BigDecimal("10000"))))
                .thenReturn(new ResultatRegle(com.example.comptable.comptabilite.domain.TypeJournal.OPERATIONS_DIVERSES,
                        List.of(), List.of(), List.of()));
        when(accountingOperationEngine.proposerEtValiderImmediatement(eq(entrepriseId), eq(AccountingOperationType.CLOTURE_EXERCICE),
                eq(null), eq(exercice.getDateFin()), any(), eq(null), any(), eq("EXERCICE"), eq(exerciceId), eq(principal)))
                .thenReturn(new AccountingOperationProposalDto(UUID.randomUUID(), entrepriseId, "CLOTURE_EXERCICE", "APPROVED",
                        null, null, null, exercice.getDateFin(), "Determination du resultat de l'exercice", null,
                        List.of(), BigDecimal.ZERO, BigDecimal.ZERO, true, List.of(), List.of(), null, null, null, null,
                        UUID.randomUUID(), "EXERCICE", exerciceId));
        when(exerciceComptableRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));
        when(exerciceComptableRepository.findByEntrepriseIdAndDate(entrepriseId, LocalDate.of(2027, 1, 1)))
                .thenReturn(Optional.empty());

        ExerciceComptableDto resultat = clotureExerciceService.cloturer(entrepriseId, exerciceId, principal);

        assertThat(resultat.statut()).isEqualTo("CLOTURE");
        assertThat(exercice.getStatut()).isEqualTo(StatutExercice.CLOTURE);

        ArgumentCaptor<List<AccountingOperationProposalLigne>> lignesCaptor = ArgumentCaptor.forClass(List.class);
        org.mockito.Mockito.verify(clotureRule).construire(eq(entreprise), lignesCaptor.capture(), eq(new BigDecimal("10000")));
        List<AccountingOperationProposalLigne> lignesASolder = lignesCaptor.getValue();
        assertThat(lignesASolder).hasSize(2);
        assertThat(lignesASolder).anyMatch(l -> l.getCompte() == achats && l.getCredit().compareTo(new BigDecimal("30000")) == 0);
        assertThat(lignesASolder).anyMatch(l -> l.getCompte() == ventes && l.getDebit().compareTo(new BigDecimal("40000")) == 0);

        org.mockito.Mockito.verify(accountingOperationEngine).proposerEtValiderImmediatement(eq(entrepriseId),
                eq(AccountingOperationType.CLOTURE_EXERCICE), eq(null), eq(exercice.getDateFin()), any(), eq(null), any(),
                eq("EXERCICE"), eq(exerciceId), eq(principal));

        org.mockito.Mockito.verify(exerciceComptableRepository, org.mockito.Mockito.times(2)).save(any());
    }

    @Test
    void genere_lecriture_de_determination_du_resultat_perte() {
        checklistPrete();
        when(compteComptableRepository.findByEntrepriseId(entrepriseId)).thenReturn(List.of(achats, ventes));
        when(etatsFinanciersService.genererCompteResultatInterne(exercice)).thenReturn(compteResultat(new BigDecimal("-5000")));
        when(ligneEcritureRepository.mouvementNetPeriode(achats.getId(), exercice.getDateDebut(), exercice.getDateFin()))
                .thenReturn(new BigDecimal("-30000"));
        when(ligneEcritureRepository.mouvementNetPeriode(ventes.getId(), exercice.getDateDebut(), exercice.getDateFin()))
                .thenReturn(new BigDecimal("25000"));
        when(clotureRule.construire(eq(entreprise), any(), eq(new BigDecimal("-5000"))))
                .thenReturn(new ResultatRegle(com.example.comptable.comptabilite.domain.TypeJournal.OPERATIONS_DIVERSES,
                        List.of(), List.of(), List.of()));
        when(accountingOperationEngine.proposerEtValiderImmediatement(eq(entrepriseId), eq(AccountingOperationType.CLOTURE_EXERCICE),
                eq(null), eq(exercice.getDateFin()), any(), eq(null), any(), eq("EXERCICE"), eq(exerciceId), eq(principal)))
                .thenReturn(new AccountingOperationProposalDto(UUID.randomUUID(), entrepriseId, "CLOTURE_EXERCICE", "APPROVED",
                        null, null, null, exercice.getDateFin(), "Determination du resultat de l'exercice", null,
                        List.of(), BigDecimal.ZERO, BigDecimal.ZERO, true, List.of(), List.of(), null, null, null, null,
                        UUID.randomUUID(), "EXERCICE", exerciceId));
        when(exerciceComptableRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));
        when(exerciceComptableRepository.findByEntrepriseIdAndDate(entrepriseId, LocalDate.of(2027, 1, 1)))
                .thenReturn(Optional.empty());

        clotureExerciceService.cloturer(entrepriseId, exerciceId, principal);

        org.mockito.Mockito.verify(clotureRule).construire(eq(entreprise), any(), eq(new BigDecimal("-5000")));
    }

    @Test
    void ne_genere_aucune_ecriture_si_aucun_mouvement_de_charge_ou_produit() {
        checklistPrete();
        when(compteComptableRepository.findByEntrepriseId(entrepriseId)).thenReturn(List.of());
        when(etatsFinanciersService.genererCompteResultatInterne(exercice)).thenReturn(compteResultat(BigDecimal.ZERO));
        when(exerciceComptableRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));
        when(exerciceComptableRepository.findByEntrepriseIdAndDate(entrepriseId, LocalDate.of(2027, 1, 1)))
                .thenReturn(Optional.empty());

        clotureExerciceService.cloturer(entrepriseId, exerciceId, principal);

        org.mockito.Mockito.verifyNoInteractions(accountingOperationEngine, clotureRule);
    }

    @Test
    void cree_lexercice_suivant_sil_nexiste_pas() {
        checklistPrete();
        when(compteComptableRepository.findByEntrepriseId(entrepriseId)).thenReturn(List.of());
        when(etatsFinanciersService.genererCompteResultatInterne(exercice)).thenReturn(compteResultat(BigDecimal.ZERO));
        when(exerciceComptableRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));
        when(exerciceComptableRepository.findByEntrepriseIdAndDate(entrepriseId, LocalDate.of(2027, 1, 1)))
                .thenReturn(Optional.empty());

        clotureExerciceService.cloturer(entrepriseId, exerciceId, principal);

        ArgumentCaptor<ExerciceComptable> captor = ArgumentCaptor.forClass(ExerciceComptable.class);
        org.mockito.Mockito.verify(exerciceComptableRepository, org.mockito.Mockito.times(2)).save(captor.capture());
        ExerciceComptable suivant = captor.getAllValues().stream()
                .filter(e -> e.getDateDebut().equals(LocalDate.of(2027, 1, 1)))
                .findFirst().orElseThrow();
        assertThat(suivant.getDateFin()).isEqualTo(LocalDate.of(2027, 12, 31));
    }

    @Test
    void refuse_de_cloturer_un_exercice_dune_autre_entreprise() {
        exercice.getEntreprise().setId(UUID.randomUUID());

        assertThatThrownBy(() -> clotureExerciceService.cloturer(entrepriseId, exerciceId, principal))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("introuvable");
    }

    @Test
    void refuse_de_cloturer_un_exercice_introuvable() {
        when(exerciceComptableRepository.findById(exerciceId)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> clotureExerciceService.cloturer(entrepriseId, exerciceId, principal))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("introuvable");
    }

    @Test
    void ne_recree_pas_lexercice_suivant_sil_existe_deja() {
        checklistPrete();
        when(compteComptableRepository.findByEntrepriseId(entrepriseId)).thenReturn(List.of());
        when(etatsFinanciersService.genererCompteResultatInterne(exercice)).thenReturn(compteResultat(BigDecimal.ZERO));
        when(exerciceComptableRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));
        ExerciceComptable exerciceSuivantExistant = new ExerciceComptable();
        exerciceSuivantExistant.setId(UUID.randomUUID());
        when(exerciceComptableRepository.findByEntrepriseIdAndDate(entrepriseId, LocalDate.of(2027, 1, 1)))
                .thenReturn(Optional.of(exerciceSuivantExistant));

        clotureExerciceService.cloturer(entrepriseId, exerciceId, principal);

        org.mockito.Mockito.verify(exerciceComptableRepository, org.mockito.Mockito.times(1)).save(any());
    }

    private CompteResultatDto compteResultat(BigDecimal resultatNet) {
        SectionFinanciereDto vide = new SectionFinanciereDto("x", List.of(), BigDecimal.ZERO);
        return new CompteResultatDto(exercice.getDateDebut(), exercice.getDateFin(),
                vide, vide, BigDecimal.ZERO, vide, vide, BigDecimal.ZERO, resultatNet);
    }
}
