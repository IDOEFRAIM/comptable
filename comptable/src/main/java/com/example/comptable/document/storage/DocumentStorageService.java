package com.example.comptable.document.storage;

import org.springframework.core.io.Resource;

import java.io.IOException;
import java.io.InputStream;
import java.util.UUID;

/**
 * Abstraction de stockage des pieces justificatives, isolee par tenant/entreprise.
 * Implementation locale pour le MVP ; une implementation S3 (MinIO en prod on-premise
 * ou AWS S3) peut se brancher plus tard sans changer les appelants.
 */
public interface DocumentStorageService {

    StoredFile stocker(UUID tenantId, UUID entrepriseId, String nomFichierOriginal, InputStream contenu) throws IOException;

    Resource charger(String cheminFichier);
}
