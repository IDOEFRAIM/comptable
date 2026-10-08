package com.example.comptable.identite.web;

import com.example.comptable.identite.service.AuthService;
import com.example.comptable.identite.web.dto.AuthResponse;
import com.example.comptable.identite.web.dto.ChangePasswordRequest;
import com.example.comptable.identite.web.dto.ResetPasswordRequest;
import com.example.comptable.security.UtilisateurPrincipal;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.RequestHeader;
import com.example.comptable.identite.web.dto.LoginRequest;
import com.example.comptable.identite.web.dto.RegisterCabinetRequest;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/auth")
@RequiredArgsConstructor
public class AuthController {

    private final AuthService authService;

    @PostMapping("/login")
    public AuthResponse login(@Valid @RequestBody LoginRequest request, HttpServletRequest http) {
        return authService.login(request, http.getRemoteAddr());
    }

    /** Création d'un cabinet + premier expert : libre en développement, réservée à l'opérateur (en-tête {@code X-Provisioning-Token}) en pilote / production. */
    @PostMapping("/register-cabinet")
    public ResponseEntity<AuthResponse> registerCabinet(@Valid @RequestBody RegisterCabinetRequest request, @RequestHeader(value = "X-Provisioning-Token", required = false) String jeton, HttpServletRequest http) {
        AuthResponse response = authService.registerCabinet(request, jeton, http.getRemoteAddr());
        return ResponseEntity.status(HttpStatus.CREATED).body(response);
    }

    /** Seule route accessible avec un mot de passe temporaire / réinitialisé. */
    @PostMapping("/change-password")
    public AuthResponse changePassword(@Valid @RequestBody ChangePasswordRequest request, @AuthenticationPrincipal UtilisateurPrincipal principal) {
        return authService.changerMotDePasse(principal, request.motDePasseActuel(), request.nouveauMotDePasse());
    }

    /** Public : le jeton à usage unique, remis par l'expert du cabinet, tient lieu d'authentification. */
    @PostMapping("/reset-password")
    public ResponseEntity<Void> resetPassword(@Valid @RequestBody ResetPasswordRequest request, HttpServletRequest http) {
        authService.reinitialiserMotDePasse(request.jeton(), request.nouveauMotDePasse(), http.getRemoteAddr());
        return ResponseEntity.noContent().build();
    }
}
