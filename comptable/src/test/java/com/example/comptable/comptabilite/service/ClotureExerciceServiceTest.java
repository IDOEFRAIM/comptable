package com.example.comptable.comptabilite.service;

import com.example.comptable.comptabilite.domain.CompteComptable;
import com.example.comptable.comptabilite.domain.EcritureComptable;
import com.example.comptable.comptabilite.domain.ExerciceComptable;
import com.example.comptable.comptabilite.domain.Journal;
import com.example.comptable.comptabilite.domain.PlanComptableReference;
import com.example.comptable.comptabilite.domain.StatutEcriture;
import com.example.comptable.comptabilite.domain.StatutExercice;
import com.example.comptable.comptabilite.repository.CompteComptableRepository;
import com.example.comptable.comptabilite.repository.EcritureComptableRepository;
import com.example.comptable.comptabilite.repository.ExerciceComptableRepository;
import com.example.comptable.comptabilite.repository.JournalRepository;
import com.example.comptable.comptabilite.repository.LigneEcritureRepository;
import com.example.comptable.comptabilite.web.dto.CompteResultatDto;
import com.example.comptable.comptabilite.web.dto.ExerciceComptableDto;
import com.example.comptable.comptabilite.web.dto.SectionFinanciereDto;
import com.example.comptable.entreprise.domain.Entreprise;
import com.example.comptable.entreprise.service.EntrepriseAccessService;
import com.example.comptable.identite.domain.Role;
import com.example.comptable.identite.domain.Utilisateur;
import com.example.comptable.identite.repository.UtilisateurRepository;
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
import static org.mockito.Mockito.when;

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
    private EcritureComptableRepository ecritureComptableRepository;
    @Mock
    private JournalRepository journalRepository;
    @Mock
    private EtatsFinanciersService etatsFinanciersService;
    @Mock
    private UtilisateurRepository utilisateurRepository;

    @InjectMocks
    private ClotureExerciceService clotureExerciceService;

    private UUID entrepriseId;
    private UUID exerciceId;
    private Entreprise entreprise;
    private ExerciceComptable exercice;
    private UtilisateurPrincipal principal;
    private Utilisateur utilisateur;
    private Journal journalOd;
    private CompteComptable achats;
    private CompteComptable ventes;
    private CompteComptable compte120;
    private CompteComptable compte129;

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
        utilisateur = new Utilisateur();
        utilisateur.setId(UUID.randomUUID());
        utilisateur.setEmail("test@cabinet.ci");
        utilisateur.setMotDePasse("hash");
        utilisateur.setNom("Nom");
        utilisateur.setPrenom("Prenom");
        utilisateur.setRoles(Set.of(role));
        principal = new UtilisateurPrincipal(utilisateur);

        journalOd = new Journal();
        journalOd.setId(UUID.randomUUID());
        journalOd.setEntreprise(entreprise);
        journalOd.setCode("OD");

        achats = compte("601", 6);
        ventes = compte("701", 7);
        compte120 = compte("120", 1);
        compte129 = compte("129", 1);

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

    @Test
    void refuse_de_cloturer_un_exercice_deja_cloture() {
        exercice.setStatut(StatutExercice.CLOTURE);

        assertThatThrownBy(() -> clotureExerciceService.cloturer(entrepriseId, exerciceId, principal))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("deja cloture");
    }

    @Test
    void refuse_de_cloturer_si_des_brouillons_subsistent() {
        when(ecritureComptableRepository.countByExercice_IdAndStatut(exerciceId, StatutEcriture.BROUILLON))
                .thenReturn(1L);

        assertThatThrownBy(() -> clotureExerciceService.cloturer(entrepriseId, exerciceId, principal))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("brouillon");
    }

    @Test
    void genere_lecriture_de_determination_du_resultat_benefice() {
        when(ecritureComptableRepository.countByExercice_IdAndStatut(exerciceId, StatutEcriture.BROUILLON))
                .thenReturn(0L);
        when(utilisateurRepository.getReferenceById(principal.getId())).thenReturn(utilisateur);
        when(compteComptableRepository.findByEntrepriseId(entrepriseId))
                .thenReturn(List.of(achats, ventes, compte120, compte129));
        when(etatsFinanciersService.genererCompteResultatInterne(exercice)).thenReturn(compteResultat(new BigDecimal("10000")));
        when(ligneEcritureRepository.mouvementNetPeriode(achats.getId(), exercice.getDateDebut(), exercice.getDateFin()))
                .thenReturn(new BigDecimal("-30000"));
        when(ligneEcritureRepository.mouvementNetPeriode(ventes.getId(), exercice.getDateDebut(), exercice.getDateFin()))
                .thenReturn(new BigDecimal("40000"));
        when(journalRepository.findByEntrepriseIdAndCode(entrepriseId, "OD")).thenReturn(Optional.of(journalOd));
        when(compteComptableRepository.findByEntrepriseIdAndNumero(entrepriseId, "120")).thenReturn(Optional.of(compte120));
        when(ecritureComptableRepository.countByJournalId(journalOd.getId())).thenReturn(0L);
        when(ecritureComptableRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));
        when(exerciceComptableRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));
        when(exerciceComptableRepository.findByEntrepriseIdAndDate(entrepriseId, LocalDate.of(2027, 1, 1)))
                .thenReturn(Optional.empty());

        ExerciceComptableDto resultat = clotureExerciceService.cloturer(entrepriseId, exerciceId, principal);

        assertThat(resultat.statut()).isEqualTo("CLOTURE");
        assertThat(exercice.getStatut()).isEqualTo(StatutExercice.CLOTURE);

        ArgumentCaptor<EcritureComptable> captor = ArgumentCaptor.forClass(EcritureComptable.class);
        org.mockito.Mockito.verify(ecritureComptableRepository).save(captor.capture());
        EcritureComptable ecriture = captor.getValue();
        assertThat(ecriture.estEquilibree()).isTrue();
        assertThat(ecriture.totalDebit()).isEqualByComparingTo("40000");
        assertThat(ecriture.getLignes()).hasSize(3);
        assertThat(ecriture.getLignes()).anyMatch(l -> l.getCompte() == compte120 && l.getCredit().compareTo(new BigDecimal("10000")) == 0);

        ArgumentCaptor<ExerciceComptable> exerciceCaptor = ArgumentCaptor.forClass(ExerciceComptable.class);
        org.mockito.Mockito.verify(exerciceComptableRepository, org.mockito.Mockito.times(2)).save(exerciceCaptor.capture());
        assertThat(exerciceCaptor.getAllValues()).anyMatch(e -> e.getStatut() == StatutExercice.CLOTURE);
    }

    @Test
    void genere_lecriture_de_determination_du_resultat_perte() {
        when(ecritureComptableRepository.countByExercice_IdAndStatut(exerciceId, StatutEcriture.BROUILLON))
                .thenReturn(0L);
        when(utilisateurRepository.getReferenceById(principal.getId())).thenReturn(utilisateur);
        when(compteComptableRepository.findByEntrepriseId(entrepriseId))
                .thenReturn(List.of(achats, ventes, compte120, compte129));
        when(etatsFinanciersService.genererCompteResultatInterne(exercice)).thenReturn(compteResultat(new BigDecimal("-5000")));
        when(ligneEcritureRepository.mouvementNetPeriode(achats.getId(), exercice.getDateDebut(), exercice.getDateFin()))
                .thenReturn(new BigDecimal("-30000"));
        when(ligneEcritureRepository.mouvementNetPeriode(ventes.getId(), exercice.getDateDebut(), exercice.getDateFin()))
                .thenReturn(new BigDecimal("25000"));
        when(journalRepository.findByEntrepriseIdAndCode(entrepriseId, "OD")).thenReturn(Optional.of(journalOd));
        when(compteComptableRepository.findByEntrepriseIdAndNumero(entrepriseId, "129")).thenReturn(Optional.of(compte129));
        when(ecritureComptableRepository.countByJournalId(journalOd.getId())).thenReturn(0L);
        when(ecritureComptableRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));
        when(exerciceComptableRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));
        when(exerciceComptableRepository.findByEntrepriseIdAndDate(entrepriseId, LocalDate.of(2027, 1, 1)))
                .thenReturn(Optional.empty());

        clotureExerciceService.cloturer(entrepriseId, exerciceId, principal);

        ArgumentCaptor<EcritureComptable> captor = ArgumentCaptor.forClass(EcritureComptable.class);
        org.mockito.Mockito.verify(ecritureComptableRepository).save(captor.capture());
        EcritureComptable ecriture = captor.getValue();
        assertThat(ecriture.estEquilibree()).isTrue();
        assertThat(ecriture.getLignes()).anyMatch(l -> l.getCompte() == compte129 && l.getDebit().compareTo(new BigDecimal("5000")) == 0);
    }

    @Test
    void cree_lexercice_suivant_sil_nexiste_pas() {
        when(ecritureComptableRepository.countByExercice_IdAndStatut(exerciceId, StatutEcriture.BROUILLON))
                .thenReturn(0L);
        when(utilisateurRepository.getReferenceById(principal.getId())).thenReturn(utilisateur);
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
    void echoue_si_le_compte_de_resultat_120_est_absent_du_plan() {
        when(ecritureComptableRepository.countByExercice_IdAndStatut(exerciceId, StatutEcriture.BROUILLON))
                .thenReturn(0L);
        when(utilisateurRepository.getReferenceById(principal.getId())).thenReturn(utilisateur);
        when(compteComptableRepository.findByEntrepriseId(entrepriseId))
                .thenReturn(List.of(achats, ventes));
        when(etatsFinanciersService.genererCompteResultatInterne(exercice)).thenReturn(compteResultat(new BigDecimal("10000")));
        when(ligneEcritureRepository.mouvementNetPeriode(achats.getId(), exercice.getDateDebut(), exercice.getDateFin()))
                .thenReturn(new BigDecimal("-30000"));
        when(ligneEcritureRepository.mouvementNetPeriode(ventes.getId(), exercice.getDateDebut(), exercice.getDateFin()))
                .thenReturn(new BigDecimal("40000"));
        when(journalRepository.findByEntrepriseIdAndCode(entrepriseId, "OD")).thenReturn(Optional.of(journalOd));
        when(ecritureComptableRepository.countByJournalId(journalOd.getId())).thenReturn(0L);
        when(compteComptableRepository.findByEntrepriseIdAndNumero(entrepriseId, "120")).thenReturn(Optional.empty());

        assertThatThrownBy(() -> clotureExerciceService.cloturer(entrepriseId, exerciceId, principal))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("120");
    }

    @Test
    void echoue_si_le_journal_od_est_absent() {
        when(ecritureComptableRepository.countByExercice_IdAndStatut(exerciceId, StatutEcriture.BROUILLON))
                .thenReturn(0L);
        when(utilisateurRepository.getReferenceById(principal.getId())).thenReturn(utilisateur);
        when(compteComptableRepository.findByEntrepriseId(entrepriseId))
                .thenReturn(List.of(achats, ventes));
        when(etatsFinanciersService.genererCompteResultatInterne(exercice)).thenReturn(compteResultat(new BigDecimal("10000")));
        when(ligneEcritureRepository.mouvementNetPeriode(achats.getId(), exercice.getDateDebut(), exercice.getDateFin()))
                .thenReturn(new BigDecimal("-30000"));
        when(ligneEcritureRepository.mouvementNetPeriode(ventes.getId(), exercice.getDateDebut(), exercice.getDateFin()))
                .thenReturn(new BigDecimal("40000"));
        when(journalRepository.findByEntrepriseIdAndCode(entrepriseId, "OD")).thenReturn(Optional.empty());

        assertThatThrownBy(() -> clotureExerciceService.cloturer(entrepriseId, exerciceId, principal))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("OD");
    }

    @Test
    void ne_recree_pas_lexercice_suivant_sil_existe_deja() {
        when(ecritureComptableRepository.countByExercice_IdAndStatut(exerciceId, StatutEcriture.BROUILLON))
                .thenReturn(0L);
        when(utilisateurRepository.getReferenceById(principal.getId())).thenReturn(utilisateur);
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
