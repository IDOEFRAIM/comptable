package com.example.comptable.collecte.web;

import com.example.comptable.collecte.service.DepotClientService;
import com.example.comptable.security.RateLimiter;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

/** Depot par lien opaque : seul endpoint public de la collecte (voir {@link DepotClientService}). */
@RestController
@RequestMapping("/api/depot")
@RequiredArgsConstructor
public class DepotPublicController {

    private final DepotClientService depotService;
    private final RateLimiter rateLimiter;

    /** Lecture : 60 consultations / 10 min par adresse (empêche de deviner des jetons en rafale). */
    @GetMapping("/{jeton}")
    public DepotClientService.Informations informations(@PathVariable String jeton, HttpServletRequest http) {
        rateLimiter.tenter("depot-lecture|" + http.getRemoteAddr(), 60, 600);
        return depotService.informations(jeton);
    }

    /** Dépôt : 20 envois / 10 min par adresse. */
    @PostMapping("/{jeton}")
    public ResponseEntity<Void> deposer(@PathVariable String jeton, @RequestParam MultipartFile fichier, HttpServletRequest http) {
        rateLimiter.tenter("depot-envoi|" + http.getRemoteAddr(), 20, 600);
        depotService.deposer(jeton, fichier);
        return ResponseEntity.status(HttpStatus.ACCEPTED).build();
    }
}
