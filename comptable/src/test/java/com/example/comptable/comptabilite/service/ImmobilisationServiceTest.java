package com.example.comptable.comptabilite.service;

import com.example.comptable.comptabilite.operation.common.AccountingSource;

import com.example.comptable.audit.AuditTrailService;
import com.example.comptable.comptabilite.domain.CompteComptable;
import com.example.comptable.comptabilite.domain.DotationAmortissement;
import com.example.comptable.comptabilite.domain.ExerciceComptable;
import com.example.comptable.comptabilite.domain.Immobilisation;
import com.example.comptable.comptabilite.domain.ModeAmortissement;
import com.example.comptable.comptabilite.domain.MotifSortie;
import com.example.comptable.comptabilite.domain.StatutEcriture;
import com.example.comptable.comptabilite.domain.StatutExercice;
import com.example.comptable.comptabilite.domain.StatutImmobilisation;
import com.example.comptable.comptabilite.domain.Tiers;
import com.example.comptable.comptabilite.domain.TypeImmobilisation;
import com.example.comptable.comptabilite.domain.TypeTiers;
import com.example.comptable.comptabilite.operation.common.AccountingOperationEngine;
import com.example.comptable.comptabilite.operation.common.AccountingOperationType;
import com.example.comptable.comptabilite.operation.common.ResultatRegle;
import com.example.comptable.comptabilite.operation.common.web.dto.AccountingOperationProposalDto;
import com.example.comptable.comptabilite.operation.immobilisation.ImmobilisationRule;
import com.example.comptable.comptabilite.repository.CompteComptableRepository;
import com.example.comptable.comptabilite.repository.DotationAmortissementRepository;
import com.example.comptable.comptabilite.repository.ExerciceComptableRepository;
import com.example.comptable.comptabilite.repository.ImmobilisationRepository;
import com.example.comptable.comptabilite.repository.TiersRepository;
import com.example.comptable.comptabilite.web.dto.ComptabiliserAcquisitionRequest;
import com.example.comptable.comptabilite.web.dto.CreerImmobilisationRequest;
import com.example.comptable.comptabilite.web.dto.GenererDotationsResultDto;
import com.example.comptable.comptabilite.web.dto.ImmobilisationDto;
import com.example.comptable.comptabilite.web.dto.MettreEnServiceRequest;
import com.example.comptable.comptabilite.web.dto.SortirImmobilisationRequest;
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
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Phase A : le service Immobilisation possede le DOMAINE (statuts, dates, plan) et confie ses ecritures
 * au moteur ; ces tests verifient le cycle de vie et l'orchestration. Les comptes resolus, l'idempotence,
 * l'audit et l'isolation entre entreprises sont verifies sur PostgreSQL reel (CoeurComptableUniqueTest).
 *
 * Classement des tests historiques remplaces : « genere_une_ecriture_de_dotation_pour_lannee_courante »
 * (COMPORTEMENT LEGACY : une ecriture groupee construite par le service, remplacee par une ecriture par
 * immobilisation produite par le moteur), « refuse_de_generer_sur_un_exercice_cloture » et
 * « ne_genere_rien_si_toutes_les_dotations_... » (INVARIANTS METIER, conserves ci-dessous).
 */
@ExtendWith(MockitoExtension.class)
class ImmobilisationServiceTest {

    @Mock private EntrepriseAccessService entrepriseAccessService;
    @Mock private ImmobilisationRepository immobilisationRepository;
    @Mock private DotationAmortissementRepository dotationAmortissementRepository;
    @Mock private CompteComptableRepository compteComptableRepository;
    @Mock private ExerciceComptableRepository exerciceComptableRepository;
    @Mock private TiersRepository tiersRepository;
    @Mock private AccountingOperationEngine accountingOperationEngine;
    @Mock private ImmobilisationRule immobilisationRule;
    @Mock private AuditTrailService auditTrailService;
    @Mock private com.example.comptable.comptabilite.operation.common.repository.AccountingOperationProposalRepository proposalRepository;

