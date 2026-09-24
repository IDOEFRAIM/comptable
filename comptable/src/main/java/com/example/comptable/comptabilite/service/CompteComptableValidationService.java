package com.example.comptable.comptabilite.service;

import com.example.comptable.comptabilite.domain.CompteComptable;
import com.example.comptable.comptabilite.repository.CompteComptableRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import java.util.UUID;

/**
 * Point unique de validation d'un compte avant usage (ecriture, lettrage, rapprochement,
 * avoir, proposition IA...) : meme entreprise, actif, et utilisable comme compte de detail quand
 * l'operation l'exige. Avant cette fondation, cette logique etait re-implementee ad hoc dans
 * chaque service (voir l'audit) ; certains points d'appel (BalanceService.genererLigne,
 * FactureAvoirService.validerAvoir, AccountingProposalBuilder.resoudreCompte) n'appliquaient
 * aucune verification explicite sur ce lookup precis, surs uniquement parce que l'id etait
 * deja filtre en amont par une requete separee.
 */
@Service
@RequiredArgsConstructor
public class CompteComptableValidationService {

    private final CompteComptableRepository compteComptableRepository;

    /**
     * Charge un compte en verifiant uniquement qu'il appartient a l'entreprise - jamais 403,
     * 404 pour ne pas laisser fuiter l'existence d'un compte d'une autre entreprise (meme
     * convention que EntrepriseAccessService). Destine a la consultation en lecture seule
     * (balance, grand livre) : un compte desactive garde son historique consultable, seule la
     * creation de nouvelles ecritures doit etre bloquee - voir {@link #chargerCompteUtilisable}.
     */
    public CompteComptable chargerCompteDeLEntreprise(UUID entrepriseId, UUID compteId) {
        return compteComptableRepository.findById(compteId)
                .filter(c -> c.getEntreprise().getId().equals(entrepriseId))
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Compte introuvable"));
    }

    /**
     * Charge un compte en verifiant qu'il appartient bien a l'entreprise, qu'il est actif, et
     * (si {@code exigerDetail}) qu'il est utilisable comme compte de detail. A utiliser avant
     * toute operation qui POSTE une ecriture (creation manuelle, comptabilisation, avoir,
     * proposition IA...) - pas pour la simple consultation d'historique.
     */
    public CompteComptable chargerCompteUtilisable(UUID entrepriseId, UUID compteId, boolean exigerDetail) {
        CompteComptable compte = chargerCompteDeLEntreprise(entrepriseId, compteId);

        if (!compte.isActif()) {
            throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY,
                    "Le compte " + compte.getNumero() + " est desactive");
        }
        if (exigerDetail && !isUsableCommeDetail(compte)) {
            throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY,
                    "Le compte " + compte.getNumero() + " n'est pas utilisable comme compte de detail");
        }
        return compte;
    }

    /**
     * Un compte derive du referentiel est utilisable en detail si le referentiel le marque
     * ainsi ({@code compte_detail}). Un compte purement local (sans reference) est utilisable en
     * detail tant qu'il n'a pas lui-meme de sous-compte local - recalcule dynamiquement, jamais
     * stocke, pour retrograder automatiquement des qu'une subdivision est ajoutee dessous.
     */
    public boolean isUsableCommeDetail(CompteComptable compte) {
        if (compte.getReference() != null) {
            return compte.getReference().isCompteDetail();
        }
        return !compteComptableRepository.existsByParentId(compte.getId());
    }
}
