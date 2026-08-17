package com.example.comptable.cabinet;

import com.example.comptable.agent.finding.domain.Finding;
import com.example.comptable.agent.finding.domain.SeveriteFinding;
import com.example.comptable.agent.finding.domain.StatutFinding;
import com.example.comptable.agent.finding.domain.TypeFinding;
import com.example.comptable.agent.finding.repository.FindingRepository;
import com.example.comptable.cabinet.web.dto.CabinetCockpitDto;
import com.example.comptable.cabinet.web.dto.ClientHealthDto;
import com.example.comptable.cabinet.web.dto.RisqueClientDto;
import com.example.comptable.demande.domain.StatutDemandePiece;
import com.example.comptable.demande.repository.DemandePieceRepository;
import com.example.comptable.dossier.DossierComptableService;
import com.example.comptable.dossier.web.dto.DossierComptableDto;
import com.example.comptable.entreprise.domain.Entreprise;
import com.example.comptable.entreprise.repository.EntrepriseRepository;
import com.example.comptable.security.UtilisateurPrincipal;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * "Cabinet Cockpit" (mission P1, section 8) : vue portefeuille pour l'expert-comptable/
 * collaborateur - jamais accessible a un CLIENT (l'isolation par tenant suffit ici, un CLIENT
 * n'a de toute facon jamais ce role autorise cote {@code SecurityConfig}). Reutilise
 * integralement {@link DossierComptableService} pour le score de chaque client (aucun second
 * algorithme de score, mission section 9 : "Client Health" = projection du Dossier P0) et une
 * seule requete batchee sur {@link Finding} pour les repartitions/top risques du portefeuille
 * (mission section 54 : eviter un appel IA/calcul complet par client affiche).
 * <p>
 * Limite assumee : {@link DossierComptableService#generer} rafraichit le {@code ControlEngine}
 * (declarations manquantes, documents a risque...) a chaque appel - ce Cockpit l'appelle donc une
 * fois par client du cabinet a chaque ouverture. Correct et sans duplication de logique, mais pas
 * optimal a tres grande echelle (centaines de clients) : un job de rafraichissement planifie
 * (deja recommande en P0) ou une pagination cote UI seraient l'evolution naturelle P2.
 */
@Service
public class CabinetCockpitService {

    private static final int TOP_RISQUES_LIMIT = 10;

    private final EntrepriseRepository entrepriseRepository;
    private final DossierComptableService dossierComptableService;
    private final FindingRepository findingRepository;
    private final DemandePieceRepository demandePieceRepository;

    public CabinetCockpitService(EntrepriseRepository entrepriseRepository,
                                  DossierComptableService dossierComptableService,
                                  FindingRepository findingRepository,
                                  DemandePieceRepository demandePieceRepository) {
        this.entrepriseRepository = entrepriseRepository;
        this.dossierComptableService = dossierComptableService;
        this.findingRepository = findingRepository;
        this.demandePieceRepository = demandePieceRepository;
    }

    @Transactional
    public CabinetCockpitDto generer(UtilisateurPrincipal principal) {
        List<Entreprise> entreprises = entrepriseRepository.findByTenantId(principal.getTenantId());
        List<UUID> entrepriseIds = entreprises.stream().map(Entreprise::getId).toList();
        Map<UUID, String> raisonSocialeParEntrepriseId = entreprises.stream()
                .collect(Collectors.toMap(Entreprise::getId, Entreprise::getRaisonSociale));

        List<ClientHealthDto> clients = new ArrayList<>();
        Map<UUID, DossierComptableDto> dossiersParEntrepriseId = new LinkedHashMap<>();
        for (Entreprise entreprise : entreprises) {
            DossierComptableDto dossier = dossierComptableService.generer(entreprise.getId(), principal);
            dossiersParEntrepriseId.put(entreprise.getId(), dossier);
            clients.add(new ClientHealthDto(entreprise.getId(), entreprise.getRaisonSociale(),
                    dossier.scoreGlobal(), dossier.statut()));
        }

        int clientsNormaux = compter(clients, "PRET");
        int clientsASurveiller = compter(clients, "A_SURVEILLER");
        int clientsBloques = compter(clients, "BLOQUE");

        List<Finding> findingsCabinet = entrepriseIds.isEmpty() ? List.of()
                : findingRepository.findByEntrepriseIdInAndStatusOrderBySeverityAscCreatedAtDesc(
                        entrepriseIds, StatutFinding.OPEN);

        int anomaliesFiscales = (int) findingsCabinet.stream()
                .filter(f -> f.getType() == TypeFinding.TAX_REVIEW)
                .count();
        int rapprochementsCritiques = (int) findingsCabinet.stream()
                .filter(f -> f.getSeverity() == SeveriteFinding.CRITICAL && "LIGNE_RELEVE".equals(f.getSourceType()))
                .count();
        int cloturesARisque = (int) dossiersParEntrepriseId.values().stream()
                .filter(d -> d.scoresParDomaine().stream()
                        .anyMatch(s -> "Cloture".equals(s.domaine()) && s.score() < 100))
                .count();
        long demandesEnRetard = entrepriseIds.isEmpty() ? 0
                : demandePieceRepository.countByEntrepriseIdInAndStatutAndDateLimiteBefore(
                        entrepriseIds, StatutDemandePiece.EN_ATTENTE, LocalDate.now());

        List<RisqueClientDto> topRisques = findingsCabinet.stream()
                .filter(f -> f.getSeverity() == SeveriteFinding.CRITICAL || f.getSeverity() == SeveriteFinding.IMPORTANT)
                .limit(TOP_RISQUES_LIMIT)
                .map(f -> new RisqueClientDto(
                        f.getEntreprise().getId(),
                        raisonSocialeParEntrepriseId.get(f.getEntreprise().getId()),
                        f.getSeverity().name(),
                        f.getTitle(),
                        f.getCreatedAt().toLocalDate().toString(),
                        f.getRecommendedAction()))
                .toList();

        return new CabinetCockpitDto(entreprises.size(), clientsNormaux, clientsASurveiller, clientsBloques,
                clientsASurveiller + clientsBloques, (int) demandesEnRetard, cloturesARisque,
                anomaliesFiscales, rapprochementsCritiques, clients, topRisques);
    }

    private int compter(List<ClientHealthDto> clients, String statut) {
        return (int) clients.stream().filter(c -> statut.equals(c.statut())).count();
    }
}
