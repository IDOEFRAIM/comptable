package com.example.comptable.collecte.domain;

/** INITIAL : premiere demande ; REMINDER : relance CLIENT (le message precedent est parti, le client n'a pas repondu). */
public enum TypeMessage {
    INITIAL,
    REMINDER
}
