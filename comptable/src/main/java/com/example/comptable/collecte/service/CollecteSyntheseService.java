package com.example.comptable.collecte.service;

import com.example.comptable.agent.finding.domain.Finding;
import com.example.comptable.agent.finding.repository.FindingRepository;
import com.example.comptable.collecte.domain.StatutMessage;
import com.example.comptable.collecte.repository.OutboundMessageRepository;
import com.example.comptable.controle.ControlCodes;
import com.example.comptable.controle.ControlRunService;
import com.example.comptable.demande.domain.DemandePiece;
import com.example.comptable.demande.domain.StatutDemandePiece;
import com.example.comptable.demande.repository.DemandePieceRepository;
import com.example.comptable.document.domain.StatutDemandeDocument;
import com.example.comptable.document.repository.DocumentRepository;
import com.example.comptable.entreprise.domain.Entreprise;
import com.example.comptable.entreprise.service.EntrepriseAccessService;
import com.example.comptable.security.UtilisateurPrincipal;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/** Vue d'ensemble de la collecte pour le cabinet (compteurs, elements a demander) et metriques produit mesurables. */
@Service
public class CollecteSyntheseService {

    private static final EnumSet<StatutDemandePiece> OUVERTES = EnumSet.of(StatutDemandePiece.A_ENVOYER, StatutDemandePiece.EN_ATTENTE,
            StatutDemandePiece.RECU, StatutDemandePiece.ECHEC_ENVOI);

    /**
     * @param piecesManquantes operations sans justificatif (constats actifs)   @param aDemander manquantes sans demande ouverte
     * @param aEnvoyer demandes en boite d'envoi                                 @param demandesEnvoyees parties et encore ouvertes
     * @param enAttenteClient demandes parties, sans piece                       @param recues pieces recues, demande encore ouverte (a confirmer)
     * @param relancesNecessaires relances dues maintenant                       @param sansContact demandes impossibles a envoyer (aucun contact / numero invalide)
     * @param echecsEnvoi non envoyees apres les tentatives                      @param sansReponse politique de relance epuisee
     */
    public record Synthese(long piecesManquantes, long aDemander, long aEnvoyer, long demandesEnvoyees, long enAttenteClient, long recues,
                           long relancesNecessaires, long sansContact, long echecsEnvoi, long sansReponse, long piecesAConfirmer,
                           long piecesAmbigues) {
    }

    public record Metriques(long piecesManquantes, long demandesCreees, Double pourcentageEnvoyeesAutomatiquement, Double delaiMoyenReceptionHeures,
                            Double nombreMoyenRelances, Double pourcentageRapprocheesAutomatiquement, Double tauxEchecEnvoi,
                            long demandesNecessitantUneIntervention) {
    }

    private final EntrepriseAccessService entrepriseAccessService;
    private final FindingRepository findingRepository;
    private final DemandePieceRepository demandePieceRepository;
    private final DocumentRepository documentRepository;
    private final OutboundMessageRepository messageRepository;
    private final ControlRunService controlRunService;
    private final CollecteConfig config;

    public CollecteSyntheseService(EntrepriseAccessService entrepriseAccessService, FindingRepository findingRepository,
                                   DemandePieceRepository demandePieceRepository, DocumentRepository documentRepository,
                                   OutboundMessageRepository messageRepository, ControlRunService controlRunService, CollecteConfig config) {
        this.entrepriseAccessService = entrepriseAccessService;
        this.findingRepository = findingRepository;
        this.demandePieceRepository = demandePieceRepository;
        this.documentRepository = documentRepository;
        this.messageRepository = messageRepository;
        this.controlRunService = controlRunService;
        this.config = config;
    }

