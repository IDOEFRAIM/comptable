package com.example.comptable.stock.service;

import com.example.comptable.comptabilite.operation.achat.AchatMultiLigneOperationRequest;
import com.example.comptable.comptabilite.operation.achat.AchatRule;
import com.example.comptable.comptabilite.operation.achat.LigneAchatOperationRequest;
import com.example.comptable.comptabilite.operation.avoir.AvoirRule;
import com.example.comptable.comptabilite.operation.common.AccountingOperationEngine;
import com.example.comptable.comptabilite.operation.common.AccountingSource;
import com.example.comptable.comptabilite.operation.common.AccountingOperationType;
import com.example.comptable.comptabilite.operation.common.ResultatRegle;
import com.example.comptable.comptabilite.operation.common.domain.AccountingOperationProposalLigne;
import com.example.comptable.comptabilite.operation.common.web.dto.AccountingOperationProposalDto;
import com.example.comptable.entreprise.domain.Entreprise;
import com.example.comptable.entreprise.service.EntrepriseAccessService;
import com.example.comptable.security.UtilisateurPrincipal;
import com.example.comptable.stock.domain.Article;
import com.example.comptable.stock.domain.AvoirFournisseur;
import com.example.comptable.stock.domain.CommandeAchat;
import com.example.comptable.stock.domain.FactureFournisseur;
import com.example.comptable.stock.domain.LigneCommandeAchat;
import com.example.comptable.stock.domain.TypeMouvementStock;
import com.example.comptable.stock.repository.AvoirFournisseurRepository;
import com.example.comptable.stock.repository.FactureFournisseurRepository;
import com.example.comptable.stock.web.dto.AvoirFournisseurDto;
import com.example.comptable.stock.web.dto.CreerAvoirFournisseurRequest;
import com.example.comptable.stock.web.dto.CreerMouvementStockRequest;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/**
 * Avoir fournisseur documentaire (mission Etape 5, section 16) : reconstruit les memes lignes que
 * {@link CommandeAchatService#genererFactureFournisseur} (via {@link LigneAchatMapper} +
 * {@link AchatRule#construireMultiLigne}), les inverse via {@link AvoirRule#inverserLignes} - zero
 * nouvelle logique de calcul de compte/montant - puis propose+valide comme toute autre operation
 * documentaire (facture = confirmation humaine, meme discipline que
 * CommandeAchatService/FacturationService/FactureAvoirService). Un avoir total par facture
 * uniquement dans cette mission (pas de selection partielle des lignes de la commande).
 */
@Service
@RequiredArgsConstructor
public class AvoirFournisseurService {

    private final EntrepriseAccessService entrepriseAccessService;
    private final FactureFournisseurRepository factureFournisseurRepository;
    private final AvoirFournisseurRepository avoirFournisseurRepository;
    private final AchatRule achatRule;
    private final AvoirRule avoirRule;
    private final AccountingOperationEngine accountingOperationEngine;
    private final MouvementStockService mouvementStockService;

    @Transactional
    public AvoirFournisseurDto creer(UUID entrepriseId, UUID factureId, CreerAvoirFournisseurRequest request,
                                      UtilisateurPrincipal principal) {
        Entreprise entreprise = entrepriseAccessService.verifierAcces(entrepriseId, principal);
        FactureFournisseur facture = factureFournisseurRepository.findById(factureId)
                .filter(f -> f.getEntreprise().getId().equals(entrepriseId))
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Facture fournisseur introuvable"));
        if (avoirFournisseurRepository.existsByFactureId(factureId)) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Cette facture a deja un avoir");
        }

        CommandeAchat commande = facture.getCommande();
        List<LigneAchatOperationRequest> lignesAchat = LigneAchatMapper.depuisCommande(commande);
        LocalDate dateEmission = LocalDate.now();
        String libelle = "Avoir fournisseur sur facture " + facture.getNumero() + " - " + facture.getFournisseur().getNom();

