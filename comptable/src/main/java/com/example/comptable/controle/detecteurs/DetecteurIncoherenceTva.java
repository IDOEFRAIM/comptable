package com.example.comptable.controle.detecteurs;

import com.example.comptable.comptabilite.service.CoherenceMontants;
import com.example.comptable.controle.ControlCategory;
import com.example.comptable.controle.ControlCodes;
import com.example.comptable.controle.ControlDetector;
import com.example.comptable.controle.ControlResult;
import com.example.comptable.controle.ControlSeverity;
import com.example.comptable.document.domain.Document;
import com.example.comptable.document.repository.DocumentRepository;
import com.example.comptable.entreprise.domain.Entreprise;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * {@code VAT_INCONSISTENCY} : piece non comptabilisee dont HT + TVA differe du TTC. Meme arithmetique
 * ({@link CoherenceMontants}) et meme tolerance d'arrondi que le pipeline agentique (0,02) ; une
 * incoherence bloque la comptabilisation, le constat explique donc quelle valeur corriger.
 */
@Component
public class DetecteurIncoherenceTva implements ControlDetector {

    static final BigDecimal TOLERANCE = new BigDecimal("0.02");

    private final DocumentRepository documentRepository;

    public DetecteurIncoherenceTva(DocumentRepository documentRepository) {
        this.documentRepository = documentRepository;
    }

    @Override
    public String nom() {
        return "incoherence-tva";
    }

    @Override
    public Set<String> codes() {
        return Set.of(ControlCodes.VAT_INCONSISTENCY);
    }

    @Override
    public List<ControlResult> detecter(Entreprise entreprise) {
        return documentRepository.rechercherMontantsIncoherents(entreprise.getId(), TOLERANCE).stream()
                .map(d -> constat(entreprise, d))
                .flatMap(Optional::stream)
                .toList();
    }

    private Optional<ControlResult> constat(Entreprise entreprise, Document document) {
        return CoherenceMontants.verifier(document.getMontantHt(), document.getMontantTva(), document.getMontantTtc(), TOLERANCE)
                .map(ecart -> {
                    Map<String, Object> metadata = new LinkedHashMap<>();
                    metadata.put("montantHt", ecart.ht());
                    metadata.put("montantTva", ecart.tva());
                    metadata.put("montantTtc", ecart.ttc());
                    metadata.put("ecart", ecart.ecart());
                    String nom = document.getNomFichierOriginal() != null ? document.getNomFichierOriginal() : document.getId().toString();
                    return ControlResult.ouvert(ControlCodes.VAT_INCONSISTENCY, ControlCategory.TAX, ControlSeverity.ERROR,
                            entreprise.getId(), "DOCUMENT", document.getId().toString(),
                            "Montants incoherents (TVA) : " + nom, ecart.explication() + ".", List.of(), metadata,
                            "Corriger le HT, la TVA ou le TTC de la piece avant de la comptabiliser.");
                });
    }
}
