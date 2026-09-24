package com.example.comptable.comptabilite.operation.regularisation;

import com.example.comptable.comptabilite.depreciation.domain.Depreciation;
import com.example.comptable.comptabilite.domain.CompteComptable;
import com.example.comptable.comptabilite.domain.TypeJournal;
import com.example.comptable.comptabilite.operation.common.ResultatRegle;
import com.example.comptable.comptabilite.operation.common.domain.AccountingOperationProposalLigne;
import com.example.comptable.comptabilite.service.CompteComptableValidationService;
import com.example.comptable.entreprise.domain.Entreprise;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;

/**
 * Constatation d'une perte de valeur : perte = max(0, valeurComptable - valeurRecouvrable). Si la
 * valeur recouvrable couvre ou depasse la valeur comptable, aucune perte n'est constatee et aucune
 * ecriture n'est generee (jamais d'ecriture nulle forcee) - la depreciation reste tracee pour
 * historique via son statut/justification, sans proposition.
 */
@Service
@RequiredArgsConstructor
public class DepreciationRule {

    private final CompteComptableValidationService compteComptableValidationService;

    public ResultatRegle construire(Entreprise entreprise, Depreciation depreciation) {
        List<String> explications = new ArrayList<>();
        List<String> warnings = new ArrayList<>();
        List<AccountingOperationProposalLigne> lignes = new ArrayList<>();

        BigDecimal perte = depreciation.getValeurComptable().subtract(depreciation.getValeurRecouvrable());
        if (perte.signum() <= 0) {
            explications.add("Aucune perte de valeur constatee (valeur recouvrable >= valeur comptable)");
            return new ResultatRegle(TypeJournal.OPERATIONS_DIVERSES, lignes, explications, warnings);
        }

        CompteComptable compteDotation;
        CompteComptable compteDepreciation;
        try {
            compteDotation = compteComptableValidationService.chargerCompteUtilisable(
                    entreprise.getId(), depreciation.getCompteDotation().getId(), true);
            compteDepreciation = compteComptableValidationService.chargerCompteUtilisable(
                    entreprise.getId(), depreciation.getCompteDepreciation().getId(), true);
        } catch (ResponseStatusException e) {
            explications.add("Compte de dotation ou de depreciation non configure ou inactif");
            return new ResultatRegle(TypeJournal.OPERATIONS_DIVERSES, lignes, explications, warnings);
        }

        String libelle = "Depreciation " + depreciation.getObjetType().name().toLowerCase();
        lignes.add(AccountingOperationProposalLigne.debit(compteDotation, perte, libelle));
        lignes.add(AccountingOperationProposalLigne.credit(compteDepreciation, perte, libelle));

        return new ResultatRegle(TypeJournal.OPERATIONS_DIVERSES, lignes, explications, warnings);
    }
}
