package com.example.comptable.finance;

import com.example.comptable.comptabilite.domain.Facture;
import com.example.comptable.comptabilite.domain.StatutFacture;
import com.example.comptable.comptabilite.domain.TypeFacture;
import com.example.comptable.comptabilite.repository.FactureRepository;
import com.example.comptable.comptabilite.repository.LigneEcritureRepository;
import com.example.comptable.entreprise.service.EntrepriseAccessService;
import com.example.comptable.finance.web.dto.PrevisionTresorerieDto;
import com.example.comptable.fiscalite.domain.DeclarationFiscale;
import com.example.comptable.fiscalite.repository.DeclarationFiscaleRepository;
import com.example.comptable.security.UtilisateurPrincipal;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/**
 * "Cash Forecasting" (mission P1, section 23-25) : horizons 7/30/90 jours. Palier KNOWN =
 * factures clients dont l'echeance tombe dans l'horizon (montant ET date certains) + declarations
 * fiscales dues dans l'horizon (decaissement certain). Palier EXPECTED = factures clients deja en
 * retard, ponderees par une probabilite de recouvrement deterministe basee sur l'anciennete du
 * retard (pas un modele statistique entraine - aucune donnee de paiement historique n'existe
 * encore pour en calibrer un reellement, voir docs/P1_ARCHITECTURE.md). Les paiements fournisseurs
 * ne sont volontairement pas inclus : {@code FactureFournisseur} n'a aucune colonne d'echeance
 * (limite deja documentee en P0), impossible de les dater sans inventer une donnee.
 */
@Service
@RequiredArgsConstructor
public class CashForecastService {

    private static final int[] HORIZONS_JOURS = {7, 30, 90};

    private static final int BORNE_TRANCHE_1 = 30;
    private static final int BORNE_TRANCHE_2 = 60;
    private static final int BORNE_TRANCHE_3 = 90;
    private static final BigDecimal PROBABILITE_TRANCHE_1 = new BigDecimal("0.90");
    private static final BigDecimal PROBABILITE_TRANCHE_2 = new BigDecimal("0.70");
    private static final BigDecimal PROBABILITE_TRANCHE_3 = new BigDecimal("0.50");
    private static final BigDecimal PROBABILITE_TRANCHE_4 = new BigDecimal("0.20");

    private final EntrepriseAccessService entrepriseAccessService;
    private final LigneEcritureRepository ligneEcritureRepository;
    private final FactureRepository factureRepository;
    private final DeclarationFiscaleRepository declarationFiscaleRepository;

    @Transactional(readOnly = true)
    public List<PrevisionTresorerieDto> generer(UUID entrepriseId, UtilisateurPrincipal principal) {
        entrepriseAccessService.verifierAcces(entrepriseId, principal);
        LocalDate aujourdHui = LocalDate.now();

        BigDecimal soldeActuel = ligneEcritureRepository.soldeTresorerie(entrepriseId, aujourdHui);
        BigDecimal encaissementsAttendus = ponderer(factureRepository
                .findByEntrepriseIdAndTypeAndStatutAndDateEcheanceBeforeOrderByDateEcheanceAsc(
                        entrepriseId, TypeFacture.FACTURE, StatutFacture.VALIDEE, aujourdHui), aujourdHui);

        return Arrays.stream(HORIZONS_JOURS)
                .mapToObj(horizon -> construire(entrepriseId, aujourdHui, horizon, soldeActuel, encaissementsAttendus))
                .toList();
    }

    private PrevisionTresorerieDto construire(UUID entrepriseId, LocalDate aujourdHui, int horizonJours,
                                               BigDecimal soldeActuel, BigDecimal encaissementsAttendus) {
        LocalDate limite = aujourdHui.plusDays(horizonJours);

        BigDecimal encaissementsConnus = factureRepository
                .findByEntrepriseIdAndTypeAndStatutAndDateEcheanceBetweenOrderByDateEcheanceAsc(
                        entrepriseId, TypeFacture.FACTURE, StatutFacture.VALIDEE, aujourdHui, limite)
                .stream()
                .map(Facture::montantTtc)
                .reduce(BigDecimal.ZERO, BigDecimal::add);

        BigDecimal decaissementsConnus = declarationFiscaleRepository
                .findByEntrepriseIdAndDateEcheanceBetween(entrepriseId, aujourdHui, limite)
                .stream()
                .map(DeclarationFiscale::getMontant)
                .filter(Objects::nonNull)
                .reduce(BigDecimal.ZERO, BigDecimal::add);

        BigDecimal soldePrevisionnel = soldeActuel
                .add(encaissementsConnus)
                .add(encaissementsAttendus)
                .subtract(decaissementsConnus);

        return new PrevisionTresorerieDto(aujourdHui, horizonJours, soldeActuel, encaissementsConnus,
                encaissementsAttendus, decaissementsConnus, soldePrevisionnel);
    }

    /** Ponderation deterministe (pas de LLM, pas de modele statistique) par anciennete de retard -
     * memes bornes que {@code BalanceAgeeService} pour rester coherent avec le reste du systeme. */
    private BigDecimal ponderer(List<Facture> facturesEnRetard, LocalDate aujourdHui) {
        return facturesEnRetard.stream()
                .map(f -> {
                    long joursRetard = ChronoUnit.DAYS.between(f.getDateEcheance(), aujourdHui);
                    BigDecimal probabilite = probabiliteRecouvrement(joursRetard);
                    return f.montantTtc().multiply(probabilite).setScale(0, RoundingMode.HALF_UP);
                })
                .reduce(BigDecimal.ZERO, BigDecimal::add);
    }

    private BigDecimal probabiliteRecouvrement(long joursRetard) {
        if (joursRetard <= BORNE_TRANCHE_1) {
            return PROBABILITE_TRANCHE_1;
        }
        if (joursRetard <= BORNE_TRANCHE_2) {
            return PROBABILITE_TRANCHE_2;
        }
        if (joursRetard <= BORNE_TRANCHE_3) {
            return PROBABILITE_TRANCHE_3;
        }
        return PROBABILITE_TRANCHE_4;
    }
}
