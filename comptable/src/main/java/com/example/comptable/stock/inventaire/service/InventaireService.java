package com.example.comptable.stock.inventaire.service;

import com.example.comptable.comptabilite.operation.common.AccountingOperationEngine;
import com.example.comptable.comptabilite.operation.common.domain.StatutOperationProposal;
import com.example.comptable.comptabilite.operation.common.repository.AccountingOperationProposalRepository;
import com.example.comptable.comptabilite.operation.common.web.dto.AccountingOperationProposalDto;
import com.example.comptable.entreprise.domain.Entreprise;
import com.example.comptable.entreprise.service.EntrepriseAccessService;
import com.example.comptable.identite.repository.UtilisateurRepository;
import com.example.comptable.security.UtilisateurPrincipal;
import com.example.comptable.stock.domain.Article;
import com.example.comptable.stock.domain.ModeGestionStock;
import com.example.comptable.stock.repository.ArticleRepository;
import com.example.comptable.stock.inventaire.domain.Inventaire;
import com.example.comptable.stock.inventaire.domain.LigneInventaire;
import com.example.comptable.stock.inventaire.domain.StatutInventaire;
import com.example.comptable.stock.inventaire.repository.InventaireRepository;
import com.example.comptable.stock.inventaire.repository.LigneInventaireRepository;
import com.example.comptable.stock.inventaire.web.dto.CreerInventaireRequest;
import com.example.comptable.stock.inventaire.web.dto.InventaireDto;
import com.example.comptable.stock.inventaire.web.dto.SaisirLigneInventaireRequest;
import com.example.comptable.stock.service.MouvementStockService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.List;
import java.util.UUID;

/**
 * Inventaire physique : saisie du stock compte, calcul de l'ecart contre le stock theorique,
 * regularisation via {@link MouvementStockService#enregistrerRegularisationInventaire} (met a jour
 * la quantite/valeur de l'article immediatement) puis proposition comptable via
 * {@link AccountingOperationEngine} - jamais d'ecriture creee directement ici. La cloture
 * n'est possible qu'une fois cette proposition APPROVED par un humain (voir {@link #cloturer}).
 */
@Service
@RequiredArgsConstructor
public class InventaireService {

    private final EntrepriseAccessService entrepriseAccessService;
    private final InventaireRepository inventaireRepository;
    private final LigneInventaireRepository ligneInventaireRepository;
    private final ArticleRepository articleRepository;
    private final UtilisateurRepository utilisateurRepository;
    private final MouvementStockService mouvementStockService;
    private final AccountingOperationEngine accountingOperationEngine;
    private final AccountingOperationProposalRepository accountingOperationProposalRepository;

    @Transactional
    public InventaireDto creer(UUID entrepriseId, CreerInventaireRequest request, UtilisateurPrincipal principal) {
        Entreprise entreprise = entrepriseAccessService.verifierAcces(entrepriseId, principal);
        Inventaire inventaire = new Inventaire();
        inventaire.setEntreprise(entreprise);
        inventaire.setDate(request.date());
        inventaire.setCommentaire(request.commentaire());
        inventaire.setCreatedBy(utilisateurRepository.getReferenceById(principal.getId()));
        return InventaireDto.from(inventaireRepository.save(inventaire));
    }

    @Transactional(readOnly = true)
    public List<InventaireDto> lister(UUID entrepriseId, UtilisateurPrincipal principal) {
        entrepriseAccessService.verifierAcces(entrepriseId, principal);
        return inventaireRepository.findByEntrepriseIdOrderByDateDesc(entrepriseId).stream()
                .map(InventaireDto::from)
                .toList();
    }

    @Transactional(readOnly = true)
    public InventaireDto obtenir(UUID entrepriseId, UUID inventaireId, UtilisateurPrincipal principal) {
        entrepriseAccessService.verifierAcces(entrepriseId, principal);
        return InventaireDto.from(chargerEntite(entrepriseId, inventaireId));
    }

