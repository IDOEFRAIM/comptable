package com.example.comptable.comptabilite.service;

import com.example.comptable.comptabilite.domain.LigneEcriture;
import com.example.comptable.comptabilite.domain.Tiers;
import com.example.comptable.comptabilite.repository.LigneEcritureRepository;
import com.example.comptable.comptabilite.web.dto.BalanceAgeeDto;
import com.example.comptable.comptabilite.web.dto.BalanceAgeeLigneDto;
import com.example.comptable.entreprise.service.EntrepriseAccessService;
import com.example.comptable.security.UtilisateurPrincipal;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * Etat des creances/dettes par anciennete ("balance agee", Sprint 10) : pour chaque tiers,
 * decompose son solde en instance en tranches de 30 jours a partir des lignes non lettrees de
 * son compte auxiliaire (411xxx/401xxx).
 * <p>
 * Limite connue et deliberement acceptee pour cette premiere version : une ligne lettree en
 * lettrage PARTIEL (cf. {@link LettrageService#lettrer}, {@code partiel=true}) sort de ce calcul
 * meme si un reliquat reste du, faute d'un champ dedie au montant residuel sur {@link LigneEcriture}.
 * A lever dans une iteration ulterieure si le lettrage partiel s'avere frequent en pratique.
 */
@Service
@RequiredArgsConstructor
public class BalanceAgeeService {

    private static final int BORNE_TRANCHE_1 = 30;
    private static final int BORNE_TRANCHE_2 = 60;
    private static final int BORNE_TRANCHE_3 = 90;

    private final EntrepriseAccessService entrepriseAccessService;
    private final LigneEcritureRepository ligneEcritureRepository;

    @Transactional(readOnly = true)
    public BalanceAgeeDto generer(UUID entrepriseId, LocalDate dateArrete, UtilisateurPrincipal principal) {
        entrepriseAccessService.verifierAcces(entrepriseId, principal);
        LocalDate date = dateArrete != null ? dateArrete : LocalDate.now();

        List<LigneEcriture> lignesEnInstance = ligneEcritureRepository.rechercherLignesEnInstance(entrepriseId, date);

        Map<Tiers, List<LigneEcriture>> parTiers = lignesEnInstance.stream()
                .filter(l -> l.getTiers() != null)
                .collect(Collectors.groupingBy(LigneEcriture::getTiers));

        List<BalanceAgeeLigneDto> lignesDto = parTiers.entrySet().stream()
                .map(entry -> construireLigne(entry.getKey(), entry.getValue(), date))
                .filter(l -> l.total().signum() != 0)
                .sorted(Comparator.comparing(BalanceAgeeLigneDto::tiersNom))
                .toList();

        BigDecimal totalGeneral = lignesDto.stream().map(BalanceAgeeLigneDto::total).reduce(BigDecimal.ZERO, BigDecimal::add);

        return new BalanceAgeeDto(date, lignesDto, totalGeneral);
    }

    private BalanceAgeeLigneDto construireLigne(Tiers tiers, List<LigneEcriture> lignes, LocalDate dateArrete) {
        BigDecimal tranche1 = BigDecimal.ZERO;
        BigDecimal tranche2 = BigDecimal.ZERO;
        BigDecimal tranche3 = BigDecimal.ZERO;
        BigDecimal tranche4 = BigDecimal.ZERO;

        for (LigneEcriture ligne : lignes) {
            // Positif = le tiers doit (creance client) ; negatif = l'entreprise doit (dette fournisseur).
            BigDecimal montant = ligne.getDebit().subtract(ligne.getCredit());
            long jours = ChronoUnit.DAYS.between(ligne.getEcriture().getDateEcriture(), dateArrete);

            if (jours <= BORNE_TRANCHE_1) {
                tranche1 = tranche1.add(montant);
            } else if (jours <= BORNE_TRANCHE_2) {
                tranche2 = tranche2.add(montant);
            } else if (jours <= BORNE_TRANCHE_3) {
                tranche3 = tranche3.add(montant);
            } else {
                tranche4 = tranche4.add(montant);
            }
        }

        BigDecimal total = tranche1.add(tranche2).add(tranche3).add(tranche4);
        return new BalanceAgeeLigneDto(tiers.getId(), tiers.getNom(), tiers.getType().name(),
                tranche1, tranche2, tranche3, tranche4, total);
    }
}
