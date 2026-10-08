package com.example.comptable.banque;

import com.example.comptable.audit.AuditTrailService;
import com.example.comptable.comptabilite.domain.LigneEcriture;
import com.example.comptable.comptabilite.domain.LigneReleve;
import com.example.comptable.comptabilite.domain.StatutEcriture;
import com.example.comptable.comptabilite.repository.LigneEcritureRepository;
import com.example.comptable.comptabilite.repository.LigneReleveRepository;
import com.example.comptable.entreprise.service.EntrepriseAccessService;
import com.example.comptable.security.UtilisateurPrincipal;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * SEULE porte d'ecriture du rapprochement bancaire : tout pointage d'une transaction passe ici, qu'il vienne du systeme (cas certain), de
 * l'IA validee par un humain ou d'un utilisateur. Invariant : une reconciliation {@code CONFIRMED} &lt;=&gt; {@code LigneReleve.ligneEcritureId}
 * renseigne ; les deux sont modifies dans la MEME transaction, sous verrou de la transaction bancaire.
 *
 * <p>Rien n'est supprime : un rapprochement defait passe {@code REVERSED}, un rejet est memorise (la meme proposition n'est jamais refaite).
 * Une transaction bancaire n'est jamais liee directement a une facture : la suite (reglement, allocation, factures) reste portee par l'ecriture.</p>
 */
@Service
public class BankReconciliationService {

    private final BankReconciliationRepository repository;
    private final LigneReleveRepository ligneReleveRepository;
    private final LigneEcritureRepository ligneEcritureRepository;
    private final EntrepriseAccessService entrepriseAccessService;
    private final AuditTrailService auditTrailService;
    private final BankInternalTransferRepository transferRepository;

    public BankReconciliationService(BankReconciliationRepository repository, LigneReleveRepository ligneReleveRepository,
                                     LigneEcritureRepository ligneEcritureRepository, EntrepriseAccessService entrepriseAccessService,
                                     AuditTrailService auditTrailService, BankInternalTransferRepository transferRepository) {
        this.repository = repository;
        this.ligneReleveRepository = ligneReleveRepository;
        this.ligneEcritureRepository = ligneEcritureRepository;
        this.entrepriseAccessService = entrepriseAccessService;
        this.auditTrailService = auditTrailService;
        this.transferRepository = transferRepository;
    }

    // ------------------------------------------------------------------------------------------------ decisions du systeme

    /** Cas CERTAIN (ou filet IA deja garde-fou) : rapprochement confirme par le systeme, audite. Idempotent. */
    @Transactional
    public BankReconciliation confirmerParLeSysteme(LigneReleve transaction, UUID ligneEcritureId, SourceReconciliation source,
                                                    DecisionReconciliation decision, BigDecimal confiance, String explication) {
        LigneReleve tx = verrouiller(transaction.getEntrepriseId(), transaction.getId());
        BankReconciliation existante = repository.findConfirmeeDe(tx.getId()).orElse(null);
        if (existante != null) {
            return existante;
        }
        if (repository.findConfirmeeSurCible(ligneEcritureId).isPresent()) {
            return null; // l'ecriture est deja prise par une autre transaction : le systeme ne force rien
        }
        BankReconciliation r = vivantOuNouvelle(tx, ligneEcritureId);
        if (r.getStatus() == StatutReconciliation.REJECTED || annuleParUnHumain(tx.getId(), ligneEcritureId)) {
            return null; // rejete ou defait par un humain : jamais reconfirme automatiquement
        }
        r.setDecision(decision);
        r.setConfidence(confiance);
        r.setSource(source);
        r.setExplanation(explication != null ? Map.of("explication", explication) : null);
        confirmer(tx, r, null);
        auditer(tx, null, "BANK_TRANSACTION_MATCHED_AUTOMATICALLY", r, Map.of("source", source.name()));
        return r;
    }

