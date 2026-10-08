package com.example.comptable.cabinet.support;

import com.example.comptable.security.UtilisateurPrincipal;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Diagnostic de support du cabinet (expert-comptable). Sous /api/cabinet/** : jamais accessible à un CLIENT. */
@RestController
@RequestMapping("/api/cabinet/support")
@RequiredArgsConstructor
public class SupportController {

    private final SupportDiagnosticService service;

    @GetMapping("/diagnostic")
    public SupportDiagnosticService.Diagnostic diagnostic(@AuthenticationPrincipal UtilisateurPrincipal principal) {
        return service.diagnostiquer(principal);
    }
}
