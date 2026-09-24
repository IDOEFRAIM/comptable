package com.example.comptable.finance;

import com.example.comptable.comptabilite.repository.LigneEcritureRepository;
import com.example.comptable.comptabilite.service.BalanceAgeeService;
import com.example.comptable.comptabilite.web.dto.BalanceAgeeDto;
import com.example.comptable.comptabilite.web.dto.BalanceAgeeLigneDto;
import com.example.comptable.entreprise.domain.Entreprise;
import com.example.comptable.entreprise.repository.EntrepriseRepository;
import com.example.comptable.entreprise.service.EntrepriseAccessService;
import com.example.comptable.finance.web.dto.ComparaisonFinancialSnapshotDto;
import com.example.comptable.finance.web.dto.FinancialSnapshotDto;
import com.example.comptable.fiscalite.domain.DeclarationFiscale;
import com.example.comptable.fiscalite.domain.TypeImpot;
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
import org.springframework.web.server.ResponseStatusException;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class FinancialSnapshotServiceTest {

    @Mock
    private EntrepriseAccessService entrepriseAccessService;
    @Mock
    private EntrepriseRepository entrepriseRepository;
    @Mock
    private LigneEcritureRepository ligneEcritureRepository;
    @Mock
    private BalanceAgeeService balanceAgeeService;
    @Mock
    private DeclarationFiscaleRepository declarationFiscaleRepository;

    @InjectMocks
    private FinancialSnapshotService financialSnapshotService;

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
        utilisateur.setNom("Compaore");
        utilisateur.setPrenom("Salif");
        utilisateur.setRoles(Set.of(role));
        principal = new UtilisateurPrincipal(utilisateur);

        lenient().when(entrepriseAccessService.verifierAcces(eq(entrepriseId), eq(principal))).thenReturn(entreprise);
    }

    @Test
    void combine_cash_creances_dettes_revenu_charges_et_taxes() {
        LocalDate debut = LocalDate.of(2026, 8, 1);
        LocalDate fin = LocalDate.of(2026, 8, 31);

        when(ligneEcritureRepository.soldeTresorerie(entrepriseId, fin)).thenReturn(new BigDecimal("11100000"));

        BalanceAgeeDto balanceAgee = new BalanceAgeeDto(fin, List.of(
                new BalanceAgeeLigneDto(UUID.randomUUID(), "Client ABC", "CLIENT",
                        new BigDecimal("500000"), BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, new BigDecimal("500000")),
                new BalanceAgeeLigneDto(UUID.randomUUID(), "Fournisseur XYZ", "FOURNISSEUR",
                        new BigDecimal("-300000"), BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, new BigDecimal("-300000"))
        ), new BigDecimal("200000"));
        when(balanceAgeeService.generer(entrepriseId, fin, principal)).thenReturn(balanceAgee);

        when(ligneEcritureRepository.mouvementNetClasseSurPeriode(entrepriseId, "6%", debut, fin))
                .thenReturn(new BigDecimal("2000000"));
        when(ligneEcritureRepository.mouvementNetClasseSurPeriode(entrepriseId, "7%", debut, fin))
                .thenReturn(new BigDecimal("-5000000"));

        DeclarationFiscale tva = new DeclarationFiscale();
        tva.setTypeImpot(TypeImpot.TVA);
        tva.setMontant(new BigDecimal("450000"));
        when(declarationFiscaleRepository.findByEntrepriseIdAndDateEcheanceBetween(entrepriseId, debut, fin))
                .thenReturn(List.of(tva));

        FinancialSnapshotDto snapshot = financialSnapshotService.generer(entrepriseId, debut, fin, principal);

        assertThat(snapshot.cash()).isEqualByComparingTo("11100000");
        assertThat(snapshot.receivables()).isEqualByComparingTo("500000");
        assertThat(snapshot.payables()).isEqualByComparingTo("300000");
        assertThat(snapshot.expenses()).isEqualByComparingTo("2000000");
        assertThat(snapshot.revenue()).isEqualByComparingTo("5000000");
        assertThat(snapshot.profit()).isEqualByComparingTo("3000000");
        assertThat(snapshot.taxes()).isEqualByComparingTo("450000");
    }

    @Test
    void comparaison_periode_precedente_utilise_une_periode_de_meme_longueur() {
        LocalDate debut = LocalDate.of(2026, 8, 1);
        LocalDate fin = LocalDate.of(2026, 8, 31);
        stubValeursNeutres();

        ComparaisonFinancialSnapshotDto comparaison = financialSnapshotService.comparer(
                entrepriseId, debut, fin, "PERIODE_PRECEDENTE", principal);

        assertThat(comparaison.periodeComparaison().periodeFin()).isEqualTo(LocalDate.of(2026, 7, 31));
        assertThat(comparaison.periodeComparaison().periodeDebut()).isEqualTo(LocalDate.of(2026, 7, 1));
    }

    @Test
    void comparaison_an_precedent_decale_dun_an() {
        LocalDate debut = LocalDate.of(2026, 8, 1);
        LocalDate fin = LocalDate.of(2026, 8, 31);
        stubValeursNeutres();

        ComparaisonFinancialSnapshotDto comparaison = financialSnapshotService.comparer(
                entrepriseId, debut, fin, "AN_PRECEDENT", principal);

        assertThat(comparaison.periodeComparaison().periodeDebut()).isEqualTo(LocalDate.of(2025, 8, 1));
        assertThat(comparaison.periodeComparaison().periodeFin()).isEqualTo(LocalDate.of(2025, 8, 31));
    }

    @Test
    void genererSysteme_calcule_sans_principal_quand_lentreprise_existe() {
        LocalDate debut = LocalDate.of(2026, 8, 1);
        LocalDate fin = LocalDate.of(2026, 8, 31);
        when(entrepriseRepository.existsById(entrepriseId)).thenReturn(true);
        when(ligneEcritureRepository.soldeTresorerie(entrepriseId, fin)).thenReturn(new BigDecimal("1000000"));
        when(balanceAgeeService.genererSysteme(entrepriseId, fin))
                .thenReturn(new BalanceAgeeDto(fin, List.of(), BigDecimal.ZERO));
        when(ligneEcritureRepository.mouvementNetClasseSurPeriode(entrepriseId, "6%", debut, fin)).thenReturn(new BigDecimal("500000"));
        when(ligneEcritureRepository.mouvementNetClasseSurPeriode(entrepriseId, "7%", debut, fin)).thenReturn(new BigDecimal("-900000"));
        when(declarationFiscaleRepository.findByEntrepriseIdAndDateEcheanceBetween(entrepriseId, debut, fin)).thenReturn(List.of());

        FinancialSnapshotDto snapshot = financialSnapshotService.genererSysteme(entrepriseId, debut, fin);

        assertThat(snapshot.cash()).isEqualByComparingTo("1000000");
        assertThat(snapshot.revenue()).isEqualByComparingTo("900000");
        assertThat(snapshot.expenses()).isEqualByComparingTo("500000");
        assertThat(snapshot.profit()).isEqualByComparingTo("400000");
    }

    @Test
    void genererSysteme_refuse_une_entreprise_introuvable() {
        when(entrepriseRepository.existsById(entrepriseId)).thenReturn(false);

        assertThatThrownBy(() -> financialSnapshotService.genererSysteme(entrepriseId, LocalDate.now(), LocalDate.now()))
                .isInstanceOf(ResponseStatusException.class);
    }

    private void stubValeursNeutres() {
        lenient().when(ligneEcritureRepository.soldeTresorerie(any(), any())).thenReturn(BigDecimal.ZERO);
        lenient().when(balanceAgeeService.generer(any(), any(), any()))
                .thenReturn(new BalanceAgeeDto(LocalDate.now(), List.of(), BigDecimal.ZERO));
        lenient().when(ligneEcritureRepository.mouvementNetClasseSurPeriode(any(), any(), any(), any()))
                .thenReturn(BigDecimal.ZERO);
        lenient().when(declarationFiscaleRepository.findByEntrepriseIdAndDateEcheanceBetween(any(), any(), any()))
                .thenReturn(List.of());
    }
}
