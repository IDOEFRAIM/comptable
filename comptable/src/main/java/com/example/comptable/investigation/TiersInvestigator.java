package com.example.comptable.investigation;

import com.example.comptable.banque.BankReconciliation;
import com.example.comptable.banque.BankReconciliationRepository;
import com.example.comptable.comptabilite.allocation.PaymentAllocation;
import com.example.comptable.comptabilite.allocation.PaymentAllocationRepository;
import com.example.comptable.comptabilite.domain.LigneEcriture;
import com.example.comptable.comptabilite.domain.Tiers;
import com.example.comptable.comptabilite.domain.TypeJournal;
import com.example.comptable.comptabilite.domain.TypeTiers;
import com.example.comptable.comptabilite.repository.ExerciceComptableRepository;
import com.example.comptable.comptabilite.repository.LigneEcritureRepository;
import com.example.comptable.comptabilite.repository.TiersRepository;
import com.example.comptable.demande.domain.DemandePiece;
import com.example.comptable.demande.repository.DemandePieceRepository;
import com.example.comptable.document.domain.Document;
import com.example.comptable.document.repository.DocumentRepository;
import com.example.comptable.investigation.InvestigationResult.Basis;
import com.example.comptable.investigation.InvestigationResult.Cause;
import com.example.comptable.investigation.InvestigationResult.Evidence;
import com.example.comptable.investigation.InvestigationResult.Nature;
import com.example.comptable.investigation.InvestigationResult.TimelineEvent;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * Investigation d'un COMPTE DE TIERS (client ou fournisseur) et de ses lignes. Reconstruit : solde d'ouverture, factures, avoirs, reglements, ecritures
 * diverses, affectations, puis rapproche le solde du grand livre du « restant du » deduit des affectations. Un solde a contre-sens n'est jamais
 * qualifie d'anomalie avant d'avoir ete explique : un reglement non affecte ou un avoir non impute le justifie economiquement.
 *
 * <p>Lecture seule. Sources par ordre de fiabilite : affectations et rapprochements enregistres, puis ecritures ; aucune heuristique.</p>
 */
@Component
class TiersInvestigator {

    static final int MAX_LIGNES_DETAIL = 50;
    static final int MAX_EVENEMENTS = 100;

    enum Genre { FACTURE, AVOIR, REGLEMENT, DIVERS }

    /** Une ligne du compte avec ce qui en est affecte. {@code ouverture} : anterieure au debut de l'exercice de la date d'arrete. */
    record Ligne(LigneEcriture l, Genre genre, BigDecimal montant, BigDecimal alloue, boolean ouverture) {
        BigDecimal reste() {
            return montant.subtract(alloue).max(BigDecimal.ZERO);
        }

        UUID id() {
            return l.getId();
        }
    }

    /** Photographie complete d'un tiers a une date : ce que lisent toutes les investigations qui le concernent. */
    record Analyse(Tiers tiers, LocalDate au, List<Ligne> lignes, List<PaymentAllocation> allocations, Map<UUID, Document> documents,
                   Map<UUID, DemandePiece> demandes, Map<UUID, BankReconciliation> rapprochements, Map<UUID, BankReconciliation> inverses, BigDecimal solde) {
    }

    private final TiersRepository tiersRepository;
    private final LigneEcritureRepository ligneEcritureRepository;
    private final PaymentAllocationRepository allocationRepository;
    private final ExerciceComptableRepository exerciceRepository;
    private final DocumentRepository documentRepository;
    private final DemandePieceRepository demandeRepository;
    private final BankReconciliationRepository reconciliationRepository;

    TiersInvestigator(TiersRepository tiersRepository, LigneEcritureRepository ligneEcritureRepository, PaymentAllocationRepository allocationRepository,
                      ExerciceComptableRepository exerciceRepository, DocumentRepository documentRepository, DemandePieceRepository demandeRepository,
                      BankReconciliationRepository reconciliationRepository) {
        this.tiersRepository = tiersRepository;
        this.ligneEcritureRepository = ligneEcritureRepository;
        this.allocationRepository = allocationRepository;
        this.exerciceRepository = exerciceRepository;
        this.documentRepository = documentRepository;
        this.demandeRepository = demandeRepository;
        this.reconciliationRepository = reconciliationRepository;
    }

    // ------------------------------------------------------------------------------------------------ lecture

