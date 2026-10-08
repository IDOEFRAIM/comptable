package com.example.comptable.comptabilite.operation.avoir;

import com.example.comptable.comptabilite.operation.common.Blocages;

import com.example.comptable.comptabilite.domain.CompteComptable;
import com.example.comptable.comptabilite.domain.Tiers;
import com.example.comptable.comptabilite.domain.TypeJournal;
import com.example.comptable.comptabilite.operation.achat.AchatRule;
import com.example.comptable.comptabilite.operation.common.AccountingOperationType;
import com.example.comptable.comptabilite.operation.common.ResultatRegle;
import com.example.comptable.comptabilite.operation.common.domain.AccountingOperationProposalLigne;
import com.example.comptable.comptabilite.operation.vente.VenteRule;
import com.example.comptable.comptabilite.service.CompteComptableValidationService;
import com.example.comptable.entreprise.domain.Entreprise;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;

/**
 * Avoir : inverse debit/credit d'une operation de meme nature, jamais une seconde logique
 * dupliquee. AVOIR_VENTE reutilise {@link VenteRule#construireLignesCore}, AVOIR_ACHAT reutilise
 * {@link AchatRule#construireLignesCore} - une seule vraie implementation de construction de
 * lignes existe, l'avoir n'est qu'une transformation dessus.
 */
@Service
@RequiredArgsConstructor
public class AvoirRule {

    private final CompteComptableValidationService compteComptableValidationService;
    private final AchatRule achatRule;
    private final VenteRule venteRule;

    public ResultatRegle construire(Entreprise entreprise, Tiers tiers, AvoirOperationRequest request) {
        if (request.montantHt().signum() < 0) {
            throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY, "Montant HT negatif interdit");
        }
        if (request.type() != AccountingOperationType.AVOIR_VENTE && request.type() != AccountingOperationType.AVOIR_ACHAT) {
            throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY,
                    "Type d'operation invalide pour un avoir : " + request.type());
        }

        CompteComptable compte = compteComptableValidationService
                .chargerCompteUtilisable(entreprise.getId(), request.compteId(), true);

        Blocages explications = new Blocages();
        List<String> warnings = new ArrayList<>();

        List<AccountingOperationProposalLigne> lignesOrigine = request.type() == AccountingOperationType.AVOIR_VENTE
                ? venteRule.construireLignesCore(entreprise, tiers, compte, request.montantHt(), request.tauxTvaId(),
                        request.date(), request.libelle(), explications, warnings)
                : achatRule.construireLignesCore(entreprise, tiers, compte, request.montantHt(), request.tauxTvaId(),
                        request.date(), request.libelle(), explications, warnings);

        List<AccountingOperationProposalLigne> lignesInversees = inverserLignes(lignesOrigine);

        TypeJournal typeJournal = request.type() == AccountingOperationType.AVOIR_VENTE
                ? TypeJournal.VENTES
                : TypeJournal.ACHATS;

        return new ResultatRegle(typeJournal, lignesInversees, explications, warnings);
    }

    /** Inverse debit/credit d'une liste de lignes deja construites - reutilise par tout avoir
     * documentaire (ex. AvoirFournisseurService) qui reconstruit d'abord les lignes d'origine via
     * la regle correspondante (AchatRule/VenteRule) puis les inverse ici, sans jamais dupliquer la
     * logique de calcul des comptes/montants. */
    public List<AccountingOperationProposalLigne> inverserLignes(List<AccountingOperationProposalLigne> lignes) {
        return lignes.stream().map(this::inverser).toList();
    }

    private AccountingOperationProposalLigne inverser(AccountingOperationProposalLigne ligne) {
        BigDecimal debitOrigine = ligne.getDebit();
        BigDecimal creditOrigine = ligne.getCredit();
        AccountingOperationProposalLigne inversee = new AccountingOperationProposalLigne();
        inversee.setCompte(ligne.getCompte());
        inversee.setTiers(ligne.getTiers());
        inversee.setLibelle(ligne.getLibelle());
        inversee.setDebit(creditOrigine);
        inversee.setCredit(debitOrigine);
        return inversee;
    }
}
