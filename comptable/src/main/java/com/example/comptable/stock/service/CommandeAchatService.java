package com.example.comptable.stock.service;

import com.example.comptable.comptabilite.domain.CompteComptable;
import com.example.comptable.comptabilite.domain.EcritureComptable;
import com.example.comptable.comptabilite.domain.ExerciceComptable;
import com.example.comptable.comptabilite.domain.Journal;
import com.example.comptable.comptabilite.domain.LigneEcriture;
import com.example.comptable.comptabilite.domain.StatutEcriture;
import com.example.comptable.comptabilite.domain.StatutExercice;
import com.example.comptable.comptabilite.domain.Tiers;
import com.example.comptable.comptabilite.domain.TypeTiers;
import com.example.comptable.comptabilite.repository.CompteComptableRepository;
import com.example.comptable.comptabilite.repository.EcritureComptableRepository;
import com.example.comptable.comptabilite.repository.ExerciceComptableRepository;
import com.example.comptable.comptabilite.repository.JournalRepository;
import com.example.comptable.comptabilite.repository.TiersRepository;
import com.example.comptable.entreprise.domain.Entreprise;
import com.example.comptable.entreprise.service.EntrepriseAccessService;
import com.example.comptable.identite.domain.Utilisateur;
import com.example.comptable.identite.repository.UtilisateurRepository;
import com.example.comptable.security.UtilisateurPrincipal;
import com.example.comptable.stock.domain.Article;
import com.example.comptable.stock.domain.CommandeAchat;
import com.example.comptable.stock.domain.FactureFournisseur;
import com.example.comptable.stock.domain.LigneCommandeAchat;
import com.example.comptable.stock.domain.StatutCommandeAchat;
import com.example.comptable.stock.domain.TypeMouvementStock;
import com.example.comptable.stock.repository.ArticleRepository;
import com.example.comptable.stock.repository.CommandeAchatRepository;
import com.example.comptable.stock.repository.FactureFournisseurRepository;
import com.example.comptable.stock.web.dto.CommandeAchatDto;
import com.example.comptable.stock.web.dto.CreerCommandeAchatRequest;
import com.example.comptable.stock.web.dto.CreerMouvementStockRequest;
import com.example.comptable.stock.web.dto.FactureFournisseurDto;
import com.example.comptable.stock.web.dto.LigneCommandeAchatRequest;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Chaine d'achats : Bon de Commande Fournisseur -> Reception -> Facture Fournisseur. La
 * reception ({@link #validerReception}) delegue l'entree en stock de chaque ligne a
 * {@link MouvementStockService}, qui porte deja tout le calcul du PUMP et la generation de
 * l'ecriture 311/6031 - aucune duplication de cette logique ici. La facturation fournisseur
 * ({@link #genererFactureFournisseur}) cloture la chaine : une seule facture par commande,
 * generee uniquement une fois receptionnee, avec son ecriture d'achat TTC (601/4456/401).
 */
@Service
@RequiredArgsConstructor
public class CommandeAchatService {

    /** Compte reel apres correction V4 (l'intitule d'origine "TVA recuperable sur achats" /
     * numero 4452 a ete renomme en 4456 "Etat, TVA deductible" - cf. FacturationService/
     * ComptabilisationService qui utilisent deja cette meme constante). */
    private static final String COMPTE_TVA_DEDUCTIBLE = "4456";

    private final EntrepriseAccessService entrepriseAccessService;
    private final CommandeAchatRepository commandeAchatRepository;
    private final TiersRepository tiersRepository;
    private final ArticleRepository articleRepository;
    private final MouvementStockService mouvementStockService;
    private final FactureFournisseurRepository factureFournisseurRepository;
    private final CompteComptableRepository compteComptableRepository;
    private final ExerciceComptableRepository exerciceComptableRepository;
    private final JournalRepository journalRepository;
    private final EcritureComptableRepository ecritureComptableRepository;
    private final UtilisateurRepository utilisateurRepository;

    @Transactional
    public CommandeAchatDto creer(UUID entrepriseId, CreerCommandeAchatRequest request, UtilisateurPrincipal principal) {
        Entreprise entreprise = entrepriseAccessService.verifierAcces(entrepriseId, principal);

        Tiers fournisseur = tiersRepository.findById(request.fournisseurId())
                .filter(t -> t.getEntreprise().getId().equals(entrepriseId))
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Fournisseur introuvable"));
        if (fournisseur.getType() != TypeTiers.FOURNISSEUR) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Le tiers selectionne doit etre un fournisseur");
        }

        CommandeAchat commande = new CommandeAchat();
        commande.setEntreprise(entreprise);
        commande.setFournisseur(fournisseur);
        commande.setNumero(genererNumero(entrepriseId));
        commande.setDateCommande(request.dateCommande());
        commande.setStatut(StatutCommandeAchat.EN_COURS);

        for (LigneCommandeAchatRequest ligneRequest : request.lignes()) {
            Article article = articleRepository.findById(ligneRequest.articleId())
                    .filter(a -> a.getEntreprise().getId().equals(entrepriseId))
                    .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Article introuvable"));
            LigneCommandeAchat ligne = new LigneCommandeAchat();
            ligne.setArticle(article);
            ligne.setQuantite(ligneRequest.quantite());
            ligne.setPrixUnitaireHt(ligneRequest.prixUnitaireHt());
            commande.ajouterLigne(ligne);
        }

        return CommandeAchatDto.from(commandeAchatRepository.save(commande));
    }

    @Transactional(readOnly = true)
    public List<CommandeAchatDto> lister(UUID entrepriseId, UtilisateurPrincipal principal) {
        entrepriseAccessService.verifierAcces(entrepriseId, principal);
        return commandeAchatRepository.findByEntrepriseIdFetchAll(entrepriseId).stream()
                .map(CommandeAchatDto::from)
                .toList();
    }

    @Transactional
    public CommandeAchatDto validerReception(UUID entrepriseId, UUID commandeId, UtilisateurPrincipal principal) {
        entrepriseAccessService.verifierAcces(entrepriseId, principal);
        CommandeAchat commande = commandeAchatRepository.findById(commandeId)
                .filter(c -> c.getEntreprise().getId().equals(entrepriseId))
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Commande introuvable"));
        if (commande.getStatut() == StatutCommandeAchat.RECEPTIONNEE) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Cette commande a deja ete receptionnee");
        }

        LocalDate dateReception = LocalDate.now();
        for (LigneCommandeAchat ligne : commande.getLignes()) {
            CreerMouvementStockRequest mouvementRequest = new CreerMouvementStockRequest(
                    ligne.getArticle().getId(), TypeMouvementStock.ENTREE, ligne.getQuantite(), ligne.getPrixUnitaireHt(),
                    dateReception, "Reception " + commande.getNumero() + " - " + commande.getFournisseur().getNom());
            mouvementStockService.enregistrer(entrepriseId, mouvementRequest, principal);
        }

        commande.setStatut(StatutCommandeAchat.RECEPTIONNEE);
        commande.setDateReception(dateReception);
        return CommandeAchatDto.from(commandeAchatRepository.save(commande));
    }

    @Transactional
    public FactureFournisseurDto genererFactureFournisseur(UUID entrepriseId, UUID commandeId, UtilisateurPrincipal principal) {
        entrepriseAccessService.verifierAcces(entrepriseId, principal);
        CommandeAchat commande = commandeAchatRepository.findById(commandeId)
                .filter(c -> c.getEntreprise().getId().equals(entrepriseId))
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Commande introuvable"));
        if (commande.getStatut() == StatutCommandeAchat.EN_COURS) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Cette commande n'a pas encore ete receptionnee");
        }
        if (commande.getStatut() == StatutCommandeAchat.FACTUREE) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Cette commande a deja ete facturee");
        }

        // Regroupe par compte d'achat (des lignes peuvent porter des articles de comptes
        // differents, ex. 601 marchandises vs 604 matieres) plutot que de debiter un seul 601.
        Map<CompteComptable, BigDecimal> montantHtParCompte = new LinkedHashMap<>();
        BigDecimal montantTvaTotal = BigDecimal.ZERO;
        for (LigneCommandeAchat ligne : commande.getLignes()) {
            BigDecimal montantLigneHt = ligne.getQuantite().multiply(ligne.getPrixUnitaireHt()).setScale(2, RoundingMode.HALF_UP);
            montantHtParCompte.merge(ligne.getArticle().getCompteAchat(), montantLigneHt, BigDecimal::add);
            BigDecimal tauxTva = ligne.getArticle().getTauxTva();
            montantTvaTotal = montantTvaTotal.add(
                    montantLigneHt.multiply(tauxTva).divide(new BigDecimal("100"), 2, RoundingMode.HALF_UP));
        }
        BigDecimal montantHtTotal = montantHtParCompte.values().stream().reduce(BigDecimal.ZERO, BigDecimal::add);
        BigDecimal montantTtcTotal = montantHtTotal.add(montantTvaTotal);

        LocalDate dateFacture = LocalDate.now();
        ExerciceComptable exercice = exerciceComptableRepository.findByEntrepriseIdAndDate(entrepriseId, dateFacture)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY,
                        "Aucun exercice comptable ne couvre la date " + dateFacture));
        if (exercice.getStatut() != StatutExercice.OUVERT) {
            throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY, "L'exercice couvrant cette date est cloture");
        }
        Journal journal = journalRepository.findByEntrepriseIdAndCode(entrepriseId, "ACH")
                .orElseThrow(() -> new IllegalStateException("Journal ACH introuvable, l'entreprise n'a pas ete initialisee correctement"));
        Utilisateur utilisateur = utilisateurRepository.getReferenceById(principal.getId());
        String libelle = "Facture fournisseur " + commande.getNumero() + " - " + commande.getFournisseur().getNom();

        EcritureComptable ecriture = new EcritureComptable();
        ecriture.setEntreprise(commande.getEntreprise());
        ecriture.setExercice(exercice);
        ecriture.setJournal(journal);
        ecriture.setDateEcriture(dateFacture);
        ecriture.setLibelle(libelle);
        ecriture.setCreatedBy(utilisateur);
        ecriture.setStatut(StatutEcriture.VALIDEE);
        ecriture.setNumeroPiece(genererNumeroPieceEcriture(journal));

        for (Map.Entry<CompteComptable, BigDecimal> entry : montantHtParCompte.entrySet()) {
            ecriture.ajouterLigne(LigneEcriture.debit(entry.getKey(), entry.getValue(), libelle));
        }
        if (montantTvaTotal.signum() > 0) {
            CompteComptable compteTva = compteComptableRepository.findByEntrepriseIdAndNumero(entrepriseId, COMPTE_TVA_DEDUCTIBLE)
                    .orElseThrow(() -> new IllegalStateException(
                            "Compte TVA " + COMPTE_TVA_DEDUCTIBLE + " introuvable, l'entreprise n'a pas ete initialisee correctement"));
            ecriture.ajouterLigne(LigneEcriture.debit(compteTva, montantTvaTotal, libelle));
        }
        LigneEcriture ligneFournisseur = LigneEcriture.credit(commande.getFournisseur().getCompteComptable(), montantTtcTotal, libelle);
        ligneFournisseur.setTiers(commande.getFournisseur());
        ecriture.ajouterLigne(ligneFournisseur);

        if (!ecriture.estEquilibree()) {
            throw new IllegalStateException("Ecriture de facture fournisseur desequilibree, generation annulee");
        }
        ecriture = ecritureComptableRepository.save(ecriture);

        FactureFournisseur facture = new FactureFournisseur();
        facture.setEntreprise(commande.getEntreprise());
        facture.setCommande(commande);
        facture.setFournisseur(commande.getFournisseur());
        facture.setNumero(genererNumeroFacture(entrepriseId));
        facture.setDateFacture(dateFacture);
        facture.setMontantHt(montantHtTotal);
        facture.setMontantTva(montantTvaTotal);
        facture.setMontantTtc(montantTtcTotal);
        facture.setEcritureId(ecriture.getId());
        facture = factureFournisseurRepository.save(facture);

        commande.setStatut(StatutCommandeAchat.FACTUREE);
        commandeAchatRepository.save(commande);

        return FactureFournisseurDto.from(facture);
    }

    private String genererNumero(UUID entrepriseId) {
        long sequence = commandeAchatRepository.countByEntrepriseId(entrepriseId) + 1;
        return "BC-%d-%04d".formatted(LocalDate.now().getYear(), sequence);
    }

    private String genererNumeroFacture(UUID entrepriseId) {
        long sequence = factureFournisseurRepository.countByEntrepriseId(entrepriseId) + 1;
        return "FF-%d-%04d".formatted(LocalDate.now().getYear(), sequence);
    }

    private String genererNumeroPieceEcriture(Journal journal) {
        long sequence = ecritureComptableRepository.countByJournalId(journal.getId()) + 1;
        return "%s-%d-%04d".formatted(journal.getCode(), LocalDate.now().getYear(), sequence);
    }
}
