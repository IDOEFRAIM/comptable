package com.example.comptable.fiscalite.vat;

import com.example.comptable.fiscalite.core.TaxPeriodStatus;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;

/**
 * Évaluateur UNIQUE du statut opérationnel d'une période TVA et de sa progression en étapes (aucune logique de statut dans Angular). Pur : aucune base, aucun Spring. Il ne décide d'aucune règle
 * fiscale : il lit des faits techniques (statut du cycle, anomalies, montants du snapshot, paiements) ; le crédit à reporter n'est actif que si le cadre vérifié prévoit le report.
 *
 * <p>Statuts : A_PREPARER, EN_PREPARATION, ATTENTION_REQUISE, PRET_POUR_REVUE, EN_REVUE (période revue mais finalisation encore bloquée), PRET_A_FINALISER, FINALISE, A_PAYER, PARTIELLEMENT_PAYE, PAYE,
 * CREDIT_A_REPORTER.</p>
 */
public final class VatOperationalStatusEvaluator {

    public static final String A_PREPARER = "A_PREPARER";
    public static final String EN_PREPARATION = "EN_PREPARATION";
    public static final String ATTENTION_REQUISE = "ATTENTION_REQUISE";
    public static final String PRET_POUR_REVUE = "PRET_POUR_REVUE";
    public static final String EN_REVUE = "EN_REVUE";
    public static final String PRET_A_FINALISER = "PRET_A_FINALISER";
    public static final String FINALISE = "FINALISE";
    public static final String A_PAYER = "A_PAYER";
    public static final String PARTIELLEMENT_PAYE = "PARTIELLEMENT_PAYE";
    public static final String PAYE = "PAYE";
    public static final String CREDIT_A_REPORTER = "CREDIT_A_REPORTER";

    private VatOperationalStatusEvaluator() {
    }

    /**
     * @param netDeclared     solde déclaré (positif : TVA due ; négatif : crédit) ; fiable seulement quand la période est FINAL (lu du snapshot), {@code null} sinon
     * @param paid            total des paiements actifs
     * @param reportPrevu     le cadre VERIFIED prévoit le report d'un crédit
     * @param finalisable     le backend autorise la finalisation (aucun bloqueur, période revue)
     */
    public record Input(TaxPeriodStatus statut, boolean cadreVerifie, int aRevoir, int anomaliesBloquantes, boolean montantsFiscauxComplets, BigDecimal unexplained, BigDecimal netDeclared,
                        BigDecimal paid, boolean reportPrevu, boolean finalisable, int operations) {
    }

    public record Step(String code, String label, boolean ready, boolean blocking) {
    }

    /** {@code readySteps} / {@code totalSteps} : jamais un pourcentage ; {@code blockingSteps} : codes des étapes non prêtes qui empêchent d'avancer. */
    public record Result(String status, int readySteps, int totalSteps, List<String> blockingSteps, List<Step> steps, String creditState) {
    }

    public static Result evaluer(Input in) {
        BigDecimal net = in.netDeclared();
        BigDecimal paye = in.paid() == null ? BigDecimal.ZERO : in.paid();
        boolean final_ = in.statut() == TaxPeriodStatus.FINAL;
        boolean ouverte = in.statut() == TaxPeriodStatus.OPEN;
        boolean revue = in.statut() == TaxPeriodStatus.REVIEWED || final_;
        boolean pret = in.statut() == TaxPeriodStatus.READY_FOR_REVIEW;
        boolean due = final_ && net != null && net.signum() > 0;
        boolean credit = final_ && net != null && net.signum() < 0;
        String etatCredit = credit ? (in.reportPrevu() ? "REPORTABLE" : "TO_REVIEW") : "NONE";

        String statut;
        if (final_) {
            if (due) {
                statut = paye.signum() == 0 ? A_PAYER : paye.compareTo(net) >= 0 ? PAYE : PARTIELLEMENT_PAYE;
            } else if (credit && in.reportPrevu()) {
                statut = CREDIT_A_REPORTER;
            } else {
                statut = FINALISE;
            }
        } else if (ouverte) {
            statut = A_PREPARER;
        } else if (in.statut() == TaxPeriodStatus.REVIEWED) {
            statut = in.finalisable() ? PRET_A_FINALISER : EN_REVUE;
        } else if (pret) {
            statut = PRET_POUR_REVUE;
        } else {
            statut = in.aRevoir() > 0 || in.anomaliesBloquantes() > 0 ? ATTENTION_REQUISE : EN_PREPARATION;
        }

        List<Step> etapes = new ArrayList<>();
        etapes.add(new Step("OPERATIONS_CHARGEES", "Opérations chargées", !ouverte, ouverte));
        etapes.add(new Step("TRAITEMENTS_DECIDES", "Traitements décidés", !ouverte && in.aRevoir() == 0, in.aRevoir() > 0));
        etapes.add(new Step("CONTROLES", "Contrôles", !ouverte && in.anomaliesBloquantes() == 0, in.anomaliesBloquantes() > 0));
        etapes.add(new Step("AJUSTEMENTS", "Ajustements", !ouverte && (in.unexplained() == null || in.unexplained().signum() == 0), in.unexplained() != null && in.unexplained().signum() != 0));
        etapes.add(new Step("DECLARATION", "Déclaration", in.cadreVerifie() && in.montantsFiscauxComplets(), !(in.cadreVerifie() && in.montantsFiscauxComplets())));
        etapes.add(new Step("REVUE", "Revue", revue, false));
        etapes.add(new Step("FINALISATION", "Finalisation", final_, false));
        boolean paiementPret = final_ && (due ? paye.compareTo(net) >= 0 : !credit || in.reportPrevu());
        etapes.add(new Step("PAIEMENT_CREDIT", "Paiement / crédit", paiementPret, false));
        int prets = (int) etapes.stream().filter(Step::ready).count();
        List<String> bloquantes = etapes.stream().filter(s -> s.blocking() && !s.ready()).map(Step::code).toList();
        return new Result(statut, prets, etapes.size(), bloquantes, List.copyOf(etapes), etatCredit);
    }

    /**
     * Code du statut affiché dans le portefeuille du cabinet : un seul indicateur compact. « Prête / finalisable » n'est jamais affiché si le cadre réglementaire n'est pas vérifié
     * (CADRE_INCOMPLET).
     */
    public static String codePortefeuille(TaxPeriodStatus statut, boolean cadreVerifie, int aTraiter, BigDecimal netDeclared, BigDecimal paid, boolean reportPrevu) {
        BigDecimal paye = paid == null ? BigDecimal.ZERO : paid;
        return switch (statut) {
            case FINAL -> netDeclared != null && netDeclared.signum() > 0 ? (paye.signum() == 0 ? "A_PAYER" : paye.compareTo(netDeclared) >= 0 ? "PAYEE" : "PARTIELLEMENT_PAYEE")
                    : netDeclared != null && netDeclared.signum() < 0 && reportPrevu ? "CREDIT_A_REPORTER" : "FINALISEE";
            case REVIEWED -> cadreVerifie ? "PRETE" : "CADRE_INCOMPLET";
            case READY_FOR_REVIEW -> "A_REVOIR";
            case PREPARING, OPEN -> aTraiter > 0 ? "A_TRAITER" : statut == TaxPeriodStatus.OPEN ? "A_PREPARER" : cadreVerifie ? "A_PREPARER" : "CADRE_INCOMPLET";
        };
    }
}
