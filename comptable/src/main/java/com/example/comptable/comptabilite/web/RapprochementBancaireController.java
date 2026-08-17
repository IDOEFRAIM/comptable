package com.example.comptable.comptabilite.web;

import com.example.comptable.agent.matching.AiMatchProposalService;
import com.example.comptable.agent.matching.web.dto.AiMatchProposalDto;
import com.example.comptable.comptabilite.service.RapprochementBancaireService;
import com.example.comptable.comptabilite.web.dto.ImporterReleveRequest;
import com.example.comptable.comptabilite.web.dto.LigneReleveDto;
import com.example.comptable.comptabilite.web.dto.ReleveBancaireDto;
import com.example.comptable.security.UtilisateurPrincipal;
import lombok.RequiredArgsConstructor;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.server.ResponseStatusException;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/entreprises/{entrepriseId}/rapprochements")
@RequiredArgsConstructor
public class RapprochementBancaireController {

    private final RapprochementBancaireService rapprochementBancaireService;
    private final AiMatchProposalService aiMatchProposalService;

    @PostMapping
    public ResponseEntity<ReleveBancaireDto> importer(
            @PathVariable UUID entrepriseId,
            @RequestParam UUID compteId,
            @RequestParam String libelle,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate dateDebut,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate dateFin,
            @RequestParam BigDecimal soldeDebut,
            @RequestParam BigDecimal soldeFin,
            @RequestParam MultipartFile fichier,
            @AuthenticationPrincipal UtilisateurPrincipal principal) {
        ImporterReleveRequest request = new ImporterReleveRequest(compteId, libelle, dateDebut, dateFin, soldeDebut, soldeFin);
        ReleveBancaireDto releve = rapprochementBancaireService.importer(entrepriseId, request, fichier, principal);
        return ResponseEntity.status(HttpStatus.CREATED).body(releve);
    }

    @GetMapping
    public List<ReleveBancaireDto> lister(@PathVariable UUID entrepriseId,
                                           @AuthenticationPrincipal UtilisateurPrincipal principal) {
        return rapprochementBancaireService.lister(entrepriseId, principal);
    }

    @GetMapping("/{releveId}")
    public ReleveBancaireDto obtenir(@PathVariable UUID entrepriseId, @PathVariable UUID releveId,
                                      @AuthenticationPrincipal UtilisateurPrincipal principal) {
        return rapprochementBancaireService.obtenir(entrepriseId, releveId, principal);
    }

    @PostMapping("/{releveId}/lignes/{ligneReleveId}/pointer")
    public LigneReleveDto pointer(@PathVariable UUID entrepriseId, @PathVariable UUID releveId,
                                   @PathVariable UUID ligneReleveId, @RequestParam UUID ligneEcritureId,
                                   @AuthenticationPrincipal UtilisateurPrincipal principal) {
        return rapprochementBancaireService.pointer(entrepriseId, releveId, ligneReleveId, ligneEcritureId, principal);
    }

    @PostMapping("/{releveId}/lignes/{ligneReleveId}/depointer")
    public void depointer(@PathVariable UUID entrepriseId, @PathVariable UUID releveId,
                           @PathVariable UUID ligneReleveId, @AuthenticationPrincipal UtilisateurPrincipal principal) {
        rapprochementBancaireService.depointer(entrepriseId, releveId, ligneReleveId, principal);
    }

    /** Correspondance proposee par le Universal Matching Engine pour cette ligne, si elle existe
     * encore et n'a pas deja ete traitee (section 12 de la mission : affichage dans l'ecran de
     * rapprochement existant). {@code releveId} n'est pas utilise pour la recherche elle-meme
     * (la proposition est indexee par ligneReleveId) mais garde l'URL cohente avec le reste du
     * controleur. */
    @GetMapping("/{releveId}/lignes/{ligneReleveId}/proposition-ia")
    public AiMatchProposalDto obtenirPropositionIA(@PathVariable UUID entrepriseId, @PathVariable UUID releveId,
                                                     @PathVariable UUID ligneReleveId,
                                                     @AuthenticationPrincipal UtilisateurPrincipal principal) {
        return aiMatchProposalService.obtenirPourLigne(entrepriseId, ligneReleveId, principal)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Aucune proposition IA pour cette ligne"));
    }

    @PostMapping("/propositions-ia/{propositionId}/accepter")
    public AiMatchProposalDto accepterPropositionIA(@PathVariable UUID entrepriseId, @PathVariable UUID propositionId,
                                                      @AuthenticationPrincipal UtilisateurPrincipal principal) {
        return aiMatchProposalService.accepter(entrepriseId, propositionId, principal);
    }

    @PostMapping("/propositions-ia/{propositionId}/rejeter")
    public AiMatchProposalDto rejeterPropositionIA(@PathVariable UUID entrepriseId, @PathVariable UUID propositionId,
                                                     @RequestParam(required = false) String motif,
                                                     @AuthenticationPrincipal UtilisateurPrincipal principal) {
        return aiMatchProposalService.rejeter(entrepriseId, propositionId, motif, principal);
    }
}
