package com.example.comptable.collecte.repository;

import com.example.comptable.collecte.domain.DemandeMessage;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import java.util.List;
import java.util.UUID;

public interface DemandeMessageRepository extends JpaRepository<DemandeMessage, DemandeMessage.Cle> {

    @Query("select dm.id.demandeId from DemandeMessage dm where dm.id.messageId = :messageId")
    List<UUID> demandeIdsDuMessage(UUID messageId);
}
