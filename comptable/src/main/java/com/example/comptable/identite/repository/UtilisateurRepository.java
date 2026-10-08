package com.example.comptable.identite.repository;

import com.example.comptable.identite.domain.Utilisateur;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface UtilisateurRepository extends JpaRepository<Utilisateur, UUID> {

    Optional<Utilisateur> findByEmail(String email);

    boolean existsByEmail(String email);

    /** Mission P1.5 (section 51) : source pour le selecteur "assigner a un collaborateur" -
     * tous les utilisateurs du tenant portant un des roles donnes, quel que soit leur nombre de
     * Finding assignes (contrairement a {@code InboxService.chargeParCollaborateur}, qui ne liste
     * que les collaborateurs ayant deja au moins une tache). */
    @Query("SELECT DISTINCT u FROM Utilisateur u JOIN u.roles r "
            + "WHERE u.tenant.id = :tenantId AND r.code IN :codes ORDER BY u.nom, u.prenom")
    List<Utilisateur> findByTenantIdAndRoleCodeIn(@Param("tenantId") UUID tenantId, @Param("codes") List<String> codes);

    /** Compare uniquement les chiffres (ignore espaces/tirets/+) pour tolerer les formats de
     * saisie varies du numero de telephone enregistre. Le mediaId WhatsApp {@code from} est
     * deja purement numerique (format E.164 sans "+") : voir WhatsAppIngestionService. */
    @Query(value = "SELECT * FROM utilisateur WHERE regexp_replace(telephone, '[^0-9]', '', 'g') = :telephoneNormalise",
            nativeQuery = true)
    Optional<Utilisateur> findByTelephoneNormalise(@Param("telephoneNormalise") String telephoneNormalise);

    /** Utilisateurs du portail client rattaches a une entreprise (interlocuteurs a qui adresser les demandes de pieces). */
    @org.springframework.data.jpa.repository.Query("select u from Utilisateur u where u.id in ("
            + "select ue.id.utilisateurId from com.example.comptable.entreprise.domain.UtilisateurEntreprise ue where ue.id.entrepriseId = :entrepriseId) "
            + "order by u.nom, u.prenom, u.id")
    java.util.List<Utilisateur> findInterlocuteursDeLEntreprise(java.util.UUID entrepriseId);
}
