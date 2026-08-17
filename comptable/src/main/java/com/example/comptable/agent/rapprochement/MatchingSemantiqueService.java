package com.example.comptable.agent.rapprochement;

import com.example.comptable.agent.client.AiServiceClient;
import com.example.comptable.agent.rapprochement.dto.LigneEcritureCandidateDTO;
import com.example.comptable.agent.rapprochement.dto.LigneReleveDTO;
import com.example.comptable.agent.rapprochement.dto.RapprochementSemantiqueRequestDTO;
import com.example.comptable.comptabilite.domain.LigneEcriture;
import com.example.comptable.comptabilite.domain.LigneReleve;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * Rapprochement semantique : filet de secours appele par {@link com.example.comptable.comptabilite.service.RapprochementBancaireService}
 * uniquement sur les lignes de releve que l'appariement deterministe (montant exact, cf.
 * {@code rapprocherAutomatiquement}) n'a pas su resoudre. Frequent en Afrique de l'Ouest ou une
 * part importante des mouvements arrive par Mobile Money (Wave, Orange Money, MTN Money, Moov
 * Money) avec des libelles courts, abreges ou dans un ordre nom/prenom variable, et des montants
 * parfois legerement differents du montant facture (frais preleves par l'operateur).
 * <p>
 * Delegue au microservice IA (ai-service) via {@link AiServiceClient#rapprocherSemantiquement} :
 * prompt, appel modele et garde-fou (seuil de confiance, validite des identifiants renvoyes,
 * coherence de direction, unicite) vivent desormais cote Python, portes verbatim depuis cette
 * classe (cf. ai-service/app/services/rapprochement_service.py). Ce service ne fait plus
 * qu'assembler la requete et respecter le flag {@code actif} : ai-service ne renvoie deja que des
 * appariements retenus, jamais de proposition brute a re-filtrer.
 */
@Service
public class MatchingSemantiqueService {

    private final AiServiceClient aiServiceClient;
    private final boolean actif;

    public MatchingSemantiqueService(AiServiceClient aiServiceClient,
                                      @Value("${agent.rapprochement-semantique.enabled:false}") boolean actif) {
        this.aiServiceClient = aiServiceClient;
        this.actif = actif;
    }

    /**
     * Propose des appariements pour les lignes de releve restees non rapprochees apres le
     * passage deterministe. Ne leve jamais d'exception : {@link AiServiceClient#rapprocherSemantiquement}
     * degrade deja silencieusement vers "aucun appariement semantique" en cas de panne - le
     * rapprochement deterministe et le pointage manuel restent pleinement fonctionnels dans tous
     * les cas.
     */
    public List<AppariementSemantique> proposerAppariements(List<LigneReleve> lignesNonRapprochees,
                                                              List<LigneEcriture> candidats) {
        if (!actif || lignesNonRapprochees.isEmpty() || candidats.isEmpty()) {
            return List.of();
        }

        List<LigneReleveDTO> lignesDTO = lignesNonRapprochees.stream().map(LigneReleveDTO::from).toList();
        List<LigneEcritureCandidateDTO> candidatsDTO = candidats.stream().map(LigneEcritureCandidateDTO::from).toList();

        return aiServiceClient.rapprocherSemantiquement(new RapprochementSemantiqueRequestDTO(lignesDTO, candidatsDTO));
    }
}
