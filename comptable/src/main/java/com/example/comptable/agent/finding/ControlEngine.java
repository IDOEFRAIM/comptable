package com.example.comptable.agent.finding;

import com.example.comptable.agent.finding.domain.SeveriteFinding;
import com.example.comptable.agent.finding.domain.TypeFinding;
import com.example.comptable.comptabilite.domain.EcritureComptable;
import com.example.comptable.comptabilite.domain.StatutEcriture;
import com.example.comptable.comptabilite.repository.EcritureComptableRepository;
import com.example.comptable.document.domain.Document;
import com.example.comptable.document.domain.StatutDocument;
import com.example.comptable.document.repository.DocumentRepository;
import com.example.comptable.entreprise.domain.Entreprise;
import com.example.comptable.fiscalite.domain.DeclarationFiscale;
import com.example.comptable.fiscalite.domain.StatutDeclaration;
import com.example.comptable.fiscalite.domain.TypeImpot;
import com.example.comptable.fiscalite.repository.DeclarationFiscaleRepository;
import com.example.comptable.fiscalite.service.EcheancierTvaGenerator;
import com.example.comptable.fiscalite.service.EcheancierTvaGenerator.PeriodeEcheance;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Controles deterministes (mission P0, section 16 - volet "controles deterministes" uniquement,
 * les anomalies probabilistes restent du ressort de l'analyse de fraude deja existante,
 * {@code agent.fraude.AnalyseFraudeService}). Contrairement au Missing Evidence Engine (evenement
 * : declenche a chaque import de releve), ce moteur relit un etat qui evolue dans le temps sans
 * evenement declencheur naturel (declarations en retard, documents en attente, ecritures
 * oubliees) - il est donc recalcule a la lecture, appele par
 * {@code DossierComptableService} plutot que par un job planifie, pour rester coherent avec la
 * consigne "le score doit toujours refleter l'etat courant" sans ajouter de scheduler pour P0.
 */
@Component
public class ControlEngine {

    /** Ecriture BROUILLON depuis plus de ce nombre de jours : signal qu'elle a probablement ete
     * oubliee plutot que volontairement laissee en attente. */
    private static final int JOURS_BROUILLON_ANCIEN = 30;

    private final FindingService findingService;
    private final DeclarationFiscaleRepository declarationFiscaleRepository;
    private final DocumentRepository documentRepository;
    private final EcritureComptableRepository ecritureComptableRepository;

    public ControlEngine(FindingService findingService,
                          DeclarationFiscaleRepository declarationFiscaleRepository,
                          DocumentRepository documentRepository,
                          EcritureComptableRepository ecritureComptableRepository) {
        this.findingService = findingService;
        this.declarationFiscaleRepository = declarationFiscaleRepository;
        this.documentRepository = documentRepository;
        this.ecritureComptableRepository = ecritureComptableRepository;
    }

    @Transactional
    public void analyser(Entreprise entreprise) {
        analyserDeclarationsFiscales(entreprise);
        analyserDocumentsARisque(entreprise);
        analyserEcrituresBrouillonAnciennes(entreprise);
    }

