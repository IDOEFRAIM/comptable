package com.example.comptable.admin;

import java.time.LocalDate;
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

    public record Apercu(long cabinets, long utilisateurs, long actifs, long suspendus, long verrouilles, long jamaisConnectes, long administrateurs, long cabinetsSuspendus, long essaisExpires) {
    }

    /** Un cabinet avec son abonnement, ses limites et ce qu'il consomme. {@code derniereActivite} : dernière trace d'audit du cabinet. */
    public record CabinetAdmin(UUID id, String raisonSociale, String slug, String pays, String plan, String statut, LocalDate finEssai, boolean essaiExpire, Integer quotaUtilisateurs,
                               Integer quotaDossiers, long membresActifs, long utilisateursTotal, long dossiersActifs, OffsetDateTime creeLe, OffsetDateTime derniereActivite,
                               String motifSuspension, OffsetDateTime suspenduLe) {
    }

    /** Abonnement d'un cabinet : quotas et date de fin d'essai null = pas de limite / pas de date. */
    public record AbonnementRequest(String plan, LocalDate finEssai, Integer quotaUtilisateurs, Integer quotaDossiers, String motif) {
    }

    public record CreerCabinetRequest(String raisonSociale, String pays, String emailExpert, String nom, String prenom, String telephone, String plan, LocalDate finEssai, Integer quotaUtilisateurs,
                                      Integer quotaDossiers) {
    }

    /** Le mot de passe temporaire de l'expert n'est renvoyé qu'ici, une seule fois. */
    public record CreationCabinetAdmin(CabinetAdmin cabinet, String emailExpert, String motDePasseTemporaire) {
    }
}
