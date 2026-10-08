package com.example.comptable.comptabilite.service;

import com.example.comptable.agent.client.AiServiceClient;
import com.example.comptable.banque.BankReconciliationService;
import com.example.comptable.banque.BankStatementImporter;
import com.example.comptable.banque.DecisionReconciliation;
import com.example.comptable.banque.SourceReconciliation;
import com.example.comptable.comptabilite.service.RapprochementScorer.ClassementNote;
import com.example.comptable.agent.finding.MissingEvidenceAnalyzer;
import com.example.comptable.agent.finding.WhatsAppResolutionService;
import com.example.comptable.agent.matching.MatchDecisionEngine;
import com.example.comptable.agent.matching.MatchingContextBuilder;
import com.example.comptable.agent.matching.ResultatDecision;
import com.example.comptable.agent.matching.dto.MatchingRequestDTO;
import com.example.comptable.agent.matching.dto.MatchingResponseDTO;
import com.example.comptable.agent.rapprochement.AppariementSemantique;
import com.example.comptable.agent.rapprochement.MatchingSemantiqueService;
import com.example.comptable.comptabilite.domain.CompteComptable;
import com.example.comptable.comptabilite.domain.LigneEcriture;
import com.example.comptable.comptabilite.domain.LigneReleve;
import com.example.comptable.comptabilite.domain.ReleveBancaire;
import com.example.comptable.comptabilite.repository.CompteComptableRepository;
import com.example.comptable.comptabilite.repository.LigneEcritureRepository;
import com.example.comptable.comptabilite.repository.LigneReleveRepository;
import com.example.comptable.comptabilite.repository.ReleveBancaireRepository;
import com.example.comptable.comptabilite.web.dto.ImporterReleveRequest;
import com.example.comptable.comptabilite.web.dto.LigneReleveDto;
import com.example.comptable.comptabilite.web.dto.ReleveBancaireDto;
import com.example.comptable.entreprise.domain.Entreprise;
import com.example.comptable.entreprise.service.EntrepriseAccessService;
import com.example.comptable.security.UtilisateurPrincipal;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.server.ResponseStatusException;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * Rapprochement bancaire : import d'un releve CSV (date;libelle;montant, montant signe -
 * positif = entree d'argent), puis pointage de chaque ligne contre la ligne d'ecriture validee
 * correspondante sur le compte de tresorerie concerne. Un appariement automatique tente de
 * rapprocher par montant exact quand un seul candidat non deja pointe existe sur la periode ;
 * les lignes encore non rapprochees passent ensuite par {@link MatchingSemantiqueService}
 * (filet de secours base sur un modele de langage, cf. {@code rapprocherAutomatiquement}) ; le
 * reste se pointe manuellement.
 */
@Service
public class RapprochementBancaireService {

    private static final Logger log = LoggerFactory.getLogger(RapprochementBancaireService.class);
    private static final List<DateTimeFormatter> FORMATS_DATE = List.of(
            DateTimeFormatter.ISO_LOCAL_DATE,
            DateTimeFormatter.ofPattern("dd/MM/yyyy")
    );
    private static final int TOLERANCE_JOURS_APPARIEMENT = 15;

    private final EntrepriseAccessService entrepriseAccessService;
    private final CompteComptableRepository compteComptableRepository;
    private final ReleveBancaireRepository releveBancaireRepository;
    private final LigneReleveRepository ligneReleveRepository;
    private final LigneEcritureRepository ligneEcritureRepository;
    private final MatchingSemantiqueService matchingSemantiqueService;
    private final MatchingContextBuilder matchingContextBuilder;
    private final MatchDecisionEngine matchDecisionEngine;
    private final AiServiceClient aiServiceClient;
    private final MissingEvidenceAnalyzer missingEvidenceAnalyzer;
    private final WhatsAppResolutionService whatsAppResolutionService;
    private final MeterRegistry meterRegistry;
    private final BankStatementImporter bankStatementImporter;
    private final BankReconciliationService reconciliationService;
    private final com.example.comptable.banque.InternalTransferService internalTransferService;
    private final boolean matchingIaActif;

