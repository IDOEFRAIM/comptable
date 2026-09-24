package com.example.comptable.comptabilite.parametrage;

import com.example.comptable.comptabilite.domain.CompteComptable;
import com.example.comptable.comptabilite.domain.RoleCompteSysteme;
import com.example.comptable.comptabilite.parametrage.domain.AccountPurpose;
import com.example.comptable.comptabilite.parametrage.domain.AccountingAccountMapping;
import com.example.comptable.comptabilite.parametrage.repository.AccountingAccountMappingRepository;
import com.example.comptable.comptabilite.service.CompteComptableValidationService;
import com.example.comptable.comptabilite.service.CompteSystemeResolver;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * Point d'entree unique pour "quel compte PCE utiliser pour cette operation, dans cette
 * entreprise ?" - generalise {@link CompteSystemeResolver} (conserve tel quel, jamais remplace,
 * utilise ici comme dernier recours) plutot que de creer un second moteur de resolution de compte
 * (mission Etape 6, section 2). Priorite strictement deterministe, jamais un choix arbitraire en
 * cas d'ambiguite (section 8-9) :
 * <pre>
 * 1. compte explicitement fourni par l'appelant   -&gt; RESOLVED / EXPLICITE
 * 2. mapping actif (entreprise, purpose, contextKey exact) -&gt; RESOLVED / AMBIGUOUS si plusieurs
 * 3. mapping actif (entreprise, purpose, sans contexte)    -&gt; RESOLVED / AMBIGUOUS si plusieurs
 * 4. RoleCompteSysteme correspondant (s'il existe pour ce purpose) -&gt; delegue a CompteSystemeResolver
 * 5. NOT_CONFIGURED
 * </pre>
 * Le compte trouve a n'importe quel palier est toujours revalide (actif/utilisable) avant d'etre
 * renvoye ; un mapping pointant vers un compte desactive produit INVALID, jamais une resolution
 * silencieusement incorrecte.
 */
@Service
@RequiredArgsConstructor
public class AccountingAccountResolver {

    /** Sous-ensemble des purposes qui ont un equivalent RoleCompteSysteme historique - les autres
     * (achats/ventes/stock/immobilisation-actif) n'ont jamais eu de role systeme et restent
     * NOT_CONFIGURED tant qu'aucun mapping n'est configure, jamais devines. */
    private static final Map<AccountPurpose, RoleCompteSysteme> FALLBACK_ROLE_SYSTEME = new EnumMap<>(AccountPurpose.class);

    static {
        FALLBACK_ROLE_SYSTEME.put(AccountPurpose.TVA_COLLECTEE, RoleCompteSysteme.TVA_COLLECTEE);
        FALLBACK_ROLE_SYSTEME.put(AccountPurpose.TVA_RECUPERABLE, RoleCompteSysteme.TVA_DEDUCTIBLE);
        FALLBACK_ROLE_SYSTEME.put(AccountPurpose.BANQUE, RoleCompteSysteme.TRESORERIE_BANQUE);
        FALLBACK_ROLE_SYSTEME.put(AccountPurpose.CAISSE, RoleCompteSysteme.TRESORERIE_CAISSE);
        FALLBACK_ROLE_SYSTEME.put(AccountPurpose.RESULTAT_BENEFICE, RoleCompteSysteme.RESULTAT_BENEFICE);
        FALLBACK_ROLE_SYSTEME.put(AccountPurpose.RESULTAT_PERTE, RoleCompteSysteme.RESULTAT_PERTE);
        FALLBACK_ROLE_SYSTEME.put(AccountPurpose.DOTATION_AMORTISSEMENT, RoleCompteSysteme.DOTATIONS_AMORTISSEMENTS);
        FALLBACK_ROLE_SYSTEME.put(AccountPurpose.CLIENT, RoleCompteSysteme.RACINE_CLIENT);
        FALLBACK_ROLE_SYSTEME.put(AccountPurpose.FOURNISSEUR, RoleCompteSysteme.RACINE_FOURNISSEUR);
    }

    private final AccountingAccountMappingRepository mappingRepository;
    private final CompteSystemeResolver compteSystemeResolver;
    private final CompteComptableValidationService compteComptableValidationService;

    public ResolvedAccount resolve(UUID entrepriseId, AccountPurpose purpose, AccountContext context, UUID compteIdExplicite) {
        if (compteIdExplicite != null) {
            return revaliderEtEnvelopper(entrepriseId, compteIdExplicite, AccountResolutionSource.EXPLICITE,
                    "Compte fourni explicitement par l'operation.");
        }

        String contextKey = context != null ? context.contextKey() : null;
        if (contextKey != null) {
            List<AccountingAccountMapping> specifiques =
                    mappingRepository.findByEntrepriseIdAndPurposeAndContextKeyAndActifTrue(entrepriseId, purpose, contextKey);
            ResolvedAccount depuisSpecifique = resoudreDepuisMappings(entrepriseId, specifiques,
                    AccountResolutionSource.MAPPING_CONTEXTUEL, purpose + ":" + contextKey);
            if (depuisSpecifique != null) {
                return depuisSpecifique;
            }
        }

        List<AccountingAccountMapping> generiques =
                mappingRepository.findByEntrepriseIdAndPurposeAndContextKeyIsNullAndActifTrue(entrepriseId, purpose);
        ResolvedAccount depuisGenerique = resoudreDepuisMappings(entrepriseId, generiques,
                AccountResolutionSource.MAPPING_GENERIQUE, purpose.name());
        if (depuisGenerique != null) {
            return depuisGenerique;
        }

        RoleCompteSysteme role = FALLBACK_ROLE_SYSTEME.get(purpose);
        if (role != null) {
            Optional<CompteComptable> compteRole = compteSystemeResolver.resoudre(entrepriseId, role);
            if (compteRole.isPresent()) {
                return revaliderEtEnvelopper(entrepriseId, compteRole.get().getId(), AccountResolutionSource.ROLE_SYSTEME,
                        "Resolu via le role systeme historique " + role + ".");
            }
        }

        return ResolvedAccount.nonConfigure(
                "Aucun mapping ni role systeme configure pour " + purpose + " dans cette entreprise.");
    }

    private ResolvedAccount resoudreDepuisMappings(UUID entrepriseId, List<AccountingAccountMapping> mappings,
            AccountResolutionSource source, String cle) {
        if (mappings.isEmpty()) {
            return null;
        }
        if (mappings.size() > 1) {
            return ResolvedAccount.ambigu(
                    "Plusieurs mappings actifs (" + mappings.size() + ") correspondent a " + cle + " - configuration a corriger.");
        }
        return revaliderEtEnvelopper(entrepriseId, mappings.get(0).getCompte().getId(), source,
                "Resolu via le parametrage comptable de l'entreprise (" + cle + ").");
    }

    private ResolvedAccount revaliderEtEnvelopper(UUID entrepriseId, UUID compteId, AccountResolutionSource source, String explication) {
        CompteComptable compte;
        try {
            compte = compteComptableValidationService.chargerCompteUtilisable(entrepriseId, compteId, true);
        } catch (ResponseStatusException e) {
            return ResolvedAccount.invalide("Compte configure indisponible : " + e.getReason());
        }
        return new ResolvedAccount(AccountResolutionStatus.RESOLVED, compte.getId(), compte.getNumero(),
                compte.getIntitule(), source, NiveauConfiance.CERTAIN, explication);
    }
}
