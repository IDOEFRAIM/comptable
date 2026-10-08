package com.example.comptable.controle.detecteurs;

import com.example.comptable.comptabilite.domain.Immobilisation;
import com.example.comptable.comptabilite.domain.StatutImmobilisation;
import com.example.comptable.comptabilite.operation.common.domain.AccountingOperationProposal;
import com.example.comptable.comptabilite.operation.common.repository.AccountingOperationProposalRepository;
import com.example.comptable.comptabilite.repository.ImmobilisationRepository;
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

/**
 * Immobilisation &lt;-&gt; Facture d'achat (Phase B, lots B0/B2) :
 * <ul>
 *   <li>{@code FIXED_ASSET_WITHOUT_SOURCE} : un bien au registre qu'aucune comptabilisation ni facture ne porte ;</li>
 *   <li>{@code POSSIBLE_DOUBLE_ACQUISITION} : acquisition comptabilisee directement alors qu'une facture d'achat
 *       deja comptabilisee debite le meme compte d'actif pour la meme valeur et n'est rattachee a aucun bien.</li>
 * </ul>
 */
@Component
public class DetecteurImmobilisations implements ControlDetector {

    private final ImmobilisationRepository immobilisationRepository;
    private final AccountingOperationProposalRepository proposalRepository;

    public DetecteurImmobilisations(ImmobilisationRepository immobilisationRepository,
                                    AccountingOperationProposalRepository proposalRepository) {
        this.immobilisationRepository = immobilisationRepository;
        this.proposalRepository = proposalRepository;
    }

    @Override
    public String nom() {
        return "immobilisations";
    }

    @Override
    public Set<String> codes() {
        return Set.of(ControlCodes.FIXED_ASSET_WITHOUT_SOURCE, ControlCodes.POSSIBLE_DOUBLE_ACQUISITION);
    }

    @Override
    public List<ControlResult> detecter(Entreprise entreprise) {
        List<ControlResult> resultats = new ArrayList<>();
        for (Immobilisation i : immobilisationRepository.rechercherSansSourceComptable(entreprise.getId())) {
            boolean enService = i.getStatut() == StatutImmobilisation.EN_SERVICE;
            Map<String, Object> metadata = new LinkedHashMap<>();
            metadata.put("valeurOrigine", i.getValeurOrigine());
            metadata.put("compte", i.getCompte().getNumero());
            metadata.put("statut", i.getStatut().name());
            resultats.add(ControlResult.ouvert(ControlCodes.FIXED_ASSET_WITHOUT_SOURCE, ControlCategory.FIXED_ASSET,
                    enService ? ControlSeverity.ERROR : ControlSeverity.WARNING, entreprise.getId(), "IMMOBILISATION",
                    i.getId().toString(), "Immobilisation sans source comptable : " + i.getLibelle(),
                    "Le bien (" + i.getValeurOrigine().toPlainString() + ", compte " + i.getCompte().getNumero()
                            + ") figure au registre mais aucune ecriture d'acquisition ni facture d'achat ne le porte"
                            + (enService ? " alors qu'il est deja en service et amortissable." : "."),
                    List.of(), metadata,
                    "Lier le bien a la facture d'achat deja comptabilisee, ou comptabiliser son acquisition."));
        }
        for (Immobilisation i : immobilisationRepository.rechercherAcquisitionsDirectes(entreprise.getId())) {
            List<AccountingOperationProposal> achats = proposalRepository
                    .rechercherAchatsDebitant(entreprise.getId(), i.getCompte().getId(), i.getValeurOrigine()).stream()
                    .filter(p -> immobilisationRepository.findByAcquisitionProposalId(p.getId()).isEmpty())
                    .toList();
            if (achats.isEmpty()) {
                continue;
            }
            Map<String, Object> metadata = new LinkedHashMap<>();
            metadata.put("valeurOrigine", i.getValeurOrigine());
            metadata.put("compte", i.getCompte().getNumero());
            metadata.put("achatsCandidats", achats.stream().map(p -> p.getSourceType() + "/" + p.getSourceId()).toList());
            resultats.add(ControlResult.ouvert(ControlCodes.POSSIBLE_DOUBLE_ACQUISITION, ControlCategory.FIXED_ASSET,
                    ControlSeverity.ERROR, entreprise.getId(), "IMMOBILISATION", i.getId().toString(),
                    "Double acquisition possible : " + i.getLibelle(),
                    "L'acquisition a ete comptabilisee directement (" + i.getValeurOrigine().toPlainString() + " sur "
                            + i.getCompte().getNumero() + ") alors que " + achats.size()
                            + " facture(s) d'achat comptabilisee(s) debitent ce compte pour la meme valeur sans etre rattachee(s) a un bien : "
                            + "le meme achat est peut-etre compte deux fois.",
                    achats.stream().map(p -> p.getSourceId().toString()).toList(), metadata,
                    "Verifier les ecritures ; contre-passer l'acquisition en double ou confirmer qu'il s'agit d'achats distincts."));
        }
        return resultats;
    }
}
