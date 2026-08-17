package com.example.comptable.recouvrement;

import com.example.comptable.comptabilite.service.BalanceAgeeService;
import com.example.comptable.comptabilite.web.dto.BalanceAgeeLigneDto;
import com.example.comptable.entreprise.service.EntrepriseAccessService;
import com.example.comptable.recouvrement.web.dto.CollectionRiskDto;
import com.example.comptable.security.UtilisateurPrincipal;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;

/**
 * "Recouvrement Intelligent" (mission P1, section 26-28) : reutilise integralement
 * {@link BalanceAgeeService} (deja construit en Sprint 10) pour l'anciennete par client - aucun
 * second calcul de solde/aging. Le niveau de risque est deterministe et explicable (jamais un
 * score opaque), l'action recommandee reste un texte a valider par un humain - jamais d'envoi
 * automatique (mission section 28 : "ne jamais envoyer automatiquement un message agressif ou
 * sensible sans politique de validation claire").
 */
@Service
@RequiredArgsConstructor
public class CollectionRiskService {

    private final EntrepriseAccessService entrepriseAccessService;
    private final BalanceAgeeService balanceAgeeService;

    @Transactional(readOnly = true)
    public List<CollectionRiskDto> generer(UUID entrepriseId, UtilisateurPrincipal principal) {
        entrepriseAccessService.verifierAcces(entrepriseId, principal);

        return balanceAgeeService.generer(entrepriseId, LocalDate.now(), principal).lignes().stream()
                .filter(ligne -> "CLIENT".equals(ligne.typeTiers()) && ligne.total().signum() > 0)
                .map(this::evaluer)
                .sorted(Comparator.comparing(CollectionRiskDto::soldeDu).reversed())
                .toList();
    }

    private CollectionRiskDto evaluer(BalanceAgeeLigneDto ligne) {
        String niveau;
        String action;
        if (ligne.tranchePlus90().signum() > 0 || ligne.tranche61a90().signum() > 0) {
            niveau = "HIGH";
            action = "Contact humain recommande (creance de plus de 60 jours)";
        } else if (ligne.tranche31a60().signum() > 0) {
            niveau = "MEDIUM";
            action = "Relance ferme recommandee";
        } else if (ligne.tranche0a30().signum() > 0) {
            niveau = "LOW";
            action = "Relance douce recommandee";
        } else {
            niveau = "LOW";
            action = "Aucune action necessaire";
        }
        return new CollectionRiskDto(ligne.tiersId(), ligne.tiersNom(), ligne.total(), niveau, action);
    }
}
