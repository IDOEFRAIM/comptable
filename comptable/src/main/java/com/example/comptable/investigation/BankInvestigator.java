package com.example.comptable.investigation;

import com.example.comptable.banque.BankInternalTransfer;
import com.example.comptable.banque.BankInternalTransferRepository;
import com.example.comptable.banque.BankReconciliation;
import com.example.comptable.banque.BankReconciliationRepository;
import com.example.comptable.banque.StatutReconciliation;
import com.example.comptable.comptabilite.domain.LigneEcriture;
import com.example.comptable.comptabilite.domain.LigneReleve;
import com.example.comptable.comptabilite.domain.ReleveBancaire;
import com.example.comptable.comptabilite.repository.LigneEcritureRepository;
import com.example.comptable.comptabilite.repository.LigneReleveRepository;
import com.example.comptable.comptabilite.repository.ReleveBancaireRepository;
import com.example.comptable.comptabilite.service.RapprochementAutomatiqueService;
import com.example.comptable.comptabilite.service.RapprochementClassifier;
import com.example.comptable.comptabilite.service.RapprochementScorer;
import com.example.comptable.investigation.InvestigationResult.Basis;
import com.example.comptable.investigation.InvestigationResult.Cause;
import com.example.comptable.investigation.InvestigationResult.Evidence;
import com.example.comptable.investigation.InvestigationResult.Nature;
import com.example.comptable.investigation.InvestigationResult.TimelineEvent;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/**
 * Investigation bancaire : UNE transaction (pourquoi n'est-elle pas rapprochee ?) ou UN releve (pourquoi le solde du releve differe-t-il du solde
 * comptable ?). Les liens enregistres (propositions, rapprochements annules, virements internes) passent avant le classement par montant et date.
 * Le releve n'est jamais charge ligne a ligne : tout se lit par agregats SQL et quelques lignes d'exemple.
 */
@Component
class BankInvestigator {

    static final int EXEMPLES = 20;

    private final LigneReleveRepository ligneReleveRepository;
    private final ReleveBancaireRepository releveRepository;
    private final BankReconciliationRepository reconciliationRepository;
    private final BankInternalTransferRepository transferRepository;
    private final LigneEcritureRepository ligneEcritureRepository;
    private final RapprochementAutomatiqueService automatiqueService;

    BankInvestigator(LigneReleveRepository ligneReleveRepository, ReleveBancaireRepository releveRepository, BankReconciliationRepository reconciliationRepository,
                     BankInternalTransferRepository transferRepository, LigneEcritureRepository ligneEcritureRepository,
                     RapprochementAutomatiqueService automatiqueService) {
        this.ligneReleveRepository = ligneReleveRepository;
        this.releveRepository = releveRepository;
        this.reconciliationRepository = reconciliationRepository;
        this.transferRepository = transferRepository;
        this.ligneEcritureRepository = ligneEcritureRepository;
        this.automatiqueService = automatiqueService;
    }

    // ------------------------------------------------------------------------------------------------ une transaction

