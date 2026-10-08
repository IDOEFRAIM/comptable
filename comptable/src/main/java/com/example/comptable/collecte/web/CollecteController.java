package com.example.comptable.collecte.web;

import com.example.comptable.collecte.repository.OutboundMessageRepository;
import com.example.comptable.collecte.service.CollecteService;
import com.example.comptable.collecte.service.CollecteSyntheseService;
import com.example.comptable.demande.service.DemandePieceService;
import com.example.comptable.demande.web.dto.DemandePieceDto;
import com.example.comptable.security.UtilisateurPrincipal;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotEmpty;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

/**
 * Collecte active : travail du CABINET (jamais du role CLIENT). Les routes d'une demande precise (envoyer, relancer, annuler)
 * et la demande groupee (apercu puis envoi) ; le client n'a que le depot et la liste simplifiee de ses demandes.
 */
@RestController
@RequestMapping("/api/entreprises/{entrepriseId}/demandes-pieces")
@RequiredArgsConstructor
public class CollecteController {

    private final CollecteService collecteService;
    private final CollecteSyntheseService syntheseService;
    private final DemandePieceService demandePieceService;
    private final OutboundMessageRepository messageRepository;

    public record DemandeGroupeeRequest(@NotEmpty List<UUID> findingIds, boolean apercuSeulement, Boolean envoyerMaintenant) {
    }

    public record MarquerRecueRequest(String commentaire) {
    }

    /** Un message de la demande, tel que le fournisseur l'a traite (SENT = accepte, jamais « delivre »). */
    public record MessageDto(UUID id, String type, String canal, String statut, int tentatives, OffsetDateTime cree, OffsetDateTime envoye,
                             String derniereErreur, OffsetDateTime prochaineTentative) {
    }

    @GetMapping("/collecte/synthese")
    public CollecteSyntheseService.Synthese synthese(@PathVariable UUID entrepriseId, @AuthenticationPrincipal UtilisateurPrincipal principal) {
        return syntheseService.synthese(entrepriseId, principal);
    }

    @GetMapping("/collecte/a-demander")
    public List<CollecteService.ElementApercu> aDemander(@PathVariable UUID entrepriseId, @AuthenticationPrincipal UtilisateurPrincipal principal) {
        return syntheseService.aDemander(entrepriseId, principal);
    }

    @GetMapping("/collecte/metriques")
    public CollecteSyntheseService.Metriques metriques(@PathVariable UUID entrepriseId, @AuthenticationPrincipal UtilisateurPrincipal principal) {
        return syntheseService.metriques(entrepriseId, principal);
    }

    /** Liste enrichie du dernier message de chaque demande (une requete pour tout le lot). */
    @GetMapping("/collecte")
    public List<DemandePieceDto> collecte(@PathVariable UUID entrepriseId, @AuthenticationPrincipal UtilisateurPrincipal principal) {
        return demandePieceService.lister(entrepriseId, null, principal);
    }

    /** Demande groupee : {@code apercuSeulement=true} ne cree ni n'envoie rien (apercu obligatoire avant l'envoi reel). */
    @PostMapping("/bulk")
    public Object bulk(@PathVariable UUID entrepriseId, @Valid @RequestBody DemandeGroupeeRequest requete,
                       @AuthenticationPrincipal UtilisateurPrincipal principal) {
        if (requete.apercuSeulement()) {
            return collecteService.apercu(entrepriseId, requete.findingIds(), principal);
        }
        return collecteService.demander(entrepriseId, requete.findingIds(), principal, !Boolean.FALSE.equals(requete.envoyerMaintenant()));
    }

    @GetMapping("/{demandeId}/messages")
    public List<MessageDto> messages(@PathVariable UUID entrepriseId, @PathVariable UUID demandeId,
                                     @AuthenticationPrincipal UtilisateurPrincipal principal) {
        collecteService.chargerPourLecture(entrepriseId, demandeId, principal);
        return messageRepository.findParDemande(demandeId).stream()
                .filter(m -> m.getEntrepriseId().equals(entrepriseId))
                .map(m -> new MessageDto(m.getId(), m.getKind().name(), m.getChannel().name(), m.getStatus().name(), m.getAttemptCount(),
                        m.getCreatedAt(), m.getSentAt(), m.getLastError(), m.getNextAttemptAt()))
                .toList();
    }

    @PostMapping("/{demandeId}/envoyer")
    public DemandePieceDto envoyer(@PathVariable UUID entrepriseId, @PathVariable UUID demandeId,
                                   @AuthenticationPrincipal UtilisateurPrincipal principal) {
        return DemandePieceDto.from(collecteService.envoyer(entrepriseId, demandeId, principal));
    }

    @PostMapping("/{demandeId}/relancer")
    public DemandePieceDto relancer(@PathVariable UUID entrepriseId, @PathVariable UUID demandeId,
                                    @AuthenticationPrincipal UtilisateurPrincipal principal) {
        return DemandePieceDto.from(collecteService.relancer(entrepriseId, demandeId, principal));
    }

    @PostMapping("/{demandeId}/marquer-recue")
    public DemandePieceDto marquerRecue(@PathVariable UUID entrepriseId, @PathVariable UUID demandeId,
                                        @RequestBody(required = false) MarquerRecueRequest requete,
                                        @AuthenticationPrincipal UtilisateurPrincipal principal) {
        return DemandePieceDto.from(collecteService.marquerRecue(entrepriseId, demandeId, requete != null ? requete.commentaire() : null, principal));
    }
}
