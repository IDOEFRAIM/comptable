package com.example.comptable.comptabilite.web.dto;

import com.example.comptable.comptabilite.domain.Journal;

import java.util.UUID;

public record JournalDto(
        UUID id,
        String code,
        String libelle,
        String type
) {
    public static JournalDto from(Journal journal) {
        return new JournalDto(journal.getId(), journal.getCode(), journal.getLibelle(), journal.getType().name());
    }
}
