package com.example.comptable.comptabilite.allocation;

import com.example.comptable.comptabilite.allocation.PaymentAllocationService.Situation;
import com.example.comptable.comptabilite.domain.Facture;
import com.example.comptable.comptabilite.domain.LigneEcriture;
import com.example.comptable.comptabilite.repository.LigneEcritureRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/**
 * SEULE source du statut de paiement d'une facture cliente : {@code montant de la facture - somme des allocations actives = reste a payer}.
 * Le drapeau historique {@code Facture.statut = PAYEE} n'existe plus comme verite (il n'est plus ecrit, et les anciennes valeurs ont ete ramenees a
 * {@code VALIDEE} par la migration V57) : le recouvrement, la prevision de tresorerie, le score du dossier, le Copilot et les listes lisent TOUS ce
 * service. Un surplus de reglement reste non affecte sur le reglement ; il ne modifie jamais le montant de la facture.
 *
 * <p>Une facture non comptabilisee (sans ecriture) n'a pas de statut de paiement : elle n'est jamais « ouverte » au sens du recouvrement.</p>
 */
@Service
public class StatutPaiementFactureService {

    /** Une facture comptabilisee avec ce qui en est regle et ce qui reste. */
    public record FactureOuverte(Facture facture, BigDecimal montant, BigDecimal regle, BigDecimal reste, StatutPaiement statut) {
    }

    private final PaymentAllocationRepository allocationRepository;
    private final LigneEcritureRepository ligneEcritureRepository;
    private final PaymentAllocationService allocationService;

    public StatutPaiementFactureService(LigneEcritureRepository ligneEcritureRepository, PaymentAllocationService allocationService,
                                        PaymentAllocationRepository allocationRepository) {
        this.allocationRepository = allocationRepository;
        this.ligneEcritureRepository = ligneEcritureRepository;
        this.allocationService = allocationService;
    }

    /** Situation de paiement par ECRITURE de facturation (deux requetes quel que soit le nombre de factures). */
    @Transactional(readOnly = true)
    public Map<UUID, Situation> situationsParEcriture(UUID entrepriseId, Collection<UUID> ecritureIds) {
        List<UUID> ids = ecritureIds.stream().filter(Objects::nonNull).distinct().toList();
        if (ids.isEmpty()) {
            return Map.of();
        }
        // Facture client : la ligne de tiers est au debit ; une ligne de tiers au credit d'une meme ecriture (avoir) n'est pas la facture.
        Map<UUID, UUID> ligneParEcriture = new HashMap<>();
        for (LigneEcriture l : ligneEcritureRepository.rechercherLignesTiersDesEcritures(ids)) {
            if (l.getDebit().signum() > 0) {
                ligneParEcriture.putIfAbsent(l.getEcriture().getId(), l.getId());
            }
        }
        Map<UUID, Situation> parLigne = allocationService.situations(entrepriseId, ligneParEcriture.values());
        Map<UUID, Situation> resultat = new HashMap<>();
        ligneParEcriture.forEach((ecriture, ligne) -> {
            Situation s = parLigne.get(ligne);
            if (s != null) {
                resultat.put(ecriture, s);
            }
        });
        return resultat;
    }

    /** Parmi ces factures, celles qui ont encore un reste a payer, avec leur situation. L'ordre d'entree est conserve. */
    @Transactional(readOnly = true)
    public List<FactureOuverte> ouvertes(UUID entrepriseId, Collection<Facture> factures) {
        Map<UUID, Situation> situations = situationsParEcriture(entrepriseId, factures.stream().map(Facture::getEcritureId).toList());
        return factures.stream()
                .map(f -> {
                    Situation s = f.getEcritureId() == null ? null : situations.get(f.getEcritureId());
                    // Sans ecriture, aucune affectation n'est possible : la facture est entierement a payer.
                    return s != null ? new FactureOuverte(f, s.montant(), s.alloue(), s.reste(), s.statut())
                            : new FactureOuverte(f, f.montantTtc(), BigDecimal.ZERO, f.montantTtc(), StatutPaiement.NON_PAYEE);
                })
                .filter(o -> o.reste() != null && o.reste().signum() > 0)
                .toList();
    }

    /**
     * Affectations ACTIVES qui portent cette facture (liste vide : aucune, ou facture sans ecriture). Les affectations annulees (REVERSED) ne comptent pas : elles ne
     * portent plus aucun reglement. Sert d'invariant a l'annulation d'une facture.
     */
    @Transactional(readOnly = true)
    public List<PaymentAllocation> affectationsActives(UUID entrepriseId, Facture facture) {
        if (facture.getEcritureId() == null) {
            return List.of();
        }
        List<UUID> lignesFacture = ligneEcritureRepository.rechercherLignesTiersDesEcritures(List.of(facture.getEcritureId())).stream()
                .filter(l -> l.getDebit().signum() > 0).map(LigneEcriture::getId).toList();
        if (lignesFacture.isEmpty()) {
            return List.of();
        }
        return allocationRepository.activesDesLignes(lignesFacture).stream()
                .filter(a -> a.getEntrepriseId().equals(entrepriseId) && lignesFacture.contains(a.getInvoiceLineId())).toList();
    }

    /** Parmi ces factures, celles qui sont entierement reglees (reste = 0, montant > 0). */
    @Transactional(readOnly = true)
    public List<Facture> payees(UUID entrepriseId, Collection<Facture> factures) {
        Map<UUID, Situation> situations = situationsParEcriture(entrepriseId, factures.stream().map(Facture::getEcritureId).toList());
        return factures.stream()
                .filter(f -> f.getEcritureId() != null && situations.containsKey(f.getEcritureId())
                        && situations.get(f.getEcritureId()).statut() == StatutPaiement.PAYEE)
                .toList();
    }
}
