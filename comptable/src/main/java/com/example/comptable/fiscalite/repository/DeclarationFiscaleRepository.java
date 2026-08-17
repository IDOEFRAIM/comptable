package com.example.comptable.fiscalite.repository;

import com.example.comptable.fiscalite.domain.DeclarationFiscale;
import com.example.comptable.fiscalite.domain.StatutDeclaration;
import com.example.comptable.fiscalite.domain.TypeImpot;
import org.springframework.data.jpa.repository.JpaRepository;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface DeclarationFiscaleRepository extends JpaRepository<DeclarationFiscale, UUID> {

    List<DeclarationFiscale> findByEntrepriseIdOrderByDateEcheanceAsc(UUID entrepriseId);

    boolean existsByEntrepriseIdAndTypeImpotAndPeriode(UUID entrepriseId, TypeImpot typeImpot, String periode);

    /** Mission P1 (Copilot, intention EVOLUTION_TVA) : le montant declare d'une periode precise,
     * pour comparer un mois au precedent. */
    Optional<DeclarationFiscale> findByEntrepriseIdAndTypeImpotAndPeriode(
            UUID entrepriseId, TypeImpot typeImpot, String periode);

    List<DeclarationFiscale> findByEntrepriseIdAndStatutNotAndDateEcheanceBetweenOrderByDateEcheanceAsc(
            UUID entrepriseId, StatutDeclaration statut, LocalDate dateDebut, LocalDate dateFin);

    List<DeclarationFiscale> findByEntrepriseIdAndStatutNotAndDateEcheanceBeforeOrderByDateEcheanceAsc(
            UUID entrepriseId, StatutDeclaration statut, LocalDate date);

    /** Mission P1 (Financial Snapshot) : charge fiscale totale de la periode, quel que soit le
     * statut de paiement - distinct de la detection de retard (P0), qui filtre au contraire sur
     * le statut. */
    List<DeclarationFiscale> findByEntrepriseIdAndDateEcheanceBetween(UUID entrepriseId, LocalDate dateDebut, LocalDate dateFin);
}
