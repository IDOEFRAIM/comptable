package com.example.comptable.agent.matching;

import com.example.comptable.agent.matching.domain.AiMatchProposal;
import com.example.comptable.agent.matching.domain.DecisionRapprochementIA;
import com.example.comptable.agent.matching.domain.SourceType;
import com.example.comptable.agent.matching.domain.StatutPropositionIA;
import com.example.comptable.agent.matching.dto.MatchProposalDTO;
import com.example.comptable.agent.matching.dto.MatchingCandidateDTO;
import com.example.comptable.agent.matching.dto.MatchingRequestDTO;
import com.example.comptable.agent.matching.dto.MatchingResponseDTO;
import com.example.comptable.agent.matching.dto.MatchingSourceDTO;
import com.example.comptable.agent.matching.repository.AiMatchProposalRepository;
import com.example.comptable.comptabilite.domain.StatutFacture;
import com.example.comptable.comptabilite.repository.FactureRepository;
import com.example.comptable.comptabilite.repository.LigneEcritureRepository;
import com.example.comptable.comptabilite.domain.LigneReleve;
import com.example.comptable.document.repository.DocumentRepository;
import com.example.comptable.entreprise.domain.Entreprise;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * "Java Control" du Universal Matching Engine (mission 2, sections 7/8/9) : Spring Boot reste
 * l'autorite metier. Cette classe ne fait jamais confiance a la sortie brute d'ai-service - elle
 * revalide independamment chaque proposition (le candidat existe bien dans ce qui a ete envoye,
 * appartient a l'entreprise, est encore disponible, le montant est coherent) avant de statuer, et
 * ne declenche un pointage automatique reel que pour le cas le plus sur et le plus reversible :
 * une ecriture comptable deja validee (ACCOUNTING_ENTRY), jamais une facture ou un document -
 * "ne pas creer d'ecriture / ne pas valider une facture sur la seule base d'un score IA"
 * (sections 14/15/20) est interprete ici de la facon la plus prudente possible.
 */
@Component
public class MatchDecisionEngine {

    private static final Logger log = LoggerFactory.getLogger(MatchDecisionEngine.class);

    /** Ecart relatif de montant tolere avant de refuser tout MATCH_AUTO, meme si l'IA annonce
     * une confiance elevee - defense en profondeur independante du score IA (section 9). */
    private static final BigDecimal TOLERANCE_ECART_MONTANT_RELATIF = new BigDecimal("0.20");

    private final AiMatchProposalRepository aiMatchProposalRepository;
    private final LigneEcritureRepository ligneEcritureRepository;
    private final FactureRepository factureRepository;
    private final DocumentRepository documentRepository;
    private final double seuilAutoMatch;
    private final double seuilReview;
    private final com.example.comptable.banque.BankReconciliationService reconciliationService;

    public MatchDecisionEngine(AiMatchProposalRepository aiMatchProposalRepository,
                                LigneEcritureRepository ligneEcritureRepository,
                                FactureRepository factureRepository,
                                DocumentRepository documentRepository,
                                com.example.comptable.banque.BankReconciliationService reconciliationService,
                                @Value("${agent.matching.auto-match-threshold:0.95}") double seuilAutoMatch,
                                @Value("${agent.matching.review-threshold:0.70}") double seuilReview) {
        this.aiMatchProposalRepository = aiMatchProposalRepository;
        this.ligneEcritureRepository = ligneEcritureRepository;
        this.factureRepository = factureRepository;
        this.documentRepository = documentRepository;
        this.reconciliationService = reconciliationService;
        this.seuilAutoMatch = seuilAutoMatch;
        this.seuilReview = seuilReview;
    }

    /**
     * @param ecrituresIndisponibles ecritures deja pointees/proposees dans le lot en cours
     *                                (releve entier), maintenues par l'appelant - evite qu'une
     *                                meme ecriture soit proposee deux fois au sein d'un meme import.
     */
    @Transactional
    public ResultatDecision statuer(Entreprise entreprise, LigneReleve ligne, MatchingRequestDTO requete,
                                     Optional<MatchingResponseDTO> reponseIA, Set<UUID> ecrituresIndisponibles) {
        if (reponseIA.isEmpty()) {
            AiMatchProposal proposition = enregistrer(entreprise, requete.source(), null, 0.0,
                    DecisionRapprochementIA.AI_UNAVAILABLE, null, List.of(), List.of());
            return new ResultatDecision(DecisionRapprochementIA.AI_UNAVAILABLE, proposition, null);
        }

        MatchingResponseDTO reponse = reponseIA.get();
        // "NO_MATCH" est le seul cas a court-circuiter ici : MATCH_PROPOSED, PARTIAL_MATCH et
        // AMBIGUOUS_MATCH portent tous des candidats exploitables (cf. MatchDecision cote
        // ai-service) et doivent etre evalues plus bas, pas silencieusement abandonnes.
        if ("NO_MATCH".equals(reponse.decision()) || reponse.matches().isEmpty()) {
            AiMatchProposal proposition = enregistrer(entreprise, requete.source(), null, reponse.confidence(),
                    DecisionRapprochementIA.NO_MATCH, reponse.matchType(), reponse.reasons(), reponse.warnings());
            return new ResultatDecision(DecisionRapprochementIA.NO_MATCH, proposition, null);
        }

        List<MatchProposalDTO> matches = reponse.matches();
        MatchProposalDTO meilleur = matches.stream()
                .max(Comparator.comparingDouble(MatchProposalDTO::score))
                .orElseThrow();

        // Valide TOUTES les entrees proposees, pas seulement la mieux notee (mission P0, Matching
        // V2) : un ONE_TO_MANY/MANY_TO_ONE auto-declare par l'IA ne doit pas laisser passer un
        // candidat invente ailleurs qu'en premiere position - avant cette verification, seul
        // "meilleur" etait controle, un second/troisieme candidat hallucine dans un groupe
        // n'etait jamais detecte.
        List<MatchingCandidateDTO> candidatsValides = new ArrayList<>();
        boolean toutesLesEntreesValides = true;
        for (MatchProposalDTO match : matches) {
            Optional<MatchingCandidateDTO> candidat = requete.candidates().stream()
                    .filter(c -> c.id().equals(match.candidateId()) && c.type().equals(match.candidateType()))
                    .findFirst();
            if (candidat.isEmpty()) {
                toutesLesEntreesValides = false;
                break;
            }
            candidatsValides.add(candidat.get());
        }

        if (!toutesLesEntreesValides) {
            log.warn("Universal Matching Engine : au moins un candidat propose est absent des candidats envoyes "
                            + "pour la source {}, proposition retrogradee en revue humaine",
                    requete.source().id());
            List<String> motifs = avecMotif(reponse.reasons(), "invalid_candidate_id");
            AiMatchProposal proposition = enregistrer(entreprise, requete.source(), meilleur, reponse.confidence(),
                    DecisionRapprochementIA.MATCH_REVIEW, reponse.matchType(), motifs, reponse.warnings());
            return new ResultatDecision(DecisionRapprochementIA.MATCH_REVIEW, proposition, null);
        }

        // Pour un groupe (matches.size() > 1, ONE_TO_MANY/MANY_TO_ONE) la coherence se verifie sur
        // la SOMME des candidats valides, pas sur le seul "meilleur" : avant cette mission, aucun
        // code ne sommait jamais les candidats d'un groupe auto-declare par l'IA.
        boolean coherenceMontant = matches.size() > 1
                ? verifierCoherenceMontantGroupe(requete.source(), candidatsValides)
                : verifierCoherenceMontant(requete.source(), candidatsValides.get(0));
        boolean disponible = matches.stream().allMatch(m -> verifierDisponibilite(entreprise, m, ecrituresIndisponibles));
        boolean seulUnCandidatPropose = matches.size() == 1;

        DecisionRapprochementIA decision = determinerDecision(reponse, meilleur, coherenceMontant, disponible,
                seulUnCandidatPropose);

        List<String> reasons = new ArrayList<>(reponse.reasons());
        if (!coherenceMontant) {
            reasons.add("java_control:montant_incoherent");
        }
        if (!disponible) {
            reasons.add("java_control:candidat_indisponible");
        }

        AiMatchProposal proposition = enregistrer(entreprise, requete.source(), meilleur, reponse.confidence(),
                decision, reponse.matchType(), reasons, reponse.warnings());

        UUID ligneEcriturePointee = null;
        if (decision == DecisionRapprochementIA.MATCH_AUTO) {
            ligneEcriturePointee = UUID.fromString(meilleur.candidateId());
            reconciliationService.confirmerParLeSysteme(ligne, ligneEcriturePointee, com.example.comptable.banque.SourceReconciliation.AI_PROPOSAL,
                    com.example.comptable.banque.DecisionReconciliation.PROBABLE, java.math.BigDecimal.valueOf(reponse.confidence() * 100),
                    "Proposition IA validee par les garde-fous Java");
            proposition.setStatus(StatutPropositionIA.ACCEPTED);
            proposition.setValidatedAt(OffsetDateTime.now());
            aiMatchProposalRepository.save(proposition);
        }

        return new ResultatDecision(decision, proposition, ligneEcriturePointee);
    }

    /**
     * MATCH_AUTO seulement si : confiance >= seuil, l'IA elle-meme n'exige pas de revue humaine,
     * garde-fous Java tous passes, un SEUL candidat propose (jamais d'auto-accept sur un
     * ONE_TO_MANY/AMBIGUOUS), ET candidat de type ACCOUNTING_ENTRY exclusivement - une facture ou
     * un document ne sont jamais auto-pointes (sections 14/15/20 : ne jamais creer d'ecriture ni
     * valider une facture sur la seule base d'un score IA).
     */
    private DecisionRapprochementIA determinerDecision(MatchingResponseDTO reponse, MatchProposalDTO meilleur,
                                                         boolean coherenceMontant, boolean disponible,
                                                         boolean seulUnCandidatPropose) {
        if ("AMBIGUOUS_MATCH".equals(reponse.decision())) {
            // Plusieurs candidats plausibles que l'IA n'arrive pas a departager : toujours une
            // revue humaine, jamais un NO_MATCH silencieux (on perdrait les candidats) ni un
            // MATCH_AUTO (seulUnCandidatPropose serait de toute facon faux ici en pratique).
            return DecisionRapprochementIA.MATCH_REVIEW;
        }

        boolean garanti = coherenceMontant && disponible && seulUnCandidatPropose
                && !reponse.requiresHumanReview()
                && SourceType.ACCOUNTING_ENTRY.name().equals(meilleur.candidateType());

        if (garanti && reponse.confidence() >= seuilAutoMatch) {
            return DecisionRapprochementIA.MATCH_AUTO;
        }
        if (reponse.confidence() >= seuilReview && (coherenceMontant || disponible)) {
            return DecisionRapprochementIA.MATCH_REVIEW;
        }
        if (!coherenceMontant || !disponible) {
            // Garde-fou viole : meme une confiance elevee ne doit jamais passer inapercue,
            // toujours au moins une revue humaine plutot qu'un NO_MATCH silencieux.
            return DecisionRapprochementIA.MATCH_REVIEW;
        }
        return DecisionRapprochementIA.NO_MATCH;
    }

    private boolean verifierCoherenceMontant(MatchingSourceDTO source, MatchingCandidateDTO candidat) {
        if (candidat.amount() == null || candidat.amount().signum() == 0) {
            return false;
        }
        BigDecimal montantSource = source.amount().abs();
        BigDecimal montantCandidat = candidat.amount().abs();
        BigDecimal ecartRelatif = montantSource.subtract(montantCandidat).abs()
                .divide(montantCandidat, 4, RoundingMode.HALF_UP);
        return ecartRelatif.compareTo(TOLERANCE_ECART_MONTANT_RELATIF) <= 0;
    }

    /** Meme garde-fou que {@link #verifierCoherenceMontant}, applique a la SOMME d'un groupe de
     * candidats (ONE_TO_MANY/MANY_TO_ONE) plutot qu'a un candidat unique - mission P0, Matching V2 :
     * "l'IA classe et explique, le Core verifie la somme". */
    private boolean verifierCoherenceMontantGroupe(MatchingSourceDTO source, List<MatchingCandidateDTO> candidats) {
        BigDecimal sommeCandidats = candidats.stream()
                .map(MatchingCandidateDTO::amount)
                .filter(Objects::nonNull)
                .map(BigDecimal::abs)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        if (sommeCandidats.signum() == 0) {
            return false;
        }
        BigDecimal montantSource = source.amount().abs();
        BigDecimal ecartRelatif = montantSource.subtract(sommeCandidats).abs()
                .divide(sommeCandidats, 4, RoundingMode.HALF_UP);
        return ecartRelatif.compareTo(TOLERANCE_ECART_MONTANT_RELATIF) <= 0;
    }

    private boolean verifierDisponibilite(Entreprise entreprise, MatchProposalDTO match, Set<UUID> ecrituresIndisponibles) {
        SourceType type;
        try {
            type = SourceType.valueOf(match.candidateType());
        } catch (IllegalArgumentException | NullPointerException e) {
            return false;
        }
        if (aiMatchProposalRepository.existsByCandidateTypeAndCandidateIdAndStatus(
                type, match.candidateId(), StatutPropositionIA.ACCEPTED)) {
            return false;
        }
        UUID candidatId;
        try {
            candidatId = UUID.fromString(match.candidateId());
        } catch (IllegalArgumentException e) {
            return false;
        }
        return switch (type) {
            case ACCOUNTING_ENTRY -> !ecrituresIndisponibles.contains(candidatId)
                    && ligneEcritureRepository.findById(candidatId)
                            .filter(le -> le.getCompte().getEntreprise().getId().equals(entreprise.getId()))
                            .isPresent();
            case CUSTOMER_INVOICE -> factureRepository.findById(candidatId)
                    .filter(f -> f.getEntreprise().getId().equals(entreprise.getId()))
                    .filter(f -> f.getStatut() == StatutFacture.VALIDEE)
                    .isPresent();
            case SUPPLIER_INVOICE, DOCUMENT -> documentRepository.findById(candidatId)
                    .filter(d -> d.getEntreprise().getId().equals(entreprise.getId()))
                    .filter(d -> d.getEcritureId() == null)
                    .isPresent();
            default -> false;
        };
    }

    private List<String> avecMotif(List<String> motifs, String motifSupplementaire) {
        List<String> resultat = new ArrayList<>(motifs);
        resultat.add(motifSupplementaire);
        return resultat;
    }

    private AiMatchProposal enregistrer(Entreprise entreprise, MatchingSourceDTO source, MatchProposalDTO match,
                                         double confidence, DecisionRapprochementIA decision, String matchType,
                                         List<String> reasons, List<String> warnings) {
        AiMatchProposal proposition = new AiMatchProposal();
        proposition.setEntreprise(entreprise);
        proposition.setSourceType(SourceType.valueOf(source.type()));
        proposition.setSourceId(source.id());
        if (match != null) {
            proposition.setCandidateType(analyserTypeCandidat(match.candidateType()));
            proposition.setCandidateId(match.candidateId());
        }
        proposition.setConfidence(confidence);
        proposition.setDecision(decision);
        proposition.setMatchType(matchType);
        proposition.setReasonsJson(reasons);
        proposition.setWarningsJson(warnings);
        return aiMatchProposalRepository.save(proposition);
    }

    private SourceType analyserTypeCandidat(String valeur) {
        try {
            return SourceType.valueOf(valeur);
        } catch (IllegalArgumentException | NullPointerException e) {
            return null;
        }
    }
}