    Analyse analyser(UUID entrepriseId, UUID tiersId, LocalDate arrete) {
        Tiers tiers = tiersRepository.findById(tiersId).filter(t -> t.getEntreprise().getId().equals(entrepriseId))
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Tiers introuvable"));
        LocalDate au = arrete != null ? arrete : LocalDate.now();
        LocalDate debutExercice = exerciceRepository.findByEntrepriseIdAndDate(entrepriseId, au).map(e -> e.getDateDebut()).orElse(null);
        List<LigneEcriture> brutes = ligneEcritureRepository.lignesDuTiers(entrepriseId, tiersId,
                tiers.getCompteComptable() != null ? tiers.getCompteComptable().getId() : new UUID(0, 0)).stream()
                .filter(l -> !l.getEcriture().getDateEcriture().isAfter(au)).toList();
        List<UUID> ids = brutes.stream().map(LigneEcriture::getId).toList();
        List<PaymentAllocation> allocations = ids.isEmpty() ? List.of() : allocationRepository.activesDesLignes(ids);
        Map<UUID, BigDecimal> alloueFacture = new HashMap<>();
        Map<UUID, BigDecimal> alloueReglement = new HashMap<>();
        for (PaymentAllocation a : allocations) {
            alloueFacture.merge(a.getInvoiceLineId(), a.getAllocatedAmount(), BigDecimal::add);
            alloueReglement.merge(a.getPaymentLineId(), a.getAllocatedAmount(), BigDecimal::add);
        }
        boolean client = tiers.getType() == TypeTiers.CLIENT;
        List<Ligne> lignes = new ArrayList<>();
        for (LigneEcriture l : brutes) {
            boolean debit = l.getDebit().signum() > 0;
            BigDecimal montant = debit ? l.getDebit() : l.getCredit();
            boolean coteNormal = debit == client;
            TypeJournal journal = l.getEcriture().getJournal().getType();
            Genre genre = switch (journal) {
                case VENTES, ACHATS -> coteNormal ? Genre.FACTURE : Genre.AVOIR;
                // Une ligne de banque du cote normal du tiers est un remboursement, pas un reglement a repartir.
                case BANQUE, CAISSE -> coteNormal ? Genre.DIVERS : Genre.REGLEMENT;
                default -> Genre.DIVERS;
            };
            BigDecimal alloue = genre == Genre.FACTURE ? alloueFacture.getOrDefault(l.getId(), BigDecimal.ZERO)
                    : genre == Genre.REGLEMENT ? alloueReglement.getOrDefault(l.getId(), BigDecimal.ZERO) : BigDecimal.ZERO;
            boolean ouverture = debutExercice != null && l.getEcriture().getDateEcriture().isBefore(debutExercice);
            lignes.add(new Ligne(l, genre, montant, alloue, ouverture));
        }
        Set<UUID> ecritures = lignes.stream().filter(x -> x.genre() == Genre.FACTURE || x.genre() == Genre.AVOIR).map(x -> x.l().getEcriture().getId())
                .collect(Collectors.toSet());
        Map<UUID, Document> documents = new HashMap<>();
        Map<UUID, DemandePiece> demandes = new HashMap<>();
        if (!ecritures.isEmpty()) {
            documentRepository.deLEcritures(entrepriseId, ecritures).forEach(d -> documents.putIfAbsent(d.getEcritureId(), d));
            demandeRepository.deLEcritures(entrepriseId, ecritures).forEach(d -> demandes.putIfAbsent(d.getEcritureId(), d));
        }
        List<UUID> reglements = lignes.stream().filter(x -> x.genre() == Genre.REGLEMENT).map(Ligne::id).toList();
        Map<UUID, BankReconciliation> rapprochements = new HashMap<>();
        Map<UUID, BankReconciliation> inverses = new HashMap<>();
        if (!reglements.isEmpty()) {
            reconciliationRepository.findConfirmeesSurCibles(reglements).forEach(r -> rapprochements.put(r.getTargetId(), r));
            reconciliationRepository.findInversesSurCibles(reglements).forEach(r -> inverses.putIfAbsent(r.getTargetId(), r));
        }
        BigDecimal solde = lignes.stream().map(x -> sens(client, x.l())).reduce(BigDecimal.ZERO, BigDecimal::add);
        return new Analyse(tiers, au, lignes, allocations, documents, demandes, rapprochements, inverses, solde);
    }

    /** Positif = situation normale du tiers (creance client, dette fournisseur) ; negatif = a contre-sens. */
    static BigDecimal sens(boolean client, LigneEcriture l) {
        return client ? l.getDebit().subtract(l.getCredit()) : l.getCredit().subtract(l.getDebit());
    }

    // ------------------------------------------------------------------------------------------------ investigation d'un tiers

