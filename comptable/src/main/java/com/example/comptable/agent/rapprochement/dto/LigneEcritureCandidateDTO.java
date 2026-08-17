package com.example.comptable.agent.rapprochement.dto;

import com.example.comptable.comptabilite.domain.LigneEcriture;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.UUID;

/** Miroir JSON (camelCase) de ai-service/app/schemas/rapprochement.py::LigneEcritureCandidate,
 * envoye a {@code POST /api/v1/rapprochement/semantique}. Debit/credit distincts (pas un
 * montant signe unique) : c'est le contrat attendu cote Python pour reproduire la regle de
 * direction (positif = doit correspondre a un debit, negatif a un credit). */
public record LigneEcritureCandidateDTO(
        UUID id, LocalDate dateEcriture, BigDecimal debit, BigDecimal credit, String libelle, String tiers
) {
    public static LigneEcritureCandidateDTO from(LigneEcriture ligne) {
        String nomTiers = ligne.getTiers() != null ? ligne.getTiers().getNom() : null;
        return new LigneEcritureCandidateDTO(ligne.getId(), ligne.getEcriture().getDateEcriture(),
                ligne.getDebit(), ligne.getCredit(), ligne.getLibelle(), nomTiers);
    }
}
