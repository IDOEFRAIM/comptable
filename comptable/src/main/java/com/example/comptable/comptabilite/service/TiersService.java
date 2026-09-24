package com.example.comptable.comptabilite.service;

import com.example.comptable.comptabilite.domain.CompteComptable;
import com.example.comptable.comptabilite.domain.Tiers;
import com.example.comptable.comptabilite.domain.TypeTiers;
import com.example.comptable.comptabilite.repository.CompteComptableRepository;
import com.example.comptable.comptabilite.repository.TiersRepository;
import com.example.comptable.comptabilite.web.dto.TiersDto;
import com.example.comptable.entreprise.domain.Entreprise;
import com.example.comptable.entreprise.service.EntrepriseAccessService;
import com.example.comptable.security.UtilisateurPrincipal;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class TiersService {

    /** Compte collectif racine sous lequel chaque tiers recoit un sous-compte auxiliaire dedie. */
    private static final String COMPTE_RACINE_CLIENT = "411";
    private static final String COMPTE_RACINE_FOURNISSEUR = "401";

    private final EntrepriseAccessService entrepriseAccessService;
    private final TiersRepository tiersRepository;
    private final CompteComptableRepository compteComptableRepository;

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
        String racine = type == TypeTiers.CLIENT ? COMPTE_RACINE_CLIENT : COMPTE_RACINE_FOURNISSEUR;

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

        // Rattachement au compte collectif (401/411) de l'entreprise quand il existe deja (PCE
        // clone/importe depuis le referentiel) : donne au sous-compte auxiliaire sa place dans la
        // hierarchie locale des sa creation, plutot que de compter uniquement sur le backfill de
        // migration pour les comptes deja existants (voir V32).
        CompteComptable compteCollectif = compteComptableRepository
                .findByEntrepriseIdAndNumero(entreprise.getId(), racine)
                .orElse(null);

        CompteComptable sousCompte = new CompteComptable();
        sousCompte.setEntreprise(entreprise);
        sousCompte.setNumero(racine + String.format("%03d", prochainSuffixe));
        sousCompte.setIntitule((type == TypeTiers.CLIENT ? "Client - " : "Fournisseur - ") + prochainSuffixe);
        sousCompte.setLettrable(true);
        if (compteCollectif != null) {
            sousCompte.setParent(compteCollectif);
            sousCompte.setNiveau((short) (compteCollectif.getNiveau() + 1));
        }
        return compteComptableRepository.save(sousCompte);
    }
}
