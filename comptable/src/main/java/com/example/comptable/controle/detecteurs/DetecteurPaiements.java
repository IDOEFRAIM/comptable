package com.example.comptable.controle.detecteurs;

import com.example.comptable.comptabilite.allocation.SituationTiersService;
import com.example.comptable.comptabilite.allocation.SituationTiersService.LigneSuivie;
import com.example.comptable.comptabilite.allocation.SituationTiersService.Role;
import com.example.comptable.controle.ControlCategory;
import com.example.comptable.controle.ControlCodes;
import com.example.comptable.controle.ControlDetector;
import com.example.comptable.controle.ControlResult;
import com.example.comptable.controle.ControlSeverity;
import com.example.comptable.entreprise.domain.Entreprise;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Reglements qui n'ont plus de facture a regler :
 * <ul>
 *   <li>{@code PAYMENT_WITHOUT_INVOICE} : reglement jamais affecte alors que le tiers n'a aucune facture ouverte ;</li>
 *   <li>{@code OVERPAYMENT} : reglement partiellement affecte dont le reliquat depasse ce que les factures ouvertes du tiers peuvent absorber.</li>
 * </ul>
 * Un reglement qui attend simplement d'etre affecte a une facture ouverte n'est PAS une anomalie : aucun constat, l'Inbox reste propre. Au-dela
 * de {@link #SEUIL_AGREGATION} reglements, un seul constat les resume.
 */
@Component
public class DetecteurPaiements implements ControlDetector {

    static final int SEUIL_AGREGATION = 8;
    static final int MAX_IDS_LIES = 50;

    private final SituationTiersService situationTiersService;

    public DetecteurPaiements(SituationTiersService situationTiersService) {
        this.situationTiersService = situationTiersService;
    }

    @Override
    public String nom() {
        return "paiements";
    }

    @Override
    public Set<String> codes() {
        return Set.of(ControlCodes.PAYMENT_WITHOUT_INVOICE, ControlCodes.OVERPAYMENT, ControlCodes.PAYMENT_ALLOCATION_NEEDS_REVIEW);
    }

    @Override
    public List<ControlResult> detecter(Entreprise entreprise) {
        List<LigneSuivie> sansFacture = new ArrayList<>();
        List<LigneSuivie> trop = new ArrayList<>();
        List<LigneSuivie> aRepartir = new ArrayList<>();
        for (List<LigneSuivie> groupe : situationTiersService.lignesOuvertesParTiers(entreprise.getId()).values()) {
            List<LigneSuivie> factures = groupe.stream().filter(l -> l.role() == Role.FACTURE).toList();
            for (LigneSuivie reglement : groupe) {
                if (reglement.role() != Role.REGLEMENT) {
                    continue;
                }
                boolean aUneFactureOuverte = factures.stream().anyMatch(f -> SituationTiersService.sensCompatibles(f.ligne(), reglement.ligne()));
                if (aUneFactureOuverte) {
                    // Des factures attendent et rien n'est certain : au comptable de repartir, jamais la premiere facture.
                    aRepartir.add(reglement);
                    continue;
                }
                (reglement.alloue().signum() == 0 ? sansFacture : trop).add(reglement);
            }
        }
        List<ControlResult> resultats = new ArrayList<>();
        resultats.addAll(construire(entreprise, sansFacture, ControlCodes.PAYMENT_WITHOUT_INVOICE, "Reglement sans facture",
                "n'est affecte a aucune facture et le tiers n'a aucune facture ouverte",
                "Verifier le tiers du reglement, ou saisir la facture correspondante (ou un acompte)."));
        resultats.addAll(construire(entreprise, trop, ControlCodes.OVERPAYMENT, "Reglement superieur aux factures ouvertes",
                "a un reliquat non affecte alors que le tiers n'a plus de facture ouverte",
                "Decider du reliquat : avoir, acompte ou remboursement. Il reste non affecte tant qu'on n'a pas tranche."));
        resultats.addAll(construire(entreprise, aRepartir, ControlCodes.PAYMENT_ALLOCATION_NEEDS_REVIEW, "Reglement a affecter",
                "n'a pas de facture certaine : plusieurs factures ouvertes ou des montants differents",
                "Choisir la ou les factures reglees, et le montant pour chacune (un reglement peut couvrir plusieurs factures)."));
        return resultats;
    }

    private List<ControlResult> construire(Entreprise entreprise, List<LigneSuivie> lignes, String code, String titre, String raison, String action) {
        if (lignes.isEmpty()) {
            return List.of();
        }
        if (lignes.size() > SEUIL_AGREGATION) {
            BigDecimal total = lignes.stream().map(LigneSuivie::reste).reduce(BigDecimal.ZERO, BigDecimal::add);
            Map<String, Object> metadata = new LinkedHashMap<>();
            metadata.put("nbReglements", lignes.size());
            metadata.put("montantNonAffecte", total);
            if (lignes.stream().map(DetecteurPaiements::typeDe).distinct().count() == 1) {
                metadata.put("typeTiers", typeDe(lignes.get(0)));
            }
            return List.of(ControlResult.ouvert(code, ControlCategory.RECONCILIATION, ControlSeverity.WARNING, entreprise.getId(), "ENTREPRISE",
                    entreprise.getId() + ":" + code, lignes.size() + " reglements : " + titre.toLowerCase(),
                    lignes.size() + " reglements " + raison + ". Un seul constat les resume pour ne pas noyer la revue.",
                    lignes.stream().limit(MAX_IDS_LIES).map(l -> l.id().toString()).toList(), metadata, action));
        }
        List<ControlResult> resultats = new ArrayList<>();
        for (LigneSuivie l : lignes) {
            Map<String, Object> metadata = new LinkedHashMap<>();
            metadata.put("montant", l.montant());
            metadata.put("alloue", l.alloue());
            metadata.put("nonAffecte", l.reste());
            metadata.put("piece", l.ligne().getEcriture().getNumeroPiece());
            metadata.put("typeTiers", typeDe(l));
            metadata.put("date", l.ligne().getEcriture().getDateEcriture().toString());
            resultats.add(ControlResult.ouvert(code, ControlCategory.RECONCILIATION, ControlSeverity.WARNING, entreprise.getId(), "LIGNE_ECRITURE",
                    l.id().toString(), titre + " : " + l.ligne().getEcriture().getNumeroPiece(),
                    "Le reglement " + l.ligne().getEcriture().getNumeroPiece() + " (" + l.montant().toPlainString() + ") " + raison
                            + " (non affecte : " + l.reste().toPlainString() + ").", List.of(), metadata, action));
        }
        return resultats;
    }

    /** CLIENT (compte 41...) ou FOURNISSEUR (compte 40...) : permet a la revue de ranger un reglement dans le bon domaine. */
    private static String typeDe(LigneSuivie l) {
        return l.ligne().getCompte().getNumero().startsWith("41") ? "CLIENT" : "FOURNISSEUR";
    }
}
