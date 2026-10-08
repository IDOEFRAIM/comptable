package com.example.comptable.document.storage;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.FileSystemResource;
import org.springframework.core.io.Resource;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.UUID;

/**
 * Stockage sur disque local, organise en {racine}/{tenantId}/{entrepriseId}/{uuid}-{nom}.
 * L'isolation par dossier tenant/entreprise est le mecanisme d'isolation ; a remplacer par
 * une implementation S3 (bucket/prefix par tenant) pour un deploiement multi-instance.
 */
@Service
public class LocalDocumentStorageService implements DocumentStorageService {

    private final Path racine;

    public LocalDocumentStorageService(@Value("${storage.documents.root-dir}") String rootDir) {
        this.racine = Path.of(rootDir).toAbsolutePath().normalize();
    }

    @Override
    public StoredFile stocker(UUID tenantId, UUID entrepriseId, String nomFichierOriginal, InputStream contenu) throws IOException {
        Path dossier = racine.resolve(tenantId.toString()).resolve(entrepriseId.toString());
        Files.createDirectories(dossier);

        String cle = UUID.randomUUID() + "-" + assainir(nomFichierOriginal);
        Path cible = dossier.resolve(cle);

        long taille = Files.copy(contenu, cible, StandardCopyOption.REPLACE_EXISTING);
        String cheminRelatif = tenantId + "/" + entrepriseId + "/" + cle;
        return new StoredFile(cheminRelatif, taille);
    }

    @Override
    public Resource charger(String cheminFichier) {
        Path fichier = racine.resolve(cheminFichier).normalize();
        if (!fichier.startsWith(racine)) {
            throw new SecurityException("Chemin de fichier invalide");
        }
        return new FileSystemResource(fichier);
    }

    /** Garde uniquement le nom (jamais un chemin, séparateurs / et \\ compris), remplace tout caractère inattendu, borne la longueur ; un nom absent ou vide devient « document ». */
    static String assainir(String nomFichier) {
        String nom = nomFichier == null ? "" : nomFichier.substring(Math.max(nomFichier.lastIndexOf('/'), nomFichier.lastIndexOf('\\')) + 1);
        nom = nom.replaceAll("[^a-zA-Z0-9._-]", "_").replaceAll("^\\.+", "_");
        if (nom.isBlank() || nom.chars().allMatch(c -> c == '_')) {
            return "document";
        }
        return nom.length() > 100 ? nom.substring(nom.length() - 100) : nom;
    }
}
