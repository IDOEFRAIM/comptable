package com.example.comptable.fiscalite.vat;

import com.example.comptable.fiscalite.core.TaxTreatment;
import com.example.comptable.fiscalite.core.VatRules;
import com.example.comptable.fiscalite.vat.VatModel.Adjustment;
import com.example.comptable.fiscalite.vat.VatModel.Anomaly;
import com.example.comptable.fiscalite.vat.VatModel.Computation;
import com.example.comptable.fiscalite.vat.VatModel.Decision;
import com.example.comptable.fiscalite.vat.VatModel.DeclarationLine;
import com.example.comptable.fiscalite.vat.VatModel.Line;
import com.example.comptable.fiscalite.vat.VatModel.Operation;
import com.example.comptable.fiscalite.vat.VatModel.Reconciliation;
import com.example.comptable.fiscalite.vat.VatModel.Totals;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

/**
 * Moteur TVA PUR (aucune base, aucun Spring) : opérations de la comptabilité + décisions humaines + ajustements + cadre fiscal → lignes, totaux, anomalies et structure de déclaration.
 *
 * <p>Règles : le taux ne vient QUE du cadre vérifié ; sans cadre vérifié, aucun montant fiscal n'est calculé (les montants comptabilisés restent affichés, marqués « non vérifiés ») ; une
 * opération non déterminable avec certitude est {@code TO_REVIEW} avec un constat, jamais calculée sur une hypothèse ; la TVA comptabilisée n'est pas automatiquement déductible ; la comptabilité
 * n'est jamais modifiée. Tout est {@link BigDecimal}.</p>
 */
@Component
public class VatEngine {

    public static final String TAX_TREATMENT_MISSING = "TAX_TREATMENT_MISSING";
    public static final String TAX_RATE_UNRESOLVED = "TAX_RATE_UNRESOLVED";
    public static final String VAT_BASE_MISMATCH = "VAT_BASE_MISMATCH";
    public static final String VAT_AMOUNT_MISMATCH = "VAT_AMOUNT_MISMATCH";
    public static final String DUPLICATE_TAX_DOCUMENT = "DUPLICATE_TAX_DOCUMENT";
    public static final String TAX_PERIOD_INCOMPLETE = "TAX_PERIOD_INCOMPLETE";
    public static final String TAX_FRAMEWORK_NOT_VERIFIED = "TAX_FRAMEWORK_NOT_VERIFIED";

    private static final BigDecimal CENT = new BigDecimal("100");
    private static final BigDecimal TOLERANCE_BASE_PAR_DEFAUT = new BigDecimal("0.01");

