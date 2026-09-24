package com.example.comptable.comptabilite.operation.common;

import com.example.comptable.comptabilite.domain.Journal;
import com.example.comptable.comptabilite.domain.TypeJournal;
import com.example.comptable.comptabilite.repository.JournalRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class JournalResolverTest {

    @Mock
    private JournalRepository journalRepository;

    @InjectMocks
    private JournalResolver journalResolver;

    private UUID entrepriseId;

    @BeforeEach
    void setUp() {
        entrepriseId = UUID.randomUUID();
    }

    private Journal journal(String code, TypeJournal type) {
        Journal journal = new Journal();
        journal.setId(UUID.randomUUID());
        journal.setCode(code);
        journal.setLibelle(code);
        journal.setType(type);
        return journal;
    }

    @Test
    void resoud_le_journal_par_type_plutot_que_par_code_fixe() {
        Journal ventes = journal("VTE", TypeJournal.VENTES);
        Journal achats = journal("ACH", TypeJournal.ACHATS);
        when(journalRepository.findByEntrepriseId(entrepriseId)).thenReturn(List.of(achats, ventes));

        Optional<Journal> resultat = journalResolver.resoudre(entrepriseId, TypeJournal.VENTES);

        assertThat(resultat).contains(ventes);
    }

    @Test
    void aucun_journal_du_type_demande_retourne_vide() {
        Journal achats = journal("ACH", TypeJournal.ACHATS);
        when(journalRepository.findByEntrepriseId(entrepriseId)).thenReturn(List.of(achats));

        Optional<Journal> resultat = journalResolver.resoudre(entrepriseId, TypeJournal.VENTES);

        assertThat(resultat).isEmpty();
    }

    @Test
    void resoudreparcode_reste_un_passthrough_de_compatibilite() {
        Journal ventes = journal("VTE", TypeJournal.VENTES);
        when(journalRepository.findByEntrepriseIdAndCode(entrepriseId, "VTE")).thenReturn(Optional.of(ventes));

        Optional<Journal> resultat = journalResolver.resoudreParCode(entrepriseId, "VTE");

        assertThat(resultat).contains(ventes);
    }
}
