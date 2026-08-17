package com.example.comptable.entreprise.service;

import com.example.comptable.entreprise.domain.Entreprise;
import com.example.comptable.entreprise.domain.UtilisateurEntreprise;
import com.example.comptable.entreprise.repository.UtilisateurEntrepriseRepository;
import com.example.comptable.entreprise.web.dto.InviterUtilisateurRequest;
import com.example.comptable.entreprise.web.dto.RoleInvitable;
import com.example.comptable.entreprise.web.dto.UtilisateurInviteDto;
import com.example.comptable.identite.domain.Role;
import com.example.comptable.identite.domain.Tenant;
import com.example.comptable.identite.domain.Utilisateur;
import com.example.comptable.identite.repository.RoleRepository;
import com.example.comptable.identite.repository.UtilisateurRepository;
import com.example.comptable.security.UtilisateurPrincipal;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.web.server.ResponseStatusException;

import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class InvitationServiceTest {

    @Mock
    private EntrepriseAccessService entrepriseAccessService;
    @Mock
    private UtilisateurRepository utilisateurRepository;
    @Mock
    private RoleRepository roleRepository;
    @Mock
    private UtilisateurEntrepriseRepository utilisateurEntrepriseRepository;
    @Mock
    private PasswordEncoder passwordEncoder;

    private InvitationService invitationService;

    private UUID entrepriseId;
    private Entreprise entreprise;
    private UtilisateurPrincipal principal;

    @BeforeEach
    void setUp() {
        invitationService = new InvitationService(entrepriseAccessService, utilisateurRepository, roleRepository,
                utilisateurEntrepriseRepository, passwordEncoder);

        entrepriseId = UUID.randomUUID();
        Tenant tenant = new Tenant();
        tenant.setId(UUID.randomUUID());
        entreprise = new Entreprise();
        entreprise.setId(entrepriseId);
        entreprise.setTenant(tenant);

        Role roleExpert = new Role();
        roleExpert.setCode("EXPERT_COMPTABLE");
        Utilisateur utilisateur = new Utilisateur();
        utilisateur.setId(UUID.randomUUID());
        utilisateur.setEmail("cabinet@test.ci");
        utilisateur.setMotDePasse("hash");
        utilisateur.setNom("Nom");
        utilisateur.setPrenom("Prenom");
        utilisateur.setRoles(Set.of(roleExpert));
        principal = new UtilisateurPrincipal(utilisateur);

        when(entrepriseAccessService.verifierAcces(entrepriseId, principal)).thenReturn(entreprise);
    }

    @Test
    void invite_un_client_et_le_rattache_a_lentreprise() {
        Role roleClient = new Role();
        roleClient.setCode("CLIENT");
        when(utilisateurRepository.existsByEmail("client@test.ci")).thenReturn(false);
        when(roleRepository.findByCode("CLIENT")).thenReturn(Optional.of(roleClient));
        when(passwordEncoder.encode(any())).thenReturn("hash-temp");
        when(utilisateurRepository.save(any())).thenAnswer(inv -> {
            Utilisateur u = inv.getArgument(0);
            u.setId(UUID.randomUUID());
            return u;
        });

        InviterUtilisateurRequest request = new InviterUtilisateurRequest(
                "client@test.ci", "Kone", "Awa", null, RoleInvitable.CLIENT);

        UtilisateurInviteDto resultat = invitationService.inviter(entrepriseId, request, principal);

        assertThat(resultat.email()).isEqualTo("client@test.ci");
        assertThat(resultat.role()).isEqualTo("CLIENT");
        assertThat(resultat.motDePasseTemporaire()).hasSize(12);

        ArgumentCaptor<UtilisateurEntreprise> captor = ArgumentCaptor.forClass(UtilisateurEntreprise.class);
        verify(utilisateurEntrepriseRepository).save(captor.capture());
        assertThat(captor.getValue().getId().getEntrepriseId()).isEqualTo(entrepriseId);
    }

    @Test
    void invite_un_collaborateur_sans_le_rattacher_a_lentreprise() {
        Role roleCollab = new Role();
        roleCollab.setCode("COLLABORATEUR");
        when(utilisateurRepository.existsByEmail("collab@test.ci")).thenReturn(false);
        when(roleRepository.findByCode("COLLABORATEUR")).thenReturn(Optional.of(roleCollab));
        when(passwordEncoder.encode(any())).thenReturn("hash-temp");
        when(utilisateurRepository.save(any())).thenAnswer(inv -> {
            Utilisateur u = inv.getArgument(0);
            u.setId(UUID.randomUUID());
            return u;
        });

        InviterUtilisateurRequest request = new InviterUtilisateurRequest(
                "collab@test.ci", "Martin", "Sophie", "+225 01 02 03 04", RoleInvitable.COLLABORATEUR);

        UtilisateurInviteDto resultat = invitationService.inviter(entrepriseId, request, principal);

        assertThat(resultat.role()).isEqualTo("COLLABORATEUR");
        verify(utilisateurEntrepriseRepository, never()).save(any());
    }

    @Test
    void refuse_un_email_deja_utilise() {
        when(utilisateurRepository.existsByEmail("existe@test.ci")).thenReturn(true);

        InviterUtilisateurRequest request = new InviterUtilisateurRequest(
                "existe@test.ci", "Nom", "Prenom", null, RoleInvitable.CLIENT);

        assertThatThrownBy(() -> invitationService.inviter(entrepriseId, request, principal))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("deja utilise");
    }

    @Test
    void echoue_si_le_role_nest_pas_seede() {
        when(utilisateurRepository.existsByEmail("client@test.ci")).thenReturn(false);
        when(roleRepository.findByCode("CLIENT")).thenReturn(Optional.empty());

        InviterUtilisateurRequest request = new InviterUtilisateurRequest(
                "client@test.ci", "Kone", "Awa", null, RoleInvitable.CLIENT);

        assertThatThrownBy(() -> invitationService.inviter(entrepriseId, request, principal))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("seed manquant");
    }
}
