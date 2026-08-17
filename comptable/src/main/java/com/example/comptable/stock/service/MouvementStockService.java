package com.example.comptable.stock.service;

import com.example.comptable.comptabilite.domain.EcritureComptable;
import com.example.comptable.comptabilite.domain.ExerciceComptable;
import com.example.comptable.comptabilite.domain.Journal;
import com.example.comptable.comptabilite.domain.LigneEcriture;
import com.example.comptable.comptabilite.domain.StatutEcriture;
import com.example.comptable.comptabilite.domain.StatutExercice;
import com.example.comptable.comptabilite.repository.EcritureComptableRepository;
import com.example.comptable.comptabilite.repository.ExerciceComptableRepository;
import com.example.comptable.comptabilite.repository.JournalRepository;
import com.example.comptable.entreprise.service.EntrepriseAccessService;
import com.example.comptable.identite.domain.Utilisateur;
import com.example.comptable.identite.repository.UtilisateurRepository;
import com.example.comptable.security.UtilisateurPrincipal;
import com.example.comptable.stock.domain.Article;
import com.example.comptable.stock.domain.MouvementStock;
import com.example.comptable.stock.domain.TypeMouvementStock;
import com.example.comptable.stock.repository.ArticleRepository;
import com.example.comptable.stock.repository.MouvementStockRepository;
import com.example.comptable.stock.web.dto.CreerMouvementStockRequest;
import com.example.comptable.stock.web.dto.MouvementStockDto;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/**
 * Enregistre les mouvements d'inventaire permanent d'un {@link Article} et maintient son PUMP
 * (Prix Unitaire Moyen Pondere) : {@link PumpCalculator} porte la formule pure, ce service
 * l'applique, journalise le mouvement et genere l'ecriture comptable de contrepartie (compte de
 * stock / compte de variation de stock), sur le journal OD puisque ces mouvements ne sont pas
 * eux-memes une piece d'achat ou de vente (celle-ci reste a comptabiliser separement via
 * FacturationService/ComptabilisationService).
 */
@Service
@RequiredArgsConstructor
public class MouvementStockService {

    private static final String JOURNAL_MOUVEMENTS_STOCK = "OD";

    private final EntrepriseAccessService entrepriseAccessService;
    private final ArticleRepository articleRepository;
    private final MouvementStockRepository mouvementStockRepository;
    private final ExerciceComptableRepository exerciceComptableRepository;
    private final JournalRepository journalRepository;
    private final EcritureComptableRepository ecritureComptableRepository;
    private final UtilisateurRepository utilisateurRepository;

    @Transactional
    public MouvementStockDto enregistrer(UUID entrepriseId, CreerMouvementStockRequest request, UtilisateurPrincipal principal) {
        entrepriseAccessService.verifierAcces(entrepriseId, principal);
        Article article = chargerArticle(entrepriseId, request.articleId());
        LocalDate dateMouvement = request.dateMouvement() != null ? request.dateMouvement() : LocalDate.now();

        MouvementStock mouvement = switch (request.type()) {
            case ENTREE -> appliquerEntree(article, request);
            case SORTIE -> appliquerSortie(article, request);
            case AJUSTEMENT -> appliquerAjustement(article, request);
        };
        mouvement.setEntreprise(article.getEntreprise());
        mouvement.setArticle(article);
        mouvement.setDateMouvement(dateMouvement);
        mouvement.setLibelle(request.libelle());
        mouvement.setCreatedBy(utilisateurRepository.getReferenceById(principal.getId()));

        EcritureComptable ecriture = genererEcriture(article, mouvement, dateMouvement, principal);
        mouvement.setEcritureId(ecriture.getId());

        articleRepository.save(article);
        mouvement = mouvementStockRepository.save(mouvement);

        return MouvementStockDto.from(mouvement);
    }

    @Transactional(readOnly = true)
    public List<MouvementStockDto> lister(UUID entrepriseId, UUID articleId, UtilisateurPrincipal principal) {
        entrepriseAccessService.verifierAcces(entrepriseId, principal);
        chargerArticle(entrepriseId, articleId);
        return mouvementStockRepository.findByArticleIdFetchAll(articleId).stream()
                .map(MouvementStockDto::from)
                .toList();
    }