    InvestigationResult.Builder investiguerTiers(UUID entrepriseId, UUID tiersId, LocalDate arrete) {
        Analyse a = analyser(entrepriseId, tiersId, arrete);
        boolean client = a.tiers().getType() == TypeTiers.CLIENT;
        String role = client ? "client" : "fournisseur";
        InvestigationResult.Builder b = new InvestigationResult.Builder("TIERS", tiersId.toString(), a.tiers().getNom());
        b.related("TIERS", tiersId.toString(), a.tiers().getNom());

        BigDecimal ouverture = somme(a, null, true, client);
        BigDecimal factures = somme(a, Genre.FACTURE, false, client);
        BigDecimal avoirs = somme(a, Genre.AVOIR, false, client);
        BigDecimal reglements = somme(a, Genre.REGLEMENT, false, client);
        BigDecimal divers = somme(a, Genre.DIVERS, false, client);
        Map<String, Object> equation = new LinkedHashMap<>();
        equation.put("ouverture", ouverture);
        equation.put("factures", factures);
        equation.put("avoirs", avoirs);
        equation.put("reglements", reglements);
        equation.put("ecrituresDiverses", divers);
        equation.put("soldeActuel", a.solde());
        b.detail("equationDuSolde", equation);

        BigDecimal resteFactures = a.lignes().stream().filter(x -> x.genre() == Genre.FACTURE).map(Ligne::reste).reduce(BigDecimal.ZERO, BigDecimal::add);
        BigDecimal nonAffecte = a.lignes().stream().filter(x -> x.genre() == Genre.REGLEMENT).map(Ligne::reste).reduce(BigDecimal.ZERO, BigDecimal::add);
        BigDecimal avoirsBruts = a.lignes().stream().filter(x -> x.genre() == Genre.AVOIR).map(Ligne::montant).reduce(BigDecimal.ZERO, BigDecimal::add);
        BigDecimal autres = a.lignes().stream().filter(x -> x.genre() == Genre.DIVERS).map(x -> sens(client, x.l())).reduce(BigDecimal.ZERO, BigDecimal::add);
        BigDecimal restantDu = resteFactures.subtract(nonAffecte).subtract(avoirsBruts).add(autres);
        b.comparison("Restant du selon les factures, reglements et affectations", restantDu, "Solde du compte de tiers", a.solde());
        if (restantDu.compareTo(a.solde()) != 0) {
            b.cause(new Cause("ALLOCATION_IMBALANCE", Nature.ANOMALY,
                    "Les affectations ne s'equilibrent pas entre les factures et les reglements de ce tiers (ecart " + Fmt.m(a.solde().subtract(restantDu))
                            + ") : une affectation designe peut-etre une ligne d'un autre tiers.", Basis.PERSISTED, a.solde().subtract(restantDu), List.of()));
        }

        causesLignes(b, a, client, null);
        qualifierSolde(b, a, client, role, nonAffecte, avoirsBruts);
        chronologie(b, a, client);
        facturesOuvertes(b, a, client);

        String situation = a.solde().signum() == 0 ? "soldé" : a.solde().signum() > 0 ? "solde normal de " + Fmt.m(a.solde())
                : "solde a contre-sens de " + Fmt.m(a.solde().abs());
        b.summary(b.aUneAnomalie()
                ? "Le " + role + " " + a.tiers().getNom() + " presente " + situation + " et au moins un point demande une decision."
                : "Le " + role + " " + a.tiers().getNom() + " : " + situation + ". Rien a corriger.");
        return b;
    }

    private BigDecimal somme(Analyse a, Genre genre, boolean ouverture, boolean client) {
        return a.lignes().stream().filter(x -> x.ouverture() == ouverture && (genre == null || (!ouverture && x.genre() == genre)))
                .map(x -> sens(client, x.l())).reduce(BigDecimal.ZERO, BigDecimal::add);
    }

    // ------------------------------------------------------------------------------------------------ causes par ligne

