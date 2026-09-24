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
import com.example.comptable.stock.domain.Depot;
import com.example.comptable.stock.domain.ModeGestionStock;
import com.example.comptable.stock.domain.MouvementStock;
import com.example.comptable.stock.domain.PolitiqueStockNegatif;
import com.example.comptable.stock.domain.TypeMouvementStock;
import com.example.comptable.stock.repository.ArticleRepository;
import com.example.comptable.stock.repository.DepotRepository;
import com.example.comptable.stock.repository.MouvementStockRepository;
import com.example.comptable.stock.web.dto.CreerMouvementStockRequest;
import com.example.comptable.stock.web.dto.MouvementStockDto;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * Enregistre les mouvements d'inventaire permanent d'un {@link Article} et maintient son etat de
 * valorisation (delegue a {@link StockValuationService}) : ce service applique le calcul,
 * journalise le mouvement et genere l'ecriture comptable de contrepartie (compte de stock / compte
 * de variation de stock), sur le journal OD puisque ces mouvements ne sont pas eux-memes une piece
 * d'achat ou de vente (celle-ci reste a comptabiliser separement via
 * FacturationService/ComptabilisationService). La regularisation d'un ecart d'inventaire physique
 * est une exception notable : voir {@link #enregistrerRegularisationInventaire} - elle ne genere
 * jamais d'ecriture elle-meme, laissant AccountingOperationEngine porter la proposition et la
 * validation humaine.
 */
@Service
@RequiredArgsConstructor
public class MouvementStockService {

    private static final String JOURNAL_MOUVEMENTS_STOCK = "OD";

    /** Types que le formulaire generique (POST /mouvements-stock) peut creer directement. Les
     * autres portent leur propre tracabilite et ne sont crees que par l'orchestrateur interne
     * concerne (CommandeAchatService, FacturationService, InventaireService...). */
    private static final Set<TypeMouvementStock> TYPES_SAISIE_MANUELLE =
            EnumSet.of(TypeMouvementStock.ENTREE, TypeMouvementStock.SORTIE, TypeMouvementStock.AJUSTEMENT,
                    TypeMouvementStock.TRANSFERT);

    private final EntrepriseAccessService entrepriseAccessService;
    private final ArticleRepository articleRepository;
    private final DepotRepository depotRepository;
    private final MouvementStockRepository mouvementStockRepository;
    private final ExerciceComptableRepository exerciceComptableRepository;
    private final JournalRepository journalRepository;
    private final EcritureComptableRepository ecritureComptableRepository;
    private final UtilisateurRepository utilisateurRepository;
    private final StockValuationService stockValuationService;

    private record ResultatApplication(MouvementStock mouvement, String avertissement) {
    }

    @Transactional
    public MouvementStockDto enregistrer(UUID entrepriseId, CreerMouvementStockRequest request, UtilisateurPrincipal principal) {
        if (!TYPES_SAISIE_MANUELLE.contains(request.type())) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "Le type " + request.type() + " ne peut pas etre saisi manuellement, il est genere automatiquement");
        }
        if (request.type() == TypeMouvementStock.TRANSFERT) {
            return enregistrerTransfert(entrepriseId, request, principal);
        }
        return enregistrerAvecSource(entrepriseId, request, principal, null, null);
    }

    /** Reserve aux orchestrateurs internes (achat, vente, avoir) : memes garde-fous que
     * {@link #enregistrer}, mais type libre et tracabilite (sourceType/sourceId) explicite. Genere
     * toujours son ecriture propre (comportement historique, inchange pour achats/ventes).
     * <p>
     * Retourne {@code null} lorsqu'un mouvement automatique (sourceType renseigne) porte sur un
     * article en mode {@link ModeGestionStock#INTERMITTENT} : ces articles ne suivent pas le stock
     * en continu, seul un inventaire periodique ajuste leur valeur. Point de decision unique -
     * les orchestrateurs n'ont plus a repeter ce test (ni a recharger l'article pour le faire). */
    @Transactional
    public MouvementStockDto enregistrerAvecSource(UUID entrepriseId, CreerMouvementStockRequest request,
            UtilisateurPrincipal principal, String sourceType, UUID sourceId) {
        entrepriseAccessService.verifierAcces(entrepriseId, principal);
        Article article = chargerArticle(entrepriseId, request.articleId());
        if (sourceType != null && article.getModeGestionStock() == ModeGestionStock.INTERMITTENT) {
            return null;
        }
        LocalDate dateMouvement = request.dateMouvement() != null ? request.dateMouvement() : LocalDate.now();

        ResultatApplication resultat = switch (categorieDe(request.type())) {
            case ENTREE -> appliquerEntree(article, request);
            case SORTIE -> appliquerSortie(article, request);
            case AJUSTEMENT -> appliquerAjustement(article, request);
        };
        MouvementStock mouvement = resultat.mouvement();
        mouvement.setEntreprise(article.getEntreprise());
        mouvement.setArticle(article);
        mouvement.setDateMouvement(dateMouvement);
        mouvement.setLibelle(request.libelle());
        mouvement.setSourceType(sourceType);
        mouvement.setSourceId(sourceId);
        mouvement.setCreatedBy(utilisateurRepository.getReferenceById(principal.getId()));

        EcritureComptable ecriture = genererEcriture(article, mouvement, dateMouvement, principal);
        mouvement.setEcritureId(ecriture.getId());

        articleRepository.save(article);
        mouvement = mouvementStockRepository.save(mouvement);

        return MouvementStockDto.from(mouvement, resultat.avertissement());
    }

    /** Regularisation d'un ecart d'inventaire physique (positif = surplus, negatif = perte) :
     * met a jour la quantite/valeur de l'article immediatement (le comptage physique est un fait),
     * mais ne genere jamais d'ecriture elle-meme - AccountingOperationEngine porte la proposition
     * et seule une validation humaine explicite cree l'EcritureComptable reelle (voir
     * InventaireService/InventaireRegularisationRule). {@code coutUnitaire} est fourni par
     * l'appelant (PUMP courant en mode permanent, dernier inventaire ou saisie manuelle en mode
     * intermittent) plutot que devine ici. */
    @Transactional
    public MouvementStock enregistrerRegularisationInventaire(UUID entrepriseId, UUID articleId, BigDecimal ecart,
            BigDecimal coutUnitaire, LocalDate date, String libelle, UUID inventaireId, UtilisateurPrincipal principal) {
        entrepriseAccessService.verifierAcces(entrepriseId, principal);
        Article article = chargerArticle(entrepriseId, articleId);
        if (ecart.signum() == 0) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "L'ecart d'inventaire ne peut pas etre nul");
        }

        BigDecimal valeurEcart = ecart.abs().multiply(coutUnitaire).setScale(2, RoundingMode.HALF_UP);
        MouvementStock mouvement;
        if (ecart.signum() > 0) {
            article.setQuantiteEnStock(article.getQuantiteEnStock().add(ecart));
            article.setValeurStockTotale(article.getValeurStockTotale().add(valeurEcart));
            mouvement = construireMouvement(TypeMouvementStock.ENTREE_INVENTAIRE, ecart, coutUnitaire, valeurEcart,
                    article.getQuantiteEnStock(), article.getPump());
        } else {
            BigDecimal quantitePerdue = ecart.abs();
            article.setQuantiteEnStock(article.getQuantiteEnStock().subtract(quantitePerdue));
            article.setValeurStockTotale(article.getValeurStockTotale().subtract(valeurEcart));
            mouvement = construireMouvement(TypeMouvementStock.SORTIE_INVENTAIRE, quantitePerdue, coutUnitaire, valeurEcart,
                    article.getQuantiteEnStock(), article.getPump());
        }
        mouvement.setEntreprise(article.getEntreprise());
        mouvement.setArticle(article);
        mouvement.setDateMouvement(date);
        mouvement.setLibelle(libelle);
        mouvement.setSourceType("INVENTAIRE");
        mouvement.setSourceId(inventaireId);
        mouvement.setCreatedBy(utilisateurRepository.getReferenceById(principal.getId()));
        // Pas d'ecriture ici : ecritureId reste null jusqu'a la validation humaine de la
        // proposition (voir InventaireService.cloturer, qui patch ecritureId retroactivement).

        articleRepository.save(article);
        return mouvementStockRepository.save(mouvement);
    }

    /** Lie a posteriori tous les mouvements d'une source donnee (ex. un inventaire) a l'ecriture
     * reelle, une fois la proposition validee par un humain (jamais avant) - voir
     * InventaireService.cloturer. */
    @Transactional
    public void lierEcriture(String sourceType, UUID sourceId, UUID ecritureId) {
        for (MouvementStock mouvement : mouvementStockRepository.findBySourceTypeAndSourceId(sourceType, sourceId)) {
            mouvement.setEcritureId(ecritureId);
            mouvementStockRepository.save(mouvement);
        }
    }

    private MouvementStockDto enregistrerTransfert(UUID entrepriseId, CreerMouvementStockRequest request, UtilisateurPrincipal principal) {
        // Meme controle d'acces que enregistrerAvecSource : sans lui, le transfert etait le seul
        // type de mouvement ecrivant dans un tenant sans verifier que l'appelant y a acces.
        entrepriseAccessService.verifierAcces(entrepriseId, principal);
        if (request.depotSourceId() == null || request.depotDestinationId() == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Un transfert exige un depot source et un depot destination");
        }
        if (request.depotSourceId().equals(request.depotDestinationId())) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Le depot source et le depot destination doivent etre differents");
        }
        if (request.quantite().signum() <= 0) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "La quantite d'un transfert doit etre strictement positive");
        }
        Article article = chargerArticle(entrepriseId, request.articleId());
        Depot depotSource = chargerDepot(entrepriseId, request.depotSourceId());
        Depot depotDestination = chargerDepot(entrepriseId, request.depotDestinationId());

        // Deplacement interne a la meme entreprise : aucun impact sur la quantite/valeur globale
        // de l'article, aucune ecriture generee (pas de compte de liaison inter-etablissement a
        // ce stade - voir Depot).
        // prixUnitaireHt = ZERO (jamais null) : la colonne est NOT NULL, et un transfert interne
        // n'a pas de prix d'achat propre - la valeur du mouvement est nulle par construction.
        MouvementStock mouvement = construireMouvement(TypeMouvementStock.TRANSFERT, request.quantite(), BigDecimal.ZERO,
                BigDecimal.ZERO, article.getQuantiteEnStock(), article.getPump());
        mouvement.setEntreprise(article.getEntreprise());
        mouvement.setArticle(article);
        mouvement.setDateMouvement(request.dateMouvement() != null ? request.dateMouvement() : LocalDate.now());
        mouvement.setLibelle(request.libelle());
        mouvement.setDepotSource(depotSource);
        mouvement.setDepotDestination(depotDestination);
        mouvement.setSourceType("TRANSFERT_MANUEL");
        mouvement.setCreatedBy(utilisateurRepository.getReferenceById(principal.getId()));

        return MouvementStockDto.from(mouvementStockRepository.save(mouvement));
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

    private enum Categorie { ENTREE, SORTIE, AJUSTEMENT }

    private Categorie categorieDe(TypeMouvementStock type) {
        return switch (type) {
            case ENTREE, ENTREE_ACHAT, ENTREE_PRODUCTION -> Categorie.ENTREE;
            case SORTIE, SORTIE_VENTE, SORTIE_CONSOMMATION, SORTIE_PRODUCTION -> Categorie.SORTIE;
            case AJUSTEMENT -> Categorie.AJUSTEMENT;
            case ENTREE_INVENTAIRE, SORTIE_INVENTAIRE, TRANSFERT ->
                    throw new IllegalStateException(type + " ne passe pas par ce dispatch generique");
        };
    }

    private ResultatApplication appliquerEntree(Article article, CreerMouvementStockRequest request) {
        BigDecimal quantite = request.quantite();
        if (quantite.signum() <= 0) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "La quantite d'une entree doit etre strictement positive");
        }
        if (request.prixUnitaireHt() == null || request.prixUnitaireHt().signum() < 0) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Le prix unitaire d'achat est requis pour une entree");
        }

        PumpCalculator.ResultatEntree resultat = stockValuationService.appliquerEntree(
                article.getQuantiteEnStock(), article.getValeurStockTotale(), quantite, request.prixUnitaireHt());

        article.setQuantiteEnStock(resultat.nouvelleQuantite());
        article.setValeurStockTotale(resultat.nouvelleValeur());
        article.setPump(resultat.nouveauPump());

        MouvementStock mouvement = construireMouvement(request.type(), quantite, request.prixUnitaireHt(),
                resultat.valeurEntree(), resultat.nouvelleQuantite(), resultat.nouveauPump());
        return new ResultatApplication(mouvement, null);
    }

    private ResultatApplication appliquerSortie(Article article, CreerMouvementStockRequest request) {
        BigDecimal quantite = request.quantite();
        if (quantite.signum() <= 0) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "La quantite d'une sortie doit etre strictement positive");
        }
        String avertissement = null;
        if (quantite.compareTo(article.getQuantiteEnStock()) > 0) {
            avertissement = verifierPolitiqueStockNegatif(article, quantite);
        }

        BigDecimal pumpEnVigueur = article.getPump();
        PumpCalculator.ResultatSortie resultat = stockValuationService.appliquerSortie(
                article.getQuantiteEnStock(), article.getValeurStockTotale(), pumpEnVigueur, quantite);

        article.setQuantiteEnStock(resultat.nouvelleQuantite());
        article.setValeurStockTotale(resultat.nouvelleValeur());
        // Le PUMP ne change jamais lors d'une sortie.

        MouvementStock mouvement = construireMouvement(request.type(), quantite, pumpEnVigueur,
                resultat.valeurSortie(), resultat.nouvelleQuantite(), pumpEnVigueur);
        return new ResultatApplication(mouvement, avertissement);
    }

    private ResultatApplication appliquerAjustement(Article article, CreerMouvementStockRequest request) {
        BigDecimal ecart = request.quantite();
        if (ecart.signum() == 0) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "L'ecart d'un ajustement ne peut pas etre nul");
        }

        BigDecimal pumpEnVigueur = article.getPump();
        if (ecart.signum() > 0) {
            // Surplus constate : valorise au PUMP courant, comme une entree qui ne modifierait pas le PUMP.
            BigDecimal valeurAjustement = ecart.multiply(pumpEnVigueur).setScale(2, RoundingMode.HALF_UP);
            article.setQuantiteEnStock(article.getQuantiteEnStock().add(ecart));
            article.setValeurStockTotale(article.getValeurStockTotale().add(valeurAjustement));
            MouvementStock mouvement = construireMouvement(TypeMouvementStock.AJUSTEMENT, ecart, pumpEnVigueur,
                    valeurAjustement, article.getQuantiteEnStock(), pumpEnVigueur);
            return new ResultatApplication(mouvement, null);
        }

        BigDecimal quantitePerdue = ecart.abs();
        String avertissement = null;
        if (quantitePerdue.compareTo(article.getQuantiteEnStock()) > 0) {
            avertissement = verifierPolitiqueStockNegatif(article, quantitePerdue);
        }
        BigDecimal valeurAjustement = quantitePerdue.multiply(pumpEnVigueur).setScale(2, RoundingMode.HALF_UP);
        article.setQuantiteEnStock(article.getQuantiteEnStock().subtract(quantitePerdue));
        article.setValeurStockTotale(article.getValeurStockTotale().subtract(valeurAjustement));
        MouvementStock mouvement = construireMouvement(TypeMouvementStock.AJUSTEMENT, ecart, pumpEnVigueur,
                valeurAjustement, article.getQuantiteEnStock(), pumpEnVigueur);
        return new ResultatApplication(mouvement, avertissement);
    }

    /** INTERDIT (defaut, comportement historique) : bloque. AUTORISE_AVEC_ALERTE : laisse passer
     * et renvoie un avertissement non bloquant. AUTORISE : laisse passer silencieusement. Jamais de
     * politique universelle imposee - toujours celle configuree sur l'article. */
    private String verifierPolitiqueStockNegatif(Article article, BigDecimal quantiteDemandee) {
        PolitiqueStockNegatif politique = article.getPolitiqueStockNegatif();
        if (politique == PolitiqueStockNegatif.INTERDIT) {
            throw new StockInsuffisantException(article.getCodeArticle(), article.getQuantiteEnStock(), quantiteDemandee);
        }
        String message = "Stock negatif autorise pour " + article.getCodeArticle() + " : "
                + article.getQuantiteEnStock() + " disponible, " + quantiteDemandee + " demande";
        return politique == PolitiqueStockNegatif.AUTORISE_AVEC_ALERTE ? message : null;
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
     * Determine par la categorie du type deja construit (ENTREE pour entree/ajustement-surplus,
     * SORTIE ou ajustement-perte sinon).
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

        boolean entreeEnStock = mouvement.getType() == TypeMouvementStock.AJUSTEMENT
                ? mouvement.getQuantite().signum() > 0
                : categorieDe(mouvement.getType()) == Categorie.ENTREE;

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

    private Depot chargerDepot(UUID entrepriseId, UUID depotId) {
        return depotRepository.findById(depotId)
                .filter(d -> d.getEntreprise().getId().equals(entrepriseId))
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Depot introuvable"));
    }
}
