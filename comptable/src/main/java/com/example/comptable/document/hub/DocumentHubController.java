package com.example.comptable.document.hub;

import com.example.comptable.document.domain.CanalSource;
import com.example.comptable.document.domain.Document;
import com.example.comptable.document.domain.TypeDocument;
import com.example.comptable.document.hub.DocumentHubService.Filtre;
import com.example.comptable.document.hub.DocumentHubService.Tuile;
import com.example.comptable.document.service.DocumentMatcher;
import com.example.comptable.document.service.DocumentTraitementService;
import com.example.comptable.security.UtilisateurPrincipal;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

/**
 * Hub documents : vue d'ensemble et decisions sur les pieces. L'association se choisit parmi des CANDIDATES
 * ({@code /candidats}), jamais en saisissant un identifiant.
 */
@RestController
@RequestMapping("/api/entreprises/{entrepriseId}/documents")
@RequiredArgsConstructor
public class DocumentHubController {

    private final DocumentHubService hubService;
    private final DocumentTraitementService traitementService;

    public record AssocierRequest(@NotBlank String cibleType, @NotNull UUID cibleId) {
    }

    public record DoublonRequest(@NotNull UUID doublonDeId) {
    }

    public record LotRequest(@NotEmpty List<UUID> documentIds) {
    }

    @GetMapping("/hub/synthese")
    public DocumentHubDto.Synthese synthese(@PathVariable UUID entrepriseId, @RequestParam(required = false) String periode,
                                            @AuthenticationPrincipal UtilisateurPrincipal principal) {
        return hubService.synthese(entrepriseId, periode, principal);
    }

    @GetMapping("/hub")
    public DocumentHubDto.Page lister(@PathVariable UUID entrepriseId,
                                      @RequestParam(required = false) Tuile tuile,
                                      @RequestParam(required = false) TypeDocument type,
                                      @RequestParam(required = false) CanalSource canal,
                                      @RequestParam(required = false) UUID tiersId,
                                      @RequestParam(required = false) String periode,
                                      @RequestParam(required = false) String q,
                                      @RequestParam(defaultValue = "0") int page,
                                      @RequestParam(defaultValue = "25") int taille,
                                      @AuthenticationPrincipal UtilisateurPrincipal principal) {
        return hubService.lister(entrepriseId, new Filtre(tuile, type, canal, tiersId, periode, q), page, taille, principal);
    }

    /** Pieces rapprochees automatiquement et pas encore validees : apercu OBLIGATOIRE avant toute validation en masse. */
    @GetMapping("/hub/validation-lot/apercu")
    public List<DocumentHubDto.DocumentSimple> apercuLot(@PathVariable UUID entrepriseId, @AuthenticationPrincipal UtilisateurPrincipal principal) {
        return traitementService.apercuValidationLot(entrepriseId, principal).stream()
                .map(d -> new DocumentHubDto.DocumentSimple(d.getId(), d.getNomFichierOriginal(), d.getCreatedAt(), d.getMontantTtc(),
                        d.getNumeroFacture(), "rapprochee automatiquement (" + d.getCibleType() + ")"))
                .toList();
    }

    @PostMapping("/hub/validation-lot")
    public DocumentTraitementService.LotResultat validerLot(@PathVariable UUID entrepriseId, @Valid @RequestBody LotRequest requete,
                                                            @AuthenticationPrincipal UtilisateurPrincipal principal) {
        return traitementService.validerLot(entrepriseId, requete.documentIds(), principal);
    }

    @GetMapping("/{documentId}/candidats")
    public List<DocumentMatcher.Candidat> candidats(@PathVariable UUID entrepriseId, @PathVariable UUID documentId,
                                                    @AuthenticationPrincipal UtilisateurPrincipal principal) {
        return traitementService.candidats(entrepriseId, documentId, principal);
    }

    @GetMapping("/{documentId}/originaux-possibles")
    public List<DocumentHubDto.DocumentSimple> originauxPossibles(@PathVariable UUID entrepriseId, @PathVariable UUID documentId,
                                                                  @AuthenticationPrincipal UtilisateurPrincipal principal) {
        return hubService.originauxPossibles(entrepriseId, documentId, principal);
    }

    @PostMapping("/{documentId}/associer")
    public Resultat associer(@PathVariable UUID entrepriseId, @PathVariable UUID documentId, @Valid @RequestBody AssocierRequest requete,
                             @AuthenticationPrincipal UtilisateurPrincipal principal) {
        return Resultat.de(traitementService.associer(entrepriseId, documentId, requete.cibleType(), requete.cibleId(), principal));
    }

    @GetMapping("/{documentId}/demandes-candidates")
    public List<DocumentTraitementService.CandidateDemande> demandesCandidates(@PathVariable UUID entrepriseId, @PathVariable UUID documentId,
                                                                               @AuthenticationPrincipal UtilisateurPrincipal principal) {
        return traitementService.candidatsDemandes(entrepriseId, documentId, principal);
    }

    public record AssocierDemandeRequest(@NotNull UUID demandeId) {
    }

    @PostMapping("/{documentId}/associer-demande")
    public Resultat associerDemande(@PathVariable UUID entrepriseId, @PathVariable UUID documentId, @Valid @RequestBody AssocierDemandeRequest requete,
                                    @AuthenticationPrincipal UtilisateurPrincipal principal) {
        return Resultat.de(traitementService.associerDemande(entrepriseId, documentId, requete.demandeId(), principal));
    }

    @PostMapping("/{documentId}/rejeter-proposition")
    public Resultat rejeterProposition(@PathVariable UUID entrepriseId, @PathVariable UUID documentId,
                                       @AuthenticationPrincipal UtilisateurPrincipal principal) {
        return Resultat.de(traitementService.rejeterProposition(entrepriseId, documentId, principal));
    }

    @PostMapping("/{documentId}/marquer-doublon")
    public Resultat marquerDoublon(@PathVariable UUID entrepriseId, @PathVariable UUID documentId, @Valid @RequestBody DoublonRequest requete,
                                   @AuthenticationPrincipal UtilisateurPrincipal principal) {
        return Resultat.de(traitementService.marquerDoublon(entrepriseId, documentId, requete.doublonDeId(), principal));
    }

    @PostMapping("/{documentId}/valider")
    public Resultat valider(@PathVariable UUID entrepriseId, @PathVariable UUID documentId,
                            @AuthenticationPrincipal UtilisateurPrincipal principal) {
        return Resultat.de(traitementService.valider(entrepriseId, documentId, principal));
    }

    @PostMapping("/{documentId}/retraiter")
    public Resultat retraiter(@PathVariable UUID entrepriseId, @PathVariable UUID documentId,
                              @AuthenticationPrincipal UtilisateurPrincipal principal) {
        return Resultat.de(traitementService.retraiter(entrepriseId, documentId, principal));
    }

    /** Etat de la piece apres une action (le Hub recharge ensuite sa liste). */
    public record Resultat(UUID id, String statutRapprochement, String cibleType, UUID cibleId, boolean valide, boolean doublonConfirme) {
        static Resultat de(Document d) {
            return new Resultat(d.getId(), d.getStatutRapprochement().name(), d.getCibleType(), d.getCibleId(), d.getValideAt() != null,
                    d.getDoublonDeId() != null);
        }
    }
}