    /**
     * Reglements non affectes, factures ouvertes, avoirs et pieces manquantes. {@code seulement} restreint a une ligne (investigation d'un reglement ou
     * d'une facture precise) ; null : tout le compte.
     */
    void causesLignes(InvestigationResult.Builder b, Analyse a, boolean client, UUID seulement) {
        List<Ligne> facturesOuvertes = a.lignes().stream().filter(x -> x.genre() == Genre.FACTURE && x.reste().signum() > 0).toList();
        Map<UUID, List<PaymentAllocation>> parPaiement = a.allocations().stream().collect(Collectors.groupingBy(PaymentAllocation::getPaymentLineId));
        Map<UUID, List<PaymentAllocation>> parFacture = a.allocations().stream().collect(Collectors.groupingBy(PaymentAllocation::getInvoiceLineId));
        Map<UUID, Ligne> parId = a.lignes().stream().collect(Collectors.toMap(Ligne::id, x -> x));
        // Sur un compte entier, les factures ouvertes et sans piece sont RESUMEES (nombre, montant, quelques exemples) : un tiers de milliers de lignes
        // ne produit jamais des centaines de causes. La liste detaillee (plafonnee) est dans « facturesOuvertes ».
        if (seulement == null) {
            // Les factures les plus recentes restent navigables, reglees ou non (les plus anciennes sont resumees).
            List<Ligne> factures = a.lignes().stream().filter(x -> x.genre() == Genre.FACTURE).toList();
            factures.subList(Math.max(0, factures.size() - 20), factures.size())
                    .forEach(x -> b.related("ECRITURE", x.l().getEcriture().getId().toString(), "Facture " + x.l().getEcriture().getNumeroPiece()));
        }
        List<Ligne> partielles = new ArrayList<>();
        List<Ligne> impayees = new ArrayList<>();
        List<Ligne> sansPiece = new ArrayList<>();
        int anomaliesReglements = 0;
        for (Ligne x : a.lignes()) {
            if (seulement != null && !x.id().equals(seulement) && !lieA(seulement, x, parPaiement, parFacture)) {
                continue;
            }
            String piece = x.l().getEcriture().getNumeroPiece();
            LocalDate date = x.l().getEcriture().getDateEcriture();
            switch (x.genre()) {
                case REGLEMENT -> {
                    // Au-dela du plafond, les reglements non affectes restent comptes (resume) mais ne sont plus detailles un par un.
                    if (seulement == null && x.reste().signum() > 0 && ++anomaliesReglements > MAX_LIGNES_DETAIL) {
                        break;
                    }
                    reglement(b, a, x, facturesOuvertes, parPaiement.getOrDefault(x.id(), List.of()), parId);
                }
                case FACTURE -> {
                    if (seulement == null) {
                        if (x.reste().signum() > 0) {
                            (x.alloue().signum() > 0 ? partielles : impayees).add(x);
                        }
                        if (x.l().getEcriture().getJournal().getType() == TypeJournal.ACHATS && !a.documents().containsKey(x.l().getEcriture().getId())) {
                            sansPiece.add(x);
                        }
                    } else {
                        facture(b, a, x, parFacture.getOrDefault(x.id(), List.of()), parId, a.au());
                    }
                }
                case AVOIR -> {
                    if (x.montant().signum() > 0) {
                        b.cause(new Cause(CauseCodes.CREDIT_NOTE_NOT_APPLIED, Nature.EXPLANATION,
                                "L'avoir " + piece + " du " + date + " (" + Fmt.m(x.montant()) + ") reduit le solde mais n'est rattache a aucune facture : "
                                        + "les avoirs ne sont pas encore modelises comme des affectations.", Basis.LINEAGE, x.montant(),
                                List.of(preuveLigne("CREDIT_NOTE", x))));
                        b.related("ECRITURE", x.l().getEcriture().getId().toString(), "Avoir " + piece);
                    }
                }
                default -> { }
            }
        }
        resumer(b, partielles, CauseCodes.PARTIAL_PAYMENT, Nature.INFO, "facture(s) partiellement reglee(s)", a.au());
        resumer(b, impayees, CauseCodes.UNPAID_INVOICE, Nature.INFO, "facture(s) non reglee(s)", a.au());
        if (!sansPiece.isEmpty()) {
            BigDecimal total = sansPiece.stream().map(Ligne::montant).reduce(BigDecimal.ZERO, BigDecimal::add);
            b.cause(new Cause(CauseCodes.MISSING_DOCUMENT, Nature.ANOMALY, sansPiece.size() + " facture(s) d'achat (" + Fmt.m(total) + ") n'ont aucune piece justificative rattachee.",
                    Basis.EXACT, total, sansPiece.stream().limit(MAX_LIGNES_DETAIL).map(x -> preuveLigne("INVOICE", x)).toList()));
            sansPiece.stream().limit(MAX_LIGNES_DETAIL).forEach(x -> b.related("ECRITURE", x.l().getEcriture().getId().toString(), "Facture " + x.l().getEcriture().getNumeroPiece()));
        }
    }

    private void resumer(InvestigationResult.Builder b, List<Ligne> lignes, String code, Nature nature, String libelle, LocalDate au) {
        if (lignes.isEmpty()) {
            return;
        }
        BigDecimal reste = lignes.stream().map(Ligne::reste).reduce(BigDecimal.ZERO, BigDecimal::add);
        long plusAncienne = lignes.stream().mapToLong(x -> ChronoUnit.DAYS.between(x.l().getEcriture().getDateEcriture(), au)).max().orElse(0);
        b.cause(new Cause(code, nature, lignes.size() + " " + libelle + " pour " + Fmt.m(reste) + " au total (la plus ancienne a " + plusAncienne + " jours).", Basis.PERSISTED, reste,
                lignes.stream().limit(MAX_LIGNES_DETAIL).map(x -> preuveLigne("INVOICE", x)).toList()));
        lignes.stream().limit(MAX_LIGNES_DETAIL).forEach(x -> b.related("ECRITURE", x.l().getEcriture().getId().toString(), "Facture " + x.l().getEcriture().getNumeroPiece()));
    }

    private boolean lieA(UUID ligneId, Ligne x, Map<UUID, List<PaymentAllocation>> parPaiement, Map<UUID, List<PaymentAllocation>> parFacture) {
        return parPaiement.getOrDefault(ligneId, List.of()).stream().anyMatch(al -> al.getInvoiceLineId().equals(x.id()))
                || parFacture.getOrDefault(ligneId, List.of()).stream().anyMatch(al -> al.getPaymentLineId().equals(x.id()));
    }

