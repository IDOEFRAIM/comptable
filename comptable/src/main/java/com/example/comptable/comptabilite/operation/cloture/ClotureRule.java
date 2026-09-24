package com.example.comptable.comptabilite.operation.cloture;

import com.example.comptable.comptabilite.domain.CompteComptable;
import com.example.comptable.comptabilite.domain.TypeJournal;
import com.example.comptable.comptabilite.operation.common.ResultatRegle;
import com.example.comptable.comptabilite.operation.common.domain.AccountingOperationProposalLigne;
import com.example.comptable.comptabilite.parametrage.AccountContext;
import com.example.comptable.comptabilite.parametrage.AccountingAccountResolver;
import com.example.comptable.comptabilite.parametrage.ResolvedAccount;
import com.example.comptable.comptabilite.parametrage.domain.AccountPurpose;
import com.example.comptable.comptabilite.service.CompteComptableValidationService;
import com.example.comptable.entreprise.domain.Entreprise;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;

/**
 * Ecriture de determination du resultat de cloture : solde les comptes de charges/produits (deja
 * calcules ligne par ligne par {@code ClotureExerciceService}, via {@code mouvementNetPeriode} -
 * ce calcul n'est pas duplique ici) et transfere le resultat net vers le compte de resultat
 * resolu par {@link AccountingAccountResolver} (purpose RESULTAT_BENEFICE/RESULTAT_PERTE, fallback
 * sur le role systeme historique si aucun mapping specifique n'est configure) - jamais un numero
 * code en dur ("120"/"129"). Si aucun compte n'est resolu pour l'entreprise, aucune ligne de
 * resultat n'est ajoutee et une explication est renvoyee (NEEDS_REVIEW cote moteur, jamais un
 * compte devine).
 */
@Service
@RequiredArgsConstructor
public class ClotureRule {

    private final AccountingAccountResolver accountingAccountResolver;
    private final CompteComptableValidationService compteComptableValidationService;

    public ResultatRegle construire(Entreprise entreprise, List<AccountingOperationProposalLigne> lignesASolder,
                                     BigDecimal resultatNet) {
        List<String> explications = new ArrayList<>();
        List<String> warnings = new ArrayList<>();
        List<AccountingOperationProposalLigne> lignes = new ArrayList<>(lignesASolder);

        boolean benefice = resultatNet.signum() >= 0;
        AccountPurpose purpose = benefice ? AccountPurpose.RESULTAT_BENEFICE : AccountPurpose.RESULTAT_PERTE;
        ResolvedAccount resoluResultat = accountingAccountResolver.resolve(entreprise.getId(), purpose, AccountContext.vide(), null);
        if (!resoluResultat.isResolved()) {
            explications.add("Aucun compte de resultat (" + purpose + ") configure pour cette entreprise - cloture impossible : "
                    + resoluResultat.explication());
            return new ResultatRegle(TypeJournal.OPERATIONS_DIVERSES, List.of(), explications, warnings);
        }
        CompteComptable compteResultat = compteComptableValidationService
                .chargerCompteUtilisable(entreprise.getId(), resoluResultat.compteId(), true);

        String libelle = "Resultat de l'exercice";
        if (benefice) {
            lignes.add(AccountingOperationProposalLigne.credit(compteResultat, resultatNet, libelle));
        } else {
            lignes.add(AccountingOperationProposalLigne.debit(compteResultat, resultatNet.negate(), libelle));
        }

        return new ResultatRegle(TypeJournal.OPERATIONS_DIVERSES, lignes, explications, warnings);
    }
}
