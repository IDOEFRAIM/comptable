package com.example.comptable.banque;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.LocalDate;
import java.util.HexFormat;
import java.util.UUID;

/**
 * Empreinte deterministe d'une transaction bancaire : compte + date + montant + sens + reference + libelle normalise + rang d'occurrence.
 *
 * <p>Le MONTANT SEUL ne suffit jamais a declarer un doublon. Le rang d'occurrence distingue deux lignes strictement identiques d'un meme
 * releve (deux prelevements identiques le meme jour sont deux operations) tout en restant stable d'un import a l'autre : reimporter le
 * meme fichier redonne exactement les memes empreintes.</p>
 */
public final class BankFingerprint {

    private BankFingerprint() {
    }

    public static String calculer(UUID compteId, LocalDate date, BigDecimal montant, String reference, String libelleNormalise, int occurrence) {
        String sens = montant.signum() >= 0 ? "CREDIT" : "DEBIT";
        String brut = String.join("|", "v1", compteId.toString(), date.toString(), montant.abs().setScale(2, RoundingMode.HALF_UP).toPlainString(),
                sens, reference == null ? "" : BankLabelNormalizer.normaliser(reference), libelleNormalise == null ? "" : libelleNormalise,
                Integer.toString(occurrence));
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(brut.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
