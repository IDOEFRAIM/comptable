package com.example.comptable.agent.tools;

import com.example.comptable.agent.tools.dto.DemandeClarificationResponseDTO;
import com.example.comptable.agent.tools.dto.DoublonVerificationResponseDTO;
import com.example.comptable.agent.tools.dto.HistoriqueFournisseurDTO;
import com.example.comptable.demande.domain.DemandePiece;
import com.example.comptable.demande.domain.StatutDemandePiece;
import com.example.comptable.demande.service.DemandePieceService;
import com.example.comptable.demande.web.dto.DemandePieceDto;
import com.example.comptable.document.domain.Document;
import com.example.comptable.document.repository.DocumentRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class AgentToolsServiceTest {

    @Mock
    private DocumentRepository documentRepository;
    @Mock
    private DemandePieceService demandePieceService;

    @InjectMocks
    private AgentToolsService agentToolsService;

    private UUID entrepriseId;

    @BeforeEach
    void setUp() {
        entrepriseId = UUID.randomUUID();
    }

    private Document piece(LocalDate date, BigDecimal montant) {
        Document d = new Document();
        d.setDatePiece(date);
        d.setMontantTtc(montant);
        return d;
    }

    @Test
    void detecte_un_doublon_potentiel() {
        LocalDate date = LocalDate.of(2027, 1, 5);
        when(documentRepository.rechercherDoublonsPotentiels(eq(entrepriseId), eq(new BigDecimal("15000")), any(), any()))
                .thenReturn(List.of(piece(date, new BigDecimal("15000"))));

        DoublonVerificationResponseDTO resultat = agentToolsService.verifierDoublonMontant(
                entrepriseId, new BigDecimal("15000"), date);

        assertThat(resultat.doublonPotentielDetecte()).isTrue();
        assertThat(resultat.piecesSimilaires()).hasSize(1);
    }

    @Test
    void aucun_doublon_quand_le_repository_ne_retourne_rien() {
        when(documentRepository.rechercherDoublonsPotentiels(any(), any(), any(), any())).thenReturn(List.of());

        DoublonVerificationResponseDTO resultat = agentToolsService.verifierDoublonMontant(
                entrepriseId, new BigDecimal("15000"), LocalDate.of(2027, 1, 5));

        assertThat(resultat.doublonPotentielDetecte()).isFalse();
        assertThat(resultat.piecesSimilaires()).isEmpty();
    }

    @Test
    void calcule_les_statistiques_dhistorique_fournisseur() {
        when(documentRepository.rechercherParFournisseur(eq(entrepriseId), eq("Wave Money"), anyInt()))
                .thenReturn(List.of(
                        piece(LocalDate.of(2027, 1, 1), new BigDecimal("10000")),
                        piece(LocalDate.of(2027, 1, 8), new BigDecimal("20000")),
                        piece(LocalDate.of(2027, 1, 15), new BigDecimal("30000"))));

        HistoriqueFournisseurDTO resultat = agentToolsService.consulterHistoriqueFournisseur(entrepriseId, "Wave Money");

        assertThat(resultat.nombrePieces()).isEqualTo(3);
        assertThat(resultat.montantMoyen()).isEqualByComparingTo("20000");
        assertThat(resultat.montantMin()).isEqualByComparingTo("10000");
        assertThat(resultat.montantMax()).isEqualByComparingTo("30000");
        assertThat(resultat.dernieresPieces()).hasSize(3);
    }

    @Test
    void historique_vide_ne_calcule_aucune_statistique() {
        when(documentRepository.rechercherParFournisseur(any(), any(), anyInt())).thenReturn(List.of());

        HistoriqueFournisseurDTO resultat = agentToolsService.consulterHistoriqueFournisseur(entrepriseId, "Inconnu");

        assertThat(resultat.nombrePieces()).isZero();
        assertThat(resultat.montantMoyen()).isNull();
        assertThat(resultat.montantMin()).isNull();
        assertThat(resultat.montantMax()).isNull();
    }

    @Test
    void delegue_la_demande_de_clarification_et_mappe_la_reponse() {
        DemandePiece demandeSauvegardee = new DemandePiece();
        demandeSauvegardee.setId(UUID.randomUUID());
        demandeSauvegardee.setLibelle("Preciser la nature de la depense");
        demandeSauvegardee.setStatut(StatutDemandePiece.EN_ATTENTE);
        DemandePieceDto dto = DemandePieceDto.from(demandeSauvegardee);
        when(demandePieceService.creerDepuisAgent(entrepriseId, "Preciser la nature de la depense", "desc"))
                .thenReturn(dto);

        DemandeClarificationResponseDTO resultat = agentToolsService.demanderClarification(
                entrepriseId, "Preciser la nature de la depense", "desc");

        assertThat(resultat.demandeId()).isEqualTo(dto.id());
        assertThat(resultat.statut()).isEqualTo("EN_ATTENTE");
    }
}
