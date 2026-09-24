package com.example.comptable.comptabilite.regularisation.service;

import com.example.comptable.comptabilite.domain.CompteComptable;
import com.example.comptable.comptabilite.domain.ExerciceComptable;
import com.example.comptable.comptabilite.operation.common.AccountingOperationEngine;
import com.example.comptable.comptabilite.operation.common.repository.AccountingOperationProposalRepository;
import com.example.comptable.comptabilite.operation.common.web.dto.AccountingOperationProposalDto;
import com.example.comptable.comptabilite.regularisation.domain.Regularisation;
import com.example.comptable.comptabilite.regularisation.domain.RegularisationType;
import com.example.comptable.comptabilite.regularisation.domain.StatutRegularisation;
import com.example.comptable.comptabilite.regularisation.repository.RegularisationRepository;
import com.example.comptable.comptabilite.regularisation.web.dto.CreerRegularisationRequest;
import com.example.comptable.comptabilite.regularisation.web.dto.RegularisationDto;
import com.example.comptable.comptabilite.repository.ExerciceComptableRepository;
import com.example.comptable.comptabilite.service.CompteComptableValidationService;
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
class RegularisationServiceTest {

    @Mock
    private EntrepriseAccessService entrepriseAccessService;
    @Mock
    private RegularisationRepository regularisationRepository;
    @Mock
    private ExerciceComptableRepository exerciceComptableRepository;
    @Mock
    private CompteComptableValidationService compteComptableValidationService;
    @Mock
    private UtilisateurRepository utilisateurRepository;
    @Mock
    private AccountingOperationEngine accountingOperationEngine;
    @Mock
    private AccountingOperationProposalRepository accountingOperationProposalRepository;

    @InjectMocks
    private RegularisationService regularisationService;

    private UUID entrepriseId;
    private Entreprise entreprise;
    private UtilisateurPrincipal principal;
    private ExerciceComptable exercice;
    private CompteComptable compteOrigine;
    private CompteComptable compteContrepartie;

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

        exercice = new ExerciceComptable();
        exercice.setId(UUID.randomUUID());
        exercice.setEntreprise(entreprise);
        exercice.setDateDebut(LocalDate.of(2026, 1, 1));
        exercice.setDateFin(LocalDate.of(2026, 12, 31));

        compteOrigine = new CompteComptable();
        compteOrigine.setId(UUID.randomUUID());
        compteOrigine.setEntreprise(entreprise);
        compteOrigine.setNumero("616");

        compteContrepartie = new CompteComptable();
        compteContrepartie.setId(UUID.randomUUID());
        compteContrepartie.setEntreprise(entreprise);
        compteContrepartie.setNumero("476");

