package com.example.comptable.controle;

/** Etat d'un constat : {@code OPEN} tant que le probleme existe, {@code RESOLVED} s'il a disparu,
 * {@code DISMISSED} s'il a ete ecarte par un humain (correspond a {@code StatutFinding}). */
public enum ControlStatus {
    OPEN,
    RESOLVED,
    DISMISSED
}
