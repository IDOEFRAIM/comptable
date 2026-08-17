package com.example.comptable.agent.whatsapp;

import com.example.comptable.agent.pipeline.PieceJustificativeAgentService;
import com.example.comptable.agent.whatsapp.domain.WhatsappEvenementTraite;
import com.example.comptable.agent.whatsapp.dto.DonneesExtraitesDTO;
import com.example.comptable.agent.whatsapp.dto.WhatsAppDocumentPayloadDTO;
import com.example.comptable.agent.whatsapp.repository.WhatsappEvenementTraiteRepository;
import com.example.comptable.document.domain.CanalSource;
import com.example.comptable.document.domain.TypeDocument;
import com.example.comptable.document.service.DocumentService;
import com.example.comptable.document.web.dto.DocumentDto;
import com.example.comptable.entreprise.domain.UtilisateurEntreprise;
import com.example.comptable.entreprise.repository.UtilisateurEntrepriseRepository;
import com.example.comptable.identite.domain.Utilisateur;
import com.example.comptable.identite.repository.UtilisateurRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class WhatsAppIngestionServiceTest {

    @Mock
    private WhatsappEvenementTraiteRepository evenementTraiteRepository;
    @Mock
    private UtilisateurRepository utilisateurRepository;
    @Mock
    private UtilisateurEntrepriseRepository utilisateurEntrepriseRepository;
    @Mock
    private DocumentService documentService;
    @Mock
    private PieceJustificativeAgentService pieceJustificativeAgentService;

    private WhatsAppIngestionService ingestionService;

    private UUID utilisateurId;
    private UUID entrepriseId;

    @BeforeEach
    void setUp() {
        ingestionService = new WhatsAppIngestionService(evenementTraiteRepository, utilisateurRepository,
                utilisateurEntrepriseRepository, documentService, pieceJustificativeAgentService);

        utilisateurId = UUID.randomUUID();
        entrepriseId = UUID.randomUUID();
    }

    private Utilisateur utilisateurClient() {
        Utilisateur u = new Utilisateur();
        u.setId(utilisateurId);
        u.setTelephone("+225 01 23 45 67 89");
        return u;
    }

    private WhatsAppDocumentPayloadDTO payload(String messageId, String clientPhone) {
        DonneesExtraitesDTO donnees = new DonneesExtraitesDTO("Wave Money", new BigDecimal("10000"),
                new BigDecimal("1800"), new BigDecimal("11800"), "XOF", "WAVE", LocalDate.of(2027, 1, 5), 0.9);
        return new WhatsAppDocumentPayloadDTO(clientPhone, messageId, donnees, List.of());
    }

    @Test
    void ingere_une_piece_dun_client_reconnu_par_son_numero_et_demarre_le_pipeline() {
        when(evenementTraiteRepository.existsByMessageId("wamid.001")).thenReturn(false);
        when(utilisateurRepository.findByTelephoneNormalise("2250123456789")).thenReturn(Optional.of(utilisateurClient()));
        when(utilisateurEntrepriseRepository.findByIdUtilisateurId(utilisateurId))
                .thenReturn(List.of(new UtilisateurEntreprise(utilisateurId, entrepriseId)));
        UUID documentId = UUID.randomUUID();
        DocumentDto documentDto = new DocumentDto(documentId, entrepriseId, "RECU", "WHATSAPP",
                "whatsapp-wamid.001.jpg", "image/jpeg", 3L, "TRAITE", null, null, null, null, null, null, null,
                List.of(), null);
        when(documentService.ingererAvecExtractionExterne(any(), any(), any(), any(), anyString(), anyString(),
                any(), any())).thenReturn(documentDto);

        Optional<DocumentDto> resultat = ingestionService.ingererAvecExtractionExterne(
                payload("wamid.001", "225 01 23 45 67 89"), new byte[]{1, 2, 3}, "image/jpeg");

        assertThat(resultat).contains(documentDto);
        verify(documentService).ingererAvecExtractionExterne(eq(entrepriseId), eq(TypeDocument.RECU),
                eq(CanalSource.WHATSAPP), eq(new byte[]{1, 2, 3}), anyString(), eq("image/jpeg"), any(), any());
        verify(pieceJustificativeAgentService).demarrer(documentId, entrepriseId);
        ArgumentCaptor<WhatsappEvenementTraite> captor = ArgumentCaptor.forClass(WhatsappEvenementTraite.class);
        verify(evenementTraiteRepository).save(captor.capture());
        assertThat(captor.getValue().getMessageId()).isEqualTo("wamid.001");
    }

    @Test
    void ignore_un_message_deja_reclame() {
        when(evenementTraiteRepository.existsByMessageId("wamid.002")).thenReturn(true);

        Optional<DocumentDto> resultat = ingestionService.ingererAvecExtractionExterne(
                payload("wamid.002", "22501234567"), new byte[]{1}, "image/jpeg");

        assertThat(resultat).isEmpty();
        verify(evenementTraiteRepository, never()).save(any());
        verify(documentService, never()).ingererAvecExtractionExterne(any(), any(), any(), any(), any(), any(), any(), any());
        verify(pieceJustificativeAgentService, never()).demarrer(any(), any());
    }

    @Test
    void reclame_mais_ningere_rien_si_lexpediteur_est_inconnu() {
        when(evenementTraiteRepository.existsByMessageId("wamid.004")).thenReturn(false);
        when(utilisateurRepository.findByTelephoneNormalise(anyString())).thenReturn(Optional.empty());

        Optional<DocumentDto> resultat = ingestionService.ingererAvecExtractionExterne(
                payload("wamid.004", "22509999999"), new byte[]{1}, "image/jpeg");

        assertThat(resultat).isEmpty();
        verify(evenementTraiteRepository).save(any());
        verify(documentService, never()).ingererAvecExtractionExterne(any(), any(), any(), any(), any(), any(), any(), any());
    }

    @Test
    void reclame_mais_ningere_rien_si_le_numero_est_rattache_a_plusieurs_entreprises() {
        when(evenementTraiteRepository.existsByMessageId("wamid.005")).thenReturn(false);
        when(utilisateurRepository.findByTelephoneNormalise(anyString())).thenReturn(Optional.of(utilisateurClient()));
        when(utilisateurEntrepriseRepository.findByIdUtilisateurId(utilisateurId)).thenReturn(List.of(
                new UtilisateurEntreprise(utilisateurId, UUID.randomUUID()),
                new UtilisateurEntreprise(utilisateurId, UUID.randomUUID())));

        Optional<DocumentDto> resultat = ingestionService.ingererAvecExtractionExterne(
                payload("wamid.005", "22501234567"), new byte[]{1}, "image/jpeg");

        assertThat(resultat).isEmpty();
        verify(evenementTraiteRepository).save(any());
        verify(documentService, never()).ingererAvecExtractionExterne(any(), any(), any(), any(), any(), any(), any(), any());
    }

    @Test
    void normalise_le_numero_avant_la_recherche_dutilisateur() {
        when(evenementTraiteRepository.existsByMessageId("wamid.007")).thenReturn(false);
        when(utilisateurRepository.findByTelephoneNormalise("2250102030405")).thenReturn(Optional.empty());

        ingestionService.ingererAvecExtractionExterne(payload("wamid.007", "+225 01 02 03 04 05"),
                new byte[]{1}, "image/jpeg");

        verify(utilisateurRepository).findByTelephoneNormalise("2250102030405");
    }
}