    /**
     * @param regles          règles du cadre applicable (peut être {@code null} : aucun cadre)
     * @param verifie         le cadre est VERIFIED
     * @param ledgerOutput    mouvement du compte de TVA collectée sur la période (grand livre), {@code null} si le compte n'est pas configuré
     * @param ledgerInput     idem pour la TVA récupérable
     */
    public Computation calculer(List<Operation> operations, Map<UUID, Decision> decisions, List<Adjustment> ajustements, VatRules regles, boolean verifie, BigDecimal ledgerOutput,
                                BigDecimal ledgerInput) {
        boolean fiscal = verifie && regles != null;
        BigDecimal toleranceMontant = regles == null ? BigDecimal.ZERO : regles.toleranceAmount();
        BigDecimal toleranceBase = toleranceMontant.max(TOLERANCE_BASE_PAR_DEFAUT);
        Map<UUID, BigDecimal> ajustementsParOperation = new HashMap<>();
        BigDecimal ajustSortie = BigDecimal.ZERO;
        BigDecimal ajustEntree = BigDecimal.ZERO;
        for (Adjustment a : ajustements) {
            if ("OUTPUT".equals(a.effect())) {
                ajustSortie = ajustSortie.add(a.amount());
            } else {
                ajustEntree = ajustEntree.add(a.amount());
            }
            if (a.ecritureId() != null) {
                ajustementsParOperation.merge(a.ecritureId(), a.amount(), BigDecimal::add);
            }
        }

        List<Line> lignes = new ArrayList<>(operations.size());
        List<Anomaly> anomalies = new ArrayList<>();
        Map<String, UUID> vus = new HashMap<>();
        BigDecimal baseSortie = BigDecimal.ZERO, tvaSortieCompta = BigDecimal.ZERO, tvaSortieFiscale = BigDecimal.ZERO;
        BigDecimal baseEntree = BigDecimal.ZERO, tvaEntreeCompta = BigDecimal.ZERO, tvaEntreeDeductible = BigDecimal.ZERO;
        BigDecimal operationsSortie = BigDecimal.ZERO, operationsEntree = BigDecimal.ZERO, inexplique = BigDecimal.ZERO;
        int aRevoir = 0;
        boolean fiscalComplet = fiscal;

        for (Operation op : operations) {
            operationsSortie = operationsSortie.add(op.outputVat());
            operationsEntree = operationsEntree.add(op.inputVat());
            String cote = op.outputVat().signum() != 0 && op.inputVat().signum() == 0 ? "OUTPUT" : op.inputVat().signum() != 0 && op.outputVat().signum() == 0 ? "INPUT" : "UNDETERMINED";
            BigDecimal tva = "OUTPUT".equals(cote) ? op.outputVat() : "INPUT".equals(cote) ? op.inputVat() : BigDecimal.ZERO;
            BigDecimal base = "UNDETERMINED".equals(cote) ? BigDecimal.ZERO : op.totalDebit().subtract(tva.abs()).multiply(BigDecimal.valueOf(tva.signum()));
            Decision decision = decisions.get(op.ecritureId());
            TaxTreatment traitement = decision != null ? decision.treatment() : "OUTPUT".equals(cote) ? TaxTreatment.OUTPUT_TAX : "INPUT".equals(cote) ? TaxTreatment.INPUT_TAX : TaxTreatment.TO_REVIEW;
            String source = decision != null ? "DECISION" : "DERIVED";
            List<String> problemes = new ArrayList<>();

            if (traitement == TaxTreatment.TO_REVIEW) {
                problemes.add(TAX_TREATMENT_MISSING);
                anomalies.add(new Anomaly(TAX_TREATMENT_MISSING, op.ecritureId(), null, "Écriture " + libelle(op) + " : opération touchant les deux comptes de TVA, traitement fiscal à décider"));
            }
            if (op.invoiceBase() != null && !"UNDETERMINED".equals(cote) && op.invoiceBase().abs().subtract(base.abs()).abs().compareTo(toleranceBase) > 0) {
                problemes.add(VAT_BASE_MISMATCH);
                anomalies.add(new Anomaly(VAT_BASE_MISMATCH, op.ecritureId(), null, "Écriture " + libelle(op) + " : base de TVA " + base.abs().toPlainString() + " ≠ HT de la facture " + op.invoiceBase().abs().toPlainString()));
            }
            if (op.document() != null && !op.document().isBlank() && !"UNDETERMINED".equals(cote)) {
                String cle = cote + "|" + op.document().trim().toLowerCase(Locale.ROOT) + "|" + (op.tiers() == null ? "" : op.tiers().trim().toLowerCase(Locale.ROOT)) + "|" + tva.abs().toPlainString();
                UUID premier = vus.putIfAbsent(cle, op.ecritureId());
                if (premier != null) {
                    problemes.add(DUPLICATE_TAX_DOCUMENT);
                    anomalies.add(new Anomaly(DUPLICATE_TAX_DOCUMENT, op.ecritureId(), null, "Document " + op.document() + " déjà pris en compte dans cette période (même tiers, même TVA)"));
                }
            }

            BigDecimal tauxImplicite = base.signum() == 0 || "UNDETERMINED".equals(cote) ? null : tva.multiply(CENT).divide(base, 4, RoundingMode.HALF_UP);
            String codeTaux = null;
            BigDecimal tvaFiscale = null;
            BigDecimal deductible = null;
            BigDecimal ecart = null;
            String explication = null;
            boolean porteTva = traitement == TaxTreatment.OUTPUT_TAX || traitement == TaxTreatment.INPUT_TAX || traitement == TaxTreatment.NON_DEDUCTIBLE || traitement == TaxTreatment.PARTIALLY_DEDUCTIBLE;

            if (traitement == TaxTreatment.EXEMPT || traitement == TaxTreatment.OUT_OF_SCOPE) {
                tvaFiscale = BigDecimal.ZERO;
                deductible = BigDecimal.ZERO;
                ecart = tva;
                explication = tva.signum() == 0 ? "NONE" : "DECISION";
            } else if (porteTva && fiscal) {
                VatRules.Rate taux = tauxLePlusProche(regles, tauxImplicite);
                if (taux == null || tauxImplicite.subtract(taux.percent()).abs().compareTo(regles.rateMatchTolerancePoints()) > 0) {
                    problemes.add(TAX_RATE_UNRESOLVED);
                    fiscalComplet = false;
                    anomalies.add(new Anomaly(TAX_RATE_UNRESOLVED, op.ecritureId(), null, "Écriture " + libelle(op) + " : le taux implicite" + (tauxImplicite == null ? " (base nulle)" : " " + tauxImplicite.toPlainString() + " %")
                            + " ne correspond à aucun taux du cadre fiscal"));
                } else {
                    codeTaux = taux.code();
                    tvaFiscale = base.multiply(taux.percent()).divide(CENT, 2, RoundingMode.HALF_UP);
                    ecart = tva.subtract(tvaFiscale);
                    if (ecart.abs().compareTo(toleranceMontant) <= 0) {
                        explication = ecart.signum() == 0 ? "NONE" : "ROUNDING";
                    } else {
                        BigDecimal ajust = ajustementsParOperation.getOrDefault(op.ecritureId(), BigDecimal.ZERO);
                        if (ajust.subtract(ecart).abs().compareTo(toleranceMontant) <= 0 && ajust.signum() != 0) {
                            explication = "ADJUSTMENT";
                        } else {
                            explication = "NONE";
                            problemes.add(VAT_AMOUNT_MISMATCH);
                            inexplique = inexplique.add(ecart.abs());
                            anomalies.add(new Anomaly(VAT_AMOUNT_MISMATCH, op.ecritureId(), null, "Écriture " + libelle(op) + " : TVA comptabilisée " + tva.toPlainString() + " ≠ TVA calculée "
                                    + tvaFiscale.toPlainString() + " (écart " + ecart.toPlainString() + ", ni décision ni ajustement)"));
                        }
                    }
                    deductible = traitement == TaxTreatment.NON_DEDUCTIBLE ? BigDecimal.ZERO : traitement == TaxTreatment.PARTIALLY_DEDUCTIBLE
                            ? tvaFiscale.multiply(decision.deductiblePercent() == null ? BigDecimal.ZERO : decision.deductiblePercent()).divide(CENT, 2, RoundingMode.HALF_UP) : tvaFiscale;
                }
            } else if (porteTva) {
                fiscalComplet = false;                                                               // pas de cadre vérifié : aucun montant fiscal
            }

            boolean revoir = !problemes.isEmpty();
            if (traitement == TaxTreatment.TO_REVIEW || problemes.contains(TAX_RATE_UNRESOLVED)) {
                aRevoir++;
            }
            lignes.add(new Line(op.ecritureId(), op.date(), op.piece(), op.document(), op.tiers(), op.libelle(), cote, traitement, source, base, tauxImplicite, codeTaux, tva, tvaFiscale, deductible, ecart,
                    explication, revoir ? "TO_REVIEW" : "OK", problemes));

            if (traitement == TaxTreatment.OUTPUT_TAX || ("OUTPUT".equals(cote) && traitement == TaxTreatment.TO_REVIEW)) {
                if (traitement == TaxTreatment.OUTPUT_TAX) {
                    baseSortie = baseSortie.add(base);
                    tvaSortieCompta = tvaSortieCompta.add(tva);
                    tvaSortieFiscale = tvaSortieFiscale.add(tvaFiscale == null ? BigDecimal.ZERO : tvaFiscale);
                }
            } else if (traitement == TaxTreatment.INPUT_TAX || traitement == TaxTreatment.NON_DEDUCTIBLE || traitement == TaxTreatment.PARTIALLY_DEDUCTIBLE) {
                baseEntree = baseEntree.add(base);
                tvaEntreeCompta = tvaEntreeCompta.add(tva);
                tvaEntreeDeductible = tvaEntreeDeductible.add(deductible == null ? BigDecimal.ZERO : deductible);
            }
        }

        BigDecimal sortieDeclaree = fiscalComplet ? tvaSortieFiscale.add(ajustSortie) : tvaSortieCompta.add(ajustSortie);
        BigDecimal entreeDeclaree = fiscalComplet ? tvaEntreeDeductible.add(ajustEntree) : tvaEntreeCompta.add(ajustEntree);
        Totals totaux = new Totals(baseSortie, tvaSortieCompta, fiscal ? tvaSortieFiscale : null, baseEntree, tvaEntreeCompta, fiscal ? tvaEntreeDeductible : null, ajustSortie, ajustEntree, sortieDeclaree,
                entreeDeclaree, sortieDeclaree.subtract(entreeDeclaree), operationsSortie.subtract(operationsEntree), inexplique, aRevoir, operations.size(), fiscalComplet);
        BigDecimal ecartLivre = ledgerOutput == null || ledgerInput == null ? null : ledgerOutput.subtract(operationsSortie).abs().add(ledgerInput.subtract(operationsEntree).abs());
        Reconciliation rapprochement = new Reconciliation(ledgerOutput, ledgerInput, operationsSortie, operationsEntree, ecartLivre);

        if (!fiscal) {
            anomalies.add(new Anomaly(TAX_FRAMEWORK_NOT_VERIFIED, null, "PACK", verifie ? "Cadre fiscal sans règles exploitables" : "Aucun cadre fiscal vérifié pour cette période : montants comptabilisés uniquement, finalisation impossible"));
        }
        if (ecartLivre != null && ecartLivre.compareTo(toleranceMontant) > 0) {
            anomalies.add(new Anomaly(TAX_PERIOD_INCOMPLETE, null, "LEDGER", "Les opérations de TVA ne couvrent pas tout le mouvement des comptes de TVA du grand livre (écart " + ecartLivre.toPlainString() + ")"));
        }

        boolean officiel = regles != null && !regles.lines().isEmpty();
        return new Computation(lignes, totaux, rapprochement, anomalies, structure(regles, totaux, fiscalComplet), fiscalComplet ? "FISCAL_VERIFIED" : "ACCOUNTED_UNVERIFIED", officiel && fiscal);
    }

