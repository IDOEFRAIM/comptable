package com.example.comptable.agent.finding.domain;

/** Comment un Finding a cesse d'etre actif. Un constat ecarte ou resolu n'est jamais supprime. */
public enum TypeResolution {
    /** Resolu par un humain (action explicite). */
    MANUAL,
    /** Resolu par le systeme : la cause a disparu au dernier passage des controles. */
    SYSTEM,
    /** Ecarte par un humain avec un motif : le constat n'est pas recree tant que la cause persiste. */
    DISMISSED
}
