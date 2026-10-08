package com.example.comptable.dossier.controles;

import com.example.comptable.comptabilite.depreciation.domain.Depreciation;
import com.example.comptable.comptabilite.depreciation.domain.StatutDepreciation;
import com.example.comptable.comptabilite.depreciation.repository.DepreciationRepository;
import com.example.comptable.comptabilite.domain.Immobilisation;
import com.example.comptable.comptabilite.domain.StatutImmobilisation;
import com.example.comptable.comptabilite.provision.domain.Provision;
import com.example.comptable.comptabilite.provision.domain.StatutProvision;
import com.example.comptable.comptabilite.provision.repository.ProvisionRepository;
import com.example.comptable.comptabilite.repository.DotationAmortissementRepository;
import com.example.comptable.comptabilite.repository.ImmobilisationRepository;
import com.example.comptable.comptabilite.service.PlanAmortissementCalculator;
import com.example.comptable.dossier.web.dto.ControlItem;
import com.example.comptable.fiscalite.domain.DeclarationFiscale;
import com.example.comptable.fiscalite.domain.StatutDeclaration;
import com.example.comptable.fiscalite.repository.DeclarationFiscaleRepository;
import com.example.comptable.stock.inventaire.domain.Inventaire;
import com.example.comptable.stock.inventaire.domain.StatutInventaire;
import com.example.comptable.stock.inventaire.repository.InventaireRepository;
import com.example.comptable.stock.repository.ArticleRepository;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;

/**
 * Stocks, amortissements, depreciations, provisions et obligations fiscales (lot 1.10). Chaque point
 * ne repond OK que s'il peut l'affirmer ; sans objet a controler il reste NOT_APPLICABLE et le dit.
 */
@Component
public class ControleActifsFiscalCloture implements ClotureControle {

    private final ArticleRepository articleRepository;
    private final InventaireRepository inventaireRepository;
    private final ImmobilisationRepository immobilisationRepository;
    private final DotationAmortissementRepository dotationAmortissementRepository;
    private final DepreciationRepository depreciationRepository;
    private final ProvisionRepository provisionRepository;
    private final DeclarationFiscaleRepository declarationFiscaleRepository;

    public ControleActifsFiscalCloture(ArticleRepository articleRepository, InventaireRepository inventaireRepository,
                                       ImmobilisationRepository immobilisationRepository,
                                       DotationAmortissementRepository dotationAmortissementRepository,
                                       DepreciationRepository depreciationRepository, ProvisionRepository provisionRepository,
                                       DeclarationFiscaleRepository declarationFiscaleRepository) {
        this.articleRepository = articleRepository;
        this.inventaireRepository = inventaireRepository;
        this.immobilisationRepository = immobilisationRepository;
        this.dotationAmortissementRepository = dotationAmortissementRepository;
        this.depreciationRepository = depreciationRepository;
        this.provisionRepository = provisionRepository;
        this.declarationFiscaleRepository = declarationFiscaleRepository;
    }

    @Override
    public List<ControlItem> evaluer(Contexte contexte) {
        return List.of(stocks(contexte), amortissements(contexte), depreciations(contexte), provisions(contexte), fiscalite(contexte));
    }

