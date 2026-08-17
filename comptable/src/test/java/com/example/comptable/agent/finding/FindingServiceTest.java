package com.example.comptable.agent.finding;

import com.example.comptable.agent.finding.domain.Finding;
import com.example.comptable.agent.finding.domain.SeveriteFinding;
import com.example.comptable.agent.finding.domain.StatutFinding;
import com.example.comptable.agent.finding.domain.TypeFinding;
import com.example.comptable.agent.finding.repository.FindingRepository;
import com.example.comptable.agent.finding.web.dto.FindingDto;
import com.example.comptable.audit.AuditTrailService;
import com.example.comptable.entreprise.domain.Entreprise;
import com.example.comptable.entreprise.service.EntrepriseAccessService;
import com.example.comptable.identite.domain.Role;
import com.example.comptable.identite.domain.Tenant;
import com.example.comptable.identite.domain.Utilisateur;
import com.example.comptable.identite.repository.UtilisateurRepository;
import com.example.comptable.notification.domain.TypeNotification;
import com.example.comptable.notification.service.NotificationService;
import com.example.comptable.security.UtilisateurPrincipal;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.web.server.ResponseStatusException;

import java.time.LocalDate;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** Mission P1 (section 11) : couverture du workflow collaboratif ajoute a FindingService, la
 * seule mecanique existante etait deja verifiee indirectement via P0CoreIntelligentE2ETest. */
@ExtendWith(MockitoExtension.class)
class FindingServiceTest {

    @Mock
    private FindingRepository findingRepository;
    @Mock
    private EntrepriseAccessService entrepriseAccessService;
    @Mock
    private UtilisateurRepository utilisateurRepository;
    @Mock
    private AuditTrailService auditTrailService;
    @Mock
    private NotificationService notificationService;

    @InjectMocks
    private FindingService findingService;

    private UUID entrepriseId;
    private Entreprise entreprise;
    private UtilisateurPrincipal principal;
    private Finding finding;
    private Tenant tenantDuCabinet;

