package com.example.comptable.audit;

import com.example.comptable.audit.domain.AuditEvent;
import com.example.comptable.audit.repository.AuditEventRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Captor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class AuditTrailServiceTest {

    @Mock
    private AuditEventRepository auditEventRepository;

    @Captor
    private ArgumentCaptor<AuditEvent> captor;

    @InjectMocks
    private AuditTrailService auditTrailService;

    private UUID tenantId;
    private UUID utilisateurId;

    @BeforeEach
    void setUp() {
        tenantId = UUID.randomUUID();
        utilisateurId = UUID.randomUUID();
        when(auditEventRepository.save(captor.capture())).thenAnswer(inv -> inv.getArgument(0));
    }

    @Test
    void enregistrerActionHumaine_marque_la_source_comme_humaine() {
        auditTrailService.enregistrerActionHumaine(tenantId, utilisateurId, "FINDING_RESOLU", "FINDING",
                "abc-123", Map.of("type", "MISSING_EVIDENCE"));

        AuditEvent event = captor.getValue();
        assertThat(event.getTenantId()).isEqualTo(tenantId);
        assertThat(event.getUtilisateurId()).isEqualTo(utilisateurId);
        assertThat(event.getAction()).isEqualTo("FINDING_RESOLU");
        assertThat(event.getEntite()).isEqualTo("FINDING");
        assertThat(event.getEntiteId()).isEqualTo("abc-123");
        assertThat(event.getDetails()).containsEntry("source", "HUMAIN").containsEntry("type", "MISSING_EVIDENCE");
    }

    @Test
    void enregistrerDecisionIa_ne_melange_jamais_source_ia_et_humaine() {
        auditTrailService.enregistrerDecisionIa(tenantId, "MATCH_PROPOSE", "LIGNE_RELEVE", "ligne-1",
                "claude-sonnet-5", "matching-v1", "MATCH_AUTO", false, Map.of("confidence", 0.97));

        AuditEvent event = captor.getValue();
        assertThat(event.getUtilisateurId()).isNull();
        assertThat(event.getDetails())
                .containsEntry("source", "IA")
                .containsEntry("model", "claude-sonnet-5")
                .containsEntry("promptVersion", "matching-v1")
                .containsEntry("decision", "MATCH_AUTO")
                .containsEntry("userValidation", false)
                .containsEntry("confidence", 0.97);
    }
}
