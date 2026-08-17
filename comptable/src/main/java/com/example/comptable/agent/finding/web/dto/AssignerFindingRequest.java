package com.example.comptable.agent.finding.web.dto;

import java.time.LocalDate;
import java.util.UUID;

public record AssignerFindingRequest(UUID assignedToId, LocalDate dueDate) {
}
