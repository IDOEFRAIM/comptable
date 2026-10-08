package com.example.comptable.collecte.repository;

import com.example.comptable.collecte.domain.OutboundMessage;
import com.example.comptable.collecte.domain.StatutMessage;
import com.example.comptable.collecte.domain.TypeMessage;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;

import java.time.OffsetDateTime;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface OutboundMessageRepository extends JpaRepository<OutboundMessage, UUID> {

    Optional<OutboundMessage> findByEntrepriseIdAndIdempotencyKey(UUID entrepriseId, String idempotencyKey);

    Optional<OutboundMessage> findByIdAndEntrepriseId(UUID id, UUID entrepriseId);

    /**
     * RESERVE un lot de messages a envoyer : deux workers (ou deux instances) ne prennent jamais le meme message
     * ({@code FOR UPDATE SKIP LOCKED}). Le message passe a SENDING et son compteur de tentatives augmente.
     */
    @Query(value = "UPDATE outbound_message SET status = 'SENDING', locked_at = now(), attempt_count = attempt_count + 1 "
            + "WHERE id IN (SELECT id FROM outbound_message WHERE status = 'PENDING' AND next_attempt_at <= now() "
            + "ORDER BY next_attempt_at, created_at LIMIT :limite FOR UPDATE SKIP LOCKED) RETURNING id", nativeQuery = true)
    List<UUID> reserver(int limite);

    /** Message reserve par un worker qui a plante : il redevient envoyable (la tentative deja comptee reste comptee). */
    @Modifying
    @Query(value = "UPDATE outbound_message SET status = 'PENDING', locked_at = NULL "
            + "WHERE status = 'SENDING' AND locked_at < :avant", nativeQuery = true)
    int liberer(OffsetDateTime avant);

    /** Message non terminal du meme type deja lie a ces demandes (anti double clic). */
    @Query("select m from OutboundMessage m where m.entrepriseId = :entrepriseId and m.kind = :kind "
            + "and m.status in (com.example.comptable.collecte.domain.StatutMessage.PENDING, com.example.comptable.collecte.domain.StatutMessage.SENDING) "
            + "and exists (select 1 from DemandeMessage dm where dm.id.messageId = m.id and dm.id.demandeId in :demandeIds)")
    List<OutboundMessage> findActifsPourDemandes(UUID entrepriseId, TypeMessage kind, Collection<UUID> demandeIds);

    /** Messages deja crees aujourd'hui pour ce destinataire (plafond anti-spam). */
    long countByEntrepriseIdAndRecipientAndCreatedAtAfter(UUID entrepriseId, String recipient, OffsetDateTime depuis);

    @Query("select m from OutboundMessage m join DemandeMessage dm on dm.id.messageId = m.id where dm.id.demandeId = :demandeId order by m.createdAt desc")
    List<OutboundMessage> findParDemande(UUID demandeId);

    List<OutboundMessage> findByEntrepriseIdOrderByCreatedAtDesc(UUID entrepriseId);

    long countByEntrepriseIdAndStatus(UUID entrepriseId, StatutMessage status);

    /** (idDemande, message) pour un lot de demandes, du plus recent au plus ancien : le dernier message de chacune sans requete par ligne. */
    @Query("select dm.id.demandeId, m from OutboundMessage m join DemandeMessage dm on dm.id.messageId = m.id "
            + "where dm.id.demandeId in :demandeIds order by m.createdAt desc")
    List<Object[]> derniersMessages(Collection<UUID> demandeIds);
}
