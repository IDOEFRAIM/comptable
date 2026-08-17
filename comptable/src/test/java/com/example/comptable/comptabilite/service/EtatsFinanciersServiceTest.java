package com.example.comptable.comptabilite.service;

import com.example.comptable.comptabilite.domain.CompteComptable;
import com.example.comptable.comptabilite.domain.ExerciceComptable;
import com.example.comptable.comptabilite.domain.NatureCompte;
import com.example.comptable.comptabilite.domain.PlanComptableReference;
import com.example.comptable.comptabilite.domain.StatutExercice;
import com.example.comptable.comptabilite.repository.CompteComptableRepository;
import com.example.comptable.comptabilite.repository.ExerciceComptableRepository;
import com.example.comptable.comptabilite.repository.LigneEcritureRepository;
import com.example.comptable.comptabilite.web.dto.BilanDto;
import com.example.comptable.comptabilite.web.dto.CompteResultatDto;
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
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class EtatsFinanciersServiceTest {

    @Mock
    private EntrepriseAccessService entrepriseAccessService;
    @Mock
    private ExerciceComptableRepository exerciceComptableRepository;
    @Mock
    private CompteComptableRepository compteComptableRepository;
    @Mock
    private LigneEcritureRepository ligneEcritureRepository;

    @InjectMocks
    private EtatsFinanciersService etatsFinanciersService;

    private UUID entrepriseId;
    private UUID exerciceId;
    private Entreprise entreprise;
    private ExerciceComptable exercice;
    private UtilisateurPrincipal principal;

    private CompteComptable capital;
    private CompteComptable terrains;
    private CompteComptable client;
    private CompteComptable fournisseur;
    private CompteComptable banque;
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

        capital = compte("101", "Capital social", 1, NatureCompte.PASSIF);
        terrains = compte("211", "Terrains", 2, NatureCompte.ACTIF);
        client = compteSansReference("411001", "Client - Fidele SARL");
        fournisseur = compteSansReference("401001", "Fournisseur - Premium Ltd");
        banque = compte("521", "Banques", 5, NatureCompte.ACTIF);
        achats = compte("601", "Achats de marchandises", 6, NatureCompte.CHARGE);
        ventes = compte("701", "Ventes de marchandises", 7, NatureCompte.PRODUIT);

        when(entrepriseAccessService.verifierAcces(entrepriseId, principal)).thenReturn(entreprise);
        org.mockito.Mockito.lenient().when(exerciceComptableRepository.findById(exerciceId)).thenReturn(Optional.of(exercice));
        org.mockito.Mockito.lenient().when(compteComptableRepository.findByEntrepriseId(entrepriseId))
                .thenReturn(List.of(capital, terrains, client, fournisseur, banque, achats, ventes));
    }

    private CompteComptable compte(String numero, String intitule, int classe, NatureCompte nature) {
        PlanComptableReference reference = new PlanComptableReference();
        reference.setClasse((short) classe);
        reference.setNature(nature);
        CompteComptable c = new CompteComptable();
        c.setId(UUID.randomUUID());
        c.setEntreprise(entreprise);
        c.setNumero(numero);
        c.setIntitule(intitule);
        c.setReference(reference);
        return c;
    }

    private CompteComptable compteSansReference(String numero, String intitule) {
        CompteComptable c = new CompteComptable();
        c.setId(UUID.randomUUID());
        c.setEntreprise(entreprise);
        c.setNumero(numero);
        c.setIntitule(intitule);
        return c;
    }

    /** Comptes de bilan (classes 1-5) : la somme de tous les nets (debit-credit), charges/produits
     * inclus, vaut zero pour un jeu de donnees coherent en partie double (cf. configurerMouvementsResultat). */
    private void configurerSoldesBilan() {
        LocalDate borne = exercice.getDateFin().plusDays(1);
        when(ligneEcritureRepository.soldeNetAvant(capital.getId(), borne)).thenReturn(new BigDecimal("-100000"));
        when(ligneEcritureRepository.soldeNetAvant(terrains.getId(), borne)).thenReturn(new BigDecimal("50000"));
        when(ligneEcritureRepository.soldeNetAvant(client.getId(), borne)).thenReturn(new BigDecimal("20000"));
        when(ligneEcritureRepository.soldeNetAvant(fournisseur.getId(), borne)).thenReturn(new BigDecimal("-15000"));
        when(ligneEcritureRepository.soldeNetAvant(banque.getId(), borne)).thenReturn(new BigDecimal("55000"));
    }

    private void configurerMouvementsResultat() {
        when(ligneEcritureRepository.mouvementNetPeriode(achats.getId(), exercice.getDateDebut(), exercice.getDateFin()))
                .thenReturn(new BigDecimal("-30000"));
        when(ligneEcritureRepository.mouvementNetPeriode(ventes.getId(), exercice.getDateDebut(), exercice.getDateFin()))
                .thenReturn(new BigDecimal("40000"));
    }

    @Test
    void genere_un_compte_de_resultat_correct() {
        configurerMouvementsResultat();

        CompteResultatDto cr = etatsFinanciersService.genererCompteResultat(entrepriseId, exerciceId, principal);

        assertThat(cr.chargesExploitation().total()).isEqualByComparingTo("30000");
        assertThat(cr.produitsExploitation().total()).isEqualByComparingTo("40000");
        assertThat(cr.resultatExploitation()).isEqualByComparingTo("10000");
        assertThat(cr.resultatFinancier()).isEqualByComparingTo("0");
        assertThat(cr.resultatNet()).isEqualByComparingTo("10000");
    }

    @Test
    void genere_un_bilan_equilibre_avec_resultat_provisoire_sur_exercice_ouvert() {
        configurerSoldesBilan();
        configurerMouvementsResultat();

        BilanDto bilan = etatsFinanciersService.genererBilan(entrepriseId, exerciceId, principal);

        assertThat(bilan.actifImmobilise().total()).isEqualByComparingTo("50000");
        assertThat(bilan.actifCirculant().total()).isEqualByComparingTo("20000");
        assertThat(bilan.tresorerieActif().total()).isEqualByComparingTo("55000");
        assertThat(bilan.totalActif()).isEqualByComparingTo("125000");

        assertThat(bilan.ressourcesDurables().total()).isEqualByComparingTo("110000");
        assertThat(bilan.passifCirculant().total()).isEqualByComparingTo("15000");
        assertThat(bilan.totalPassif()).isEqualByComparingTo("125000");

        assertThat(bilan.totalActif()).isEqualByComparingTo(bilan.totalPassif());
        assertThat(bilan.ressourcesDurables().postes())
                .anyMatch(p -> p.numero().equals("120/129") && p.montant().compareTo(new BigDecimal("10000")) == 0);
    }

    @Test
    void sous_compte_tiers_sans_reference_est_classe_par_prefixe_numero() {
        configurerSoldesBilan();
        configurerMouvementsResultat();

        BilanDto bilan = etatsFinanciersService.genererBilan(entrepriseId, exerciceId, principal);

        assertThat(bilan.actifCirculant().postes()).anyMatch(p -> p.numero().equals("411001"));
        assertThat(bilan.passifCirculant().postes()).anyMatch(p -> p.numero().equals("401001"));
    }

    @Test
    void ninjecte_pas_de_resultat_provisoire_si_exercice_cloture() {
        exercice.setStatut(StatutExercice.CLOTURE);
        configurerSoldesBilan();

        BilanDto bilan = etatsFinanciersService.genererBilan(entrepriseId, exerciceId, principal);

        assertThat(bilan.ressourcesDurables().postes()).noneMatch(p -> p.numero().equals("120/129"));
    }

    @Test
    void refuse_le_bilan_dun_exercice_dune_autre_entreprise() {
        exercice.getEntreprise().setId(UUID.randomUUID());

        assertThatThrownBy(() -> etatsFinanciersService.genererBilan(entrepriseId, exerciceId, principal))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("introuvable");
    }

    @Test
    void refuse_le_compte_de_resultat_dun_exercice_introuvable() {
        when(exerciceComptableRepository.findById(exerciceId)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> etatsFinanciersService.genererCompteResultat(entrepriseId, exerciceId, principal))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("introuvable");
    }

    @Test
    void classe_un_compte_de_tresorerie_credit_en_tresorerie_passif() {
        CompteComptable decouvertBancaire = compte("565", "Escompte de credits ordinaires", 5, NatureCompte.PASSIF);
        when(compteComptableRepository.findByEntrepriseId(entrepriseId))
                .thenReturn(List.of(decouvertBancaire));
        when(ligneEcritureRepository.soldeNetAvant(decouvertBancaire.getId(), exercice.getDateFin().plusDays(1)))
                .thenReturn(new BigDecimal("-25000"));

        BilanDto bilan = etatsFinanciersService.genererBilan(entrepriseId, exerciceId, principal);

        assertThat(bilan.tresoreriePassif().postes()).anyMatch(p -> p.numero().equals("565"));
        assertThat(bilan.tresoreriePassif().total()).isEqualByComparingTo("25000");
        assertThat(bilan.tresorerieActif().postes()).isEmpty();
    }

    @Test
    void isole_les_comptes_66x_77x_dans_les_sections_financieres() {
        CompteComptable interetsBancaires = compte("661", "Interets des emprunts", 6, NatureCompte.CHARGE);
        CompteComptable produitsFinanciers = compte("771", "Interets de prets", 7, NatureCompte.PRODUIT);
        when(compteComptableRepository.findByEntrepriseId(entrepriseId))
                .thenReturn(List.of(interetsBancaires, produitsFinanciers));
        when(ligneEcritureRepository.mouvementNetPeriode(interetsBancaires.getId(), exercice.getDateDebut(), exercice.getDateFin()))
                .thenReturn(new BigDecimal("-5000"));
        when(ligneEcritureRepository.mouvementNetPeriode(produitsFinanciers.getId(), exercice.getDateDebut(), exercice.getDateFin()))
                .thenReturn(new BigDecimal("8000"));

        CompteResultatDto cr = etatsFinanciersService.genererCompteResultat(entrepriseId, exerciceId, principal);

        assertThat(cr.chargesFinancieres().total()).isEqualByComparingTo("5000");
        assertThat(cr.produitsFinanciers().total()).isEqualByComparingTo("8000");
        assertThat(cr.resultatFinancier()).isEqualByComparingTo("3000");
        assertThat(cr.chargesExploitation().postes()).isEmpty();
        assertThat(cr.produitsExploitation().postes()).isEmpty();
    }

    @Test
    void ignore_les_comptes_sans_mouvement_sur_la_periode() {
        when(ligneEcritureRepository.mouvementNetPeriode(achats.getId(), exercice.getDateDebut(), exercice.getDateFin()))
                .thenReturn(BigDecimal.ZERO);
        when(ligneEcritureRepository.mouvementNetPeriode(ventes.getId(), exercice.getDateDebut(), exercice.getDateFin()))
                .thenReturn(BigDecimal.ZERO);

        CompteResultatDto cr = etatsFinanciersService.genererCompteResultat(entrepriseId, exerciceId, principal);

        assertThat(cr.chargesExploitation().postes()).isEmpty();
        assertThat(cr.produitsExploitation().postes()).isEmpty();
        assertThat(cr.resultatNet()).isEqualByComparingTo("0");
    }
}