    /** Entreprise avec articles : un inventaire valide/cloture doit exister dans l'exercice. */
    private ControlItem stocks(Contexte c) {
        long nbArticles = articleRepository.countByEntrepriseId(c.entrepriseId());
        if (nbArticles == 0) {
            return ClotureControle.nonApplicable("stocks", "Valorisation des stocks",
                    "Aucun article de stock : rien a inventorier.");
        }
        LocalDate debut = c.exercice().getDateDebut();
        LocalDate fin = c.exercice().getDateFin();
        List<Inventaire> inventaires = inventaireRepository.findByEntrepriseIdOrderByDateDesc(c.entrepriseId()).stream()
                .filter(i -> !i.getDate().isBefore(debut) && !i.getDate().isAfter(fin))
                .toList();
        long finalises = inventaires.stream()
                .filter(i -> i.getStatut() == StatutInventaire.VALIDE || i.getStatut() == StatutInventaire.CLOTURE).count();
        if (finalises > 0) {
            return ClotureControle.ok("stocks", "Valorisation des stocks",
                    finalises + " inventaire(s) valide(s) sur l'exercice (" + nbArticles + " article(s) suivis).",
                    Map.of("nbArticles", nbArticles, "inventairesValides", finalises));
        }
        long enCours = inventaires.size();
        String message = enCours == 0
                ? "Aucun inventaire sur l'exercice alors que " + nbArticles + " article(s) sont suivis en stock."
                : enCours + " inventaire(s) sur l'exercice mais aucun valide (brouillon ou en cours).";
        return ClotureControle.avertissement("stocks", "Valorisation des stocks", message,
                message + " La valeur du stock a la cloture n'est pas justifiee par un inventaire valide.",
                enCours == 0 ? "EXERCICE" : "INVENTAIRE",
                enCours == 0 ? c.exercice().getId().toString() : inventaires.get(0).getId().toString(),
                inventaires.stream().map(i -> i.getId().toString()).toList(),
                Map.of("nbArticles", nbArticles, "nbInventaires", enCours));
    }

    /** Immobilisations mises en service dont la dotation de l'annee de cloture est attendue mais pas generee. */
    private ControlItem amortissements(Contexte c) {
        List<Immobilisation> enService = immobilisationRepository
                .findByEntrepriseIdAndStatut(c.entrepriseId(), StatutImmobilisation.EN_SERVICE).stream()
                .filter(i -> i.getDateMiseEnService() != null)
                .toList();
        if (enService.isEmpty()) {
            return ClotureControle.nonApplicable("amortissements", "Amortissements",
                    "Aucune immobilisation mise en service : aucune dotation a generer.");
        }
        int annee = c.exercice().getDateFin().getYear();
        List<Immobilisation> manquantes = enService.stream()
                .filter(i -> PlanAmortissementCalculator.calculer(i.getValeurOrigine(), i.getDateMiseEnService(), i.getDureeAnnees(), i.getMode())
                        .stream().anyMatch(l -> l.annee() == annee))
                .filter(i -> !dotationAmortissementRepository.existsByImmobilisationIdAndAnnee(i.getId(), annee))
                .toList();
        if (manquantes.isEmpty()) {
            return ClotureControle.ok("amortissements", "Amortissements",
                    "Les dotations " + annee + " sont generees pour toutes les immobilisations concernees.",
                    Map.of("annee", annee, "nbImmobilisations", enService.size()));
        }
        String message = manquantes.size() + " immobilisation(s) sans dotation " + annee + " alors que le plan en prevoit une.";
        return ClotureControle.avertissement("amortissements", "Amortissements", message,
                message + " Generer les dotations aux amortissements de l'exercice avant de cloturer : "
                        + String.join(", ", manquantes.stream().map(Immobilisation::getLibelle).toList()) + ".",
                "IMMOBILISATION", manquantes.get(0).getId().toString(),
                manquantes.stream().map(i -> i.getId().toString()).toList(),
                Map.of("annee", annee, "nbManquantes", manquantes.size(), "nbImmobilisations", enService.size()));
    }

