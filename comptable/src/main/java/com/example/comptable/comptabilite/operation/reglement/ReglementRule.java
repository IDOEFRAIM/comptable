package com.example.comptable.comptabilite.operation.reglement;

import com.example.comptable.comptabilite.operation.common.Blocages;

import com.example.comptable.comptabilite.domain.CompteComptable;
import com.example.comptable.comptabilite.domain.Tiers;
import com.example.comptable.comptabilite.domain.TypeJournal;
import com.example.comptable.comptabilite.operation.common.AccountingOperationType;
import com.example.comptable.comptabilite.operation.common.MoyenPaiement;
import com.example.comptable.comptabilite.operation.common.ResultatRegle;
import com.example.comptable.comptabilite.operation.common.domain.AccountingOperationProposalLigne;
import com.example.comptable.comptabilite.parametrage.AccountContext;
import com.example.comptable.comptabilite.parametrage.AccountingAccountResolver;
import com.example.comptable.comptabilite.parametrage.ResolvedAccount;
import com.example.comptable.comptabilite.parametrage.domain.AccountPurpose;
import com.example.comptable.comptabilite.service.CompteComptableValidationService;
import com.example.comptable.entreprise.domain.Entreprise;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/** Reglement : mouvement de tresorerie pur, pas de HT/TVA. Le compte de tresorerie est resolu
 * selon le moyen de paiement via AccountingAccountResolver (purpose BANQUE/CAISSE, contexte =
 * moyen de paiement - un mapping specifique par moyen de paiement peut etre configure par
 * entreprise, sinon fallback sur le role systeme historique) - jamais 521/571 code en dur.
 * Simplification documentee : ESPECES -&gt; CAISSE, tout le reste (CHEQUE/VIREMENT/CARTE/
 * MOBILE_MONEY/AUTRE) -&gt; BANQUE (pas de compte dedie distinct pour carte/mobile money dans cette
 * premiere version). */
@Service
@RequiredArgsConstructor
public class ReglementRule {

    private final AccountingAccountResolver accountingAccountResolver;
    private final CompteComptableValidationService compteComptableValidationService;

    public ResultatRegle construire(Entreprise entreprise, Tiers tiers, ReglementOperationRequest request) {
        if (request.montant().signum() < 0) {
            throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY, "Montant de reglement negatif interdit");
        }
        if (request.type() != AccountingOperationType.REGLEMENT_CLIENT
                && request.type() != AccountingOperationType.REGLEMENT_FOURNISSEUR) {
            throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY,
                    "Type d'operation invalide pour un reglement : " + request.type());
        }

        AccountPurpose purposeTresorerie = request.moyenPaiement() == MoyenPaiement.ESPECES
                ? AccountPurpose.CAISSE
                : AccountPurpose.BANQUE;
        TypeJournal typeJournal = request.moyenPaiement() == MoyenPaiement.ESPECES
                ? TypeJournal.CAISSE
                : TypeJournal.BANQUE;

        Blocages explications = new Blocages();
        List<String> warnings = new ArrayList<>();
        List<AccountingOperationProposalLigne> lignes = new ArrayList<>();

        AccountContext context = new AccountContext(request.type(), null, null, null, entreprise.getPays(),
                request.moyenPaiement().name(), Map.of());
        ResolvedAccount resoluTresorerie = accountingAccountResolver.resolve(entreprise.getId(), purposeTresorerie, context, null);
        if (!resoluTresorerie.isResolved()) {
            explications.ajouter(resoluTresorerie.code(), "Compte de tresorerie (" + purposeTresorerie + ") non configure pour cette entreprise : "
                    + resoluTresorerie.explication());
        } else {
            CompteComptable tresorerie = compteComptableValidationService
                    .chargerCompteUtilisable(entreprise.getId(), resoluTresorerie.compteId(), true);
            CompteComptable compteTiers = tiers.getCompteComptable();
            if (request.type() == AccountingOperationType.REGLEMENT_CLIENT) {
                lignes.add(AccountingOperationProposalLigne.debit(tresorerie, request.montant(), request.libelle()));
                lignes.add(AccountingOperationProposalLigne.credit(compteTiers, request.montant(), request.libelle()));
            } else {
                lignes.add(AccountingOperationProposalLigne.debit(compteTiers, request.montant(), request.libelle()));
                lignes.add(AccountingOperationProposalLigne.credit(tresorerie, request.montant(), request.libelle()));
            }
        }

        return new ResultatRegle(typeJournal, lignes, explications, warnings);
    }
}
