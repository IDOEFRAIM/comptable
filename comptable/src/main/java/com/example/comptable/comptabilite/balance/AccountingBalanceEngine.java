package com.example.comptable.comptabilite.balance;

import com.example.comptable.comptabilite.domain.ExerciceComptable;
import com.example.comptable.comptabilite.repository.ExerciceComptableRepository;
import com.example.comptable.comptabilite.repository.LigneEcritureRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.HexFormat;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * SOURCE UNIQUE des soldes comptables. Grand livre, balance generale, bilan et compte de resultat (legacy, puis les futurs etats financiers) calculent
 * leurs montants ICI et nulle part ailleurs : une seule agregation SQL par appel, jamais une requete par compte.
 *
 * <p>Definitions (voir {@code docs/accounting-balance-engine.md}) : {@code ouverture} = solde net de toutes les ecritures du perimetre de la vue datees
 * STRICTEMENT avant {@code from} ; {@code mouvements} = cumuls debit/credit des ecritures datees dans [{@code from} ; {@code to}] (bornes incluses) ;
 * {@code cloture} = ouverture + mouvements. L'ouverture n'est jamais fabriquee par « solde courant moins mouvements » : c'est une somme directe de
 * l'historique. Tous les montants sont des {@link BigDecimal} sans arrondi (une somme de montants a 2 decimales n'en demande pas).</p>
 *
 * <p>Le moteur ne controle pas les droits d'acces : l'appelant verifie l'acces a l'entreprise. L'isolation multi-tenant est assuree ici par le filtre
 * {@code compte.entreprise}.</p>
 */
@Service
@RequiredArgsConstructor
public class AccountingBalanceEngine {

    private final LigneEcritureRepository ligneEcritureRepository;
    private final ExerciceComptableRepository exerciceComptableRepository;

    /** Soldes de tous les comptes mouvementes jusqu'a {@code to}, tries par numero. */
    @Transactional(readOnly = true)
    public List<AccountBalance> balances(UUID entrepriseId, LocalDate from, LocalDate to, ReportingView view) {
        verifierBornes(from, to);
        return ligneEcritureRepository.agregerSoldes(entrepriseId, from, to, view.statuts(), view.natures()).stream()
                .map(AccountingBalanceEngine::enSolde)
                .toList();
    }

    /** Solde d'un seul compte de l'entreprise ; vide si le compte n'a aucune ecriture du perimetre jusqu'a {@code to}. */
    @Transactional(readOnly = true)
    public Optional<AccountBalance> balance(UUID entrepriseId, UUID compteId, LocalDate from, LocalDate to, ReportingView view) {
        verifierBornes(from, to);
        return ligneEcritureRepository.agregerSoldesDuCompte(entrepriseId, compteId, from, to, view.statuts(), view.natures()).stream()
                .map(AccountingBalanceEngine::enSolde)
                .findFirst();
    }

    /** Soldes sur les bornes de l'exercice. */
    @Transactional(readOnly = true)
    public List<AccountBalance> balancesExercice(UUID entrepriseId, UUID exerciceId, ReportingView view) {
        ExerciceComptable exercice = chargerExercice(entrepriseId, exerciceId);
        return balances(entrepriseId, exercice.getDateDebut(), exercice.getDateFin(), view);
    }

    /**
     * Exercice N et exercice precedent REEL (N-1) dans la meme vue : le precedent est celui dont la fin precede le plus recemment le debut de N, pas
     * « date moins un an » (exercices courts ou de plus de 12 mois pris en charge).
     */
    @Transactional(readOnly = true)
    public ExerciseBalances avecComparatif(UUID entrepriseId, UUID exerciceId, ReportingView view) {
        ExerciceComptable exercice = chargerExercice(entrepriseId, exerciceId);
        List<AccountBalance> courant = balances(entrepriseId, exercice.getDateDebut(), exercice.getDateFin(), view);
        Optional<ExerciceComptable> precedent = exerciceComptableRepository
                .findFirstByEntrepriseIdAndDateFinLessThanOrderByDateFinDesc(entrepriseId, exercice.getDateDebut());
        List<AccountBalance> soldesPrecedents = precedent
                .map(p -> balances(entrepriseId, p.getDateDebut(), p.getDateFin(), view))
                .orElse(List.of());
        return new ExerciseBalances(exercice, courant, precedent, soldesPrecedents);
    }

    /** Empreinte technique des donnees du perimetre jusqu'a {@code to} : preuve de provenance pour un futur snapshot (voir {@link DatasetVersion}). */
    @Transactional(readOnly = true)
    public DatasetVersion datasetVersion(UUID entrepriseId, UUID exerciceId, LocalDate from, LocalDate to, ReportingView view) {
        Object[] ligne = ligneEcritureRepository.empreinte(entrepriseId, to, view.statuts(), view.natures()).get(0);
        long lignes = ((Number) ligne[0]).longValue();
        long ecritures = ((Number) ligne[1]).longValue();
        BigDecimal debit = (BigDecimal) ligne[2];
        BigDecimal credit = (BigDecimal) ligne[3];
        OffsetDateTime derniere = ligne[4] == null ? null : toOffset(ligne[4]);
        String brut = String.join("|", entrepriseId.toString(), view.name(), String.valueOf(from), String.valueOf(to), String.valueOf(lignes),
                String.valueOf(ecritures), debit.stripTrailingZeros().toPlainString(), credit.stripTrailingZeros().toPlainString(),
                String.valueOf(derniere));
        return new DatasetVersion(entrepriseId, exerciceId, view, from, to, OffsetDateTime.now(), lignes, ecritures, debit, credit, derniere, sha256(brut));
    }

    private static OffsetDateTime toOffset(Object valeur) {
        if (valeur instanceof OffsetDateTime o) {
            return o;
        }
        if (valeur instanceof java.time.Instant i) {
            return i.atOffset(java.time.ZoneOffset.UTC);
        }
        return OffsetDateTime.parse(valeur.toString());
    }

    private static AccountBalance enSolde(Object[] ligne) {
        return AccountBalance.of((UUID) ligne[0], (String) ligne[1], (String) ligne[2],
                (BigDecimal) ligne[3], (BigDecimal) ligne[4], (BigDecimal) ligne[5]);
    }

    private static void verifierBornes(LocalDate from, LocalDate to) {
        if (from == null || to == null || to.isBefore(from)) {
            throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY, "Periode invalide : la date de fin doit etre posterieure ou egale a la date de debut");
        }
    }

    private ExerciceComptable chargerExercice(UUID entrepriseId, UUID exerciceId) {
        return exerciceComptableRepository.findById(exerciceId)
                .filter(e -> e.getEntreprise().getId().equals(entrepriseId))
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Exercice introuvable"));
    }

    private static String sha256(String texte) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(texte.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
