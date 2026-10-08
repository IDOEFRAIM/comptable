package com.example.comptable.controle;

/** Gravite d'un constat de controle. {@code BLOCKING} empeche une action (ex. cloture) ; les autres
 * ne bloquent jamais d'elles-memes. */
public enum ControlSeverity {
    INFO,
    WARNING,
    ERROR,
    BLOCKING
}