    /** Proposition (cas probable) : enregistree une seule fois, jamais si le couple a ete rejete ou si l'ecriture est deja prise. */
    @Transactional
    public BankReconciliation proposer(LigneReleve transaction, UUID ligneEcritureId, SourceReconciliation source, BigDecimal confiance,
                                       String explication) {
        if (transaction.getLigneEcritureId() != null || repository.findConfirmeeSurCible(ligneEcritureId).isPresent()
                || annuleParUnHumain(transaction.getId(), ligneEcritureId)) {
            return null;
        }
        Optional<BankReconciliation> vivante = repository.findVivant(transaction.getId(), ligneEcritureId);
        if (vivante.isPresent()) {
            BankReconciliation r = vivante.get();
            return r.getStatus() == StatutReconciliation.PROPOSED ? r : null;
        }
        BankReconciliation r = nouvelle(transaction, ligneEcritureId);
        r.setDecision(DecisionReconciliation.PROBABLE);
        r.setConfidence(confiance);
        r.setSource(source);
        r.setStatus(StatutReconciliation.PROPOSED);
        r.setExplanation(explication != null ? Map.of("explication", explication) : null);
        r = repository.save(r);
        auditer(transaction, null, "BANK_MATCH_PROPOSED", r, Map.of("confiance", String.valueOf(confiance)));
        return r;
    }

    /** Une decision du systeme a appliquer dans un lot : confirmer (cas certain) ou proposer (cas probable). */
    public record DemandeAutomatique(LigneReleve transaction, UUID ligneEcritureId, SourceReconciliation source, DecisionReconciliation decision,
                                     BigDecimal confiance, String explication, boolean confirmer) {
    }

    public record BilanLot(int confirmees, int proposees, int ignorees) {
    }

    /**
     * Applique en UNE passe des centaines de decisions du systeme : les etats existants sont lus en quelques requetes, puis tout est decide en
     * memoire et ecrit d'un bloc (aucune requete dans la boucle : sinon chaque ligne declencherait un controle de toute la session). Memes regles
     * que {@link #confirmerParLeSysteme} et {@link #proposer} : jamais de reprise d'une paire rejetee ou defaite par un humain, jamais une
     * ecriture deja confirmee ailleurs, jamais deux transactions sur la meme ecriture dans le meme lot.
     */
    @Transactional
    public BilanLot appliquerLot(UUID entrepriseId, List<DemandeAutomatique> demandes) {
        if (demandes.isEmpty()) {
            return new BilanLot(0, 0, 0);
        }
        List<UUID> txIds = demandes.stream().map(d -> d.transaction().getId()).toList();
        List<UUID> cibles = demandes.stream().map(DemandeAutomatique::ligneEcritureId).distinct().toList();
        java.util.Set<UUID> txConfirmees = new java.util.HashSet<>();
        repository.findConfirmeesDe(txIds).forEach(r -> txConfirmees.add(r.getBankTransactionId()));
        java.util.Set<UUID> ciblesPrises = new java.util.HashSet<>();
        repository.findConfirmeesSurCibles(cibles).forEach(r -> ciblesPrises.add(r.getTargetId()));
        java.util.Set<String> exclues = new java.util.HashSet<>();
        repository.findRejetesDe(txIds).forEach(r -> exclues.add(r.getBankTransactionId() + "|" + r.getTargetId()));
        Map<String, BankReconciliation> vivantes = new java.util.HashMap<>();
        repository.findVivantsDe(txIds).forEach(r -> vivantes.put(r.getBankTransactionId() + "|" + r.getTargetId(), r));
        UUID tenantId = ligneEcritureRepository.findById(cibles.get(0))
                .map(l -> l.getEcriture().getEntreprise().getTenant() != null ? l.getEcriture().getEntreprise().getTenant().getId() : null).orElse(null);

        int confirmees = 0;
        int proposees = 0;
        List<BankReconciliation> aEnregistrer = new java.util.ArrayList<>();
        List<Object[]> audits = new java.util.ArrayList<>();
        for (DemandeAutomatique d : demandes) {
            LigneReleve tx = d.transaction();
            String paire = tx.getId() + "|" + d.ligneEcritureId();
            if (txConfirmees.contains(tx.getId()) || tx.getLigneEcritureId() != null || ciblesPrises.contains(d.ligneEcritureId()) || exclues.contains(paire)) {
                continue;
            }
            BankReconciliation existante = vivantes.get(paire);
            if (existante != null && existante.getStatus() == StatutReconciliation.REJECTED) {
                continue;
            }
            if (!d.confirmer()) {
                if (existante != null) {
                    continue; // deja proposee
                }
                BankReconciliation r = nouvelle(tx, d.ligneEcritureId());
                r.setDecision(DecisionReconciliation.PROBABLE);
                r.setConfidence(d.confiance());
                r.setSource(d.source());
                r.setStatus(StatutReconciliation.PROPOSED);
                r.setExplanation(d.explication() != null ? Map.of("explication", d.explication()) : null);
                aEnregistrer.add(r);
                audits.add(new Object[] {"BANK_MATCH_PROPOSED", r, Map.of("confiance", String.valueOf(d.confiance()))});
                proposees++;
                continue;
            }
            BankReconciliation r = existante != null ? existante : nouvelle(tx, d.ligneEcritureId());
            r.setDecision(d.decision());
            r.setConfidence(d.confiance());
            r.setSource(d.source());
            r.setExplanation(d.explication() != null ? Map.of("explication", d.explication()) : null);
            r.setStatus(StatutReconciliation.CONFIRMED);
            r.setConfirmedAt(OffsetDateTime.now());
            tx.setLigneEcritureId(d.ligneEcritureId());
            ciblesPrises.add(d.ligneEcritureId());
            txConfirmees.add(tx.getId());
            aEnregistrer.add(r);
            audits.add(new Object[] {"BANK_TRANSACTION_MATCHED_AUTOMATICALLY", r, Map.of("source", d.source().name())});
            confirmees++;
        }
        repository.saveAll(aEnregistrer);
        ligneReleveRepository.saveAll(demandes.stream().map(DemandeAutomatique::transaction).filter(t -> t.getLigneEcritureId() != null).toList());
        for (Object[] a : audits) {
            BankReconciliation r = (BankReconciliation) a[1];
            @SuppressWarnings("unchecked") Map<String, Object> extra = (Map<String, Object>) a[2];
            Map<String, Object> details = new LinkedHashMap<>(extra);
            details.put("transactionId", r.getBankTransactionId().toString());
            details.put("cibleId", r.getTargetId().toString());
            details.put("decision", String.valueOf(r.getDecision()));
            if (r.getConfidence() != null) {
                details.put("confiance", r.getConfidence().toPlainString());
            }
            auditTrailService.enregistrerEvenementSysteme(tenantId, (String) a[0], "BANK_RECONCILIATION", r.getId() != null ? r.getId().toString() : r.getBankTransactionId().toString(), details);
        }
        return new BilanLot(confirmees, proposees, demandes.size() - confirmees - proposees);
    }

