package com.example.comptable.dossier;

import com.example.comptable.agent.finding.ControlEngine;
import com.example.comptable.agent.finding.domain.Finding;
import com.example.comptable.agent.finding.domain.SeveriteFinding;
import com.example.comptable.agent.finding.domain.StatutFinding;
import com.example.comptable.agent.finding.domain.TypeFinding;
import com.example.comptable.agent.finding.repository.FindingRepository;
import com.example.comptable.agent.finding.web.dto.FindingDto;
import com.example.comptable.comptabilite.domain.StatutFacture;
import com.example.comptable.comptabilite.domain.TypeFacture;
import com.example.comptable.comptabilite.repository.FactureRepository;
import com.example.comptable.document.domain.StatutDocument;
import com.example.comptable.document.domain.TypeDocument;
import com.example.comptable.document.repository.DocumentRepository;
import com.example.comptable.dossier.web.dto.DossierComptableDto;
import com.example.comptable.dossier.web.dto.ScoreDomaineDto;
import com.example.comptable.entreprise.domain.Entreprise;
import com.example.comptable.entreprise.service.EntrepriseAccessService;
import com.example.comptable.security.UtilisateurPrincipal;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import java.util.function.Predicate;

/**
 * "Dossier Comptable Intelligent" (mission P0, section 8) : point d'entree qui repond a "qu'est-ce
 * qui est deja correct / qu'est-ce qui manque / qu'est-ce qui est anormal / que dois-je faire
 * maintenant" pour une entreprise. Jamais persiste (voir la javadoc de {@link DossierComptableDto})
 * - recalcule a chaque appel a partir de {@link Finding} (deja peuples par {@link ControlEngine} et
 * {@code MissingEvidenceAnalyzer}) et de quelques compteurs deterministes existants.
 * <p>
 * Limite assumee pour ce P0 : les domaines Banque et Justificatifs partagent aujourd'hui la meme
 * source de signal (les Finding produits par le Missing Evidence Engine sur les lignes de releve
 * non resolues) - un enrichissement naturel de P1 serait d'ajouter des Finding "justificatif
 * manquant" sur d'autres sources (ex: document a faible confiance d'extraction). Les domaines
 * Clients/Fournisseurs n'ont pas encore de regle Finding dediee : ils sont calcules directement a
 * partir de compteurs deterministes existants (factures clients en retard, pieces fournisseur non
 * comptabilisees) plutot que d'inventer une regle non testee sous la pression du delai.
 */
@Service
public class DossierComptableService {

    private static final int PENALITE_CRITICAL = 25;
    private static final int PENALITE_IMPORTANT = 10;
    private static final int PENALITE_REVIEW = 3;
    private static final int SEUIL_SCORE_BLOQUE = 70;
    private static final int SEUIL_SCORE_PRET = 95;
    private static final int PENALITE_PAR_FACTURE_CLIENT_EN_RETARD = 8;
    private static final int PENALITE_PAR_PIECE_FOURNISSEUR_EN_ATTENTE = 5;
    private static final int MAX_ACTIONS_PRIORITAIRES = 50;

    private final EntrepriseAccessService entrepriseAccessService;
    private final ControlEngine controlEngine;
    private final FindingRepository findingRepository;
    private final FactureRepository factureRepository;
    private final DocumentRepository documentRepository;

    public DossierComptableService(EntrepriseAccessService entrepriseAccessService,
                                    ControlEngine controlEngine,
                                    FindingRepository findingRepository,
                                    FactureRepository factureRepository,
                                    DocumentRepository documentRepository) {
        this.entrepriseAccessService = entrepriseAccessService;
        this.controlEngine = controlEngine;
        this.findingRepository = findingRepository;
        this.factureRepository = factureRepository;
        this.documentRepository = documentRepository;
    }

