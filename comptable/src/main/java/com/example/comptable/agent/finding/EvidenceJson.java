package com.example.comptable.agent.finding;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Met une map de valeurs sous la forme EXACTE que la base relira (types JSON : nombres entiers/decimaux, textes, listes).
 * Sans cela, un {@code BigDecimal} ou un {@code UUID} place en memoire ne serait jamais egal a sa relecture : Hibernate
 * verrait l'entite « modifiee » a chaque flush (une requete UPDATE par constat a chaque requete suivante) et la comparaison
 * « le constat a-t-il change ? » repondrait toujours oui.
 */
final class EvidenceJson {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private EvidenceJson() {
    }

    static Map<String, Object> normaliser(Map<String, Object> evidence) {
        if (evidence == null) {
            return null;
        }
        try {
            // Vrai aller-retour par le texte JSON (convertValue conserverait un BigDecimal tel quel).
            return MAPPER.readValue(MAPPER.writeValueAsString(evidence), new TypeReference<LinkedHashMap<String, Object>>() {
            });
        } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
            throw new IllegalArgumentException("Valeurs de constat non serialisables en JSON", e);
        }
    }
}
