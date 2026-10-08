package com.example.comptable.security;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;

import java.time.Clock;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Iterator;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Limiteur de débit par clé (adresse IP + objet), fenêtre glissante EN MÉMOIRE : protège les points d'entrée publics (connexion, réinitialisation, dépôt par lien) sans infrastructure supplémentaire.
 * Limite connue : le compteur est local à chaque instance ; derrière plusieurs instances la limite effective est multipliée par leur nombre, et le verrouillage de compte (en base) reste l'autorité pour
 * la connexion. Une limite par adresse au niveau du reverse proxy complète ce dispositif en production.
 */
@Component
public class RateLimiter {

    private final Clock clock;
    private final ConcurrentHashMap<String, Deque<Long>> fenetres = new ConcurrentHashMap<>();

    public RateLimiter(Clock clock) {
        this.clock = clock;
    }

    /** Compte une tentative ; refuse (429) au-delà de {@code max} tentatives sur {@code fenetreSecondes}. */
    public void tenter(String cle, int max, long fenetreSecondes) {
        long maintenant = clock.millis();
        long debut = maintenant - fenetreSecondes * 1000;
        Deque<Long> f = fenetres.computeIfAbsent(cle, k -> new ArrayDeque<>());
        synchronized (f) {
            Iterator<Long> it = f.iterator();
            while (it.hasNext() && it.next() < debut) {
                it.remove();
            }
            if (f.size() >= max) {
                throw new ResponseStatusException(HttpStatus.TOO_MANY_REQUESTS, "Trop de tentatives. Réessayez dans quelques minutes.");
            }
            f.addLast(maintenant);
        }
        if (fenetres.size() > 10_000) {                                                                 // purge opportuniste : la mémoire reste bornée
            fenetres.entrySet().removeIf(e -> e.getValue().isEmpty() || e.getValue().peekLast() < debut);
        }
    }

    /** Oublie une clé (après un succès). */
    public void reinitialiser(String cle) {
        fenetres.remove(cle);
    }
}
