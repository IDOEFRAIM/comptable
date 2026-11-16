package com.example.comptable.admin;

import com.example.comptable.audit.AuditTrailService;
import com.example.comptable.identite.domain.Role;
import com.example.comptable.identite.domain.Utilisateur;
import com.example.comptable.identite.repository.RoleRepository;
import com.example.comptable.identite.repository.UtilisateurRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** Amorçage du premier administrateur : une seule fois, jamais avec un mot de passe faible, jamais si un administrateur existe déjà. */
class AdminAmorcageTest {

    private UtilisateurRepository utilisateurs;
    private AuditTrailService audit;
    private PasswordEncoder encodeur;
    private AdminAmorcage amorcage;

    @BeforeEach
    void preparer() {
        utilisateurs = mock(UtilisateurRepository.class);
        RoleRepository roles = mock(RoleRepository.class);
        Role role = new Role();
        role.setCode("SUPER_ADMIN");
        when(roles.findByCode("SUPER_ADMIN")).thenReturn(Optional.of(role));
        audit = mock(AuditTrailService.class);
        // la persistance donne son identifiant à l'entité (ici simulée)
        when(utilisateurs.save(any())).thenAnswer(a -> {
            Utilisateur u = a.getArgument(0);
            ReflectionTestUtils.setField(u, "id", java.util.UUID.randomUUID());
            return u;
        });
        encodeur = new BCryptPasswordEncoder();
        amorcage = new AdminAmorcage(utilisateurs, roles, encodeur, audit);
        ReflectionTestUtils.setField(amorcage, "email", "Admin@Warimara.Test ");
        ReflectionTestUtils.setField(amorcage, "motDePasse", "Provisoire-2026-xyz");
    }

    @Test
    void creeLePremierAdministrateur_sansCabinet_avecChangementDeMotDePasseObligatoire() {
        amorcage.amorcer();
        ArgumentCaptor<Utilisateur> cree = ArgumentCaptor.forClass(Utilisateur.class);
        verify(utilisateurs).save(cree.capture());
        Utilisateur u = cree.getValue();
        assertThat(u.getEmail()).isEqualTo("admin@warimara.test");
        assertThat(u.getTenant()).isNull();
        assertThat(u.isMustChangePassword()).isTrue();
        assertThat(u.getRoles()).extracting(Role::getCode).containsExactly("SUPER_ADMIN");
        assertThat(encodeur.matches("Provisoire-2026-xyz", u.getMotDePasse())).as("mot de passe stocké haché").isTrue();
        verify(audit).enregistrerEvenementSysteme(eq(null), eq("ADMIN_AMORCE"), any(), any(), any());
    }

    @Test
    void neFaitRien_sansVariables() {
        ReflectionTestUtils.setField(amorcage, "motDePasse", "");
        amorcage.amorcer();
        ReflectionTestUtils.setField(amorcage, "email", "");
        ReflectionTestUtils.setField(amorcage, "motDePasse", "Provisoire-2026-xyz");
        amorcage.amorcer();
        verify(utilisateurs, never()).save(any());
    }

    @Test
    void neFaitRien_siUnAdministrateurExisteDeja_ouSiLAdresseEstPrise() {
        when(utilisateurs.existsByRoleCode("SUPER_ADMIN")).thenReturn(true);
        amorcage.amorcer();
        verify(utilisateurs, never()).save(any());

        when(utilisateurs.existsByRoleCode("SUPER_ADMIN")).thenReturn(false);
        when(utilisateurs.existsByEmail("admin@warimara.test")).thenReturn(true);
        amorcage.amorcer();
        verify(utilisateurs, never()).save(any());
    }

    @Test
    void refuseUnMotDePasseFaible() {
        ReflectionTestUtils.setField(amorcage, "motDePasse", "court");
        amorcage.amorcer();
        verify(utilisateurs, never()).save(any());
    }
}