    private void reglement(InvestigationResult.Builder b, Analyse a, Ligne x, List<Ligne> facturesOuvertes, List<PaymentAllocation> allocs, Map<UUID, Ligne> parId) {
        String piece = x.l().getEcriture().getNumeroPiece();
        b.related("ECRITURE", x.l().getEcriture().getId().toString(), "Reglement " + piece);
        List<Evidence> preuves = new ArrayList<>();
        preuves.add(preuveLigne("PAYMENT", x));
        for (PaymentAllocation al : allocs) {
            Ligne f = parId.get(al.getInvoiceLineId());
            preuves.add(new Evidence("ALLOCATION", "ALLOCATION", al.getId().toString(), "Affectation a la facture " + (f != null ? f.l().getEcriture().getNumeroPiece() : "?"),
                    al.getAllocatedAmount(), al.getCreatedAt().toLocalDate()));
        }
        BankReconciliation pointe = a.rapprochements().get(x.id());
        BankReconciliation defait = a.inverses().get(x.id());
        if (pointe != null) {
            preuves.add(new Evidence("BANK_RECONCILIATION", "LIGNE_RELEVE", pointe.getBankTransactionId().toString(), "Rapproche a une transaction bancaire", x.montant(), null));
            b.related("LIGNE_RELEVE", pointe.getBankTransactionId().toString(), "Transaction bancaire du reglement " + piece);
        } else if (defait != null) {
            b.cause(new Cause(CauseCodes.BANK_RECONCILIATION_REVERSED, Nature.ANOMALY,
                    "Le rapprochement bancaire du reglement " + piece + " a ete annule" + (defait.getReversalReason() != null ? " (« " + defait.getReversalReason() + " »)" : "")
                            + " : il n'est plus lie a une transaction du releve.", Basis.PERSISTED, x.montant(),
                    List.of(new Evidence("BANK_RECONCILIATION", "LIGNE_RELEVE", defait.getBankTransactionId().toString(), "Rapprochement annule", x.montant(),
                            defait.getReversedAt() != null ? defait.getReversedAt().toLocalDate() : null))));
            b.related("LIGNE_RELEVE", defait.getBankTransactionId().toString(), "Transaction bancaire dont le rapprochement a ete annule");
        }
        BigDecimal reste = x.reste();
        if (reste.signum() <= 0) {
            return;
        }
        boolean aUneFacture = !facturesOuvertes.isEmpty();
        b.cause(new Cause(CauseCodes.UNALLOCATED_PAYMENT, Nature.ANOMALY,
                "Le reglement " + piece + " du " + x.l().getEcriture().getDateEcriture() + " (" + Fmt.m(x.montant()) + ") n'est affecte qu'a hauteur de "
                        + Fmt.m(x.alloue()) + " : " + Fmt.m(reste) + " ne correspondent a aucune facture.", Basis.PERSISTED, reste, preuves));
        if (aUneFacture) {
            List<Evidence> candidates = facturesOuvertes.stream().limit(MAX_LIGNES_DETAIL).map(f -> new Evidence("OPEN_INVOICE", "LIGNE_ECRITURE", f.id().toString(),
                    "Facture " + f.l().getEcriture().getNumeroPiece() + " : reste " + Fmt.m(f.reste()), f.reste(), f.l().getEcriture().getDateEcriture())).toList();
            b.cause(new Cause("OPEN_INVOICES_AVAILABLE", Nature.EXPLANATION, facturesOuvertes.size() + " facture(s) restent ouvertes pour ce tiers : le reglement peut etre reparti dessus.",
                    Basis.EXACT, null, candidates));
            facturesOuvertes.stream().limit(MAX_LIGNES_DETAIL).forEach(f -> b.related("ECRITURE", f.l().getEcriture().getId().toString(),
                    "Facture " + f.l().getEcriture().getNumeroPiece()));
            b.action("ALLOCATE_PAYMENT", "Repartir le reglement sur les factures ouvertes", "Banque > Reglements a repartir", "LIGNE_ECRITURE", x.id().toString());
        } else if (x.alloue().signum() > 0) {
            b.cause(new Cause(CauseCodes.OVERPAYMENT, Nature.EXPLANATION, "Le tiers n'a plus aucune facture ouverte : le reliquat de " + Fmt.m(reste)
                    + " est un trop-percu (avoir, acompte ou remboursement a decider).", Basis.PERSISTED, reste, List.of(preuveLigne("PAYMENT", x))));
            b.action("DECIDE_SURPLUS", "Decider du reliquat : avoir, acompte ou remboursement", "Comptabilite > Operations", "LIGNE_ECRITURE", x.id().toString());
        } else {
            b.cause(new Cause(CauseCodes.PAYMENT_WITHOUT_INVOICE, Nature.EXPLANATION, "Aucune facture n'existe pour ce tiers : le reglement est peut-etre un acompte, ou la facture "
                    + "n'est pas encore comptabilisee.", Basis.PERSISTED, reste, List.of(preuveLigne("PAYMENT", x))));
            b.action("POST_INVOICE", "Comptabiliser la facture correspondante (ou saisir un acompte)", "Comptabilite > Facturation", "LIGNE_ECRITURE", x.id().toString());
        }
    }

