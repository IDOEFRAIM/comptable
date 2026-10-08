package com.example.comptable.audit;

import com.example.comptable.audit.domain.AuditEvent;
import com.example.comptable.audit.repository.AuditEventRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Mission P1 (section 34) : point d'entree unique pour tracer un evenement dans {@code audit_log}
 * (table existante depuis la migration V1, jamais ecrite avant cette mission). Deux methodes,
 * jamais melangees dans le meme evenement (section 35 - "ne jamais melanger AI suggestion et
 * human decision") : une decision humaine porte {@code source=HUMAIN} ; une decision/suggestion
 * IA porte {@code source=IA} avec le modele, la version de prompt et si elle a ete validee par un
 * humain.
 */
@Service
@RequiredArgsConstructor
public class AuditTrailService {

    private final AuditEventRepository auditEventRepository;

    @Transactional
    public void enregistrerActionHumaine(UUID tenantId, UUID utilisateurId, String action,
                                          String entite, String entiteId, Map<String, Object> details) {
        Map<String, Object> enrichis = new HashMap<>(details != null ? details : Map.of());
        enrichis.put("source", "HUMAIN");
        enregistrer(tenantId, utilisateurId, action, entite, entiteId, enrichis);
    }

    @Transactional
    public void enregistrerDecisionIa(UUID tenantId, String action, String entite, String entiteId,
                                       String model, String promptVersion, String decision,
                                       boolean userValidation, Map<String, Object> extra) {
        Map<String, Object> details = new HashMap<>(extra != null ? extra : Map.of());
        details.put("source", "IA");
        details.put("model", model);
        details.put("promptVersion", promptVersion);
        details.put("decision", decision);
        details.put("userValidation", userValidation);
        enregistrer(tenantId, null, action, entite, entiteId, details);
    }

    /** Evenement produit par le systeme lui-meme (moteur deterministe, pipeline), ni humain ni IA :
     * {@code source=SYSTEME}. Jamais utilise pour une decision humaine ou une suggestion IA. */
    @Transactional
    public void enregistrerEvenementSysteme(UUID tenantId, String action, String entite, String entiteId,
                                             Map<String, Object> details) {
        Map<String, Object> enrichis = new HashMap<>(details != null ? details : Map.of());
        enrichis.put("source", "SYSTEME");
        enregistrer(tenantId, null, action, entite, entiteId, enrichis);
    }

    /**
     * Echec ou refus metier a tracer meme quand la transaction appelante est annulee (une
     * {@code ResponseStatusException} 422 fait un rollback) : transaction INDEPENDANTE. A reserver aux
     * refus qui racontent une decision (compte non resolu, cloture refusee), jamais aux erreurs techniques.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void enregistrerRefus(UUID tenantId, UUID utilisateurId, String action, String entite, String entiteId,
                                  Map<String, Object> details) {
        Map<String, Object> enrichis = new HashMap<>(details != null ? details : Map.of());
        enrichis.put("source", utilisateurId != null ? "HUMAIN" : "SYSTEME");
        enrichis.put("resultat", "REFUSE");
        enregistrer(tenantId, utilisateurId, action, entite, entiteId, enrichis);
    }

    private void enregistrer(UUID tenantId, UUID utilisateurId, String action, String entite,
                              String entiteId, Map<String, Object> details) {
        AuditEvent event = new AuditEvent();
        event.setTenantId(tenantId);
        event.setUtilisateurId(utilisateurId);
        event.setAction(action);
        event.setEntite(entite);
        event.setEntiteId(entiteId);
        event.setDetails(details);
        auditEventRepository.save(event);
    }
}
