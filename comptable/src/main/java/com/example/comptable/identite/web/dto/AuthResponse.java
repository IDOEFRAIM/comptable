package com.example.comptable.identite.web.dto;

import java.util.List;
import java.util.UUID;

public record AuthResponse(
        String token,
        UUID utilisateurId,
        UUID tenantId,
        String email,
        List<String> roles
) {
}