    private void facture(InvestigationResult.Builder b, Analyse a, Ligne x, List<PaymentAllocation> allocs, Map<UUID, Ligne> parId, LocalDate au) {
        String piece = x.l().getEcriture().getNumeroPiece();
        b.related("ECRITURE", x.l().getEcriture().getId().toString(), "Facture " + piece);
        long age = ChronoUnit.DAYS.between(x.l().getEcriture().getDateEcriture(), au);
        List<Evidence> preuves = new ArrayList<>();
        preuves.add(preuveLigne("INVOICE", x));
        for (PaymentAllocation al : allocs) {
            Ligne p = parId.get(al.getPaymentLineId());
            preuves.add(new Evidence("ALLOCATION", "ALLOCATION", al.getId().toString(), "Reglement " + (p != null ? p.l().getEcriture().getNumeroPiece() : "?"),
                    al.getAllocatedAmount(), al.getCreatedAt().toLocalDate()));
        }
        if (x.reste().signum() > 0) {
            if (x.alloue().signum() > 0) {
                b.cause(new Cause(CauseCodes.PARTIAL_PAYMENT, Nature.INFO, "La facture " + piece + " (" + Fmt.m(x.montant()) + ") est partiellement reglee : "
                        + Fmt.m(x.alloue()) + " affectes, reste " + Fmt.m(x.reste()) + " (" + age + " jours).", Basis.PERSISTED, x.reste(), preuves));
            } else {
                b.cause(new Cause(CauseCodes.UNPAID_INVOICE, Nature.INFO, "La facture " + piece + " (" + Fmt.m(x.montant()) + ") n'est pas reglee (" + age + " jours).",
                        Basis.PERSISTED, x.reste(), preuves));
            }
        }
        // Seules les factures d'ACHAT s'appuient sur une piece du fournisseur : une facture de vente emise par le cabinet n'en a pas besoin.
        if (x.l().getEcriture().getJournal().getType() == TypeJournal.ACHATS && !a.documents().containsKey(x.l().getEcriture().getId())) {
            DemandePiece d = a.demandes().get(x.l().getEcriture().getId());
            String suite = d == null ? "Aucune demande de piece n'a ete faite." : "Une demande de piece existe (statut " + d.getStatut() + ").";
            b.cause(new Cause(CauseCodes.MISSING_DOCUMENT, Nature.ANOMALY, "La facture " + piece + " n'a aucune piece justificative rattachee. " + suite, Basis.EXACT, x.montant(),
                    List.of(preuveLigne("INVOICE", x))));
            b.action(d == null ? "REQUEST_DOCUMENT" : "FOLLOW_UP_DOCUMENT_REQUEST", d == null ? "Demander la piece au client" : "Relancer la demande de piece", "Demandes de pieces",
                    "ECRITURE", x.l().getEcriture().getId().toString());
        }
    }

    // ------------------------------------------------------------------------------------------------ qualification du solde

    private void qualifierSolde(InvestigationResult.Builder b, Analyse a, boolean client, String role, BigDecimal nonAffecte, BigDecimal avoirsBruts) {
        if (a.solde().signum() >= 0) {
            b.cause(new Cause(CauseCodes.NORMAL_BALANCE, Nature.INFO, "Le solde est du cote normal pour un " + role + " (" + Fmt.m(a.solde()) + ").", Basis.PERSISTED,
                    a.solde(), List.of()));
            return;
        }
        BigDecimal ampleur = a.solde().abs();
        BigDecimal explique = ampleur.min(nonAffecte.add(avoirsBruts));
        String cote = client ? "crediteur" : "debiteur";
        List<Evidence> preuves = new ArrayList<>();
        if (nonAffecte.signum() > 0) {
            preuves.add(new Evidence("UNALLOCATED_TOTAL", "TIERS", a.tiers().getId().toString(), "Reglements non affectes", nonAffecte, null));
        }
        if (avoirsBruts.signum() > 0) {
            preuves.add(new Evidence("CREDIT_NOTES_TOTAL", "TIERS", a.tiers().getId().toString(), "Avoirs non imputes", avoirsBruts, null));
        }
        String code = client ? CauseCodes.CREDIT_BALANCE_EXPLAINED : CauseCodes.DEBIT_BALANCE_EXPLAINED;
        if (explique.compareTo(ampleur) == 0) {
            b.cause(new Cause(code, Nature.EXPLANATION, "Le solde " + cote + " de " + Fmt.m(ampleur) + " est economiquement justifie : il correspond a "
                    + Fmt.m(nonAffecte) + " de reglement(s) non affecte(s) et " + Fmt.m(avoirsBruts) + " d'avoir(s) non impute(s). Il reste a decider de son sort "
                    + "(affecter, avoir, acompte ou remboursement).", Basis.PERSISTED, ampleur, preuves));
        } else {
            BigDecimal residu = ampleur.subtract(explique);
            if (explique.signum() > 0) {
                b.cause(new Cause(code, Nature.EXPLANATION, "Le solde " + cote + " est explique a hauteur de " + Fmt.m(explique) + " par des reglements non affectes ou avoirs non imputes.",
                        Basis.PERSISTED, explique, preuves));
            }
            b.cause(new Cause(client ? CauseCodes.CREDIT_BALANCE_UNEXPLAINED : CauseCodes.DEBIT_BALANCE_UNEXPLAINED, Nature.ANOMALY,
                    "Un solde " + cote + " de " + Fmt.m(residu) + " reste inexplique par les reglements, avoirs et factures du compte : avance, erreur d'imputation ou piece manquante.",
                    Basis.PERSISTED, residu, preuves));
        }
    }

