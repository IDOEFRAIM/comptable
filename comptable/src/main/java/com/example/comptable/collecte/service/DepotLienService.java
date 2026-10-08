package com.example.comptable.collecte.service;

import com.example.comptable.demande.domain.DemandePiece;
import com.example.comptable.demande.repository.DemandePieceRepository;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.time.OffsetDateTime;
import java.util.Base64;
import java.util.Optional;
import java.util.UUID;

/**
 * Lien de depot opaque et non devinable : {@code jeton = base64url(idDemande) + "." + base64url(HMAC-SHA256(secret, idDemande))}.
 * Le jeton est SIGNE : sans le secret serveur on ne peut ni en fabriquer un, ni passer d'une demande a une autre en changeant
 * l'identifiant. Il se recalcule (les relances reutilisent le meme lien), son expiration est portee par la demande, et il
 * cesse de fonctionner quand la demande est close. Aucun secret n'est stocke en base.
 */
@Service
public class DepotLienService {

    private final DemandePieceRepository demandePieceRepository;
    private final CollecteConfig config;
    private final byte[] secret;

    public DepotLienService(DemandePieceRepository demandePieceRepository, CollecteConfig config,
                            @Value("${collecte.lien-depot.secret:${security.jwt.secret}}") String secret) {
        this.demandePieceRepository = demandePieceRepository;
        this.config = config;
        this.secret = ("depot-collecte|" + secret).getBytes(StandardCharsets.UTF_8);
    }

    /** Lien complet a placer dans un message, ou {@code null} si aucune URL de base n'est configuree. */
    public String lien(DemandePiece demande) {
        if (config.lienDepotBaseUrl() == null || config.lienDepotBaseUrl().isBlank()) {
            return null;
        }
        if (demande.getTokenExpireAt() == null) {
            demande.setTokenExpireAt(OffsetDateTime.now().plusDays(config.tokenValiditeJours()));
        }
        return config.lienDepotBaseUrl().replaceAll("/+$", "") + "/" + jeton(demande.getId());
    }

    /** Jeton signe d'une demande (le lien complet est {@link #lien}). */
    public String jeton(UUID demandeId) {
        return Base64.getUrlEncoder().withoutPadding().encodeToString(octets(demandeId)) + "." + signature(demandeId);
    }

    /** La demande ouverte et non expiree correspondant a ce jeton, ou vide (jeton forge, inconnu, expire ou demande close). */
    public Optional<DemandePiece> verifier(String jeton) {
        if (jeton == null || jeton.length() > 120 || !jeton.contains(".")) {
            return Optional.empty();
        }
        String[] parties = jeton.split("\\.", 2);
        UUID id;
        try {
            ByteBuffer buffer = ByteBuffer.wrap(Base64.getUrlDecoder().decode(parties[0]));
            id = new UUID(buffer.getLong(), buffer.getLong());
        } catch (RuntimeException e) {
            return Optional.empty();
        }
        // Comparaison en temps constant : la signature n'est jamais deduite d'un delai de reponse.
        if (!MessageDigest.isEqual(signature(id).getBytes(StandardCharsets.UTF_8), parties[1].getBytes(StandardCharsets.UTF_8))) {
            return Optional.empty();
        }
        return demandePieceRepository.findById(id)
                .filter(d -> d.getStatut().estOuverte())
                .filter(d -> d.getTokenExpireAt() == null || d.getTokenExpireAt().isAfter(OffsetDateTime.now()));
    }

    private String signature(UUID demandeId) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret, "HmacSHA256"));
            return Base64.getUrlEncoder().withoutPadding().encodeToString(mac.doFinal(octets(demandeId)));
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("HMAC indisponible", e);
        }
    }

    private static byte[] octets(UUID id) {
        return ByteBuffer.allocate(16).putLong(id.getMostSignificantBits()).putLong(id.getLeastSignificantBits()).array();
    }
}