        when(entrepriseAccessService.verifierAcces(entrepriseId, principal)).thenReturn(entreprise);
    }

    private CreerRegularisationRequest requeteCca(LocalDate debut, LocalDate fin, BigDecimal montant) {
        return new CreerRegularisationRequest(exercice.getId(), RegularisationType.CHARGE_CONSTATEE_AVANCE,
                LocalDate.of(2026, 11, 1), debut, fin, montant, "Assurance annuelle",
                compteOrigine.getId(), compteContrepartie.getId(), null);
    }

    @Test
    void calcule_la_repartition_temporelle_au_prorata_des_jours() {
        when(exerciceComptableRepository.findById(exercice.getId())).thenReturn(Optional.of(exercice));
        when(compteComptableValidationService.chargerCompteUtilisable(entrepriseId, compteOrigine.getId(), true)).thenReturn(compteOrigine);
        when(compteComptableValidationService.chargerCompteUtilisable(entrepriseId, compteContrepartie.getId(), true)).thenReturn(compteContrepartie);
        when(utilisateurRepository.getReferenceById(principal.getId())).thenReturn(new Utilisateur());
        when(regularisationRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        // Contrat du 01/11/2026 au 30/04/2027 (181 jours), exercice se termine le 31/12/2026 :
        // 61 jours (01/11 au 31/12 inclus) appartiennent a l'exercice courant.
        RegularisationDto resultat = regularisationService.creer(entrepriseId,
                requeteCca(LocalDate.of(2026, 11, 1), LocalDate.of(2027, 4, 30), new BigDecimal("1810")), principal);

        assertThat(resultat.montantExercice()).isEqualByComparingTo(new BigDecimal("610.00"));
        assertThat(resultat.montantFutur()).isEqualByComparingTo(new BigDecimal("1200.00"));
    }

    @Test
    void sans_dates_le_montant_entier_reste_attache_a_lexercice() {
        when(exerciceComptableRepository.findById(exercice.getId())).thenReturn(Optional.of(exercice));
        when(compteComptableValidationService.chargerCompteUtilisable(entrepriseId, compteOrigine.getId(), true)).thenReturn(compteOrigine);
        when(compteComptableValidationService.chargerCompteUtilisable(entrepriseId, compteContrepartie.getId(), true)).thenReturn(compteContrepartie);
        when(utilisateurRepository.getReferenceById(principal.getId())).thenReturn(new Utilisateur());
        when(regularisationRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        CreerRegularisationRequest requete = new CreerRegularisationRequest(exercice.getId(), RegularisationType.FACTURE_NON_PARVENUE,
                LocalDate.of(2026, 12, 20), null, null, new BigDecimal("5000"), "Facture non parvenue",
                compteOrigine.getId(), compteContrepartie.getId(), null);

        RegularisationDto resultat = regularisationService.creer(entrepriseId, requete, principal);

        assertThat(resultat.montantExercice()).isEqualByComparingTo("5000");
        assertThat(resultat.montantFutur()).isEqualByComparingTo("0");
    }

    @Test
    void proposer_delegue_au_moteur_et_passe_en_statut_proposee() {
        Regularisation regularisation = regularisationBrouillon();
        when(regularisationRepository.findById(regularisation.getId()))
                .thenReturn(Optional.of(regularisation));
        UUID propositionId = UUID.randomUUID();
        when(accountingOperationEngine.proposerRegularisation(entrepriseId, regularisation, principal))
                .thenReturn(propositionDto(propositionId, "PROPOSED"));
        when(regularisationRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        RegularisationDto resultat = regularisationService.proposer(entrepriseId, regularisation.getId(), principal);

        assertThat(resultat.statut()).isEqualTo("PROPOSEE");
    }

    @Test
    void reprise_refusee_si_non_parametree() {
        Regularisation regularisation = regularisationBrouillon();
        regularisation.setStatut(StatutRegularisation.VALIDEE);
        regularisation.setRepriseAutomatique(false);
        when(regularisationRepository.findById(regularisation.getId())).thenReturn(Optional.of(regularisation));

        assertThatThrownBy(() -> regularisationService.proposerReprise(entrepriseId, regularisation.getId(), principal))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("pas parametree");
    }

    @Test
    void isolation_multi_tenant_une_regularisation_dune_autre_entreprise_est_introuvable() {
        Regularisation regularisation = regularisationBrouillon();
        Entreprise autreEntreprise = new Entreprise();
        autreEntreprise.setId(UUID.randomUUID());
        regularisation.setEntreprise(autreEntreprise);
        when(regularisationRepository.findById(regularisation.getId())).thenReturn(Optional.of(regularisation));

        assertThatThrownBy(() -> regularisationService.obtenir(entrepriseId, regularisation.getId(), principal))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("introuvable");
    }

    private Regularisation regularisationBrouillon() {
        Regularisation r = new Regularisation();
        r.setId(UUID.randomUUID());
        r.setEntreprise(entreprise);
        r.setExercice(exercice);
        r.setType(RegularisationType.FACTURE_NON_PARVENUE);
        r.setDateOrigine(LocalDate.of(2026, 12, 20));
        r.setMontant(new BigDecimal("5000"));
        r.setMontantExercice(new BigDecimal("5000"));
        r.setMontantFutur(BigDecimal.ZERO);
        r.setLibelle("FNP transport");
        r.setCompteOrigine(compteOrigine);
        r.setCompteContrepartie(compteContrepartie);
        r.setStatut(StatutRegularisation.BROUILLON);
        return r;
    }

    private AccountingOperationProposalDto propositionDto(UUID id, String statut) {
        return new AccountingOperationProposalDto(id, entrepriseId, "REGULARISATION", statut, null, null, null,
                LocalDate.of(2026, 12, 31), "libelle", null, java.util.List.of(), BigDecimal.ZERO, BigDecimal.ZERO,
                true, java.util.List.of(), java.util.List.of(), null, null, null, null, null, "REGULARISATION", id);
    }
}
