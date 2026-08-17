package com.example.comptable.agent.relance;

import com.example.comptable.agent.relance.domain.RelancePieceEnvoyee;
import com.example.comptable.agent.relance.domain.StatutRelance;
import com.example.comptable.agent.relance.repository.RelancePieceEnvoyeeRepository;
import com.example.comptable.agent.relance.web.dto.RelancePieceEnvoyeeDto;
import com.example.comptable.demande.domain.DemandePiece;
import com.example.comptable.demande.domain.StatutDemandePiece;
import com.example.comptable.demande.repository.DemandePieceRepository;
import com.example.comptable.entreprise.domain.UtilisateurEntreprise;
import com.example.comptable.entreprise.repository.UtilisateurEntrepriseRepository;
import com.example.comptable.entreprise.service.EntrepriseAccessService;
import com.example.comptable.identite.domain.Utilisateur;
import com.example.comptable.identite.repository.UtilisateurRepository;
import com.example.comptable.notification.domain.TypeNotification;
import com.example.comptable.notification.service.NotificationService;
import com.example.comptable.security.UtilisateurPrincipal;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * Detecte les {@code DemandePiece} en retard (EN_ATTENTE, date limite depassee) et declenche une
 * relance WhatsApp vers chaque client rattache a l'entreprise concernee. Le "seuil" mentionne dans
 * la demande fonctionnelle est directement {@code DemandePiece.dateLimite} : le cabinet la fixe
 * deja explicitement au moment de la demande (cf. DemandePieceService.creer), donc la detection
 * n'a pas besoin d'une heuristique separee ("X jours avant la fin du mois") - la date limite EST
 * le seuil, par construction.
 */
@Service
public class RelancePieceService {

    private static final Logger log = LoggerFactory.getLogger(RelancePieceService.class);

    private final DemandePieceRepository demandePieceRepository;
    private final UtilisateurEntrepriseRepository utilisateurEntrepriseRepository;
    private final UtilisateurRepository utilisateurRepository;
    private final RelancePieceEnvoyeeRepository relancePieceEnvoyeeRepository;
    private final WhatsAppCloudApiClient whatsAppCloudApiClient;
    private final NotificationService notificationService;
    private final EntrepriseAccessService entrepriseAccessService;
    private final String nomTemplate;
    private final String langueTemplate;

    public RelancePieceService(DemandePieceRepository demandePieceRepository,
                                UtilisateurEntrepriseRepository utilisateurEntrepriseRepository,
                                UtilisateurRepository utilisateurRepository,
                                RelancePieceEnvoyeeRepository relancePieceEnvoyeeRepository,
                                WhatsAppCloudApiClient whatsAppCloudApiClient,
                                NotificationService notificationService,
                                EntrepriseAccessService entrepriseAccessService,
                                @Value("${relance.template-name:relance_pieces_comptables}") String nomTemplate,
                                @Value("${relance.template-language:fr}") String langueTemplate) {
        this.demandePieceRepository = demandePieceRepository;
        this.utilisateurEntrepriseRepository = utilisateurEntrepriseRepository;
        this.utilisateurRepository = utilisateurRepository;
        this.entrepriseAccessService = entrepriseAccessService;
        this.relancePieceEnvoyeeRepository = relancePieceEnvoyeeRepository;
        this.whatsAppCloudApiClient = whatsAppCloudApiClient;
        this.notificationService = notificationService;
        this.nomTemplate = nomTemplate;
        this.langueTemplate = langueTemplate;
    }

    @Transactional
    public RelanceResumeDto executerRelancesDuJour() {
        List<DemandePiece> enRetard = demandePieceRepository.findEnRetard(StatutDemandePiece.EN_ATTENTE, LocalDate.now());
        if (enRetard.isEmpty()) {
            return new RelanceResumeDto(0, 0, 0);
        }

        Map<UUID, List<UtilisateurEntreprise>> clientsParEntreprise = chargerClientsParEntreprise(enRetard);
        Map<UUID, Utilisateur> utilisateursParId = chargerUtilisateurs(clientsParEntreprise);
        OffsetDateTime seuilAntiSpam = OffsetDateTime.now().minusDays(1);

        int envoyees = 0;
        int echecs = 0;
        int ignorees = 0;

        for (DemandePiece demande : enRetard) {
            UUID entrepriseId = demande.getEntreprise().getId();
            List<UtilisateurEntreprise> clients = clientsParEntreprise.getOrDefault(entrepriseId, List.of());
            if (clients.isEmpty()) {
                log.warn("Demande {} en retard mais aucun client rattache a l'entreprise {} : relance impossible",
                        demande.getId(), entrepriseId);
                ignorees++;
                continue;
            }
            if (relancePieceEnvoyeeRepository.existsByDemandePieceIdAndEnvoyeAtAfter(demande.getId(), seuilAntiSpam)) {
                ignorees++;
                continue;
            }

            for (UtilisateurEntreprise lien : clients) {
                Utilisateur client = utilisateursParId.get(lien.getId().getUtilisateurId());
                if (client == null || client.getTelephone() == null || client.getTelephone().isBlank()) {
                    log.warn("Client {} de l'entreprise {} sans numero de telephone : relance impossible",
                            lien.getId().getUtilisateurId(), entrepriseId);
                    ignorees++;
                    continue;
                }
                if (relancerUnClient(demande, client)) {
                    envoyees++;
                } else {
                    echecs++;
                }
            }
        }

        return new RelanceResumeDto(envoyees, echecs, ignorees);
    }

