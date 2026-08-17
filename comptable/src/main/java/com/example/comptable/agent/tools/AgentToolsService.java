package com.example.comptable.agent.tools;

import com.example.comptable.agent.tools.dto.DemandeClarificationResponseDTO;
import com.example.comptable.agent.tools.dto.DoublonVerificationResponseDTO;
import com.example.comptable.agent.tools.dto.HistoriqueFournisseurDTO;
import com.example.comptable.agent.tools.dto.PieceHistoriqueDTO;
import com.example.comptable.demande.service.DemandePieceService;
import com.example.comptable.demande.web.dto.DemandePieceDto;
import com.example.comptable.document.domain.Document;
import com.example.comptable.document.repository.DocumentRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/**
 * Implementation des 3 outils exposes a l'agent comptable autonome (ai-service,
 * agent_orchestrator.py) via {@link AgentToolsInternalController} : deux outils de lecture
 * (doublon, historique fournisseur) et un outil d'ecriture (demande de clarification). Chaque
 * outil est une lecture/ecriture ciblee, jamais une exposition generale du modele de donnees -
 * l'agent ne recoit que ce dont il a besoin pour statuer.
 */
@Service
@RequiredArgsConstructor
public class AgentToolsService {

    /** Fenetre de recherche de doublon (+/- jours autour de la date de la piece analysee),
     * meme valeur par defaut que {@code agent.fraude.doublon.fenetre-jours} (AnalyseFraudeService). */
    private static final int FENETRE_DOUBLON_JOURS = 3;

    private static final int LIMITE_HISTORIQUE_FOURNISSEUR = 5;

    private final DocumentRepository documentRepository;
    private final DemandePieceService demandePieceService;

    @Transactional(readOnly = true)
    public DoublonVerificationResponseDTO verifierDoublonMontant(UUID entrepriseId, BigDecimal montantTtc,
                                                                    LocalDate datePiece) {
        LocalDate debut = datePiece.minusDays(FENETRE_DOUBLON_JOURS);
        LocalDate fin = datePiece.plusDays(FENETRE_DOUBLON_JOURS);
        List<PieceHistoriqueDTO> piecesSimilaires = documentRepository
                .rechercherDoublonsPotentiels(entrepriseId, montantTtc, debut, fin)
                .stream()
                .map(d -> new PieceHistoriqueDTO(d.getDatePiece(), d.getMontantTtc()))
                .toList();
        return new DoublonVerificationResponseDTO(!piecesSimilaires.isEmpty(), piecesSimilaires);
    }

    @Transactional(readOnly = true)
    public HistoriqueFournisseurDTO consulterHistoriqueFournisseur(UUID entrepriseId, String fournisseur) {
        List<Document> pieces = documentRepository
                .rechercherParFournisseur(entrepriseId, fournisseur, LIMITE_HISTORIQUE_FOURNISSEUR);

        List<PieceHistoriqueDTO> dernieresPieces = pieces.stream()
                .map(d -> new PieceHistoriqueDTO(d.getDatePiece(), d.getMontantTtc()))
                .toList();

        List<BigDecimal> montants = pieces.stream()
                .map(Document::getMontantTtc)
                .filter(Objects::nonNull)
                .toList();
        BigDecimal montantMoyen = montants.isEmpty() ? null
                : montants.stream().reduce(BigDecimal.ZERO, BigDecimal::add)
                        .divide(BigDecimal.valueOf(montants.size()), 2, RoundingMode.HALF_UP);
        BigDecimal montantMin = montants.stream().min(Comparator.naturalOrder()).orElse(null);
        BigDecimal montantMax = montants.stream().max(Comparator.naturalOrder()).orElse(null);

        return new HistoriqueFournisseurDTO(pieces.size(), montantMoyen, montantMin, montantMax, dernieresPieces);
    }

    @Transactional
    public DemandeClarificationResponseDTO demanderClarification(UUID entrepriseId, String libelle,
                                                                    String description) {
        DemandePieceDto demande = demandePieceService.creerDepuisAgent(entrepriseId, libelle, description);
        return new DemandeClarificationResponseDTO(demande.id(), demande.statut());
    }
}
