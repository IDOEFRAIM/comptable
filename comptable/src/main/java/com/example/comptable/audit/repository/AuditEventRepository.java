package com.example.comptable.audit.repository;

import com.example.comptable.audit.domain.AuditEvent;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

public interface AuditEventRepository extends JpaRepository<AuditEvent, Long> {

    List<AuditEvent> findByTenantIdOrderByCreatedAtDesc(UUID tenantId);

    List<AuditEvent> findByEntiteAndEntiteIdOrderByCreatedAtDesc(String entite, String entiteId);
}
