package com.example.comptable.comptabilite.service;

import com.example.comptable.comptabilite.domain.CompteComptable;
import com.example.comptable.comptabilite.parametrage.repository.AccountingAccountMappingRepository;
import com.example.comptable.comptabilite.repository.CompteComptableRepository;
import com.example.comptable.comptabilite.repository.LigneEcritureRepository;
import com.example.comptable.comptabilite.web.dto.CompteComptableArbreDto;
import com.example.comptable.comptabilite.web.dto.CompteComptableDto;
import com.example.comptable.comptabilite.web.dto.CreerSubdivisionCompteRequest;
import com.example.comptable.comptabilite.web.dto.ModifierCompteRequest;
import com.example.comptable.entreprise.domain.Entreprise;
import com.example.comptable.entreprise.service.EntrepriseAccessService;
import com.example.comptable.security.UtilisateurPrincipal;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * Plan comptable enrichi d'une entreprise (hierarchie, activation, subdivisions locales) - a ne
 * pas confondre avec {@link ReferentielComptableService}, qui reste le service de la route legacy
 * {@code GET/POST /api/entreprises/{id}/comptes} (contrat inchange, jamais renomme ni deplace).
 */
@Service
@RequiredArgsConstructor
public class PlanComptableService {

    private final EntrepriseAccessService entrepriseAccessService;
    private final CompteComptableRepository compteComptableRepository;
    private final CompteComptableValidationService compteComptableValidationService;
    private final LigneEcritureRepository ligneEcritureRepository;
    private final AccountingAccountMappingRepository accountingAccountMappingRepository;

    @Transactional(readOnly = true)
    public List<CompteComptableDto> lister(UUID entrepriseId, UtilisateurPrincipal principal) {
        entrepriseAccessService.verifierAcces(entrepriseId, principal);
        return compteComptableRepository.findByEntrepriseId(entrepriseId).stream()
                .map(CompteComptableDto::from)
                .sorted(Comparator.comparing(CompteComptableDto::numero))
                .toList();
    }

    /** Arbre construit en Java par regroupement sur parentId : volume par entreprise trop
     * faible pour justifier une requete SQL recursive (CTE). */
    @Transactional(readOnly = true)
    public List<CompteComptableArbreDto> arbre(UUID entrepriseId, UtilisateurPrincipal principal) {
        entrepriseAccessService.verifierAcces(entrepriseId, principal);
        List<CompteComptable> comptes = compteComptableRepository.findByEntrepriseId(entrepriseId);

        Map<UUID, List<CompteComptable>> enfantsParParentId = comptes.stream()
                .filter(c -> c.getParent() != null)
                .collect(Collectors.groupingBy(c -> c.getParent().getId()));

        return comptes.stream()
                .filter(c -> c.getParent() == null)
                .sorted(Comparator.comparing(CompteComptable::getNumero))
                .map(c -> construireNoeud(c, enfantsParParentId))
                .toList();
    }

    private CompteComptableArbreDto construireNoeud(CompteComptable compte, Map<UUID, List<CompteComptable>> enfantsParParentId) {
        List<CompteComptableArbreDto> enfants = enfantsParParentId.getOrDefault(compte.getId(), List.of()).stream()
                .sorted(Comparator.comparing(CompteComptable::getNumero))
                .map(enfant -> construireNoeud(enfant, enfantsParParentId))
                .toList();
        return new CompteComptableArbreDto(
                compte.getId(), compte.getNumero(), compte.getIntitule(), compte.getNiveau(), compte.isActif(),
                compte.getReference() != null,
                enfants
        );
    }

    @Transactional
    public CompteComptableDto creerSubdivision(UUID entrepriseId, CreerSubdivisionCompteRequest request,
                                                UtilisateurPrincipal principal) {
        Entreprise entreprise = entrepriseAccessService.verifierAcces(entrepriseId, principal);
        if (compteComptableRepository.findByEntrepriseIdAndNumero(entrepriseId, request.numero()).isPresent()) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Un compte porte deja ce numero");
        }

        CompteComptable parent = null;
        short niveau = 1;
        if (request.parentId() != null) {
            // Verification tenant seule (pas actif/detail) : on autorise une subdivision sous un
            // parent meme desactive plutot que d'imposer une reactivation prealable.
            parent = compteComptableValidationService.chargerCompteDeLEntreprise(entrepriseId, request.parentId());
            niveau = (short) (parent.getNiveau() + 1);
        }

        CompteComptable compte = new CompteComptable();
        compte.setEntreprise(entreprise);
        compte.setNumero(request.numero());
        compte.setIntitule(request.intitule());
        compte.setLettrable(request.lettrable());
        compte.setParent(parent);
        compte.setNiveau(niveau);
        return CompteComptableDto.from(compteComptableRepository.save(compte));
    }

    /** N'edite jamais numero/parent/reference - uniquement le libelle local et/ou l'activation.
     * Une desactivation n'est jamais bloquee par des mappings comptables existants (mission Etape
     * 6, section 23) - mais leur nombre est renvoye pour que le comptable sache qu'il doit les
     * reconfigurer avant que les operations concernees redeviennent utilisables (le mapping n'est
     * jamais supprime silencieusement ; AccountingAccountResolver le detectera comme INVALID). */
    @Transactional
    public CompteComptableDto modifier(UUID entrepriseId, UUID compteId, ModifierCompteRequest request,
                                        UtilisateurPrincipal principal) {
        entrepriseAccessService.verifierAcces(entrepriseId, principal);
        CompteComptable compte = compteComptableValidationService.chargerCompteDeLEntreprise(entrepriseId, compteId);
        if (request.intitule() != null) {
            compte.setIntitule(request.intitule());
        }
        boolean desactivation = request.actif() != null && !request.actif() && compte.isActif();
        if (request.actif() != null) {
            compte.setActif(request.actif());
        }
        compte = compteComptableRepository.save(compte);

        Integer mappingsActifsCount = desactivation
                ? (int) accountingAccountMappingRepository.countByCompteIdAndActifTrue(compteId)
                : null;
        return CompteComptableDto.from(compte, mappingsActifsCount);
    }

    @Transactional
    public void supprimer(UUID entrepriseId, UUID compteId, UtilisateurPrincipal principal) {
        entrepriseAccessService.verifierAcces(entrepriseId, principal);
        CompteComptable compte = compteComptableValidationService.chargerCompteDeLEntreprise(entrepriseId, compteId);

        if (ligneEcritureRepository.existsByCompteId(compteId)) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "Ce compte a deja des ecritures ; desactivez-le au lieu de le supprimer");
        }
        if (compteComptableRepository.existsByParentId(compteId)) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "Ce compte a des sous-comptes locaux ; supprimez-les d'abord ou desactivez ce compte");
        }
        compteComptableRepository.delete(compte);
    }
}
