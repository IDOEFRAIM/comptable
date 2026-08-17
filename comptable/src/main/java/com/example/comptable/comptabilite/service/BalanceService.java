package com.example.comptable.comptabilite.service;

import com.example.comptable.comptabilite.domain.CompteComptable;
import com.example.comptable.comptabilite.domain.LigneEcriture;
import com.example.comptable.comptabilite.repository.CompteComptableRepository;
import com.example.comptable.comptabilite.repository.LigneEcritureRepository;
import com.example.comptable.comptabilite.web.dto.BalanceDto;
import com.example.comptable.comptabilite.web.dto.BalanceLigneDto;
import com.example.comptable.comptabilite.web.dto.SoldeDto;
import com.example.comptable.entreprise.service.EntrepriseAccessService;
import com.example.comptable.security.UtilisateurPrincipal;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;

/**
 * Balance generale : pour chaque compte mouvemente, solde d'ouverture / mouvements de
 * la periode / solde de cloture. Les totaux debit et credit doivent s'equilibrer a
 * chaque colonne (invariant fondamental de la partie double sur l'ensemble des comptes).
 */
@Service
@RequiredArgsConstructor
public class BalanceService {

    private final EntrepriseAccessService entrepriseAccessService;
    private final CompteComptableRepository compteComptableRepository;
    private final LigneEcritureRepository ligneEcritureRepository;

    @Transactional(readOnly = true)
    public BalanceDto generer(UUID entrepriseId, LocalDate dateDebut, LocalDate dateFin, UtilisateurPrincipal principal) {
        entrepriseAccessService.verifierAcces(entrepriseId, principal);

        List<UUID> comptesMouvementes = ligneEcritureRepository.rechercherComptesMouvementes(entrepriseId, dateFin);

        List<BalanceLigneDto> lignes = comptesMouvementes.stream()
                .map(compteId -> genererLigne(compteId, dateDebut, dateFin))
                .sorted(Comparator.comparing(BalanceLigneDto::compteNumero))
                .toList();

        BigDecimal totalSoldeOuvertureDebit = sommer(lignes, l -> l.soldeOuverture().debit());
        BigDecimal totalSoldeOuvertureCredit = sommer(lignes, l -> l.soldeOuverture().credit());
        BigDecimal totalMouvementDebit = sommer(lignes, BalanceLigneDto::mouvementDebit);
        BigDecimal totalMouvementCredit = sommer(lignes, BalanceLigneDto::mouvementCredit);
        BigDecimal totalSoldeClotureDebit = sommer(lignes, l -> l.soldeCloture().debit());
        BigDecimal totalSoldeClotureCredit = sommer(lignes, l -> l.soldeCloture().credit());

        return new BalanceDto(dateDebut, dateFin, lignes,
                totalSoldeOuvertureDebit, totalSoldeOuvertureCredit,
                totalMouvementDebit, totalMouvementCredit,
                totalSoldeClotureDebit, totalSoldeClotureCredit);
    }

    private BalanceLigneDto genererLigne(UUID compteId, LocalDate dateDebut, LocalDate dateFin) {
        CompteComptable compte = compteComptableRepository.findById(compteId).orElseThrow();

        BigDecimal netOuverture = ligneEcritureRepository.soldeNetAvant(compteId, dateDebut);
        List<LigneEcriture> mouvements = ligneEcritureRepository.rechercherMouvementsValides(compteId, dateDebut, dateFin);

        BigDecimal mouvementDebit = sommerLignes(mouvements, LigneEcriture::getDebit);
        BigDecimal mouvementCredit = sommerLignes(mouvements, LigneEcriture::getCredit);
        BigDecimal netCloture = netOuverture.add(mouvementDebit).subtract(mouvementCredit);

        return new BalanceLigneDto(
                compte.getId(), compte.getNumero(), compte.getIntitule(),
                SoldeDto.depuisNet(netOuverture),
                mouvementDebit, mouvementCredit,
                SoldeDto.depuisNet(netCloture)
        );
    }

    private BigDecimal sommer(List<BalanceLigneDto> lignes, java.util.function.Function<BalanceLigneDto, BigDecimal> extracteur) {
        return lignes.stream().map(extracteur).reduce(BigDecimal.ZERO, BigDecimal::add);
    }

    private BigDecimal sommerLignes(List<LigneEcriture> lignes, java.util.function.Function<LigneEcriture, BigDecimal> extracteur) {
        return lignes.stream().map(extracteur).reduce(BigDecimal.ZERO, BigDecimal::add);
    }
}