    private Map<UUID, List<UtilisateurEntreprise>> chargerClientsParEntreprise(List<DemandePiece> demandes) {
        Set<UUID> entrepriseIds = demandes.stream().map(d -> d.getEntreprise().getId()).collect(Collectors.toSet());
        return utilisateurEntrepriseRepository.findByIdEntrepriseIdIn(entrepriseIds).stream()
                .collect(Collectors.groupingBy(ue -> ue.getId().getEntrepriseId()));
    }

    private Map<UUID, Utilisateur> chargerUtilisateurs(Map<UUID, List<UtilisateurEntreprise>> clientsParEntreprise) {
        Set<UUID> utilisateurIds = clientsParEntreprise.values().stream()
                .flatMap(List::stream)
                .map(ue -> ue.getId().getUtilisateurId())
                .collect(Collectors.toSet());
        return utilisateurRepository.findAllById(utilisateurIds).stream()
                .collect(Collectors.toMap(Utilisateur::getId, u -> u));
    }

    private boolean relancerUnClient(DemandePiece demande, Utilisateur client) {
        String numeroNormalise = client.getTelephone().replaceAll("[^0-9]", "");

        RelancePieceEnvoyee historique = new RelancePieceEnvoyee();
        historique.setDemandePieceId(demande.getId());
        historique.setEntrepriseId(demande.getEntreprise().getId());
        historique.setUtilisateurClientId(client.getId());
        historique.setTelephone(numeroNormalise);

        List<String> parametresCorps = List.of(
                nomAffiche(client),
                demande.getEntreprise().getRaisonSociale(),
                demande.getLibelle());

        try {
            String messageId = whatsAppCloudApiClient.envoyerTemplate(numeroNormalise, nomTemplate, langueTemplate,
                    parametresCorps);
            historique.setStatut(StatutRelance.ENVOYEE);
            historique.setWhatsappMessageId(messageId);
            relancePieceEnvoyeeRepository.save(historique);

            notifierComptable(demande, client, true);
            log.info("Relance WhatsApp envoyee pour la demande {} (client {}, message {})",
                    demande.getId(), client.getId(), messageId);
            return true;
        } catch (Exception e) {
            historique.setStatut(StatutRelance.ECHEC);
            historique.setMotifEchec(tronquer(e.getMessage()));
            relancePieceEnvoyeeRepository.save(historique);

            notifierComptable(demande, client, false);
            log.error("Echec de la relance WhatsApp pour la demande {} (client {}) : {}",
                    demande.getId(), client.getId(), e.getMessage());
            return false;
        }
    }

    /** Notifie (in-app, cloche existante) le collaborateur a l'origine de la demande - c'est lui,
     * pas le client, qui doit voir "j'ai relance ce client" depuis son tableau de bord. Si la
     * demande n'a pas de demandeur trace (ancienne donnee), on abandonne silencieusement plutot
     * que de planter la relance elle-meme sur un simple manque de tracabilite. */
    private void notifierComptable(DemandePiece demande, Utilisateur client, boolean succes) {
        if (demande.getDemandeePar() == null) {
            return;
        }
        String contenu = succes
                ? "Relance WhatsApp envoyee a " + nomAffiche(client) + " pour \"" + demande.getLibelle() + "\" ("
                        + demande.getEntreprise().getRaisonSociale() + ")"
                : "Echec de la relance WhatsApp pour \"" + demande.getLibelle() + "\" ("
                        + demande.getEntreprise().getRaisonSociale() + ")";
        notificationService.notifier(demande.getDemandeePar(), TypeNotification.RELANCE_PIECE, contenu);
    }

    private String nomAffiche(Utilisateur utilisateur) {
        return utilisateur.getPrenom() + " " + utilisateur.getNom();
    }

    private String tronquer(String message) {
        if (message == null) {
            return null;
        }
        return message.length() > 500 ? message.substring(0, 500) : message;
    }

    /** Historique des relances d'une entreprise, pour affichage dashboard (Sprint 5, point 4). */
    @Transactional(readOnly = true)
    public List<RelancePieceEnvoyeeDto> listerHistorique(UUID entrepriseId, UtilisateurPrincipal principal) {
        entrepriseAccessService.verifierAcces(entrepriseId, principal);
        return relancePieceEnvoyeeRepository.findByEntrepriseIdOrderByEnvoyeAtDesc(entrepriseId).stream()
                .map(RelancePieceEnvoyeeDto::from)
                .toList();
    }
}
