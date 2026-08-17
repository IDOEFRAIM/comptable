package com.example.comptable.identite.web;

import com.example.comptable.identite.service.AuthService;
import com.example.comptable.identite.web.dto.AuthResponse;
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
    public AuthResponse login(@Valid @RequestBody LoginRequest request) {
        return authService.login(request);
    }

    @PostMapping("/register-cabinet")
    public ResponseEntity<AuthResponse> registerCabinet(@Valid @RequestBody RegisterCabinetRequest request) {
        AuthResponse response = authService.registerCabinet(request);
        return ResponseEntity.status(HttpStatus.CREATED).body(response);
    }
}
