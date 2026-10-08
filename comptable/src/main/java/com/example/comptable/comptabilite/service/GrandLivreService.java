package com.example.comptable.comptabilite.service;

import com.example.comptable.comptabilite.balance.AccountBalance;
import com.example.comptable.comptabilite.balance.AccountingBalanceEngine;
import com.example.comptable.comptabilite.balance.ReportingView;
import com.example.comptable.comptabilite.domain.CompteComptable;
import com.example.comptable.comptabilite.domain.LigneEcriture;
import com.example.comptable.comptabilite.repository.CompteComptableRepository;
import com.example.comptable.comptabilite.repository.LigneEcritureRepository;
import com.example.comptable.comptabilite.web.dto.GrandLivreDto;
import com.example.comptable.comptabilite.web.dto.GrandLivreLigneDto;
import com.example.comptable.comptabilite.web.dto.SoldeDto;
import com.example.comptable.entreprise.service.EntrepriseAccessService;
import com.example.comptable.security.UtilisateurPrincipal;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Grand Livre OFFICIEL d'un compte sur une periode (vue {@link ReportingView#LEGAL_LEDGER} : ecritures validees, toutes natures, y compris l'ecriture de
 * cloture) : solde d'ouverture et solde de cloture viennent de {@link AccountingBalanceEngine} (jamais recalcules ici), les ecritures de la periode sont
 * listees avec leur solde progressif. Le solde progressif de la derniere ligne egale le solde de cloture du moteur (invariant teste).
 */
@Service
@RequiredArgsConstructor
public class GrandLivreService {

    private final EntrepriseAccessService entrepriseAccessService;
    private final CompteComptableRepository compteComptableRepository;
    private final LigneEcritureRepository ligneEcritureRepository;
    private final AccountingBalanceEngine accountingBalanceEngine;

    @Transactional(readOnly = true)
    public GrandLivreDto generer(UUID entrepriseId, UUID compteId, LocalDate dateDebut, LocalDate dateFin,
                                  UtilisateurPrincipal principal) {
        entrepriseAccessService.verifierAcces(entrepriseId, principal);

        CompteComptable compte = compteComptableRepository.findById(compteId)
                .filter(c -> c.getEntreprise().getId().equals(entrepriseId))
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Compte introuvable"));

        AccountBalance solde = accountingBalanceEngine.balance(entrepriseId, compteId, dateDebut, dateFin, ReportingView.LEGAL_LEDGER).orElse(null);
        BigDecimal netOuverture = solde == null ? BigDecimal.ZERO : solde.openingNet();
        BigDecimal netCloture = solde == null ? BigDecimal.ZERO : solde.netBalance();

        List<LigneEcriture> mouvements = ligneEcritureRepository.rechercherMouvementsValides(compteId, dateDebut, dateFin);
        List<GrandLivreLigneDto> lignes = new ArrayList<>();
        BigDecimal soldeProgressif = netOuverture;
        for (LigneEcriture ligne : mouvements) {
            soldeProgressif = soldeProgressif.add(ligne.getDebit()).subtract(ligne.getCredit());
            lignes.add(new GrandLivreLigneDto(
                    ligne.getId(),
                    ligne.getEcriture().getDateEcriture(),
                    ligne.getEcriture().getJournal().getCode(),
                    ligne.getEcriture().getNumeroPiece(),
                    ligne.getLibelle(),
                    ligne.getDebit(),
                    ligne.getCredit(),
                    ligne.getLettrage(),
                    SoldeDto.depuisNet(soldeProgressif)
            ));
        }

        return new GrandLivreDto(
                compte.getId(), compte.getNumero(), compte.getIntitule(),
                dateDebut, dateFin,
                SoldeDto.depuisNet(netOuverture),
                lignes,
                SoldeDto.depuisNet(netCloture)
        );
    }
}