    InvestigationResult.Builder transaction(UUID entrepriseId, UUID transactionId) {
        LigneReleve tx = ligneReleveRepository.avecReleve(List.of(transactionId)).stream().findFirst().filter(l -> l.getEntrepriseId().equals(entrepriseId))
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Transaction bancaire introuvable"));
        String libelleTx = tx.getLibelle() + " (" + tx.getDateOperation() + ")";
        InvestigationResult.Builder b = new InvestigationResult.Builder("LIGNE_RELEVE", transactionId.toString(), "Transaction « " + tx.getLibelle() + " » du " + tx.getDateOperation());
        b.related("LIGNE_RELEVE", transactionId.toString(), libelleTx);
        b.related("RELEVE", tx.getReleve().getId().toString(), "Releve " + tx.getReleve().getLibelle());
        Evidence preuveTx = new Evidence("BANK_TRANSACTION", "LIGNE_RELEVE", transactionId.toString(), "Transaction « " + tx.getLibelle() + " »", tx.getMontant(), tx.getDateOperation());
        b.evidence(preuveTx);
        b.event(new TimelineEvent(tx.getReleve().getCreatedAt() != null ? tx.getReleve().getCreatedAt().toLocalDate() : tx.getDateOperation(), "IMPORT",
                "Importee avec le releve " + tx.getReleve().getLibelle(), tx.getMontant(), "RELEVE", tx.getReleve().getId().toString()));
        b.event(new TimelineEvent(tx.getDateOperation(), "OPERATION", "Operation bancaire : " + tx.getLibelle(), tx.getMontant(), "LIGNE_RELEVE", transactionId.toString()));

        List<BankReconciliation> historique = reconciliationRepository.findHistoriqueDe(transactionId);
        List<UUID> cibles = historique.stream().map(BankReconciliation::getTargetId).filter(Objects::nonNull).toList();
        Map<UUID, LigneEcriture> ecritures = cibles.isEmpty() ? Map.of() : ligneEcritureRepository.chargerAvecEcriture(cibles).stream()
                .collect(java.util.stream.Collectors.toMap(LigneEcriture::getId, l -> l, (x, y) -> x));
        for (BankReconciliation r : historique) {
            LigneEcriture cible = ecritures.get(r.getTargetId());
            String piece = cible != null ? cible.getEcriture().getNumeroPiece() : "?";
            b.event(new TimelineEvent(r.getCreatedAt().toLocalDate(), "RECONCILIATION_" + r.getStatus(), "Rapprochement " + libelleStatut(r.getStatus()) + " avec l'ecriture " + piece,
                    tx.getMontant(), "LIGNE_ECRITURE", r.getTargetId().toString()));
        }
        List<BankInternalTransfer> transferts = transferRepository.findVivantsDont(List.of(transactionId));
        BankReconciliation confirme = historique.stream().filter(r -> r.getStatus() == StatutReconciliation.CONFIRMED).findFirst().orElse(null);
        BankReconciliation propose = historique.stream().filter(r -> r.getStatus() == StatutReconciliation.PROPOSED).findFirst().orElse(null);
        BankReconciliation annule = historique.stream().filter(r -> r.getStatus() == StatutReconciliation.REVERSED).reduce((x, y) -> y).orElse(null);

        if (!transferts.isEmpty()) {
            transfert(b, tx, transferts.get(0));
        } else if (confirme != null || tx.getLigneEcritureId() != null) {
            UUID cible = confirme != null ? confirme.getTargetId() : tx.getLigneEcritureId();
            LigneEcriture e = ecritures.get(cible);
            String piece = e != null ? e.getEcriture().getNumeroPiece() : "?";
            b.cause(new Cause(CauseCodes.BANK_RECONCILED, Nature.INFO, "La transaction est rapprochee a l'ecriture " + piece + " : rien a traiter.", Basis.PERSISTED, tx.getMontant(),
                    e == null ? List.of(preuveTx) : List.of(preuveTx, new Evidence("ENTRY", "LIGNE_ECRITURE", e.getId().toString(), "Ecriture " + piece, tx.getMontant(), e.getEcriture().getDateEcriture()))));
            if (e != null) {
                b.related("ECRITURE", e.getEcriture().getId().toString(), "Ecriture " + piece);
            }
        } else {
            nonRapprochee(b, tx, propose, annule, ecritures);
        }
        if ("POSSIBLE_DUPLICATE".equals(tx.getDuplicateStatus())) {
            b.cause(new Cause(CauseCodes.BANK_DUPLICATE_TRANSACTION, Nature.ANOMALY, "Une autre transaction du meme jour, de meme montant et de meme sens existe : c'est peut-etre la meme operation importee deux fois.",
                    Basis.EXACT, tx.getMontant(), List.of(preuveTx)));
            b.action("CONFIRM_DISTINCT", "Confirmer que ce sont deux operations distinctes", "Banque > Doublons possibles", "LIGNE_RELEVE", transactionId.toString());
        }
        b.summary(b.aUneAnomalie() ? "La transaction « " + tx.getLibelle() + " » (" + Fmt.m(tx.getMontant()) + ") n'est pas encore expliquee par la comptabilite."
                : "La transaction « " + tx.getLibelle() + " » (" + Fmt.m(tx.getMontant()) + ") est expliquee.");
        return b;
    }

