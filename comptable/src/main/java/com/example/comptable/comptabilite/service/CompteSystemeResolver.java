package com.example.comptable.comptabilite.service;

import com.example.comptable.comptabilite.domain.CompteComptable;
import com.example.comptable.comptabilite.domain.RoleCompteSysteme;
import com.example.comptable.comptabilite.repository.CompteComptableRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.Optional;
import java.util.UUID;

/** Resout le compte d'une entreprise jouant un role technique donne (TVA collectee/deductible,
 * dotations, resultat, racines tiers) via le registre {@code role_systeme} du referentiel, au
 * lieu d'un numero code en dur. Capacite ajoutee par la fondation SYSCOHADA.
 * <p>
 * Depuis la mission Etape 6 (paramerage comptable par entreprise), ce resolveur n'est plus le
 * point d'entree principal pour les operations courantes : {@code AchatRule}, {@code
 * FacturationService}, {@code ReglementRule}, {@code ClotureRule} et {@code ImmobilisationService}
 * passent desormais par {@code comptabilite.parametrage.AccountingAccountResolver}, qui essaie
 * d'abord un mapping configure par le cabinet (specifique au contexte, puis generique) avant de
 * retomber sur ce resolveur pour les purposes qui ont un role systeme historique equivalent -
 * jamais supprime, jamais duplique. {@code ComptabilisationService}, {@code FactureAvoirService},
 * {@code CommandeAchatService}, {@code DeclarationFiscaleService}, {@code ClotureExerciceService}
 * et {@code TiersService} restent des sites d'appel directs a ce resolveur (hors perimetre de la
 * mission Etape 6, migration eventuelle a faible risque toujours en suivi separe). */
@Service
@RequiredArgsConstructor
public class CompteSystemeResolver {

    private final CompteComptableRepository compteComptableRepository;

    public Optional<CompteComptable> resoudre(UUID entrepriseId, RoleCompteSysteme role) {
        return compteComptableRepository.findByEntrepriseIdAndReferenceRoleSysteme(entrepriseId, role);
    }
}
