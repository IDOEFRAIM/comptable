package com.example.comptable.finance.forecast.domain;

/**
 * Confiance globale du forecast (mission P2, section 16 du plan) - deterministe, calculee par
 * {@code CashForecastService} a partir de la profondeur d'historique disponible et de la
 * proportion KNOWN/EXPECTED/PREDICTED - jamais demandee au LLM ("le moteur doit determiner cela").
 */
public enum ForecastConfidence {
    LOW,
    MEDIUM,
    HIGH
}
