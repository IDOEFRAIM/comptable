package com.example.comptable.comptabilite.allocation;

import com.example.comptable.comptabilite.domain.LigneEcriture;
import com.example.comptable.comptabilite.domain.TypeJournal;
import com.example.comptable.comptabilite.repository.LigneEcritureRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * Photographie des lignes de TIERS d'une entreprise : quelles factures restent ouvertes, quels reglements ne sont pas (entierement) affectes.
 * Une facture est une ligne tiers d'un journal d'achats/ventes ; un reglement, une ligne tiers d'un journal de banque/caisse. Lecture seule,
 * en trois requetes quel que soit le volume (jamais une requete par ligne).
 */
@Service
public class SituationTiersService {

    public enum Role { FACTURE, REGLEMENT }

    /** Une ligne de tiers avec ce qui en est alloue et ce qui reste. */
    public record LigneSuivie(LigneEcriture ligne, Role role, BigDecimal montant, BigDecimal alloue) {
        public BigDecimal reste() {
            return montant.subtract(alloue).max(BigDecimal.ZERO);
        }

        public UUID id() {
            return ligne.getId();
        }

        /** Meme compte de tiers et meme tiers (un tiers absent de la ligne ne se confond avec aucun autre). */
        public String cleTiers() {
            return ligne.getCompte().getId() + "|" + (ligne.getTiers() != null ? ligne.getTiers().getId() : "");
        }
    }

    private static final Set<TypeJournal> JOURNAUX_FACTURES = EnumSet.of(TypeJournal.ACHATS, TypeJournal.VENTES);
    private static final Set<TypeJournal> JOURNAUX_REGLEMENTS = EnumSet.of(TypeJournal.BANQUE, TypeJournal.CAISSE);

    private final LigneEcritureRepository ligneEcritureRepository;
    private final PaymentAllocationRepository allocationRepository;

    public SituationTiersService(LigneEcritureRepository ligneEcritureRepository, PaymentAllocationRepository allocationRepository) {
        this.ligneEcritureRepository = ligneEcritureRepository;
        this.allocationRepository = allocationRepository;
    }

    /** Lignes de factures et de reglements, par cle de tiers, avec leur alloue ; seules celles qui ont encore un reste sont gardees. */
    @Transactional(readOnly = true)
    public Map<String, List<LigneSuivie>> lignesOuvertesParTiers(UUID entrepriseId) {
        List<LigneEcriture> factures = ligneEcritureRepository.rechercherLignesTiersParJournal(entrepriseId, JOURNAUX_FACTURES);
        List<LigneEcriture> reglements = ligneEcritureRepository.rechercherLignesTiersParJournal(entrepriseId, JOURNAUX_REGLEMENTS);

        Map<UUID, BigDecimal> alloueFactures = totaux(factures.isEmpty() ? List.of() : allocationRepository.totauxParFacture(ids(factures)));
        Map<UUID, BigDecimal> alloueReglements = totaux(ids(reglements).isEmpty() ? List.of() : allocationRepository.totauxParPaiement(ids(reglements)));

        List<LigneSuivie> toutes = new ArrayList<>();
        factures.forEach(l -> toutes.add(new LigneSuivie(l, Role.FACTURE, montantDe(l), alloueFactures.getOrDefault(l.getId(), BigDecimal.ZERO))));
        reglements.forEach(l -> toutes.add(new LigneSuivie(l, Role.REGLEMENT, montantDe(l), alloueReglements.getOrDefault(l.getId(), BigDecimal.ZERO))));
        return toutes.stream().filter(l -> l.reste().signum() > 0)
                .collect(Collectors.groupingBy(LigneSuivie::cleTiers, LinkedHashMap::new, Collectors.toList()));
    }

    /** Une facture et un reglement sont compatibles s'ils sont du cote oppose d'un meme compte de tiers. */
    public static boolean sensCompatibles(LigneEcriture facture, LigneEcriture reglement) {
        return facture.getDebit().signum() > 0 ? reglement.getCredit().signum() > 0 : reglement.getDebit().signum() > 0;
    }

    private static BigDecimal montantDe(LigneEcriture l) {
        return l.getDebit().signum() > 0 ? l.getDebit() : l.getCredit();
    }

    private static List<UUID> ids(List<LigneEcriture> lignes) {
        return lignes.stream().map(LigneEcriture::getId).toList();
    }

    private static Map<UUID, BigDecimal> totaux(List<Object[]> lignes) {
        Map<UUID, BigDecimal> m = new HashMap<>();
        for (Object[] l : lignes) {
            m.put((UUID) l[0], (BigDecimal) l[1]);
        }
        return m;
    }
}
