package com.example.comptable.comptabilite.web.dto;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

/** {@code ecart} : toujours present, egal a zero pour un lettrage integral (le cas historique) ;
 * non nul uniquement pour un lettrage partiel explicitement demande (montant reste du/a payer,
 * toujours positif, quel que soit le sens debit/credit du solde residuel). */
public record LettrageResultDto(String code, List<UUID> ligneIds, BigDecimal ecart) {
}