        AvoirFournisseur avoir = new AvoirFournisseur();
        avoir.setEntreprise(entreprise);
        avoir.setFacture(facture);
        avoir.setFournisseur(facture.getFournisseur());
        avoir.setNumero(genererNumero(entrepriseId));
        avoir.setDateEmission(dateEmission);
        avoir.setMontantHt(facture.getMontantHt());
        avoir.setMontantTva(facture.getMontantTva());
        avoir.setMontantTtc(facture.getMontantTtc());
        avoir.setMotif(request.motif());
        avoir.setRetourStock(request.retourStock());
        avoir = avoirFournisseurRepository.save(avoir);

        AchatMultiLigneOperationRequest requeteOrigine = new AchatMultiLigneOperationRequest(
                facture.getFournisseur().getId(), dateEmission, facture.getNumero(), libelle, lignesAchat, null, null);
        ResultatRegle resultatOrigine = achatRule.construireMultiLigne(entreprise, facture.getFournisseur(), requeteOrigine);
        List<AccountingOperationProposalLigne> lignesInversees = avoirRule.inverserLignes(resultatOrigine.lignes());
        ResultatRegle resultatInverse = new ResultatRegle(resultatOrigine.typeJournalAttendu(), lignesInversees,
                resultatOrigine.explications(), resultatOrigine.warnings());

        AccountingOperationProposalDto proposition = accountingOperationEngine.proposerEtValiderImmediatement(
                entrepriseId, AccountingOperationType.AVOIR_ACHAT, facture.getFournisseur(), dateEmission, libelle,
                avoir.getNumero(), resultatInverse, AccountingSource.de("AVOIR_FACTURE_FOURNISSEUR", avoir.getId(), "VALIDATION"), principal);

        avoir.setEcritureId(proposition.ecritureId());
        avoir = avoirFournisseurRepository.save(avoir);

        if (request.retourStock()) {
            reintegrerStock(entrepriseId, commande, avoir, principal);
        }

        return AvoirFournisseurDto.from(avoir, proposition.lignes());
    }

    /** Retour physique au fournisseur : une sortie de stock par ligne stockee de la commande
     * d'origine, meme mecanisme generique que toute sortie (voir MouvementStockService), meme choix
     * deja documente par FactureAvoirService.reintegrerStock cote vente qui reutilise ENTREE
     * generique plutot qu'un type dedie. */
    private void reintegrerStock(UUID entrepriseId, CommandeAchat commande, AvoirFournisseur avoir, UtilisateurPrincipal principal) {
        for (LigneCommandeAchat ligne : commande.getLignes()) {
            Article article = ligne.getArticle();
            // Article INTERMITTENT : enregistrerAvecSource renvoie null (pas de suivi de stock en
            // continu), le test unique vit la-bas.
            CreerMouvementStockRequest mouvementRequest = new CreerMouvementStockRequest(
                    article.getId(), TypeMouvementStock.SORTIE, ligne.getQuantite(), null,
                    avoir.getDateEmission(), "Retour fournisseur - avoir " + avoir.getNumero());
            mouvementStockService.enregistrerAvecSource(entrepriseId, mouvementRequest, principal,
                    "AVOIR_FOURNISSEUR", avoir.getId());
        }
    }

    @Transactional(readOnly = true)
    public List<AvoirFournisseurDto> lister(UUID entrepriseId, UtilisateurPrincipal principal) {
        entrepriseAccessService.verifierAcces(entrepriseId, principal);
        return avoirFournisseurRepository.findByEntrepriseId(entrepriseId).stream()
                .map(AvoirFournisseurDto::from)
                .toList();
    }

    @Transactional(readOnly = true)
    public AvoirFournisseurDto obtenir(UUID entrepriseId, UUID avoirId, UtilisateurPrincipal principal) {
        entrepriseAccessService.verifierAcces(entrepriseId, principal);
        AvoirFournisseur avoir = avoirFournisseurRepository.findById(avoirId)
                .filter(a -> a.getEntreprise().getId().equals(entrepriseId))
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Avoir fournisseur introuvable"));
        return AvoirFournisseurDto.from(avoir);
    }

    private String genererNumero(UUID entrepriseId) {
        long sequence = avoirFournisseurRepository.countByEntrepriseId(entrepriseId) + 1;
        return "AF-%d-%04d".formatted(LocalDate.now().getYear(), sequence);
    }
}
