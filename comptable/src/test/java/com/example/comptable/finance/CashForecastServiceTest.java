package com.example.comptable.finance;

import com.example.comptable.comptabilite.domain.Facture;
import com.example.comptable.comptabilite.domain.LigneFacture;
import com.example.comptable.comptabilite.domain.StatutFacture;
import com.example.comptable.comptabilite.domain.Tiers;
import com.example.comptable.comptabilite.domain.TypeFacture;
import com.example.comptable.comptabilite.repository.FactureRepository;
import com.example.comptable.comptabilite.repository.LigneEcritureRepository;
import com.example.comptable.entreprise.domain.Entreprise;
import com.example.comptable.entreprise.service.EntrepriseAccessService;
import com.example.comptable.finance.web.dto.PrevisionTresorerieDto;
import com.example.comptable.fiscalite.repository.DeclarationFiscaleRepository;
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
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class CashForecastServiceTest {

    @Mock
    private EntrepriseAccessService entrepriseAccessService;
    @Mock
    private LigneEcritureRepository ligneEcritureRepository;
    @Mock
    private FactureRepository factureRepository;
    @Mock
    private DeclarationFiscaleRepository declarationFiscaleRepository;

    @InjectMocks
    private CashForecastService cashForecastService;

    private UUID entrepriseId;
    private UtilisateurPrincipal principal;

    @BeforeEach
    void setUp() {
        entrepriseId = UUID.randomUUID();
        Entreprise entreprise = new Entreprise();
        entreprise.setId(entrepriseId);

        Role role = new Role();
        role.setCode("EXPERT_COMPTABLE");
        Utilisateur utilisateur = new Utilisateur();
        utilisateur.setId(UUID.randomUUID());
        utilisateur.setEmail("expert@cabinet.ci");
        utilisateur.setMotDePasse("hash");
        utilisateur.setNom("Nikiema");
        utilisateur.setPrenom("Adama");
        utilisateur.setRoles(Set.of(role));
        principal = new UtilisateurPrincipal(utilisateur);

        when(entrepriseAccessService.verifierAcces(entrepriseId, principal)).thenReturn(entreprise);
        when(ligneEcritureRepository.soldeTresorerie(any(), any())).thenReturn(new BigDecimal("10000000"));
        lenient().when(declarationFiscaleRepository.findByEntrepriseIdAndDateEcheanceBetween(any(), any(), any()))
                .thenReturn(List.of());
    }

    private Facture facture(LocalDate dateEcheance, BigDecimal montant) {
        Facture f = new Facture();
        f.setId(UUID.randomUUID());
        Tiers tiers = new Tiers();
        tiers.setNom("Client Test");
        f.setTiers(tiers);
        f.setDateEcheance(dateEcheance);
        LigneFacture ligne = new LigneFacture();
        ligne.setQuantite(BigDecimal.ONE);
        ligne.setPrixUnitaire(montant);
        ligne.setTauxTva(BigDecimal.ZERO);
        ligne.setNumeroLigne(1);
        f.setLignes(List.of(ligne));
        return f;
    }

    @Test
    void produit_les_trois_horizons_avec_known_et_expected_separes() {
        LocalDate aujourdHui = LocalDate.now();
        Facture connue = facture(aujourdHui.plusDays(5), new BigDecimal("1000000"));
        Facture enRetard30j = facture(aujourdHui.minusDays(15), new BigDecimal("500000"));

        when(factureRepository.findByEntrepriseIdAndTypeAndStatutAndDateEcheanceBeforeOrderByDateEcheanceAsc(
                eq(entrepriseId), eq(TypeFacture.FACTURE), eq(StatutFacture.VALIDEE), any()))
                .thenReturn(List.of(enRetard30j));
        when(factureRepository.findByEntrepriseIdAndTypeAndStatutAndDateEcheanceBetweenOrderByDateEcheanceAsc(
                eq(entrepriseId), eq(TypeFacture.FACTURE), eq(StatutFacture.VALIDEE), any(), any()))
                .thenAnswer(inv -> {
                    LocalDate debut = inv.getArgument(3);
                    LocalDate fin = inv.getArgument(4);
                    return !connue.getDateEcheance().isBefore(debut) && !connue.getDateEcheance().isAfter(fin)
                            ? List.of(connue) : List.of();
                });

        List<PrevisionTresorerieDto> previsions = cashForecastService.generer(entrepriseId, principal);

        assertThat(previsions).hasSize(3);
        PrevisionTresorerieDto horizon7 = previsions.stream().filter(p -> p.horizonJours() == 7).findFirst().orElseThrow();
        assertThat(horizon7.soldeActuel()).isEqualByComparingTo("10000000");
        assertThat(horizon7.encaissementsConnus()).isEqualByComparingTo("1000000");
        // Retard de 15j -> tranche 1 (<=30j) -> probabilite 0.90 -> 500000 * 0.90 = 450000
        assertThat(horizon7.encaissementsAttendus()).isEqualByComparingTo("450000");
        assertThat(horizon7.decaissementsConnus()).isEqualByComparingTo(BigDecimal.ZERO);
        assertThat(horizon7.soldePrevisionnel()).isEqualByComparingTo("11450000");

        PrevisionTresorerieDto horizon90 = previsions.stream().filter(p -> p.horizonJours() == 90).findFirst().orElseThrow();
        assertThat(horizon90.encaissementsConnus()).isEqualByComparingTo("1000000");
        assertThat(horizon90.encaissementsAttendus()).isEqualByComparingTo("450000");
    }
}
