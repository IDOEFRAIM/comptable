package com.example.comptable.comptabilite.operation.immobilisation;

import com.example.comptable.comptabilite.operation.common.Blocages;

import com.example.comptable.comptabilite.domain.Immobilisation;
import com.example.comptable.comptabilite.domain.Tiers;
import com.example.comptable.comptabilite.domain.TypeImmobilisation;
import com.example.comptable.comptabilite.domain.TypeJournal;
import com.example.comptable.comptabilite.operation.common.AccountingOperationType;
import com.example.comptable.comptabilite.operation.common.ResultatRegle;
import com.example.comptable.comptabilite.operation.common.domain.AccountingOperationProposalLigne;
import com.example.comptable.comptabilite.parametrage.AccountContext;
import com.example.comptable.comptabilite.parametrage.AccountingAccountResolver;
import com.example.comptable.comptabilite.parametrage.AccountingAccountResolver.CompteResolu;
import com.example.comptable.comptabilite.parametrage.domain.AccountPurpose;
import com.example.comptable.entreprise.domain.Entreprise;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Traduction comptable des evenements du cycle de vie d'une immobilisation. Le domaine Immobilisation
 * decide des montants (valeur d'origine, dotation calculee par {@code PlanAmortissementCalculator}) ;
 * cette regle ne fait que choisir les comptes par purpose via {@link AccountingAccountResolver}.
 * Un compte non configure, ambigu ou invalide devient une explication (NEEDS_REVIEW), jamais un
 * compte devine.
 * <ul>
 *   <li><b>Acquisition</b> : Debit immobilisation (HT) / Debit TVA recuperable (si &gt; 0) / Credit
 *       fournisseur (TTC).</li>
 *   <li><b>Dotation</b> : Debit dotation aux amortissements / Credit amortissement cumule.</li>
 * </ul>
 * La sortie (cession, mise au rebut) n'a volontairement pas de regle : ses ecritures ne sont pas
 * verifiees reglementairement.
 */
@Service
@RequiredArgsConstructor
public class ImmobilisationRule {

    private final AccountingAccountResolver accountingAccountResolver;

    public ResultatRegle construireAcquisition(Entreprise entreprise, Immobilisation immobilisation, Tiers fournisseur,
                                               BigDecimal montantTva, String libelle) {
        Blocages explications = new Blocages();
        AccountPurpose purposeActif = immobilisation.getType() == TypeImmobilisation.INCORPORELLE
                ? AccountPurpose.IMMOBILISATION_INCORPORELLE : AccountPurpose.IMMOBILISATION_CORPORELLE;

        CompteResolu actif = resoudre(entreprise, purposeActif, AccountingOperationType.IMMOBILISATION_ACQUISITION,
                immobilisation, immobilisation.getCompte().getId());
        CompteResolu tiers = resoudre(entreprise, AccountPurpose.FOURNISSEUR, AccountingOperationType.IMMOBILISATION_ACQUISITION,
                immobilisation, fournisseur.getCompteComptable() != null ? fournisseur.getCompteComptable().getId() : null);
        CompteResolu tva = montantTva.signum() > 0
                ? resoudre(entreprise, AccountPurpose.TVA_RECUPERABLE, AccountingOperationType.IMMOBILISATION_ACQUISITION, immobilisation, null)
                : null;

        if (!actif.estResolu()) {
            explications.compte(actif, "d'immobilisation");
        }
        if (!tiers.estResolu()) {
            explications.compte(tiers, "du fournisseur");
        }
        if (tva != null && !tva.estResolu()) {
            explications.compte(tva, "de TVA recuperable");
        }
        if (!explications.isEmpty()) {
            return new ResultatRegle(TypeJournal.ACHATS, List.of(), explications, List.of());
        }

        BigDecimal ht = immobilisation.getValeurOrigine();
        List<AccountingOperationProposalLigne> lignes = new ArrayList<>();
        lignes.add(AccountingOperationProposalLigne.debit(actif.compte(), ht, libelle));
        if (tva != null) {
            lignes.add(AccountingOperationProposalLigne.debit(tva.compte(), montantTva, libelle));
        }
        AccountingOperationProposalLigne ligneFournisseur = AccountingOperationProposalLigne.credit(
                tiers.compte(), ht.add(montantTva), libelle);
        ligneFournisseur.setTiers(fournisseur);
        lignes.add(ligneFournisseur);
        return new ResultatRegle(TypeJournal.ACHATS, lignes, explications, List.of());
    }

    public ResultatRegle construireDotation(Entreprise entreprise, Immobilisation immobilisation, BigDecimal dotation, String libelle) {
        Blocages explications = new Blocages();
        CompteResolu compteDotation = resoudre(entreprise, AccountPurpose.DOTATION_AMORTISSEMENT,
                AccountingOperationType.AMORTISSEMENT_DOTATION, immobilisation, null);
        CompteResolu compteAmortissement = resoudre(entreprise, AccountPurpose.AMORTISSEMENT,
                AccountingOperationType.AMORTISSEMENT_DOTATION, immobilisation, immobilisation.getCompteAmortissement().getId());
        if (!compteDotation.estResolu()) {
            explications.compte(compteDotation, "de dotation aux amortissements");
        }
        if (!compteAmortissement.estResolu()) {
            explications.compte(compteAmortissement, "d'amortissement de l'immobilisation '" + immobilisation.getLibelle() + "'");
        }
        if (!explications.isEmpty()) {
            return new ResultatRegle(TypeJournal.OPERATIONS_DIVERSES, List.of(), explications, List.of());
        }
        return new ResultatRegle(TypeJournal.OPERATIONS_DIVERSES, List.of(
                AccountingOperationProposalLigne.debit(compteDotation.compte(), dotation, libelle),
                AccountingOperationProposalLigne.credit(compteAmortissement.compte(), dotation, libelle)),
                explications, List.of());
    }

    private CompteResolu resoudre(Entreprise entreprise, AccountPurpose purpose, AccountingOperationType operation,
                                  Immobilisation immobilisation, java.util.UUID compteExplicite) {
        return accountingAccountResolver.resoudreCompte(entreprise.getId(), purpose,
                new AccountContext(operation, immobilisation.getType().name(), null, null, entreprise.getPays(), null, Map.of()),
                compteExplicite);
    }
}
