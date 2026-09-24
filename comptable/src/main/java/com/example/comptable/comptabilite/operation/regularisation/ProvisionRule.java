package com.example.comptable.comptabilite.operation.regularisation;

import com.example.comptable.comptabilite.domain.CompteComptable;
import com.example.comptable.comptabilite.domain.TypeJournal;
import com.example.comptable.comptabilite.operation.common.ResultatRegle;
import com.example.comptable.comptabilite.operation.common.domain.AccountingOperationProposalLigne;
import com.example.comptable.comptabilite.provision.domain.Provision;
import com.example.comptable.comptabilite.provision.domain.TypeMouvementProvision;
import com.example.comptable.comptabilite.service.CompteComptableValidationService;
import com.example.comptable.entreprise.domain.Entreprise;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;

/**
 * Dotation/reprise de provision - mecanique universelle de double-ecriture (pas une regle
 * SYSCOHADA specifique disputee) : DOTATION debite le compte de dotation (charge) et credite le
 * compte de provision pour l'augmentation (montantNouveau - montantPrecedent) ; REPRISE_TOTALE et
 * REPRISE_PARTIELLE debitent le compte de provision et creditent le compte de reprise (produit)
 * pour respectivement montantPrecedent entier ou la diminution (montantPrecedent - montantNouveau).
 * Un montant nul ou negatif (dotation sans augmentation reelle, reprise sans risque couvert)
 * ne produit aucune ligne - jamais d'ecriture nulle ou de sens invalide force.
 */
@Service
@RequiredArgsConstructor
public class ProvisionRule {

    private final CompteComptableValidationService compteComptableValidationService;

    public ResultatRegle construire(Entreprise entreprise, Provision provision) {
        List<String> explications = new ArrayList<>();
        List<String> warnings = new ArrayList<>();
        List<AccountingOperationProposalLigne> lignes = new ArrayList<>();

        CompteComptable compteDotation;
        CompteComptable compteProvision;
        CompteComptable compteReprise;
        try {
            compteDotation = compteComptableValidationService.chargerCompteUtilisable(
                    entreprise.getId(), provision.getCompteDotation().getId(), true);
            compteProvision = compteComptableValidationService.chargerCompteUtilisable(
                    entreprise.getId(), provision.getCompteProvision().getId(), true);
            compteReprise = compteComptableValidationService.chargerCompteUtilisable(
                    entreprise.getId(), provision.getCompteReprise().getId(), true);
        } catch (ResponseStatusException e) {
            explications.add("Compte de dotation, de provision ou de reprise non configure ou inactif");
            return new ResultatRegle(TypeJournal.OPERATIONS_DIVERSES, lignes, explications, warnings);
        }

        String libelle = provision.getNature() + (provision.getObjet() != null ? " - " + provision.getObjet() : "");
        BigDecimal montant = switch (provision.getTypeMouvement()) {
            case DOTATION -> provision.getMontantNouveau().subtract(provision.getMontantPrecedent());
            case REPRISE_TOTALE -> provision.getMontantPrecedent();
            case REPRISE_PARTIELLE -> provision.getMontantPrecedent().subtract(provision.getMontantNouveau());
        };

        if (montant.signum() <= 0) {
            explications.add("Le mouvement de provision ne represente aucune variation positive a comptabiliser");
            return new ResultatRegle(TypeJournal.OPERATIONS_DIVERSES, lignes, explications, warnings);
        }

        if (provision.getTypeMouvement() == TypeMouvementProvision.DOTATION) {
            lignes.add(AccountingOperationProposalLigne.debit(compteDotation, montant, libelle));
            lignes.add(AccountingOperationProposalLigne.credit(compteProvision, montant, libelle));
        } else {
            lignes.add(AccountingOperationProposalLigne.debit(compteProvision, montant, libelle));
            lignes.add(AccountingOperationProposalLigne.credit(compteReprise, montant, libelle));
        }

        return new ResultatRegle(TypeJournal.OPERATIONS_DIVERSES, lignes, explications, warnings);
    }
}
