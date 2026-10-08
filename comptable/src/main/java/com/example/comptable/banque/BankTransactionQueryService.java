package com.example.comptable.banque;

import com.example.comptable.banque.BanqueDto.CandidatDto;
import com.example.comptable.banque.BanqueDto.CritereDto;
import com.example.comptable.banque.BanqueDto.MetriquesDto;
import com.example.comptable.banque.BanqueDto.PageTransactions;
import com.example.comptable.banque.BanqueDto.PropositionDto;
import com.example.comptable.banque.BanqueDto.PropositionLotDto;
import com.example.comptable.banque.BanqueDto.StatutTransaction;
import com.example.comptable.banque.BanqueDto.SyntheseDto;
import com.example.comptable.banque.BanqueDto.TransactionDto;
import com.example.comptable.comptabilite.allocation.SituationTiersService;
import com.example.comptable.comptabilite.domain.LigneEcriture;
import com.example.comptable.comptabilite.domain.LigneReleve;
import com.example.comptable.comptabilite.repository.LigneEcritureRepository;
import com.example.comptable.comptabilite.repository.LigneReleveRepository;
import com.example.comptable.comptabilite.service.RapprochementAutomatiqueService;
import com.example.comptable.comptabilite.service.RapprochementScorer;
import com.example.comptable.entreprise.service.EntrepriseAccessService;
import com.example.comptable.security.UtilisateurPrincipal;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.temporal.ChronoUnit;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * Lecture de l'ecran Banque : liste filtrable des transactions avec leur statut RECONSTITUE a la demande (rapproche, a confirmer, ambigu, sans
 * correspondance), synthese, candidats expliques, mesures. Le classement des lignes non rapprochees est calcule en une passe par releve (index
 * par montant), jamais une requete par transaction.
 */
@Service
public class BankTransactionQueryService {

    private static final LocalDate DEBUT_DES_TEMPS = LocalDate.of(1900, 1, 1);
    private static final LocalDate FIN_DES_TEMPS = LocalDate.of(2999, 12, 31);
    static final int MAX_CANDIDATS = 20;

    private record Vue(LigneReleve ligne, StatutTransaction statut, BankReconciliation rapprochement, RapprochementScorer.ClassementNote note,
                       BankInternalTransfer transfert) {
    }

    private final EntrepriseAccessService entrepriseAccessService;
    private final LigneReleveRepository ligneReleveRepository;
    private final LigneEcritureRepository ligneEcritureRepository;
    private final BankReconciliationService reconciliationService;
    private final BankReconciliationRepository reconciliationRepository;
    private final RapprochementAutomatiqueService automatiqueService;
    private final SituationTiersService situationTiersService;
    private final BankInternalTransferRepository transferRepository;

    public BankTransactionQueryService(EntrepriseAccessService entrepriseAccessService, LigneReleveRepository ligneReleveRepository,
                                       LigneEcritureRepository ligneEcritureRepository, BankReconciliationService reconciliationService,
                                       BankReconciliationRepository reconciliationRepository, RapprochementAutomatiqueService automatiqueService,
                                       SituationTiersService situationTiersService, BankInternalTransferRepository transferRepository) {
        this.entrepriseAccessService = entrepriseAccessService;
        this.ligneReleveRepository = ligneReleveRepository;
        this.ligneEcritureRepository = ligneEcritureRepository;
        this.reconciliationService = reconciliationService;
        this.reconciliationRepository = reconciliationRepository;
        this.automatiqueService = automatiqueService;
        this.situationTiersService = situationTiersService;
        this.transferRepository = transferRepository;
    }

    // ------------------------------------------------------------------------------------------------ liste

    @Transactional(readOnly = true)
    public PageTransactions lister(UUID entrepriseId, UUID compteId, String periode, StatutTransaction statut, String q, int page, int taille,
                                   UtilisateurPrincipal principal) {
        return lister(entrepriseId, compteId, periode, statut, q, null, true, page, taille, principal);
    }

