package com.example.comptable.cabinet.web;

import com.example.comptable.cabinet.InboxService;
import com.example.comptable.cabinet.web.dto.CollaborateurDto;
import com.example.comptable.cabinet.web.dto.InboxItemDto;
import com.example.comptable.cabinet.web.dto.WorkloadDto;
import com.example.comptable.security.UtilisateurPrincipal;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/** Mission P1 (section 12) : "MES TACHES" - Finding + DemandePiece unifies, jamais un nouveau
 * modele de donnees (voir {@link InboxService}). */
@RestController
@RequestMapping("/api/cabinet")
@RequiredArgsConstructor
public class InboxController {

    private final InboxService inboxService;

    @GetMapping("/inbox")
    public List<InboxItemDto> inbox(@RequestParam(name = "assigneAMoi", defaultValue = "false") boolean assigneAMoi,
                                     @AuthenticationPrincipal UtilisateurPrincipal principal) {
        return inboxService.lister(principal, assigneAMoi);
    }

    @GetMapping("/charge-collaborateurs")
    public List<WorkloadDto> chargeCollaborateurs(@AuthenticationPrincipal UtilisateurPrincipal principal) {
        return inboxService.chargeParCollaborateur(principal);
    }

    @GetMapping("/collaborateurs")
    public List<CollaborateurDto> collaborateurs(@AuthenticationPrincipal UtilisateurPrincipal principal) {
        return inboxService.listerCollaborateurs(principal);
    }
}
