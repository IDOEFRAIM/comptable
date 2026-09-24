package com.example.comptable.comptabilite.service;

import com.example.comptable.comptabilite.domain.CompteComptable;
import com.example.comptable.comptabilite.domain.EcritureComptable;
import com.example.comptable.comptabilite.domain.ExerciceComptable;
import com.example.comptable.comptabilite.domain.Journal;
import com.example.comptable.comptabilite.domain.StatutExercice;
import com.example.comptable.comptabilite.repository.EcritureComptableRepository;
import com.example.comptable.comptabilite.repository.ExerciceComptableRepository;
import com.example.comptable.comptabilite.repository.JournalRepository;
import com.example.comptable.comptabilite.repository.TiersRepository;
import com.example.comptable.comptabilite.web.dto.CreerEcritureRequest;
import com.example.comptable.comptabilite.web.dto.EcritureComptableDto;
import com.example.comptable.comptabilite.web.dto.LigneEcritureRequest;
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
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class EcritureComptableServiceTest {

    @Mock
    private EntrepriseAccessService entrepriseAccessService;
    @Mock
    private EcritureComptableRepository ecritureComptableRepository;
    @Mock
    private DocumentRepository documentRepository;
    @Mock
    private JournalRepository journalRepository;
    @Mock
    private CompteComptableValidationService compteComptableValidationService;
    @Mock
    private ExerciceComptableRepository exerciceComptableRepository;
    @Mock
    private TiersRepository tiersRepository;
    @Mock
    private UtilisateurRepository utilisateurRepository;

    @InjectMocks
    private EcritureComptableService ecritureComptableService;

    private UUID entrepriseId;
    private Entreprise entreprise;
    private UtilisateurPrincipal principal;
    private Utilisateur utilisateur;
    private Journal journal;
    private ExerciceComptable exercice;
    private CompteComptable compteBanque;
    private CompteComptable compteVentes;

    @BeforeEach
    void setUp() {
        entrepriseId = UUID.randomUUID();
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

        journal = new Journal();
        journal.setId(UUID.randomUUID());
        journal.setEntreprise(entreprise);
        journal.setCode("OD");

        exercice = new ExerciceComptable();
        exercice.setId(UUID.randomUUID());
        exercice.setEntreprise(entreprise);
        exercice.setDateDebut(LocalDate.of(2026, 1, 1));
        exercice.setDateFin(LocalDate.of(2026, 12, 31));
        exercice.setStatut(StatutExercice.OUVERT);

        compteBanque = new CompteComptable();
        compteBanque.setId(UUID.randomUUID());
        compteBanque.setEntreprise(entreprise);
        compteBanque.setNumero("521");
        compteBanque.setIntitule("Banque");

        compteVentes = new CompteComptable();
        compteVentes.setId(UUID.randomUUID());
        compteVentes.setEntreprise(entreprise);
        compteVentes.setNumero("701");
        compteVentes.setIntitule("Ventes");

        when(entrepriseAccessService.verifierAcces(entrepriseId, principal)).thenReturn(entreprise);
    }

    private CreerEcritureRequest requeteEquilibree() {
        return new CreerEcritureRequest(
                journal.getId(),
                LocalDate.of(2026, 3, 15),
                "Vente comptant",
                null,
                List.of(
                        new LigneEcritureRequest(compteBanque.getId(), "Encaissement", new BigDecimal("50000"), BigDecimal.ZERO, null),
                        new LigneEcritureRequest(compteVentes.getId(), "Vente", BigDecimal.ZERO, new BigDecimal("50000"), null)
                )
        );
    }

    @Test
    void cree_une_ecriture_manuelle_equilibree() {
        when(journalRepository.findById(journal.getId())).thenReturn(Optional.of(journal));
        when(exerciceComptableRepository.findByEntrepriseIdAndDate(entrepriseId, LocalDate.of(2026, 3, 15)))
                .thenReturn(Optional.of(exercice));
        when(compteComptableValidationService.chargerCompteUtilisable(entrepriseId, compteBanque.getId(), true)).thenReturn(compteBanque);
        when(compteComptableValidationService.chargerCompteUtilisable(entrepriseId, compteVentes.getId(), true)).thenReturn(compteVentes);
        when(ecritureComptableRepository.countByJournalId(journal.getId())).thenReturn(3L);
        when(utilisateurRepository.getReferenceById(principal.getId())).thenReturn(utilisateur);
        when(ecritureComptableRepository.save(any())).thenAnswer(inv -> {
            EcritureComptable e = inv.getArgument(0);
            e.setId(UUID.randomUUID());
            return e;
        });

        EcritureComptableDto resultat = ecritureComptableService.creerManuelle(entrepriseId, requeteEquilibree(), principal);

        assertThat(resultat.statut()).isEqualTo("BROUILLON");
        assertThat(resultat.numeroPiece()).isEqualTo("OD-2026-0004");
        assertThat(resultat.totalDebit()).isEqualByComparingTo("50000");
        assertThat(resultat.totalCredit()).isEqualByComparingTo("50000");
    }

    @Test
    void utilise_le_numero_de_piece_fourni_sil_est_present() {
        when(journalRepository.findById(journal.getId())).thenReturn(Optional.of(journal));
        when(exerciceComptableRepository.findByEntrepriseIdAndDate(entrepriseId, LocalDate.of(2026, 3, 15)))
                .thenReturn(Optional.of(exercice));
        when(compteComptableValidationService.chargerCompteUtilisable(entrepriseId, compteBanque.getId(), true)).thenReturn(compteBanque);
        when(compteComptableValidationService.chargerCompteUtilisable(entrepriseId, compteVentes.getId(), true)).thenReturn(compteVentes);
        when(utilisateurRepository.getReferenceById(principal.getId())).thenReturn(utilisateur);
        when(ecritureComptableRepository.save(any())).thenAnswer(inv -> {
            EcritureComptable e = inv.getArgument(0);
            e.setId(UUID.randomUUID());
            return e;
        });

        CreerEcritureRequest request = new CreerEcritureRequest(
                journal.getId(), LocalDate.of(2026, 3, 15), "Vente comptant", "OD-PERSO-01",
                List.of(
                        new LigneEcritureRequest(compteBanque.getId(), null, new BigDecimal("50000"), BigDecimal.ZERO, null),
                        new LigneEcritureRequest(compteVentes.getId(), null, BigDecimal.ZERO, new BigDecimal("50000"), null)
                ));

        EcritureComptableDto resultat = ecritureComptableService.creerManuelle(entrepriseId, request, principal);

        assertThat(resultat.numeroPiece()).isEqualTo("OD-PERSO-01");
    }

    @Test
    void refuse_une_ecriture_desequilibree() {
        when(journalRepository.findById(journal.getId())).thenReturn(Optional.of(journal));
        when(exerciceComptableRepository.findByEntrepriseIdAndDate(entrepriseId, LocalDate.of(2026, 3, 15)))
                .thenReturn(Optional.of(exercice));
        when(compteComptableValidationService.chargerCompteUtilisable(entrepriseId, compteBanque.getId(), true)).thenReturn(compteBanque);
        when(compteComptableValidationService.chargerCompteUtilisable(entrepriseId, compteVentes.getId(), true)).thenReturn(compteVentes);

        CreerEcritureRequest request = new CreerEcritureRequest(
                journal.getId(), LocalDate.of(2026, 3, 15), "Deséquilibrée", null,
                List.of(
                        new LigneEcritureRequest(compteBanque.getId(), null, new BigDecimal("50000"), BigDecimal.ZERO, null),
                        new LigneEcritureRequest(compteVentes.getId(), null, BigDecimal.ZERO, new BigDecimal("40000"), null)
                ));

        assertThatThrownBy(() -> ecritureComptableService.creerManuelle(entrepriseId, request, principal))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("desequilibree");
    }

    @Test
    void refuse_une_ligne_avec_debit_et_credit_simultanes() {
        when(journalRepository.findById(journal.getId())).thenReturn(Optional.of(journal));
        when(exerciceComptableRepository.findByEntrepriseIdAndDate(entrepriseId, LocalDate.of(2026, 3, 15)))
                .thenReturn(Optional.of(exercice));

        CreerEcritureRequest request = new CreerEcritureRequest(
                journal.getId(), LocalDate.of(2026, 3, 15), "Invalide", null,
                List.of(new LigneEcritureRequest(compteBanque.getId(), null, new BigDecimal("50000"), new BigDecimal("50000"), null)));

        assertThatThrownBy(() -> ecritureComptableService.creerManuelle(entrepriseId, request, principal))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("jamais les deux");
    }

    @Test
    void refuse_si_aucun_exercice_ouvert_ne_couvre_la_date() {
        when(journalRepository.findById(journal.getId())).thenReturn(Optional.of(journal));
        when(exerciceComptableRepository.findByEntrepriseIdAndDate(entrepriseId, LocalDate.of(2026, 3, 15)))
                .thenReturn(Optional.empty());

        assertThatThrownBy(() -> ecritureComptableService.creerManuelle(entrepriseId, requeteEquilibree(), principal))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("Aucun exercice");
    }
}
