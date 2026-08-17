package com.example.comptable.document.storage;

/** Resultat d'un stockage : cle relative (a persister en base) et taille reelle ecrite. */
public record StoredFile(String cheminFichier, long tailleOctets) {
}
