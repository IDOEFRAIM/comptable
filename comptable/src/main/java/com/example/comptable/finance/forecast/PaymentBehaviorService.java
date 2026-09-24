package com.example.comptable.finance.forecast;

import com.example.comptable.comptabilite.domain.Facture;
import com.example.comptable.comptabilite.domain.LigneEcriture;
import com.example.comptable.comptabilite.domain.TypeFacture;
import com.example.comptable.comptabilite.repository.FactureRepository;
import com.example.comptable.comptabilite.repository.LigneEcritureRepository;
import com.example.comptable.finance.forecast.web.dto.PaymentBehaviorDto;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static java.util.stream.Collectors.groupingBy;

/**
 * Comportement de paiement historique par client (mission P2, "13-Week Cash Forecast", section 11
 * du plan) - calcule EXCLUSIVEMENT a partir de donnees deja reelles : les lignes deja lettrees
 * (paiements reellement reconcilies, {@link LigneEcritureRepository#rechercherLignesLettreesPourTiers})
 * correlees a l'echeance contractuelle de la facture d'origine ({@code Facture.dateEcheance}, via
 * {@code Facture.ecritureId}).
 * <p>
 * Limite structurelle honnete : {@code Facture} ne porte aucune colonne "date de paiement" - le
 * seul horodatage fiable d'un paiement reellement recu est la date de l'ecriture de reglement qui
 * a lettre la ligne client, retrouvee ici via le lettrage (meme mecanisme que
 * {@code LettrageService.lettrerAutomatiquement}). Seuls les groupes de lettrage NON AMBIGUS (une
 * ligne debit, une ligne credit) sont exploites - un groupe multi-lignes (paiements echelonnes,
 * avoirs...) est ignore plutot que d'inventer une correlation arbitraire.
 */
@Service
@RequiredArgsConstructor
public class PaymentBehaviorService {

    /** En-dessous de ce nombre de paiements reconcilies et dates, la mediane/moyenne ne serait
     * pas statistiquement significative - retourne explicitement sufficientHistory=false plutot
     * qu'un chiffre trompeur (section 11 du plan : "seulement si le volume historique est
     * suffisant, sinon insufficient_history"). */
    public static final int ECHANTILLON_MINIMUM = 3;

    private final LigneEcritureRepository ligneEcritureRepository;
    private final FactureRepository factureRepository;

    @Transactional(readOnly = true)
    public PaymentBehaviorDto analyser(UUID tiersId, String tiersNom) {
        List<LigneEcriture> lignesLettrees = ligneEcritureRepository.rechercherLignesLettreesPourTiers(tiersId);
        Map<String, List<LigneEcriture>> parCodeLettrage = lignesLettrees.stream()
                .collect(groupingBy(LigneEcriture::getLettrage));

        Map<UUID, LocalDate> datePaiementParEcritureFacture = new HashMap<>();
        for (List<LigneEcriture> groupe : parCodeLettrage.values()) {
            List<LigneEcriture> debits = groupe.stream().filter(l -> l.getDebit().signum() > 0).toList();
            List<LigneEcriture> credits = groupe.stream().filter(l -> l.getCredit().signum() > 0).toList();
            if (debits.size() != 1 || credits.size() != 1) {
                continue; // groupe ambigu (paiement echelonne, avoir...) : ignore, jamais devine.
            }
            datePaiementParEcritureFacture.put(debits.get(0).getEcriture().getId(), credits.get(0).getEcriture().getDateEcriture());
        }

        if (datePaiementParEcritureFacture.isEmpty()) {
            return new PaymentBehaviorDto(tiersId, tiersNom, 0, null, null, false);
        }

        List<Facture> factures = factureRepository.findByEcritureIdIn(new ArrayList<>(datePaiementParEcritureFacture.keySet()));
        List<Long> delaisJours = new ArrayList<>();
        for (Facture facture : factures) {
            if (facture.getType() != TypeFacture.FACTURE || facture.getDateEcheance() == null) {
                continue;
            }
            LocalDate datePaiement = datePaiementParEcritureFacture.get(facture.getEcritureId());
            if (datePaiement == null) {
                continue;
            }
            delaisJours.add(ChronoUnit.DAYS.between(facture.getDateEcheance(), datePaiement));
        }

        if (delaisJours.size() < ECHANTILLON_MINIMUM) {
            return new PaymentBehaviorDto(tiersId, tiersNom, delaisJours.size(), null, null, false);
        }

        double moyenne = delaisJours.stream().mapToLong(Long::longValue).average().orElse(0);
        long mediane = calculerMediane(delaisJours);
        return new PaymentBehaviorDto(tiersId, tiersNom, delaisJours.size(), mediane, moyenne, true);
    }

    private long calculerMediane(List<Long> valeurs) {
        List<Long> triees = new ArrayList<>(valeurs);
        Collections.sort(triees);
        int milieu = triees.size() / 2;
        if (triees.size() % 2 == 0) {
            return Math.round((triees.get(milieu - 1) + triees.get(milieu)) / 2.0);
        }
        return triees.get(milieu);
    }
}