    /**
     * Pagination, filtres et tri cote serveur. Rapprochees et virements confirmes : une page SQL, puis le classement des SEULES lignes affichees. Statuts
     * deduits du classement (a confirmer, ambigu, sans correspondance) : seules les transactions non expliquees sont examinees, jamais l'historique.
     */
    @Transactional(readOnly = true)
    public PageTransactions lister(UUID entrepriseId, UUID compteId, String periode, StatutTransaction statut, String q, String tri, boolean decroissant,
                                   int page, int taille, UtilisateurPrincipal principal) {
        entrepriseAccessService.verifierAcces(entrepriseId, principal);
        LocalDate[] bornes = bornes(periode);
        String motif = motif(q);
        Sort sort = tri(tri, decroissant);
        int taillePage = Math.max(1, Math.min(taille, 200));
        int numero = Math.max(0, page);
        boolean calcule = statut == StatutTransaction.A_CONFIRMER || statut == StatutTransaction.AMBIGUE || statut == StatutTransaction.SANS_CORRESPONDANCE;
        List<Vue> pageVues;
        long total;
        if (!calcule) {
            String etat = statut == null ? "TOUS" : statut == StatutTransaction.RAPPROCHEE ? "RAPP" : "V_CONF";
            Page<UUID> ids = ligneReleveRepository.pageIds(entrepriseId, bornes[0], bornes[1], compteId == null, compteId == null ? AUCUN : compteId, motif == null,
                    motif == null ? "" : motif, etat, PageRequest.of(numero, taillePage, sort));
            Map<UUID, LigneReleve> parId = ids.isEmpty() ? Map.of()
                    : ligneReleveRepository.avecReleve(ids.getContent()).stream().collect(Collectors.toMap(LigneReleve::getId, l -> l));
            List<LigneReleve> lignes = ids.getContent().stream().map(parId::get).toList();
            pageVues = construire(entrepriseId, lignes, automatiqueService.classerCibles(lignes));
            total = ids.getTotalElements();
        } else {
            List<Vue> vues = nonExpliquees(entrepriseId, compteId, bornes, motif, sort, true).stream().filter(v -> v.statut() == statut).toList();
            total = vues.size();
            pageVues = vues.stream().skip((long) numero * taillePage).limit(taillePage).toList();
        }
        Map<UUID, LigneEcriture> ecritures = ecrituresDe(pageVues);
        Map<UUID, LigneReleve> contreparties = contrepartiesDe(pageVues);
        List<TransactionDto> lignes = pageVues.stream().map(v -> versDto(v, ecritures, contreparties)).toList();
        int pages = (int) Math.ceil(total / (double) taillePage);
        return new PageTransactions(lignes, total, numero, taillePage, pages);
    }

    @Transactional(readOnly = true)
    public SyntheseDto synthese(UUID entrepriseId, UUID compteId, String periode, UtilisateurPrincipal principal) {
        entrepriseAccessService.verifierAcces(entrepriseId, principal);
        LocalDate[] bornes = bornes(periode);
        long rapprochees = compter(entrepriseId, compteId, bornes, "RAPP");
        long virements = compter(entrepriseId, compteId, bornes, "V_CONF");
        List<Vue> vues = nonExpliquees(entrepriseId, compteId, bornes, null, Sort.unsorted(), true);
        Map<StatutTransaction, Long> parStatut = vues.stream().collect(Collectors.groupingBy(Vue::statut, Collectors.counting()));
        long total = rapprochees + virements + vues.size();
        BigDecimal nonRapproche = vues.stream().map(v -> v.ligne().getMontant().abs()).reduce(BigDecimal.ZERO, BigDecimal::add);
        long doublons = vues.stream().filter(v -> "POSSIBLE_DUPLICATE".equals(v.ligne().getDuplicateStatus())).count();
        return new SyntheseDto(periode, total, rapprochees, parStatut.getOrDefault(StatutTransaction.A_CONFIRMER, 0L),
                parStatut.getOrDefault(StatutTransaction.AMBIGUE, 0L), parStatut.getOrDefault(StatutTransaction.SANS_CORRESPONDANCE, 0L), doublons,
                total == 0 ? 0 : Math.round((rapprochees + virements) * 1000.0 / total) / 10.0, nonRapproche, virements);
    }

