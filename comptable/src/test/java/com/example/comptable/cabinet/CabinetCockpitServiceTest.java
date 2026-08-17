package com.example.comptable.cabinet;

import com.example.comptable.agent.finding.domain.Finding;
import com.example.comptable.agent.finding.domain.SeveriteFinding;
import com.example.comptable.agent.finding.domain.StatutFinding;
import com.example.comptable.agent.finding.domain.TypeFinding;
import com.example.comptable.agent.finding.repository.FindingRepository;
import com.example.comptable.cabinet.web.dto.CabinetCockpitDto;
import com.example.comptable.demande.domain.StatutDemandePiece;
import com.example.comptable.demande.repository.DemandePieceRepository;
import com.example.comptable.dossier.DossierComptableService;
import com.example.comptable.dossier.web.dto.DossierComptableDto;
import com.example.comptable.dossier.web.dto.ScoreDomaineDto;
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

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class CabinetCockpitServiceTest {

    @Mock
    private EntrepriseRepository entrepriseRepository;
    @Mock
    private DossierComptableService dossierComptableService;
    @Mock
    private FindingRepository findingRepository;
    @Mock
    private DemandePieceRepository demandePieceRepository;

    @InjectMocks
    private CabinetCockpitService cabinetCockpitService;

    private UtilisateurPrincipal principal;
    private UUID tenantId;
    private Entreprise clientSain;
    private Entreprise clientBloque;

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
        utilisateur.setNom("Sana");
        utilisateur.setPrenom("Boureima");
        utilisateur.setTenant(tenant);
        utilisateur.setRoles(Set.of(role));
        principal = new UtilisateurPrincipal(utilisateur);

        clientSain = new Entreprise();
        clientSain.setId(UUID.randomUUID());
        clientSain.setRaisonSociale("SARL Propre");

        clientBloque = new Entreprise();
        clientBloque.setId(UUID.randomUUID());
        clientBloque.setRaisonSociale("SARL En Difficulte");
    }

    @Test
    void agrege_le_portefeuille_sans_dupliquer_lalgorithme_de_score() {
        when(entrepriseRepository.findByTenantId(tenantId)).thenReturn(List.of(clientSain, clientBloque));

        DossierComptableDto dossierSain = new DossierComptableDto("SARL Propre", 100, "PRET",
                List.of(new ScoreDomaineDto("Banque", 100), new ScoreDomaineDto("Cloture", 100)),
                List.of(), List.of());
        DossierComptableDto dossierBloque = new DossierComptableDto("SARL En Difficulte", 40, "BLOQUE",
                List.of(new ScoreDomaineDto("Banque", 40), new ScoreDomaineDto("Cloture", 75)),
                List.of(), List.of());
        when(dossierComptableService.generer(eq(clientSain.getId()), eq(principal))).thenReturn(dossierSain);
        when(dossierComptableService.generer(eq(clientBloque.getId()), eq(principal))).thenReturn(dossierBloque);

        Finding findingCritique = new Finding();
        findingCritique.setId(UUID.randomUUID());
        findingCritique.setEntreprise(clientBloque);
        findingCritique.setType(TypeFinding.TAX_REVIEW);
        findingCritique.setSeverity(SeveriteFinding.CRITICAL);
        findingCritique.setSourceType("DECLARATION_FISCALE");
        findingCritique.setSourceId("TVA-2026-06");
        findingCritique.setTitle("Declaration TVA en retard");
        findingCritique.setStatus(StatutFinding.OPEN);
        findingCritique.setCreatedAt(OffsetDateTime.now());

        when(findingRepository.findByEntrepriseIdInAndStatusOrderBySeverityAscCreatedAtDesc(any(), eq(StatutFinding.OPEN)))
                .thenReturn(List.of(findingCritique));
        when(demandePieceRepository.countByEntrepriseIdInAndStatutAndDateLimiteBefore(
                any(), eq(StatutDemandePiece.EN_ATTENTE), any(LocalDate.class))).thenReturn(2L);

        CabinetCockpitDto cockpit = cabinetCockpitService.generer(principal);

        assertThat(cockpit.totalClients()).isEqualTo(2);
        assertThat(cockpit.clientsNormaux()).isEqualTo(1);
        assertThat(cockpit.clientsBloques()).isEqualTo(1);
        assertThat(cockpit.dossiersATraiter()).isEqualTo(1);
        assertThat(cockpit.demandesEnRetard()).isEqualTo(2);
        assertThat(cockpit.anomaliesFiscales()).isEqualTo(1);
        assertThat(cockpit.cloturesARisque()).isEqualTo(1);
        assertThat(cockpit.topRisques()).hasSize(1);
        assertThat(cockpit.topRisques().get(0).raisonSociale()).isEqualTo("SARL En Difficulte");
        assertThat(cockpit.topRisques().get(0).cause()).isEqualTo("Declaration TVA en retard");
    }

    @Test
    void cabinet_sans_client_ne_leve_aucune_erreur() {
        when(entrepriseRepository.findByTenantId(tenantId)).thenReturn(List.of());

        CabinetCockpitDto cockpit = cabinetCockpitService.generer(principal);

        assertThat(cockpit.totalClients()).isZero();
        assertThat(cockpit.topRisques()).isEmpty();
    }
}
