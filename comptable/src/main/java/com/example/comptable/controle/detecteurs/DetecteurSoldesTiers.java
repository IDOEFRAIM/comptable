package com.example.comptable.controle.detecteurs;

import com.example.comptable.comptabilite.domain.TypeTiers;
import com.example.comptable.comptabilite.repository.LigneEcritureRepository;
import com.example.comptable.comptabilite.repository.LigneEcritureRepository.SoldeTiersRow;
import com.example.comptable.controle.ControlCategory;
import com.example.comptable.controle.ControlCodes;
import com.example.comptable.controle.ControlDetector;
import com.example.comptable.controle.ControlResult;
import com.example.comptable.controle.ControlSeverity;
import com.example.comptable.entreprise.domain.Entreprise;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Banque/Ecritures &lt;-&gt; Tiers : {@code CLIENT_CREDIT_BALANCE} (client crediteur : avance, avoir non
 * impute ou trop-percu) et {@code SUPPLIER_DEBIT_BALANCE} (fournisseur debiteur : avance, paiement en
 * double ou avoir non impute). Un seul agregat SQL pour tout le dossier ; un constat par tiers, pas par ecriture.
 */
@Component
public class DetecteurSoldesTiers implements ControlDetector {

    private static final BigDecimal SEUIL_MINIMAL = BigDecimal.ONE;

    private final LigneEcritureRepository ligneEcritureRepository;

    public DetecteurSoldesTiers(LigneEcritureRepository ligneEcritureRepository) {
        this.ligneEcritureRepository = ligneEcritureRepository;
    }

    @Override
    public String nom() {
        return "soldes-tiers";
    }

    @Override
    public Set<String> codes() {
        return Set.of(ControlCodes.CLIENT_CREDIT_BALANCE, ControlCodes.SUPPLIER_DEBIT_BALANCE);
    }

    @Override
    public List<ControlResult> detecter(Entreprise entreprise) {
        return ligneEcritureRepository.rechercherSoldesTiersAnormaux(entreprise.getId()).stream()
                .filter(r -> r.debit().subtract(r.credit()).abs().compareTo(SEUIL_MINIMAL) >= 0)
                .map(r -> constat(entreprise, r))
                .toList();
    }

    private ControlResult constat(Entreprise entreprise, SoldeTiersRow r) {
        boolean client = r.type() == TypeTiers.CLIENT;
        BigDecimal solde = r.debit().subtract(r.credit()).abs();
        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put("tiers", r.nom());
        metadata.put("totalDebit", r.debit());
        metadata.put("totalCredit", r.credit());
        metadata.put("soldeAnormal", solde);
        return ControlResult.ouvert(client ? ControlCodes.CLIENT_CREDIT_BALANCE : ControlCodes.SUPPLIER_DEBIT_BALANCE,
                ControlCategory.THIRD_PARTY, ControlSeverity.WARNING, entreprise.getId(), "TIERS", r.tiersId().toString(),
                (client ? "Client crediteur : " : "Fournisseur debiteur : ") + r.nom(),
                (client ? "Le client " : "Le fournisseur ") + r.nom() + " presente un solde " + (client ? "crediteur" : "debiteur")
                        + " de " + solde.toPlainString() + " (debit " + r.debit().toPlainString() + ", credit "
                        + r.credit().toPlainString() + ") : "
                        + (client ? "avance, avoir non impute ou encaissement en trop." : "avance, paiement en double ou avoir non impute."),
                List.of(), metadata,
                client ? "Verifier les encaissements et avoirs, lettrer ou rembourser."
                        : "Verifier les paiements et avoirs fournisseur, lettrer ou demander un remboursement.");
    }
}