    @Transactional(readOnly = true)
    public MetriquesDto metriques(UUID entrepriseId, UtilisateurPrincipal principal) {
        entrepriseAccessService.verifierAcces(entrepriseId, principal);
        LocalDate[] bornes = bornes(null);
        long expliquees = compter(entrepriseId, null, bornes, "RAPP") + compter(entrepriseId, null, bornes, "V_CONF");
        List<Vue> vues = nonExpliquees(entrepriseId, null, bornes, null, Sort.unsorted(), true);
        long total = expliquees + vues.size();
        long automatiques = reconciliationRepository.compterConfirmeesParSource(entrepriseId, SourceReconciliation.SYSTEM_EXACT);
        long aConfirmer = vues.stream().filter(v -> v.statut() == StatutTransaction.A_CONFIRMER).count();
        long ambigues = vues.stream().filter(v -> v.statut() == StatutTransaction.AMBIGUE).count();
        long sans = vues.stream().filter(v -> v.statut() == StatutTransaction.SANS_CORRESPONDANCE).count();
        LocalDate aujourdhui = LocalDate.now();
        Map<String, Long> anciennete = new LinkedHashMap<>();
        for (String tranche : List.of("0-30 jours", "31-60 jours", "61-90 jours", "plus de 90 jours")) {
            anciennete.put(tranche, 0L);
        }
        long plusAncien = 0;
        BigDecimal montant = BigDecimal.ZERO;
        for (Vue v : vues) {
            if (v.statut() == StatutTransaction.RAPPROCHEE || v.statut() == StatutTransaction.VIREMENT_INTERNE) {
                continue;
            }
            montant = montant.add(v.ligne().getMontant().abs());
            long age = Math.max(0, ChronoUnit.DAYS.between(v.ligne().getDateOperation(), aujourdhui));
            plusAncien = Math.max(plusAncien, age);
            String tranche = age <= 30 ? "0-30 jours" : age <= 60 ? "31-60 jours" : age <= 90 ? "61-90 jours" : "plus de 90 jours";
            anciennete.merge(tranche, 1L, Long::sum);
        }
        Double secondes = reconciliationRepository.tempsMoyenSecondes(entrepriseId);
        long facturesOuvertes = situationTiersService.lignesOuvertesParTiers(entrepriseId).values().stream()
                .flatMap(List::stream).filter(l -> l.role() == SituationTiersService.Role.FACTURE).count();
        return new MetriquesDto(total, pct(automatiques, total), pct(aConfirmer, total), pct(ambigues, total), pct(sans, total),
                secondes == null ? null : Math.round(secondes / 360.0) / 10.0, facturesOuvertes, montant, anciennete,
                montant.signum() == 0 ? null : plusAncien);
    }

    // ------------------------------------------------------------------------------------------------ candidats

    /** Candidats expliques pour une transaction (recherche metier par piece ou libelle, jamais un identifiant a saisir). */
    @Transactional(readOnly = true)
    public List<CandidatDto> candidats(UUID entrepriseId, UUID transactionId, String q, UtilisateurPrincipal principal) {
        entrepriseAccessService.verifierAcces(entrepriseId, principal);
        LigneReleve tx = ligneReleveRepository.findById(transactionId).filter(l -> l.getEntrepriseId().equals(entrepriseId))
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Transaction bancaire introuvable"));
        List<LigneEcriture> mouvements = ligneEcritureRepository.rechercherMouvementsValides(tx.getCompteId(),
                tx.getDateOperation().minusDays(90), tx.getDateOperation().plusDays(90));
        Set<UUID> prises = new java.util.HashSet<>(ligneReleveRepository.rechercherLigneEcritureIdsPointees(tx.getCompteId()));
        Set<UUID> proposes = reconciliationService.actifsDe(List.of(tx.getId())).stream()
                .filter(r -> r.getStatus() == StatutReconciliation.PROPOSED).map(BankReconciliation::getTargetId).collect(Collectors.toSet());
        String recherche = q == null ? "" : com.example.comptable.banque.BankLabelNormalizer.normaliser(q);
        return mouvements.stream()
                .filter(m -> !prises.contains(m.getId()) || m.getId().equals(tx.getLigneEcritureId()))
                .filter(m -> recherche.isEmpty() || com.example.comptable.banque.BankLabelNormalizer.normaliser(
                        m.getEcriture().getNumeroPiece() + " " + m.getEcriture().getLibelle() + " " + m.getLibelle()).contains(recherche))
                .map(m -> RapprochementScorer.noterLibre(tx, m))
                .sorted(Comparator.comparingInt(RapprochementScorer.Candidat::score).reversed()
                        .thenComparing(c -> c.ligne().getEcriture().getDateEcriture(), Comparator.reverseOrder()))
                .limit(MAX_CANDIDATS)
                .map(c -> new CandidatDto(c.ligne().getId(), c.ligne().getEcriture().getNumeroPiece(), c.ligne().getEcriture().getDateEcriture(),
                        c.ligne().getEcriture().getLibelle(), c.ligne().getDebit().signum() > 0 ? c.ligne().getDebit() : c.ligne().getCredit().negate(),
                        c.score(), criteresDe(c), proposes.contains(c.ligne().getId()), prises.contains(c.ligne().getId())))
                .toList();
    }

