package com.example.comptable.agent.finding;

import com.example.comptable.agent.finding.domain.Finding;
import com.example.comptable.agent.matching.ResultatDecision;
import com.example.comptable.agent.matching.domain.AiMatchProposal;
import com.example.comptable.agent.matching.dto.MatchingRequestDTO;
import com.example.comptable.comptabilite.domain.LigneReleve;
import com.example.comptable.controle.ControlCategory;
import com.example.comptable.controle.ControlCodes;
import com.example.comptable.controle.ControlResult;
import com.example.comptable.controle.ControlSeverity;
import com.example.comptable.controle.ControlResultPublisher;
import com.example.comptable.entreprise.domain.Entreprise;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * "Missing Evidence Engine" (mission P0, section 12) : interprete en Java les decisions deja
 * produites par {@code MatchDecisionEngine} pour determiner CE QUI MANQUE, sans appel IA
 * supplementaire. Phase B : produit des {@link ControlResult} (codes {@code MATCH_TO_CONFIRM} pour
 * une correspondance probable, {@code UNMATCHED_PAYMENT} sinon) publies comme Finding avec la meme
 * cle de deduplication que le detecteur deterministe de lignes de releve non pointees. Ne cree
 * jamais de {@code DemandePiece} lui-meme (reserve a la boucle WhatsApp) - "puis Java decide".
 */
@Component
public class MissingEvidenceAnalyzer {

    static final String SOURCE_TYPE = "LIGNE_RELEVE";

    private final FindingService findingService;
    private final ControlResultPublisher publisher;

    public MissingEvidenceAnalyzer(FindingService findingService, ControlResultPublisher publisher) {
        this.findingService = findingService;
        this.publisher = publisher;
    }

    /** @return le Finding cree/mis a jour pour cette ligne, ou vide pour MATCH_AUTO (rien a
     * signaler, un Finding anterieur eventuel est au contraire resolu). Permet a l'appelant
     * (boucle de resolution WhatsApp) de decider - separement - s'il faut aller plus loin qu'un
     * simple constat, sans que ce moteur ne declenche jamais une action lui-meme. */
    public Optional<Finding> analyser(Entreprise entreprise, LigneReleve ligne, MatchingRequestDTO requete, ResultatDecision resultat) {
        String sourceId = ligne.getId().toString();
        return switch (resultat.decision()) {
            case MATCH_AUTO -> {
                findingService.resoudreParSource(entreprise.getId(), SOURCE_TYPE, sourceId);
                yield Optional.empty();
            }
            case MATCH_REVIEW -> {
                findingService.resoudreParControle(entreprise.getId(), ControlCodes.UNMATCHED_PAYMENT, SOURCE_TYPE, sourceId);
                yield publier(entreprise, correspondanceAConfirmer(entreprise, ligne, resultat.proposition()));
            }
            case NO_MATCH -> {
                findingService.resoudreParControle(entreprise.getId(), ControlCodes.MATCH_TO_CONFIRM, SOURCE_TYPE, sourceId);
                yield publier(entreprise, preuveManquante(entreprise, ligne, requete));
            }
            case AI_UNAVAILABLE -> {
                findingService.resoudreParControle(entreprise.getId(), ControlCodes.MATCH_TO_CONFIRM, SOURCE_TYPE, sourceId);
                yield publier(entreprise, contexteManquant(entreprise, ligne));
            }
        };
    }

    private Optional<Finding> publier(Entreprise entreprise, ControlResult constat) {
        return Optional.ofNullable(publisher.publier(entreprise, constat, ControlCodes.typeFinding(constat.code())));
    }

    private ControlResult correspondanceAConfirmer(Entreprise entreprise, LigneReleve ligne, AiMatchProposal proposition) {
        return ControlResult.ouvert(ControlCodes.MATCH_TO_CONFIRM, ControlCategory.RECONCILIATION, ControlSeverity.WARNING,
                entreprise.getId(), SOURCE_TYPE, ligne.getId().toString(),
                "Rapprochement a confirmer : " + libelleLigne(ligne),
                "L'IA propose une correspondance (confiance " + Math.round(proposition.getConfidence() * 100)
                        + "%) mais elle necessite une validation humaine avant application.",
                List.of(),
                Map.of("aiMatchProposalId", proposition.getId().toString(),
                        "confiance", proposition.getConfidence(),
                        "montant", ligne.getMontant(),
                        "date", ligne.getDateOperation().toString()),
                "Verifier la proposition IA et l'accepter ou la rejeter depuis l'ecran de rapprochement.");
    }

    private ControlResult preuveManquante(Entreprise entreprise, LigneReleve ligne, MatchingRequestDTO requete) {
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
        return ControlResult.ouvert(ControlCodes.UNMATCHED_PAYMENT, ControlCategory.RECONCILIATION, ControlSeverity.WARNING,
                entreprise.getId(), SOURCE_TYPE, ligne.getId().toString(), titre, description, List.of(),
                Map.of("montant", ligne.getMontant(), "date", ligne.getDateOperation().toString(),
                        "candidatsExamines", requete.candidates().size()),
                action);
    }

    private ControlResult contexteManquant(Entreprise entreprise, LigneReleve ligne) {
        return ControlResult.ouvert(ControlCodes.UNMATCHED_PAYMENT, ControlCategory.RECONCILIATION, ControlSeverity.INFO,
                entreprise.getId(), SOURCE_TYPE, ligne.getId().toString(),
                "Analyse IA indisponible : " + libelleLigne(ligne),
                "Le microservice IA etait indisponible pour cette ligne ; rapprochement manuel requis en attendant.",
                List.of(),
                Map.of("montant", ligne.getMontant(), "date", ligne.getDateOperation().toString()),
                "Pointer manuellement cette ligne ou relancer l'analyse plus tard.");
    }

    private String libelleLigne(LigneReleve ligne) {
        String libelle = ligne.getLibelle() != null ? ligne.getLibelle() : "transaction";
        return libelle + " (" + ligne.getMontant() + ")";
    }
}