    public RapprochementBancaireService(EntrepriseAccessService entrepriseAccessService,
                                         CompteComptableRepository compteComptableRepository,
                                         ReleveBancaireRepository releveBancaireRepository,
                                         LigneReleveRepository ligneReleveRepository,
                                         LigneEcritureRepository ligneEcritureRepository,
                                         MatchingSemantiqueService matchingSemantiqueService,
                                         MatchingContextBuilder matchingContextBuilder,
                                         MatchDecisionEngine matchDecisionEngine,
                                         AiServiceClient aiServiceClient,
                                         MissingEvidenceAnalyzer missingEvidenceAnalyzer,
                                         WhatsAppResolutionService whatsAppResolutionService,
                                         MeterRegistry meterRegistry,
                                         BankStatementImporter bankStatementImporter,
                                         BankReconciliationService reconciliationService,
                                         com.example.comptable.banque.InternalTransferService internalTransferService,
                                         @Value("${agent.matching-ia.enabled:false}") boolean matchingIaActif) {
        this.entrepriseAccessService = entrepriseAccessService;
        this.compteComptableRepository = compteComptableRepository;
        this.releveBancaireRepository = releveBancaireRepository;
        this.ligneReleveRepository = ligneReleveRepository;
        this.ligneEcritureRepository = ligneEcritureRepository;
        this.matchingSemantiqueService = matchingSemantiqueService;
        this.matchingContextBuilder = matchingContextBuilder;
        this.matchDecisionEngine = matchDecisionEngine;
        this.aiServiceClient = aiServiceClient;
        this.missingEvidenceAnalyzer = missingEvidenceAnalyzer;
        this.whatsAppResolutionService = whatsAppResolutionService;
        this.meterRegistry = meterRegistry;
        this.bankStatementImporter = bankStatementImporter;
        this.reconciliationService = reconciliationService;
        this.internalTransferService = internalTransferService;
        this.matchingIaActif = matchingIaActif;
    }

    /** Resultat complet d'un import : le lot (toujours trace), l'apercu chiffre, et le releve cree (null si rien de nouveau). */
    public record ResultatImport(com.example.comptable.banque.BankImportBatch lot, BankStatementImporter.Apercu apercu, ReleveBancaireDto releve) {
    }

    /** Importe, trace le lot, puis rapproche automatiquement les cas certains et propose les cas probables. */
    @Transactional
    public ResultatImport importerComplet(UUID entrepriseId, BankStatementImporter.DemandeImport demande, MultipartFile fichier,
                                          UtilisateurPrincipal principal) {
        BankStatementImporter.ImportEffectue effectue = bankStatementImporter.importer(entrepriseId, demande, fichier, principal);
        ReleveBancaireDto dto = null;
        if (effectue.releve() != null) {
            rapprocherAutomatiquement(effectue.releve());
            dto = toDto(effectue.releve());
        }
        return new ResultatImport(effectue.lot(), effectue.apercu(), dto);
    }