    private void nonRapprochee(InvestigationResult.Builder b, LigneReleve tx, BankReconciliation propose, BankReconciliation annule, Map<UUID, LigneEcriture> ecritures) {
        String id = tx.getId().toString();
        if (propose != null) {
            LigneEcriture e = ecritures.get(propose.getTargetId());
            String piece = e != null ? e.getEcriture().getNumeroPiece() : "?";
            b.cause(new Cause(CauseCodes.BANK_MATCH_TO_CONFIRM, Nature.ANOMALY, "Le systeme propose l'ecriture " + piece + " (confiance " + (propose.getConfidence() != null ? propose.getConfidence().intValue() : "?")
                    + " %) mais le rapprochement attend une confirmation.", Basis.PERSISTED, tx.getMontant(),
                    e == null ? List.of() : List.of(new Evidence("PROPOSED_ENTRY", "LIGNE_ECRITURE", e.getId().toString(), "Ecriture " + piece, tx.getMontant(), e.getEcriture().getDateEcriture()))));
            if (e != null) {
                b.related("ECRITURE", e.getEcriture().getId().toString(), "Ecriture proposee " + piece);
            }
            b.action("CONFIRM_MATCH", "Confirmer ou rejeter la proposition", "Banque > Rapprochement", "LIGNE_RELEVE", id);
            return;
        }
        if (annule != null) {
            LigneEcriture e = ecritures.get(annule.getTargetId());
            b.cause(new Cause(CauseCodes.BANK_RECONCILIATION_REVERSED, Nature.ANOMALY, "Le rapprochement a ete annule" + (annule.getReversalReason() != null ? " (« " + annule.getReversalReason() + " »)" : "")
                    + " : la transaction ne sera pas reproposee a la meme ecriture.", Basis.PERSISTED, tx.getMontant(),
                    List.of(new Evidence("REVERSED_RECONCILIATION", "LIGNE_ECRITURE", annule.getTargetId().toString(), "Rapprochement annule avec l'ecriture " + (e != null ? e.getEcriture().getNumeroPiece() : "?"),
                            tx.getMontant(), annule.getReversedAt() != null ? annule.getReversedAt().toLocalDate() : null))));
        }
        RapprochementScorer.ClassementNote note = automatiqueService.classerCibles(List.of(tx)).get(tx.getId());
        if (note == null || note.statut() == RapprochementClassifier.Statut.UNMATCHED) {
            b.cause(new Cause(CauseCodes.BANK_TRANSACTION_UNMATCHED, Nature.ANOMALY, "Aucune ecriture validee du compte ne correspond a cette transaction (meme sens et meme montant " + Fmt.m(tx.getMontant().abs())
                    + ") : le reglement n'est probablement pas comptabilise.", Basis.EXACT, tx.getMontant(), List.of()));
            b.action("ENTER_PAYMENT", "Saisir le reglement correspondant", "Comptabilite > Saisie", "LIGNE_RELEVE", id);
            return;
        }
        List<Evidence> candidats = note.candidats().stream().limit(EXEMPLES).map(c -> new Evidence("CANDIDATE", "LIGNE_ECRITURE", c.ligne().getId().toString(),
                "Ecriture " + c.ligne().getEcriture().getNumeroPiece() + " (" + c.explication() + ")", tx.getMontant().abs(), c.ligne().getEcriture().getDateEcriture())).toList();
        note.candidats().stream().limit(EXEMPLES).forEach(c -> b.related("ECRITURE", c.ligne().getEcriture().getId().toString(), "Ecriture candidate " + c.ligne().getEcriture().getNumeroPiece()));
        if (note.statut() == RapprochementClassifier.Statut.AMBIGUOUS) {
            b.cause(new Cause(CauseCodes.BANK_MATCH_AMBIGUOUS, Nature.ANOMALY, note.candidats().size() + " ecritures ont le meme montant : le systeme ne choisit pas a votre place.", Basis.EXACT,
                    tx.getMontant(), candidats));
            b.action("CHOOSE_MATCH", "Choisir l'ecriture correspondante", "Banque > Rapprochement", "LIGNE_RELEVE", id);
        } else {
            boolean exact = note.statut() == RapprochementClassifier.Statut.EXACT;
            b.cause(new Cause(CauseCodes.BANK_MATCH_TO_CONFIRM, Nature.ANOMALY, "Une seule ecriture correspond (" + note.candidats().get(0).explication() + ")" + (exact ? "." : ", mais l'ecart de date impose une confirmation."),
                    exact ? Basis.EXACT : Basis.HEURISTIC, tx.getMontant(), candidats));
            b.action("CONFIRM_MATCH", "Confirmer le rapprochement", "Banque > Rapprochement", "LIGNE_RELEVE", id);
        }
    }

