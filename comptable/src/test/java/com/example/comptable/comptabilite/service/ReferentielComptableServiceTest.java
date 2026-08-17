package com.example.comptable.comptabilite.service;

import com.example.comptable.comptabilite.domain.CompteComptable;
import com.example.comptable.comptabilite.repository.CompteComptableRepository;
import com.example.comptable.comptabilite.repository.ExerciceComptableRepository;
import com.example.comptable.comptabilite.repository.JournalRepository;
import com.example.comptable.comptabilite.web.dto.CompteComptableDto;
import com.example.comptable.comptabilite.web.dto.CreerCompteRequest;
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

import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ReferentielComptableServiceTest {

    @Mock
    private EntrepriseAccessService entrepriseAccessService;
    @Mock
    private CompteComptableRepository compteComptableRepository;
    @Mock
    private JournalRepository journalRepository;
    @Mock
    private ExerciceComptableRepository exerciceComptableRepository;

    @InjectMocks
    private ReferentielComptableService referentielComptableService;

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
    }

    @Test
    void cree_un_compte_general_personnalise_sans_reference_syscohada() {
        when(entrepriseAccessService.verifierAcces(entrepriseId, principal)).thenReturn(entreprise);
        when(compteComptableRepository.findByEntrepriseIdAndNumero(entrepriseId, "6588")).thenReturn(Optional.empty());
        when(compteComptableRepository.save(any())).thenAnswer(inv -> {
            CompteComptable c = inv.getArgument(0);
            c.setId(UUID.randomUUID());
            return c;
        });

        CreerCompteRequest request = new CreerCompteRequest("6588", "Charges diverses analytiques", false);
        CompteComptableDto resultat = referentielComptableService.creerCompte(entrepriseId, request, principal);

        assertThat(resultat.numero()).isEqualTo("6588");
        assertThat(resultat.intitule()).isEqualTo("Charges diverses analytiques");
        assertThat(resultat.nature()).isNull(); // pas de reference SYSCOHADA : nature indeterminee
    }

    @Test
    void refuse_un_numero_de_compte_deja_utilise_par_lentreprise() {
        when(entrepriseAccessService.verifierAcces(entrepriseId, principal)).thenReturn(entreprise);
        CompteComptable existant = new CompteComptable();
        existant.setNumero("6588");
        when(compteComptableRepository.findByEntrepriseIdAndNumero(entrepriseId, "6588")).thenReturn(Optional.of(existant));

        CreerCompteRequest request = new CreerCompteRequest("6588", "Doublon", false);

        assertThatThrownBy(() -> referentielComptableService.creerCompte(entrepriseId, request, principal))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("deja");
    }
}
