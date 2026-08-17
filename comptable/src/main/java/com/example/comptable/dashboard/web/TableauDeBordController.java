package com.example.comptable.dashboard.web;

import com.example.comptable.dashboard.service.TableauDeBordService;
import com.example.comptable.dashboard.web.dto.TableauDeBordDto;
import com.example.comptable.security.UtilisateurPrincipal;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

@RestController
@RequestMapping("/api/entreprises/{entrepriseId}/tableau-de-bord")
@RequiredArgsConstructor
public class TableauDeBordController {

    private final TableauDeBordService tableauDeBordService;

    @GetMapping
    public TableauDeBordDto obtenir(@PathVariable UUID entrepriseId,
                                     @AuthenticationPrincipal UtilisateurPrincipal principal) {
        return tableauDeBordService.generer(entrepriseId, principal);
    }
}
