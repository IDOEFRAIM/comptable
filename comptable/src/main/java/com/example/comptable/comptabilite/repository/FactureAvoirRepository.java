package com.example.comptable.comptabilite.repository;

import com.example.comptable.comptabilite.domain.FactureAvoir;
import com.example.comptable.comptabilite.domain.StatutFactureAvoir;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import java.util.List;
import java.util.UUID;

public interface FactureAvoirRepository extends JpaRepository<FactureAvoir, UUID> {

    @Query("select distinct a from FactureAvoir a "
            + "join fetch a.tiers join fetch a.factureOrigine "
            + "left join fetch a.lignes l left join fetch l.ligneFactureOrigine "
            + "where a.entreprise.id = :entrepriseId "
            + "order by a.dateEmission desc, a.numero desc")
    List<FactureAvoir> findByEntrepriseIdFetchAll(UUID entrepriseId);

    /** Quantite deja creditee par avoir VALIDEE, par ligne de facture d'origine : empeche un
     * deuxieme avoir de crediter plus que ce qu'il reste a crediter sur une ligne. */
    @Query("select l.ligneFactureOrigine.id, coalesce(sum(l.quantite), 0) from LigneFactureAvoir l "
            + "where l.avoir.factureOrigine.id = :factureId and l.avoir.statut = :statut "
            + "group by l.ligneFactureOrigine.id")
    List<Object[]> sommeQuantitesCrediteesParLigne(UUID factureId, StatutFactureAvoir statut);

    long countByEntrepriseId(UUID entrepriseId);

    /** Vrai si la facture a deja des avoirs d'un statut donne (annulation d'une facture deja creditee : refusee, l'ecriture serait contrepassee deux fois). */
    boolean existsByFactureOrigineIdAndStatut(UUID factureId, StatutFactureAvoir statut);
}
