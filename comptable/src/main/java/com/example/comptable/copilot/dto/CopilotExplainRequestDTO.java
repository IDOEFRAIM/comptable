package com.example.comptable.copilot.dto;

import java.util.Map;

/** Miroir de ai-service/app/schemas/copilot.py::CopilotExplainRequest. */
public record CopilotExplainRequestDTO(String question, String intent, Map<String, Object> facts) {
}
