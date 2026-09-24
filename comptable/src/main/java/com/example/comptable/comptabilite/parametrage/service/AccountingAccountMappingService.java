package com.example.comptable.comptabilite.parametrage.service;

import com.example.comptable.audit.AuditTrailService;
import com.example.comptable.comptabilite.domain.CompteComptable;
import com.example.comptable.comptabilite.parametrage.AccountContext;
import com.example.comptable.comptabilite.parametrage.AccountingAccountResolver;
import com.example.comptable.comptabilite.parametrage.ResolvedAccount;
import com.example.comptable.comptabilite.parametrage.domain.AccountPurpose;
import com.example.comptable.comptabilite.parametrage.domain.AccountingAccountMapping;
import com.example.comptable.comptabilite.parametrage.repository.AccountingAccountMappingRepository;
import com.example.comptable.comptabilite.parametrage.web.dto.AccountingAccountMappingDto;
import com.example.comptable.comptabilite.parametrage.web.dto.CreerAccountingAccountMappingRequest;
import com.example.comptable.comptabilite.parametrage.web.dto.ModifierAccountingAccountMappingRequest;
import com.example.comptable.comptabilite.parametrage.web.dto.ResolvedAccountDto;
import com.example.comptable.comptabilite.parametrage.web.dto.TestResolutionRequest;
import com.example.comptable.comptabilite.service.CompteComptableValidationService;
import com.example.comptable.entreprise.domain.Entreprise;
import com.example.comptable.entreprise.service.EntrepriseAccessService;
import com.example.comptable.security.UtilisateurPrincipal;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Orchestration + validation du parametrage comptable (mission Etape 6, section 22-24) :
 * verifie le compte (entreprise/actif/utilisable/classe compatible avec le purpose) avant de
 * l'enregistrer, empeche un doublon actif de meme (purpose, contextKey), et trace chaque
 * modification via {@link AuditTrailService} existant (jamais un second systeme d'audit).
 */
@Service
@RequiredArgsConstructor
public class AccountingAccountMappingService {

    private final EntrepriseAccessService entrepriseAccessService;
    private final AccountingAccountMappingRepository mappingRepository;
    private final CompteComptableValidationService compteComptableValidationService;
    private final AccountingAccountResolver accountingAccountResolver;
    private final AuditTrailService auditTrailService;

    @Transactional(readOnly = true)
    public List<AccountingAccountMappingDto> lister(UUID entrepriseId, AccountPurpose purpose, UtilisateurPrincipal principal) {
        entrepriseAccessService.verifierAcces(entrepriseId, principal);
        List<AccountingAccountMapping> mappings = purpose != null
                ? mappingRepository.findByEntrepriseIdAndPurpose(entrepriseId, purpose)
                : mappingRepository.findByEntrepriseId(entrepriseId);
        return mappings.stream().map(AccountingAccountMappingDto::from).toList();
    }

    @Transactional
    public AccountingAccountMappingDto creer(UUID entrepriseId, CreerAccountingAccountMappingRequest request,
                                              UtilisateurPrincipal principal) {
        Entreprise entreprise = entrepriseAccessService.verifierAcces(entrepriseId, principal);
        CompteComptable compte = validerCompteEtPurpose(entrepriseId, request.compteId(), request.purpose());
        verifierAbsenceDoublon(entrepriseId, request.purpose(), request.contextKey(), null);

        AccountingAccountMapping mapping = new AccountingAccountMapping();
        mapping.setEntreprise(entreprise);
        mapping.setPurpose(request.purpose());
        mapping.setContextKey(request.contextKey());
        mapping.setCompte(compte);
        mapping.setActif(true);
        mapping.setPriorite(request.priorite() != null ? request.priorite() : 0);
        mapping.setCreatedBy(principal.getId());
        mapping = mappingRepository.save(mapping);

        auditer(principal, "CREATE_MAPPING", mapping);
        return AccountingAccountMappingDto.from(mapping);
    }

    @Transactional
    public AccountingAccountMappingDto modifier(UUID entrepriseId, UUID mappingId, ModifierAccountingAccountMappingRequest request,
                                                 UtilisateurPrincipal principal) {
        entrepriseAccessService.verifierAcces(entrepriseId, principal);
        AccountingAccountMapping mapping = chargerMapping(entrepriseId, mappingId);

        if (request.compteId() != null) {
            mapping.setCompte(validerCompteEtPurpose(entrepriseId, request.compteId(), mapping.getPurpose()));
        }
        if (request.contextKey() != null) {
            mapping.setContextKey(request.contextKey().isBlank() ? null : request.contextKey());
        }
        boolean changementActivation = request.actif() != null && request.actif() != mapping.isActif();
        if (request.actif() != null) {
            if (request.actif()) {
                verifierAbsenceDoublon(entrepriseId, mapping.getPurpose(), mapping.getContextKey(), mapping.getId());
            }
            mapping.setActif(request.actif());
        } else if (mapping.isActif()) {
            // Un compte ou un contextKey a pu changer sur un mapping deja actif : re-verifier
            // qu'aucun autre mapping actif ne couvre desormais le meme (purpose, contextKey).
            verifierAbsenceDoublon(entrepriseId, mapping.getPurpose(), mapping.getContextKey(), mapping.getId());
        }

        mapping = mappingRepository.save(mapping);
        auditer(principal, changementActivation
                ? (mapping.isActif() ? "ACTIVATE_MAPPING" : "DEACTIVATE_MAPPING")
                : "UPDATE_MAPPING", mapping);
        return AccountingAccountMappingDto.from(mapping);
    }

    @Transactional
    public void supprimer(UUID entrepriseId, UUID mappingId, UtilisateurPrincipal principal) {
        entrepriseAccessService.verifierAcces(entrepriseId, principal);
        AccountingAccountMapping mapping = chargerMapping(entrepriseId, mappingId);
        mappingRepository.delete(mapping);
        auditer(principal, "DELETE_MAPPING", mapping);
    }

    @Transactional(readOnly = true)
    public ResolvedAccountDto testerResolution(UUID entrepriseId, TestResolutionRequest request, UtilisateurPrincipal principal) {
        entrepriseAccessService.verifierAcces(entrepriseId, principal);
        AccountContext context = new AccountContext(null, request.natureOperation(), null, null, null,
                request.contextKey(), request.metadata() != null ? request.metadata() : Map.of());
        ResolvedAccount resolved = accountingAccountResolver.resolve(entrepriseId, request.purpose(), context, null);
        return ResolvedAccountDto.from(resolved);
    }

    private CompteComptable validerCompteEtPurpose(UUID entrepriseId, UUID compteId, AccountPurpose purpose) {
        CompteComptable compte = compteComptableValidationService.chargerCompteUtilisable(entrepriseId, compteId, true);
        int classe = Character.getNumericValue(compte.getNumero().charAt(0));
        if (!purpose.classesAttendues().contains(classe)) {
            throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY,
                    "Le compte " + compte.getNumero() + " (classe " + classe + ") n'est pas compatible avec " + purpose
                            + " (classe attendue " + purpose.classesAttendues() + ")");
        }
        return compte;
    }

    private void verifierAbsenceDoublon(UUID entrepriseId, AccountPurpose purpose, String contextKey, UUID mappingIdExclu) {
        List<AccountingAccountMapping> existants = contextKey != null
                ? mappingRepository.findByEntrepriseIdAndPurposeAndContextKeyAndActifTrue(entrepriseId, purpose, contextKey)
                : mappingRepository.findByEntrepriseIdAndPurposeAndContextKeyIsNullAndActifTrue(entrepriseId, purpose);
        boolean conflit = existants.stream().anyMatch(m -> !m.getId().equals(mappingIdExclu));
        if (conflit) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "Un mapping actif existe deja pour " + purpose + (contextKey != null ? (":" + contextKey) : ""));
        }
    }

    private AccountingAccountMapping chargerMapping(UUID entrepriseId, UUID mappingId) {
        return mappingRepository.findById(mappingId)
                .filter(m -> m.getEntreprise().getId().equals(entrepriseId))
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Mapping introuvable"));
    }

    private void auditer(UtilisateurPrincipal principal, String action, AccountingAccountMapping mapping) {
        auditTrailService.enregistrerActionHumaine(principal.getTenantId(), principal.getId(), action,
                "AccountingAccountMapping", mapping.getId().toString(),
                Map.of("purpose", mapping.getPurpose().name(),
                        "contextKey", mapping.getContextKey() != null ? mapping.getContextKey() : "",
                        "compteNumero", mapping.getCompte().getNumero(),
                        "actif", mapping.isActif()));
    }
}