    @Transactional
    public Synthese synthese(UUID entrepriseId, UtilisateurPrincipal principal) {
        Entreprise entreprise = entrepriseAccessService.verifierAcces(entrepriseId, principal);
        controlRunService.executerSiPerime(entreprise);
        OffsetDateTime maintenant = OffsetDateTime.now();

        List<DemandePiece> demandes = demandePieceRepository.findOuvertes(entrepriseId, OUVERTES);
        long manquantes = 0;
        Set<UUID> ecrituresManquantes = new HashSet<>();
        long agregees = 0;
        for (Finding f : findingRepository.findActifsParCodes(entrepriseId, List.of(ControlCodes.MISSING_DOCUMENT))) {
            if ("ECRITURE_COMPTABLE".equals(f.getSourceType())) {
                manquantes++;
                ecrituresManquantes.add(UUID.fromString(f.getSourceId()));
            } else {
                Object n = f.getEvidenceJson() != null ? f.getEvidenceJson().get("nbEcritures") : null;
                agregees += n instanceof Number nombre ? nombre.longValue() : 1;
            }
        }
        Set<UUID> ecrituresDemandees = new HashSet<>();
        demandes.stream().filter(d -> d.getEcritureId() != null).forEach(d -> ecrituresDemandees.add(d.getEcritureId()));
        long aDemander = ecrituresManquantes.stream().filter(e -> !ecrituresDemandees.contains(e)).count() + agregees;

        long aEnvoyer = comptes(demandes, StatutDemandePiece.A_ENVOYER);
        long enAttente = comptes(demandes, StatutDemandePiece.EN_ATTENTE);
        long recues = comptes(demandes, StatutDemandePiece.RECU);
        long relances = demandes.stream().filter(d -> d.getStatut() == StatutDemandePiece.EN_ATTENTE && d.getProchaineRelanceAt() != null
                && !d.getProchaineRelanceAt().isAfter(maintenant)).count();
        long sansContact = demandes.stream().filter(d -> d.getStatut() == StatutDemandePiece.ECHEC_ENVOI
                && (ContactResolver.NO_CONTACT.equals(d.getMotifBlocage()) || ContactResolver.INVALID_CONTACT.equals(d.getMotifBlocage()))).count();
        long echecs = comptes(demandes, StatutDemandePiece.ECHEC_ENVOI) - sansContact;
        long sansReponse = demandes.stream().filter(d -> d.getStatut() == StatutDemandePiece.EN_ATTENTE && d.getProchaineRelanceAt() == null
                && d.getSentAt() != null && d.getNbRelances() >= config.maxRelances()).count();
        long envoyees = demandes.stream().filter(d -> d.getSentAt() != null && (d.getStatut() == StatutDemandePiece.EN_ATTENTE || d.getStatut() == StatutDemandePiece.RECU)).count();
        long aConfirmer = documentRepository.rechercherParStatutDemande(entrepriseId, StatutDemandeDocument.A_CONFIRMER).size();
        long ambigues = documentRepository.rechercherParStatutDemande(entrepriseId, StatutDemandeDocument.AMBIGU).size();
        return new Synthese(manquantes + agregees, aDemander, aEnvoyer, envoyees, enAttente, recues, relances, sansContact, echecs, sansReponse,
                aConfirmer, ambigues);
    }

