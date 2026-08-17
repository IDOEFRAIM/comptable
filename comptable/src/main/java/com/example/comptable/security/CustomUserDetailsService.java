package com.example.comptable.security;

import com.example.comptable.identite.domain.Utilisateur;
import com.example.comptable.identite.repository.UtilisateurRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class CustomUserDetailsService implements UserDetailsService {

    private final UtilisateurRepository utilisateurRepository;

    @Override
    public UtilisateurPrincipal loadUserByUsername(String email) {
        Utilisateur utilisateur = utilisateurRepository.findByEmail(email)
                .orElseThrow(() -> new UsernameNotFoundException("Identifiants invalides"));
        return new UtilisateurPrincipal(utilisateur);
    }

    public UtilisateurPrincipal loadUserById(String id) {
        Utilisateur utilisateur = utilisateurRepository.findById(UUID.fromString(id))
                .orElseThrow(() -> new UsernameNotFoundException("Utilisateur introuvable"));
        return new UtilisateurPrincipal(utilisateur);
    }
}
