package com.example.comptable.copilot.repository;

import com.example.comptable.copilot.domain.CopilotQuery;
import org.springframework.data.jpa.repository.JpaRepository;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

public interface CopilotQueryRepository extends JpaRepository<CopilotQuery, UUID> {

    List<CopilotQuery> findByTenantIdAndCreatedAtAfter(UUID tenantId, OffsetDateTime depuis);
}
