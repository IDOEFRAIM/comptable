package com.example.comptable.fiscalite.conformite;

import com.example.comptable.agent.finding.domain.StatutFinding;
import com.example.comptable.agent.finding.domain.TypeFinding;
import com.example.comptable.agent.finding.repository.FindingRepository;
import com.example.comptable.agent.finding.web.dto.FindingDto;
import com.example.comptable.dossier.DossierComptableService;
import com.example.comptable.dossier.web.dto.DossierComptableDto;
import com.example.comptable.dossier.web.dto.ScoreDomaineDto;
import com.example.comptable.entreprise.service.EntrepriseAccessService;
import com.example.comptable.fiscalite.conformite.web.dto.ComplianceCenterDto;
import com.example.comptable.fiscalite.conformite.web.dto.ObligationFiscaleDto;
import com.example.comptable.fiscalite.conformite.web.dto.TaxHealthDto;
import com.example.comptable.fiscalite.repository.DeclarationFiscaleRepository;
import com.example.comptable.security.UtilisateurPrincipal;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.UUID;

/**
 * "Compliance Center" + "Tax Health" (mission P1, sections 30, 33) : deux agregations de lecture
 * sur des donnees deja existantes en P0 - {@code DeclarationFiscale} (deja modelise), les
 * {@code Finding} de type TAX_REVIEW (deja produits par {@code ControlEngine}), et le score de
 * domaine "Fiscalite" du Dossier Comptable Intelligent. Aucune nouvelle logique de calcul.
 */
@Service
@RequiredArgsConstructor
public class ComplianceCenterService {

    private final EntrepriseAccessService entrepriseAccessService;
    private final DeclarationFiscaleRepository declarationFiscaleRepository;
    private final FindingRepository findingRepository;
    private final DossierComptableService dossierComptableService;

    @Transactional(readOnly = true)
    public ComplianceCenterDto genererCentreConformite(UUID entrepriseId, UtilisateurPrincipal principal) {
        entrepriseAccessService.verifierAcces(entrepriseId, principal);

        List<ObligationFiscaleDto> obligations = declarationFiscaleRepository
                .findByEntrepriseIdOrderByDateEcheanceAsc(entrepriseId)
                .stream().map(ObligationFiscaleDto::from).toList();

        List<FindingDto> anomalies = findingRepository
                .findByEntrepriseIdAndStatusOrderBySeverityAscCreatedAtDesc(entrepriseId, StatutFinding.OPEN)
                .stream()
                .filter(f -> f.getType() == TypeFinding.TAX_REVIEW)
                .map(FindingDto::from)
                .toList();

        return new ComplianceCenterDto(obligations, anomalies);
    }

    @Transactional(readOnly = true)
    public TaxHealthDto genererTaxHealth(UUID entrepriseId, UtilisateurPrincipal principal) {
        DossierComptableDto dossier = dossierComptableService.generer(entrepriseId, principal);
        int score = dossier.scoresParDomaine().stream()
                .filter(s -> "Fiscalite".equals(s.domaine()))
                .findFirst()
                .map(ScoreDomaineDto::score)
                .orElse(100);
        return new TaxHealthDto(score);
    }
}
