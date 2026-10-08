package com.example.comptable.banque;

/** Qui est a l origine du rapprochement : le systeme (cas certain ou proposition), l IA (proposition validee par un humain), un utilisateur, ou une reprise. */
public enum SourceReconciliation {
    SYSTEM_EXACT,
    SYSTEM_PROPOSAL,
    USER,
    AI_PROPOSAL,
    LEGACY
}
