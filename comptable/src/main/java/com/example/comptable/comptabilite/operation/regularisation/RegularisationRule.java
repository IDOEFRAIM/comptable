package com.example.comptable.comptabilite.operation.regularisation;

import com.example.comptable.comptabilite.operation.common.Blocages;

import com.example.comptable.comptabilite.domain.CompteComptable;
import com.example.comptable.comptabilite.domain.TypeJournal;
import com.example.comptable.comptabilite.operation.common.ResultatRegle;
import com.example.comptable.comptabilite.operation.common.domain.AccountingOperationProposalLigne;
import com.example.comptable.comptabilite.regularisation.domain.Regularisation;
import com.example.comptable.comptabilite.service.CompteComptableValidationService;
import com.example.comptable.entreprise.domain.Entreprise;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;

/**
 * Regularisations de fin de periode (CCA/PCA/FNP/CAP/PAR). compteOrigine/compteContrepartie sont
 * toujours ceux deja choisis explicitement sur la Regularisation (jamais devines ici) - revalides
 * via {@link CompteComptableValidationService} au moment de la proposition, meme discipline que
 * {@code InventaireRegularisationRule}. Une regularisation dont le montant a regulariser (part
 * future pour CCA/PCA/PAR, montant entier pour FNP/CAP/PAR-recevoir) est nul ne produit aucune
 * ligne - jamais d'ecriture nulle forcee.
 */
@Service
@RequiredArgsConstructor
public class RegularisationRule {

    private final CompteComptableValidationService compteComptableValidationService;

    public ResultatRegle construire(Entreprise entreprise, Regularisation regularisation) {
        return construireLignes(entreprise, regularisation, false);
    }

    /** Contre-passation de l'ecriture d'origine (meme montant, sens inverse) - jamais appelee
     * automatiquement, uniquement sur action explicite (voir AccountingOperationEngine.proposerReprise). */
    public ResultatRegle construireReprise(Entreprise entreprise, Regularisation regularisation) {
        return construireLignes(entreprise, regularisation, true);
    }

    private ResultatRegle construireLignes(Entreprise entreprise, Regularisation regularisation, boolean reprise) {
        Blocages explications = new Blocages();
        List<String> warnings = new ArrayList<>();
        List<AccountingOperationProposalLigne> lignes = new ArrayList<>();

        CompteComptable compteOrigine;
        CompteComptable compteContrepartie;
        try {
            compteOrigine = compteComptableValidationService.chargerCompteUtilisable(
                    entreprise.getId(), regularisation.getCompteOrigine().getId(), true);
            compteContrepartie = compteComptableValidationService.chargerCompteUtilisable(
                    entreprise.getId(), regularisation.getCompteContrepartie().getId(), true);
        } catch (ResponseStatusException e) {
            explications.ajouter(com.example.comptable.comptabilite.parametrage.CompteNonUtilisableException.codeDe(e), "Compte d'origine ou de contrepartie non configure ou inactif - regularisation impossible");
            return new ResultatRegle(TypeJournal.OPERATIONS_DIVERSES, lignes, explications, warnings);
        }

        BigDecimal montant = montantARegulariser(regularisation);
        if (montant == null || montant.signum() == 0) {
            explications.add("Aucun montant a regulariser pour cette periode");
            return new ResultatRegle(TypeJournal.OPERATIONS_DIVERSES, lignes, explications, warnings);
        }

        String libelle = (reprise ? "Reprise - " : "") + regularisation.getLibelle();
        boolean debitOrigine = switch (regularisation.getType()) {
            case PRODUIT_CONSTATE_AVANCE, PRODUIT_A_REPORTER, FACTURE_NON_PARVENUE, CHARGE_A_PAYER -> true;
            case CHARGE_CONSTATEE_AVANCE, PRODUIT_A_RECEVOIR -> false;
        };
        if (reprise) {
            debitOrigine = !debitOrigine;
        }

        if (debitOrigine) {
            lignes.add(AccountingOperationProposalLigne.debit(compteOrigine, montant, libelle));
            lignes.add(AccountingOperationProposalLigne.credit(compteContrepartie, montant, libelle));
        } else {
            lignes.add(AccountingOperationProposalLigne.debit(compteContrepartie, montant, libelle));
            lignes.add(AccountingOperationProposalLigne.credit(compteOrigine, montant, libelle));
        }

        return new ResultatRegle(TypeJournal.OPERATIONS_DIVERSES, lignes, explications, warnings);
    }

    private BigDecimal montantARegulariser(Regularisation regularisation) {
        return switch (regularisation.getType()) {
            case CHARGE_CONSTATEE_AVANCE, PRODUIT_CONSTATE_AVANCE, PRODUIT_A_REPORTER -> regularisation.getMontantFutur();
            case FACTURE_NON_PARVENUE, CHARGE_A_PAYER, PRODUIT_A_RECEVOIR -> regularisation.getMontant();
        };
    }
}
