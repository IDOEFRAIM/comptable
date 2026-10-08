package com.example.comptable.banque;

import com.example.comptable.audit.AuditTrailService;
import com.example.comptable.banque.InternalTransferMatcher.Appariement;
import com.example.comptable.banque.InternalTransferMatcher.Niveau;
import com.example.comptable.comptabilite.domain.LigneEcriture;
import com.example.comptable.comptabilite.domain.LigneReleve;
import com.example.comptable.comptabilite.repository.LigneEcritureRepository;
import com.example.comptable.comptabilite.repository.LigneReleveRepository;
import com.example.comptable.entreprise.domain.Entreprise;
import com.example.comptable.entreprise.service.EntrepriseAccessService;
import com.example.comptable.security.UtilisateurPrincipal;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * Virements internes entre deux comptes bancaires de l'entreprise (Banque A -&gt; Banque B). Un virement interne n'est ni une charge, ni un produit, ni
 * le reglement d'un fournisseur, ni l'encaissement d'un client : ses deux jambes sont liees entre elles et retirees du rapprochement avec les tiers.
 *
 * <p>Ce module ne CREE JAMAIS d'ecriture comptable : si une ecriture de transfert existe deja (une seule ecriture creditant le compte A et debitant le
 * compte B du meme montant), les deux jambes y sont rapprochees ; sinon le virement reste a enregistrer par le comptable (constat dedie).</p>
 */
@Service
public class InternalTransferService {

    public record Bilan(int confirmes, int proposes, int ambigus) {
    }

    private static final int FENETRE_ECRITURE_JOURS = 5;

    private final BankInternalTransferRepository repository;
    private final LigneReleveRepository ligneReleveRepository;
    private final LigneEcritureRepository ligneEcritureRepository;
    private final BankReconciliationService reconciliationService;
    private final EntrepriseAccessService entrepriseAccessService;
    private final AuditTrailService auditTrailService;

    public InternalTransferService(BankInternalTransferRepository repository, LigneReleveRepository ligneReleveRepository,
                                   LigneEcritureRepository ligneEcritureRepository, BankReconciliationService reconciliationService,
                                   EntrepriseAccessService entrepriseAccessService, AuditTrailService auditTrailService) {
        this.repository = repository;
        this.ligneReleveRepository = ligneReleveRepository;
        this.ligneEcritureRepository = ligneEcritureRepository;
        this.reconciliationService = reconciliationService;
        this.entrepriseAccessService = entrepriseAccessService;
        this.auditTrailService = auditTrailService;
    }

    /** Parmi ces transactions, celles qui sont deja la jambe d'un virement interne (propose ou confirme) : elles n'entrent plus dans le rapprochement avec les tiers. */
    @Transactional(readOnly = true)
    public Set<UUID> jambes(java.util.Collection<UUID> transactionIds) {
        Set<UUID> ids = new HashSet<>(transactionIds);
        Set<UUID> jambes = new HashSet<>();
        if (ids.isEmpty()) {
            return jambes;
        }
        for (BankInternalTransfer t : repository.findVivantsDont(ids)) {
            if (ids.contains(t.getDebitTransactionId())) {
                jambes.add(t.getDebitTransactionId());
            }
            if (ids.contains(t.getCreditTransactionId())) {
                jambes.add(t.getCreditTransactionId());
            }
        }
        return jambes;
    }

    // ------------------------------------------------------------------------------------------------ decisions du systeme

    /** Detecte les virements internes parmi les transactions NON rapprochees : certains lies, probables proposes, ambigus laisses au comptable. */
    @Transactional
    public Bilan detecter(Entreprise entreprise) {
        List<Appariement> appariements = appariementsCourants(entreprise.getId());
        int confirmes = 0;
        int proposes = 0;
        int ambigus = 0;
        for (Appariement a : appariements) {
            if (a.niveau() == Niveau.AMBIGUOUS) {
                ambigus++;
                continue;
            }
            boolean certain = a.niveau() == Niveau.EXACT;
            BankInternalTransfer t = nouveau(entreprise.getId(), a, certain ? StatutReconciliation.CONFIRMED : StatutReconciliation.PROPOSED);
            t.setSource(certain ? SourceReconciliation.SYSTEM_EXACT : SourceReconciliation.SYSTEM_PROPOSAL);
            t.setDecision(certain ? DecisionReconciliation.EXACT : DecisionReconciliation.PROBABLE);
            if (certain) {
                t.setConfirmedAt(OffsetDateTime.now());
            }
            t = repository.save(t);
            if (certain) {
                reconciliationService.supprimerPropositions(List.of(a.sortie().getId(), a.entree().getId()));
                rattacherEcriture(entreprise.getId(), t, a.sortie(), a.entree());
                confirmes++;
            } else {
                proposes++;
            }
            auditer(entreprise, null, certain ? "BANK_INTERNAL_TRANSFER_MATCHED_AUTOMATICALLY" : "BANK_INTERNAL_TRANSFER_PROPOSED", t,
                    Map.of("score", a.score()));
        }
        return new Bilan(confirmes, proposes, ambigus);
    }

