package com.example.comptable.cabinet;

import com.example.comptable.agent.finding.domain.Finding;
import com.example.comptable.agent.finding.domain.SeveriteFinding;
import com.example.comptable.agent.finding.domain.StatutFinding;
import com.example.comptable.agent.finding.domain.TypeFinding;
import com.example.comptable.agent.finding.repository.FindingRepository;
import com.example.comptable.cabinet.web.dto.CollaborateurDto;
import com.example.comptable.cabinet.web.dto.InboxItemDto;
import com.example.comptable.cabinet.web.dto.WorkloadDto;
import com.example.comptable.demande.domain.DemandePiece;
import com.example.comptable.demande.domain.StatutDemandePiece;
import com.example.comptable.demande.repository.DemandePieceRepository;
import com.example.comptable.entreprise.domain.Entreprise;
import com.example.comptable.entreprise.repository.EntrepriseRepository;
import com.example.comptable.identite.domain.Role;
import com.example.comptable.identite.domain.Tenant;
import com.example.comptable.identite.domain.Utilisateur;
import com.example.comptable.identite.repository.UtilisateurRepository;
import com.example.comptable.security.UtilisateurPrincipal;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class InboxServiceTest {

    @Mock
    private EntrepriseRepository entrepriseRepository;
    @Mock
    private FindingRepository findingRepository;
    @Mock
    private DemandePieceRepository demandePieceRepository;
    @Mock
    private UtilisateurRepository utilisateurRepository;

    @InjectMocks
    private InboxService inboxService;

    private UtilisateurPrincipal principal;
    private Utilisateur sophie;
    private Entreprise entreprise;

    @BeforeEach
    void setUp() {
        UUID tenantId = UUID.randomUUID();
        Tenant tenant = new Tenant();
        tenant.setId(tenantId);

        Role role = new Role();
        role.setCode("EXPERT_COMPTABLE");
        Utilisateur utilisateur = new Utilisateur();
        utilisateur.setId(UUID.randomUUID());
        utilisateur.setEmail("expert@cabinet.ci");
        utilisateur.setMotDePasse("hash");
        utilisateur.setNom("Kabore");
        utilisateur.setPrenom("Rasmane");
        utilisateur.setTenant(tenant);
        utilisateur.setRoles(Set.of(role));
        principal = new UtilisateurPrincipal(utilisateur);

        sophie = new Utilisateur();
        sophie.setId(UUID.randomUUID());
        sophie.setNom("Sawadogo");
        sophie.setPrenom("Sophie");

        entreprise = new Entreprise();
        entreprise.setId(UUID.randomUUID());
        entreprise.setRaisonSociale("SARL Bâtir Plus");

        lenient().when(entrepriseRepository.findByTenantId(tenantId)).thenReturn(List.of(entreprise));
    }

    private Finding findingAssigne(SeveriteFinding severite, Utilisateur assigne) {
        Finding f = new Finding();
        f.setId(UUID.randomUUID());
        f.setEntreprise(entreprise);
        f.setType(TypeFinding.MISSING_EVIDENCE);
        f.setSeverity(severite);
        f.setControlCode("MISSING_DOCUMENT");
        f.setCategory(com.example.comptable.controle.ControlCategory.DOCUMENT);
        f.setControlSeverity(com.example.comptable.controle.ControlSeverity.WARNING);
        f.setLastDetectedAt(OffsetDateTime.now());
        f.setSourceType("LIGNE_RELEVE");
        f.setSourceId("ligne-1");
        f.setTitle("Justificatif manquant");
        f.setStatus(StatutFinding.OPEN);
        f.setCreatedAt(OffsetDateTime.now());
        f.setAssignedTo(assigne);
        return f;
    }

    @Test
    void unifie_findings_et_demandes_dans_une_seule_liste_triee() {
        Finding finding = findingAssigne(SeveriteFinding.CRITICAL, null);
        when(findingRepository.findByEntrepriseIdInAndStatusOrderBySeverityAscCreatedAtDesc(any(), any()))
                .thenReturn(List.of(finding));

        DemandePiece demande = new DemandePiece();
        demande.setId(UUID.randomUUID());
        demande.setEntreprise(entreprise);
        demande.setLibelle("Facture electricite juin");
        demande.setDateLimite(LocalDate.now().minusDays(2));
        demande.setCreatedAt(OffsetDateTime.now().minusDays(3));
        when(demandePieceRepository.findByEntrepriseIdInAndStatutOrderByDateLimiteAsc(any(), any()))
                .thenReturn(List.of(demande));

        List<InboxItemDto> items = inboxService.lister(principal, false);

        assertThat(items).hasSize(2);
        assertThat(items.get(0).itemType()).isEqualTo("FINDING");
        assertThat(items.get(0).severite()).isEqualTo("CRITICAL");
        assertThat(items.get(1).itemType()).isEqualTo("DEMANDE_PIECE");
        assertThat(items.get(1).severite()).isEqualTo("IMPORTANT");
    }

    @Test
    void assigneAMoiSeulement_exclut_les_demandes_et_les_findings_non_assignes() {
        Finding findingAMoi = findingAssigne(SeveriteFinding.IMPORTANT, principal_utilisateur());
        Finding findingAutre = findingAssigne(SeveriteFinding.CRITICAL, sophie);
        when(findingRepository.findByEntrepriseIdInAndStatusOrderBySeverityAscCreatedAtDesc(any(), any()))
                .thenReturn(List.of(findingAutre, findingAMoi));

        List<InboxItemDto> items = inboxService.lister(principal, true);

        assertThat(items).hasSize(1);
        assertThat(items.get(0).assignedToId()).isEqualTo(principal.getId());
    }

    private Utilisateur principal_utilisateur() {
        Utilisateur u = new Utilisateur();
        u.setId(principal.getId());
        u.setNom("Kabore");
        u.setPrenom("Rasmane");
        return u;
    }

    @Test
    void charge_par_collaborateur_regroupe_et_compte_les_critiques() {
        Finding f1 = findingAssigne(SeveriteFinding.CRITICAL, sophie);
        Finding f2 = findingAssigne(SeveriteFinding.REVIEW, sophie);
        when(findingRepository.findByEntrepriseIdInAndStatusOrderBySeverityAscCreatedAtDesc(any(), any()))
                .thenReturn(List.of(f1, f2));

        List<WorkloadDto> charge = inboxService.chargeParCollaborateur(principal);

        assertThat(charge).hasSize(1);
        assertThat(charge.get(0).collaborateurId()).isEqualTo(sophie.getId());
        assertThat(charge.get(0).totalTaches()).isEqualTo(2);
        assertThat(charge.get(0).tachesCritiques()).isEqualTo(1);
    }

    @Test
    void liste_collaborateurs_du_tenant_meme_sans_tache_assignee() {
        when(utilisateurRepository.findByTenantIdAndRoleCodeIn(eq(principal.getTenantId()),
                eq(List.of("COLLABORATEUR", "EXPERT_COMPTABLE"))))
                .thenReturn(List.of(sophie));

        List<CollaborateurDto> collaborateurs = inboxService.listerCollaborateurs(principal);

        assertThat(collaborateurs).hasSize(1);
        assertThat(collaborateurs.get(0).id()).isEqualTo(sophie.getId());
        assertThat(collaborateurs.get(0).nomComplet()).isEqualTo("Sawadogo Sophie");
    }
}
