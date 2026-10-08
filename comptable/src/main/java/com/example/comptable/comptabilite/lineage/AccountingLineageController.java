package com.example.comptable.comptabilite.lineage;

import com.example.comptable.security.UtilisateurPrincipal;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

@RestController
@RequestMapping("/api/entreprises/{entrepriseId}/lineage")
@RequiredArgsConstructor
public class AccountingLineageController {

    private final AccountingLineageService lineageService;
    private final com.example.comptable.banque.BankLineageService bankLineageService;

    /** Ecriture -> proposition -> source (-> document). */
    @GetMapping("/ecritures/{ecritureId}")
    public AccountingLineageDto.DepuisEcriture depuisEcriture(@PathVariable UUID entrepriseId, @PathVariable UUID ecritureId,
                                                              @AuthenticationPrincipal UtilisateurPrincipal principal) {
        return lineageService.depuisEcriture(entrepriseId, ecritureId, principal);
    }

    /** Ecriture <- transaction bancaire rapprochee ; reglement <-> factures affectees (la banque n'est jamais liee directement a une facture). */
    @GetMapping("/ecritures/{ecritureId}/banque")
    public com.example.comptable.banque.BankLineageService.DepuisEcritureBanque depuisEcritureBanque(@PathVariable UUID entrepriseId, @PathVariable UUID ecritureId,
                                                                                                      @AuthenticationPrincipal UtilisateurPrincipal principal) {
        return bankLineageService.depuisEcriture(entrepriseId, ecritureId, principal);
    }

    /** Source metier (DOCUMENT, FACTURE, IMMOBILISATION, MOUVEMENT_STOCK...) -> ses consequences comptables. */
    @GetMapping("/sources/{sourceType}/{sourceId}")
    public AccountingLineageDto.DepuisSource depuisSource(@PathVariable UUID entrepriseId, @PathVariable String sourceType,
                                                          @PathVariable UUID sourceId,
                                                          @AuthenticationPrincipal UtilisateurPrincipal principal) {
        return lineageService.depuisSource(entrepriseId, sourceType, sourceId, principal);
    }

    /** Document -> facture -> propositions -> ecritures. */
    @GetMapping("/documents/{documentId}")
    public AccountingLineageDto.DepuisDocument depuisDocument(@PathVariable UUID entrepriseId, @PathVariable UUID documentId,
                                                              @AuthenticationPrincipal UtilisateurPrincipal principal) {
        return lineageService.depuisDocument(entrepriseId, documentId, principal);
    }
}
