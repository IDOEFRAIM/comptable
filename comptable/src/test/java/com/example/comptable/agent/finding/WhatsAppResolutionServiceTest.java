package com.example.comptable.agent.finding;

import com.example.comptable.agent.finding.domain.Finding;
import com.example.comptable.agent.finding.domain.SeveriteFinding;
import com.example.comptable.agent.finding.domain.TypeFinding;
import com.example.comptable.comptabilite.domain.LigneReleve;
import com.example.comptable.demande.repository.DemandePieceRepository;
import com.example.comptable.demande.service.DemandePieceService;
import com.example.comptable.entreprise.domain.Entreprise;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.Map;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class WhatsAppResolutionServiceTest {

    @Mock
    private DemandePieceService demandePieceService;
    @Mock
    private DemandePieceRepository demandePieceRepository;

    private WhatsAppResolutionService service;
    private Entreprise entreprise;
    private LigneReleve ligne;

    @BeforeEach
    void setUp() {
        service = new WhatsAppResolutionService(demandePieceService, demandePieceRepository);
        entreprise = new Entreprise();
        entreprise.setId(UUID.randomUUID());
        ligne = new LigneReleve(LocalDate.of(2027, 1, 5), "VIR ABC SARL", new BigDecimal("485000"));
        ligne.setId(UUID.randomUUID());
    }

    private Finding finding(TypeFinding type, Map<String, Object> evidence) {
        Finding f = new Finding();
        f.setId(UUID.randomUUID());
        f.setEntreprise(entreprise);
        f.setType(type);
        f.setSeverity(SeveriteFinding.IMPORTANT);
        f.setSourceType("LIGNE_RELEVE");
        f.setSourceId(ligne.getId().toString());
        f.setTitle("titre");
        f.setEvidenceJson(evidence);
        f.setCreatedAt(OffsetDateTime.now());
        return f;
    }

    @Test
    void aucun_candidat_examine_declenche_une_demande_ciblee() {
        Finding f = finding(TypeFinding.MISSING_EVIDENCE, Map.of("candidatsExamines", 0));
        when(demandePieceRepository.existsByFindingId(f.getId())).thenReturn(false);

        service.resoudreSiPreuveManquante(f, ligne);

        verify(demandePieceService).creerDepuisFinding(eq(entreprise.getId()), eq(f.getId()), any(), any());
    }

    @Test
    void des_candidats_examines_mais_ambigus_ne_declenche_aucune_demande() {
        Finding f = finding(TypeFinding.MISSING_EVIDENCE, Map.of("candidatsExamines", 2));

        service.resoudreSiPreuveManquante(f, ligne);

        verify(demandePieceService, never()).creerDepuisFinding(any(), any(), any(), any());
    }

    @Test
    void un_finding_deja_promu_en_demande_nest_pas_duplique() {
        Finding f = finding(TypeFinding.MISSING_EVIDENCE, Map.of("candidatsExamines", 0));
        when(demandePieceRepository.existsByFindingId(f.getId())).thenReturn(true);

        service.resoudreSiPreuveManquante(f, ligne);

        verify(demandePieceService, never()).creerDepuisFinding(any(), any(), any(), any());
    }

    @Test
    void un_finding_dambiguite_de_matching_ne_declenche_jamais_de_demande() {
        Finding f = finding(TypeFinding.MATCH_AMBIGUOUS, Map.of("aiMatchProposalId", UUID.randomUUID().toString()));

        service.resoudreSiPreuveManquante(f, ligne);

        verify(demandePieceService, never()).creerDepuisFinding(any(), any(), any(), any());
    }
}
