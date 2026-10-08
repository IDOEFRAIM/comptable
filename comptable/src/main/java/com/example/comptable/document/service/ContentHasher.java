package com.example.comptable.document.service;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

/** SHA-256 hexadecimal du contenu reel du fichier : calcule cote backend, jamais fourni par le client. */
public final class ContentHasher {

    private ContentHasher() {
    }

    public static String sha256(byte[] contenu) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(contenu));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 indisponible", e);
        }
    }
}
