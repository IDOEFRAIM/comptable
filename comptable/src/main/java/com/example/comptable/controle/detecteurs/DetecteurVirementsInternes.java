package com.example.comptable.controle.detecteurs;

import com.example.comptable.banque.BankInternalTransfer;
import com.example.comptable.banque.BankInternalTransferRepository;
import com.example.comptable.banque.InternalTransferMatcher.Appariement;
import com.example.comptable.banque.InternalTransferService;
import com.example.comptable.banque.StatutReconciliation;
import com.example.comptable.comptabilite.domain.LigneReleve;
import com.example.comptable.comptabilite.repository.LigneReleveRepository;
import com.example.comptable.controle.ControlCategory;
import com.example.comptable.controle.ControlCodes;
import com.example.comptable.controle.ControlDetector;
import com.example.comptable.controle.ControlResult;
import com.example.comptable.controle.ControlSeverity;
import com.example.comptable.entreprise.domain.Entreprise;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * Virements internes entre comptes bancaires de l'entreprise : uniquement ce qui demande une decision humaine.
 * <ul>
 *   <li>{@code INTERNAL_TRANSFER_TO_CONFIRM} : deux jambes probables, proposees ;</li>
 *   <li>{@code INTERNAL_TRANSFER_AMBIGUOUS} : plusieurs jambes possibles, jamais tranchees ;</li>
 *   <li>{@code INTERNAL_TRANSFER_WITHOUT_ENTRY} : virement lie mais aucune ecriture de transfert (le module n'en cree jamais).</li>
 * </ul>
 * Un virement certain dont l'ecriture existe deja ne produit AUCUN constat. Au-dela de {@link #SEUIL_AGREGATION}, un seul constat resume la categorie.
 */
@Component
public class DetecteurVirementsInternes implements ControlDetector {

    static final int SEUIL_AGREGATION = 8;
    static final int MAX_IDS_LIES = 50;

    private final BankInternalTransferRepository repository;
    private final InternalTransferService service;
    private final LigneReleveRepository ligneReleveRepository;

    public DetecteurVirementsInternes(BankInternalTransferRepository repository, InternalTransferService service, LigneReleveRepository ligneReleveRepository) {
        this.repository = repository;
        this.service = service;
        this.ligneReleveRepository = ligneReleveRepository;
    }

    @Override
    public String nom() {
        return "virements-internes";
    }

    @Override
    public Set<String> codes() {
        return Set.of(ControlCodes.INTERNAL_TRANSFER_TO_CONFIRM, ControlCodes.INTERNAL_TRANSFER_AMBIGUOUS, ControlCodes.INTERNAL_TRANSFER_WITHOUT_ENTRY);
    }

    @Override
    public List<ControlResult> detecter(Entreprise entreprise) {
        List<ControlResult> resultats = new ArrayList<>();
        List<BankInternalTransfer> aConfirmer = repository.findParStatut(entreprise.getId(), StatutReconciliation.PROPOSED);
        List<BankInternalTransfer> sansEcriture = repository.findParStatut(entreprise.getId(), StatutReconciliation.CONFIRMED).stream()
                .filter(t -> t.getEcritureId() == null).toList();
        List<Appariement> ambigus = service.ambigus(entreprise.getId());

        Map<UUID, LigneReleve> transactions = ligneReleveRepository.findAllById(java.util.stream.Stream.concat(
                java.util.stream.Stream.concat(aConfirmer.stream(), sansEcriture.stream()).flatMap(t -> java.util.stream.Stream.of(t.getDebitTransactionId(), t.getCreditTransactionId())),
                ambigus.stream().map(a -> a.sortie().getId())).distinct().toList()).stream().collect(Collectors.toMap(LigneReleve::getId, l -> l));

        resultats.addAll(transferts(entreprise, aConfirmer, transactions, ControlCodes.INTERNAL_TRANSFER_TO_CONFIRM, ControlSeverity.INFO,
                "Virement interne a confirmer", "ressemblent a un virement entre deux de vos comptes : meme montant, comptes differents, dates proches, mais rien ne le prouve",
                "Confirmer que c'est bien un virement interne, ou le rejeter (la sortie et l'entree seront alors rapprochees normalement)."));
        resultats.addAll(transferts(entreprise, sansEcriture, transactions, ControlCodes.INTERNAL_TRANSFER_WITHOUT_ENTRY, ControlSeverity.WARNING,
                "Virement interne non comptabilise", "sont bien un virement interne, mais aucune ecriture de transfert ne les couvre",
                "Enregistrer le virement dans les ecritures (aucune ecriture n'est creee automatiquement)."));

        if (ambigus.size() > SEUIL_AGREGATION) {
            Map<String, Object> metadata = new LinkedHashMap<>();
            metadata.put("nbSorties", ambigus.size());
            resultats.add(ControlResult.ouvert(ControlCodes.INTERNAL_TRANSFER_AMBIGUOUS, ControlCategory.RECONCILIATION, ControlSeverity.WARNING, entreprise.getId(),
                    "ENTREPRISE", entreprise.getId() + ":" + ControlCodes.INTERNAL_TRANSFER_AMBIGUOUS, ambigus.size() + " virements internes ambigus",
                    ambigus.size() + " sorties peuvent correspondre a plusieurs entrees d'un autre compte : le systeme ne choisit jamais. Un seul constat les resume.",
                    ambigus.stream().limit(MAX_IDS_LIES).map(a -> a.sortie().getId().toString()).toList(), metadata,
                    "Ouvrir l'ecran Banque et choisir pour chaque sortie l'entree qui correspond."));
        } else {
            for (Appariement a : ambigus) {
                LigneReleve s = transactions.get(a.sortie().getId());
                Map<String, Object> metadata = new LinkedHashMap<>();
                metadata.put("montant", s != null ? s.getMontant() : a.sortie().getMontant());
                metadata.put("nbEntreesPossibles", a.alternatives().size());
                resultats.add(ControlResult.ouvert(ControlCodes.INTERNAL_TRANSFER_AMBIGUOUS, ControlCategory.RECONCILIATION, ControlSeverity.WARNING, entreprise.getId(),
                        "LIGNE_RELEVE", a.sortie().getId().toString(), "Virement interne ambigu : " + a.sortie().getLibelle(),
                        "Cette sortie de " + a.sortie().getMontant().abs().toPlainString() + " peut correspondre a " + a.alternatives().size()
                                + " entrees d'un autre compte (ou une entree a plusieurs sorties) : le systeme ne choisit jamais.",
                        a.alternatives().stream().map(e -> e.getId().toString()).toList(), metadata,
                        "Choisir l'entree qui correspond (ou traiter les transactions normalement s'il ne s'agit pas d'un virement interne)."));
            }
        }
        return resultats;
    }

    private List<ControlResult> transferts(Entreprise entreprise, List<BankInternalTransfer> liste, Map<UUID, LigneReleve> transactions, String code,
                                           ControlSeverity gravite, String titre, String raison, String action) {
        if (liste.isEmpty()) {
            return List.of();
        }
        if (liste.size() > SEUIL_AGREGATION) {
            Map<String, Object> metadata = new LinkedHashMap<>();
            metadata.put("nbVirements", liste.size());
            return List.of(ControlResult.ouvert(code, ControlCategory.RECONCILIATION, gravite, entreprise.getId(), "ENTREPRISE", entreprise.getId() + ":" + code,
                    liste.size() + " virements internes : " + titre.toLowerCase().replace("virement interne ", ""),
                    liste.size() + " paires de transactions " + raison + ". Un seul constat les resume.",
                    liste.stream().limit(MAX_IDS_LIES).map(t -> t.getId().toString()).toList(), metadata, action));
        }
        List<ControlResult> resultats = new ArrayList<>();
        for (BankInternalTransfer t : liste) {
            LigneReleve sortie = transactions.get(t.getDebitTransactionId());
            LigneReleve entree = transactions.get(t.getCreditTransactionId());
            Map<String, Object> metadata = new LinkedHashMap<>();
            metadata.put("montant", t.getAmount());
            metadata.put("sortieId", t.getDebitTransactionId().toString());
            metadata.put("entreeId", t.getCreditTransactionId().toString());
            metadata.put("confiance", t.getConfidence());
            resultats.add(ControlResult.ouvert(code, ControlCategory.RECONCILIATION, gravite, entreprise.getId(), "BANK_INTERNAL_TRANSFER", t.getId().toString(),
                    titre + " : " + t.getAmount().toPlainString() + (sortie != null ? " (" + sortie.getDateOperation() + ")" : ""),
                    "Une sortie et une entree de " + t.getAmount().toPlainString() + " " + raison + ".",
                    List.of(t.getDebitTransactionId().toString(), t.getCreditTransactionId().toString()), metadata, action));
        }
        return resultats;
    }
}
