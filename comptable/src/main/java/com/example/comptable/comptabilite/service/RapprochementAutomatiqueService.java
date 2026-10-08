package com.example.comptable.comptabilite.service;

import com.example.comptable.banque.BankReconciliation;
import com.example.comptable.banque.BankReconciliationRepository;
import com.example.comptable.banque.BankReconciliationService;
import com.example.comptable.banque.DecisionReconciliation;
import com.example.comptable.banque.SourceReconciliation;
import java.math.BigDecimal;

import com.example.comptable.audit.AuditTrailService;
import com.example.comptable.comptabilite.domain.LigneEcriture;
import com.example.comptable.comptabilite.domain.LigneReleve;
import com.example.comptable.comptabilite.domain.ReleveBancaire;
import com.example.comptable.comptabilite.repository.LigneEcritureRepository;
import com.example.comptable.comptabilite.repository.LigneReleveRepository;
import com.example.comptable.comptabilite.repository.ReleveBancaireRepository;
import com.example.comptable.entreprise.domain.Entreprise;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Rapprochement bancaire RELANCABLE (Phase B, lot B4) : classe les lignes de releve non pointees
 * d'une entreprise avec {@link RapprochementClassifier} et ne pointe automatiquement que les
 * rapprochements EXACTS (CERTAIN -&gt; automatise, audite). Tout le reste (PROBABLE, AMBIGUOUS,
 * UNMATCHED) est laisse a la revue : jamais de choix arbitraire.
 */
@Service
public class RapprochementAutomatiqueService {

    private static final int TOLERANCE_JOURS = 15;

    private final ReleveBancaireRepository releveRepository;
    private final LigneEcritureRepository ligneEcritureRepository;
    private final LigneReleveRepository ligneReleveRepository;
    private final BankReconciliationService reconciliationService;
    private final BankReconciliationRepository reconciliationRepository;
    private final com.example.comptable.banque.InternalTransferService internalTransferService;

    public RapprochementAutomatiqueService(ReleveBancaireRepository releveRepository,
                                           LigneEcritureRepository ligneEcritureRepository,
                                           LigneReleveRepository ligneReleveRepository,
                                           BankReconciliationService reconciliationService,
                                           BankReconciliationRepository reconciliationRepository,
                                           com.example.comptable.banque.InternalTransferService internalTransferService) {
        this.releveRepository = releveRepository;
        this.ligneEcritureRepository = ligneEcritureRepository;
        this.ligneReleveRepository = ligneReleveRepository;
        this.reconciliationService = reconciliationService;
        this.reconciliationRepository = reconciliationRepository;
        this.internalTransferService = internalTransferService;
    }

    /** Classement d'une ligne non pointee avec son releve et son score explicable, pour la revue. */
    public record LigneClassee(ReleveBancaire releve, RapprochementScorer.ClassementNote classement) {
    }

    /** Classe les lignes non pointees de tous les releves qui en ont (aucune ecriture). */
    @Transactional(readOnly = true)
    public List<LigneClassee> classerNonPointees(UUID entrepriseId) {
        List<LigneClassee> resultat = new ArrayList<>();
        for (ReleveBancaire releve : releveRepository.rechercherAvecLignesNonPointees(entrepriseId)) {
            resultat.addAll(classerReleve(releve));
        }
        return resultat;
    }

    /**
     * Classe SEULEMENT les transactions demandees (une page de l'ecran), avec le meme resultat qu'un classement complet : le statut d'une ligne ne depend
     * que des lignes du meme releve ayant le MEME montant signe (ce sont les seules a pouvoir se disputer ses mouvements), qui sont donc ajoutees.
     */
    @Transactional(readOnly = true)
    public Map<UUID, RapprochementScorer.ClassementNote> classerCibles(Collection<LigneReleve> cibles) {
        Map<UUID, List<LigneReleve>> parReleve = new java.util.LinkedHashMap<>();
        for (LigneReleve l : cibles) {
            if (l.getLigneEcritureId() == null) {
                parReleve.computeIfAbsent(l.getReleve().getId(), k -> new ArrayList<>()).add(l);
            }
        }
        Map<UUID, Set<UUID>> pointeesParCompte = new HashMap<>();
        Map<UUID, RapprochementScorer.ClassementNote> resultat = new HashMap<>();
        for (Map.Entry<UUID, List<LigneReleve>> e : parReleve.entrySet()) {
            ReleveBancaire releve = e.getValue().get(0).getReleve();
            Set<BigDecimal> montants = e.getValue().stream().map(LigneReleve::getMontant).collect(java.util.stream.Collectors.toSet());
            List<LigneReleve> concurrentes = ligneReleveRepository.nonPointeesDeMemeMontant(releve.getId(), montants);
            UUID compteId = releve.getCompte().getId();
            List<LigneEcriture> mouvements = ligneEcritureRepository.rechercherMouvementsValides(compteId,
                    releve.getDateDebut().minusDays(TOLERANCE_JOURS), releve.getDateFin().plusDays(TOLERANCE_JOURS));
            Set<UUID> dejaPointees = pointeesParCompte.computeIfAbsent(compteId, c -> new HashSet<>(ligneReleveRepository.rechercherLigneEcritureIdsPointees(c)));
            Set<UUID> jambes = internalTransferService.jambes(concurrentes.stream().map(LigneReleve::getId).toList());
            List<LigneReleve> nonPointees = concurrentes.stream().filter(l -> !jambes.contains(l.getId())).toList();
            Map<UUID, Set<UUID>> rejetes = new HashMap<>();
            for (BankReconciliation r : reconciliationService.rejetesDe(nonPointees.stream().map(LigneReleve::getId).toList())) {
                rejetes.computeIfAbsent(r.getBankTransactionId(), k -> new HashSet<>()).add(r.getTargetId());
            }
            Set<UUID> voulus = e.getValue().stream().map(LigneReleve::getId).collect(java.util.stream.Collectors.toSet());
            for (RapprochementScorer.ClassementNote note : RapprochementScorer.classer(nonPointees, mouvements, dejaPointees)) {
                if (voulus.contains(note.ligne().getId())) {
                    resultat.put(note.ligne().getId(), sansRejets(note, rejetes.getOrDefault(note.ligne().getId(), Set.of())));
                }
            }
        }
        return resultat;
    }

