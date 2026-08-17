package com.example.comptable.comptabilite.web;

import com.example.comptable.comptabilite.service.LettrageService;
import com.example.comptable.comptabilite.web.dto.LettrageResultDto;
import com.example.comptable.comptabilite.web.dto.LettrerLignesRequest;
import com.example.comptable.security.UtilisateurPrincipal;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/entreprises/{entrepriseId}/lettrage")
@RequiredArgsConstructor
public class LettrageController {

    private final LettrageService lettrageService;

    @PostMapping
    public LettrageResultDto lettrer(@PathVariable UUID entrepriseId, @Valid @RequestBody LettrerLignesRequest request,
                                      @AuthenticationPrincipal UtilisateurPrincipal principal) {
        return lettrageService.lettrer(entrepriseId, request, principal);
    }

    @PostMapping("/automatique")
    public List<LettrageResultDto> lettrerAutomatiquement(@PathVariable UUID entrepriseId, @RequestParam UUID compteId,
                                                            @AuthenticationPrincipal UtilisateurPrincipal principal) {
        return lettrageService.lettrerAutomatiquement(entrepriseId, compteId, principal);
    }

    @DeleteMapping
    public void delettrer(@PathVariable UUID entrepriseId, @RequestParam UUID compteId, @RequestParam String code,
                           @AuthenticationPrincipal UtilisateurPrincipal principal) {
        lettrageService.delettrer(entrepriseId, compteId, code, principal);
    }
}