    // ------------------------------------------------------------------------------------------------ validation en lot

    /** Propositions assez sures pour etre validees ensemble : le comptable les voit TOUTES avant de confirmer. */
    @Transactional(readOnly = true)
    public List<PropositionLotDto> apercuLot(UUID entrepriseId, int confianceMin, UtilisateurPrincipal principal) {
        entrepriseAccessService.verifierAcces(entrepriseId, principal);
        List<Vue> vues = nonExpliquees(entrepriseId, null, bornes(null), null, Sort.unsorted(), false).stream()
                .filter(v -> v.statut() == StatutTransaction.A_CONFIRMER && v.rapprochement() != null
                        && v.rapprochement().getStatus() == StatutReconciliation.PROPOSED && v.rapprochement().getConfidence() != null
                        && v.rapprochement().getConfidence().intValue() >= confianceMin)
                .toList();
        Map<UUID, LigneEcriture> ecritures = ecrituresDe(vues);
        return vues.stream().map(v -> {
            LigneEcriture cible = ecritures.get(v.rapprochement().getTargetId());
            return new PropositionLotDto(v.rapprochement().getId(), v.ligne().getId(), v.ligne().getDateOperation(), v.ligne().getLibelle(),
                    v.ligne().getMontant(), cible != null ? cible.getEcriture().getNumeroPiece() : null, v.rapprochement().getConfidence().intValue(),
                    explication(v.rapprochement()));
        }).toList();
    }

    // ------------------------------------------------------------------------------------------------ interne

    private static final UUID AUCUN = new UUID(0, 0);

    private static LocalDate[] bornes(String periode) {
        if (periode == null || periode.isBlank()) {
            return new LocalDate[] {DEBUT_DES_TEMPS, FIN_DES_TEMPS};
        }
        YearMonth mois = YearMonth.parse(periode);
        return new LocalDate[] {mois.atDay(1), mois.atEndOfMonth()};
    }

    /** Motif SQL de la recherche (meme normalisation que les libelles stockes) ; null si aucune recherche. */
    private static String motif(String q) {
        if (q == null || q.isBlank()) {
            return null;
        }
        String n = BankLabelNormalizer.normaliser(q).replace("%", "").replace("_", "");
        return n.isBlank() ? null : "%" + n + "%";
    }

    private static Sort tri(String tri, boolean decroissant) {
        String propriete = "montant".equals(tri) ? "montant" : "libelle".equals(tri) ? "libelle" : "dateOperation";
        Sort.Direction sens = decroissant ? Sort.Direction.DESC : Sort.Direction.ASC;
        return Sort.by(new Sort.Order(sens, propriete), new Sort.Order(Sort.Direction.ASC, "id"));
    }

    private long compter(UUID entrepriseId, UUID compteId, LocalDate[] bornes, String etat) {
        return ligneReleveRepository.compterFiltrees(entrepriseId, bornes[0], bornes[1], compteId == null, compteId == null ? AUCUN : compteId, true, "", etat);
    }