    private void transfert(InvestigationResult.Builder b, LigneReleve tx, BankInternalTransfer t) {
        UUID autre = t.getDebitTransactionId().equals(tx.getId()) ? t.getCreditTransactionId() : t.getDebitTransactionId();
        b.related("LIGNE_RELEVE", autre.toString(), "Autre jambe du virement interne");
        Evidence preuve = new Evidence("INTERNAL_TRANSFER", "LIGNE_RELEVE", autre.toString(), "Autre jambe du virement (" + Fmt.m(t.getAmount()) + ")", t.getAmount(), null);
        if (t.getStatus() == StatutReconciliation.PROPOSED) {
            b.cause(new Cause(CauseCodes.INTERNAL_TRANSFER_PENDING, Nature.ANOMALY, "Cette transaction semble etre une jambe de virement entre deux comptes de l'entreprise ; la detection attend votre confirmation.",
                    Basis.PERSISTED, t.getAmount(), List.of(preuve)));
            b.action("CONFIRM_TRANSFER", "Confirmer le virement interne", "Banque > Virements internes", "LIGNE_RELEVE", tx.getId().toString());
        } else if (t.getEcritureId() == null) {
            b.cause(new Cause(CauseCodes.INTERNAL_TRANSFER_WITHOUT_ENTRY, Nature.ANOMALY, "Le virement interne est confirme mais aucune ecriture de virement n'existe : le solde comptable des deux comptes ne le reflete pas.",
                    Basis.PERSISTED, t.getAmount(), List.of(preuve)));
            b.action("ENTER_TRANSFER_ENTRY", "Saisir l'ecriture du virement interne", "Comptabilite > Saisie", "LIGNE_RELEVE", tx.getId().toString());
        } else {
            b.cause(new Cause(CauseCodes.BANK_RECONCILED, Nature.INFO, "Virement interne confirme et comptabilise : rien a traiter.", Basis.PERSISTED, t.getAmount(), List.of(preuve)));
            b.related("ECRITURE", t.getEcritureId().toString(), "Ecriture du virement interne");
        }
    }

    private static String libelleStatut(StatutReconciliation s) {
        return switch (s) {
            case PROPOSED -> "propose";
            case CONFIRMED -> "confirme";
            case REJECTED -> "rejete";
            case REVERSED -> "annule";
        };
    }

    // ------------------------------------------------------------------------------------------------ un releve

