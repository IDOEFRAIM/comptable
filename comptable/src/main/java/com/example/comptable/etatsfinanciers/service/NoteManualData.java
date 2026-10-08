package com.example.comptable.etatsfinanciers.service;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Donnees saisies par un humain pour une note : {@code sections -> champs -> valeur}. Strictement separees des donnees generees : un recalcul ne les touche jamais. Les valeurs
 * sont des textes valides par type a l'ecriture (voir le service d'espace de travail) ; l'horodatage est un texte ISO-8601.
 */
public record NoteManualData(Map<String, Map<String, ManualValue>> sections) {

    public record ManualValue(String value, String comment, String source, UUID updatedBy, String updatedAt) {
    }

    public NoteManualData {
        sections = sections == null ? new LinkedHashMap<>() : sections;
    }

    public static NoteManualData vide() {
        return new NoteManualData(new LinkedHashMap<>());
    }

    public ManualValue valeur(String section, String champ) {
        Map<String, ManualValue> champs = sections.get(section);
        return champs == null ? null : champs.get(champ);
    }
}
