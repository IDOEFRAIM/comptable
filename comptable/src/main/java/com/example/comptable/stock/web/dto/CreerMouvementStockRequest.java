package com.example.comptable.stock.web.dto;

import com.example.comptable.stock.domain.TypeMouvementStock;
import jakarta.validation.constraints.NotNull;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.UUID;

/**
 * {@code quantite} est une grandeur strictement positive pour ENTREE/SORTIE/TRANSFERT. Pour
 * AJUSTEMENT elle porte le signe de l'ecart constate (positif = surplus, negatif = perte/casse)
 * puisqu'un ajustement n'a pas de sens de mouvement fixe contrairement a une entree ou une sortie.
 * {@code prixUnitaireHt} n'est requis que pour ENTREE (prix d'achat) ; ignore pour SORTIE/
 * AJUSTEMENT qui se valorisent toujours au PUMP en vigueur. {@code depotSourceId}/
 * {@code depotDestinationId} ne sont utilises que pour TRANSFERT. Seuls ENTREE/SORTIE/AJUSTEMENT/
 * TRANSFERT sont acceptes ici - les types portant leur propre tracabilite (ENTREE_ACHAT,
 * SORTIE_VENTE, *_INVENTAIRE, *_PRODUCTION, SORTIE_CONSOMMATION) ne sont crees que par les
 * orchestrateurs internes concernes.
 */
public record CreerMouvementStockRequest(
        @NotNull UUID articleId,
        @NotNull TypeMouvementStock type,
        @NotNull BigDecimal quantite,
        BigDecimal prixUnitaireHt,
        LocalDate dateMouvement,
        String libelle,
        UUID depotSourceId,
        UUID depotDestinationId
) {
    /** Constructeur de compatibilite : tous les appels internes existants (CommandeAchatService,
     * FacturationService, FactureAvoirService) qui ne concernent jamais un transfert restent
     * inchanges. */
    public CreerMouvementStockRequest(UUID articleId, TypeMouvementStock type, BigDecimal quantite,
            BigDecimal prixUnitaireHt, LocalDate dateMouvement, String libelle) {
        this(articleId, type, quantite, prixUnitaireHt, dateMouvement, libelle, null, null);
    }
}
