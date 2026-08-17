package com.example.comptable.comptabilite.service;

import com.example.comptable.comptabilite.domain.CompteComptable;
import com.example.comptable.comptabilite.domain.EcritureComptable;
import com.example.comptable.comptabilite.domain.LigneEcriture;
import com.example.comptable.comptabilite.domain.StatutEcriture;
import com.example.comptable.comptabilite.domain.Tiers;
import com.example.comptable.comptabilite.domain.TypeTiers;
import com.example.comptable.comptabilite.repository.LigneEcritureRepository;
import com.example.comptable.comptabilite.web.dto.BalanceAgeeDto;
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

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class BalanceAgeeServiceTest {

    @Mock
    private EntrepriseAccessService entrepriseAccessService;
    @Mock
    private LigneEcritureRepository ligneEcritureRepository;

    @InjectMocks
    private BalanceAgeeService balanceAgeeService;

    private UUID entrepriseId;
    private Entreprise entreprise;
    private UtilisateurPrincipal principal;
    private CompteComptable compteClient;
    private LocalDate dateArrete;
    private Tiers client;

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

        compteClient = new CompteComptable();
        compteClient.setId(UUID.randomUUID());
        compteClient.setEntreprise(entreprise);
        compteClient.setNumero("411001");
        compteClient.setLettrable(true);

        client = new Tiers();
        client.setId(UUID.randomUUID());
        client.setEntreprise(entreprise);
        client.setType(TypeTiers.CLIENT);
        client.setNom("Client Alpha");
        client.setCompteComptable(compteClient);

        dateArrete = LocalDate.of(2026, 8, 5);

        when(entrepriseAccessService.verifierAcces(entrepriseId, principal)).thenReturn(entreprise);
    }

    private LigneEcriture ligne(Tiers tiers, BigDecimal debit, BigDecimal credit, LocalDate dateEcriture) {
        EcritureComptable ecriture = new EcritureComptable();
        ecriture.setId(UUID.randomUUID());
        ecriture.setEntreprise(entreprise);
        ecriture.setStatut(StatutEcriture.VALIDEE);
        ecriture.setDateEcriture(dateEcriture);

        LigneEcriture ligne = new LigneEcriture();
        ligne.setId(UUID.randomUUID());
        ligne.setEcriture(ecriture);
        ligne.setCompte(compteClient);
        ligne.setTiers(tiers);
        ligne.setDebit(debit);
        ligne.setCredit(credit);
        return ligne;
    }

    @Test
    void repartit_les_creances_dans_la_bonne_tranche_dagee() {
        LigneEcriture recente = ligne(client, new BigDecimal("10000"), BigDecimal.ZERO, dateArrete.minusDays(10));
        LigneEcriture moyenne = ligne(client, new BigDecimal("20000"), BigDecimal.ZERO, dateArrete.minusDays(45));
        LigneEcriture ancienne = ligne(client, new BigDecimal("30000"), BigDecimal.ZERO, dateArrete.minusDays(75));
        LigneEcriture tresAncienne = ligne(client, new BigDecimal("40000"), BigDecimal.ZERO, dateArrete.minusDays(120));

        when(ligneEcritureRepository.rechercherLignesEnInstance(entrepriseId, dateArrete))
                .thenReturn(List.of(recente, moyenne, ancienne, tresAncienne));

        BalanceAgeeDto resultat = balanceAgeeService.generer(entrepriseId, dateArrete, principal);

        assertThat(resultat.lignes()).hasSize(1);
        var ligneTiers = resultat.lignes().get(0);
        assertThat(ligneTiers.tiersId()).isEqualTo(client.getId());
        assertThat(ligneTiers.typeTiers()).isEqualTo("CLIENT");
        assertThat(ligneTiers.tranche0a30()).isEqualByComparingTo("10000");
        assertThat(ligneTiers.tranche31a60()).isEqualByComparingTo("20000");
        assertThat(ligneTiers.tranche61a90()).isEqualByComparingTo("30000");
        assertThat(ligneTiers.tranchePlus90()).isEqualByComparingTo("40000");
        assertThat(ligneTiers.total()).isEqualByComparingTo("100000");
        assertThat(resultat.totalGeneral()).isEqualByComparingTo("100000");
    }

    @Test
    void un_solde_de_tiers_totalement_nul_est_exclu_du_resultat() {
        LigneEcriture facture = ligne(client, new BigDecimal("50000"), BigDecimal.ZERO, dateArrete.minusDays(5));
        LigneEcriture avoir = ligne(client, BigDecimal.ZERO, new BigDecimal("50000"), dateArrete.minusDays(5));

        when(ligneEcritureRepository.rechercherLignesEnInstance(entrepriseId, dateArrete))
                .thenReturn(List.of(facture, avoir));

        BalanceAgeeDto resultat = balanceAgeeService.generer(entrepriseId, dateArrete, principal);

        assertThat(resultat.lignes()).isEmpty();
        assertThat(resultat.totalGeneral()).isEqualByComparingTo("0");
    }

    @Test
    void les_lignes_sans_tiers_sont_ignorees() {
        LigneEcriture sansTiers = ligne(null, new BigDecimal("15000"), BigDecimal.ZERO, dateArrete.minusDays(5));

        when(ligneEcritureRepository.rechercherLignesEnInstance(entrepriseId, dateArrete))
                .thenReturn(List.of(sansTiers));

        BalanceAgeeDto resultat = balanceAgeeService.generer(entrepriseId, dateArrete, principal);

        assertThat(resultat.lignes()).isEmpty();
    }

    @Test
    void utilise_la_date_du_jour_quand_aucune_date_darrete_nest_fournie() {
        when(ligneEcritureRepository.rechercherLignesEnInstance(entrepriseId, LocalDate.now()))
                .thenReturn(List.of());

        BalanceAgeeDto resultat = balanceAgeeService.generer(entrepriseId, null, principal);

        assertThat(resultat.dateArrete()).isEqualTo(LocalDate.now());
    }

    @Test
    void une_dette_fournisseur_apparait_avec_un_montant_negatif() {
        Tiers fournisseur = new Tiers();
        fournisseur.setId(UUID.randomUUID());
        fournisseur.setEntreprise(entreprise);
        fournisseur.setType(TypeTiers.FOURNISSEUR);
        fournisseur.setNom("Fournisseur Beta");
        fournisseur.setCompteComptable(compteClient);

        LigneEcriture achat = ligne(fournisseur, BigDecimal.ZERO, new BigDecimal("25000"), dateArrete.minusDays(5));

        when(ligneEcritureRepository.rechercherLignesEnInstance(entrepriseId, dateArrete))
                .thenReturn(List.of(achat));

        BalanceAgeeDto resultat = balanceAgeeService.generer(entrepriseId, dateArrete, principal);

        assertThat(resultat.lignes()).hasSize(1);
        assertThat(resultat.lignes().get(0).typeTiers()).isEqualTo("FOURNISSEUR");
        assertThat(resultat.lignes().get(0).tranche0a30()).isEqualByComparingTo("-25000");
        assertThat(resultat.totalGeneral()).isEqualByComparingTo("-25000");
    }
}
