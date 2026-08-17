package com.example.comptable.common.tenant;

import java.util.UUID;

/**
 * Porte le tenant courant (extrait du JWT) le temps d'une requete HTTP.
 * Peuple par JwtAuthFilter, lu par les services pour scoper explicitement
 * leurs requetes (ex: repository.findByTenantId(TenantContext.require())).
 * Aucun SUPER_ADMIN n'est associe a un tenant : TenantContext reste vide
 * pour ces requetes, ce qui est attendu.
 */
public final class TenantContext {

    private static final ThreadLocal<UUID> CURRENT_TENANT = new ThreadLocal<>();

    private TenantContext() {
    }

    public static void set(UUID tenantId) {
        CURRENT_TENANT.set(tenantId);
    }

    public static UUID get() {
        return CURRENT_TENANT.get();
    }

    public static UUID require() {
        UUID tenantId = CURRENT_TENANT.get();
        if (tenantId == null) {
            throw new IllegalStateException("Aucun tenant dans le contexte courant");
        }
        return tenantId;
    }

    public static void clear() {
        CURRENT_TENANT.remove();
    }
}
