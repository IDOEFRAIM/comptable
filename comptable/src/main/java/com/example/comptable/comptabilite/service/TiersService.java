package com.example.comptable.comptabilite.service;

import com.example.comptable.comptabilite.domain.CompteComptable;
import com.example.comptable.comptabilite.domain.Tiers;
import com.example.comptable.comptabilite.domain.TypeTiers;
import com.example.comptable.comptabilite.parametrage.AccountContext;
import com.example.comptable.comptabilite.parametrage.AccountingAccountResolver;
import com.example.comptable.comptabilite.parametrage.ResolvedAccount;
import com.example.comptable.comptabilite.parametrage.domain.AccountPurpose;
import com.example.comptable.comptabilite.repository.CompteComptableRepository;
import com.example.comptable.comptabilite.repository.TiersRepository;
import com.example.comptable.comptabilite.web.dto.TiersDto;
import com.example.comptable.entreprise.domain.Entreprise;
import com.example.comptable.entreprise.service.EntrepriseAccessService;
import com.example.comptable.security.UtilisateurPrincipal;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class TiersService {

    private final EntrepriseAccessService entrepriseAccessService;
    private final TiersRepository tiersRepository;
    private final CompteComptableRepository compteComptableRepository;
    private final AccountingAccountResolver accountingAccountResolver;

    @Transactional
    public TiersDto creer(UUID entrepriseId, TypeTiers type, String nom, String numeroContribuable,
                          UtilisateurPrincipal principal) {
        Entreprise entreprise = entrepriseAccessService.verifierAcces(entrepriseId, principal);

        CompteComptable sousCompte = provisionnerSousCompte(entreprise, type);

        Tiers tiers = new Tiers();
        tiers.setEntreprise(entreprise);
        tiers.setType(type);
        tiers.setNom(nom);
        tiers.setNumeroContribuable(numeroContribuable);
        tiers.setCompteComptable(sousCompte);
        return TiersDto.from(tiersRepository.save(tiers));
    }

    /** Mapping en DTO fait ici, a l'interieur de la transaction : les associations
     * lazy (compte comptable) ne sont plus accessibles une fois la session fermee. */
    @Transactional(readOnly = true)
    public List<TiersDto> lister(UUID entrepriseId, TypeTiers type, UtilisateurPrincipal principal) {
        entrepriseAccessService.verifierAcces(entrepriseId, principal);
        List<Tiers> tiers = type != null
                ? tiersRepository.findByEntrepriseIdAndType(entrepriseId, type)
                : tiersRepository.findByEntrepriseId(entrepriseId);
        return tiers.stream().map(TiersDto::from).toList();
    }

    private CompteComptable provisionnerSousCompte(Entreprise entreprise, TypeTiers type) {
        // Compte collectif sous lequel le tiers recoit son sous-compte auxiliaire : jamais un numero
        // suppose, toujours celui que l'entreprise a configure (mapping CLIENT/FOURNISSEUR, puis role
        // systeme du referentiel). Non configure/ambigu/invalide : refus explicite.
        AccountPurpose purpose = type == TypeTiers.CLIENT ? AccountPurpose.CLIENT : AccountPurpose.FOURNISSEUR;
        ResolvedAccount resolu = accountingAccountResolver.resolve(entreprise.getId(), purpose,
                new AccountContext(null, null, null, type.name(), entreprise.getPays(), null, java.util.Map.of()), null, false);
        if (!resolu.isResolved()) {
            throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY,
                    "Compte collectif " + (type == TypeTiers.CLIENT ? "clients" : "fournisseurs")
                            + " non utilisable pour cette entreprise (" + resolu.status() + ") : " + resolu.explication());
        }
        CompteComptable compteCollectif = compteComptableRepository.findById(resolu.compteId())
                .orElseThrow(() -> new IllegalStateException("Compte collectif resolu mais introuvable : " + resolu.compteId()));
        String racine = compteCollectif.getNumero();

        List<CompteComptable> existants = compteComptableRepository
                .findByEntrepriseIdAndNumeroStartingWithOrderByNumeroDesc(entreprise.getId(), racine);

        int prochainSuffixe = existants.stream()
                .map(CompteComptable::getNumero)
                .filter(numero -> numero.length() > racine.length())
                .map(numero -> numero.substring(racine.length()))
                .mapToInt(suffixe -> {
                    try {
                        return Integer.parseInt(suffixe);
                    } catch (NumberFormatException e) {
                        return 0;
                    }
                })
                .max()
                .orElse(0) + 1;

        // Rattachement au compte collectif resolu : place le sous-compte dans la hierarchie locale des
        // sa creation (voir V32 pour les comptes deja existants).
        CompteComptable sousCompte = new CompteComptable();
        sousCompte.setEntreprise(entreprise);
        sousCompte.setNumero(racine + String.format("%03d", prochainSuffixe));
        sousCompte.setIntitule((type == TypeTiers.CLIENT ? "Client - " : "Fournisseur - ") + prochainSuffixe);
        sousCompte.setLettrable(true);
        sousCompte.setParent(compteCollectif);
        sousCompte.setNiveau((short) (compteCollectif.getNiveau() + 1));
        return compteComptableRepository.save(sousCompte);
    }
}