    /** Operations sans justificatif qu'aucune demande ouverte ne couvre : la liste proposee pour « Demander les pieces ». */
    @Transactional(readOnly = true)
    public List<CollecteService.ElementApercu> aDemander(UUID entrepriseId, UtilisateurPrincipal principal) {
        entrepriseAccessService.verifierAcces(entrepriseId, principal);
        Set<UUID> demandees = new HashSet<>();
        demandePieceRepository.findOuvertes(entrepriseId, OUVERTES).stream().filter(d -> d.getEcritureId() != null)
                .forEach(d -> demandees.add(d.getEcritureId()));
        List<CollecteService.ElementApercu> elements = new ArrayList<>();
        for (Finding f : findingRepository.findActifsParCodes(entrepriseId, List.of(ControlCodes.MISSING_DOCUMENT))) {
            if (!"ECRITURE_COMPTABLE".equals(f.getSourceType()) || demandees.contains(UUID.fromString(f.getSourceId()))) {
                continue;
            }
            Map<String, Object> ev = f.getEvidenceJson() != null ? f.getEvidenceJson() : Map.of();
            LocalDate date = ev.get("dateOperation") != null ? LocalDate.parse(String.valueOf(ev.get("dateOperation"))) : null;
            String tiers = ev.get("tiersNom") != null ? String.valueOf(ev.get("tiersNom")) : null;
            elements.add(new CollecteService.ElementApercu(f.getId(), tiers != null ? "Achat aupres de " + tiers : "Operation",
                    ev.get("montant") != null ? new BigDecimal(String.valueOf(ev.get("montant"))) : null, date, tiers, false));
        }
        elements.sort(java.util.Comparator.comparing(CollecteService.ElementApercu::date, java.util.Comparator.nullsLast(java.util.Comparator.naturalOrder())));
        return elements;
    }

    /** Donnees mesurables (pas de tableau de bord) : de quoi savoir si la collecte tient sa promesse. */
    @Transactional(readOnly = true)
    public Metriques metriques(UUID entrepriseId, UtilisateurPrincipal principal) {
        entrepriseAccessService.verifierAcces(entrepriseId, principal);
        List<DemandePiece> toutes = demandePieceRepository.findByEntrepriseIdOrderByCreatedAtDesc(entrepriseId).stream()
                .filter(d -> d.getMotif() != null).toList();
        long manquantes = findingRepository.findActifsParCodes(entrepriseId, List.of(ControlCodes.MISSING_DOCUMENT)).size();
        long envoyees = toutes.stream().filter(d -> d.getSentAt() != null).count();
        Double pctEnvoyees = toutes.isEmpty() ? null : 100.0 * envoyees / toutes.size();
        List<DemandePiece> recues = toutes.stream().filter(d -> d.getReceivedAt() != null).toList();
        Double delai = recues.stream().filter(d -> d.getSentAt() != null)
                .mapToDouble(d -> Duration.between(d.getSentAt(), d.getReceivedAt()).toMinutes() / 60.0).average().stream().boxed().findFirst().orElse(null);
        Double relancesMoyennes = recues.isEmpty() ? null : recues.stream().mapToInt(DemandePiece::getNbRelances).average().orElse(0);
        List<DemandePiece> resolues = toutes.stream().filter(d -> d.getResoluPar() != null).toList();
        Double pctAuto = resolues.isEmpty() ? null : 100.0 * resolues.stream().filter(d -> "SYSTEME".equals(d.getResoluPar())).count() / resolues.size();
        long envoyesMessages = messageRepository.countByEntrepriseIdAndStatus(entrepriseId, StatutMessage.SENT);
        long echecsMessages = messageRepository.countByEntrepriseIdAndStatus(entrepriseId, StatutMessage.FAILED);
        Double tauxEchec = envoyesMessages + echecsMessages == 0 ? null : 100.0 * echecsMessages / (envoyesMessages + echecsMessages);
        long interventions = toutes.stream().filter(d -> d.getStatut() == StatutDemandePiece.ECHEC_ENVOI
                || (d.getStatut() == StatutDemandePiece.EN_ATTENTE && d.getProchaineRelanceAt() == null && d.getSentAt() != null
                && d.getNbRelances() >= config.maxRelances())).count()
                + documentRepository.rechercherParStatutDemande(entrepriseId, StatutDemandeDocument.A_CONFIRMER).size()
                + documentRepository.rechercherParStatutDemande(entrepriseId, StatutDemandeDocument.AMBIGU).size();
        return new Metriques(manquantes, toutes.size(), pctEnvoyees, delai, relancesMoyennes, pctAuto, tauxEchec, interventions);
    }

    private static long comptes(List<DemandePiece> demandes, StatutDemandePiece statut) {
        return demandes.stream().filter(d -> d.getStatut() == statut).count();
    }
}