    /** Charge l'entite (pas le DTO) pour un service appelant, ex. FacturationService lors de la
     * reintegration en stock d'une facture annulee (memes quantite/prix que le mouvement d'origine). */
    @Transactional(readOnly = true)
    public MouvementStock chargerEntite(UUID entrepriseId, UUID mouvementStockId) {
        return mouvementStockRepository.findById(mouvementStockId)
                .filter(m -> m.getEntreprise().getId().equals(entrepriseId))
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Mouvement de stock introuvable"));
    }

    private MouvementStock appliquerEntree(Article article, CreerMouvementStockRequest request) {
        BigDecimal quantite = request.quantite();
        if (quantite.signum() <= 0) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "La quantite d'une entree doit etre strictement positive");
        }
        if (request.prixUnitaireHt() == null || request.prixUnitaireHt().signum() < 0) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Le prix unitaire d'achat est requis pour une entree");
        }

        PumpCalculator.ResultatEntree resultat = PumpCalculator.appliquerEntree(
                article.getQuantiteEnStock(), article.getValeurStockTotale(), quantite, request.prixUnitaireHt());

        article.setQuantiteEnStock(resultat.nouvelleQuantite());
        article.setValeurStockTotale(resultat.nouvelleValeur());
        article.setPump(resultat.nouveauPump());

        return construireMouvement(TypeMouvementStock.ENTREE, quantite, request.prixUnitaireHt(),
                resultat.valeurEntree(), resultat.nouvelleQuantite(), resultat.nouveauPump());
    }

    private MouvementStock appliquerSortie(Article article, CreerMouvementStockRequest request) {
        BigDecimal quantite = request.quantite();
        if (quantite.signum() <= 0) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "La quantite d'une sortie doit etre strictement positive");
        }
        if (quantite.compareTo(article.getQuantiteEnStock()) > 0) {
            throw new StockInsuffisantException(article.getCodeArticle(), article.getQuantiteEnStock(), quantite);
        }

        BigDecimal pumpEnVigueur = article.getPump();
        PumpCalculator.ResultatSortie resultat = PumpCalculator.appliquerSortie(
                article.getQuantiteEnStock(), article.getValeurStockTotale(), pumpEnVigueur, quantite);

        article.setQuantiteEnStock(resultat.nouvelleQuantite());
        article.setValeurStockTotale(resultat.nouvelleValeur());
        // Le PUMP ne change jamais lors d'une sortie.

        return construireMouvement(TypeMouvementStock.SORTIE, quantite, pumpEnVigueur,
                resultat.valeurSortie(), resultat.nouvelleQuantite(), pumpEnVigueur);
    }

    private MouvementStock appliquerAjustement(Article article, CreerMouvementStockRequest request) {
        BigDecimal ecart = request.quantite();
        if (ecart.signum() == 0) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "L'ecart d'un ajustement ne peut pas etre nul");
        }

        BigDecimal pumpEnVigueur = article.getPump();
        if (ecart.signum() > 0) {
            // Surplus constate : valorise au PUMP courant, comme une entree qui ne modifierait pas le PUMP.
            BigDecimal valeurAjustement = ecart.multiply(pumpEnVigueur).setScale(2, java.math.RoundingMode.HALF_UP);
            article.setQuantiteEnStock(article.getQuantiteEnStock().add(ecart));
            article.setValeurStockTotale(article.getValeurStockTotale().add(valeurAjustement));
            return construireMouvement(TypeMouvementStock.AJUSTEMENT, ecart, pumpEnVigueur,
                    valeurAjustement, article.getQuantiteEnStock(), pumpEnVigueur);
        }

        BigDecimal quantitePerdue = ecart.abs();
        if (quantitePerdue.compareTo(article.getQuantiteEnStock()) > 0) {
            throw new StockInsuffisantException(article.getCodeArticle(), article.getQuantiteEnStock(), quantitePerdue);
        }
        BigDecimal valeurAjustement = quantitePerdue.multiply(pumpEnVigueur).setScale(2, java.math.RoundingMode.HALF_UP);
        article.setQuantiteEnStock(article.getQuantiteEnStock().subtract(quantitePerdue));
        article.setValeurStockTotale(article.getValeurStockTotale().subtract(valeurAjustement));
        return construireMouvement(TypeMouvementStock.AJUSTEMENT, ecart, pumpEnVigueur,
                valeurAjustement, article.getQuantiteEnStock(), pumpEnVigueur);
    }

    private MouvementStock construireMouvement(TypeMouvementStock type, BigDecimal quantite, BigDecimal prixUnitaire,
                                                 BigDecimal valeurMouvement, BigDecimal quantiteApres, BigDecimal pumpApres) {
        MouvementStock mouvement = new MouvementStock();
        mouvement.setType(type);
        mouvement.setQuantite(quantite);
        mouvement.setPrixUnitaireHt(prixUnitaire);
        mouvement.setValeurMouvement(valeurMouvement);
        mouvement.setQuantiteApres(quantiteApres);
        mouvement.setPumpApres(pumpApres);
        return mouvement;
    }

    /**
     * Entree/ajustement-surplus : Debit compte de stock / Credit compte de variation.
     * Sortie/ajustement-perte : Debit compte de variation / Credit compte de stock.
     * Determine par le signe de {@code quantite} sur le mouvement deja construit (positif pour
     * ENTREE et ajustement-surplus, negatif pour ajustement-perte ; SORTIE est toujours positif
     * en base mais debite systematiquement le compte de variation).
     */
    private EcritureComptable genererEcriture(Article article, MouvementStock mouvement, LocalDate dateMouvement,
                                               UtilisateurPrincipal principal) {
        ExerciceComptable exercice = exerciceComptableRepository.findByEntrepriseIdAndDate(article.getEntreprise().getId(), dateMouvement)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY,
                        "Aucun exercice comptable ne couvre la date " + dateMouvement));
        if (exercice.getStatut() != StatutExercice.OUVERT) {
            throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY, "L'exercice couvrant cette date est cloture");
        }

        Journal journal = journalRepository.findByEntrepriseIdAndCode(article.getEntreprise().getId(), JOURNAL_MOUVEMENTS_STOCK)
                .orElseThrow(() -> new IllegalStateException("Journal OD introuvable, l'entreprise n'a pas ete initialisee correctement"));
        Utilisateur utilisateur = utilisateurRepository.getReferenceById(principal.getId());

        boolean entreeEnStock = mouvement.getType() == TypeMouvementStock.ENTREE
                || (mouvement.getType() == TypeMouvementStock.AJUSTEMENT && mouvement.getQuantite().signum() > 0);

        String libelle = (mouvement.getLibelle() != null ? mouvement.getLibelle() : mouvement.getType() + " stock")
                + " - " + article.getDesignation();

        EcritureComptable ecriture = new EcritureComptable();
        ecriture.setEntreprise(article.getEntreprise());
        ecriture.setExercice(exercice);
        ecriture.setJournal(journal);
        ecriture.setDateEcriture(dateMouvement);
        ecriture.setLibelle(libelle);
        ecriture.setCreatedBy(utilisateur);
        ecriture.setStatut(StatutEcriture.VALIDEE);
        ecriture.setNumeroPiece(genererNumeroPiece(journal));

        if (entreeEnStock) {
            ecriture.ajouterLigne(LigneEcriture.debit(article.getCompteStock(), mouvement.getValeurMouvement(), libelle));
            ecriture.ajouterLigne(LigneEcriture.credit(article.getCompteVariation(), mouvement.getValeurMouvement(), libelle));
        } else {
            ecriture.ajouterLigne(LigneEcriture.debit(article.getCompteVariation(), mouvement.getValeurMouvement(), libelle));
            ecriture.ajouterLigne(LigneEcriture.credit(article.getCompteStock(), mouvement.getValeurMouvement(), libelle));
        }

        if (!ecriture.estEquilibree()) {
            throw new IllegalStateException("Ecriture de mouvement de stock desequilibree, enregistrement annule");
        }
        return ecritureComptableRepository.save(ecriture);
    }

    private String genererNumeroPiece(Journal journal) {
        long sequence = ecritureComptableRepository.countByJournalId(journal.getId()) + 1;
        return "%s-%d-%04d".formatted(journal.getCode(), LocalDate.now().getYear(), sequence);
    }

    private Article chargerArticle(UUID entrepriseId, UUID articleId) {
        return articleRepository.findById(articleId)
                .filter(a -> a.getEntreprise().getId().equals(entrepriseId))
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Article introuvable"));
    }
}
