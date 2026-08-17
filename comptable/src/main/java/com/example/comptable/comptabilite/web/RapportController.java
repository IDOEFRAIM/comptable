package com.example.comptable.comptabilite.web;

import com.example.comptable.comptabilite.service.BalanceAgeeService;
import com.example.comptable.comptabilite.service.BalanceService;
import com.example.comptable.comptabilite.service.ExportFecService;
import com.example.comptable.comptabilite.service.GrandLivreService;
import com.example.comptable.comptabilite.service.JournalOperationsService;
import com.example.comptable.comptabilite.web.dto.BalanceAgeeDto;
import com.example.comptable.comptabilite.web.dto.BalanceDto;
import com.example.comptable.comptabilite.web.dto.EcritureComptableDto;
import com.example.comptable.comptabilite.web.dto.FecExportDto;
import com.example.comptable.comptabilite.web.dto.GrandLivreDto;
import com.example.comptable.security.UtilisateurPrincipal;
import lombok.RequiredArgsConstructor;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/entreprises/{entrepriseId}/rapports")
@RequiredArgsConstructor
public class RapportController {

    private final GrandLivreService grandLivreService;
    private final BalanceService balanceService;
    private final JournalOperationsService journalOperationsService;
    private final BalanceAgeeService balanceAgeeService;
    private final ExportFecService exportFecService;

    @GetMapping("/grand-livre")
    public GrandLivreDto grandLivre(
            @PathVariable UUID entrepriseId,
            @RequestParam UUID compteId,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate dateDebut,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate dateFin,
            @AuthenticationPrincipal UtilisateurPrincipal principal) {
        return grandLivreService.generer(entrepriseId, compteId, dateDebut, dateFin, principal);
    }

    @GetMapping("/balance")
    public BalanceDto balance(
            @PathVariable UUID entrepriseId,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate dateDebut,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate dateFin,
            @AuthenticationPrincipal UtilisateurPrincipal principal) {
        return balanceService.generer(entrepriseId, dateDebut, dateFin, principal);
    }

    @GetMapping("/balance-agee")
    public BalanceAgeeDto balanceAgee(
            @PathVariable UUID entrepriseId,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate dateArrete,
            @AuthenticationPrincipal UtilisateurPrincipal principal) {
        return balanceAgeeService.generer(entrepriseId, dateArrete, principal);
    }

    @GetMapping("/fec/{exerciceId}")
    public ResponseEntity<byte[]> exportFec(
            @PathVariable UUID entrepriseId,
            @PathVariable UUID exerciceId,
            @AuthenticationPrincipal UtilisateurPrincipal principal) {
        FecExportDto export = exportFecService.exporter(entrepriseId, exerciceId, principal);
        byte[] contenu = export.contenu().getBytes(StandardCharsets.UTF_8);
        return ResponseEntity.ok()
                .contentType(MediaType.TEXT_PLAIN)
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"" + export.nomFichier() + "\"")
                .body(contenu);
    }

    @GetMapping("/journaux/{journalId}")
    public List<EcritureComptableDto> journal(
            @PathVariable UUID entrepriseId,
            @PathVariable UUID journalId,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate dateDebut,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate dateFin,
            @AuthenticationPrincipal UtilisateurPrincipal principal) {
        return journalOperationsService.generer(entrepriseId, journalId, dateDebut, dateFin, principal);
    }
}
