package com.example.comptable.comptabilite.repository;

import org.springframework.data.jpa.repository.Query;
import com.example.comptable.comptabilite.domain.Immobilisation;
import com.example.comptable.comptabilite.domain.StatutImmobilisation;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

public interface ImmobilisationRepository extends JpaRepository<Immobilisation, UUID> {

    List<Immobilisation> findByEntrepriseIdOrderByDateAcquisitionAsc(UUID entrepriseId);

    List<Immobilisation> findByEntrepriseIdAndStatut(UUID entrepriseId, StatutImmobilisation statut);

    /** Immobilisations deja rattachees a une proposition d'acquisition (une facture peut porter plusieurs biens). */
    List<Immobilisation> findByAcquisitionProposalId(UUID acquisitionProposalId);

    /** Acces borne a l'entreprise : jamais un findById brut quand le contexte entreprise est connu. */
    java.util.Optional<Immobilisation> findByIdAndEntrepriseId(UUID id, UUID entrepriseId);

    /** Immobilisations encore au registre sans aucune consequence comptable d'acquisition rattachee (ni facture liee, ni comptabilisation). */
    @Query("select i from Immobilisation i join fetch i.compte where i.entreprise.id = :entrepriseId "
            + "and i.acquisitionProposal is null and i.statut <> com.example.comptable.comptabilite.domain.StatutImmobilisation.SORTIE")
    List<Immobilisation> rechercherSansSourceComptable(UUID entrepriseId);

    /** Immobilisations dont l'acquisition a ete comptabilisee directement (source = elle-meme, pas une facture d'achat). */
    @Query("select i from Immobilisation i join fetch i.compte join fetch i.acquisitionProposal p "
            + "where i.entreprise.id = :entrepriseId and p.sourceType = 'IMMOBILISATION'")
    List<Immobilisation> rechercherAcquisitionsDirectes(UUID entrepriseId);
}
