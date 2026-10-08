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
            + "and l.ecriture.statut = 'VALIDEE' and l.ecriture.nature not in ('CLOSING', 'OPENING') and l.ecriture.dateEcriture between :dateDebut and :dateFin")
    BigDecimal mouvementNetClasseSurPeriode(UUID entrepriseId, String prefixeClasse, LocalDate dateDebut, LocalDate dateFin);

    /**
     * Fondation des soldes (voir {@code AccountingBalanceEngine}) : UNE agregation SQL par entreprise, jamais une requete par compte. Par compte :
     * solde net d'ouverture (toutes les ecritures du perimetre ANTERIEURES a {@code debut}), cumuls debit et credit de la periode [debut ; fin] (bornes
     * incluses). Les ecritures sont filtrees par statut et par nature (la vue de reporting), les comptes sans aucune ecriture avant {@code fin} sont absents.
     */
    @Query("select c.id, c.numero, c.intitule, "
            + "coalesce(sum(case when e.dateEcriture < :debut then l.debit - l.credit else 0 end), 0), "
            + "coalesce(sum(case when e.dateEcriture >= :debut then l.debit else 0 end), 0), "
            + "coalesce(sum(case when e.dateEcriture >= :debut then l.credit else 0 end), 0) "
            + "from LigneEcriture l join l.ecriture e join l.compte c "
            + "where c.entreprise.id = :entrepriseId and e.statut in :statuts and e.nature in :natures and e.dateEcriture <= :fin "
            + "group by c.id, c.numero, c.intitule order by c.numero")
    List<Object[]> agregerSoldes(UUID entrepriseId, LocalDate debut, LocalDate fin,
                                 java.util.Collection<com.example.comptable.comptabilite.domain.StatutEcriture> statuts,
                                 java.util.Collection<com.example.comptable.comptabilite.domain.NatureEcriture> natures);

    /** Toutes les lignes des ecritures d'une vue de reporting sur une periode (une seule requete, comptes et ecritures charges) : analyse des flux de tresorerie. */
    @Query("select l from LigneEcriture l join fetch l.ecriture e join fetch l.compte c "
            + "where c.entreprise.id = :entrepriseId and e.statut in :statuts and e.nature in :natures "
            + "and e.dateEcriture between :debut and :fin order by e.dateEcriture asc, e.numeroPiece asc, l.numeroLigne asc")
    List<LigneEcriture> rechercherLignesDeLaVue(UUID entrepriseId, LocalDate debut, LocalDate fin,
                                                java.util.Collection<com.example.comptable.comptabilite.domain.StatutEcriture> statuts,
                                                java.util.Collection<com.example.comptable.comptabilite.domain.NatureEcriture> natures);

    /** Identifiants des ecritures d'une vue de reporting touchant au moins un des comptes donnes (une requete) : cible les ecritures de tresorerie sans hydrater les autres. */
    @Query("select distinct e.id from LigneEcriture l join l.ecriture e where l.compte.id in :comptes and e.entreprise.id = :entrepriseId and e.statut in :statuts and e.nature in :natures "
            + "and e.dateEcriture between :debut and :fin")
    List<UUID> rechercherEcrituresTouchantLesComptes(UUID entrepriseId, LocalDate debut, LocalDate fin,
                                                     java.util.Collection<com.example.comptable.comptabilite.domain.StatutEcriture> statuts,
                                                     java.util.Collection<com.example.comptable.comptabilite.domain.NatureEcriture> natures, java.util.Collection<UUID> comptes);

    /** Toutes les lignes (avec leur ecriture et leur compte) d'ecritures donnees, dans l'ordre comptable. */
    @Query("select l from LigneEcriture l join fetch l.ecriture e join fetch l.compte c where e.id in :ecritureIds order by e.dateEcriture asc, e.numeroPiece asc, l.numeroLigne asc")
    List<LigneEcriture> rechercherLignesDesEcritures(java.util.Collection<UUID> ecritureIds);

    /** Lignes d'ecriture d'un compte de l'entreprise dans une vue de reporting (drill-down des etats financiers) : statut et nature filtres, bornes incluses. */
    @Query("select l from LigneEcriture l join fetch l.ecriture e join fetch e.journal join fetch l.compte c "
            + "where c.entreprise.id = :entrepriseId and c.id = :compteId and e.statut in :statuts and e.nature in :natures "
            + "and e.dateEcriture between :debut and :fin order by e.dateEcriture asc, e.numeroPiece asc, l.numeroLigne asc")
    List<LigneEcriture> rechercherLignesDuCompteDansLaVue(UUID entrepriseId, UUID compteId, LocalDate debut, LocalDate fin,
                                                          java.util.Collection<com.example.comptable.comptabilite.domain.StatutEcriture> statuts,
                                                          java.util.Collection<com.example.comptable.comptabilite.domain.NatureEcriture> natures);

    /** Meme agregation pour un seul compte de l'entreprise (grand livre). */
    @Query("select c.id, c.numero, c.intitule, "
            + "coalesce(sum(case when e.dateEcriture < :debut then l.debit - l.credit else 0 end), 0), "
            + "coalesce(sum(case when e.dateEcriture >= :debut then l.debit else 0 end), 0), "
            + "coalesce(sum(case when e.dateEcriture >= :debut then l.credit else 0 end), 0) "
            + "from LigneEcriture l join l.ecriture e join l.compte c "
            + "where c.entreprise.id = :entrepriseId and c.id = :compteId and e.statut in :statuts and e.nature in :natures and e.dateEcriture <= :fin "
            + "group by c.id, c.numero, c.intitule")
    List<Object[]> agregerSoldesDuCompte(UUID entrepriseId, UUID compteId, LocalDate debut, LocalDate fin,
                                         java.util.Collection<com.example.comptable.comptabilite.domain.StatutEcriture> statuts,
                                         java.util.Collection<com.example.comptable.comptabilite.domain.NatureEcriture> natures);

    /** Empreinte du perimetre : (nombre de lignes, nombre d'ecritures, total debit, total credit, derniere creation d'ecriture). */
    @Query("select count(l), count(distinct e.id), coalesce(sum(l.debit), 0), coalesce(sum(l.credit), 0), max(e.createdAt) "
            + "from LigneEcriture l join l.ecriture e join l.compte c "
            + "where c.entreprise.id = :entrepriseId and e.statut in :statuts and e.nature in :natures and e.dateEcriture <= :fin")
    List<Object[]> empreinte(UUID entrepriseId, LocalDate fin,
                             java.util.Collection<com.example.comptable.comptabilite.domain.StatutEcriture> statuts,
                             java.util.Collection<com.example.comptable.comptabilite.domain.NatureEcriture> natures);

    /** Mouvements nets (credit - debit) d'un compte sur une periode : activite economique (ecritures validees, hors ecritures techniques de cloture/ouverture). */
    @Query("select coalesce(sum(l.credit), 0) - coalesce(sum(l.debit), 0) from LigneEcriture l "
            + "where l.compte.id = :compteId and l.ecriture.statut = 'VALIDEE' and l.ecriture.nature not in ('CLOSING', 'OPENING') "
            + "and l.ecriture.dateEcriture between :dateDebut and :dateFin")
    BigDecimal mouvementNetPeriode(UUID compteId, LocalDate dateDebut, LocalDate dateFin);

    List<LigneEcriture> findByCompteIdAndLettrage(UUID compteId, String lettrage);

    /** Lignes de tiers (comptes lettrables) de plusieurs ecritures de facturation, en une requete. */
    @Query("select l from LigneEcriture l join fetch l.ecriture e join fetch l.compte c where e.id in :ecritureIds and c.lettrable = true")
    List<LigneEcriture> rechercherLignesTiersDesEcritures(java.util.Collection<UUID> ecritureIds);

    /** Toutes les lignes d'une ecriture avec l'ecriture, en une requete. */
    @Query("select l from LigneEcriture l join fetch l.ecriture e where e.id = :ecritureId")
    List<LigneEcriture> chargerDeLEcriture(UUID ecritureId);

    /** Lignes avec leur ecriture en une seule requete (affichage de listes). */
    @Query("select l from LigneEcriture l join fetch l.ecriture where l.id in :ids")
    List<LigneEcriture> chargerAvecEcriture(java.util.Collection<UUID> ids);

    /** Lignes validees des comptes de tiers (lettrables) d'une entreprise, par type de journal : factures (achats, ventes) ou reglements (banque, caisse). */
    @Query("select l from LigneEcriture l join fetch l.ecriture e join fetch e.journal j join fetch l.compte c "
            + "where e.entreprise.id = :entrepriseId and e.statut = 'VALIDEE' and c.lettrable = true and j.type in :types")
    List<LigneEcriture> rechercherLignesTiersParJournal(UUID entrepriseId, java.util.Collection<com.example.comptable.comptabilite.domain.TypeJournal> types);

    /** Toutes les lignes validees d'un tiers, portees par le tiers lui-meme OU par son compte auxiliaire (tous journaux), avec ecriture, journal et compte : base de l'investigation d'un compte de tiers. */
    @Query("select l from LigneEcriture l join fetch l.ecriture e join fetch e.journal j join fetch l.compte c "
            + "where e.entreprise.id = :entrepriseId and e.statut = 'VALIDEE' and (l.tiers.id = :tiersId or l.compte.id = :compteId) order by e.dateEcriture, e.id, l.numeroLigne")
    List<LigneEcriture> lignesDuTiers(UUID entrepriseId, UUID tiersId, UUID compteId);

    /** Verrou d'ecriture sur les lignes (ordre stable) : deux allocations concurrentes sur la meme ligne se serialisent. */
    @org.springframework.data.jpa.repository.Lock(jakarta.persistence.LockModeType.PESSIMISTIC_WRITE)
    @Query("select l from LigneEcriture l where l.id in :ids order by l.id")
    List<LigneEcriture> verrouiller(java.util.Collection<UUID> ids);

    /** Utilise avant suppression d'un compte du PCE : un compte deja mouvemente ne doit jamais
     * etre supprime (seule la desactivation est proposee), quel que soit le statut de l'ecriture. */
    boolean existsByCompteId(UUID compteId);

    /** Historique des comptes de charge/produit deja utilises pour ce tiers (mission P0,
     * ImputationSuggestionBuilder) : une ligne par ecriture ou ce tiers apparait, cote de l'AUTRE
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
            + "and e.statut = 'VALIDEE' and e.nature not in ('CLOSING', 'OPENING') and e.dateEcriture between :dateDebut and :dateFin "
            + "order by c.numero asc, e.dateEcriture asc")
    List<LigneEcriture> rechercherLignesClasseSurPeriode(UUID entrepriseId, String prefixeClasse, LocalDate dateDebut, LocalDate dateFin);

    /** Date de la plus ancienne ecriture validee de l'entreprise (mission P2, Cash Forecast -
     * profondeur d'historique disponible pour la confiance du forecast) - null si aucune ecriture
     * validee n'existe encore. */
    @Query("select min(e.dateEcriture) from LigneEcriture l join l.ecriture e "
            + "where l.compte.entreprise.id = :entrepriseId and e.statut = 'VALIDEE'")
    LocalDate trouverDatePlusAncienneEcritureValidee(UUID entrepriseId);

    /** Solde d'un tiers sur les ecritures validees. */
    record SoldeTiersRow(UUID tiersId, com.example.comptable.comptabilite.domain.TypeTiers type, String nom,
                         BigDecimal debit, BigDecimal credit) {
    }

    /** Tiers dont le solde est anormal (client crediteur, fournisseur debiteur) : un seul agregat, jamais ecriture par ecriture. */
    @Query("select new com.example.comptable.comptabilite.repository.LigneEcritureRepository$SoldeTiersRow("
            + "t.id, t.type, t.nom, sum(l.debit), sum(l.credit)) from LigneEcriture l join l.tiers t join l.ecriture e "
            + "where e.entreprise.id = :entrepriseId and e.statut = 'VALIDEE' group by t.id, t.type, t.nom "
            + "having (t.type = com.example.comptable.comptabilite.domain.TypeTiers.CLIENT and sum(l.credit) > sum(l.debit)) "
            + "or (t.type = com.example.comptable.comptabilite.domain.TypeTiers.FOURNISSEUR and sum(l.debit) > sum(l.credit))")
    List<SoldeTiersRow> rechercherSoldesTiersAnormaux(UUID entrepriseId);

    /** Mouvement net (debit - credit) des ecritures validees d'un compte, sur la periode d'un releve, qu'AUCUNE transaction de releve ne pointe : ce qui est comptabilise mais absent de la banque. */
    @Query("select coalesce(sum(l.debit), 0) - coalesce(sum(l.credit), 0) from LigneEcriture l where l.compte.id = :compteId and l.ecriture.statut = 'VALIDEE' "
            + "and l.ecriture.dateEcriture between :debut and :fin and not exists (select 1 from LigneReleve r where r.ligneEcritureId = l.id)")
    BigDecimal montantNonPointeEntre(UUID compteId, LocalDate debut, LocalDate fin);

    @Query("select l from LigneEcriture l join fetch l.ecriture e where l.compte.id = :compteId and e.statut = 'VALIDEE' and e.dateEcriture between :debut and :fin "
            + "and not exists (select 1 from LigneReleve r where r.ligneEcritureId = l.id) order by e.dateEcriture desc, e.id")
    List<LigneEcriture> nonPointeesEntre(UUID compteId, LocalDate debut, LocalDate fin, org.springframework.data.domain.Pageable page);
}
