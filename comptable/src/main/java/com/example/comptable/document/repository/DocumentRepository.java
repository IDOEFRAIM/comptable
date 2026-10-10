package com.example.comptable.document.repository;

import com.example.comptable.document.domain.Document;
import com.example.comptable.document.domain.StatutDocument;
import com.example.comptable.document.domain.TypeDocument;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface DocumentRepository extends JpaRepository<Document, UUID>,
        org.springframework.data.jpa.repository.JpaSpecificationExecutor<Document> {

    List<Document> findByEntrepriseIdOrderByCreatedAtDesc(UUID entrepriseId);

    List<Document> findByEntrepriseIdAndStatutOrderByCreatedAtDesc(UUID entrepriseId, StatutDocument statut);

    Optional<Document> findByEcritureId(UUID ecritureId);

    long countByEntrepriseIdAndStatut(UUID entrepriseId, StatutDocument statut);

    long countByEntrepriseId(UUID entrepriseId);

    long countByEntrepriseIdAndStatutAndTypeDocumentInAndEcritureIdIsNull(
            UUID entrepriseId, StatutDocument statut, Collection<TypeDocument> typesDocument);

    /** Doublons stricts potentiels (agent.fraude, Sprint 7) : meme entreprise, meme montant TTC
     * exact, date de piece dans la fenetre donnee. ERREUR exclu : une extraction en echec n'est
     * pas une piece "deja enregistree" comparable. */
    @Query("select d from Document d where d.entreprise.id = :entrepriseId and d.montantTtc = :montantTtc "
            + "and d.datePiece between :debut and :fin and d.statut <> 'ERREUR'")
    List<Document> rechercherDoublonsPotentiels(UUID entrepriseId, BigDecimal montantTtc, LocalDate debut, LocalDate fin);

    /** Historique des pieces d'un fournisseur donne (outil {@code consulter_historique_fournisseur}
     * de l'agent comptable autonome, cf. agent.tools). {@code fournisseur} n'est pas une colonne
     * dediee (stocke dans le JSON libre {@link Document#getDonneesOcr()}, cf. DocumentService) :
     * requete native Postgres via l'operateur JSONB {@code ->>}, filtrage approximatif (ILIKE)
     * pour tolerer les variations mineures d'un fournisseur a l'autre (casse, abreviation). */
    @Query(value = "SELECT d.* FROM document d WHERE d.entreprise_id = :entrepriseId "
            + "AND d.donnees_ocr ->> 'fournisseur' ILIKE CONCAT('%', :fournisseur, '%') "
            + "AND d.statut <> 'ERREUR' ORDER BY d.date_piece DESC NULLS LAST LIMIT :limite",
            nativeQuery = true)
    List<Document> rechercherParFournisseur(@Param("entrepriseId") UUID entrepriseId,
                                             @Param("fournisseur") String fournisseur,
                                             @Param("limite") int limite);

    /** Candidats de rapprochement (agent.matching.MatchingContextBuilder) : pieces pas encore
     * liees a une ecriture ({@code ecritureId is null} - donc encore "en attente" d'un
     * rapprochement/comptabilisation), dans une fenetre de dates raisonnable. Sert de source
     * pour les candidats SUPPLIER_INVOICE (type FACTURE_ACHAT) et DOCUMENT (autres types) du
     * Universal Matching Engine - jamais toutes les pieces de l'entreprise. */
    @Query("select d from Document d where d.entreprise.id = :entrepriseId and d.ecritureId is null "
            + "and d.statut <> 'ERREUR' and d.datePiece between :debut and :fin "
            + "order by d.datePiece desc")
    List<Document> rechercherCandidatsRapprochement(UUID entrepriseId, LocalDate debut, LocalDate fin);

    /** Factures deja traitees par l'extraction/validation mais jamais comptabilisees (aucune ecriture liee). */
    @Query("select d from Document d where d.entreprise.id = :entrepriseId and d.ecritureId is null "
            + "and d.typeDocument in :types and d.statut in :statuts order by d.createdAt desc")
    List<Document> rechercherNonComptabilises(UUID entrepriseId, java.util.Collection<com.example.comptable.document.domain.TypeDocument> types,
                                              java.util.Collection<StatutDocument> statuts);

    /** Pieces non encore comptabilisees dont HT + TVA differe du TTC de plus de la tolerance (HT et TTC renseignes). */
    @Query("select d from Document d where d.entreprise.id = :entrepriseId and d.ecritureId is null "
            + "and d.statut <> com.example.comptable.document.domain.StatutDocument.ERREUR "
            + "and d.montantHt is not null and d.montantTtc is not null "
            + "and abs(d.montantHt + coalesce(d.montantTva, 0) - d.montantTtc) > :tolerance")
    List<Document> rechercherMontantsIncoherents(UUID entrepriseId, java.math.BigDecimal tolerance);

    // ------------------------------------------------------------------ Phase C : identite, doublons, rapprochement

    /** Pieces dont le contenu (hash) existe plusieurs fois dans l'entreprise, hors doublons deja confirmes par un humain. */
    @Query("select d from Document d where d.entreprise.id = :entrepriseId and d.doublonDeId is null and d.contentHash in ("
            + "select d2.contentHash from Document d2 where d2.entreprise.id = :entrepriseId and d2.contentHash is not null "
            + "and d2.doublonDeId is null group by d2.contentHash having count(d2) > 1) "
            + "order by d.contentHash, d.createdAt, d.id")
    List<Document> rechercherDoublonsExacts(UUID entrepriseId);

    /** Pieces partageant (tiers, numero de facture normalise) avec au moins une autre piece non confirmee comme doublon. */
    @Query("select d from Document d where d.entreprise.id = :entrepriseId and d.doublonDeId is null "
            + "and d.tiersId is not null and d.numeroFactureNormalise is not null and exists ("
            + "select 1 from Document d2 where d2.entreprise.id = :entrepriseId and d2.id <> d.id and d2.doublonDeId is null "
            + "and d2.tiersId = d.tiersId and d2.numeroFactureNormalise = d.numeroFactureNormalise) "
            + "order by d.tiersId, d.numeroFactureNormalise, d.createdAt, d.id")
    List<Document> rechercherFacturesEnDouble(UUID entrepriseId);

    /** Pieces d'un meme tiers au meme TTC exact (candidates au doublon suspect ; les dates se comparent cote service). */
    @Query("select d from Document d where d.entreprise.id = :entrepriseId and d.doublonDeId is null "
            + "and d.tiersId is not null and d.montantTtc is not null and exists ("
            + "select 1 from Document d2 where d2.entreprise.id = :entrepriseId and d2.id <> d.id and d2.doublonDeId is null "
            + "and d2.tiersId = d.tiersId and d2.montantTtc = d.montantTtc) "
            + "order by d.tiersId, d.montantTtc, d.datePiece, d.createdAt, d.id")
    List<Document> rechercherDoublonsSuspects(UUID entrepriseId);

    @Query("select d from Document d where d.entreprise.id = :entrepriseId and d.doublonDeId is null "
            + "and d.statut = com.example.comptable.document.domain.StatutDocument.ERREUR and d.valideAt is null")
    List<Document> rechercherIllisibles(UUID entrepriseId);

    @Query("select d from Document d where d.entreprise.id = :entrepriseId and d.doublonDeId is null and d.valideAt is null "
            + "and (d.statutRapprochement = com.example.comptable.document.domain.StatutRapprochementDocument.AMBIGU "
            + "or d.statutClassification = com.example.comptable.document.domain.QualiteClassification.AMBIGU)")
    List<Document> rechercherAmbigus(UUID entrepriseId);

    /** Factures non comptabilisees ni rapprochees depuis trop longtemps (aucune facture, aucune ecriture, tiers souvent connu). */
    @Query("select d from Document d where d.entreprise.id = :entrepriseId and d.doublonDeId is null and d.valideAt is null "
            + "and d.ecritureId is null and d.cibleId is null and d.createdAt < :avant "
            + "and d.typeDocument in (com.example.comptable.document.domain.TypeDocument.FACTURE_ACHAT, com.example.comptable.document.domain.TypeDocument.FACTURE_VENTE) "
            + "and d.statut = com.example.comptable.document.domain.StatutDocument.TRAITE and d.statutRapprochement = com.example.comptable.document.domain.StatutRapprochementDocument.NON_RAPPROCHE "
            + "order by d.createdAt")
    List<Document> rechercherNonRapprochesAnciens(UUID entrepriseId, java.time.OffsetDateTime avant);

    record EcartMontantRow(UUID documentId, UUID factureId, String numeroFacture, java.math.BigDecimal montantDocument,
                           java.math.BigDecimal montantFacture) {
    }

    /** Piece et facture fournisseur de meme fournisseur et de meme numero (fournisseur) mais de TTC different. */
    @Query("select new com.example.comptable.document.repository.DocumentRepository$EcartMontantRow(d.id, f.id, d.numeroFacture, d.montantTtc, f.montantTtc) "
            + "from Document d, FactureFournisseur f where d.entreprise.id = :entrepriseId and d.doublonDeId is null and d.valideAt is null "
            + "and d.cibleId is null and d.numeroFactureNormalise is not null and d.montantTtc is not null "
            + "and f.entreprise.id = :entrepriseId and f.fournisseur.id = d.tiersId "
            + "and f.numeroFournisseurNormalise = d.numeroFactureNormalise and abs(f.montantTtc - d.montantTtc) > 0.01")
    List<EcartMontantRow> rechercherEcartsDeMontant(UUID entrepriseId);

    java.util.Optional<Document> findByIdAndEntrepriseId(UUID id, UUID entrepriseId);

    List<Document> findByEntrepriseIdAndCibleTypeAndCibleId(UUID entrepriseId, String cibleType, UUID cibleId);

    boolean existsByEntrepriseIdAndContentHashAndIdNot(UUID entrepriseId, String contentHash, UUID id);

    /** Pieces rapprochees AUTOMATIQUEMENT (cas certain) que personne n'a encore validees : base de la validation en masse. */
    @Query("select d from Document d where d.entreprise.id = :entrepriseId and d.doublonDeId is null and d.valideAt is null "
            + "and d.statutRapprochement = com.example.comptable.document.domain.StatutRapprochementDocument.RAPPROCHE "
            + "and d.rapprochePar = com.example.comptable.document.domain.OrigineRapprochement.SYSTEME order by d.createdAt")
    List<Document> rechercherRapprochesNonValides(UUID entrepriseId);

    /** Pieces rapprochees a la facture fournisseur qui porte cette ecriture (justificatif « par la facture »). */
    @Query("select d from Document d, FactureFournisseur ff where d.entreprise.id = :entrepriseId and d.cibleType = 'FACTURE_FOURNISSEUR' "
            + "and d.cibleId = ff.id and ff.ecritureId = :ecritureId and d.doublonDeId is null order by d.createdAt")
    List<Document> findViaFactureFournisseur(UUID entrepriseId, UUID ecritureId);

    /** Une piece recue attend une decision du comptable (plusieurs demandes plausibles) pour ce montant : on ne relance pas ce client. */
    @Query("select count(d) > 0 from Document d where d.entreprise.id = :entrepriseId and d.statutDemande = com.example.comptable.document.domain.StatutDemandeDocument.AMBIGU "
            + "and d.doublonDeId is null and d.montantTtc is not null and abs(d.montantTtc - :montant) <= 0.01")
    boolean existsAmbiguPourMontant(UUID entrepriseId, java.math.BigDecimal montant);

    @Query("select d from Document d where d.entreprise.id = :entrepriseId and d.doublonDeId is null and d.statutDemande = :statut order by d.createdAt")
    List<Document> rechercherParStatutDemande(UUID entrepriseId, com.example.comptable.document.domain.StatutDemandeDocument statut);

    /** Pieces portees par un lot d'ecritures (une requete) : investigation, pas d'appel par ecriture. */
    @Query("select d from Document d where d.entreprise.id = :entrepriseId and d.ecritureId in :ecritureIds")
    List<Document> deLEcritures(UUID entrepriseId, java.util.Collection<UUID> ecritureIds);
}