    private ControlItem depreciations(Contexte c) {
        List<Depreciation> deprec = depreciationRepository.findByEntrepriseIdOrderByCreatedAtDesc(c.entrepriseId()).stream()
                .filter(d -> d.getExercice().getId().equals(c.exercice().getId())).toList();
        if (deprec.isEmpty()) {
            return ClotureControle.nonApplicable("depreciations", "Depreciations",
                    "Aucune depreciation enregistree pour cet exercice : rien a controler.");
        }
        List<Depreciation> enAttente = deprec.stream()
                .filter(d -> d.getStatut() == StatutDepreciation.BROUILLON || d.getStatut() == StatutDepreciation.PROPOSEE).toList();
        if (enAttente.isEmpty()) {
            return ClotureControle.ok("depreciations", "Depreciations",
                    deprec.size() + " depreciation(s) de l'exercice toutes traitees.", Map.of("nbDepreciations", deprec.size()));
        }
        String message = enAttente.size() + " depreciation(s) en attente de validation (brouillon ou proposee).";
        return ClotureControle.avertissement("depreciations", "Depreciations", message,
                message + " Valider ou rejeter chaque proposition avant la cloture.", "DEPRECIATION",
                enAttente.get(0).getId().toString(), enAttente.stream().map(d -> d.getId().toString()).toList(),
                Map.of("nbEnAttente", enAttente.size(), "nbDepreciations", deprec.size()));
    }

    private ControlItem provisions(Contexte c) {
        List<Provision> provisions = provisionRepository.findByEntrepriseIdOrderByCreatedAtDesc(c.entrepriseId()).stream()
                .filter(p -> p.getExercice().getId().equals(c.exercice().getId())).toList();
        if (provisions.isEmpty()) {
            return ClotureControle.nonApplicable("provisions", "Provisions",
                    "Aucune provision enregistree pour cet exercice : rien a controler.");
        }
        List<Provision> enAttente = provisions.stream()
                .filter(p -> p.getStatut() == StatutProvision.BROUILLON || p.getStatut() == StatutProvision.PROPOSEE).toList();
        if (enAttente.isEmpty()) {
            return ClotureControle.ok("provisions", "Provisions",
                    provisions.size() + " provision(s) de l'exercice toutes traitees.", Map.of("nbProvisions", provisions.size()));
        }
        String message = enAttente.size() + " provision(s) en attente de validation (brouillon ou proposee).";
        return ClotureControle.avertissement("provisions", "Provisions", message,
                message + " Valider ou rejeter chaque proposition avant la cloture.", "PROVISION",
                enAttente.get(0).getId().toString(), enAttente.stream().map(p -> p.getId().toString()).toList(),
                Map.of("nbEnAttente", enAttente.size(), "nbProvisions", provisions.size()));
    }

    /** Declarations dont l'echeance tombe dans l'exercice : en retard = echeance passee et encore A_FAIRE. */
    private ControlItem fiscalite(Contexte c) {
        List<DeclarationFiscale> declarations = declarationFiscaleRepository.findByEntrepriseIdAndDateEcheanceBetween(
                c.entrepriseId(), c.exercice().getDateDebut(), c.exercice().getDateFin());
        if (declarations.isEmpty()) {
            return ClotureControle.nonApplicable("fiscalite", "Fiscalite",
                    "Aucune declaration fiscale a echeance dans cet exercice n'est enregistree.");
        }
        LocalDate aujourdHui = LocalDate.now();
        List<DeclarationFiscale> enRetard = declarations.stream()
                .filter(d -> d.getStatut() == StatutDeclaration.A_FAIRE && d.getDateEcheance().isBefore(aujourdHui)).toList();
        if (enRetard.isEmpty()) {
            return ClotureControle.ok("fiscalite", "Fiscalite",
                    declarations.size() + " declaration(s) de l'exercice, aucune en retard.", Map.of("nbDeclarations", declarations.size()));
        }
        String message = enRetard.size() + " declaration(s) fiscale(s) en retard (echeance passee, non deposee).";
        return ClotureControle.avertissement("fiscalite", "Fiscalite", message,
                message + " Echeances : " + String.join(", ",
                        enRetard.stream().map(d -> d.getTypeImpot() + " " + d.getPeriode() + " (" + d.getDateEcheance() + ")").toList()) + ".",
                "DECLARATION", enRetard.get(0).getId().toString(),
                enRetard.stream().map(d -> d.getId().toString()).toList(),
                Map.of("nbEnRetard", enRetard.size(), "nbDeclarations", declarations.size()));
    }
}