    @BeforeEach
    void setUp() {
        entrepriseId = UUID.randomUUID();
        entreprise = new Entreprise();
        entreprise.setId(entrepriseId);

        tenantDuCabinet = new Tenant();
        tenantDuCabinet.setId(UUID.randomUUID());

        Role role = new Role();
        role.setCode("EXPERT_COMPTABLE");
        Utilisateur utilisateur = new Utilisateur();
        utilisateur.setId(UUID.randomUUID());
        utilisateur.setEmail("expert@cabinet.ci");
        utilisateur.setMotDePasse("hash");
        utilisateur.setNom("Ouedraogo");
        utilisateur.setPrenom("Fatou");
        utilisateur.setTenant(tenantDuCabinet);
        utilisateur.setRoles(Set.of(role));
        principal = new UtilisateurPrincipal(utilisateur);

        finding = new Finding();
        finding.setId(UUID.randomUUID());
        finding.setEntreprise(entreprise);
        finding.setType(TypeFinding.MISSING_EVIDENCE);
        finding.setSeverity(SeveriteFinding.IMPORTANT);
        finding.setSourceType("LIGNE_RELEVE");
        finding.setSourceId("ligne-1");
        finding.setTitle("Justificatif manquant");
        finding.setStatus(StatutFinding.OPEN);

        lenient().when(entrepriseAccessService.verifierAcces(entrepriseId, principal)).thenReturn(entreprise);
        lenient().when(findingRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));
    }

    @Test
    void assigner_un_finding_a_un_collaborateur_du_meme_tenant_notifie_et_trace_laudit() {
        Utilisateur collaborateur = new Utilisateur();
        collaborateur.setId(UUID.randomUUID());
        collaborateur.setEmail("collab@cabinet.ci");
        collaborateur.setNom("Sawadogo");
        collaborateur.setPrenom("Sophie");
        collaborateur.setTenant(tenantDuCabinet);

        when(findingRepository.findById(finding.getId())).thenReturn(Optional.of(finding));
        when(utilisateurRepository.findById(collaborateur.getId())).thenReturn(Optional.of(collaborateur));

        LocalDate echeance = LocalDate.now().plusDays(3);
        FindingDto resultat = findingService.assigner(entrepriseId, finding.getId(), collaborateur.getId(), echeance, principal);

        assertThat(resultat.assignedToId()).isEqualTo(collaborateur.getId());
        assertThat(resultat.dueDate()).isEqualTo(echeance);
        verify(notificationService).notifier(eq(collaborateur), eq(TypeNotification.FINDING_ASSIGNE), any());
        verify(auditTrailService).enregistrerActionHumaine(any(), any(), any(), any(), any(), any());
    }

    /** Bug reel trouve par audit (mission P1.5, section 78) : un collaborateur pouvait assigner
     * un Finding a un utilisateur d'un AUTRE tenant, faisant fuiter le titre du Finding dans la
     * notification d'un cabinet etranger. Corrige : l'assignation doit echouer proprement. */
    @Test
    void assigner_a_un_utilisateur_dun_autre_tenant_echoue_sans_notifier() {
        Tenant autreTenant = new Tenant();
        autreTenant.setId(UUID.randomUUID());
        Utilisateur utilisateurEtranger = new Utilisateur();
        utilisateurEtranger.setId(UUID.randomUUID());
        utilisateurEtranger.setEmail("etranger@autre-cabinet.ci");
        utilisateurEtranger.setNom("Inconnu");
        utilisateurEtranger.setPrenom("Autre");
        utilisateurEtranger.setTenant(autreTenant);

        when(findingRepository.findById(finding.getId())).thenReturn(Optional.of(finding));
        when(utilisateurRepository.findById(utilisateurEtranger.getId())).thenReturn(Optional.of(utilisateurEtranger));

        assertThatThrownBy(() -> findingService.assigner(
                entrepriseId, finding.getId(), utilisateurEtranger.getId(), null, principal))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("introuvable");

        verify(notificationService, never()).notifier(any(), any(), any());
        verify(findingRepository, never()).save(any());
    }

    @Test
    void assigner_a_un_utilisateur_sans_tenant_echoue() {
        Utilisateur utilisateurSansTenant = new Utilisateur();
        utilisateurSansTenant.setId(UUID.randomUUID());
        utilisateurSansTenant.setEmail("super-admin@plateforme.ci");

        when(findingRepository.findById(finding.getId())).thenReturn(Optional.of(finding));
        when(utilisateurRepository.findById(utilisateurSansTenant.getId())).thenReturn(Optional.of(utilisateurSansTenant));

        assertThatThrownBy(() -> findingService.assigner(
                entrepriseId, finding.getId(), utilisateurSansTenant.getId(), null, principal))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("introuvable");
    }

    @Test
    void assigner_a_personne_desassigne_sans_notifier() {
        finding.setAssignedTo(new Utilisateur());
        when(findingRepository.findById(finding.getId())).thenReturn(Optional.of(finding));

        FindingDto resultat = findingService.assigner(entrepriseId, finding.getId(), null, null, principal);

        assertThat(resultat.assignedToId()).isNull();
        verify(notificationService, never()).notifier(any(), any(), any());
    }

    @Test
    void assigner_un_finding_introuvable_echoue() {
        when(findingRepository.findById(finding.getId())).thenReturn(Optional.empty());

        assertThatThrownBy(() -> findingService.assigner(entrepriseId, finding.getId(), null, null, principal))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("introuvable");
    }

    @Test
    void resoudre_trace_laudit_avec_la_source_humaine() {
        when(findingRepository.findById(finding.getId())).thenReturn(Optional.of(finding));
        when(utilisateurRepository.getReferenceById(principal.getId())).thenReturn(new Utilisateur());

        findingService.resoudre(entrepriseId, finding.getId(), principal);

        verify(auditTrailService).enregistrerActionHumaine(
                eq(principal.getTenantId()), eq(principal.getId()), eq("FINDING_RESOLU"), eq("FINDING"),
                eq(finding.getId().toString()), any());
    }
}
