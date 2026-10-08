package com.example.comptable.agent.finding;

import com.example.comptable.comptabilite.domain.EcritureComptable;
import com.example.comptable.comptabilite.domain.StatutEcriture;
import com.example.comptable.comptabilite.repository.EcritureComptableRepository;
import com.example.comptable.controle.ControlCategory;
import com.example.comptable.controle.ControlCodes;
import com.example.comptable.controle.ControlDetector;
import com.example.comptable.controle.ControlResult;
import com.example.comptable.controle.ControlSeverity;
import com.example.comptable.document.domain.Document;
import com.example.comptable.document.domain.StatutDocument;
import com.example.comptable.document.repository.DocumentRepository;
import com.example.comptable.entreprise.domain.Entreprise;
import com.example.comptable.fiscalite.domain.DeclarationFiscale;
import com.example.comptable.fiscalite.domain.StatutDeclaration;
import com.example.comptable.fiscalite.domain.TypeImpot;
import com.example.comptable.fiscalite.repository.DeclarationFiscaleRepository;
import com.example.comptable.fiscalite.core.TaxDeadlineGenerator;
import com.example.comptable.fiscalite.core.TaxDeadlineGenerator.PeriodeEcheance;
import com.example.comptable.fiscalite.core.TaxFrameworkService;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * Controles deterministes sur un etat qui evolue dans le temps sans evenement declencheur naturel
 * (declarations en retard, documents en attente, ecritures oubliees). Phase B : ce moteur produit
 * des {@link ControlResult} ; {@code ControlRunService} les reconcilie en Findings (creation,
 * mise a jour, resolution automatique quand la cause disparait).
 */
@Component
public class ControlEngine implements ControlDetector {

    /** Ecriture BROUILLON depuis plus de ce nombre de jours : signal qu'elle a probablement ete
     * oubliee plutot que volontairement laissee en attente. */
    private static final int JOURS_BROUILLON_ANCIEN = 30;
    /** Au-dela, les brouillons anciens sont agreges en UN constat (jamais un Finding par operation). */
    static final int SEUIL_AGREGATION_BROUILLONS = 20;
    private static final int MAX_IDS_LIES = 50;

    private final DeclarationFiscaleRepository declarationFiscaleRepository;
    private final DocumentRepository documentRepository;
    private final EcritureComptableRepository ecritureComptableRepository;
    private final TaxFrameworkService cadres;

    public ControlEngine(DeclarationFiscaleRepository declarationFiscaleRepository,
                          DocumentRepository documentRepository,
                          EcritureComptableRepository ecritureComptableRepository,
                          TaxFrameworkService cadres) {
        this.cadres = cadres;
        this.declarationFiscaleRepository = declarationFiscaleRepository;
        this.documentRepository = documentRepository;
        this.ecritureComptableRepository = ecritureComptableRepository;
    }

    @Override
    public String nom() {
        return "controles-deterministes";
    }

    @Override
    public Set<String> codes() {
        return Set.of(ControlCodes.TAX_DECLARATION_MISSING, ControlCodes.TAX_DECLARATION_LATE,
                ControlCodes.DOCUMENT_AT_RISK, ControlCodes.DRAFT_ENTRY_OLD);
    }

    @Override
    public List<ControlResult> detecter(Entreprise entreprise) {
        List<ControlResult> resultats = new ArrayList<>();
        resultats.addAll(declarationsFiscales(entreprise));
        resultats.addAll(documentsARisque(entreprise));
        resultats.addAll(ecrituresBrouillonAnciennes(entreprise));
        return resultats;
    }

    /** TVA uniquement. Une periode manquante n'est detectee QUE si un cadre fiscal VERIFIED fournit la periodicite et la regle d'echeance
     * ({@link TaxDeadlineGenerator}) : sans regle verifiee, aucune echeance n'est supposee (les declarations deja creees restent controlees sur leur echeance propre). */
    private List<ControlResult> declarationsFiscales(Entreprise entreprise) {
        List<ControlResult> resultats = new ArrayList<>();
        LocalDate aujourdHui = LocalDate.now();
        LocalDate dateOnboarding = entreprise.getCreatedAt().toLocalDate();

        var regles = cadres.resoudre(entreprise.getTenant().getId(), entreprise.getPays(), TaxFrameworkService.TVA, aujourdHui)
                .filter(TaxFrameworkService.Resolution::verifie).map(r -> r.framework().rules()).orElse(null);
        for (PeriodeEcheance periode : TaxDeadlineGenerator.periodesAnnuelles(regles, aujourdHui.getYear())) {
            if (periode.dateEcheance().isAfter(aujourdHui)) {
                continue;
            }
            // Une periode dont l'echeance precede l'entree du dossier dans le cabinet n'est pas une
            // negligence du comptable actuel : ne pas la signaler evite un faux-positif de bienvenue.
            if (periode.dateEcheance().isBefore(dateOnboarding)) {
                continue;
            }
            boolean existe = declarationFiscaleRepository.existsByEntrepriseIdAndTypeImpotAndPeriode(
                    entreprise.getId(), TypeImpot.TVA, periode.periode());
            if (!existe) {
                resultats.add(ControlResult.ouvert(ControlCodes.TAX_DECLARATION_MISSING, ControlCategory.TAX,
                        ControlSeverity.WARNING, entreprise.getId(), "DECLARATION_FISCALE_PERIODE", "TVA-" + periode.periode(),
                        "Declaration TVA manquante : " + periode.periode(),
                        "Aucune declaration TVA n'a ete creee pour cette periode, alors que l'echeance ("
                                + periode.dateEcheance() + ") est deja passee.",
                        List.of(), Map.of("periode", periode.periode(), "dateEcheance", periode.dateEcheance().toString()),
                        "Generer l'echeancier TVA de l'annee puis completer la declaration manquante."));
            }
        }

        List<DeclarationFiscale> enRetard = declarationFiscaleRepository
                .findByEntrepriseIdAndStatutNotAndDateEcheanceBeforeOrderByDateEcheanceAsc(
                        entreprise.getId(), StatutDeclaration.PAYEE, aujourdHui);
        for (DeclarationFiscale declaration : enRetard) {
            resultats.add(ControlResult.ouvert(ControlCodes.TAX_DECLARATION_LATE, ControlCategory.TAX,
                    ControlSeverity.BLOCKING, entreprise.getId(), "DECLARATION_FISCALE", declaration.getId().toString(),
                    "Declaration en retard : " + declaration.getTypeImpot() + " " + declaration.getPeriode(),
                    "Echeance du " + declaration.getDateEcheance() + " depassee, statut actuel : "
                            + declaration.getStatut() + ".",
                    List.of(),
                    Map.of("typeImpot", declaration.getTypeImpot().name(), "periode", declaration.getPeriode(),
                            "dateEcheance", declaration.getDateEcheance().toString(),
                            "statut", declaration.getStatut().name()),
                    "Deposer et/ou payer cette declaration sans delai."));
        }
        return resultats;
    }