    /** Transactions non expliquees (tout sauf rapprochees et virements confirmes) : le seul volume qui demande du calcul. */
    private List<Vue> nonExpliquees(UUID entrepriseId, UUID compteId, LocalDate[] bornes, String motif, Sort sort, boolean classer) {
        List<LigneReleve> lignes = ligneReleveRepository.filtrees(entrepriseId, bornes[0], bornes[1], compteId == null, compteId == null ? AUCUN : compteId,
                motif == null, motif == null ? "" : motif, "NON_EXPLIQUEES", sort);
        if (lignes.isEmpty()) {
            return List.of();
        }
        Map<UUID, RapprochementScorer.ClassementNote> classements = !classer ? Map.of() : automatiqueService.classerNonPointees(entrepriseId).stream()
                .collect(Collectors.toMap(c -> c.classement().ligne().getId(), RapprochementAutomatiqueService.LigneClassee::classement, (a, b) -> a));
        return construire(entrepriseId, lignes, classements);
    }

    private List<Vue> construire(UUID entrepriseId, List<LigneReleve> lignes, Map<UUID, RapprochementScorer.ClassementNote> classements) {
        if (lignes.isEmpty()) {
            return List.of();
        }
        Map<UUID, BankReconciliation> actifs = new HashMap<>();
        for (BankReconciliation r : reconciliationService.actifsDe(lignes.stream().map(LigneReleve::getId).toList())) {
            BankReconciliation deja = actifs.get(r.getBankTransactionId());
            if (deja == null || r.getStatus() == StatutReconciliation.CONFIRMED) {
                actifs.put(r.getBankTransactionId(), r);
            }
        }
        Map<UUID, BankInternalTransfer> transferts = new HashMap<>();
        for (BankInternalTransfer t : transferRepository.findVivantsDont(lignes.stream().map(LigneReleve::getId).toList())) {
            transferts.put(t.getDebitTransactionId(), t);
            transferts.put(t.getCreditTransactionId(), t);
        }
        return lignes.stream().map(l -> {
            BankReconciliation r = actifs.get(l.getId());
            RapprochementScorer.ClassementNote note = classements.get(l.getId());
            BankInternalTransfer transfert = transferts.get(l.getId());
            StatutTransaction statut;
            if (transfert != null) {
                // Une jambe de virement interne n'est ni un reglement de tiers ni une charge : elle est expliquee (ou proposee) comme virement.
                statut = transfert.getStatus() == StatutReconciliation.CONFIRMED ? StatutTransaction.VIREMENT_INTERNE : StatutTransaction.A_CONFIRMER;
            } else if (l.getLigneEcritureId() != null) {
                statut = StatutTransaction.RAPPROCHEE;
            } else if (r != null && r.getStatus() == StatutReconciliation.PROPOSED) {
                statut = StatutTransaction.A_CONFIRMER;
            } else if (note == null || note.statut() == com.example.comptable.comptabilite.service.RapprochementClassifier.Statut.UNMATCHED) {
                statut = StatutTransaction.SANS_CORRESPONDANCE;
            } else if (note.statut() == com.example.comptable.comptabilite.service.RapprochementClassifier.Statut.AMBIGUOUS) {
                statut = StatutTransaction.AMBIGUE;
            } else {
                statut = StatutTransaction.A_CONFIRMER;
            }
            return new Vue(l, statut, r, note, transfert);
        }).toList();
    }

    private Map<UUID, LigneEcriture> ecrituresDe(List<Vue> vues) {
        Set<UUID> ids = new java.util.HashSet<>();
        for (Vue v : vues) {
            if (v.ligne().getLigneEcritureId() != null) {
                ids.add(v.ligne().getLigneEcritureId());
            }
            if (v.rapprochement() != null) {
                ids.add(v.rapprochement().getTargetId());
            }
            if (v.note() != null && v.note().meilleur() != null) {
                ids.add(v.note().meilleur().ligne().getId());
            }
        }
        if (ids.isEmpty()) {
            return Map.of();
        }
        return ligneEcritureRepository.chargerAvecEcriture(ids).stream().collect(Collectors.toMap(LigneEcriture::getId, l -> l, (a, b) -> a));
    }

    private Map<UUID, LigneReleve> contrepartiesDe(List<Vue> vues) {
        Set<UUID> ids = new java.util.HashSet<>();
        for (Vue v : vues) {
            if (v.transfert() != null) {
                UUID autre = v.transfert().getDebitTransactionId().equals(v.ligne().getId()) ? v.transfert().getCreditTransactionId() : v.transfert().getDebitTransactionId();
                ids.add(autre);
            }
        }
        return ids.isEmpty() ? Map.of() : ligneReleveRepository.findAllById(ids).stream().collect(Collectors.toMap(LigneReleve::getId, x -> x));
    }

