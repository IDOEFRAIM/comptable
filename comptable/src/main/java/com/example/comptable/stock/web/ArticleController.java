package com.example.comptable.stock.web;

import com.example.comptable.security.UtilisateurPrincipal;
import com.example.comptable.stock.service.ArticleService;
import com.example.comptable.stock.web.dto.ArticleDto;
import com.example.comptable.stock.web.dto.CreerArticleRequest;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/entreprises/{entrepriseId}/articles")
@RequiredArgsConstructor
public class ArticleController {

    private final ArticleService articleService;

    @PostMapping
    public ResponseEntity<ArticleDto> creer(@PathVariable UUID entrepriseId, @Valid @RequestBody CreerArticleRequest request,
                                             @AuthenticationPrincipal UtilisateurPrincipal principal) {
        ArticleDto article = articleService.creer(entrepriseId, request, principal);
        return ResponseEntity.status(HttpStatus.CREATED).body(article);
    }

    /** Catalogue + etat des stocks (quantite, PUMP, valeur) en une seule liste. */
    @GetMapping
    public List<ArticleDto> lister(@PathVariable UUID entrepriseId, @AuthenticationPrincipal UtilisateurPrincipal principal) {
        return articleService.lister(entrepriseId, principal);
    }
}