    InvestigationResult.Builder releve(UUID entrepriseId, UUID releveId) {
        ReleveBancaire r = releveRepository.findById(releveId).filter(x -> x.getEntreprise().getId().equals(entrepriseId))
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Releve introuvable"));
        UUID compteId = r.getCompte().getId();
        InvestigationResult.Builder b = new InvestigationResult.Builder("RELEVE", releveId.toString(), "Releve " + r.getLibelle() + " (compte " + r.getCompte().getNumero() + ")");
        b.related("RELEVE", releveId.toString(), "Releve " + r.getLibelle());
        BigDecimal comptable = Objects.requireNonNullElse(ligneEcritureRepository.soldeNetAvant(compteId, r.getDateFin().plusDays(1)), BigDecimal.ZERO);
        b.event(new TimelineEvent(r.getDateDebut(), "PERIODE", "Debut du releve (solde annonce " + (r.getSoldeDebut() != null ? Fmt.m(r.getSoldeDebut()) : "non renseigne") + ")", r.getSoldeDebut(), "RELEVE", releveId.toString()));
        b.event(new TimelineEvent(r.getDateFin(), "PERIODE", "Fin du releve (solde annonce " + (r.getSoldeFin() != null ? Fmt.m(r.getSoldeFin()) : "non renseigne") + ")", r.getSoldeFin(), "RELEVE", releveId.toString()));
        if (r.getSoldeFin() == null) {
            b.comparison("Solde comptable du compte " + r.getCompte().getNumero(), comptable, "Solde du releve (non renseigne)", null);
            b.summary("Le solde du releve n'est pas renseigne : seul le pointage des transactions peut etre controle.");
        } else {
            b.comparison("Solde comptable du compte " + r.getCompte().getNumero(), comptable, "Solde du releve", r.getSoldeFin());
        }
        BigDecimal ecart = r.getSoldeFin() == null ? null : r.getSoldeFin().subtract(comptable);

        Object[] sansEcriture = ligneReleveRepository.agregatNonPointees(releveId).get(0);
        Object[] jambes = ligneReleveRepository.agregatJambesDeVirement(releveId).get(0);
        BigDecimal montantSans = (BigDecimal) sansEcriture[0];
        long nbSans = ((Number) sansEcriture[1]).longValue();
        BigDecimal montantJambes = (BigDecimal) jambes[0];
        long nbJambes = ((Number) jambes[1]).longValue();
        BigDecimal montantNonRapproche = montantSans.subtract(montantJambes);
        long nbNonRapproche = nbSans - nbJambes;
        BigDecimal ecritureHorsReleve = Objects.requireNonNullElse(ligneEcritureRepository.montantNonPointeEntre(compteId, r.getDateDebut(), r.getDateFin()), BigDecimal.ZERO);

        List<LigneReleve> exemples = nbNonRapproche == 0 ? List.of() : ligneReleveRepository.premieresSansEcriture(releveId, PageRequest.of(0, EXEMPLES));
        List<Evidence> preuvesBanque = exemples.stream().map(l -> new Evidence("BANK_TRANSACTION", "LIGNE_RELEVE", l.getId().toString(), "Transaction « " + l.getLibelle() + " »", l.getMontant(), l.getDateOperation())).toList();
        exemples.forEach(l -> b.related("LIGNE_RELEVE", l.getId().toString(), "Transaction « " + l.getLibelle() + " »"));
        if (nbNonRapproche > 0) {
            b.cause(new Cause(CauseCodes.BANK_TRANSACTION_UNMATCHED, Nature.ANOMALY, nbNonRapproche + " transaction(s) du releve (" + Fmt.m(montantNonRapproche)
                    + " au total) n'ont aucune ecriture rapprochee : elles ne figurent pas dans la comptabilite.", Basis.PERSISTED, montantNonRapproche, preuvesBanque));
            b.action("REVIEW_BANK", "Rapprocher ou saisir les transactions manquantes", "Banque", "RELEVE", releveId.toString());
        }
        if (nbJambes > 0) {
            b.cause(new Cause(CauseCodes.INTERNAL_TRANSFER_WITHOUT_ENTRY, Nature.ANOMALY, nbJambes + " jambe(s) de virement interne confirme(s) (" + Fmt.m(montantJambes) + ") sans ecriture de virement.",
                    Basis.PERSISTED, montantJambes, List.of()));
        }
        if (ecritureHorsReleve.signum() != 0) {
            List<LigneEcriture> hors = ligneEcritureRepository.nonPointeesEntre(compteId, r.getDateDebut(), r.getDateFin(), PageRequest.of(0, EXEMPLES));
            hors.forEach(e -> b.related("ECRITURE", e.getEcriture().getId().toString(), "Ecriture " + e.getEcriture().getNumeroPiece()));
            b.cause(new Cause(CauseCodes.BOOKED_NOT_ON_STATEMENT, Nature.EXPLANATION, "Des ecritures du compte (net " + Fmt.m(ecritureHorsReleve)
                    + ") sont comptabilisees sur la periode du releve mais ne correspondent a aucune transaction de releve : cheque non encaisse, virement en transit ou ecriture a tort.", Basis.PERSISTED, ecritureHorsReleve,
                    hors.stream().map(e -> new Evidence("ENTRY", "LIGNE_ECRITURE", e.getId().toString(), "Ecriture " + e.getEcriture().getNumeroPiece() + " : " + e.getEcriture().getLibelle(),
                            e.getDebit().subtract(e.getCredit()), e.getEcriture().getDateEcriture())).toList()));
        }
        if (ecart != null) {
            // releve - comptable = (transactions de banque absentes de la compta) - (ecritures de compta absentes de la banque)
            BigDecimal ouvertureComptable = Objects.requireNonNullElse(ligneEcritureRepository.soldeNetAvant(compteId, r.getDateDebut()), BigDecimal.ZERO);
            BigDecimal ecartOuverture = r.getSoldeDebut() == null ? BigDecimal.ZERO : r.getSoldeDebut().subtract(ouvertureComptable);
            if (ecartOuverture.signum() != 0) {
                b.cause(new Cause(CauseCodes.OPENING_BALANCE_MISMATCH, Nature.ANOMALY, "Le solde de debut du releve (" + Fmt.m(r.getSoldeDebut()) + ") differe du solde comptable a l'ouverture de la periode ("
                        + Fmt.m(ouvertureComptable) + ") : l'ecart de " + Fmt.m(ecartOuverture.abs()) + " vient d'avant ce releve.", Basis.EXACT, ecartOuverture, List.of()));
            }
            BigDecimal explique = ecartOuverture.add(montantSans).subtract(ecritureHorsReleve);
            BigDecimal residu = ecart.subtract(explique);
            Map<String, Object> eq = new java.util.LinkedHashMap<>();
            eq.put("ecartReleveMoinsComptable", ecart);
            eq.put("ecartALOuverture", ecartOuverture);
            eq.put("transactionsSansEcriture", montantSans);
            eq.put("ecrituresSansTransaction", ecritureHorsReleve);
            eq.put("residuInexplique", residu);
            b.detail("equationDeLEcart", eq);
            if (residu.signum() != 0) {
                b.cause(new Cause(CauseCodes.BANK_DIFFERENCE_UNEXPLAINED, Nature.ANOMALY, "Apres prise en compte des transactions sans ecriture et des ecritures sans transaction, il reste " + Fmt.m(residu)
                        + " d'ecart inexplique : erreur de solde d'ouverture, transaction manquante a l'import ou ecriture dans un autre compte.", Basis.EXACT, residu, List.of()));
            }
            b.summary(ecart.signum() == 0 && nbNonRapproche == 0 ? "Le releve concorde avec la comptabilite et toutes ses transactions sont expliquees."
                    : ecart.signum() == 0 ? "Les soldes concordent, mais " + nbNonRapproche + " transaction(s) restent a rapprocher."
                    : "Le solde du releve (" + Fmt.m(r.getSoldeFin()) + ") differe du solde comptable (" + Fmt.m(comptable) + ") de " + Fmt.m(ecart.abs())
                    + (residu.signum() == 0 ? " ; l'ecart s'explique entierement par les transactions et ecritures ci-dessus." : " ; " + Fmt.m(residu.abs()) + " restent inexpliques."));
        }
        return b;
    }

    static List<Evidence> bornees(List<Evidence> l) {
        return l.size() > EXEMPLES ? new ArrayList<>(l.subList(0, EXEMPLES)) : l;
    }
}
