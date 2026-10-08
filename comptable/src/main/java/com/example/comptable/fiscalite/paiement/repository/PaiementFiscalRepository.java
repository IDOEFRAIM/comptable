package com.example.comptable.fiscalite.paiement.repository;

import com.example.comptable.fiscalite.paiement.domain.PaiementFiscal;
import com.example.comptable.fiscalite.paiement.domain.PaiementFournisseur;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface PaiementFiscalRepository extends JpaRepository<PaiementFiscal, UUID> {

    List<PaiementFiscal> findByDeclarationFiscaleIdOrderByDateInitiationDesc(UUID declarationFiscaleId);

    /** Recherche par le webhook du fournisseur (confirmation asynchrone). */
    Optional<PaiementFiscal> findByFournisseurAndReferenceExterne(PaiementFournisseur fournisseur, String referenceExterne);
}
