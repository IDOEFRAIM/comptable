package com.example.comptable.stock.service;

import com.example.comptable.entreprise.domain.Entreprise;
import com.example.comptable.entreprise.service.EntrepriseAccessService;
import com.example.comptable.identite.domain.Role;
import com.example.comptable.identite.domain.Utilisateur;
import com.example.comptable.security.UtilisateurPrincipal;
import com.example.comptable.stock.domain.Depot;
import com.example.comptable.stock.repository.DepotRepository;
import com.example.comptable.stock.web.dto.CreerDepotRequest;
import com.example.comptable.stock.web.dto.DepotDto;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class DepotServiceTest {

    @Mock
    private EntrepriseAccessService entrepriseAccessService;
    @Mock
    private DepotRepository depotRepository;

    @InjectMocks
    private DepotService depotService;

    private UUID entrepriseId;
    private Entreprise entreprise;
    private UtilisateurPrincipal principal;

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

        when(entrepriseAccessService.verifierAcces(entrepriseId, principal)).thenReturn(entreprise);
    }

    @Test
    void cree_un_depot() {
        when(depotRepository.findByEntrepriseIdAndCode(entrepriseId, "DEP-01")).thenReturn(Optional.empty());
        when(depotRepository.save(any())).thenAnswer(inv -> {
            Depot d = inv.getArgument(0);
            d.setId(UUID.randomUUID());
            return d;
        });

        DepotDto resultat = depotService.creer(entrepriseId, new CreerDepotRequest("DEP-01", "Entrepot principal"), principal);

        assertThat(resultat.code()).isEqualTo("DEP-01");
        assertThat(resultat.actif()).isTrue();
    }

    @Test
    void refuse_un_code_deja_utilise() {
        Depot existant = new Depot();
        existant.setId(UUID.randomUUID());
        when(depotRepository.findByEntrepriseIdAndCode(entrepriseId, "DEP-01")).thenReturn(Optional.of(existant));

        assertThatThrownBy(() -> depotService.creer(entrepriseId, new CreerDepotRequest("DEP-01", "Doublon"), principal))
                .hasMessageContaining("existe deja");
    }
}