    @Transactional
    public InventaireDto saisirLigne(UUID entrepriseId, UUID inventaireId, SaisirLigneInventaireRequest request,
                                      UtilisateurPrincipal principal) {
        entrepriseAccessService.verifierAcces(entrepriseId, principal);
        Inventaire inventaire = chargerEntite(entrepriseId, inventaireId);
        if (inventaire.getStatut() != StatutInventaire.BROUILLON && inventaire.getStatut() != StatutInventaire.EN_COURS) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "Cet inventaire n'accepte plus de saisie (statut " + inventaire.getStatut() + ")");
        }
        Article article = articleRepository.findById(request.articleId())
                .filter(a -> a.getEntreprise().getId().equals(entrepriseId))
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Article introuvable"));
        if (!article.isActif()) {
            throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY,
                    "L'article " + article.getCodeArticle() + " est desactive");
        }

        BigDecimal stockTheorique;
        BigDecimal coutUnitaire;
        if (article.getModeGestionStock() == ModeGestionStock.PERMANENT) {
            stockTheorique = article.getQuantiteEnStock();
            coutUnitaire = article.getPump();
        } else {
            List<LigneInventaire> dernieres = ligneInventaireRepository.findDernieresValideesParArticle(article.getId());
            if (!dernieres.isEmpty()) {
                LigneInventaire derniere = dernieres.get(0);
                stockTheorique = derniere.getStockCompte();
                coutUnitaire = derniere.getCoutUnitaire();
            } else if (request.coutUnitaireManuel() != null) {
                stockTheorique = BigDecimal.ZERO;
                coutUnitaire = request.coutUnitaireManuel();
            } else {
                throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY,
                        "Cout unitaire requis pour " + article.getCodeArticle()
                                + " (mode intermittent, aucun inventaire anterieur - premiere valorisation)");
            }
        }

        BigDecimal ecart = request.stockCompte().subtract(stockTheorique);
        BigDecimal valeurEcart = ecart.abs().multiply(coutUnitaire).setScale(2, RoundingMode.HALF_UP);

        LigneInventaire ligne = ligneInventaireRepository.findByInventaireIdAndArticleId(inventaireId, article.getId())
                .orElse(null);
        boolean nouvelleLigne = ligne == null;
        if (nouvelleLigne) {
            ligne = new LigneInventaire();
        }
        ligne.setArticle(article);
        ligne.setStockTheorique(stockTheorique);
        ligne.setStockCompte(request.stockCompte());
        ligne.setEcart(ecart);
        ligne.setCoutUnitaire(coutUnitaire);
        ligne.setValeurEcart(valeurEcart);

        if (nouvelleLigne) {
            inventaire.ajouterLigne(ligne);
        } else {
            ligneInventaireRepository.save(ligne);
        }
        if (inventaire.getStatut() == StatutInventaire.BROUILLON) {
            inventaire.setStatut(StatutInventaire.EN_COURS);
        }
        return InventaireDto.from(inventaireRepository.save(inventaire));
    }

    @Transactional
    public InventaireDto valider(UUID entrepriseId, UUID inventaireId, UtilisateurPrincipal principal) {
        entrepriseAccessService.verifierAcces(entrepriseId, principal);
        Inventaire inventaire = chargerEntite(entrepriseId, inventaireId);
        if (inventaire.getStatut() != StatutInventaire.EN_COURS) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "Seul un inventaire en cours peut etre valide (statut actuel " + inventaire.getStatut() + ")");
        }
        if (inventaire.getLignes().isEmpty()) {
            throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY, "Aucune ligne saisie pour cet inventaire");
        }

        boolean auMoinsUnEcart = inventaire.getLignes().stream().anyMatch(l -> l.getEcart().signum() != 0);
        if (auMoinsUnEcart) {
            for (LigneInventaire ligne : inventaire.getLignes()) {
                if (ligne.getEcart().signum() == 0) {
                    continue;
                }
                String libelle = "Regularisation inventaire - " + ligne.getArticle().getDesignation();
                mouvementStockService.enregistrerRegularisationInventaire(entrepriseId, ligne.getArticle().getId(),
                        ligne.getEcart(), ligne.getCoutUnitaire(), inventaire.getDate(), libelle, inventaire.getId(), principal);
            }
            AccountingOperationProposalDto propositionDto =
                    accountingOperationEngine.proposerRegularisationInventaire(entrepriseId, inventaire, principal);
            inventaire.setProposition(accountingOperationProposalRepository.getReferenceById(propositionDto.id()));
        }

        inventaire.setStatut(StatutInventaire.VALIDE);
        return InventaireDto.from(inventaireRepository.save(inventaire));
    }

    @Transactional
    public InventaireDto cloturer(UUID entrepriseId, UUID inventaireId, UtilisateurPrincipal principal) {
        entrepriseAccessService.verifierAcces(entrepriseId, principal);
        Inventaire inventaire = chargerEntite(entrepriseId, inventaireId);
        if (inventaire.getStatut() != StatutInventaire.VALIDE) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "Seul un inventaire valide peut etre cloture (statut actuel " + inventaire.getStatut() + ")");
        }
        if (inventaire.getProposition() != null) {
            if (inventaire.getProposition().getStatut() != StatutOperationProposal.APPROVED) {
                throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY,
                        "La proposition de regularisation doit etre validee avant de cloturer cet inventaire (statut actuel "
                                + inventaire.getProposition().getStatut() + ")");
            }
            mouvementStockService.lierEcriture("INVENTAIRE", inventaire.getId(), inventaire.getProposition().getEcriture().getId());
        }
        inventaire.setStatut(StatutInventaire.CLOTURE);
        return InventaireDto.from(inventaireRepository.save(inventaire));
    }

    private Inventaire chargerEntite(UUID entrepriseId, UUID inventaireId) {
        return inventaireRepository.findById(inventaireId)
                .filter(i -> i.getEntreprise().getId().equals(entrepriseId))
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Inventaire introuvable"));
    }
}
