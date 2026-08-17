package com.example.comptable.recouvrement.web;

import com.example.comptable.recouvrement.CollectionRiskService;
import com.example.comptable.recouvrement.web.dto.CollectionRiskDto;
import com.example.comptable.security.UtilisateurPrincipal;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/entreprises/{entrepriseId}/recouvrement")
@RequiredArgsConstructor
public class CollectionRiskController {

    private final CollectionRiskService collectionRiskService;

    @GetMapping("/risques")
    public List<CollectionRiskDto> risques(@PathVariable UUID entrepriseId,
                                            @AuthenticationPrincipal UtilisateurPrincipal principal) {
        return collectionRiskService.generer(entrepriseId, principal);
    }
}