    // ------------------------------------------------------------------------------------------------ decisions humaines

    /** Confirme une proposition. Deja confirmee : renvoyee telle quelle (double confirmation idempotente). */
    @Transactional
    public BankReconciliation confirmer(UUID entrepriseId, UUID reconciliationId, UtilisateurPrincipal principal) {
        entrepriseAccessService.verifierAcces(entrepriseId, principal);
        BankReconciliation r = charger(entrepriseId, reconciliationId);
        if (r.getStatus() == StatutReconciliation.CONFIRMED) {
            return r;
        }
        if (r.getStatus() != StatutReconciliation.PROPOSED) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Ce rapprochement n'est plus une proposition (" + r.getStatus() + ")");
        }
        LigneReleve tx = verrouiller(entrepriseId, r.getBankTransactionId());
        controlerPoseePossible(tx, r.getTargetId());
        confirmer(tx, r, principal.getId());
        auditer(tx, principal, "BANK_MATCH_CONFIRMED", r, Map.of("origine", "PROPOSITION"));
        return r;
    }

    /** Choix du comptable parmi des candidats (ou pointage direct). Deja confirme sur la meme cible : idempotent. */
    @Transactional
    public BankReconciliation pointer(UUID entrepriseId, UUID transactionId, UUID ligneEcritureId, SourceReconciliation source,
                                      UtilisateurPrincipal principal) {
        entrepriseAccessService.verifierAcces(entrepriseId, principal);
        LigneReleve tx = verrouiller(entrepriseId, transactionId);
        BankReconciliation confirmee = repository.findConfirmeeDe(tx.getId()).orElse(null);
        if (confirmee != null) {
            if (confirmee.getTargetId().equals(ligneEcritureId)) {
                return confirmee;
            }
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Cette transaction est deja rapprochee : annulez le rapprochement avant d'en choisir un autre");
        }
        controlerPoseePossible(tx, ligneEcritureId);
        Optional<BankReconciliation> vivante = repository.findVivant(tx.getId(), ligneEcritureId);
        BankReconciliation r = vivante.orElseGet(() -> nouvelle(tx, ligneEcritureId));
        // Une proposition qui existait deja garde son origine ; un choix sans proposition est un choix humain.
        boolean etaitProposee = vivante.isPresent() && r.getStatus() == StatutReconciliation.PROPOSED;
        if (!etaitProposee) {
            r.setDecision(DecisionReconciliation.MANUAL);
            r.setSource(source);
        }
        confirmer(tx, r, principal.getId());
        auditer(tx, principal, "BANK_MATCH_CONFIRMED", r, Map.of("origine", etaitProposee ? "PROPOSITION" : "CHOIX_MANUEL"));
        return r;
    }

    /** Rejette une proposition : memorisee, elle ne reviendra pas. */
    @Transactional
    public BankReconciliation rejeter(UUID entrepriseId, UUID reconciliationId, String motif, UtilisateurPrincipal principal) {
        entrepriseAccessService.verifierAcces(entrepriseId, principal);
        BankReconciliation r = charger(entrepriseId, reconciliationId);
        if (r.getStatus() == StatutReconciliation.REJECTED) {
            return r;
        }
        if (r.getStatus() != StatutReconciliation.PROPOSED) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Seule une proposition peut etre rejetee (" + r.getStatus() + ")");
        }
        r.setStatus(StatutReconciliation.REJECTED);
        r.setRejectedBy(principal.getId());
        r.setRejectedAt(OffsetDateTime.now());
        r = repository.save(r);
        LigneReleve tx = ligneReleveRepository.findById(r.getBankTransactionId()).orElseThrow();
        auditer(tx, principal, "BANK_MATCH_REJECTED", r, Map.of("motif", motif == null ? "" : motif));
        return r;
    }

    /** Defait un rapprochement confirme : il reste dans l'historique (REVERSED), la transaction redevient a traiter. */
    @Transactional
    public BankReconciliation annuler(UUID entrepriseId, UUID transactionId, String motif, UtilisateurPrincipal principal) {
        entrepriseAccessService.verifierAcces(entrepriseId, principal);
        if (motif == null || motif.isBlank()) {
            throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY, "Un motif est obligatoire pour annuler un rapprochement");
        }
        LigneReleve tx = verrouiller(entrepriseId, transactionId);
        BankReconciliation r = repository.findConfirmeeDe(tx.getId()).orElse(null);
        if (r == null) {
            return null; // rien a annuler : idempotent
        }
        r.setStatus(StatutReconciliation.REVERSED);
        r.setReversedBy(principal.getId());
        r.setReversedAt(OffsetDateTime.now());
        r.setReversalReason(motif.length() > 300 ? motif.substring(0, 300) : motif);
        repository.save(r);
        tx.setLigneEcritureId(null);
        ligneReleveRepository.save(tx);
        auditer(tx, principal, "BANK_RECONCILIATION_REVERSED", r, Map.of("motif", motif));
        return r;
    }

    /**
     * Retire les PROPOSITIONS du systeme (jamais une decision humaine, jamais un rapprochement confirme) portant sur ces transactions : elles sont
     * devenues sans objet (la transaction est desormais la jambe d'un virement interne).
     */
    @Transactional
    public int supprimerPropositions(Collection<UUID> transactionIds) {
        if (transactionIds.isEmpty()) {
            return 0;
        }
        List<BankReconciliation> propositions = repository.findActifsDe(transactionIds).stream()
                .filter(r -> r.getStatus() == StatutReconciliation.PROPOSED).toList();
        repository.deleteAll(propositions);
        return propositions.size();
    }

    // ------------------------------------------------------------------------------------------------ lecture

    @Transactional(readOnly = true)
    public List<BankReconciliation> actifsDe(Collection<UUID> transactionIds) {
        return transactionIds.isEmpty() ? List.of() : repository.findActifsDe(transactionIds);
    }

    @Transactional(readOnly = true)
    public List<BankReconciliation> rejetesDe(Collection<UUID> transactionIds) {
        return transactionIds.isEmpty() ? List.of() : repository.findRejetesDe(transactionIds);
    }

    // ------------------------------------------------------------------------------------------------ interne

    private boolean annuleParUnHumain(UUID transactionId, UUID ligneEcritureId) {
        return repository.existsByBankTransactionIdAndTargetTypeAndTargetIdAndStatus(transactionId, BankReconciliation.CIBLE_LIGNE_ECRITURE,
                ligneEcritureId, StatutReconciliation.REVERSED);
    }

    private void confirmer(LigneReleve tx, BankReconciliation r, UUID confirmePar) {
        r.setStatus(StatutReconciliation.CONFIRMED);
        r.setConfirmedBy(confirmePar);
        r.setConfirmedAt(OffsetDateTime.now());
        try {
            repository.saveAndFlush(r);
        } catch (DataIntegrityViolationException e) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Cette ecriture est deja rapprochee a une autre transaction");
        }
        tx.setLigneEcritureId(r.getTargetId());
        ligneReleveRepository.save(tx);
    }

    private BankReconciliation vivantOuNouvelle(LigneReleve tx, UUID ligneEcritureId) {
        return repository.findVivant(tx.getId(), ligneEcritureId).orElseGet(() -> nouvelle(tx, ligneEcritureId));
    }

    private BankReconciliation nouvelle(LigneReleve tx, UUID ligneEcritureId) {
        BankReconciliation r = new BankReconciliation();
        r.setEntrepriseId(tx.getEntrepriseId());
        r.setBankTransactionId(tx.getId());
        r.setTargetId(ligneEcritureId);
        r.setStatus(StatutReconciliation.PROPOSED);
        return r;
    }

    private BankReconciliation charger(UUID entrepriseId, UUID reconciliationId) {
        return repository.findByIdAndEntrepriseId(reconciliationId, entrepriseId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Rapprochement introuvable"));
    }

    private LigneReleve verrouiller(UUID entrepriseId, UUID transactionId) {
        return ligneReleveRepository.verrouiller(transactionId)
                .filter(l -> l.getEntrepriseId().equals(entrepriseId))
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Transaction bancaire introuvable"));
    }

    /** La cible doit etre une ecriture validee de l'entreprise, sur le compte de la transaction, non prise par une autre transaction. */
    private void controlerPoseePossible(LigneReleve tx, UUID ligneEcritureId) {
        if (!transferRepository.findVivantsDont(List.of(tx.getId())).isEmpty()) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "Cette transaction est la jambe d'un virement interne : annulez le virement interne avant de la rapprocher a une ecriture");
        }
        LigneEcriture cible = ligneEcritureRepository.findById(ligneEcritureId)
                .filter(l -> l.getEcriture().getEntreprise().getId().equals(tx.getEntrepriseId()))
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Ligne d'ecriture introuvable"));
        if (!cible.getCompte().getId().equals(tx.getCompteId())) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Cette ligne d'ecriture ne concerne pas le compte de ce releve");
        }
        if (cible.getEcriture().getStatut() != StatutEcriture.VALIDEE) {
            throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY, "Seule une ecriture validee peut etre rapprochee");
        }
        Optional<BankReconciliation> prise = repository.findConfirmeeSurCible(ligneEcritureId);
        if (prise.isPresent() && !prise.get().getBankTransactionId().equals(tx.getId())) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Cette ecriture est deja rapprochee a une autre transaction");
        }
    }

    private void auditer(LigneReleve tx, UtilisateurPrincipal principal, String action, BankReconciliation r, Map<String, Object> extra) {
        Map<String, Object> details = new LinkedHashMap<>(extra);
        details.put("transactionId", tx.getId().toString());
        details.put("cibleId", r.getTargetId().toString());
        details.put("decision", String.valueOf(r.getDecision()));
        if (r.getConfidence() != null) {
            details.put("confiance", r.getConfidence().toPlainString());
        }
        UUID tenantId = ligneEcritureRepository.findById(r.getTargetId())
                .map(l -> l.getEcriture().getEntreprise().getTenant() != null ? l.getEcriture().getEntreprise().getTenant().getId() : null).orElse(null);
        if (principal != null) {
            auditTrailService.enregistrerActionHumaine(tenantId, principal.getId(), action, "BANK_RECONCILIATION", r.getId().toString(), details);
        } else {
            auditTrailService.enregistrerEvenementSysteme(tenantId, action, "BANK_RECONCILIATION", r.getId().toString(), details);
        }
    }
}
