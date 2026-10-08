package com.example.comptable.stock.service;

import com.example.comptable.comptabilite.domain.Tiers;
import com.example.comptable.comptabilite.domain.TypeTiers;
import com.example.comptable.comptabilite.operation.achat.AchatMultiLigneOperationRequest;
import com.example.comptable.comptabilite.operation.achat.LigneAchatOperationRequest;
import com.example.comptable.comptabilite.operation.common.AccountingOperationEngine;
import com.example.comptable.comptabilite.operation.common.AccountingSource;
import com.example.comptable.comptabilite.operation.common.web.dto.AccountingOperationProposalDto;
import com.example.comptable.comptabilite.repository.TiersRepository;
import com.example.comptable.entreprise.domain.Entreprise;
import com.example.comptable.entreprise.service.EntrepriseAccessService;
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
import java.util.List;
import java.util.UUID;

/**
 * Chaine d'achats : Bon de Commande Fournisseur -> Reception -> Facture Fournisseur. La
 * reception ({@link #validerReception}) delegue l'entree en stock de chaque ligne a
 * {@link MouvementStockService}, qui porte deja tout le calcul du PUMP et la generation de
 * l'ecriture 311/6031 - aucune duplication de cette logique ici. La facturation fournisseur
 * ({@link #genererFactureFournisseur}) cloture la chaine : une seule facture par commande,
 * generee uniquement une fois receptionnee. Ce service n'a jamais construit d'EcritureComptable
 * lui-meme depuis la mission Etape 5 : il orchestre (charge la commande, traduit ses lignes via
 * {@link LigneAchatMapper}) et delegue la construction/comptabilisation a
 * {@link AccountingOperationEngine#proposerAchatEtValiderImmediatement} - la seule logique
 * d'achat multi-comptes vit dans AchatRule.
 */
@Service
@RequiredArgsConstructor
public class CommandeAchatService {

    private final EntrepriseAccessService entrepriseAccessService;
    private final CommandeAchatRepository commandeAchatRepository;
    private final TiersRepository tiersRepository;
    private final ArticleRepository articleRepository;
    private final MouvementStockService mouvementStockService;
    private final FactureFournisseurRepository factureFournisseurRepository;
    private final AccountingOperationEngine accountingOperationEngine;

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
        // Seule une commande encore EN_COURS peut etre receptionnee : rejeter aussi RECEPTIONNEE
        // ET FACTUREE (sans quoi une commande deja facturee pourrait etre re-receptionnee -
        // double entree en stock, PUMP re-gonfle, seconde ecriture, et statut ramene en arriere).
        if (commande.getStatut() != StatutCommandeAchat.EN_COURS) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "Seule une commande en cours peut etre receptionnee (statut actuel : " + commande.getStatut() + ")");
        }

        LocalDate dateReception = LocalDate.now();
        for (LigneCommandeAchat ligne : commande.getLignes()) {
            // Un article INTERMITTENT ne genere pas de mouvement (la charge reste comptabilisee
            // normalement via genererFactureFournisseur) : enregistrerAvecSource porte ce test et
            // renvoie null dans ce cas - inutile de le repeter ici.
            CreerMouvementStockRequest mouvementRequest = new CreerMouvementStockRequest(
                    ligne.getArticle().getId(), TypeMouvementStock.ENTREE_ACHAT, ligne.getQuantite(), ligne.getPrixUnitaireHt(),
                    dateReception, "Reception " + commande.getNumero() + " - " + commande.getFournisseur().getNom());
            mouvementStockService.enregistrerAvecSource(entrepriseId, mouvementRequest, principal, "COMMANDE_ACHAT", commande.getId());
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

        List<LigneAchatOperationRequest> lignesAchat = LigneAchatMapper.depuisCommande(commande);
        BigDecimal montantHtTotal = lignesAchat.stream().map(LigneAchatOperationRequest::montantHt)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        BigDecimal montantTvaTotal = lignesAchat.stream()
                .map(l -> l.tauxTva() == null ? BigDecimal.ZERO
                        : l.montantHt().multiply(l.tauxTva()).divide(new BigDecimal("100"), 2, RoundingMode.HALF_UP))
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        BigDecimal montantTtcTotal = montantHtTotal.add(montantTvaTotal);

        LocalDate dateFacture = LocalDate.now();
        FactureFournisseur facture = new FactureFournisseur();
        facture.setEntreprise(commande.getEntreprise());
        facture.setCommande(commande);
        facture.setFournisseur(commande.getFournisseur());
        facture.setNumero(genererNumeroFacture(entrepriseId));
        facture.setDateFacture(dateFacture);
        facture.setMontantHt(montantHtTotal);
        facture.setMontantTva(montantTvaTotal);
        facture.setMontantTtc(montantTtcTotal);
        facture = factureFournisseurRepository.save(facture);

        String libelle = "Facture fournisseur " + commande.getNumero() + " - " + commande.getFournisseur().getNom();
        AchatMultiLigneOperationRequest request = new AchatMultiLigneOperationRequest(
                commande.getFournisseur().getId(), dateFacture, facture.getNumero(), libelle,
                lignesAchat, montantTtcTotal, AccountingSource.de("FACTURE_FOURNISSEUR", facture.getId(), "VALIDATION"));
        AccountingOperationProposalDto proposition =
                accountingOperationEngine.proposerAchatEtValiderImmediatement(entrepriseId, request, principal);

        facture.setEcritureId(proposition.ecritureId());
        facture = factureFournisseurRepository.save(facture);

        commande.setStatut(StatutCommandeAchat.FACTUREE);
        commandeAchatRepository.save(commande);

        return FactureFournisseurDto.from(facture, proposition);
    }

    private String genererNumero(UUID entrepriseId) {
        long sequence = commandeAchatRepository.countByEntrepriseId(entrepriseId) + 1;
        return "BC-%d-%04d".formatted(LocalDate.now().getYear(), sequence);
    }

    private String genererNumeroFacture(UUID entrepriseId) {
        long sequence = factureFournisseurRepository.countByEntrepriseId(entrepriseId) + 1;
        return "FF-%d-%04d".formatted(LocalDate.now().getYear(), sequence);
    }
}