    private static List<DeclarationLine> structure(VatRules regles, Totals t, boolean fiscal) {
        List<DeclarationLine> sortie = new ArrayList<>();
        List<VatRules.DeclarationLine> definition = regles != null && !regles.lines().isEmpty() ? regles.lines() : List.of(
                new VatRules.DeclarationLine("BASE_COLLECTEE", "Base imposable collectée", "OUTPUT_BASE"), new VatRules.DeclarationLine("TVA_COLLECTEE", "TVA collectée", "OUTPUT_VAT"),
                new VatRules.DeclarationLine("BASE_DEDUCTIBLE", "Base des achats", "INPUT_BASE"), new VatRules.DeclarationLine("TVA_DEDUCTIBLE", "TVA déductible", "INPUT_VAT_DEDUCTIBLE"),
                new VatRules.DeclarationLine("AJUSTEMENTS_COLLECTEE", "Ajustements de TVA collectée", "ADJUSTMENTS_OUTPUT"), new VatRules.DeclarationLine("AJUSTEMENTS_DEDUCTIBLE", "Ajustements de TVA déductible", "ADJUSTMENTS_INPUT"),
                new VatRules.DeclarationLine("TVA_NETTE", "Solde de TVA (positif : à payer ; négatif : crédit)", "NET_VAT"));
        for (VatRules.DeclarationLine l : definition) {
            BigDecimal montant = switch (l.source()) {
                case "OUTPUT_BASE" -> t.outputBase();
                case "OUTPUT_VAT" -> fiscal ? (t.outputVatFiscal() == null ? BigDecimal.ZERO : t.outputVatFiscal()) : t.outputVatAccounted();
                case "INPUT_BASE" -> t.inputBase();
                case "INPUT_VAT_DEDUCTIBLE" -> fiscal ? (t.inputVatDeductible() == null ? BigDecimal.ZERO : t.inputVatDeductible()) : t.inputVatAccounted();
                case "ADJUSTMENTS_OUTPUT" -> t.adjustmentsOutput();
                case "ADJUSTMENTS_INPUT" -> t.adjustmentsInput();
                case "NET_VAT" -> t.netDeclared();
                default -> null;
            };
            sortie.add(new DeclarationLine(l.code(), l.label(), l.source(), montant));
        }
        return sortie;
    }

    private static VatRules.Rate tauxLePlusProche(VatRules regles, BigDecimal implicite) {
        if (implicite == null) {
            return null;
        }
        VatRules.Rate meilleur = null;
        for (VatRules.Rate r : regles.rates()) {
            if (meilleur == null || r.percent().subtract(implicite).abs().compareTo(meilleur.percent().subtract(implicite).abs()) < 0) {
                meilleur = r;
            }
        }
        return meilleur;
    }

    private static String libelle(Operation op) {
        return op.piece() != null && !op.piece().isBlank() ? op.piece() : String.valueOf(op.ecritureId());
    }
}
