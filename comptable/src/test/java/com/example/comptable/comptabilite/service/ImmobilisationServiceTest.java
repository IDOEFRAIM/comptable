package com.example.comptable.comptabilite.service;

import com.example.comptable.comptabilite.domain.CompteComptable;
import com.example.comptable.comptabilite.domain.EcritureComptable;
import com.example.comptable.comptabilite.domain.ExerciceComptable;
import com.example.comptable.comptabilite.domain.Immobilisation;
import com.example.comptable.comptabilite.domain.Journal;
import com.example.comptable.comptabilite.domain.ModeAmortissement;
import com.example.comptable.comptabilite.domain.StatutExercice;
import com.example.comptable.comptabilite.domain.StatutImmobilisation;
import com.example.comptable.comptabilite.repository.CompteComptableRepository;
import com.example.comptable.comptabilite.repository.DotationAmortissementRepository;
import com.example.comptable.comptabilite.repository.EcritureComptableRepository;
import com.example.comptable.comptabilite.repository.ExerciceComptableRepository;
import com.example.comptable.comptabilite.repository.ImmobilisationRepository;
import com.example.comptable.comptabilite.repository.JournalRepository;
import com.example.comptable.comptabilite.web.dto.CreerImmobilisationRequest;
import com.example.comptable.comptabilite.web.dto.GenererDotationsResultDto;
import com.example.comptable.comptabilite.web.dto.ImmobilisationDto;
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
class ImmobilisationServiceTest {

    @Mock
    private EntrepriseAccessService entrepriseAccessService;
    @Mock
    private ImmobilisationRepository immobilisationRepository;
    @Mock
    private DotationAmortissementRepository dotationAmortissementRepository;
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
    private ImmobilisationService immobilisationService;

    private UUID entrepriseId;
    private Entreprise entreprise;
    private UtilisateurPrincipal principal;
    private Utilisateur utilisateur;
    private CompteComptable compteMateriel;
    private CompteComptable compteAmortissement;
    private CompteComptable compte681;
    private Journal journalOd;
    private ExerciceComptable exercice;

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

        compteMateriel = compte("244");
        compteAmortissement = compte("2844");
        compte681 = compte("681");

        journalOd = new Journal();
        journalOd.setId(UUID.randomUUID());
        journalOd.setEntreprise(entreprise);
        journalOd.setCode("OD");

        exercice = new ExerciceComptable();
        exercice.setId(UUID.randomUUID());
        exercice.setEntreprise(entreprise);
        exercice.setDateDebut(LocalDate.of(2027, 1, 1));
        exercice.setDateFin(LocalDate.of(2027, 12, 31));
        exercice.setStatut(StatutExercice.OUVERT);

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

    private Immobilisation immobilisationEnService(BigDecimal valeur, int duree, LocalDate acquisition) {
        Immobilisation immo = new Immobilisation();
        immo.setId(UUID.randomUUID());
        immo.setEntreprise(entreprise);
        immo.setCompte(compteMateriel);
        immo.setCompteAmortissement(compteAmortissement);
        immo.setLibelle("Vehicule utilitaire");
        immo.setDateAcquisition(acquisition);
        immo.setValeurOrigine(valeur);
        immo.setDureeAnnees(duree);
        immo.setMode(ModeAmortissement.LINEAIRE);
        immo.setStatut(StatutImmobilisation.EN_SERVICE);
        return immo;
    }

    @Test
    void cree_une_immobilisation() {
        when(compteComptableRepository.findById(compteMateriel.getId())).thenReturn(Optional.of(compteMateriel));
        when(compteComptableRepository.findById(compteAmortissement.getId())).thenReturn(Optional.of(compteAmortissement));
        when(immobilisationRepository.save(any())).thenAnswer(inv -> {
            Immobilisation i = inv.getArgument(0);
            i.setId(UUID.randomUUID());
            return i;
        });

        CreerImmobilisationRequest request = new CreerImmobilisationRequest(
                compteMateriel.getId(), compteAmortissement.getId(), "Vehicule utilitaire",
                LocalDate.of(2027, 1, 1), new BigDecimal("50000"), 5, ModeAmortissement.LINEAIRE);

        ImmobilisationDto resultat = immobilisationService.creer(entrepriseId, request, principal);

        assertThat(resultat.libelle()).isEqualTo("Vehicule utilitaire");
        assertThat(resultat.plan()).hasSize(5);
        assertThat(resultat.valeurNetteComptableActuelle()).isEqualByComparingTo("50000");
    }

