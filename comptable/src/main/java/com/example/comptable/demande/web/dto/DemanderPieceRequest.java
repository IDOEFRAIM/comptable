package com.example.comptable.demande.web.dto;

import java.time.LocalDate;

/** Demande du justificatif d'une operation (constat MISSING_DOCUMENT) : echeance, message et canal sont facultatifs. */
public record DemanderPieceRequest(LocalDate dateLimite, String message, String canal) {
}
