package com.example.comptable.agent.finding;

import com.example.comptable.agent.finding.domain.Finding;
import com.example.comptable.agent.finding.domain.SeveriteFinding;
import com.example.comptable.agent.finding.domain.TypeFinding;
import com.example.comptable.agent.matching.ResultatDecision;
import com.example.comptable.agent.matching.domain.AiMatchProposal;
import com.example.comptable.agent.matching.dto.MatchingRequestDTO;
import com.example.comptable.comptabilite.domain.LigneReleve;
import com.example.comptable.entreprise.domain.Entreprise;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.Optional;

/**
 * "Missing Evidence Engine" (mission P0, section 12) : interprete en Java les decisions deja
 * produites par {@code MatchDecisionEngine} pour determiner CE QUI MANQUE, sans appel IA
 * supplementaire - NO_MATCH/AI_UNAVAILABLE/MATCH_REVIEW sont deja calcules, ce moteur se contente
 * de les traduire en {@link com.example.comptable.agent.finding.domain.Finding} exploitables par
 * l'Inbox. Ne cree jamais de {@code DemandePiece} lui-meme (reserve a la boucle WhatsApp, phase 5)
 * - produit uniquement le constat et l'action recommandee, "puis Java decide" (section 12).
 */
@Component
public class MissingEvidenceAnalyzer {

    private static final String SOURCE_TYPE = "LIGNE_RELEVE";

    private final FindingService findingService;

    public MissingEvidenceAnalyzer(FindingService findingService) {
        this.findingService = findingService;
    }

    /** @return le Finding cree/deja-ouvert pour cette ligne, ou vide pour MATCH_AUTO (rien a
     * signaler, un Finding anterieur eventuel est au contraire resolu). Permet a l'appelant
     * (mission P0, boucle de resolution WhatsApp) de decider - separement - s'il faut aller plus
     * loin qu'un simple constat, sans que ce moteur ne declenche jamais une action lui-meme. */
    public Optional<Finding> analyser(Entreprise entreprise, LigneReleve ligne, MatchingRequestDTO requete, ResultatDecision resultat) {
        String sourceId = ligne.getId().toString();
        return switch (resultat.decision()) {
            case MATCH_AUTO -> {
                findingService.resoudreParSource(entreprise.getId(), SOURCE_TYPE, sourceId);
                yield Optional.empty();
            }
            case MATCH_REVIEW -> Optional.of(signalerAmbiguite(entreprise, ligne, resultat.proposition()));
            case NO_MATCH -> Optional.of(signalerPreuveManquante(entreprise, ligne, requete));
            case AI_UNAVAILABLE -> Optional.of(signalerContexteManquant(entreprise, ligne));
        };
    }

    private Finding signalerAmbiguite(Entreprise entreprise, LigneReleve ligne, AiMatchProposal proposition) {
        return findingService.signaler(entreprise, TypeFinding.MATCH_AMBIGUOUS, SeveriteFinding.IMPORTANT,
                SOURCE_TYPE, ligne.getId().toString(),
                "Rapprochement a confirmer : " + libelleLigne(ligne),
                "L'IA propose une correspondance (confiance " + Math.round(proposition.getConfidence() * 100)
                        + "%) mais elle necessite une validation humaine avant application.",
                proposition.getConfidence(),
                Map.of("aiMatchProposalId", proposition.getId().toString(),
                        "montant", ligne.getMontant(),
                        "date", ligne.getDateOperation().toString()),
                "Verifier la proposition IA et l'accepter ou la rejeter depuis l'ecran de rapprochement.");
    }

    private Finding signalerPreuveManquante(Entreprise entreprise, LigneReleve ligne, MatchingRequestDTO requete) {
        boolean aucunCandidat = requete.candidates().isEmpty();
        String titre;
        String description;
        String action;
        if (aucunCandidat) {
            titre = "Justificatif manquant : " + libelleLigne(ligne);
            description = "Aucune ecriture, facture ou piece candidate n'a ete trouvee pour cette transaction bancaire.";
            action = "Demander la piece justificative correspondante au client ou au fournisseur.";
        } else {
            titre = "Rapprochement non resolu : " + libelleLigne(ligne);
            description = requete.candidates().size() + " candidat(s) examine(s), aucun ne correspond avec certitude.";
            action = "Verifier manuellement les candidats ou demander une reference de paiement precise.";
        }
        return findingService.signaler(entreprise, TypeFinding.MISSING_EVIDENCE, SeveriteFinding.IMPORTANT,
                SOURCE_TYPE, ligne.getId().toString(), titre, description, null,
                Map.of("montant", ligne.getMontant(), "date", ligne.getDateOperation().toString(),
                        "candidatsExamines", requete.candidates().size()),
                action);
    }

    private Finding signalerContexteManquant(Entreprise entreprise, LigneReleve ligne) {
        return findingService.signaler(entreprise, TypeFinding.MISSING_EVIDENCE, SeveriteFinding.REVIEW,
                SOURCE_TYPE, ligne.getId().toString(),
                "Analyse IA indisponible : " + libelleLigne(ligne),
                "Le microservice IA etait indisponible pour cette ligne ; rapprochement manuel requis en attendant.",
                null,
                Map.of("montant", ligne.getMontant(), "date", ligne.getDateOperation().toString()),
                "Pointer manuellement cette ligne ou relancer l'analyse plus tard.");
    }

    private String libelleLigne(LigneReleve ligne) {
        String libelle = ligne.getLibelle() != null ? ligne.getLibelle() : "transaction";
        return libelle + " (" + ligne.getMontant() + ")";
    }
}
