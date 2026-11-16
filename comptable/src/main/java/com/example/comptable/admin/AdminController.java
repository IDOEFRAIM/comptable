package com.example.comptable.admin;

import com.example.comptable.admin.AdminDtos.Apercu;
import com.example.comptable.admin.AdminDtos.EvenementAudit;
import com.example.comptable.admin.AdminDtos.MotifRequest;
import com.example.comptable.admin.AdminDtos.Page;
import com.example.comptable.admin.AdminDtos.ReinitialisationAdmin;
import com.example.comptable.admin.AdminDtos.UtilisateurAdmin;
import com.example.comptable.security.UtilisateurPrincipal;
import lombok.RequiredArgsConstructor;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDate;
import java.util.UUID;

/** API d'administration de la plateforme. Réservée au rôle SUPER_ADMIN (SecurityConfig, puis revérifié dans chaque service). */
@RestController
@RequestMapping("/api/admin")
@RequiredArgsConstructor
public class AdminController {

    private final AdminUtilisateurService utilisateurs;
    private final AdminSupervisionService supervision;

    @GetMapping("/apercu")
    public Apercu apercu(@AuthenticationPrincipal UtilisateurPrincipal admin) {
        return supervision.apercu(admin);
    }

    @GetMapping("/utilisateurs")
    public Page<UtilisateurAdmin> utilisateurs(@AuthenticationPrincipal UtilisateurPrincipal admin, @RequestParam(required = false) String q, @RequestParam(required = false) String statut,
                                               @RequestParam(defaultValue = "0") int page, @RequestParam(defaultValue = "25") int taille) {
        return utilisateurs.lister(admin, q, statut, page, taille);
    }

    @PostMapping("/utilisateurs/{id}/suspendre")
    public UtilisateurAdmin suspendre(@AuthenticationPrincipal UtilisateurPrincipal admin, @PathVariable UUID id, @RequestBody MotifRequest corps) {
        return utilisateurs.suspendre(admin, id, corps.motif());
    }

    @PostMapping("/utilisateurs/{id}/reactiver")
    public UtilisateurAdmin reactiver(@AuthenticationPrincipal UtilisateurPrincipal admin, @PathVariable UUID id, @RequestBody MotifRequest corps) {
        return utilisateurs.reactiver(admin, id, corps.motif());
    }

    @PostMapping("/utilisateurs/{id}/deverrouiller")
    public UtilisateurAdmin deverrouiller(@AuthenticationPrincipal UtilisateurPrincipal admin, @PathVariable UUID id, @RequestBody MotifRequest corps) {
        return utilisateurs.deverrouiller(admin, id, corps.motif());
    }

    @PostMapping("/utilisateurs/{id}/revoquer-sessions")
    public UtilisateurAdmin revoquerSessions(@AuthenticationPrincipal UtilisateurPrincipal admin, @PathVariable UUID id, @RequestBody MotifRequest corps) {
        return utilisateurs.revoquerSessions(admin, id, corps.motif());
    }

    @PostMapping("/utilisateurs/{id}/reinitialiser-mot-de-passe")
    public ReinitialisationAdmin reinitialiser(@AuthenticationPrincipal UtilisateurPrincipal admin, @PathVariable UUID id, @RequestBody MotifRequest corps) {
        return utilisateurs.reinitialiserMotDePasse(admin, id, corps.motif());
    }

    @GetMapping("/audit")
    public Page<EvenementAudit> audit(@AuthenticationPrincipal UtilisateurPrincipal admin, @RequestParam(required = false) UUID cabinetId, @RequestParam(required = false) String action,
                                      @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate depuis,
                                      @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate jusqu, @RequestParam(defaultValue = "0") int page,
                                      @RequestParam(defaultValue = "50") int taille) {
        return supervision.audit(admin, cabinetId, action, depuis, jusqu, page, taille);
    }
}
