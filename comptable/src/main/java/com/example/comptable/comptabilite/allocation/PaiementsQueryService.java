package com.example.comptable.comptabilite.allocation;

import com.example.comptable.banque.BankLabelNormalizer;
import com.example.comptable.comptabilite.allocation.AllocationDto.Allocation;
import com.example.comptable.comptabilite.allocation.AllocationDto.FactureCandidate;
import com.example.comptable.comptabilite.allocation.AllocationDto.ReglementARepartir;
import com.example.comptable.comptabilite.allocation.SituationTiersService.LigneSuivie;
import com.example.comptable.comptabilite.allocation.SituationTiersService.Role;
import com.example.comptable.comptabilite.domain.LigneEcriture;
import com.example.comptable.comptabilite.repository.LigneEcritureRepository;
import com.example.comptable.entreprise.service.EntrepriseAccessService;
import com.example.comptable.security.UtilisateurPrincipal;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

/** Lecture des reglements a repartir, des factures candidates et des allocations : tout est presente avec piece, date, tiers et reste. */
@Service
public class PaiementsQueryService {

    static final int MAX_FACTURES = 30;

    private final EntrepriseAccessService entrepriseAccessService;
    private final SituationTiersService situationTiersService;
    private final PaymentAllocationRepository allocationRepository;
    private final LigneEcritureRepository ligneEcritureRepository;

    public PaiementsQueryService(EntrepriseAccessService entrepriseAccessService, SituationTiersService situationTiersService,
                                 PaymentAllocationRepository allocationRepository, LigneEcritureRepository ligneEcritureRepository) {
        this.entrepriseAccessService = entrepriseAccessService;
        this.situationTiersService = situationTiersService;
        this.allocationRepository = allocationRepository;
        this.ligneEcritureRepository = ligneEcritureRepository;
    }

    @Transactional(readOnly = true)
    public List<ReglementARepartir> reglementsARepartir(UUID entrepriseId, UtilisateurPrincipal principal) {
        entrepriseAccessService.verifierAcces(entrepriseId, principal);
        return situationTiersService.lignesOuvertesParTiers(entrepriseId).values().stream().flatMap(List::stream)
                .filter(l -> l.role() == Role.REGLEMENT)
                .sorted(Comparator.comparing((LigneSuivie l) -> l.ligne().getEcriture().getDateEcriture()).reversed())
                .map(l -> new ReglementARepartir(l.id(), l.ligne().getEcriture().getNumeroPiece(), l.ligne().getEcriture().getDateEcriture(),
                        libelle(l.ligne()), tiers(l.ligne()), l.montant(), l.alloue(), l.reste()))
                .toList();
    }

    /** Factures ouvertes que ce reglement pourrait solder (meme tiers, sens oppose), filtrables par recherche libre sur piece, libelle ou tiers. */
    @Transactional(readOnly = true)
    public List<FactureCandidate> facturesCandidates(UUID entrepriseId, UUID paymentLineId, String q, UtilisateurPrincipal principal) {
        entrepriseAccessService.verifierAcces(entrepriseId, principal);
        LigneEcriture reglement = ligneEcritureRepository.findById(paymentLineId)
                .filter(l -> l.getEcriture().getEntreprise().getId().equals(entrepriseId))
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Ligne d'ecriture introuvable"));
        String recherche = q == null ? "" : BankLabelNormalizer.normaliser(q);
        return situationTiersService.lignesOuvertesParTiers(entrepriseId).values().stream().flatMap(List::stream)
                .filter(l -> l.role() == Role.FACTURE && l.ligne().getCompte().getId().equals(reglement.getCompte().getId())
                        && SituationTiersService.sensCompatibles(l.ligne(), reglement))
                .filter(l -> reglement.getTiers() == null || l.ligne().getTiers() == null || l.ligne().getTiers().getId().equals(reglement.getTiers().getId()))
                .filter(l -> recherche.isEmpty() || BankLabelNormalizer.normaliser(l.ligne().getEcriture().getNumeroPiece() + " " + libelle(l.ligne())
                        + " " + tiers(l.ligne())).contains(recherche))
                .sorted(Comparator.comparing((LigneSuivie l) -> l.ligne().getEcriture().getDateEcriture()))
                .limit(MAX_FACTURES)
                .map(l -> new FactureCandidate(l.id(), l.ligne().getEcriture().getNumeroPiece(), l.ligne().getEcriture().getDateEcriture(),
                        libelle(l.ligne()), tiers(l.ligne()), l.montant(), l.alloue(), l.reste(),
                        PaymentAllocationService.statutDe(l.montant(), l.alloue()).name()))
                .toList();
    }

    @Transactional(readOnly = true)
    public List<Allocation> allocations(UUID entrepriseId, UtilisateurPrincipal principal) {
        entrepriseAccessService.verifierAcces(entrepriseId, principal);
        List<PaymentAllocation> toutes = allocationRepository.findByEntreprise(entrepriseId);
        Set<UUID> ids = new HashSet<>();
        toutes.forEach(a -> {
            ids.add(a.getPaymentLineId());
            ids.add(a.getInvoiceLineId());
        });
        Map<UUID, LigneEcriture> lignes = ids.isEmpty() ? Map.of()
                : ligneEcritureRepository.chargerAvecEcriture(ids).stream().collect(Collectors.toMap(LigneEcriture::getId, l -> l, (a, b) -> a));
        return toutes.stream().map(a -> {
            LigneEcriture p = lignes.get(a.getPaymentLineId());
            LigneEcriture f = lignes.get(a.getInvoiceLineId());
            return new Allocation(a.getId(), a.getPaymentLineId(), a.getInvoiceLineId(), p != null ? p.getEcriture().getNumeroPiece() : null,
                    f != null ? f.getEcriture().getNumeroPiece() : null, f != null ? tiers(f) : null, a.getAllocatedAmount(), a.getStatus().name(),
                    a.getSource().name(), a.getCreatedAt(), a.getReversedAt(), a.getReversalReason());
        }).toList();
    }

    @Transactional(readOnly = true)
    public List<FactureCandidate> facturesOuvertes(UUID entrepriseId, UtilisateurPrincipal principal) {
        entrepriseAccessService.verifierAcces(entrepriseId, principal);
        return situationTiersService.lignesOuvertesParTiers(entrepriseId).values().stream().flatMap(List::stream)
                .filter(l -> l.role() == Role.FACTURE)
                .sorted(Comparator.comparing((LigneSuivie l) -> l.ligne().getEcriture().getDateEcriture()))
                .map(l -> new FactureCandidate(l.id(), l.ligne().getEcriture().getNumeroPiece(), l.ligne().getEcriture().getDateEcriture(),
                        libelle(l.ligne()), tiers(l.ligne()), l.montant(), l.alloue(), l.reste(),
                        PaymentAllocationService.statutDe(l.montant(), l.alloue()).name()))
                .toList();
    }

    private static String libelle(LigneEcriture l) {
        return l.getLibelle() != null ? l.getLibelle() : l.getEcriture().getLibelle();
    }

    private static String tiers(LigneEcriture l) {
        return l.getTiers() != null && l.getTiers().getNom() != null ? l.getTiers().getNom() : l.getCompte().getIntitule();
    }
}
