package com.example.comptable.comptabilite.repository;

import com.example.comptable.comptabilite.domain.LigneEcriture;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

public interface LigneEcritureRepository extends JpaRepository<LigneEcriture, UUID> {

    @Query("select l from LigneEcriture l "
            + "join fetch l.ecriture e join fetch e.journal "
            + "where l.compte.id = :compteId and e.statut = 'VALIDEE' "
            + "and e.dateEcriture between :dateDebut and :dateFin "
            + "order by e.dateEcriture asc, e.numeroPiece asc")
    List<LigneEcriture> rechercherMouvementsValides(UUID compteId, LocalDate dateDebut, LocalDate dateFin);

    @Query("select coalesce(sum(l.debit), 0) - coalesce(sum(l.credit), 0) from LigneEcriture l "
            + "where l.compte.id = :compteId and l.ecriture.statut = 'VALIDEE' "
            + "and l.ecriture.dateEcriture < :date")
    BigDecimal soldeNetAvant(UUID compteId, LocalDate date);

    @Query("select distinct l.compte.id from LigneEcriture l "
            + "where l.compte.entreprise.id = :entrepriseId and l.ecriture.statut = 'VALIDEE' "
            + "and l.ecriture.dateEcriture <= :dateFin")
    List<UUID> rechercherComptesMouvementes(UUID entrepriseId, LocalDate dateFin);

    /** Solde net des comptes de tresorerie (classe 5) au jour donne, pour un aperçu rapide en tableau de bord. */
    @Query("select coalesce(sum(l.debit), 0) - coalesce(sum(l.credit), 0) from LigneEcriture l "
            + "where l.compte.entreprise.id = :entrepriseId and l.compte.numero like '5%' "
            + "and l.ecriture.statut = 'VALIDEE' and l.ecriture.dateEcriture <= :date")
    BigDecimal soldeTresorerie(UUID entrepriseId, LocalDate date);

    /** Mission P1 (Cash Position multicanal) : meme convention que {@link #soldeTresorerie} mais
     * par compte individuel, pour distinguer banque/caisse/mobile money au sein de la classe 5. */
    @Query("select coalesce(sum(l.debit), 0) - coalesce(sum(l.credit), 0) from LigneEcriture l "
            + "where l.compte.id = :compteId and l.ecriture.statut = 'VALIDEE' and l.ecriture.dateEcriture <= :date")
    BigDecimal soldeNetJusquau(UUID compteId, LocalDate date);

    /** Mission P1 (Financial Snapshot) : mouvement net (debit - credit) de toute une classe de
     * comptes (ex: '6%' charges, '7%' produits) sur une periode - meme convention de signe que
     * {@link #mouvementNetPeriode}, applique a un ensemble de comptes plutot qu'un seul. */
    @Query("select coalesce(sum(l.debit), 0) - coalesce(sum(l.credit), 0) from LigneEcriture l "
            + "where l.compte.entreprise.id = :entrepriseId and l.compte.numero like :prefixeClasse "
            + "and l.ecriture.statut = 'VALIDEE' and l.ecriture.dateEcriture between :dateDebut and :dateFin")
    BigDecimal mouvementNetClasseSurPeriode(UUID entrepriseId, String prefixeClasse, LocalDate dateDebut, LocalDate dateFin);

    /** Mouvements nets (credit - debit) d'un compte sur une periode, ecritures validees uniquement. */
    @Query("select coalesce(sum(l.credit), 0) - coalesce(sum(l.debit), 0) from LigneEcriture l "
            + "where l.compte.id = :compteId and l.ecriture.statut = 'VALIDEE' "
            + "and l.ecriture.dateEcriture between :dateDebut and :dateFin")
    BigDecimal mouvementNetPeriode(UUID compteId, LocalDate dateDebut, LocalDate dateFin);

    List<LigneEcriture> findByCompteIdAndLettrage(UUID compteId, String lettrage);

    /** Utilise avant suppression d'un compte du PCE : un compte deja mouvemente ne doit jamais
     * etre supprime (seule la desactivation est proposee), quel que soit le statut de l'ecriture. */
    boolean existsByCompteId(UUID compteId);

