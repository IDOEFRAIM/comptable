package com.example.comptable.agent.rapprochement.dto;

import java.util.List;

/** Miroir JSON (camelCase) de ai-service/app/schemas/rapprochement.py::RapprochementSemantiqueRequest. */
public record RapprochementSemantiqueRequestDTO(
        List<LigneReleveDTO> lignesNonRapprochees,
        List<LigneEcritureCandidateDTO> candidats
) {
}
