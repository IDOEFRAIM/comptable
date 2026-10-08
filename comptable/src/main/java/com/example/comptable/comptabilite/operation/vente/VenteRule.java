package com.example.comptable.comptabilite.operation.vente;

import com.example.comptable.comptabilite.operation.common.Blocages;

import com.example.comptable.comptabilite.domain.CompteComptable;
import com.example.comptable.comptabilite.domain.Tiers;
import com.example.comptable.comptabilite.domain.TypeJournal;
import com.example.comptable.comptabilite.operation.common.FraisAccessoireRequest;
import com.example.comptable.comptabilite.operation.common.ReductionRequest;
import com.example.comptable.comptabilite.operation.common.ResultatRegle;
import com.example.comptable.comptabilite.operation.common.TypeReduction;
import com.example.comptable.comptabilite.operation.common.domain.AccountingOperationProposalLigne;
import com.example.comptable.comptabilite.parametrage.AccountContext;
import com.example.comptable.comptabilite.operation.common.AccountingOperationType;
import com.example.comptable.comptabilite.parametrage.domain.AccountPurpose;
import com.example.comptable.comptabilite.parametrage.AccountingAccountResolver;
import com.example.comptable.comptabilite.operation.tva.TvaRateResolver;
import com.example.comptable.comptabilite.operation.tva.TvaResultat;
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
import java.util.List;
import java.util.UUID;

/** Vente : debit client, credit compte de produit, credit TVA collectee si applicable. Le compte
 * de produit est toujours fourni explicitement par l'appelant (meme contrat que
 * FacturationService.validerFacture) - jamais devine depuis la nature seule. */
@Service
@RequiredArgsConstructor
public class VenteRule {

    private static final BigDecimal TOLERANCE_TTC = new BigDecimal("1.00");

    private final CompteComptableValidationService compteComptableValidationService;
    private final TvaRateResolver tvaRateResolver;
    private final AccountingAccountResolver accountingAccountResolver;

    public ResultatRegle construire(Entreprise entreprise, Tiers client, VenteOperationRequest request) {
        if (request.montantHt().signum() < 0) {
            throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY, "Montant HT negatif interdit");
        }

        CompteComptable compteProduit = compteComptableValidationService
                .chargerCompteUtilisable(entreprise.getId(), request.compteProduitId(), true);

        Blocages explications = new Blocages();
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
                lignesFrais.add(AccountingOperationProposalLigne.credit(compteFrais, frais.montantHt(),
                        request.libelle() + " - " + frais.type()));
                montantHtFraisTotal = montantHtFraisTotal.add(frais.montantHt());

