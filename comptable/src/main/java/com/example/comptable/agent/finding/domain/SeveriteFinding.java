package com.example.comptable.agent.finding.domain;

/** Correspond aux badges 🔴/🟠/🟡 de l'Inbox (mission P0, section 9). */
public enum SeveriteFinding {
    /** 🔴 bloquant - empeche la cloture ou une decision comptable sure. */
    CRITICAL,
    /** 🟠 important - a traiter, n'empeche rien immediatement. */
    IMPORTANT,
    /** 🟡 a verifier - signal faible, priorite basse. */
    REVIEW
}