    private List<ControlResult> documentsARisque(Entreprise entreprise) {
        List<Document> aRevoir = documentRepository.findByEntrepriseIdAndStatutOrderByCreatedAtDesc(
                entreprise.getId(), StatutDocument.EN_ATTENTE_VALIDATION_HUMAINE);
        List<ControlResult> resultats = new ArrayList<>();
        for (Document document : aRevoir) {
            if (document.fraudeNeConcerneQueLesDoublons()) {
                continue; // couvert par DetecteurDocuments (EXACT_DUPLICATE / DUPLICATE_INVOICE / POSSIBLE_DUPLICATE)
            }
            resultats.add(ControlResult.ouvert(ControlCodes.DOCUMENT_AT_RISK, ControlCategory.DOCUMENT,
                    ControlSeverity.BLOCKING, entreprise.getId(), "DOCUMENT", document.getId().toString(),
                    "Document a risque : " + Optional.ofNullable(document.getNomFichierOriginal()).orElse(document.getId().toString()),
                    "Ce document presente un score de fraude critique (ou une confiance d'extraction "
                            + "insuffisante) et attend une validation humaine.",
                    List.of(),
                    Map.of("typeDocument", document.getTypeDocument().name(), "canalSource", document.getCanalSource().name()),
                    "Ouvrir la piece, verifier les alertes de fraude, corriger ou valider manuellement."));
        }
        return resultats;
    }

    private List<ControlResult> ecrituresBrouillonAnciennes(Entreprise entreprise) {
        LocalDate seuil = LocalDate.now().minusDays(JOURS_BROUILLON_ANCIEN);
        List<EcritureComptable> anciennes = ecritureComptableRepository.findByEntrepriseIdAndStatutAndDateEcritureBefore(
                entreprise.getId(), StatutEcriture.BROUILLON, seuil);
        if (anciennes.isEmpty()) {
            return List.of();
        }
        if (anciennes.size() > SEUIL_AGREGATION_BROUILLONS) {
            Map<String, Object> metadata = new LinkedHashMap<>();
            metadata.put("nbEcritures", anciennes.size());
            metadata.put("joursSeuil", JOURS_BROUILLON_ANCIEN);
            return List.of(ControlResult.ouvert(ControlCodes.DRAFT_ENTRY_OLD, ControlCategory.ACCOUNTING,
                    ControlSeverity.INFO, entreprise.getId(), "ECRITURES_BROUILLON", entreprise.getId().toString(),
                    anciennes.size() + " ecritures brouillon anciennes",
                    anciennes.size() + " ecritures sont en brouillon depuis plus de " + JOURS_BROUILLON_ANCIEN
                            + " jours : un seul constat est ouvert pour ne pas noyer la revue.",
                    anciennes.stream().limit(MAX_IDS_LIES).map(e -> e.getId().toString()).toList(), metadata,
                    "Valider (ou supprimer) ces ecritures depuis le journal."));
        }
        List<ControlResult> resultats = new ArrayList<>();
        for (EcritureComptable ecriture : anciennes) {
            resultats.add(ControlResult.ouvert(ControlCodes.DRAFT_ENTRY_OLD, ControlCategory.ACCOUNTING,
                    ControlSeverity.INFO, entreprise.getId(), "ECRITURE_COMPTABLE", ecriture.getId().toString(),
                    "Ecriture brouillon ancienne : " + ecriture.getNumeroPiece(),
                    "Cette ecriture est en brouillon depuis plus de " + JOURS_BROUILLON_ANCIEN
                            + " jours (date : " + ecriture.getDateEcriture() + ").",
                    List.of(), Map.of("numeroPiece", ecriture.getNumeroPiece(), "dateEcriture", ecriture.getDateEcriture().toString()),
                    "Verifier et valider (ou supprimer) cette ecriture."));
        }
        return resultats;
    }
}
