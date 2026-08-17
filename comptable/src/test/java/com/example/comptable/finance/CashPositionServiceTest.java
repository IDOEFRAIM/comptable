package com.example.comptable.finance;

import com.example.comptable.comptabilite.domain.CompteComptable;
import com.example.comptable.comptabilite.repository.CompteComptableRepository;
import com.example.comptable.comptabilite.repository.LigneEcritureRepository;
import com.example.comptable.entreprise.domain.Entreprise;
import com.example.comptable.entreprise.service.EntrepriseAccessService;
import com.example.comptable.finance.web.dto.CashPositionDto;
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
class CashPositionServiceTest {

    @Mock
    private EntrepriseAccessService entrepriseAccessService;
    @Mock
    private CompteComptableRepository compteComptableRepository;
    @Mock
    private LigneEcritureRepository ligneEcritureRepository;

    @InjectMocks
    private CashPositionService cashPositionService;

    private UUID entrepriseId;
    private UtilisateurPrincipal principal;

    @BeforeEach
    void setUp() {
        entrepriseId = UUID.randomUUID();
        Entreprise entreprise = new Entreprise();
        entreprise.setId(entrepriseId);

        com.example.comptable.identite.domain.Role role = new com.example.comptable.identite.domain.Role();
        role.setCode("EXPERT_COMPTABLE");
        com.example.comptable.identite.domain.Utilisateur utilisateur = new com.example.comptable.identite.domain.Utilisateur();
        utilisateur.setId(UUID.randomUUID());
        utilisateur.setEmail("expert@cabinet.ci");
        utilisateur.setMotDePasse("hash");
        utilisateur.setNom("Zongo");
        utilisateur.setPrenom("Aissata");
        utilisateur.setRoles(Set.of(role));
        principal = new UtilisateurPrincipal(utilisateur);

        lenient().when(entrepriseAccessService.verifierAcces(entrepriseId, principal)).thenReturn(entreprise);
    }

    private CompteComptable compte(String numero, String intitule) {
        CompteComptable c = new CompteComptable();
        c.setId(UUID.randomUUID());
        c.setNumero(numero);
        c.setIntitule(intitule);
        return c;
    }

    @Test
    void distingue_banque_caisse_et_mobile_money_par_heuristique() {
        CompteComptable banque = compte("521000", "Banque BOA");
        CompteComptable caisse = compte("571000", "Caisse principale");
        CompteComptable mobileMoney = compte("522000", "Orange Money");
        CompteComptable compteClient = compte("411000", "Clients"); // classe 4, ne doit pas apparaitre

        when(compteComptableRepository.findByEntrepriseId(entrepriseId))
                .thenReturn(List.of(banque, caisse, mobileMoney, compteClient));
        when(ligneEcritureRepository.soldeNetJusquau(eq(banque.getId()), any())).thenReturn(new BigDecimal("8200000"));
        when(ligneEcritureRepository.soldeNetJusquau(eq(caisse.getId()), any())).thenReturn(new BigDecimal("800000"));
        when(ligneEcritureRepository.soldeNetJusquau(eq(mobileMoney.getId()), any())).thenReturn(new BigDecimal("2100000"));

        CashPositionDto position = cashPositionService.generer(entrepriseId, LocalDate.now(), principal);

        assertThat(position.parCompte()).hasSize(3);
        assertThat(position.total()).isEqualByComparingTo("11100000");
        assertThat(position.parCompte().stream().filter(p -> p.compteNumero().equals("522000")).findFirst().orElseThrow().canal())
                .isEqualTo("MOBILE_MONEY");
        assertThat(position.parCompte().stream().filter(p -> p.compteNumero().equals("571000")).findFirst().orElseThrow().canal())
                .isEqualTo("CAISSE");
        assertThat(position.parCompte().stream().filter(p -> p.compteNumero().equals("521000")).findFirst().orElseThrow().canal())
                .isEqualTo("BANQUE");
    }

    @Test
    void utilise_la_date_du_jour_par_defaut() {
        when(compteComptableRepository.findByEntrepriseId(entrepriseId)).thenReturn(List.of());

        CashPositionDto position = cashPositionService.generer(entrepriseId, null, principal);

        assertThat(position.dateReference()).isEqualTo(LocalDate.now());
        assertThat(position.total()).isEqualByComparingTo(BigDecimal.ZERO);
    }
}
