package com.example.comptable.etatsfinanciers.definition;

/** Source reglementaire d'une definition : document, empreinte du PDF archive, pages imprimees, matrice de reference. */
public record SourceReference(String document, String sha256, String pages, String matrix, String note) {
}
