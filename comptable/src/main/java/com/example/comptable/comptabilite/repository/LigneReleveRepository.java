package com.example.comptable.comptabilite.repository;

import com.example.comptable.comptabilite.domain.LigneReleve;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import java.util.List;
import java.util.UUID;

public interface LigneReleveRepository extends JpaRepository<LigneReleve, UUID> {

    /** Lignes d'ecriture deja pointees par un releve quelconque de ce compte, pour eviter un double appariement. */
    @Query("select l.ligneEcritureId from LigneReleve l "
            + "where l.ligneEcritureId is not null and l.releve.compte.id = :compteId")
    List<UUID> rechercherLigneEcritureIdsPointees(UUID compteId);

    /** Empreintes deja connues pour ce compte parmi celles proposees : detection des doublons EXACTS d'un reimport. */
    @Query("select l.contentFingerprint from LigneReleve l where l.entrepriseId = :entrepriseId and l.compteId = :compteId "
            + "and l.contentFingerprint in :empreintes")
    List<String> empreintesConnues(UUID entrepriseId, UUID compteId, java.util.Collection<String> empreintes);

    /** Transactions deja importees sur une periode : base de la detection des doublons POSSIBLES. */
    @Query("select l from LigneReleve l where l.entrepriseId = :entrepriseId and l.compteId = :compteId "
            + "and l.dateOperation between :debut and :fin")
    List<com.example.comptable.comptabilite.domain.LigneReleve> dejaImporteesSurPeriode(UUID entrepriseId, UUID compteId,
                                                                                         java.time.LocalDate debut, java.time.LocalDate fin);

    /** Verrou d'ecriture sur une transaction : deux confirmations concurrentes se serialisent. */
    @org.springframework.data.jpa.repository.Lock(jakarta.persistence.LockModeType.PESSIMISTIC_WRITE)
    @Query("select l from LigneReleve l where l.id = :id")
    java.util.Optional<com.example.comptable.comptabilite.domain.LigneReleve> verrouiller(UUID id);

    /** Transactions suspectes de doublon et toujours non rapprochees (celles deja rapprochees ne posent plus de probleme). */
    @Query("select l from LigneReleve l join fetch l.releve r join fetch r.compte where l.entrepriseId = :entrepriseId "
            + "and l.duplicateStatus = 'POSSIBLE_DUPLICATE' and l.ligneEcritureId is null")
    List<com.example.comptable.comptabilite.domain.LigneReleve> doublonsPossiblesNonRapproches(UUID entrepriseId);

    /** Transactions d'une entreprise sur une periode, avec leur releve et leur compte (une requete) : base de l'ecran Banque. */
    @Query("select l from LigneReleve l join fetch l.releve r join fetch r.compte where l.entrepriseId = :entrepriseId "
            + "and l.dateOperation between :debut and :fin order by l.dateOperation desc, l.id")
    List<com.example.comptable.comptabilite.domain.LigneReleve> transactionsDeLaPeriode(UUID entrepriseId, java.time.LocalDate debut, java.time.LocalDate fin);

    String CONF = "exists (select 1 from com.example.comptable.banque.BankInternalTransfer t where t.status = com.example.comptable.banque.StatutReconciliation.CONFIRMED "
            + "and (t.debitTransactionId = l.id or t.creditTransactionId = l.id))";
    String VIVANT = "exists (select 1 from com.example.comptable.banque.BankInternalTransfer t where t.status in (com.example.comptable.banque.StatutReconciliation.PROPOSED, "
            + "com.example.comptable.banque.StatutReconciliation.CONFIRMED) and (t.debitTransactionId = l.id or t.creditTransactionId = l.id))";
    String PROPOSE = "exists (select 1 from com.example.comptable.banque.BankInternalTransfer t where t.status = com.example.comptable.banque.StatutReconciliation.PROPOSED "
            + "and (t.debitTransactionId = l.id or t.creditTransactionId = l.id))";
    /**
     * Filtre de l'ecran Banque, cote base. Etats : TOUS ; RAPP (pointee a une ecriture, hors virement) ; V_CONF (virement interne confirme) ;
     * NON_EXPLIQUEES (tout le reste : leur statut fin dependra du classement).
     */
    String FILTRE = " where l.entrepriseId = :entrepriseId and l.dateOperation between :debut and :fin "
            + "and (:tousComptes = true or l.compteId = :compteId) "
            + "and (:sansRecherche = true or upper(coalesce(l.libelleNormalise, l.libelle, '')) like :motif or upper(coalesce(l.reference, '')) like :motif "
            + "or str(abs(l.montant)) like :motif) "
            + "and (:etat = 'TOUS' or (:etat = 'V_CONF' and " + CONF + ") "
            + "or (:etat = 'RAPP' and l.ligneEcritureId is not null and not " + VIVANT + ") "
            + "or (:etat = 'NON_EXPLIQUEES' and not " + CONF + " and (l.ligneEcritureId is null or " + PROPOSE + ")))";

