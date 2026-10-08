package com.example.comptable.cabinet;

import com.example.comptable.agent.finding.domain.StatutFinding;
import com.example.comptable.agent.finding.repository.FindingRepository;
import com.example.comptable.cabinet.web.dto.CabinetAnalyticsDto;
import com.example.comptable.cabinet.web.dto.CabinetCockpitDto;
import com.example.comptable.demande.domain.StatutDemandePiece;
import com.example.comptable.demande.repository.DemandePieceRepository;
import com.example.comptable.entreprise.domain.Entreprise;
import com.example.comptable.entreprise.repository.EntrepriseRepository;
import com.example.comptable.security.UtilisateurPrincipal;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.util.List;
import java.util.OptionalDouble;
import java.util.UUID;

/**
 * "Cabinet Analytics" (mission P1, section 36) : KPIs de production reutilisant
 * {@link CabinetCockpitService} pour les compteurs de dossiers (aucune deuxieme agregation du
 * Dossier Comptable) et deux requetes batchees supplementaires pour le temps de resolution et le
 * taux de demandes resolues.
 */
@Service
public class CabinetAnalyticsService {

    private final EntrepriseRepository entrepriseRepository;
    private final CabinetCockpitService cabinetCockpitService;
    private final FindingRepository findingRepository;
    private final DemandePieceRepository demandePieceRepository;

    public CabinetAnalyticsService(EntrepriseRepository entrepriseRepository,
                                    CabinetCockpitService cabinetCockpitService,
                                    FindingRepository findingRepository,
                                    DemandePieceRepository demandePieceRepository) {
        this.entrepriseRepository = entrepriseRepository;
        this.cabinetCockpitService = cabinetCockpitService;
        this.findingRepository = findingRepository;
        this.demandePieceRepository = demandePieceRepository;
    }

    @Transactional(readOnly = true)
    public CabinetAnalyticsDto generer(UtilisateurPrincipal principal) {
        CabinetCockpitDto cockpit = cabinetCockpitService.generer(principal);
        List<UUID> entrepriseIds = entrepriseRepository.findByTenantId(principal.getTenantId()).stream()
                .map(Entreprise::getId).toList();

        Double tempsMoyenResolutionJours = null;
        Double tauxDemandesResolues = null;

        if (!entrepriseIds.isEmpty()) {
            OptionalDouble moyenne = findingRepository.findByEntrepriseIdInAndStatus(entrepriseIds, StatutFinding.RESOLVED)
                    .stream()
                    .filter(f -> f.getResolvedAt() != null)
                    .mapToDouble(f -> Duration.between(f.getCreatedAt(), f.getResolvedAt()).toMinutes() / 1440.0)
                    .average();
            tempsMoyenResolutionJours = moyenne.isPresent() ? moyenne.getAsDouble() : null;

            long resolues = demandePieceRepository.countByEntrepriseIdInAndStatut(entrepriseIds, StatutDemandePiece.RECU)
                    + demandePieceRepository.countByEntrepriseIdInAndStatut(entrepriseIds, StatutDemandePiece.RAPPROCHEE);
            long enAttente = demandePieceRepository.countByEntrepriseIdInAndStatut(entrepriseIds, StatutDemandePiece.EN_ATTENTE);
            long annulees = demandePieceRepository.countByEntrepriseIdInAndStatut(entrepriseIds, StatutDemandePiece.ANNULEE);
            long total = resolues + enAttente + annulees;
            tauxDemandesResolues = total == 0 ? null : (resolues * 100.0) / total;
        }

        return new CabinetAnalyticsDto(cockpit.totalClients(), cockpit.clientsNormaux(), cockpit.clientsBloques(),
                tempsMoyenResolutionJours, tauxDemandesResolues);
    }
}