    private TransactionDto versDto(Vue v, Map<UUID, LigneEcriture> ecritures, Map<UUID, LigneReleve> contreparties) {
        LigneReleve l = v.ligne();
        PropositionDto proposition = null;
        if (v.transfert() != null) {
            BankInternalTransfer t = v.transfert();
            LigneReleve autre = contreparties.get(t.getDebitTransactionId().equals(l.getId()) ? t.getCreditTransactionId() : t.getDebitTransactionId());
            String contrepartie = autre == null ? null : (l.getMontant().signum() < 0 ? "vers le compte " : "depuis le compte ") + autre.getReleve().getCompte().getNumero()
                    + " (" + autre.getDateOperation() + ")";
            String explication = t.getExplanation() != null && t.getExplanation().get("explication") != null ? t.getExplanation().get("explication").toString() : null;
            proposition = new PropositionDto(null, null, null, null, null, t.getConfidence() != null ? t.getConfidence().intValue() : null, explication, List.of(),
                    t.getSource().name(), t.getStatus().name(), 1, "VIREMENT_INTERNE", t.getId(), contrepartie);
        } else if (v.rapprochement() != null) {
            LigneEcriture cible = ecritures.get(v.rapprochement().getTargetId());
            proposition = new PropositionDto(v.rapprochement().getId(), v.rapprochement().getTargetId(),
                    cible != null ? cible.getEcriture().getNumeroPiece() : null, cible != null ? cible.getEcriture().getDateEcriture() : null,
                    cible != null ? cible.getEcriture().getLibelle() : null,
                    v.rapprochement().getConfidence() != null ? v.rapprochement().getConfidence().intValue() : null, explication(v.rapprochement()),
                    List.of(), v.rapprochement().getSource().name(), v.rapprochement().getStatus().name(), 1);
        } else if (v.note() != null && v.note().meilleur() != null && v.statut() != StatutTransaction.SANS_CORRESPONDANCE) {
            RapprochementScorer.Candidat meilleur = v.note().meilleur();
            proposition = new PropositionDto(null, meilleur.ligne().getId(), meilleur.ligne().getEcriture().getNumeroPiece(),
                    meilleur.ligne().getEcriture().getDateEcriture(), meilleur.ligne().getEcriture().getLibelle(), meilleur.score(),
                    meilleur.explication(), criteresDe(meilleur), null, v.statut() == StatutTransaction.AMBIGUE ? "AMBIGUOUS" : "PROPOSED",
                    v.note().candidats().size());
        }
        if (proposition != null && v.note() != null && v.statut() == StatutTransaction.A_CONFIRMER && v.note().meilleur() != null
                && v.rapprochement() != null) {
            proposition = new PropositionDto(proposition.reconciliationId(), proposition.ligneEcritureId(), proposition.piece(), proposition.dateEcriture(),
                    proposition.libelleEcriture(), proposition.confiance(), v.note().meilleur().explication(), criteresDe(v.note().meilleur()),
                    proposition.source(), proposition.statut(), v.note().candidats().size());
        }
        return new TransactionDto(l.getId(), l.getReleve().getId(), l.getReleve().getCompte().getNumero(), l.getDateOperation(), l.getLibelle(),
                l.getMontant(), l.getDirection(), v.statut().name(), "POSSIBLE_DUPLICATE".equals(l.getDuplicateStatus()), l.getPaymentMethod(),
                l.getCounterpartyName(), l.getInvoiceHint() != null ? l.getInvoiceHint() : l.getReference(), proposition);
    }

    private static String explication(BankReconciliation r) {
        return r.getExplanation() != null && r.getExplanation().get("explication") != null ? r.getExplanation().get("explication").toString() : null;
    }

    private static List<CritereDto> criteresDe(RapprochementScorer.Candidat c) {
        return c.criteres().stream().map(k -> new CritereDto(k.code(), k.libelle(), k.satisfait(), k.points())).toList();
    }

    private static double pct(long n, long total) {
        return total == 0 ? 0 : Math.round(n * 1000.0 / total) / 10.0;
    }
}