    // ------------------------------------------------------------------------------------------------ chronologie et factures ouvertes

    private void chronologie(InvestigationResult.Builder b, Analyse a, boolean client) {
        Map<UUID, Ligne> parId = a.lignes().stream().collect(Collectors.toMap(Ligne::id, x -> x));
        // Un compte de milliers de lignes ne renvoie pas milliers d'evenements : on garde les plus recents et on le dit.
        List<Ligne> recentes = a.lignes().size() <= MAX_EVENEMENTS ? a.lignes()
                : a.lignes().subList(a.lignes().size() - MAX_EVENEMENTS, a.lignes().size());
        if (recentes.size() < a.lignes().size()) {
            b.detail("chronologieTronquee", a.lignes().size() - recentes.size());
        }
        for (Ligne x : recentes) {
            String type = x.ouverture() ? "OUVERTURE" : x.genre().name();
            String libelle = switch (x.genre()) {
                case FACTURE -> "Facture " + x.l().getEcriture().getNumeroPiece();
                case AVOIR -> "Avoir " + x.l().getEcriture().getNumeroPiece();
                case REGLEMENT -> "Reglement " + x.l().getEcriture().getNumeroPiece();
                default -> "Ecriture " + x.l().getEcriture().getNumeroPiece();
            };
            b.event(new TimelineEvent(x.l().getEcriture().getDateEcriture(), type, libelle, sens(client, x.l()), "ECRITURE", x.l().getEcriture().getId().toString()));
        }
        List<PaymentAllocation> recentesAllocations = a.allocations().size() <= MAX_EVENEMENTS ? a.allocations()
                : a.allocations().subList(a.allocations().size() - MAX_EVENEMENTS, a.allocations().size());
        for (PaymentAllocation al : recentesAllocations) {
            Ligne p = parId.get(al.getPaymentLineId());
            Ligne f = parId.get(al.getInvoiceLineId());
            b.event(new TimelineEvent(al.getCreatedAt().toLocalDate(), "ALLOCATION", "Affectation de " + Fmt.m(al.getAllocatedAmount()) + " : reglement "
                    + (p != null ? p.l().getEcriture().getNumeroPiece() : "?") + " vers facture " + (f != null ? f.l().getEcriture().getNumeroPiece() : "?"),
                    al.getAllocatedAmount(), "ALLOCATION", al.getId().toString()));
        }
    }

    /** Factures ouvertes avec montant, affecte, reste, anciennete (memes tranches que la balance agee), piece, reglements et rapprochement. */
    private void facturesOuvertes(InvestigationResult.Builder b, Analyse a, boolean client) {
        Map<UUID, Ligne> parId = a.lignes().stream().collect(Collectors.toMap(Ligne::id, x -> x));
        BigDecimal[] tranches = {BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO};
        List<Map<String, Object>> liste = new ArrayList<>();
        for (Ligne f : a.lignes()) {
            if (f.genre() != Genre.FACTURE || f.reste().signum() <= 0) {
                continue;
            }
            long age = Math.max(0, ChronoUnit.DAYS.between(f.l().getEcriture().getDateEcriture(), a.au()));
            tranches[age <= 30 ? 0 : age <= 60 ? 1 : age <= 90 ? 2 : 3] = tranches[age <= 30 ? 0 : age <= 60 ? 1 : age <= 90 ? 2 : 3].add(f.reste());
            if (liste.size() >= MAX_LIGNES_DETAIL) {
                continue;
            }
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("ecritureId", f.l().getEcriture().getId().toString());
            m.put("piece", f.l().getEcriture().getNumeroPiece());
            m.put("date", f.l().getEcriture().getDateEcriture().toString());
            m.put("montant", f.montant());
            m.put("affecte", f.alloue());
            m.put("reste", f.reste());
            m.put("ageJours", age);
            Document d = a.documents().get(f.l().getEcriture().getId());
            m.put("piecejustificative", d != null ? d.getNomFichierOriginal() : null);
            List<Map<String, Object>> paiements = new ArrayList<>();
            for (PaymentAllocation al : a.allocations()) {
                if (al.getInvoiceLineId().equals(f.id())) {
                    Ligne p = parId.get(al.getPaymentLineId());
                    Map<String, Object> pm = new LinkedHashMap<>();
                    pm.put("piece", p != null ? p.l().getEcriture().getNumeroPiece() : null);
                    pm.put("montant", al.getAllocatedAmount());
                    pm.put("rapprocheEnBanque", p != null && a.rapprochements().containsKey(p.id()));
                    paiements.add(pm);
                }
            }
            m.put("paiements", paiements);
            liste.add(m);
        }
        b.detail("facturesOuvertes", liste);
        Map<String, Object> agee = new LinkedHashMap<>();
        agee.put("de0a30", tranches[0]);
        agee.put("de31a60", tranches[1]);
        agee.put("de61a90", tranches[2]);
        agee.put("plusDe90", tranches[3]);
        b.detail("balanceAgeeFacturesOuvertes", agee);
    }

