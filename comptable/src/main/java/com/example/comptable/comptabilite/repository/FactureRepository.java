package com.example.comptable.comptabilite.repository;

import com.example.comptable.comptabilite.domain.Facture;
import com.example.comptable.comptabilite.domain.StatutFacture;
import com.example.comptable.comptabilite.domain.TypeFacture;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

public interface FactureRepository extends JpaRepository<Facture, UUID> {

    @Query("select distinct f from Facture f "
            + "left join fetch f.lignes "
            + "join fetch f.tiers "
            + "where f.entreprise.id = :entrepriseId and f.type = :type "
            + "order by f.dateEmission desc, f.numero desc")
    List<Facture> findByEntrepriseIdAndTypeFetchAll(UUID entrepriseId, TypeFacture type);

    long countByEntrepriseIdAndType(UUID entrepriseId, TypeFacture type);

    /** Factures clients validees (donc deja envoyees/comptabilisees) dont l'echeance est deja
     * passee sans etre marquees PAYEE (mission P0, DossierComptableService - domaine "Clients").
     * date_echeance NULL est naturellement exclu par la comparaison SQL, pas besoin de filtre
     * explicite. */
    long countByEntrepriseIdAndTypeAndStatutAndDateEcheanceBefore(
            UUID entrepriseId, TypeFacture type, StatutFacture statut, LocalDate date);

    /** Mission P1 (Copilot Evidence-First) : meme filtre que le compteur ci-dessus, mais la liste
     * elle-meme - le Copilot doit pouvoir citer les factures concernees (id/tiers/echeance), pas
     * seulement un nombre. */
    @Query("select distinct f from Facture f join fetch f.tiers "
            + "where f.entreprise.id = :entrepriseId and f.type = :type and f.statut = :statut "
            + "and f.dateEcheance < :date order by f.dateEcheance asc")
    List<Facture> findByEntrepriseIdAndTypeAndStatutAndDateEcheanceBeforeOrderByDateEcheanceAsc(
            UUID entrepriseId, TypeFacture type, StatutFacture statut, LocalDate date);

    /** Mission P1 (Cash Forecasting, palier KNOWN) : factures dont l'echeance tombe dans
     * l'horizon de prevision - montant ET date sont connus avec certitude, contrairement au
     * palier EXPECTED (factures deja en retard, ponderees par probabilite). */
    @Query("select distinct f from Facture f join fetch f.tiers "
            + "where f.entreprise.id = :entrepriseId and f.type = :type and f.statut = :statut "
            + "and f.dateEcheance between :debut and :fin order by f.dateEcheance asc")
    List<Facture> findByEntrepriseIdAndTypeAndStatutAndDateEcheanceBetweenOrderByDateEcheanceAsc(
            UUID entrepriseId, TypeFacture type, StatutFacture statut, LocalDate debut, LocalDate fin);

    /** Candidats de rapprochement (agent.matching.MatchingContextBuilder) : factures VALIDEE
     * (deja comptabilisees, pas encore marquees PAYEE - cf. StatutFacture) dans une fenetre de
     * dates raisonnable. Le montant TTC n'est pas une colonne (calcule depuis les lignes,
     * cf. {@link Facture#montantTtc()}), donc pas filtrable en SQL ici : le filtrage par montant
     * se fait cote Java sur cet ensemble deja borne par entreprise/type/statut/date, qui reste
     * petit en pratique (pas "toutes les factures de l'entreprise"). */
    @Query("select distinct f from Facture f left join fetch f.lignes join fetch f.tiers "
            + "where f.entreprise.id = :entrepriseId and f.type = :type and f.statut = :statut "
            + "and f.dateEmission between :debut and :fin "
            + "order by f.dateEmission desc")
    List<Facture> rechercherCandidatsRapprochement(UUID entrepriseId, TypeFacture type, StatutFacture statut,
                                                     LocalDate debut, LocalDate fin);
}
