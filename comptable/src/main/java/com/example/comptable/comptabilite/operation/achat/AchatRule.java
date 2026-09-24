package com.example.comptable.comptabilite.operation.achat;

import com.example.comptable.comptabilite.domain.CompteComptable;
import com.example.comptable.comptabilite.domain.RoleCompteSysteme;
import com.example.comptable.comptabilite.domain.Tiers;
import com.example.comptable.comptabilite.domain.TypeJournal;
import com.example.comptable.comptabilite.operation.common.FraisAccessoireRequest;
import com.example.comptable.comptabilite.operation.common.ReductionRequest;
import com.example.comptable.comptabilite.operation.common.ResultatRegle;
import com.example.comptable.comptabilite.operation.common.TypeReduction;
import com.example.comptable.comptabilite.operation.common.domain.AccountingOperationProposalLigne;
import com.example.comptable.comptabilite.operation.tva.TvaRateResolver;
import com.example.comptable.comptabilite.operation.tva.TvaResultat;
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

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Achat : debit compte de charge (ou d'immobilisation), debit TVA deductible si applicable,
 * credit fournisseur. Ne genere jamais le schema "achat classique" pour une IMMOBILISATION - une
 * incoherence nature/classe de compte est rejetee (422) avant toute construction de ligne.
 */
@Service
@RequiredArgsConstructor
public class AchatRule {

    private static final BigDecimal TOLERANCE_TTC = new BigDecimal("1.00");

    private final CompteComptableValidationService compteComptableValidationService;
    private final TvaRateResolver tvaRateResolver;
    private final AccountingAccountResolver accountingAccountResolver;

    public ResultatRegle construire(Entreprise entreprise, Tiers fournisseur, AchatOperationRequest request) {
        if (request.montantHt().signum() < 0) {
            throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY, "Montant HT negatif interdit");
        }

        CompteComptable compteCharge = compteComptableValidationService
                .chargerCompteUtilisable(entreprise.getId(), request.compteChargeId(), true);
        verifierCoherenceNatureClasse(request.natureAchat(), compteCharge);

        List<String> explications = new ArrayList<>();
        List<String> warnings = new ArrayList<>();

        BigDecimal montantHtPrincipal = appliquerReductions(request.reductions(), request.montantHt(), explications);

        BigDecimal montantHtBaseTva = montantHtPrincipal;
        List<AccountingOperationProposalLigne> lignesFrais = new ArrayList<>();
        BigDecimal montantHtFraisTotal = BigDecimal.ZERO;
        BigDecimal montantTvaFraisHorsBase = BigDecimal.ZERO;

        if (request.fraisAccessoires() != null) {
            for (FraisAccessoireRequest frais : request.fraisAccessoires()) {
                if (frais.montantHt().signum() < 0) {
                    throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY,
                            "Montant de frais accessoire negatif interdit");
                }
                CompteComptable compteFrais = compteComptableValidationService
                        .chargerCompteUtilisable(entreprise.getId(), frais.compteId(), true);
                lignesFrais.add(AccountingOperationProposalLigne.debit(compteFrais, frais.montantHt(),
                        request.libelle() + " - " + frais.type()));
                montantHtFraisTotal = montantHtFraisTotal.add(frais.montantHt());

                if (frais.inclusDansBaseTva()) {
                    montantHtBaseTva = montantHtBaseTva.add(frais.montantHt());
                } else if (frais.tauxTvaId() != null) {
                    TvaResultat tvaFrais = tvaRateResolver.resoudreParRegle(entreprise.getId(), frais.tauxTvaId(),
                            frais.montantHt(), RoleCompteSysteme.TVA_DEDUCTIBLE);
                    if (!tvaFrais.nonSupporte()) {
                        montantTvaFraisHorsBase = montantTvaFraisHorsBase.add(tvaFrais.montantTva());
                    }
                }
            }
        }

        List<AccountingOperationProposalLigne> lignes = new ArrayList<>();
        lignes.add(AccountingOperationProposalLigne.debit(compteCharge, montantHtPrincipal, request.libelle()));
        lignes.addAll(lignesFrais);

        BigDecimal montantTvaTotal = BigDecimal.ZERO;
        if (montantHtBaseTva.signum() > 0 || montantTvaFraisHorsBase.signum() > 0) {
            TvaResultat tva = request.tauxTvaId() != null
                    ? tvaRateResolver.resoudreParRegle(entreprise.getId(), request.tauxTvaId(), montantHtBaseTva, RoleCompteSysteme.TVA_DEDUCTIBLE)
                    : tvaRateResolver.resoudre(entreprise.getId(), entreprise.getPays(), request.date(), montantHtBaseTva, RoleCompteSysteme.TVA_DEDUCTIBLE);

            if (tva.nonSupporte()) {
                explications.add("TVA deductible non calculee : " + tva.motif());
            } else {
                montantTvaTotal = tva.montantTva().add(montantTvaFraisHorsBase);
                if (tva.sourceDemo()) {
                    warnings.add("Taux TVA issu d'une regle de demonstration non verifiee reglementairement.");
                }
                if (montantTvaTotal.signum() > 0) {
                    lignes.add(AccountingOperationProposalLigne.debit(tva.compte(), montantTvaTotal,
                            "TVA deductible - " + request.libelle()));
                }
            }
        }

        BigDecimal montantTtc = montantHtPrincipal.add(montantHtFraisTotal).add(montantTvaTotal);
        lignes.add(AccountingOperationProposalLigne.credit(fournisseur.getCompteComptable(), montantTtc, request.libelle()));

        verifierToleranceTtc(request.montantTtcAttendu(), montantTtc, warnings);

        return new ResultatRegle(TypeJournal.ACHATS, lignes, explications, warnings);
    }

    /** Reutilise par AvoirRule pour AVOIR_ACHAT : construit les lignes "core" (charge + TVA +
     * fournisseur) sans frais/reductions (non modelises sur un avoir generique), avant inversion
     * debit/credit par l'appelant. */
    public List<AccountingOperationProposalLigne> construireLignesCore(Entreprise entreprise, Tiers fournisseur,
            CompteComptable compteCharge, BigDecimal montantHt, UUID tauxTvaId, LocalDate date, String libelle,
            List<String> explications, List<String> warnings) {
        List<AccountingOperationProposalLigne> lignes = new ArrayList<>();
        lignes.add(AccountingOperationProposalLigne.debit(compteCharge, montantHt, libelle));

        BigDecimal montantTva = BigDecimal.ZERO;
        if (montantHt.signum() > 0) {
            TvaResultat tva = tauxTvaId != null
                    ? tvaRateResolver.resoudreParRegle(entreprise.getId(), tauxTvaId, montantHt, RoleCompteSysteme.TVA_DEDUCTIBLE)
                    : tvaRateResolver.resoudre(entreprise.getId(), entreprise.getPays(), date, montantHt, RoleCompteSysteme.TVA_DEDUCTIBLE);
            if (tva.nonSupporte()) {
                explications.add("TVA deductible non calculee : " + tva.motif());
            } else {
                montantTva = tva.montantTva();
                if (tva.sourceDemo()) {
                    warnings.add("Taux TVA issu d'une regle de demonstration non verifiee reglementairement.");
                }
                if (montantTva.signum() > 0) {
                    lignes.add(AccountingOperationProposalLigne.debit(tva.compte(), montantTva, "TVA deductible - " + libelle));
                }
            }
        }

        lignes.add(AccountingOperationProposalLigne.credit(fournisseur.getCompteComptable(),
                montantHt.add(montantTva), libelle));
        return lignes;
    }

    /**
     * Facture fournisseur multi-lignes multi-natures (marchandises + services + frais +
     * immobilisation sur une seule piece) : chaque ligne resout son propre compte (toujours fourni
     * explicitement par l'appelant, ex. CommandeAchatService via Article.compteAchat - jamais
     * devine depuis la seule nature) et sa propre TVA (taux explicite, montant precalcule - meme
     * convention que LigneFacture/VenteRule cote vente, pas TvaRateResolver/tax_rule ici). Une
     * ligne sans compte determinable tente d'abord AccountingAccountResolver (mapping de
     * l'entreprise, puis role systeme historique en repli - mission Etape 6, section 14) avant
     * d'etre ecartee avec une explication (NEEDS_REVIEW) plutot que devinee ou omise silencieusement
     * - meme discipline que InventaireRegularisationRule. Le compte TVA deductible passe par le meme
     * AccountingAccountResolver (purpose TVA_RECUPERABLE) ; les montants de plusieurs lignes
     * s'agregent naturellement dans la meme ligne de compte.
     */
    public ResultatRegle construireMultiLigne(Entreprise entreprise, Tiers fournisseur, AchatMultiLigneOperationRequest request) {
        List<String> explications = new ArrayList<>();
        List<String> warnings = new ArrayList<>();
        Map<CompteComptable, BigDecimal> montantHtParCompte = new LinkedHashMap<>();
        BigDecimal montantTvaTotal = BigDecimal.ZERO;
        BigDecimal montantHtTotalRetenu = BigDecimal.ZERO;

        for (LigneAchatOperationRequest ligne : request.lignes()) {
            if (ligne.montantHt().signum() < 0) {
                throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY,
                        "Montant HT negatif interdit pour la ligne '" + ligne.designation() + "'");
            }
            UUID compteIdLigne = ligne.compteId();
            if (compteIdLigne == null) {
                AccountPurpose purpose = purposeDepuisNature(ligne.nature());
                if (purpose == null) {
                    explications.add("Compte non determine pour la ligne '" + ligne.designation()
                            + "' (nature " + ligne.nature() + ") - aucun compte fourni et aucune correspondance possible");
                    continue;
                }
                ResolvedAccount resolu = accountingAccountResolver.resolve(entreprise.getId(), purpose,
                        new AccountContext(null, ligne.nature().name(), null, null, entreprise.getPays(), null, Map.of()), null);
                if (!resolu.isResolved()) {
                    explications.add("Compte non determine pour la ligne '" + ligne.designation()
                            + "' (nature " + ligne.nature() + ") - " + resolu.explication());
                    continue;
                }
                compteIdLigne = resolu.compteId();
            }

            CompteComptable compte;
            try {
                compte = compteComptableValidationService.chargerCompteUtilisable(entreprise.getId(), compteIdLigne, true);
                verifierCoherenceNatureClasse(ligne.nature(), compte);
            } catch (ResponseStatusException e) {
                explications.add("Ligne '" + ligne.designation() + "' : " + e.getReason());
                continue;
            }

            BigDecimal montantHtLigne = appliquerReductions(ligne.reductions(), ligne.montantHt(), explications);
            montantHtParCompte.merge(compte, montantHtLigne, BigDecimal::add);
            montantHtTotalRetenu = montantHtTotalRetenu.add(montantHtLigne);

            if (ligne.tauxTva() != null && ligne.tauxTva().signum() > 0 && montantHtLigne.signum() > 0) {
                montantTvaTotal = montantTvaTotal.add(
                        montantHtLigne.multiply(ligne.tauxTva()).divide(new BigDecimal("100"), 2, RoundingMode.HALF_UP));
            }
        }

        List<AccountingOperationProposalLigne> lignes = new ArrayList<>();
        for (Map.Entry<CompteComptable, BigDecimal> entry : montantHtParCompte.entrySet()) {
            lignes.add(AccountingOperationProposalLigne.debit(entry.getKey(), entry.getValue(), request.libelle()));
        }

        if (montantTvaTotal.signum() > 0) {
            ResolvedAccount resoluTva = accountingAccountResolver.resolve(entreprise.getId(), AccountPurpose.TVA_RECUPERABLE,
                    new AccountContext(null, null, null, null, entreprise.getPays(), null, Map.of()), null);
            if (!resoluTva.isResolved()) {
                explications.add("Compte TVA deductible non configure pour cette entreprise");
            } else {
                CompteComptable compteTva = compteComptableValidationService
                        .chargerCompteUtilisable(entreprise.getId(), resoluTva.compteId(), true);
                lignes.add(AccountingOperationProposalLigne.debit(compteTva, montantTvaTotal, "TVA deductible - " + request.libelle()));
            }
        }

        BigDecimal montantTtc = montantHtTotalRetenu.add(montantTvaTotal);
        if (!montantHtParCompte.isEmpty() || montantTvaTotal.signum() > 0) {
            lignes.add(AccountingOperationProposalLigne.credit(fournisseur.getCompteComptable(), montantTtc, request.libelle()));
        }

        verifierToleranceTtc(request.montantTtcAttendu(), montantTtc, warnings);

        return new ResultatRegle(TypeJournal.ACHATS, lignes, explications, warnings);
    }

    /** Correspondance nature -&gt; purpose pour la resolution automatique quand aucun compte n'est
     * fourni sur la ligne (section 14 de la mission Etape 6). CHARGE/AUTRE n'ont pas d'equivalent
     * dans la liste minimale de purposes - jamais invente, la ligne reste NEEDS_REVIEW. */
    private AccountPurpose purposeDepuisNature(NatureAchat nature) {
        return switch (nature) {
            case MARCHANDISE -> AccountPurpose.ACHAT_MARCHANDISE;
            case MATIERE_PREMIERE -> AccountPurpose.ACHAT_MATIERE;
            case CONSOMMABLE -> AccountPurpose.ACHAT_CONSOMMABLE;
            case SERVICE -> AccountPurpose.ACHAT_SERVICE;
            case FRAIS_ACCESSOIRE -> AccountPurpose.ACHAT_FRAIS_ACCESSOIRE;
            case IMMOBILISATION -> AccountPurpose.IMMOBILISATION_CORPORELLE;
            case CHARGE, AUTRE -> null;
        };
    }

    private void verifierCoherenceNatureClasse(NatureAchat nature, CompteComptable compteCharge) {
        String premiereClasse = compteCharge.getNumero().substring(0, 1);
        boolean estImmobilisation = nature == NatureAchat.IMMOBILISATION;
        if (estImmobilisation && !"2".equals(premiereClasse)) {
            throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY,
                    "Nature IMMOBILISATION incompatible avec le compte " + compteCharge.getNumero()
                            + " (classe 2 attendue) - le moteur ne genere jamais un achat classique pour une immobilisation");
        }
        if (!estImmobilisation && !"6".equals(premiereClasse)) {
            throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY,
                    "Nature d'achat incompatible avec le compte " + compteCharge.getNumero() + " (classe 6 attendue)");
        }
    }

    private BigDecimal appliquerReductions(List<ReductionRequest> reductions, BigDecimal montantHt, List<String> explications) {
        if (reductions == null) {
            return montantHt;
        }
        BigDecimal montant = montantHt;
        for (ReductionRequest reduction : reductions) {
            if (reduction.typeReduction() == TypeReduction.ESCOMPTE) {
                explications.add("Escompte non pris en charge par le moteur - saisir l'ecriture manuellement via Ecritures > Nouvelle.");
                continue;
            }
            BigDecimal montantReduction = calculerMontantReduction(reduction, montant);
            if (montantReduction.signum() < 0) {
                throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY, "Montant de reduction negatif interdit");
            }
            montant = montant.subtract(montantReduction);
        }
        return montant;
    }

    private BigDecimal calculerMontantReduction(ReductionRequest reduction, BigDecimal base) {
        if (reduction.montant() != null) {
            return reduction.montant();
        }
        if (reduction.pourcentage() != null) {
            return base.multiply(reduction.pourcentage()).divide(BigDecimal.valueOf(100), 2, RoundingMode.HALF_UP);
        }
        return BigDecimal.ZERO;
    }

    private void verifierToleranceTtc(BigDecimal montantTtcAttendu, BigDecimal montantTtcCalcule, List<String> warnings) {
        if (montantTtcAttendu == null) {
            return;
        }
        if (montantTtcAttendu.subtract(montantTtcCalcule).abs().compareTo(TOLERANCE_TTC) > 0) {
            warnings.add("Le TTC attendu (" + montantTtcAttendu + ") differe du TTC calcule (" + montantTtcCalcule
                    + ") de plus de la tolerance (" + TOLERANCE_TTC + ").");
        }
    }
}