    @Query(value = "select l.id from LigneReleve l" + FILTRE, countQuery = "select count(l) from LigneReleve l" + FILTRE)
    org.springframework.data.domain.Page<UUID> pageIds(UUID entrepriseId, java.time.LocalDate debut, java.time.LocalDate fin, boolean tousComptes, UUID compteId,
                                                       boolean sansRecherche, String motif, String etat, org.springframework.data.domain.Pageable pageable);

    @Query("select l from LigneReleve l join fetch l.releve r join fetch r.compte" + FILTRE)
    List<com.example.comptable.comptabilite.domain.LigneReleve> filtrees(UUID entrepriseId, java.time.LocalDate debut, java.time.LocalDate fin, boolean tousComptes,
                                                                          UUID compteId, boolean sansRecherche, String motif, String etat,
                                                                          org.springframework.data.domain.Sort sort);

    @Query("select count(l) from LigneReleve l" + FILTRE)
    long compterFiltrees(UUID entrepriseId, java.time.LocalDate debut, java.time.LocalDate fin, boolean tousComptes, UUID compteId, boolean sansRecherche,
                         String motif, String etat);

    @Query("select l from LigneReleve l join fetch l.releve r join fetch r.compte where l.id in :ids")
    List<com.example.comptable.comptabilite.domain.LigneReleve> avecReleve(java.util.Collection<UUID> ids);

    /** Transactions non pointees d'un releve dont le montant (signe) figure dans la liste : seules celles-ci peuvent se disputer les memes mouvements. */
    @Query("select l from LigneReleve l join fetch l.releve r join fetch r.compte where r.id = :releveId and l.ligneEcritureId is null and l.montant in :montants")
    List<com.example.comptable.comptabilite.domain.LigneReleve> nonPointeesDeMemeMontant(UUID releveId, java.util.Collection<java.math.BigDecimal> montants);

    /** Transactions d'une entreprise pas encore rapprochees a une ecriture (base de la detection des virements internes). */
    @Query("select l from LigneReleve l where l.entrepriseId = :entrepriseId and l.ligneEcritureId is null")
    List<com.example.comptable.comptabilite.domain.LigneReleve> nonRapprochees(UUID entrepriseId);

    /** Transactions anterieures a la Phase D (sans empreinte), dans un ordre deterministe : reprise des empreintes. */
    @Query("select l from LigneReleve l join fetch l.releve r where l.contentFingerprint is null "
            + "order by l.entrepriseId, l.compteId, r.createdAt, r.id, l.dateOperation, l.id")
    List<com.example.comptable.comptabilite.domain.LigneReleve> sansEmpreinte();

    /** {@code [entrepriseId, compteId, empreinte, transactionId]} des empreintes deja posees. */
    @Query("select l.entrepriseId, l.compteId, l.contentFingerprint, l.id from LigneReleve l where l.contentFingerprint is not null")
    List<Object[]> empreintesExistantes();

    /** Montant net et nombre des transactions d'un releve sans ecriture (agregat SQL : un releve de 50 000 lignes n'est jamais charge). */
    @Query("select coalesce(sum(l.montant), 0), count(l) from LigneReleve l where l.releve.id = :releveId and l.ligneEcritureId is null")
    List<Object[]> agregatNonPointees(UUID releveId);

    /** Idem, restreint aux jambes d'un virement interne confirme (deja expliquees, mais toujours sans ecriture). */
    @Query("select coalesce(sum(l.montant), 0), count(l) from LigneReleve l where l.releve.id = :releveId and l.ligneEcritureId is null and " + CONF)
    List<Object[]> agregatJambesDeVirement(UUID releveId);

    @Query("select l from LigneReleve l join fetch l.releve r join fetch r.compte where r.id = :releveId and l.ligneEcritureId is null and not " + CONF
            + " order by l.dateOperation desc, l.id")
    List<com.example.comptable.comptabilite.domain.LigneReleve> premieresSansEcriture(UUID releveId, org.springframework.data.domain.Pageable page);
}
