package com.example.comptable.comptabilite.allocation;

import com.example.comptable.comptabilite.allocation.SituationTiersService.LigneSuivie;
import com.example.comptable.comptabilite.allocation.SituationTiersService.Role;
import com.example.comptable.entreprise.domain.Entreprise;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * Allocation AUTOMATIQUE des seuls cas CERTAINS : pour un meme tiers, une facture et un reglement ouverts du meme reste, et aucun autre de ce
 * montant de part ni d'autre. Tout doute (plusieurs factures de meme montant, montants differents, paiement groupe) reste au comptable : jamais
 * « la premiere ». Chaque allocation tourne dans sa propre transaction, un refus isole n'en affecte aucune autre.
 */
@Service
public class AllocationAutomatiqueService {

    private static final Logger log = LoggerFactory.getLogger(AllocationAutomatiqueService.class);

    private final SituationTiersService situationTiersService;
    private final PaymentAllocationService allocationService;
    private final TransactionTemplate tx;

    public AllocationAutomatiqueService(SituationTiersService situationTiersService, PaymentAllocationService allocationService,
                                        PlatformTransactionManager transactionManager) {
        this.situationTiersService = situationTiersService;
        this.allocationService = allocationService;
        this.tx = new TransactionTemplate(transactionManager);
        this.tx.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    }

    /** @return le nombre d'allocations posees. */
    public int allouerExacts(Entreprise entreprise) {
        int posees = 0;
        for (List<LigneSuivie> groupe : situationTiersService.lignesOuvertesParTiers(entreprise.getId()).values()) {
            Map<BigDecimal, List<LigneSuivie>> factures = parReste(groupe, Role.FACTURE);
            Map<BigDecimal, List<LigneSuivie>> reglements = parReste(groupe, Role.REGLEMENT);
            for (Map.Entry<BigDecimal, List<LigneSuivie>> e : factures.entrySet()) {
                List<LigneSuivie> f = e.getValue();
                List<LigneSuivie> r = reglements.get(e.getKey());
                if (r == null || f.size() != 1 || r.size() != 1 || !SituationTiersService.sensCompatibles(f.get(0).ligne(), r.get(0).ligne())) {
                    continue; // ambigu ou sans contrepartie : decision humaine
                }
                try {
                    tx.executeWithoutResult(s -> allocationService.allouer(entreprise.getId(), r.get(0).id(), f.get(0).id(), e.getKey(),
                            SourceAllocation.SYSTEM_EXACT, null));
                    posees++;
                } catch (RuntimeException ex) {
                    log.warn("Allocation automatique ignoree ({} -> {}) : {}", r.get(0).id(), f.get(0).id(), ex.getMessage());
                }
            }
        }
        return posees;
    }

    private static Map<BigDecimal, List<LigneSuivie>> parReste(List<LigneSuivie> groupe, Role role) {
        return groupe.stream().filter(l -> l.role() == role).collect(Collectors.groupingBy(l -> l.reste().stripTrailingZeros()));
    }
}