    @Test
    void liste_les_immobilisations_avec_leurs_dotations_deja_generees_via_une_requete_groupee() {
        Immobilisation immo1 = immobilisationEnService(new BigDecimal("50000"), 5, LocalDate.of(2027, 1, 1));
        Immobilisation immo2 = immobilisationEnService(new BigDecimal("30000"), 3, LocalDate.of(2027, 1, 1));

        com.example.comptable.comptabilite.domain.DotationAmortissement dotationImmo1 =
                new com.example.comptable.comptabilite.domain.DotationAmortissement();
        dotationImmo1.setImmobilisation(immo1);
        dotationImmo1.setAnnee(2027);
        dotationImmo1.setMontant(new BigDecimal("10000"));

        when(immobilisationRepository.findByEntrepriseIdOrderByDateAcquisitionAsc(entrepriseId))
                .thenReturn(List.of(immo1, immo2));
        when(dotationAmortissementRepository.findByImmobilisationIdIn(List.of(immo1.getId(), immo2.getId())))
                .thenReturn(List.of(dotationImmo1));

        List<ImmobilisationDto> resultat = immobilisationService.lister(entrepriseId, principal);

        assertThat(resultat).hasSize(2);
        ImmobilisationDto dto1 = resultat.stream().filter(d -> d.id().equals(immo1.getId())).findFirst().orElseThrow();
        ImmobilisationDto dto2 = resultat.stream().filter(d -> d.id().equals(immo2.getId())).findFirst().orElseThrow();
        assertThat(dto1.valeurNetteComptableActuelle()).isEqualByComparingTo("40000");
        assertThat(dto1.plan().get(0).genere()).isTrue();
        assertThat(dto2.valeurNetteComptableActuelle()).isEqualByComparingTo("30000");
        assertThat(dto2.plan().get(0).genere()).isFalse();

        // Une seule requete groupee pour toutes les immobilisations, jamais une par immobilisation.
        org.mockito.Mockito.verify(dotationAmortissementRepository, org.mockito.Mockito.never()).findByImmobilisationId(any());
    }

    @Test
    void genere_une_ecriture_de_dotation_pour_lannee_courante() {
        Immobilisation immo = immobilisationEnService(new BigDecimal("50000"), 5, LocalDate.of(2027, 1, 1));

        when(exerciceComptableRepository.findByEntrepriseIdAndDate(entrepriseId, LocalDate.of(2027, 12, 31)))
                .thenReturn(Optional.of(exercice));
        when(immobilisationRepository.findByEntrepriseIdAndStatut(entrepriseId, StatutImmobilisation.EN_SERVICE))
                .thenReturn(List.of(immo));
        when(dotationAmortissementRepository.existsByImmobilisationIdAndAnnee(immo.getId(), 2027)).thenReturn(false);
        when(journalRepository.findByEntrepriseIdAndCode(entrepriseId, "OD")).thenReturn(Optional.of(journalOd));
        when(compteComptableRepository.findByEntrepriseIdAndNumero(entrepriseId, "681")).thenReturn(Optional.of(compte681));
        when(utilisateurRepository.getReferenceById(principal.getId())).thenReturn(utilisateur);
        when(ecritureComptableRepository.countByJournalId(journalOd.getId())).thenReturn(0L);
        when(ecritureComptableRepository.save(any())).thenAnswer(inv -> {
            EcritureComptable e = inv.getArgument(0);
            e.setId(UUID.randomUUID());
            e.getLignes().forEach(l -> l.setId(UUID.randomUUID()));
            return e;
        });
        when(dotationAmortissementRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        GenererDotationsResultDto resultat = immobilisationService.genererDotations(entrepriseId, 2027, principal);

        assertThat(resultat.ecritureId()).isNotNull();
        assertThat(resultat.nombreImmobilisations()).isEqualTo(1);
        assertThat(resultat.totalDotations()).isEqualByComparingTo("10000");
    }

    @Test
    void ne_genere_rien_si_toutes_les_dotations_de_lannee_sont_deja_generees() {
        Immobilisation immo = immobilisationEnService(new BigDecimal("50000"), 5, LocalDate.of(2027, 1, 1));

        when(exerciceComptableRepository.findByEntrepriseIdAndDate(entrepriseId, LocalDate.of(2027, 12, 31)))
                .thenReturn(Optional.of(exercice));
        when(immobilisationRepository.findByEntrepriseIdAndStatut(entrepriseId, StatutImmobilisation.EN_SERVICE))
                .thenReturn(List.of(immo));
        when(dotationAmortissementRepository.existsByImmobilisationIdAndAnnee(immo.getId(), 2027)).thenReturn(true);

        GenererDotationsResultDto resultat = immobilisationService.genererDotations(entrepriseId, 2027, principal);

        assertThat(resultat.ecritureId()).isNull();
        assertThat(resultat.nombreImmobilisations()).isEqualTo(0);
    }

    @Test
    void refuse_de_generer_sur_un_exercice_cloture() {
        exercice.setStatut(StatutExercice.CLOTURE);
        when(exerciceComptableRepository.findByEntrepriseIdAndDate(entrepriseId, LocalDate.of(2027, 12, 31)))
                .thenReturn(Optional.of(exercice));

        assertThatThrownBy(() -> immobilisationService.genererDotations(entrepriseId, 2027, principal))
                .hasMessageContaining("cloture");
    }
}
