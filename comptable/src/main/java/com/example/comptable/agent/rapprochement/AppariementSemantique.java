package com.example.comptable.agent.rapprochement;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.util.UUID;

/**
 * Un appariement ligne de releve / ligne d'ecriture propose par le microservice IA (ai-service)
 * et retenu apres application de son garde-fou de confiance (cf.
 * ai-service/app/services/rapprochement_service.py::_filtrer_et_valider) : seuls les
 * appariements avec {@code confiance > seuil} sont renvoyes, celui-ci ne renvoie donc jamais de
 * proposition "a valider manuellement" - une ligne sous le seuil reste simplement absente du
 * resultat et attend un pointage manuel via {@code pointer(...)}. Deserialise directement depuis
 * la reponse JSON d'ai-service (memes noms de champs), d'ou {@code @JsonIgnoreProperties}.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record AppariementSemantique(
        UUID ligneReleveId,
        UUID ligneEcritureId,
        double confiance,
        String justification
) {
}