    /** Historique des comptes de charge/produit deja utilises pour ce tiers (mission P0,
     * AccountingProposalBuilder) : une ligne par ecriture ou ce tiers apparait, cote de l'AUTRE
     * ligne (charge/produit) de la meme ecriture - jamais le compte 411/401 du tiers lui-meme. Pas
     * de GROUP BY/COUNT ici : le comptage/la decision de confiance restent en Java (petit volume
     * par tiers), la requete se contente de retourner les occurrences brutes. */
    @Query(value = "SELECT le2.compte_id FROM ligne_ecriture le1 "
            + "JOIN ligne_ecriture le2 ON le2.ecriture_id = le1.ecriture_id AND le2.id <> le1.id "
            + "JOIN compte_comptable cc ON cc.id = le2.compte_id "
            + "JOIN plan_comptable_reference pcr ON pcr.id = cc.reference_id "
            + "WHERE le1.tiers_id = :tiersId AND pcr.nature IN ('CHARGE', 'PRODUIT')",
            nativeQuery = true)
    List<UUID> rechercherComptesChargeProduitHistoriquesPourTiers(@Param("tiersId") UUID tiersId);

    @Query("select count(distinct l.lettrage) from LigneEcriture l where l.compte.id = :compteId and l.lettrage is not null")
    long countDistinctLettrageByCompteId(UUID compteId);

    /** Lignes candidates au lettrage automatique (Sprint 10) : validees, non deja lettrees. */
    @Query("select l from LigneEcriture l join fetch l.ecriture e "
            + "where l.compte.id = :compteId and l.lettrage is null and e.statut = 'VALIDEE' "
            + "order by e.dateEcriture asc")
    List<LigneEcriture> rechercherNonLettreesValidees(UUID compteId);

    /** Lignes en instance (non lettrees) d'un compte tiers a une date donnee, pour la balance
     * agee (Sprint 10) : point de depart, filtrees/regroupees par tiers cote service. */
    @Query("select l from LigneEcriture l join fetch l.ecriture e left join fetch l.tiers "
            + "where l.compte.entreprise.id = :entrepriseId and l.compte.lettrable = true "
            + "and l.lettrage is null and e.statut = 'VALIDEE' and e.dateEcriture <= :dateArrete "
            + "order by e.dateEcriture asc")
    List<LigneEcriture> rechercherLignesEnInstance(UUID entrepriseId, LocalDate dateArrete);

    /** Lignes DEJA lettrees (reconciliees) d'un tiers donne (mission P2, Cash Forecast -
     * comportement de paiement) : symetrique de {@link #rechercherLignesEnInstance} mais pour les
     * paiements deja rapproches, seule source reelle de "date de paiement effective" dans ce
     * systeme (aucune colonne date_paiement n'existe sur Facture - voir PaymentBehaviorService). */
    @Query("select l from LigneEcriture l join fetch l.ecriture e "
            + "where l.tiers.id = :tiersId and l.lettrage is not null and e.statut = 'VALIDEE' "
            + "order by l.lettrage asc, e.dateEcriture asc")
    List<LigneEcriture> rechercherLignesLettreesPourTiers(UUID tiersId);

    /** Retrouve la ligne cote compte tiers d'une ecriture donnee (mission P2, Cash Forecast) :
     * relie l'ecriture de facturation (Facture.ecritureId) a sa ligne 411/401 pour en lire le
     * code de lettrage eventuel. */
    List<LigneEcriture> findByEcritureIdAndCompteId(UUID ecritureId, UUID compteId);

    /** Lignes individuelles (pas un simple total, contrairement a {@link #mouvementNetClasseSurPeriode})
     * d'une classe de comptes sur une periode - mission P2, Cash Forecast (RecurringFlowDetector,
     * historique de charges recurrentes). */
    @Query("select l from LigneEcriture l join fetch l.ecriture e join fetch l.compte c "
            + "where c.entreprise.id = :entrepriseId and c.numero like :prefixeClasse "
            + "and e.statut = 'VALIDEE' and e.dateEcriture between :dateDebut and :dateFin "
            + "order by c.numero asc, e.dateEcriture asc")
    List<LigneEcriture> rechercherLignesClasseSurPeriode(UUID entrepriseId, String prefixeClasse, LocalDate dateDebut, LocalDate dateFin);

    /** Date de la plus ancienne ecriture validee de l'entreprise (mission P2, Cash Forecast -
     * profondeur d'historique disponible pour la confiance du forecast) - null si aucune ecriture
     * validee n'existe encore. */
    @Query("select min(e.dateEcriture) from LigneEcriture l join l.ecriture e "
            + "where l.compte.entreprise.id = :entrepriseId and e.statut = 'VALIDEE'")
    LocalDate trouverDatePlusAncienneEcritureValidee(UUID entrepriseId);
}