    /** TVA uniquement : seul impot pour lequel un echeancier peut etre genere automatiquement
     * aujourd'hui ({@link EcheancierTvaGenerator}), donc le seul pour lequel une periode manquante
     * peut etre detectee sans inventer une regle d'echeance pour les autres impots. */
    private void analyserDeclarationsFiscales(Entreprise entreprise) {
        LocalDate aujourdHui = LocalDate.now();
        LocalDate dateOnboarding = entreprise.getCreatedAt().toLocalDate();

        for (PeriodeEcheance periode : EcheancierTvaGenerator.genererPeriodesAnnuelles(aujourdHui.getYear())) {
            if (periode.dateEcheance().isAfter(aujourdHui)) {
                continue;
            }
            // Une periode dont l'echeance precede l'entree du dossier dans le cabinet n'est pas une
            // negligence du comptable actuel : ne pas la signaler evite un faux-positif de bienvenue
            // sur chaque nouvelle entreprise creee (trouve via ce test de bout en bout).
            if (periode.dateEcheance().isBefore(dateOnboarding)) {
                continue;
            }
            boolean existe = declarationFiscaleRepository.existsByEntrepriseIdAndTypeImpotAndPeriode(
                    entreprise.getId(), TypeImpot.TVA, periode.periode());
            if (!existe) {
                findingService.signaler(entreprise, TypeFinding.TAX_REVIEW, SeveriteFinding.IMPORTANT,
                        "DECLARATION_FISCALE_PERIODE", "TVA-" + periode.periode(),
                        "Declaration TVA manquante : " + periode.periode(),
                        "Aucune declaration TVA n'a ete creee pour cette periode, alors que l'echeance ("
                                + periode.dateEcheance() + ") est deja passee.",
                        null,
                        Map.of("periode", periode.periode(), "dateEcheance", periode.dateEcheance().toString()),
                        "Generer l'echeancier TVA de l'annee puis completer la declaration manquante.");
            }
        }

        List<DeclarationFiscale> enRetard = declarationFiscaleRepository
                .findByEntrepriseIdAndStatutNotAndDateEcheanceBeforeOrderByDateEcheanceAsc(
                        entreprise.getId(), StatutDeclaration.PAYEE, aujourdHui);
        for (DeclarationFiscale declaration : enRetard) {
            findingService.signaler(entreprise, TypeFinding.TAX_REVIEW, SeveriteFinding.CRITICAL,
                    "DECLARATION_FISCALE", declaration.getId().toString(),
                    "Declaration en retard : " + declaration.getTypeImpot() + " " + declaration.getPeriode(),
                    "Echeance du " + declaration.getDateEcheance() + " depassee, statut actuel : "
                            + declaration.getStatut() + ".",
                    null,
                    Map.of("typeImpot", declaration.getTypeImpot().name(), "periode", declaration.getPeriode(),
                            "dateEcheance", declaration.getDateEcheance().toString(),
                            "statut", declaration.getStatut().name()),
                    "Deposer et/ou payer cette declaration sans delai.");
        }
    }

    private void analyserDocumentsARisque(Entreprise entreprise) {
        List<Document> aRevoir = documentRepository.findByEntrepriseIdAndStatutOrderByCreatedAtDesc(
                entreprise.getId(), StatutDocument.EN_ATTENTE_VALIDATION_HUMAINE);
        for (Document document : aRevoir) {
            findingService.signaler(entreprise, TypeFinding.DOCUMENT_REVIEW, SeveriteFinding.CRITICAL,
                    "DOCUMENT", document.getId().toString(),
                    "Document a risque : " + Optional.ofNullable(document.getNomFichierOriginal()).orElse(document.getId().toString()),
                    "Ce document presente un score de fraude critique (ou une confiance d'extraction "
                            + "insuffisante) et attend une validation humaine.",
                    null,
                    Map.of("typeDocument", document.getTypeDocument().name(), "canalSource", document.getCanalSource().name()),
                    "Ouvrir la piece, verifier les alertes de fraude, corriger ou valider manuellement.");
        }
    }

    private void analyserEcrituresBrouillonAnciennes(Entreprise entreprise) {
        LocalDate seuil = LocalDate.now().minusDays(JOURS_BROUILLON_ANCIEN);
        List<EcritureComptable> anciennes = ecritureComptableRepository.findByEntrepriseIdAndStatutAndDateEcritureBefore(
                entreprise.getId(), StatutEcriture.BROUILLON, seuil);
        for (EcritureComptable ecriture : anciennes) {
            findingService.signaler(entreprise, TypeFinding.ECRITURE_A_CONTROLER, SeveriteFinding.REVIEW,
                    "ECRITURE_COMPTABLE", ecriture.getId().toString(),
                    "Ecriture brouillon ancienne : " + ecriture.getNumeroPiece(),
                    "Cette ecriture est en brouillon depuis plus de " + JOURS_BROUILLON_ANCIEN
                            + " jours (date : " + ecriture.getDateEcriture() + ").",
                    null,
                    Map.of("numeroPiece", ecriture.getNumeroPiece(), "dateEcriture", ecriture.getDateEcriture().toString()),
                    "Verifier et valider (ou supprimer) cette ecriture.");
        }
    }
}