                if (frais.inclusDansBaseTva()) {
                    montantHtBaseTva = montantHtBaseTva.add(frais.montantHt());
                } else if (frais.tauxTvaId() != null) {
                    TvaResultat tvaFrais = tvaRateResolver.resoudreParRegle(frais.tauxTvaId(), frais.montantHt());
                    if (!tvaFrais.nonSupporte()) {
                        montantTvaFraisHorsBase = montantTvaFraisHorsBase.add(tvaFrais.montantTva());
                    }
                }
            }
        }

        List<AccountingOperationProposalLigne> lignes = new ArrayList<>();
        lignes.add(AccountingOperationProposalLigne.credit(compteProduit, montantHtPrincipal, request.libelle()));
        lignes.addAll(lignesFrais);

        BigDecimal montantTvaTotal = BigDecimal.ZERO;
        if (montantHtBaseTva.signum() > 0 || montantTvaFraisHorsBase.signum() > 0) {
            TvaResultat tva = request.tauxTvaId() != null
                    ? tvaRateResolver.resoudreParRegle(request.tauxTvaId(), montantHtBaseTva)
                    : tvaRateResolver.resoudre(entreprise.getPays(), request.date(), montantHtBaseTva);

            if (tva.nonSupporte()) {
                explications.add("TVA collectee non calculee : " + tva.motif());
            } else {
                montantTvaTotal = tva.montantTva().add(montantTvaFraisHorsBase);
                if (tva.sourceDemo()) {
                    warnings.add("Taux TVA issu d'une regle de demonstration non verifiee reglementairement.");
                }
                if (montantTvaTotal.signum() > 0) {
                    AccountingAccountResolver.CompteResolu compteTva = resoudreCompteTva(entreprise);
                    if (compteTva.estResolu()) {
                        lignes.add(AccountingOperationProposalLigne.credit(compteTva.compte(), montantTvaTotal,
                                "TVA collectee - " + request.libelle()));
                    } else {
                        explications.compte(compteTva, "de TVA collectee");
                    }
                }
            }
        }

        BigDecimal montantTtc = montantHtPrincipal.add(montantHtFraisTotal).add(montantTvaTotal);
        lignes.add(0, AccountingOperationProposalLigne.debit(client.getCompteComptable(), montantTtc, request.libelle()));

        verifierToleranceTtc(request.montantTtcAttendu(), montantTtc, warnings);

        return new ResultatRegle(TypeJournal.VENTES, lignes, explications, warnings);
    }

    /** Compte de TVA collectee : toujours par le parametrage de l'entreprise (purpose TVA_COLLECTEE),
     * jamais lie au calcul du taux (fiscalite). */
    private AccountingAccountResolver.CompteResolu resoudreCompteTva(Entreprise entreprise) {
        return accountingAccountResolver.resoudreCompte(entreprise.getId(), AccountPurpose.TVA_COLLECTEE,
                new AccountContext(AccountingOperationType.VENTE, null, null, null, entreprise.getPays(), null, java.util.Map.of()), null);
    }

    /** Reutilise par AvoirRule pour AVOIR_VENTE : lignes "core" (client + produit + TVA) sans
     * frais/reductions, avant inversion debit/credit par l'appelant. */
    public List<AccountingOperationProposalLigne> construireLignesCore(Entreprise entreprise, Tiers client,
            CompteComptable compteProduit, BigDecimal montantHt, UUID tauxTvaId, LocalDate date, String libelle,
            Blocages explications, List<String> warnings) {
        BigDecimal montantTva = BigDecimal.ZERO;
        CompteComptable compteTva = null;
        if (montantHt.signum() > 0) {
            TvaResultat tva = tauxTvaId != null
                    ? tvaRateResolver.resoudreParRegle(tauxTvaId, montantHt)
                    : tvaRateResolver.resoudre(entreprise.getPays(), date, montantHt);
            if (tva.nonSupporte()) {
                explications.add("TVA collectee non calculee : " + tva.motif());
            } else {
                montantTva = tva.montantTva();
                if (montantTva.signum() > 0) {
                    AccountingAccountResolver.CompteResolu resolu = resoudreCompteTva(entreprise);
                    if (resolu.estResolu()) {
                        compteTva = resolu.compte();
                    } else {
                        explications.compte(resolu, "de TVA collectee");
                    }
                }
                if (tva.sourceDemo()) {
                    warnings.add("Taux TVA issu d'une regle de demonstration non verifiee reglementairement.");
                }
            }
        }

        List<AccountingOperationProposalLigne> lignes = new ArrayList<>();
        lignes.add(AccountingOperationProposalLigne.debit(client.getCompteComptable(), montantHt.add(montantTva), libelle));
        lignes.add(AccountingOperationProposalLigne.credit(compteProduit, montantHt, libelle));
        if (montantTva.signum() > 0) {
            lignes.add(AccountingOperationProposalLigne.credit(compteTva, montantTva, "TVA collectee - " + libelle));
        }
        return lignes;
    }

    /**
     * Lignes de vente (client debit TTC / produit credit HT / TVA credit si &gt; 0) a partir de
     * montants deja agreges par l'appelant (facture multi-lignes, taux TVA potentiellement
     * heterogenes par ligne) - jamais de re-derivation TVA ici, contrairement a
     * {@link #construire} qui reste reserve au flux "operation a taux unique". Utilise par
     * FacturationService.validerFacture pour l'unification vente (mission cloture Etape 4, section
     * 20) - meme forme d'ecriture qu'auparavant, construite via le moteur commun.
     */
    public List<AccountingOperationProposalLigne> construireLignesVenteDepuisMontantsPrecalcules(
            Tiers client, CompteComptable compteProduit, BigDecimal montantHt,
            CompteComptable compteTva, BigDecimal montantTva, BigDecimal montantTtc, String libelle) {
        List<AccountingOperationProposalLigne> lignes = new ArrayList<>();
        AccountingOperationProposalLigne ligneClient = AccountingOperationProposalLigne.debit(
                client.getCompteComptable(), montantTtc, libelle);
        ligneClient.setTiers(client);
        lignes.add(ligneClient);
        lignes.add(AccountingOperationProposalLigne.credit(compteProduit, montantHt, libelle));
        if (montantTva.signum() > 0) {
            lignes.add(AccountingOperationProposalLigne.credit(compteTva, montantTva, libelle));
        }
        return lignes;
    }

    /** Inverse de {@link #construireLignesVenteDepuisMontantsPrecalcules} (produit/TVA debites,
     * client credite) - utilise par FactureAvoirService.validerAvoir pour l'unification avoir
     * (section 22), meme forme d'ecriture qu'auparavant. */
    public List<AccountingOperationProposalLigne> construireLignesAvoirVenteDepuisMontantsPrecalcules(
            Tiers client, CompteComptable compteProduit, BigDecimal montantHt,
            CompteComptable compteTva, BigDecimal montantTva, BigDecimal montantTtc, String libelle) {
        List<AccountingOperationProposalLigne> lignes = new ArrayList<>();
        lignes.add(AccountingOperationProposalLigne.debit(compteProduit, montantHt, libelle));
        if (montantTva.signum() > 0) {
            lignes.add(AccountingOperationProposalLigne.debit(compteTva, montantTva, libelle));
        }
        AccountingOperationProposalLigne ligneClient = AccountingOperationProposalLigne.credit(
                client.getCompteComptable(), montantTtc, libelle);
        ligneClient.setTiers(client);
        lignes.add(ligneClient);
        return lignes;
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
