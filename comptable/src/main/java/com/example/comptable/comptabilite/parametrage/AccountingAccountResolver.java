package com.example.comptable.comptabilite.parametrage;

import com.example.comptable.comptabilite.domain.CompteComptable;
import com.example.comptable.comptabilite.domain.RoleCompteSysteme;
import com.example.comptable.comptabilite.parametrage.domain.AccountPurpose;
import com.example.comptable.comptabilite.parametrage.domain.AccountingAccountMapping;
import com.example.comptable.comptabilite.parametrage.repository.AccountingAccountMappingRepository;
import com.example.comptable.comptabilite.service.CompteComptableValidationService;
import com.example.comptable.comptabilite.repository.CompteComptableRepository;
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
 * entreprise ?" - generalise l'ancien resolveur de roles systeme (fusionne ici, jamais remplace,
 * utilise ici comme dernier recours) plutot que de creer un second moteur de resolution de compte
 * (mission Etape 6, section 2). Priorite strictement deterministe, jamais un choix arbitraire en
 * cas d'ambiguite (section 8-9) :
 * <pre>
 * 1. compte explicitement fourni par l'appelant   -&gt; RESOLVED / EXPLICITE
 * 2. mapping actif (entreprise, purpose, contextKey exact) -&gt; RESOLVED / AMBIGUOUS si plusieurs
 * 3. mapping actif (entreprise, purpose, sans contexte)    -&gt; RESOLVED / AMBIGUOUS si plusieurs
 * 4. RoleCompteSysteme correspondant (s'il existe pour ce purpose) -&gt; lu dans le referentiel (plan_comptable_reference.role_systeme)
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
    private final CompteComptableRepository compteComptableRepository;
    private final CompteComptableValidationService compteComptableValidationService;

    public ResolvedAccount resolve(UUID entrepriseId, AccountPurpose purpose, AccountContext context, UUID compteIdExplicite) {
        return resolve(entrepriseId, purpose, context, compteIdExplicite, true);
    }

    /**
     * @param exigerDetail {@code false} uniquement pour un compte COLLECTIF (racine client/fournisseur
     *                     sous laquelle on provisionne des sous-comptes auxiliaires) : il n'a pas a etre
     *                     utilisable en detail. Toute imputation reelle exige {@code true}.
     */
    public ResolvedAccount resolve(UUID entrepriseId, AccountPurpose purpose, AccountContext context, UUID compteIdExplicite,
                                   boolean exigerDetail) {
        if (compteIdExplicite != null) {
            return revaliderEtEnvelopper(entrepriseId, compteIdExplicite, AccountResolutionSource.EXPLICITE,
                    "Compte fourni explicitement par l'operation.", exigerDetail);
        }

        String contextKey = context != null ? context.contextKey() : null;
        if (contextKey != null) {
            List<AccountingAccountMapping> specifiques =
                    mappingRepository.findByEntrepriseIdAndPurposeAndContextKeyAndActifTrue(entrepriseId, purpose, contextKey);
            ResolvedAccount depuisSpecifique = resoudreDepuisMappings(entrepriseId, specifiques,
                    AccountResolutionSource.MAPPING_CONTEXTUEL, purpose + ":" + contextKey, exigerDetail);
            if (depuisSpecifique != null) {
                return depuisSpecifique;
            }
        }

        List<AccountingAccountMapping> generiques =
                mappingRepository.findByEntrepriseIdAndPurposeAndContextKeyIsNullAndActifTrue(entrepriseId, purpose);
        ResolvedAccount depuisGenerique = resoudreDepuisMappings(entrepriseId, generiques,
                AccountResolutionSource.MAPPING_GENERIQUE, purpose.name(), exigerDetail);
        if (depuisGenerique != null) {
            return depuisGenerique;
        }

        RoleCompteSysteme role = FALLBACK_ROLE_SYSTEME.get(purpose);
        if (role != null) {
            Optional<CompteComptable> compteRole = compteComptableRepository.findByEntrepriseIdAndReferenceRoleSysteme(entrepriseId, role);
            if (compteRole.isPresent()) {
                return revaliderEtEnvelopper(entrepriseId, compteRole.get().getId(), AccountResolutionSource.ROLE_SYSTEME,
                        "Resolu via le role systeme historique " + role + ".", exigerDetail);
            }
        }

        return ResolvedAccount.nonConfigure(
                "Aucun mapping ni role systeme configure pour " + purpose + " dans cette entreprise.");
    }

    /** Compte resolu ET charge (entite deja validee : de l'entreprise, actif, utilisable en detail), ou
     * l'explication du blocage. Point d'entree des regles metier : elles n'ont plus a recharger le compte. */
    public record CompteResolu(CompteComptable compte, ResolvedAccount resolution) {

        public boolean estResolu() {
            return compte != null;
        }

        /** Phrase d'explication pour une proposition NEEDS_REVIEW (jamais un compte devine). */
        /** Code machine du blocage (jamais {@code null} quand le compte n'est pas resolu). */
        public AccountResolutionCode code() {
            return resolution.code();
        }

        public String explicationBlocage(String libelleCompte) {
            return "Compte " + libelleCompte + " non utilisable pour cette entreprise (" + resolution.status() + ") : "
                    + resolution.explication();
        }
    }

    public CompteResolu resoudreCompte(UUID entrepriseId, AccountPurpose purpose, AccountContext context, UUID compteIdExplicite) {
        ResolvedAccount resolu = resolve(entrepriseId, purpose, context, compteIdExplicite);
        if (!resolu.isResolved()) {
            return new CompteResolu(null, resolu);
        }
        return new CompteResolu(compteComptableValidationService.chargerCompteUtilisable(entrepriseId, resolu.compteId(), true), resolu);
    }

    private ResolvedAccount resoudreDepuisMappings(UUID entrepriseId, List<AccountingAccountMapping> mappings,
            AccountResolutionSource source, String cle, boolean exigerDetail) {
        if (mappings.isEmpty()) {
            return null;
        }
        if (mappings.size() > 1) {
            return ResolvedAccount.ambigu(
                    "Plusieurs mappings actifs (" + mappings.size() + ") correspondent a " + cle + " - configuration a corriger.");
        }
        return revaliderEtEnvelopper(entrepriseId, mappings.get(0).getCompte().getId(), source,
                "Resolu via le parametrage comptable de l'entreprise (" + cle + ").", exigerDetail);
    }

    private ResolvedAccount revaliderEtEnvelopper(UUID entrepriseId, UUID compteId, AccountResolutionSource source, String explication,
                                                  boolean exigerDetail) {
        CompteComptable compte;
        try {
            compte = compteComptableValidationService.chargerCompteUtilisable(entrepriseId, compteId, exigerDetail);
        } catch (ResponseStatusException e) {
            return ResolvedAccount.invalide("Compte configure indisponible : " + e.getReason(), CompteNonUtilisableException.codeDe(e));
        }
        return new ResolvedAccount(AccountResolutionStatus.RESOLVED, compte.getId(), compte.getNumero(),
                compte.getIntitule(), source, NiveauConfiance.CERTAIN, explication);
    }
}
