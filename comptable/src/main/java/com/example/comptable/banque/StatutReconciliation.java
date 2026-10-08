package com.example.comptable.banque;

/** Etat d un rapprochement bancaire ; seul CONFIRMED lie reellement la transaction (et pose le pointeur historique). */
public enum StatutReconciliation {
    PROPOSED,
    CONFIRMED,
    REJECTED,
    REVERSED
}
