package com.example.comptable.admin;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** Objets échangés par l'API d'administration de la plateforme (`/api/admin/**`). */
public final class AdminDtos {

    private AdminDtos() {
    }

    public record Page<T>(List<T> elements, long total, int page, int taille) {
    }

    /** Un compte, tous cabinets confondus. Aucune donnée secrète : ni mot de passe, ni jeton. */
    public record UtilisateurAdmin(UUID id, String email, String nom, String prenom, String statut, UUID cabinetId, String cabinet, List<String> roles, OffsetDateTime creeLe,
                                   OffsetDateTime derniereConnexion, OffsetDateTime verrouilleJusqua, boolean verrouille, int echecsConsecutifs, boolean motDePasseAChanger) {
    }

    public record MotifRequest(String motif) {
    }

    /** Le mot de passe temporaire n'est renvoyé qu'ici, une seule fois. */
    public record ReinitialisationAdmin(UtilisateurAdmin utilisateur, String motDePasseTemporaire) {
    }

    public record EvenementAudit(long id, OffsetDateTime date, String action, String entite, String entiteId, Map<String, Object> details, UUID cabinetId, String cabinet, UUID acteurId,
                                 String acteur) {
    }

    public record Apercu(long cabinets, long utilisateurs, long actifs, long suspendus, long verrouilles, long jamaisConnectes, long administrateurs) {
    }
}
