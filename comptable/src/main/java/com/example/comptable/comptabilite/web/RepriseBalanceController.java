package com.example.comptable.comptabilite.web;

import com.example.comptable.comptabilite.reprise.RepriseBalanceService;
import com.example.comptable.comptabilite.reprise.RepriseDtos.ApercuReprise;
import com.example.comptable.comptabilite.reprise.RepriseDtos.ResultatReprise;
import com.example.comptable.security.UtilisateurPrincipal;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.server.ResponseStatusException;

import java.io.IOException;
import java.util.UUID;

/** Reprise d'une balance d'ouverture (Excel ou CSV) : aperçu sans rien écrire, puis import en écriture d'à-nouveaux en brouillon. */
@RestController
@RequestMapping("/api/entreprises/{entrepriseId}/reprise-balance")
@RequiredArgsConstructor
public class RepriseBalanceController {

    private final RepriseBalanceService service;

    @PostMapping(value = "/apercu", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ApercuReprise apercu(@PathVariable UUID entrepriseId, @RequestParam("fichier") MultipartFile fichier, @RequestParam(required = false) UUID exerciceId,
                                @AuthenticationPrincipal UtilisateurPrincipal principal) {
        return service.apercu(entrepriseId, octets(fichier), fichier.getOriginalFilename(), exerciceId, principal);
    }

    @PostMapping(consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @ResponseStatus(HttpStatus.CREATED)
    public ResultatReprise importer(@PathVariable UUID entrepriseId, @RequestParam("fichier") MultipartFile fichier, @RequestParam(required = false) UUID exerciceId,
                                    @AuthenticationPrincipal UtilisateurPrincipal principal) {
        return service.importer(entrepriseId, octets(fichier), fichier.getOriginalFilename(), exerciceId, principal);
    }

    private static byte[] octets(MultipartFile fichier) {
        try {
            return fichier.getBytes();
        } catch (IOException e) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Le fichier n'a pas pu être lu");
        }
    }
}
