package com.example.comptable.fiscalite.conformite;

import com.example.comptable.agent.finding.domain.Finding;
import com.example.comptable.agent.finding.domain.SeveriteFinding;
import com.example.comptable.agent.finding.domain.StatutFinding;
import com.example.comptable.agent.finding.domain.TypeFinding;
import com.example.comptable.agent.finding.repository.FindingRepository;
import com.example.comptable.dossier.DossierComptableService;
import com.example.comptable.dossier.web.dto.DossierComptableDto;
import com.example.comptable.dossier.web.dto.ScoreDomaineDto;
import com.example.comptable.entreprise.domain.Entreprise;
import com.example.comptable.entreprise.service.EntrepriseAccessService;
import com.example.comptable.fiscalite.conformite.web.dto.ComplianceCenterDto;
import com.example.comptable.fiscalite.conformite.web.dto.TaxHealthDto;
import com.example.comptable.fiscalite.domain.DeclarationFiscale;
import com.example.comptable.fiscalite.domain.StatutDeclaration;
import com.example.comptable.fiscalite.domain.TypeImpot;
import com.example.comptable.fiscalite.repository.DeclarationFiscaleRepository;
import com.example.comptable.identite.domain.Role;
import com.example.comptable.identite.domain.Utilisateur;
import com.example.comptable.security.UtilisateurPrincipal;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
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
class ComplianceCenterServiceTest {

    @Mock
    private EntrepriseAccessService entrepriseAccessService;
    @Mock
    private DeclarationFiscaleRepository declarationFiscaleRepository;
    @Mock
    private FindingRepository findingRepository;
    @Mock
    private DossierComptableService dossierComptableService;
    @Mock
    private com.example.comptable.fiscalite.core.TaxFrameworkService cadres;

    @InjectMocks
    private ComplianceCenterService complianceCenterService;

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
        utilisateur.setNom("Sanou");
        utilisateur.setPrenom("Aicha");
        utilisateur.setRoles(Set.of(role));
        principal = new UtilisateurPrincipal(utilisateur);

        org.mockito.Mockito.lenient().when(entrepriseAccessService.verifierAcces(entrepriseId, principal)).thenReturn(entreprise);
    }

    @Test
    void agrege_declarations_et_anomalies_tax_review() {
        DeclarationFiscale tva = new DeclarationFiscale();
        tva.setId(UUID.randomUUID());
        tva.setTypeImpot(TypeImpot.TVA);
        tva.setPeriode("2026-07");
        tva.setDateEcheance(LocalDate.of(2026, 8, 15));
        tva.setStatut(StatutDeclaration.A_FAIRE);
        tva.setMontant(new BigDecimal("450000"));
        when(declarationFiscaleRepository.findByEntrepriseIdOrderByDateEcheanceAsc(entrepriseId))
                .thenReturn(List.of(tva));

        Finding anomalie = new Finding();
        anomalie.setId(UUID.randomUUID());
        anomalie.setEntreprise(new Entreprise());
        anomalie.setType(TypeFinding.TAX_REVIEW);
        anomalie.setSeverity(SeveriteFinding.IMPORTANT);
        anomalie.setControlCode("TAX_DECLARATION_MISSING");
        anomalie.setCategory(com.example.comptable.controle.ControlCategory.TAX);
        anomalie.setControlSeverity(com.example.comptable.controle.ControlSeverity.WARNING);
        anomalie.setLastDetectedAt(java.time.OffsetDateTime.now());
        anomalie.setSourceType("DECLARATION_FISCALE");
        anomalie.setSourceId("TVA-2026-06");
        anomalie.setTitle("Declaration TVA manquante");
        anomalie.setStatus(StatutFinding.OPEN);
        anomalie.setCreatedAt(OffsetDateTime.now());
        when(findingRepository.findByEntrepriseIdAndStatusOrderBySeverityAscCreatedAtDesc(entrepriseId, StatutFinding.OPEN))
                .thenReturn(List.of(anomalie));

        ComplianceCenterDto conformite = complianceCenterService.genererCentreConformite(entrepriseId, principal);

        assertThat(conformite.obligations()).hasSize(1);
        assertThat(conformite.obligations().get(0).typeImpot()).isEqualTo("TVA");
        assertThat(conformite.anomalies()).hasSize(1);
        assertThat(conformite.anomalies().get(0).title()).isEqualTo("Declaration TVA manquante");
    }

    private Entreprise entrepriseDuBurkina() {
        Entreprise e = new Entreprise();
        e.setId(entrepriseId);
        e.setPays("BF");
        com.example.comptable.identite.domain.Tenant tenant = new com.example.comptable.identite.domain.Tenant();
        tenant.setId(UUID.randomUUID());
        e.setTenant(tenant);
        org.mockito.Mockito.lenient().when(entrepriseAccessService.verifierAcces(entrepriseId, principal)).thenReturn(e);
        return e;
    }

    @Test
    void sans_cadre_fiscal_verifie_la_conformite_est_non_evaluee_jamais_un_score_parfait() {
        entrepriseDuBurkina();
        when(cadres.resoudre(any(), eq("BF"), eq(com.example.comptable.fiscalite.core.TaxFrameworkService.TVA), any())).thenReturn(java.util.Optional.empty());

        TaxHealthDto sante = complianceCenterService.genererTaxHealth(entrepriseId, principal);

        assertThat(sante.evalue()).isFalse();
        assertThat(sante.motif()).contains("Non évalué").contains("cadre fiscal vérifié");
    }

    @Test
    void cadre_fiscal_non_verifie_est_aussi_non_evalue() {
        entrepriseDuBurkina();
        when(cadres.resoudre(any(), eq("BF"), eq(com.example.comptable.fiscalite.core.TaxFrameworkService.TVA), any()))
                .thenReturn(java.util.Optional.of(new com.example.comptable.fiscalite.core.TaxFrameworkService.Resolution(null, false)));

        assertThat(complianceCenterService.genererTaxHealth(entrepriseId, principal).evalue()).isFalse();
    }

    @Test
    void avec_un_cadre_verifie_le_nombre_reel_d_anomalies_fiscales_ouvertes_est_donne() {
        entrepriseDuBurkina();
        when(cadres.resoudre(any(), eq("BF"), eq(com.example.comptable.fiscalite.core.TaxFrameworkService.TVA), any()))
                .thenReturn(java.util.Optional.of(new com.example.comptable.fiscalite.core.TaxFrameworkService.Resolution(null, true)));
        Finding anomalie = new Finding();
        anomalie.setType(TypeFinding.TAX_REVIEW);
        Finding autre = new Finding();
        autre.setType(TypeFinding.DOCUMENT_REVIEW);
        when(findingRepository.findByEntrepriseIdAndStatusOrderBySeverityAscCreatedAtDesc(entrepriseId, StatutFinding.OPEN)).thenReturn(List.of(anomalie, autre));

        TaxHealthDto sante = complianceCenterService.genererTaxHealth(entrepriseId, principal);

        assertThat(sante.evalue()).isTrue();
        assertThat(sante.anomaliesOuvertes()).isEqualTo(1);
    }
}
