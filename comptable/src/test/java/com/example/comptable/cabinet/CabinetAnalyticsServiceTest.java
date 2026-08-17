package com.example.comptable.cabinet;

import com.example.comptable.agent.finding.domain.Finding;
import com.example.comptable.agent.finding.domain.SeveriteFinding;
import com.example.comptable.agent.finding.domain.StatutFinding;
import com.example.comptable.agent.finding.domain.TypeFinding;
import com.example.comptable.agent.finding.repository.FindingRepository;
import com.example.comptable.cabinet.web.dto.CabinetAnalyticsDto;
import com.example.comptable.cabinet.web.dto.CabinetCockpitDto;
import com.example.comptable.demande.domain.StatutDemandePiece;
import com.example.comptable.demande.repository.DemandePieceRepository;
import com.example.comptable.entreprise.domain.Entreprise;
import com.example.comptable.entreprise.repository.EntrepriseRepository;
import com.example.comptable.identite.domain.Role;
import com.example.comptable.identite.domain.Tenant;
import com.example.comptable.identite.domain.Utilisateur;
import com.example.comptable.security.UtilisateurPrincipal;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class CabinetAnalyticsServiceTest {

    @Mock
    private EntrepriseRepository entrepriseRepository;
    @Mock
    private CabinetCockpitService cabinetCockpitService;
    @Mock
    private FindingRepository findingRepository;
    @Mock
    private DemandePieceRepository demandePieceRepository;

    @InjectMocks
    private CabinetAnalyticsService cabinetAnalyticsService;

    private UUID tenantId;
    private UtilisateurPrincipal principal;
    private UUID entrepriseId;

    @BeforeEach
    void setUp() {
        tenantId = UUID.randomUUID();
        Tenant tenant = new Tenant();
        tenant.setId(tenantId);

        Role role = new Role();
        role.setCode("EXPERT_COMPTABLE");
        Utilisateur utilisateur = new Utilisateur();
        utilisateur.setId(UUID.randomUUID());
        utilisateur.setEmail("expert@cabinet.ci");
        utilisateur.setMotDePasse("hash");
        utilisateur.setNom("Ky");
        utilisateur.setPrenom("Etienne");
        utilisateur.setTenant(tenant);
        utilisateur.setRoles(Set.of(role));
        principal = new UtilisateurPrincipal(utilisateur);

        entrepriseId = UUID.randomUUID();
        Entreprise entreprise = new Entreprise();
        entreprise.setId(entrepriseId);
        when(entrepriseRepository.findByTenantId(tenantId)).thenReturn(List.of(entreprise));

        CabinetCockpitDto cockpit = new CabinetCockpitDto(10, 7, 2, 3, 3, 1, 1, 1, 0, List.of(), List.of());
        when(cabinetCockpitService.generer(principal)).thenReturn(cockpit);
    }

    @Test
    void calcule_le_temps_moyen_de_resolution_et_le_taux_de_demandes_resolues() {
        Finding f1 = findingResolu(OffsetDateTime.now().minusDays(4), OffsetDateTime.now().minusDays(2));
        Finding f2 = findingResolu(OffsetDateTime.now().minusDays(10), OffsetDateTime.now().minusDays(6));
        when(findingRepository.findByEntrepriseIdInAndStatus(any(), any())).thenReturn(List.of(f1, f2));

        when(demandePieceRepository.countByEntrepriseIdInAndStatut(any(), org.mockito.ArgumentMatchers.eq(StatutDemandePiece.RECU)))
                .thenReturn(7L);
        when(demandePieceRepository.countByEntrepriseIdInAndStatut(any(), org.mockito.ArgumentMatchers.eq(StatutDemandePiece.EN_ATTENTE)))
                .thenReturn(3L);
        when(demandePieceRepository.countByEntrepriseIdInAndStatut(any(), org.mockito.ArgumentMatchers.eq(StatutDemandePiece.ANNULEE)))
                .thenReturn(0L);

        CabinetAnalyticsDto analytics = cabinetAnalyticsService.generer(principal);

        assertThat(analytics.nombreDossiers()).isEqualTo(10);
        assertThat(analytics.dossiersAJour()).isEqualTo(7);
        assertThat(analytics.dossiersEnRetard()).isEqualTo(3);
        // (2 jours + 4 jours) / 2 = 3 jours
        assertThat(analytics.tempsMoyenResolutionJours()).isCloseTo(3.0, within(0.05));
        // 7 / (7+3+0) * 100 = 70%
        assertThat(analytics.tauxDemandesResoluesPourcent()).isCloseTo(70.0, within(0.01));
    }

    private Finding findingResolu(OffsetDateTime createdAt, OffsetDateTime resolvedAt) {
        Finding f = new Finding();
        f.setId(UUID.randomUUID());
        f.setType(TypeFinding.MISSING_EVIDENCE);
        f.setSeverity(SeveriteFinding.IMPORTANT);
        f.setSourceType("LIGNE_RELEVE");
        f.setSourceId("ligne-1");
        f.setTitle("Justificatif manquant");
        f.setStatus(StatutFinding.RESOLVED);
        f.setCreatedAt(createdAt);
        f.setResolvedAt(resolvedAt);
        return f;
    }
}