    @InjectMocks
    private ImmobilisationService immobilisationService;

    private UUID entrepriseId;
    private Entreprise entreprise;
    private UtilisateurPrincipal principal;
    private CompteComptable compteMateriel;
    private CompteComptable compteAmortissement;

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

        compteMateriel = compte("244");
        compteAmortissement = compte("2844");
        when(entrepriseAccessService.verifierAcces(entrepriseId, principal)).thenReturn(entreprise);
    }

    private CompteComptable compte(String numero) {
        CompteComptable c = new CompteComptable();
        c.setId(UUID.randomUUID());
        c.setEntreprise(entreprise);
        c.setNumero(numero);
        c.setIntitule("Compte " + numero);
        return c;
    }

    private Immobilisation immobilisation(StatutImmobilisation statut, LocalDate acquisition, LocalDate miseEnService) {
        Immobilisation immo = new Immobilisation();
        immo.setId(UUID.randomUUID());
        immo.setEntreprise(entreprise);
        immo.setCompte(compteMateriel);
        immo.setCompteAmortissement(compteAmortissement);
        immo.setLibelle("Vehicule utilitaire");
        immo.setType(TypeImmobilisation.CORPORELLE);
        immo.setDateAcquisition(acquisition);
        immo.setDateMiseEnService(miseEnService);
        immo.setValeurOrigine(new BigDecimal("5000000"));
        immo.setDureeAnnees(5);
        immo.setMode(ModeAmortissement.LINEAIRE);
        immo.setStatut(statut);
        return immo;
    }

    private AccountingOperationProposalDto propositionAvecEcriture(UUID ecritureId) {
        AccountingOperationProposalDto proposition = mock(AccountingOperationProposalDto.class);
        when(proposition.ecritureId()).thenReturn(ecritureId);
        return proposition;
    }

    private ExerciceComptable exerciceOuvert(int annee) {
        ExerciceComptable e = new ExerciceComptable();
        e.setId(UUID.randomUUID());
        e.setEntreprise(entreprise);
        e.setDateDebut(LocalDate.of(annee, 1, 1));
        e.setDateFin(LocalDate.of(annee, 12, 31));
        e.setStatut(StatutExercice.OUVERT);
        return e;
    }

    // ------------------------------------------------------------------ enregistrement

    @Test
    void creer_enregistre_une_immobilisation_ACQUISE_sans_plan_ni_ecriture() {
        when(compteComptableRepository.findById(compteMateriel.getId())).thenReturn(Optional.of(compteMateriel));
        when(compteComptableRepository.findById(compteAmortissement.getId())).thenReturn(Optional.of(compteAmortissement));
        when(immobilisationRepository.save(any())).thenAnswer(inv -> {
            Immobilisation immo = inv.getArgument(0);
            immo.setId(UUID.randomUUID());
            return immo;
        });

        ImmobilisationDto dto = immobilisationService.creer(entrepriseId, new CreerImmobilisationRequest(
                compteMateriel.getId(), compteAmortissement.getId(), "Camion", TypeImmobilisation.CORPORELLE,
                LocalDate.of(2027, 3, 15), new BigDecimal("5000000"), 5, ModeAmortissement.LINEAIRE), principal);

        assertThat(dto.statut()).isEqualTo("ACQUISE");
        assertThat(dto.dateMiseEnService()).isNull();
        assertThat(dto.plan()).isEmpty();
        assertThat(dto.acquisitionEcritureId()).isNull();
        verify(accountingOperationEngine, never()).proposerEtValider(any(), any(), any(), any(), anyString(), any(), any(), any(), any(), any());
        verify(auditTrailService).enregistrerActionHumaine(eq(principal.getTenantId()), eq(principal.getId()),
                eq("IMMOBILISATION_ENREGISTREE"), eq("IMMOBILISATION"), anyString(), any());
    }

    @Test
    void creer_refuse_un_compte_d_une_autre_entreprise() {
        CompteComptable autre = compte("244");
        Entreprise autreEntreprise = new Entreprise();
        autreEntreprise.setId(UUID.randomUUID());
        autre.setEntreprise(autreEntreprise);
        when(compteComptableRepository.findById(autre.getId())).thenReturn(Optional.of(autre));

        assertThatThrownBy(() -> immobilisationService.creer(entrepriseId, new CreerImmobilisationRequest(
                autre.getId(), compteAmortissement.getId(), "Camion", TypeImmobilisation.CORPORELLE,
                LocalDate.of(2027, 3, 15), new BigDecimal("5000000"), 5, ModeAmortissement.LINEAIRE), principal))
                .isInstanceOfSatisfying(ResponseStatusException.class, e -> assertThat(e.getStatusCode().value()).isEqualTo(404));
        verify(immobilisationRepository, never()).save(any());
    }

    // ------------------------------------------------------------------ acquisition

    @Test
    void comptabiliser_acquisition_confie_la_commande_au_moteur_et_memorise_l_ecriture() {
        Immobilisation immo = immobilisation(StatutImmobilisation.ACQUISE, LocalDate.of(2027, 3, 15), null);
        Tiers fournisseur = new Tiers();
        fournisseur.setId(UUID.randomUUID());
        fournisseur.setEntreprise(entreprise);
        fournisseur.setType(TypeTiers.FOURNISSEUR);
        fournisseur.setNom("Garage Central");
        UUID ecritureId = UUID.randomUUID();
        ResultatRegle resultat = new ResultatRegle(null, List.of(), List.of(), List.of());
        when(immobilisationRepository.findByIdAndEntrepriseId(immo.getId(), entrepriseId)).thenReturn(Optional.of(immo));
        when(tiersRepository.findById(fournisseur.getId())).thenReturn(Optional.of(fournisseur));
        when(immobilisationRule.construireAcquisition(eq(entreprise), eq(immo), eq(fournisseur), eq(new BigDecimal("900000")), anyString()))
                .thenReturn(resultat);
        AccountingOperationProposalDto proposition = propositionAvecEcriture(ecritureId);
        when(proposalRepository.getReferenceById(any())).thenReturn(new com.example.comptable.comptabilite.operation.common.domain.AccountingOperationProposal());
        when(accountingOperationEngine.proposerEtValider(eq(entrepriseId), eq(AccountingOperationType.IMMOBILISATION_ACQUISITION), eq(fournisseur), eq(LocalDate.of(2027, 3, 15)), anyString(), any(), eq(resultat), eq(AccountingSource.de(ImmobilisationService.SOURCE_TYPE_IMMOBILISATION, immo.getId(), "ACQUISITION")), eq(StatutEcriture.VALIDEE), eq(principal)))
                .thenReturn(proposition);

        ImmobilisationDto dto = immobilisationService.comptabiliserAcquisition(entrepriseId, immo.getId(),
                new ComptabiliserAcquisitionRequest(fournisseur.getId(), new BigDecimal("900000"), null), principal);

        assertThat(immo.getAcquisitionProposal()).isNotNull();
        verify(auditTrailService).enregistrerActionHumaine(any(), any(), eq("IMMOBILISATION_ACQUISE"), eq("IMMOBILISATION"), anyString(), any());
    }

    @Test
    void comptabiliser_acquisition_deux_fois_est_refuse_409_sans_nouvelle_ecriture() {
        Immobilisation immo = immobilisation(StatutImmobilisation.ACQUISE, LocalDate.of(2027, 3, 15), null);
        immo.setAcquisitionProposal(new com.example.comptable.comptabilite.operation.common.domain.AccountingOperationProposal());
        when(immobilisationRepository.findByIdAndEntrepriseId(immo.getId(), entrepriseId)).thenReturn(Optional.of(immo));

        assertThatThrownBy(() -> immobilisationService.comptabiliserAcquisition(entrepriseId, immo.getId(),
                new ComptabiliserAcquisitionRequest(UUID.randomUUID(), null, null), principal))
                .isInstanceOfSatisfying(ResponseStatusException.class, e -> assertThat(e.getStatusCode().value()).isEqualTo(409));
        verify(accountingOperationEngine, never()).proposerEtValider(any(), any(), any(), any(), anyString(), any(), any(), any(), any(), any());
    }

    @Test
    void comptabiliser_acquisition_refuse_un_tiers_qui_n_est_pas_un_fournisseur() {
        Immobilisation immo = immobilisation(StatutImmobilisation.ACQUISE, LocalDate.of(2027, 3, 15), null);
        Tiers client = new Tiers();
        client.setId(UUID.randomUUID());
        client.setEntreprise(entreprise);
        client.setType(TypeTiers.CLIENT);
        when(immobilisationRepository.findByIdAndEntrepriseId(immo.getId(), entrepriseId)).thenReturn(Optional.of(immo));
        when(tiersRepository.findById(client.getId())).thenReturn(Optional.of(client));

        assertThatThrownBy(() -> immobilisationService.comptabiliserAcquisition(entrepriseId, immo.getId(),
                new ComptabiliserAcquisitionRequest(client.getId(), null, null), principal))
                .isInstanceOfSatisfying(ResponseStatusException.class, e -> assertThat(e.getStatusCode().value()).isEqualTo(400));
    }

    // ------------------------------------------------------------------ mise en service

    @Test
    void mettre_en_service_passe_ACQUISE_a_EN_SERVICE_avec_la_date() {
        Immobilisation immo = immobilisation(StatutImmobilisation.ACQUISE, LocalDate.of(2027, 3, 15), null);
        when(immobilisationRepository.findByIdAndEntrepriseId(immo.getId(), entrepriseId)).thenReturn(Optional.of(immo));

        ImmobilisationDto dto = immobilisationService.mettreEnService(entrepriseId, immo.getId(),
                new MettreEnServiceRequest(LocalDate.of(2027, 4, 1)), principal);

        assertThat(dto.statut()).isEqualTo("EN_SERVICE");
        assertThat(dto.dateMiseEnService()).isEqualTo(LocalDate.of(2027, 4, 1));
        assertThat(dto.plan()).isNotEmpty();
        assertThat(dto.plan().get(0).annee()).isEqualTo(2027);
    }

    @Test
    void mettre_en_service_avant_l_acquisition_est_refuse_422_et_une_seconde_fois_409() {
        Immobilisation immo = immobilisation(StatutImmobilisation.ACQUISE, LocalDate.of(2027, 3, 15), null);
        when(immobilisationRepository.findByIdAndEntrepriseId(immo.getId(), entrepriseId)).thenReturn(Optional.of(immo));

        assertThatThrownBy(() -> immobilisationService.mettreEnService(entrepriseId, immo.getId(),
                new MettreEnServiceRequest(LocalDate.of(2027, 3, 1)), principal))
                .isInstanceOfSatisfying(ResponseStatusException.class, e -> assertThat(e.getStatusCode().value()).isEqualTo(422));

        immo.setStatut(StatutImmobilisation.EN_SERVICE);
        assertThatThrownBy(() -> immobilisationService.mettreEnService(entrepriseId, immo.getId(),
                new MettreEnServiceRequest(LocalDate.of(2027, 5, 1)), principal))
                .isInstanceOfSatisfying(ResponseStatusException.class, e -> assertThat(e.getStatusCode().value()).isEqualTo(409));
    }

    // ------------------------------------------------------------------ dotations

    @Test
    void generer_dotations_produit_une_ecriture_par_immobilisation_via_le_moteur() {
        Immobilisation enService = immobilisation(StatutImmobilisation.EN_SERVICE, LocalDate.of(2027, 1, 1), LocalDate.of(2027, 1, 1));
        UUID ecritureId = UUID.randomUUID();
        ResultatRegle resultat = new ResultatRegle(null, List.of(), List.of(), List.of());
        when(exerciceComptableRepository.findByEntrepriseIdAndDate(entrepriseId, LocalDate.of(2027, 12, 31)))
                .thenReturn(Optional.of(exerciceOuvert(2027)));
        when(immobilisationRepository.findByEntrepriseIdOrderByDateAcquisitionAsc(entrepriseId)).thenReturn(List.of(enService));
        when(dotationAmortissementRepository.existsByImmobilisationIdAndAnnee(enService.getId(), 2027)).thenReturn(false);
        when(immobilisationRule.construireDotation(eq(entreprise), eq(enService), eq(new BigDecimal("1000000.00")), anyString())).thenReturn(resultat);
        AccountingOperationProposalDto proposition = propositionAvecEcriture(ecritureId);
        when(accountingOperationEngine.proposerEtValider(eq(entrepriseId), eq(AccountingOperationType.AMORTISSEMENT_DOTATION), eq(null), eq(LocalDate.of(2027, 12, 31)), anyString(), any(), eq(resultat), eq(AccountingSource.de("IMMOBILISATION", enService.getId(), "DOTATION_2027")), eq(StatutEcriture.VALIDEE), eq(principal))).thenReturn(proposition);

        GenererDotationsResultDto resultatGeneration = immobilisationService.genererDotations(entrepriseId, 2027, principal);

        assertThat(resultatGeneration.nombreImmobilisations()).isEqualTo(1);
        assertThat(resultatGeneration.ecritureIds()).containsExactly(ecritureId);
        assertThat(resultatGeneration.totalDotations()).isEqualByComparingTo("1000000");
        verify(dotationAmortissementRepository).save(any(DotationAmortissement.class));
    }

    @Test
    void generer_dotations_ignore_les_immobilisations_non_mises_en_service_deja_amorties_ou_sorties_cette_annee() {
        Immobilisation acquise = immobilisation(StatutImmobilisation.ACQUISE, LocalDate.of(2027, 1, 1), null);
        Immobilisation dejaGeneree = immobilisation(StatutImmobilisation.EN_SERVICE, LocalDate.of(2027, 1, 1), LocalDate.of(2027, 1, 1));
        Immobilisation sortieCetteAnnee = immobilisation(StatutImmobilisation.SORTIE, LocalDate.of(2027, 1, 1), LocalDate.of(2027, 1, 1));
        sortieCetteAnnee.setDateSortie(LocalDate.of(2027, 9, 1));
        when(exerciceComptableRepository.findByEntrepriseIdAndDate(entrepriseId, LocalDate.of(2027, 12, 31)))
                .thenReturn(Optional.of(exerciceOuvert(2027)));
        when(immobilisationRepository.findByEntrepriseIdOrderByDateAcquisitionAsc(entrepriseId))
                .thenReturn(List.of(acquise, dejaGeneree, sortieCetteAnnee));
        when(dotationAmortissementRepository.existsByImmobilisationIdAndAnnee(dejaGeneree.getId(), 2027)).thenReturn(true);

        GenererDotationsResultDto resultat = immobilisationService.genererDotations(entrepriseId, 2027, principal);

        assertThat(resultat.nombreImmobilisations()).isZero();
        assertThat(resultat.ecritureIds()).isEmpty();
        verify(accountingOperationEngine, never()).proposerEtValider(any(), any(), any(), any(), anyString(), any(), any(), any(), any(), any());
    }

    @Test
    void generer_dotations_amortit_encore_les_annees_anterieures_a_la_sortie() {
        Immobilisation sortie2028 = immobilisation(StatutImmobilisation.SORTIE, LocalDate.of(2027, 1, 1), LocalDate.of(2027, 1, 1));
        sortie2028.setDateSortie(LocalDate.of(2028, 6, 1));
        when(exerciceComptableRepository.findByEntrepriseIdAndDate(entrepriseId, LocalDate.of(2027, 12, 31)))
                .thenReturn(Optional.of(exerciceOuvert(2027)));
        when(immobilisationRepository.findByEntrepriseIdOrderByDateAcquisitionAsc(entrepriseId)).thenReturn(List.of(sortie2028));
        when(dotationAmortissementRepository.existsByImmobilisationIdAndAnnee(sortie2028.getId(), 2027)).thenReturn(false);
        when(immobilisationRule.construireDotation(any(), any(), any(), anyString())).thenReturn(new ResultatRegle(null, List.of(), List.of(), List.of()));
        AccountingOperationProposalDto proposition = propositionAvecEcriture(UUID.randomUUID());
        when(accountingOperationEngine.proposerEtValider(any(), any(), any(), any(), anyString(), any(), any(), any(), any(), any()))
                .thenReturn(proposition);

        assertThat(immobilisationService.genererDotations(entrepriseId, 2027, principal).nombreImmobilisations()).isEqualTo(1);
    }

    @Test
    void refuse_de_generer_sur_un_exercice_cloture() {
        ExerciceComptable cloture = exerciceOuvert(2027);
        cloture.setStatut(StatutExercice.CLOTURE);
        when(exerciceComptableRepository.findByEntrepriseIdAndDate(entrepriseId, LocalDate.of(2027, 12, 31))).thenReturn(Optional.of(cloture));

        assertThatThrownBy(() -> immobilisationService.genererDotations(entrepriseId, 2027, principal))
                .isInstanceOfSatisfying(ResponseStatusException.class, e -> assertThat(e.getStatusCode().value()).isEqualTo(422));
        verify(accountingOperationEngine, never()).proposerEtValider(any(), any(), any(), any(), anyString(), any(), any(), any(), any(), any());
    }

    @Test
    void refuse_de_generer_sans_exercice_pour_l_annee() {
        when(exerciceComptableRepository.findByEntrepriseIdAndDate(entrepriseId, LocalDate.of(2031, 12, 31))).thenReturn(Optional.empty());

        assertThatThrownBy(() -> immobilisationService.genererDotations(entrepriseId, 2031, principal))
                .isInstanceOfSatisfying(ResponseStatusException.class, e -> assertThat(e.getStatusCode().value()).isEqualTo(422));
    }

    // ------------------------------------------------------------------ sortie

    @Test
    void sortir_par_cession_enregistre_date_motif_et_prix_sans_generer_d_ecriture() {
        Immobilisation immo = immobilisation(StatutImmobilisation.EN_SERVICE, LocalDate.of(2027, 1, 1), LocalDate.of(2027, 2, 1));
        when(immobilisationRepository.findByIdAndEntrepriseId(immo.getId(), entrepriseId)).thenReturn(Optional.of(immo));

        ImmobilisationDto dto = immobilisationService.sortir(entrepriseId, immo.getId(),
                new SortirImmobilisationRequest(LocalDate.of(2029, 6, 30), MotifSortie.CESSION, new BigDecimal("1200000")), principal);

        assertThat(dto.statut()).isEqualTo("SORTIE");
        assertThat(dto.motifSortie()).isEqualTo("CESSION");
        assertThat(dto.dateSortie()).isEqualTo(LocalDate.of(2029, 6, 30));
        assertThat(dto.montantCession()).isEqualByComparingTo("1200000");
        verify(accountingOperationEngine, never()).proposerEtValider(any(), any(), any(), any(), anyString(), any(), any(), any(), any(), any());
        verify(auditTrailService, times(1)).enregistrerActionHumaine(any(), any(), eq("IMMOBILISATION_SORTIE"), eq("IMMOBILISATION"), anyString(), any());
    }

    @Test
    void sortir_refuse_une_date_anterieure_une_mise_au_rebut_avec_prix_et_une_seconde_sortie() {
        Immobilisation immo = immobilisation(StatutImmobilisation.EN_SERVICE, LocalDate.of(2027, 1, 1), LocalDate.of(2027, 2, 1));
        when(immobilisationRepository.findByIdAndEntrepriseId(immo.getId(), entrepriseId)).thenReturn(Optional.of(immo));

        assertThatThrownBy(() -> immobilisationService.sortir(entrepriseId, immo.getId(),
                new SortirImmobilisationRequest(LocalDate.of(2027, 1, 15), MotifSortie.CESSION, null), principal))
                .isInstanceOfSatisfying(ResponseStatusException.class, e -> assertThat(e.getStatusCode().value()).isEqualTo(422));
        assertThatThrownBy(() -> immobilisationService.sortir(entrepriseId, immo.getId(),
                new SortirImmobilisationRequest(LocalDate.of(2028, 1, 15), MotifSortie.MISE_AU_REBUT, new BigDecimal("10")), principal))
                .isInstanceOfSatisfying(ResponseStatusException.class, e -> assertThat(e.getStatusCode().value()).isEqualTo(422));

        immo.setStatut(StatutImmobilisation.SORTIE);
        assertThatThrownBy(() -> immobilisationService.sortir(entrepriseId, immo.getId(),
                new SortirImmobilisationRequest(LocalDate.of(2028, 1, 15), MotifSortie.MISE_AU_REBUT, null), principal))
                .isInstanceOfSatisfying(ResponseStatusException.class, e -> assertThat(e.getStatusCode().value()).isEqualTo(409));
    }

    @Test
    void une_immobilisation_d_une_autre_entreprise_est_introuvable() {
        UUID autreId = UUID.randomUUID();
        when(immobilisationRepository.findByIdAndEntrepriseId(autreId, entrepriseId)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> immobilisationService.mettreEnService(entrepriseId, autreId,
                new MettreEnServiceRequest(LocalDate.of(2027, 5, 1)), principal))
                .isInstanceOfSatisfying(ResponseStatusException.class, e -> assertThat(e.getStatusCode().value()).isEqualTo(404));
    }

    // ------------------------------------------------------------------ liste

    @Test
    void la_liste_expose_un_plan_seulement_apres_la_mise_en_service_et_la_vnc_deduit_les_dotations_generees() {
        Immobilisation acquise = immobilisation(StatutImmobilisation.ACQUISE, LocalDate.of(2027, 1, 1), null);
        Immobilisation enService = immobilisation(StatutImmobilisation.EN_SERVICE, LocalDate.of(2027, 1, 1), LocalDate.of(2027, 1, 1));
        DotationAmortissement dotation = new DotationAmortissement();
        dotation.setImmobilisation(enService);
        dotation.setAnnee(2027);
        dotation.setMontant(new BigDecimal("1000000"));
        when(immobilisationRepository.findByEntrepriseIdOrderByDateAcquisitionAsc(entrepriseId)).thenReturn(List.of(acquise, enService));
        when(dotationAmortissementRepository.findByImmobilisationIdIn(any())).thenReturn(List.of(dotation));

        List<ImmobilisationDto> liste = immobilisationService.lister(entrepriseId, principal);

        assertThat(liste.get(0).plan()).isEmpty();
        assertThat(liste.get(0).valeurNetteComptableActuelle()).isEqualByComparingTo("5000000");
        assertThat(liste.get(1).plan()).hasSize(5);
        assertThat(liste.get(1).plan().get(0).genere()).isTrue();
        assertThat(liste.get(1).valeurNetteComptableActuelle()).isEqualByComparingTo("4000000");
    }
}
