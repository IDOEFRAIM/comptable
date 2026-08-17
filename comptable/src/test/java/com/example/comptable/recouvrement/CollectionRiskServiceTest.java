package com.example.comptable.recouvrement;

import com.example.comptable.comptabilite.service.BalanceAgeeService;
import com.example.comptable.comptabilite.web.dto.BalanceAgeeDto;
import com.example.comptable.comptabilite.web.dto.BalanceAgeeLigneDto;
import com.example.comptable.entreprise.domain.Entreprise;
import com.example.comptable.entreprise.service.EntrepriseAccessService;
import com.example.comptable.identite.domain.Role;
import com.example.comptable.identite.domain.Utilisateur;
import com.example.comptable.recouvrement.web.dto.CollectionRiskDto;
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
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class CollectionRiskServiceTest {

    @Mock
    private EntrepriseAccessService entrepriseAccessService;
    @Mock
    private BalanceAgeeService balanceAgeeService;

    @InjectMocks
    private CollectionRiskService collectionRiskService;

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
        utilisateur.setNom("Bassole");
        utilisateur.setPrenom("Josephine");
        utilisateur.setRoles(Set.of(role));
        principal = new UtilisateurPrincipal(utilisateur);

        when(entrepriseAccessService.verifierAcces(entrepriseId, principal)).thenReturn(entreprise);
    }

    @Test
    void classe_les_clients_par_niveau_de_risque_selon_lanciennete() {
        BalanceAgeeDto balanceAgee = new BalanceAgeeDto(LocalDate.now(), List.of(
                new BalanceAgeeLigneDto(UUID.randomUUID(), "Client Recent", "CLIENT",
                        new BigDecimal("100000"), BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, new BigDecimal("100000")),
                new BalanceAgeeLigneDto(UUID.randomUUID(), "Client Moyen", "CLIENT",
                        BigDecimal.ZERO, new BigDecimal("200000"), BigDecimal.ZERO, BigDecimal.ZERO, new BigDecimal("200000")),
                new BalanceAgeeLigneDto(UUID.randomUUID(), "Client Ancien", "CLIENT",
                        BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, new BigDecimal("300000"), new BigDecimal("300000")),
                new BalanceAgeeLigneDto(UUID.randomUUID(), "Fournisseur XYZ", "FOURNISSEUR",
                        new BigDecimal("-50000"), BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, new BigDecimal("-50000"))
        ), new BigDecimal("550000"));
        when(balanceAgeeService.generer(entrepriseId, LocalDate.now(), principal)).thenReturn(balanceAgee);

        List<CollectionRiskDto> risques = collectionRiskService.generer(entrepriseId, principal);

        // Le fournisseur (solde negatif = dette, pas creance) ne doit jamais apparaitre ici.
        assertThat(risques).hasSize(3);
        assertThat(risques).extracting(CollectionRiskDto::tiersNom).containsExactly("Client Ancien", "Client Moyen", "Client Recent");
        assertThat(risques.get(0).niveauRisque()).isEqualTo("HIGH");
        assertThat(risques.get(1).niveauRisque()).isEqualTo("MEDIUM");
        assertThat(risques.get(2).niveauRisque()).isEqualTo("LOW");
    }

    @Test
    void aucun_client_avec_solde_du_ne_produit_une_liste_vide() {
        when(balanceAgeeService.generer(any(), any(), any()))
                .thenReturn(new BalanceAgeeDto(LocalDate.now(), List.of(), BigDecimal.ZERO));

        assertThat(collectionRiskService.generer(entrepriseId, principal)).isEmpty();
    }
}
