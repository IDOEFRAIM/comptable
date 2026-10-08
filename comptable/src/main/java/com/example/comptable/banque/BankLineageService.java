package com.example.comptable.banque;

import com.example.comptable.comptabilite.allocation.PaymentAllocation;
import com.example.comptable.comptabilite.allocation.PaymentAllocationRepository;
import com.example.comptable.comptabilite.domain.LigneEcriture;
import com.example.comptable.comptabilite.domain.LigneReleve;
import com.example.comptable.comptabilite.repository.LigneEcritureRepository;
import com.example.comptable.comptabilite.repository.LigneReleveRepository;
import com.example.comptable.entreprise.service.EntrepriseAccessService;
import com.example.comptable.security.UtilisateurPrincipal;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * Prolongement bancaire du lineage, lisible dans les deux sens a partir d'une ECRITURE :
 * <pre>
 * transaction bancaire -&gt; rapprochement -&gt; ligne de tresorerie du reglement -&gt; (meme ecriture) ligne tiers -&gt; affectation -&gt; facture(s)
 * </pre>
 * Une transaction n'est jamais reliee directement a une facture : le lien passe toujours par l'ecriture du reglement.
 */
@Service
public class BankLineageService {

    public record TransactionLiee(UUID transactionId, LocalDate date, String libelle, BigDecimal montant, String statut, String source, BigDecimal confiance) {
    }

    /** {@code role} : REGLEMENT (cette ecriture est le reglement, la contrepartie est une facture) ou FACTURE (la contrepartie est un reglement). */
    public record AffectationLiee(UUID allocationId, String role, UUID contrepartieEcritureId, String contrepartiePiece, BigDecimal montant, String source) {
    }

    public record DepuisEcritureBanque(UUID ecritureId, String numeroPiece, List<TransactionLiee> transactions, List<AffectationLiee> affectations) {
    }

    private final EntrepriseAccessService entrepriseAccessService;
    private final LigneEcritureRepository ligneEcritureRepository;
    private final LigneReleveRepository ligneReleveRepository;
    private final BankReconciliationRepository reconciliationRepository;
    private final PaymentAllocationRepository allocationRepository;

    public BankLineageService(EntrepriseAccessService entrepriseAccessService, LigneEcritureRepository ligneEcritureRepository,
                              LigneReleveRepository ligneReleveRepository, BankReconciliationRepository reconciliationRepository,
                              PaymentAllocationRepository allocationRepository) {
        this.entrepriseAccessService = entrepriseAccessService;
        this.ligneEcritureRepository = ligneEcritureRepository;
        this.ligneReleveRepository = ligneReleveRepository;
        this.reconciliationRepository = reconciliationRepository;
        this.allocationRepository = allocationRepository;
    }

    @Transactional(readOnly = true)
    public DepuisEcritureBanque depuisEcriture(UUID entrepriseId, UUID ecritureId, UtilisateurPrincipal principal) {
        entrepriseAccessService.verifierAcces(entrepriseId, principal);
        List<LigneEcriture> lignes = ligneEcritureRepository.chargerDeLEcriture(ecritureId);
        if (lignes.isEmpty() || !lignes.get(0).getEcriture().getEntreprise().getId().equals(entrepriseId)) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Ecriture introuvable");
        }
        Set<UUID> ids = lignes.stream().map(LigneEcriture::getId).collect(Collectors.toSet());

        // Banque -> ecriture : les transactions rapprochees (ou proposees) a une ligne de cette ecriture.
        List<TransactionLiee> transactions = new ArrayList<>();
        List<BankReconciliation> rapprochements = new ArrayList<>();
        for (UUID id : ids) {
            reconciliationRepository.findConfirmeeSurCible(id).ifPresent(rapprochements::add);
        }
        Map<UUID, LigneReleve> releves = ligneReleveRepository.findAllById(rapprochements.stream().map(BankReconciliation::getBankTransactionId).toList())
                .stream().collect(Collectors.toMap(LigneReleve::getId, l -> l));
        for (BankReconciliation r : rapprochements) {
            LigneReleve t = releves.get(r.getBankTransactionId());
            if (t != null && t.getEntrepriseId().equals(entrepriseId)) {
                transactions.add(new TransactionLiee(t.getId(), t.getDateOperation(), t.getLibelle(), t.getMontant(), r.getStatus().name(), r.getSource().name(),
                        r.getConfidence()));
            }
        }

        // Reglement <-> factures : les affectations actives des lignes de tiers de cette ecriture.
        List<AffectationLiee> affectations = new ArrayList<>();
        Set<UUID> contreparties = new HashSet<>();
        List<Object[]> liens = new ArrayList<>();
        for (UUID id : ids) {
            for (PaymentAllocation a : allocationRepository.findActivesDuPaiement(id)) {
                liens.add(new Object[] {a, "REGLEMENT", a.getInvoiceLineId()});
                contreparties.add(a.getInvoiceLineId());
            }
            for (PaymentAllocation a : allocationRepository.findActivesDeLaFacture(id)) {
                liens.add(new Object[] {a, "FACTURE", a.getPaymentLineId()});
                contreparties.add(a.getPaymentLineId());
            }
        }
        Map<UUID, LigneEcriture> lignesContrepartie = contreparties.isEmpty() ? Map.of()
                : ligneEcritureRepository.chargerAvecEcriture(contreparties).stream().collect(Collectors.toMap(LigneEcriture::getId, l -> l, (a, b) -> a));
        for (Object[] lien : liens) {
            PaymentAllocation a = (PaymentAllocation) lien[0];
            LigneEcriture contrepartie = lignesContrepartie.get((UUID) lien[2]);
            if (contrepartie != null && contrepartie.getEcriture().getEntreprise().getId().equals(entrepriseId)) {
                affectations.add(new AffectationLiee(a.getId(), (String) lien[1], contrepartie.getEcriture().getId(), contrepartie.getEcriture().getNumeroPiece(),
                        a.getAllocatedAmount(), a.getSource().name()));
            }
        }
        return new DepuisEcritureBanque(ecritureId, lignes.get(0).getEcriture().getNumeroPiece(), transactions, affectations);
    }
}