    /**
     * Confirme les rapprochements EXACTS (releve par releve) et enregistre une proposition pour les PROBABLES ; tout le reste est laisse a
     * la revue. @return le nombre de lignes rapprochees automatiquement.
     */
    @Transactional
    public int appliquerExacts(Entreprise entreprise) {
        List<BankReconciliationService.DemandeAutomatique> demandes = new ArrayList<>();
        for (ReleveBancaire releve : releveRepository.rechercherAvecLignesNonPointees(entreprise.getId())) {
            for (LigneClassee classee : classerReleve(releve)) {
                RapprochementScorer.ClassementNote note = classee.classement();
                RapprochementScorer.Candidat meilleur = note.meilleur();
                if (note.statut() == RapprochementClassifier.Statut.EXACT) {
                    demandes.add(new BankReconciliationService.DemandeAutomatique(note.ligne(), meilleur.ligne().getId(), SourceReconciliation.SYSTEM_EXACT,
                            DecisionReconciliation.EXACT, BigDecimal.valueOf(meilleur.score()), meilleur.explication(), true));
                } else if (note.statut() == RapprochementClassifier.Statut.PROBABLE) {
                    demandes.add(new BankReconciliationService.DemandeAutomatique(note.ligne(), meilleur.ligne().getId(), SourceReconciliation.SYSTEM_PROPOSAL,
                            DecisionReconciliation.PROBABLE, BigDecimal.valueOf(meilleur.score()), meilleur.explication(), false));
                }
            }
        }
        return reconciliationService.appliquerLot(entreprise.getId(), demandes).confirmees();
    }

    private List<LigneClassee> classerReleve(ReleveBancaire releve) {
        LocalDate debut = releve.getDateDebut().minusDays(TOLERANCE_JOURS);
        LocalDate fin = releve.getDateFin().plusDays(TOLERANCE_JOURS);
        List<LigneEcriture> mouvements = ligneEcritureRepository.rechercherMouvementsValides(releve.getCompte().getId(), debut, fin);
        Set<UUID> dejaPointees = new HashSet<>(ligneReleveRepository.rechercherLigneEcritureIdsPointees(releve.getCompte().getId()));
        // Les jambes d'un virement interne ne sont ni un reglement fournisseur ni un encaissement client : elles sortent du rapprochement avec les tiers.
        Set<UUID> jambesDeVirement = internalTransferService.jambes(releve.getLignes().stream().map(LigneReleve::getId).toList());
        List<LigneReleve> nonPointees = releve.getLignes().stream()
                .filter(l -> l.getLigneEcritureId() == null && !jambesDeVirement.contains(l.getId())).toList();
        // Une proposition rejetee par un humain n'est plus jamais candidate pour cette transaction.
        Map<UUID, Set<UUID>> rejetes = new java.util.HashMap<>();
        for (BankReconciliation r : reconciliationService.rejetesDe(nonPointees.stream().map(LigneReleve::getId).toList())) {
            rejetes.computeIfAbsent(r.getBankTransactionId(), k -> new HashSet<>()).add(r.getTargetId());
        }
        return RapprochementScorer.classer(nonPointees, mouvements, dejaPointees).stream()
                .map(c -> new LigneClassee(releve, sansRejets(c, rejetes.getOrDefault(c.ligne().getId(), Set.of()))))
                .toList();
    }

    private static RapprochementScorer.ClassementNote sansRejets(RapprochementScorer.ClassementNote note, Set<UUID> rejetes) {
        if (rejetes.isEmpty()) {
            return note;
        }
        List<RapprochementScorer.Candidat> restants = note.candidats().stream().filter(c -> !rejetes.contains(c.ligne().getId())).toList();
        if (restants.size() == note.candidats().size()) {
            return note;
        }
        // Plus aucun candidat : la transaction est sans correspondance ; sinon la decision de base est conservee.
        return new RapprochementScorer.ClassementNote(note.ligne(), restants.isEmpty() ? RapprochementClassifier.Statut.UNMATCHED : note.statut(), restants);
    }
}