    /** Cas ambigus d'une entreprise (lecture seule) : plusieurs jambes possibles, le systeme ne choisit jamais. */
    @Transactional(readOnly = true)
    public List<Appariement> ambigus(UUID entrepriseId) {
        return appariementsCourants(entrepriseId).stream().filter(a -> a.niveau() == Niveau.AMBIGUOUS).toList();
    }

    // ------------------------------------------------------------------------------------------------ decisions humaines

    @Transactional
    public BankInternalTransfer confirmer(UUID entrepriseId, UUID transfertId, UtilisateurPrincipal principal) {
        Entreprise entreprise = entrepriseAccessService.verifierAcces(entrepriseId, principal);
        BankInternalTransfer t = charger(entrepriseId, transfertId);
        if (t.getStatus() == StatutReconciliation.CONFIRMED) {
            return t;
        }
        if (t.getStatus() != StatutReconciliation.PROPOSED) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Ce virement n'est plus une proposition (" + t.getStatus() + ")");
        }
        List<LigneReleve> jambes = jambes(t);
        if (jambes.stream().anyMatch(l -> l.getLigneEcritureId() != null)) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Une des deux transactions est deja rapprochee : ce virement interne n'est plus possible");
        }
        t.setStatus(StatutReconciliation.CONFIRMED);
        t.setConfirmedBy(principal.getId());
        t.setConfirmedAt(OffsetDateTime.now());
        t = repository.save(t);
        reconciliationService.supprimerPropositions(List.of(t.getDebitTransactionId(), t.getCreditTransactionId()));
        rattacherEcriture(entrepriseId, t, jambe(jambes, t.getDebitTransactionId()), jambe(jambes, t.getCreditTransactionId()));
        auditer(entreprise, principal, "BANK_INTERNAL_TRANSFER_CONFIRMED", t, Map.of());
        return t;
    }

    @Transactional
    public BankInternalTransfer rejeter(UUID entrepriseId, UUID transfertId, String motif, UtilisateurPrincipal principal) {
        Entreprise entreprise = entrepriseAccessService.verifierAcces(entrepriseId, principal);
        BankInternalTransfer t = charger(entrepriseId, transfertId);
        if (t.getStatus() == StatutReconciliation.REJECTED) {
            return t;
        }
        if (t.getStatus() != StatutReconciliation.PROPOSED) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Seule une proposition peut etre rejetee (" + t.getStatus() + ")");
        }
        t.setStatus(StatutReconciliation.REJECTED);
        t.setRejectedBy(principal.getId());
        t.setRejectedAt(OffsetDateTime.now());
        t = repository.save(t);
        auditer(entreprise, principal, "BANK_INTERNAL_TRANSFER_REJECTED", t, Map.of("motif", motif == null ? "" : motif));
        return t;
    }

    /** Defait un virement interne confirme (motif obligatoire) ; les rapprochements a l'ecriture de transfert qu'il avait permis sont defaits avec lui. */
    @Transactional
    public BankInternalTransfer annuler(UUID entrepriseId, UUID transfertId, String motif, UtilisateurPrincipal principal) {
        Entreprise entreprise = entrepriseAccessService.verifierAcces(entrepriseId, principal);
        if (motif == null || motif.isBlank()) {
            throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY, "Un motif est obligatoire pour annuler un virement interne");
        }
        BankInternalTransfer t = charger(entrepriseId, transfertId);
        if (t.getStatus() == StatutReconciliation.REVERSED) {
            return t;
        }
        if (t.getStatus() != StatutReconciliation.CONFIRMED) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Seul un virement confirme peut etre annule (" + t.getStatus() + ")");
        }
        boolean avaitUneEcriture = t.getEcritureId() != null;
        t.setStatus(StatutReconciliation.REVERSED);
        t.setReversedBy(principal.getId());
        t.setReversedAt(OffsetDateTime.now());
        t.setReversalReason(motif.length() > 300 ? motif.substring(0, 300) : motif);
        t = repository.save(t);
        if (avaitUneEcriture) {
            reconciliationService.annuler(entrepriseId, t.getDebitTransactionId(), "Virement interne annule : " + motif, principal);
            reconciliationService.annuler(entrepriseId, t.getCreditTransactionId(), "Virement interne annule : " + motif, principal);
        }
        auditer(entreprise, principal, "BANK_INTERNAL_TRANSFER_REVERSED", t, Map.of("motif", motif));
        return t;
    }

    // ------------------------------------------------------------------------------------------------ interne

    private List<Appariement> appariementsCourants(UUID entrepriseId) {
        List<LigneReleve> libres = ligneReleveRepository.nonRapprochees(entrepriseId);
        if (libres.size() < 2) {
            return List.of();
        }
        Set<UUID> engages = new HashSet<>();
        for (BankInternalTransfer t : repository.findVivantsDont(libres.stream().map(LigneReleve::getId).toList())) {
            engages.add(t.getDebitTransactionId());
            engages.add(t.getCreditTransactionId());
        }
        Set<String> exclus = repository.findExclus(entrepriseId).stream()
                .map(t -> t.getDebitTransactionId() + "|" + t.getCreditTransactionId()).collect(Collectors.toSet());
        return InternalTransferMatcher.apparier(libres.stream().filter(l -> !engages.contains(l.getId())).toList(), exclus);
    }

    private BankInternalTransfer nouveau(UUID entrepriseId, Appariement a, StatutReconciliation statut) {
        BankInternalTransfer t = new BankInternalTransfer();
        t.setEntrepriseId(entrepriseId);
        t.setDebitTransactionId(a.sortie().getId());
        t.setCreditTransactionId(a.entree().getId());
        t.setAmount(a.sortie().getMontant().abs());
        t.setConfidence(BigDecimal.valueOf(a.score()));
        t.setStatus(statut);
        t.setExplanation(Map.of("explication", a.explication()));
        return t;
    }

    /**
     * Si UNE ecriture validee credite le compte de la sortie et debite le compte de l'entree du meme montant (le transfert est deja comptabilise),
     * rapproche chaque jambe a sa ligne. Aucune ecriture n'est jamais creee ; plusieurs ecritures possibles : rien n'est choisi.
     */
    private void rattacherEcriture(UUID entrepriseId, BankInternalTransfer t, LigneReleve sortie, LigneReleve entree) {
        BigDecimal montant = t.getAmount();
        java.time.LocalDate debut = sortie.getDateOperation().minusDays(FENETRE_ECRITURE_JOURS);
        java.time.LocalDate fin = sortie.getDateOperation().plusDays(FENETRE_ECRITURE_JOURS);
        Set<UUID> prisesA = new HashSet<>(ligneReleveRepository.rechercherLigneEcritureIdsPointees(sortie.getCompteId()));
        Set<UUID> prisesB = new HashSet<>(ligneReleveRepository.rechercherLigneEcritureIdsPointees(entree.getCompteId()));
        List<LigneEcriture> creditsA = ligneEcritureRepository.rechercherMouvementsValides(sortie.getCompteId(), debut, fin).stream()
                .filter(l -> l.getCredit().compareTo(montant) == 0 && !prisesA.contains(l.getId())).toList();
        List<LigneEcriture> debitsB = ligneEcritureRepository.rechercherMouvementsValides(entree.getCompteId(), debut, fin).stream()
                .filter(l -> l.getDebit().compareTo(montant) == 0 && !prisesB.contains(l.getId())).toList();
        List<LigneEcriture[]> paires = new java.util.ArrayList<>();
        for (LigneEcriture a : creditsA) {
            for (LigneEcriture b : debitsB) {
                if (a.getEcriture().getId().equals(b.getEcriture().getId())) {
                    paires.add(new LigneEcriture[] {a, b});
                }
            }
        }
        if (paires.size() != 1) {
            return;
        }
        LigneEcriture[] paire = paires.get(0);
        t.setEcritureId(paire[0].getEcriture().getId());
        repository.save(t);
        String explication = "Virement interne : ecriture de transfert " + paire[0].getEcriture().getNumeroPiece();
        reconciliationService.confirmerParLeSysteme(sortie, paire[0].getId(), SourceReconciliation.SYSTEM_EXACT, DecisionReconciliation.EXACT, BigDecimal.valueOf(90), explication);
        reconciliationService.confirmerParLeSysteme(entree, paire[1].getId(), SourceReconciliation.SYSTEM_EXACT, DecisionReconciliation.EXACT, BigDecimal.valueOf(90), explication);
    }

    private List<LigneReleve> jambes(BankInternalTransfer t) {
        return ligneReleveRepository.findAllById(List.of(t.getDebitTransactionId(), t.getCreditTransactionId()));
    }

    private static LigneReleve jambe(List<LigneReleve> jambes, UUID id) {
        return jambes.stream().filter(l -> l.getId().equals(id)).findFirst().orElseThrow();
    }

    private BankInternalTransfer charger(UUID entrepriseId, UUID id) {
        return repository.findByIdAndEntrepriseId(id, entrepriseId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Virement interne introuvable"));
    }

    private void auditer(Entreprise entreprise, UtilisateurPrincipal principal, String action, BankInternalTransfer t, Map<String, Object> extra) {
        Map<String, Object> details = new LinkedHashMap<>(extra);
        details.put("sortieId", t.getDebitTransactionId().toString());
        details.put("entreeId", t.getCreditTransactionId().toString());
        details.put("montant", t.getAmount().toPlainString());
        details.put("decision", String.valueOf(t.getDecision()));
        UUID tenantId = entreprise.getTenant() != null ? entreprise.getTenant().getId() : null;
        if (principal != null) {
            auditTrailService.enregistrerActionHumaine(tenantId, principal.getId(), action, "BANK_INTERNAL_TRANSFER", t.getId().toString(), details);
        } else {
            auditTrailService.enregistrerEvenementSysteme(tenantId, action, "BANK_INTERNAL_TRANSFER", t.getId().toString(), details);
        }
    }
}
