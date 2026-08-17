package com.example.comptable.agent.whatsapp.repository;

import com.example.comptable.agent.whatsapp.domain.WhatsappEvenementTraite;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.UUID;

public interface WhatsappEvenementTraiteRepository extends JpaRepository<WhatsappEvenementTraite, UUID> {

    boolean existsByMessageId(String messageId);
}