    // ------------------------------------------------------------------------------------------------ ligne seule (reglement ou facture)

    /** Investigation centree sur UNE ligne de tiers (reglement ou facture) : ses affectations, ses candidats, son rapprochement. */
    InvestigationResult.Builder investiguerLigne(UUID entrepriseId, UUID ligneId) {
        LigneEcriture l = ligneEcritureRepository.chargerAvecEcriture(List.of(ligneId)).stream().findFirst()
                .filter(x -> x.getEcriture().getEntreprise().getId().equals(entrepriseId))
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Ligne d'ecriture introuvable"));
        // Le tiers d'une ligne : celui qu'elle porte, sinon celui dont c'est le compte auxiliaire.
        Tiers proprietaire = l.getTiers() != null ? l.getTiers()
                : tiersRepository.findFirstByEntrepriseIdAndCompteComptableId(entrepriseId, l.getCompte().getId()).orElse(null);
        if (proprietaire == null) {
            throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY, "Cette ligne n'est pas rattachee a un tiers");
        }
        Analyse a = analyser(entrepriseId, proprietaire.getId(), null);
        Ligne cible = a.lignes().stream().filter(x -> x.id().equals(ligneId)).findFirst()
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Ligne d'ecriture introuvable"));
        boolean client = a.tiers().getType() == TypeTiers.CLIENT;
        String nature = cible.genre() == Genre.REGLEMENT ? "Reglement" : cible.genre() == Genre.FACTURE ? "Facture" : cible.genre() == Genre.AVOIR ? "Avoir" : "Ecriture";
        InvestigationResult.Builder b = new InvestigationResult.Builder("LIGNE_ECRITURE", ligneId.toString(), nature + " " + l.getEcriture().getNumeroPiece());
        b.related("TIERS", a.tiers().getId().toString(), a.tiers().getNom());
        b.related("ECRITURE", l.getEcriture().getId().toString(), nature + " " + l.getEcriture().getNumeroPiece());
        b.comparison("Montant de la piece", cible.montant(), "Montant affecte", cible.alloue());
        causesLignes(b, a, client, ligneId);
        Map<UUID, Ligne> parId = a.lignes().stream().collect(Collectors.toMap(Ligne::id, x -> x));
        b.event(new TimelineEvent(l.getEcriture().getDateEcriture(), cible.genre().name(), nature + " " + l.getEcriture().getNumeroPiece(), sens(client, l), "ECRITURE",
                l.getEcriture().getId().toString()));
        for (PaymentAllocation al : a.allocations()) {
            if (al.getPaymentLineId().equals(ligneId) || al.getInvoiceLineId().equals(ligneId)) {
                Ligne autre = parId.get(al.getPaymentLineId().equals(ligneId) ? al.getInvoiceLineId() : al.getPaymentLineId());
                b.event(new TimelineEvent(al.getCreatedAt().toLocalDate(), "ALLOCATION", "Affectation de " + Fmt.m(al.getAllocatedAmount()) + " avec la piece "
                        + (autre != null ? autre.l().getEcriture().getNumeroPiece() : "?"), al.getAllocatedAmount(), "ALLOCATION", al.getId().toString()));
            }
        }
        BankReconciliation pointe = a.rapprochements().get(ligneId);
        if (pointe != null) {
            b.event(new TimelineEvent(pointe.getConfirmedAt() != null ? pointe.getConfirmedAt().toLocalDate() : l.getEcriture().getDateEcriture(), "BANK_RECONCILIATION",
                    "Rapprochement bancaire confirme", cible.montant(), "LIGNE_RELEVE", pointe.getBankTransactionId().toString()));
        }
        b.summary(b.aUneAnomalie() ? nature + " " + l.getEcriture().getNumeroPiece() + " de " + a.tiers().getNom() + " : un point demande une decision."
                : nature + " " + l.getEcriture().getNumeroPiece() + " de " + a.tiers().getNom() + " : situation normale.");
        return b;
    }

    static Evidence preuveLigne(String kind, Ligne x) {
        String libelle = switch (kind) {
            case "PAYMENT" -> "Reglement " + x.l().getEcriture().getNumeroPiece();
            case "CREDIT_NOTE" -> "Avoir " + x.l().getEcriture().getNumeroPiece();
            default -> "Facture " + x.l().getEcriture().getNumeroPiece();
        };
        return new Evidence(kind, "LIGNE_ECRITURE", x.id().toString(), libelle, x.montant(), x.l().getEcriture().getDateEcriture());
    }

    static Collection<UUID> idsEcritures(Collection<Ligne> lignes) {
        return lignes.stream().map(x -> x.l().getEcriture().getId()).collect(Collectors.toSet());
    }
}
