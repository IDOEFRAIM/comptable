package com.example.comptable.comptabilite.allocation;

import com.example.comptable.comptabilite.web.dto.FactureDto;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Ajoute aux factures leur statut de paiement CALCULE depuis les allocations de reglements (voir {@link StatutPaiementFactureService}, source unique).
 * Une facture non comptabilisee n'a pas de statut de paiement.
 */
@Service
public class PaiementFactureEnrichisseur {

    private final StatutPaiementFactureService statutPaiementFactureService;

    public PaiementFactureEnrichisseur(StatutPaiementFactureService statutPaiementFactureService) {
        this.statutPaiementFactureService = statutPaiementFactureService;
    }

    @Transactional(readOnly = true)
    public List<FactureDto> enrichir(UUID entrepriseId, List<FactureDto> factures) {
        Map<UUID, PaymentAllocationService.Situation> situations = statutPaiementFactureService.situationsParEcriture(entrepriseId,
                factures.stream().map(FactureDto::ecritureId).toList());
        if (situations.isEmpty()) {
            return factures;
        }
        return factures.stream().map(f -> {
            PaymentAllocationService.Situation s = f.ecritureId() != null ? situations.get(f.ecritureId()) : null;
            return s == null ? f : f.avecPaiement(s.statut().name(), s.alloue(), s.reste());
        }).toList();
    }
}
