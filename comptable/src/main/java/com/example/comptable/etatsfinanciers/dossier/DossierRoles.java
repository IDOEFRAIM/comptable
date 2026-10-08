package com.example.comptable.etatsfinanciers.dossier;

import com.example.comptable.security.UtilisateurPrincipal;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

/**
 * Droits du dossier financier, alignes sur le RBAC existant : CLIENT en lecture seule ; COLLABORATEUR et EXPERT_COMPTABLE preparent (notes, qualification des flux de tresorerie) ;
 * EXPERT_COMPTABLE revoit et finalise. Source unique, reutilisee par le backend (controle) et exposee au frontend (affichage) : le frontend ne decide jamais.
 */
public final class DossierRoles {

    private DossierRoles() {
    }

    public static boolean peutPreparer(UtilisateurPrincipal p) {
        return aRole(p, "ROLE_COLLABORATEUR") || aRole(p, "ROLE_EXPERT_COMPTABLE");
    }

    public static boolean peutRevoir(UtilisateurPrincipal p) {
        return aRole(p, "ROLE_EXPERT_COMPTABLE");
    }

    public static boolean peutFinaliser(UtilisateurPrincipal p) {
        return aRole(p, "ROLE_EXPERT_COMPTABLE");
    }

    public static void exigerPreparation(UtilisateurPrincipal p) {
        if (!peutPreparer(p)) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Reserve aux collaborateurs et experts-comptables du cabinet");
        }
    }

    public static void exigerFinalisation(UtilisateurPrincipal p) {
        if (!peutFinaliser(p)) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "La finalisation du dossier financier est reservee a l'expert-comptable");
        }
    }

    private static boolean aRole(UtilisateurPrincipal p, String role) {
        return p.getAuthorities().stream().anyMatch(a -> a.getAuthority().equals(role));
    }
}
