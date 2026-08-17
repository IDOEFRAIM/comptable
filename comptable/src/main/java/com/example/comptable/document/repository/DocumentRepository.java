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

public interface DocumentRepository extends JpaRepository<Document, UUID> {

    List<Document> findByEntrepriseIdOrderByCreatedAtDesc(UUID entrepriseId);

    List<Document> findByEntrepriseIdAndStatutOrderByCreatedAtDesc(UUID entrepriseId, StatutDocument statut);

    Optional<Document> findByEcritureId(UUID ecritureId);

    long countByEntrepriseIdAndStatut(UUID entrepriseId, StatutDocument statut);

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
}