    @Transactional
    public ReleveBancaireDto importer(UUID entrepriseId, ImporterReleveRequest request, MultipartFile fichier,
                                       UtilisateurPrincipal principal) {
        BankStatementImporter.DemandeImport demande = new BankStatementImporter.DemandeImport(request.compteId(), request.libelle(),
                request.dateDebut(), request.dateFin(), request.soldeDebut(), request.soldeFin(), null);
        ResultatImport resultat = importerComplet(entrepriseId, demande, fichier, principal);
        if (resultat.releve() == null) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Ce releve a deja ete importe : aucune nouvelle transaction");
        }
        return resultat.releve();
    }

    @Transactional(readOnly = true)
    public List<ReleveBancaireDto> lister(UUID entrepriseId, UtilisateurPrincipal principal) {
        entrepriseAccessService.verifierAcces(entrepriseId, principal);
        return releveBancaireRepository.findByEntrepriseIdOrderByDateFinDesc(entrepriseId).stream()
                .map(this::toDto)
                .toList();
    }

    @Transactional(readOnly = true)
    public ReleveBancaireDto obtenir(UUID entrepriseId, UUID releveId, UtilisateurPrincipal principal) {
        entrepriseAccessService.verifierAcces(entrepriseId, principal);
        return toDto(chargerReleve(entrepriseId, releveId));
    }

    @Transactional
    public LigneReleveDto pointer(UUID entrepriseId, UUID releveId, UUID ligneReleveId, UUID ligneEcritureId,
                                   UtilisateurPrincipal principal) {
        entrepriseAccessService.verifierAcces(entrepriseId, principal);
        ReleveBancaire releve = chargerReleve(entrepriseId, releveId);
        LigneReleve ligneReleve = chargerLigneReleve(releve, ligneReleveId);
        // Le rapprochement est une entite tracable : plus d'ecriture directe du pointeur.
        reconciliationService.pointer(entrepriseId, ligneReleve.getId(), ligneEcritureId, SourceReconciliation.USER, principal);
        LigneEcriture ligneEcriture = ligneEcritureRepository.findById(ligneEcritureId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Ligne d'ecriture introuvable"));
        return toLigneDto(ligneReleve, ligneEcriture);
    }

    @Transactional
    public void depointer(UUID entrepriseId, UUID releveId, UUID ligneReleveId, UtilisateurPrincipal principal) {
        entrepriseAccessService.verifierAcces(entrepriseId, principal);
        ReleveBancaire releve = chargerReleve(entrepriseId, releveId);
        LigneReleve ligneReleve = chargerLigneReleve(releve, ligneReleveId);
        reconciliationService.annuler(entrepriseId, ligneReleve.getId(), "Depointage manuel", principal);
    }

    private void rapprocherAutomatiquement(ReleveBancaire releve) {
        LocalDate debut = releve.getDateDebut().minusDays(TOLERANCE_JOURS_APPARIEMENT);
        LocalDate fin = releve.getDateFin().plusDays(TOLERANCE_JOURS_APPARIEMENT);
        List<LigneEcriture> mouvements = ligneEcritureRepository.rechercherMouvementsValides(
                releve.getCompte().getId(), debut, fin);

        Set<UUID> dejaPointees = new HashSet<>(
                ligneReleveRepository.rechercherLigneEcritureIdsPointees(releve.getCompte().getId()));

        // Classification deterministe et score explicable (jamais de choix arbitraire du premier candidat) : seuls les
        // rapprochements EXACTS sont confirmes par le systeme ; les PROBABLES sont proposes ; AMBIGUOUS / UNMATCHED restent a traiter.
        // Virements internes d'abord : leurs deux jambes ne sont ni un reglement fournisseur ni un encaissement client.
        internalTransferService.detecter(releve.getEntreprise());
        Set<UUID> jambesDeVirement = internalTransferService.jambes(releve.getLignes().stream().map(LigneReleve::getId).toList());
        List<LigneReleve> aClasser = releve.getLignes().stream().filter(l -> !jambesDeVirement.contains(l.getId())).toList();
        List<BankReconciliationService.DemandeAutomatique> demandes = new java.util.ArrayList<>();
        for (ClassementNote note : RapprochementScorer.classer(aClasser, mouvements, dejaPointees)) {
            RapprochementScorer.Candidat meilleur = note.meilleur();
            if (note.statut() == RapprochementClassifier.Statut.EXACT) {
                demandes.add(new BankReconciliationService.DemandeAutomatique(note.ligne(), meilleur.ligne().getId(), SourceReconciliation.SYSTEM_EXACT,
                        DecisionReconciliation.EXACT, BigDecimal.valueOf(meilleur.score()), meilleur.explication(), true));
            } else if (note.statut() == RapprochementClassifier.Statut.PROBABLE) {
                demandes.add(new BankReconciliationService.DemandeAutomatique(note.ligne(), meilleur.ligne().getId(), SourceReconciliation.SYSTEM_PROPOSAL,
                        DecisionReconciliation.PROBABLE, BigDecimal.valueOf(meilleur.score()), meilleur.explication(), false));
            }
        }
        // Tout est decide en memoire puis ecrit d'un bloc ; les ecritures prises par un cas certain ne sont plus candidates aux filets suivants.
        reconciliationService.appliquerLot(releve.getEntreprise().getId(), demandes);
        releve.getLignes().stream().map(LigneReleve::getLigneEcritureId).filter(java.util.Objects::nonNull).forEach(dejaPointees::add);

        completerParMatchingSemantique(releve, mouvements, dejaPointees);
        completerParUniversalMatchingEngine(releve);
    }

    /**
     * Universal Matching Engine (mission 2) : dernier filet de secours, sur les lignes que ni
     * l'appariement deterministe ni {@link MatchingSemantiqueService} (banque&lt;-&gt;ecritures
     * uniquement) n'ont su resoudre. Contrairement aux deux etapes precedentes, les candidats ne
     * se limitent plus aux ecritures du compte : factures clients et pieces fournisseur/documents
     * entrent en jeu (cf. MatchingContextBuilder). Desactive par defaut
     * ({@code agent.matching-ia.enabled=false}) : sans activation explicite, ce filtre
     * supplementaire est un no-op complet, comportement identique a avant cette mission.
     * <p>
     * Ne recalcule PAS un {@code dejaPointees} partage avec les etapes precedentes : celles-ci
     * mutent directement {@code LigneReleve.ligneEcritureId}, donc relire l'etat courant des
     * lignes du releve (au lieu de faire confiance a un set intermediaire potentiellement
     * incomplet) est la source de verite la plus sure pour "quelles ecritures sont deja prises".
     */
    private void completerParUniversalMatchingEngine(ReleveBancaire releve) {
        if (!matchingIaActif) {
            return;
        }
        List<LigneReleve> nonRapprochees = releve.getLignes().stream()
                .filter(ligne -> ligne.getLigneEcritureId() == null)
                .toList();
        if (nonRapprochees.isEmpty()) {
            return;
        }

        Entreprise entreprise = releve.getEntreprise();
        CompteComptable compte = releve.getCompte();
        Set<UUID> ecrituresIndisponibles = releve.getLignes().stream()
                .map(LigneReleve::getLigneEcritureId)
                .filter(Objects::nonNull)
                .collect(Collectors.toCollection(HashSet::new));

        int autoMatch = 0;
        int review = 0;
        int noMatch = 0;
        int indisponible = 0;

        for (LigneReleve ligne : nonRapprochees) {
            long debutMs = System.currentTimeMillis();
            MatchingRequestDTO requete = matchingContextBuilder.construire(entreprise, compte, ligne, ecrituresIndisponibles);
            Optional<MatchingResponseDTO> reponse = aiServiceClient.analyzeMatching(requete);
            meterRegistry.timer("matching.ia.latence").record(Duration.ofMillis(System.currentTimeMillis() - debutMs));

            ResultatDecision resultat = matchDecisionEngine.statuer(entreprise, ligne, requete, reponse, ecrituresIndisponibles);
            meterRegistry.counter("matching.ia.decision", "type", resultat.decision().name()).increment();
            // Missing Evidence Engine (mission P0) : traduit la decision deja calculee en Finding
            // exploitable par l'Inbox - jamais un second appel IA, uniquement une interpretation
            // Java de ce que MatchDecisionEngine a deja statue. La boucle de resolution WhatsApp
            // est un point de decision separe (section 12) : elle ne promeut en DemandePiece que
            // le sous-ensemble etroit de Finding qu'elle juge justifie (cf. sa javadoc).
            missingEvidenceAnalyzer.analyser(entreprise, ligne, requete, resultat)
                    .ifPresent(finding -> whatsAppResolutionService.resoudreSiPreuveManquante(finding, ligne));

            switch (resultat.decision()) {
                case MATCH_AUTO -> {
                    autoMatch++;
                    ecrituresIndisponibles.add(resultat.ligneEcriturePointee());
                }
                case MATCH_REVIEW -> review++;
                case NO_MATCH -> noMatch++;
                case AI_UNAVAILABLE -> indisponible++;
            }
        }

        log.info("Universal Matching Engine (releve {}) : {} ligne(s) analysee(s) - {} auto, {} a revoir, "
                        + "{} sans correspondance, {} IA indisponible",
                releve.getId(), nonRapprochees.size(), autoMatch, review, noMatch, indisponible);
    }

    /** Filet de secours (Sprint 6) sur les lignes que l'appariement par montant exact ci-dessus
     * n'a pas su resoudre : cf. {@link MatchingSemantiqueService} pour le detail du garde-fou de
     * confiance. N'agit que sur ce qui reste non rapproche a ce stade, ne modifie jamais un
     * pointage deja pose par la boucle deterministe. */
    private void completerParMatchingSemantique(ReleveBancaire releve, List<LigneEcriture> mouvements,
                                                  Set<UUID> dejaPointees) {
        List<LigneReleve> nonRapprochees = releve.getLignes().stream()
                .filter(ligne -> ligne.getLigneEcritureId() == null)
                .toList();
        if (nonRapprochees.isEmpty()) {
            return;
        }
        List<LigneEcriture> candidatsRestants = mouvements.stream()
                .filter(le -> !dejaPointees.contains(le.getId()))
                .toList();

        List<AppariementSemantique> appariements = matchingSemantiqueService.proposerAppariements(
                nonRapprochees, candidatsRestants);

        Map<UUID, LigneReleve> ligneReleveParId = nonRapprochees.stream()
                .collect(Collectors.toMap(LigneReleve::getId, ligne -> ligne));
        for (AppariementSemantique appariement : appariements) {
            reconciliationService.confirmerParLeSysteme(ligneReleveParId.get(appariement.ligneReleveId()), appariement.ligneEcritureId(),
                    SourceReconciliation.AI_PROPOSAL, DecisionReconciliation.PROBABLE, BigDecimal.valueOf(appariement.confiance() * 100),
                    appariement.justification());
            log.info("Rapprochement semantique applique : releve={} -> ecriture={} (confiance={}, {})",
                    appariement.ligneReleveId(), appariement.ligneEcritureId(), appariement.confiance(),
                    appariement.justification());
        }
    }

    /** Les lignes d'ecriture pointees sont recuperees en une seule requete groupee plutot qu'une
     * par ligne de releve, pour eviter un N+1 sur les releves contenant beaucoup de mouvements. */
    private ReleveBancaireDto toDto(ReleveBancaire releve) {
        BigDecimal soldeComptable = ligneEcritureRepository.soldeNetAvant(releve.getCompte().getId(), releve.getDateFin().plusDays(1));
        BigDecimal ecart = releve.getSoldeFin() != null && soldeComptable != null ? releve.getSoldeFin().subtract(soldeComptable) : null;

        List<UUID> ligneEcritureIds = releve.getLignes().stream()
                .map(LigneReleve::getLigneEcritureId)
                .filter(java.util.Objects::nonNull)
                .toList();
        Map<UUID, LigneEcriture> ligneEcritureParId = ligneEcritureRepository.findAllById(ligneEcritureIds).stream()
                .collect(Collectors.toMap(LigneEcriture::getId, le -> le));

        List<LigneReleveDto> lignes = releve.getLignes().stream()
                .map(ligne -> toLigneDto(ligne, ligneEcritureParId.get(ligne.getLigneEcritureId())))
                .toList();

        return new ReleveBancaireDto(
                releve.getId(), releve.getCompte().getId(), releve.getCompte().getNumero(), releve.getCompte().getIntitule(),
                releve.getLibelle(), releve.getDateDebut(), releve.getDateFin(),
                releve.getSoldeDebut(), releve.getSoldeFin(), soldeComptable, ecart,
                lignes
        );
    }

    private LigneReleveDto toLigneDto(LigneReleve ligne, LigneEcriture ligneEcriture) {
        return new LigneReleveDto(
                ligne.getId(), ligne.getDateOperation(), ligne.getLibelle(), ligne.getMontant(),
                ligne.getLigneEcritureId() != null,
                ligne.getLigneEcritureId(),
                ligneEcriture != null ? ligneEcriture.getEcriture().getNumeroPiece() : null,
                ligneEcriture != null ? ligneEcriture.getLibelle() : null
        );
    }

    private CompteComptable chargerCompte(UUID entrepriseId, UUID compteId) {
        return compteComptableRepository.findById(compteId)
                .filter(c -> c.getEntreprise().getId().equals(entrepriseId))
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Compte introuvable"));
    }

    private ReleveBancaire chargerReleve(UUID entrepriseId, UUID releveId) {
        ReleveBancaire releve = releveBancaireRepository.findById(releveId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Releve introuvable"));
        if (!releve.getEntreprise().getId().equals(entrepriseId)) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Releve introuvable");
        }
        return releve;
    }

    private LigneReleve chargerLigneReleve(ReleveBancaire releve, UUID ligneReleveId) {
        return releve.getLignes().stream()
                .filter(l -> l.getId().equals(ligneReleveId))
                .findFirst()
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Ligne de releve introuvable"));
    }
}