    @Transactional
    public DossierComptableDto generer(UUID entrepriseId, UtilisateurPrincipal principal) {
        Entreprise entreprise = entrepriseAccessService.verifierAcces(entrepriseId, principal);

        // Rafraichit les findings deterministes avant de scorer (declarations manquantes/en
        // retard, documents a risque, ecritures brouillon anciennes) - le Missing Evidence Engine,
        // lui, est deja tenu a jour a chaque import de releve (evenementiel, pas relu ici).
        controlEngine.analyser(entreprise);

        List<Finding> findingsOuverts = findingRepository
                .findByEntrepriseIdAndStatusOrderBySeverityAscCreatedAtDesc(entrepriseId, StatutFinding.OPEN);

        int scoreBanque = scorePenalise(findingsOuverts, f -> "LIGNE_RELEVE".equals(f.getSourceType()));
        int scoreDocuments = scorePenalise(findingsOuverts, f -> f.getType() == TypeFinding.DOCUMENT_REVIEW);
        int scoreJustificatifs = scorePenalise(findingsOuverts, f -> f.getType() == TypeFinding.MISSING_EVIDENCE);
        int scoreFiscalite = scorePenalise(findingsOuverts, f -> f.getType() == TypeFinding.TAX_REVIEW);
        int scoreCloture = scorePenalise(findingsOuverts,
                f -> f.getType() == TypeFinding.ECRITURE_A_CONTROLER || f.getType() == TypeFinding.CLOSING_BLOCKER);
        int scoreClients = scoreClients(entrepriseId);
        int scoreFournisseurs = scoreFournisseurs(entrepriseId);

        List<ScoreDomaineDto> scoresParDomaine = List.of(
                new ScoreDomaineDto("Banque", scoreBanque),
                new ScoreDomaineDto("Documents", scoreDocuments),
                new ScoreDomaineDto("Clients", scoreClients),
                new ScoreDomaineDto("Fournisseurs", scoreFournisseurs),
                new ScoreDomaineDto("Justificatifs", scoreJustificatifs),
                new ScoreDomaineDto("Fiscalite", scoreFiscalite),
                new ScoreDomaineDto("Cloture", scoreCloture)
        );

        int scoreGlobal = (int) Math.round(scoresParDomaine.stream()
                .mapToInt(ScoreDomaineDto::score)
                .average()
                .orElse(100.0));

        boolean auMoinsUneCritique = findingsOuverts.stream().anyMatch(f -> f.getSeverity() == SeveriteFinding.CRITICAL);
        String statut = determinerStatut(scoreGlobal, auMoinsUneCritique);

        List<FindingDto> actionsPrioritaires = findingsOuverts.stream()
                .limit(MAX_ACTIONS_PRIORITAIRES)
                .map(FindingDto::from)
                .toList();
        List<FindingDto> blockingItems = findingsOuverts.stream()
                .filter(f -> f.getSeverity() == SeveriteFinding.CRITICAL)
                .map(FindingDto::from)
                .toList();

        return new DossierComptableDto(entreprise.getRaisonSociale(), scoreGlobal, statut,
                scoresParDomaine, actionsPrioritaires, blockingItems);
    }

    private String determinerStatut(int scoreGlobal, boolean auMoinsUneCritique) {
        if (auMoinsUneCritique || scoreGlobal < SEUIL_SCORE_BLOQUE) {
            return "BLOQUE";
        }
        if (scoreGlobal < SEUIL_SCORE_PRET) {
            return "A_SURVEILLER";
        }
        return "PRET";
    }

    /** Score deterministe : 100 moins la somme des penalites des findings ouverts filtres, jamais
     * calcule par un modele de langage (mission P0, section 8). */
    private int scorePenalise(List<Finding> findingsOuverts, Predicate<Finding> filtre) {
        int penalite = findingsOuverts.stream()
                .filter(filtre)
                .mapToInt(f -> switch (f.getSeverity()) {
                    case CRITICAL -> PENALITE_CRITICAL;
                    case IMPORTANT -> PENALITE_IMPORTANT;
                    case REVIEW -> PENALITE_REVIEW;
                })
                .sum();
        return Math.max(0, 100 - Math.min(100, penalite));
    }

    private int scoreClients(UUID entrepriseId) {
        long facturesEnRetard = factureRepository.countByEntrepriseIdAndTypeAndStatutAndDateEcheanceBefore(
                entrepriseId, TypeFacture.FACTURE, StatutFacture.VALIDEE, LocalDate.now());
        return Math.max(0, 100 - Math.min(100, (int) facturesEnRetard * PENALITE_PAR_FACTURE_CLIENT_EN_RETARD));
    }

    private int scoreFournisseurs(UUID entrepriseId) {
        long piecesEnAttente = documentRepository.countByEntrepriseIdAndStatutAndTypeDocumentInAndEcritureIdIsNull(
                entrepriseId, StatutDocument.TRAITE, List.of(TypeDocument.FACTURE_ACHAT));
        return Math.max(0, 100 - Math.min(100, (int) piecesEnAttente * PENALITE_PAR_PIECE_FOURNISSEUR_EN_ATTENTE));
    }
}
