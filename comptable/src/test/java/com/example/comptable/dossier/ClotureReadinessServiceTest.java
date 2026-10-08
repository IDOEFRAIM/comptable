package com.example.comptable.dossier;

import com.example.comptable.agent.finding.domain.Finding;
import com.example.comptable.agent.finding.domain.SeveriteFinding;
import com.example.comptable.agent.finding.domain.StatutFinding;
import com.example.comptable.agent.finding.domain.TypeFinding;
import com.example.comptable.agent.finding.repository.FindingRepository;
import com.example.comptable.comptabilite.domain.ExerciceComptable;
import com.example.comptable.comptabilite.domain.StatutEcriture;
import com.example.comptable.comptabilite.domain.StatutExercice;
import com.example.comptable.comptabilite.regularisation.repository.RegularisationRepository;
import com.example.comptable.comptabilite.repository.EcritureComptableRepository;
import com.example.comptable.comptabilite.repository.ExerciceComptableRepository;
import com.example.comptable.dossier.web.dto.ClotureReadinessDto;
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

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ClotureReadinessServiceTest {

    @Mock
    private EntrepriseAccessService entrepriseAccessService;
    @Mock
    private ExerciceComptableRepository exerciceComptableRepository;
    @Mock
    private EcritureComptableRepository ecritureComptableRepository;
    @Mock
    private FindingRepository findingRepository;
    @Mock
    private RegularisationRepository regularisationRepository;

    @InjectMocks
    private ClotureReadinessService service;

    private UUID entrepriseId;
    private Entreprise entreprise;
    private UtilisateurPrincipal principal;
    private ExerciceComptable exerciceOuvert;

    @BeforeEach
    void setUp() {
        entrepriseId = UUID.randomUUID();
        entreprise = new Entreprise();
        entreprise.setId(entrepriseId);

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

        exerciceOuvert = new ExerciceComptable();
        exerciceOuvert.setId(UUID.randomUUID());
        exerciceOuvert.setEntreprise(entreprise);
        exerciceOuvert.setStatut(StatutExercice.OUVERT);

        when(entrepriseAccessService.verifierAcces(entrepriseId, principal)).thenReturn(entreprise);
        lenient().when(exerciceComptableRepository.findByEntrepriseIdAndDate(eq(entrepriseId), any(LocalDate.class)))
                .thenReturn(Optional.of(exerciceOuvert));
    }

    private Finding finding(SeveriteFinding severite) {
        Finding f = new Finding();
        f.setId(UUID.randomUUID());
        f.setEntreprise(entreprise);
        f.setType(TypeFinding.DOCUMENT_REVIEW);
        f.setSeverity(severite);
        f.setControlCode("DOCUMENT_AT_RISK");
        f.setCategory(com.example.comptable.controle.ControlCategory.DOCUMENT);
        f.setControlSeverity(severite == SeveriteFinding.CRITICAL ? com.example.comptable.controle.ControlSeverity.BLOCKING : severite == SeveriteFinding.IMPORTANT ? com.example.comptable.controle.ControlSeverity.WARNING : com.example.comptable.controle.ControlSeverity.INFO);
        f.setLastDetectedAt(java.time.OffsetDateTime.now());
        f.setSourceType("DOCUMENT");
        f.setSourceId(UUID.randomUUID().toString());
        f.setTitle("Document a risque");
        f.setStatus(StatutFinding.OPEN);
        f.setCreatedAt(OffsetDateTime.now());
        return f;
    }

    @Test
    void dossier_propre_est_pret() {
        when(ecritureComptableRepository.countByExercice_IdAndStatut(exerciceOuvert.getId(), StatutEcriture.BROUILLON))
                .thenReturn(0L);
        when(findingRepository.findByEntrepriseIdAndStatusOrderBySeverityAscCreatedAtDesc(entrepriseId, StatutFinding.OPEN))
                .thenReturn(List.of());
        when(regularisationRepository.findByEntrepriseIdAndExerciceId(entrepriseId, exerciceOuvert.getId()))
                .thenReturn(List.of());

        ClotureReadinessDto resultat = service.evaluer(entrepriseId, principal);

        assertThat(resultat.statut()).isEqualTo("READY");
        assertThat(resultat.blockingItems()).isEmpty();
        assertThat(resultat.warnings()).isEmpty();
        assertThat(resultat.checklist()).isNotEmpty();
        assertThat(resultat.checklist()).anyMatch(item -> item.code().equals("exercices") && item.statut().equals("OK"));
        assertThat(resultat.checklist()).anyMatch(item -> item.code().equals("banque") && item.statut().equals("NOT_APPLICABLE"));
    }

    @Test
    void regularisation_non_finalisee_donne_ready_with_review() {
        when(ecritureComptableRepository.countByExercice_IdAndStatut(exerciceOuvert.getId(), StatutEcriture.BROUILLON))
                .thenReturn(0L);
        when(findingRepository.findByEntrepriseIdAndStatusOrderBySeverityAscCreatedAtDesc(entrepriseId, StatutFinding.OPEN))
                .thenReturn(List.of());
        com.example.comptable.comptabilite.regularisation.domain.Regularisation regularisation =
                new com.example.comptable.comptabilite.regularisation.domain.Regularisation();
        regularisation.setStatut(com.example.comptable.comptabilite.regularisation.domain.StatutRegularisation.BROUILLON);
        when(regularisationRepository.findByEntrepriseIdAndExerciceId(entrepriseId, exerciceOuvert.getId()))
                .thenReturn(List.of(regularisation));

        ClotureReadinessDto resultat = service.evaluer(entrepriseId, principal);

        assertThat(resultat.statut()).isEqualTo("READY_WITH_WARNINGS");
        assertThat(resultat.checklist()).anyMatch(item -> item.code().equals("regularisations") && item.statut().equals("WARNING"));
    }

    @Test
    void ecritures_brouillon_bloquent_la_cloture() {
        when(ecritureComptableRepository.countByExercice_IdAndStatut(exerciceOuvert.getId(), StatutEcriture.BROUILLON))
                .thenReturn(2L);
        when(findingRepository.findByEntrepriseIdAndStatusOrderBySeverityAscCreatedAtDesc(entrepriseId, StatutFinding.OPEN))
                .thenReturn(List.of());

        ClotureReadinessDto resultat = service.evaluer(entrepriseId, principal);

        assertThat(resultat.statut()).isEqualTo("NOT_READY");
        assertThat(resultat.blockingItems()).hasSize(1);
    }

    @Test
    void finding_important_seul_donne_ready_with_review() {
        when(ecritureComptableRepository.countByExercice_IdAndStatut(exerciceOuvert.getId(), StatutEcriture.BROUILLON))
                .thenReturn(0L);
        when(findingRepository.findByEntrepriseIdAndStatusOrderBySeverityAscCreatedAtDesc(entrepriseId, StatutFinding.OPEN))
                .thenReturn(List.of(finding(SeveriteFinding.IMPORTANT)));

        ClotureReadinessDto resultat = service.evaluer(entrepriseId, principal);

        assertThat(resultat.statut()).isEqualTo("READY_WITH_WARNINGS");
        assertThat(resultat.blockingItems()).isEmpty();
        assertThat(resultat.warnings()).hasSize(1);
    }

    @Test
    void finding_critique_bloque_la_cloture() {
        when(ecritureComptableRepository.countByExercice_IdAndStatut(exerciceOuvert.getId(), StatutEcriture.BROUILLON))
                .thenReturn(0L);
        when(findingRepository.findByEntrepriseIdAndStatusOrderBySeverityAscCreatedAtDesc(entrepriseId, StatutFinding.OPEN))
                .thenReturn(List.of(finding(SeveriteFinding.CRITICAL)));

        ClotureReadinessDto resultat = service.evaluer(entrepriseId, principal);

        assertThat(resultat.statut()).isEqualTo("NOT_READY");
        assertThat(resultat.blockingItems()).hasSize(1);
    }

    @Test
    void aucun_exercice_ne_couvre_la_date_du_jour_bloque_la_cloture() {
        when(exerciceComptableRepository.findByEntrepriseIdAndDate(eq(entrepriseId), any(LocalDate.class)))
                .thenReturn(Optional.empty());
        when(findingRepository.findByEntrepriseIdAndStatusOrderBySeverityAscCreatedAtDesc(entrepriseId, StatutFinding.OPEN))
                .thenReturn(List.of());

        ClotureReadinessDto resultat = service.evaluer(entrepriseId, principal);

        assertThat(resultat.statut()).isEqualTo("NOT_READY");
    }
}
