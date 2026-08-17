package com.example.comptable.comptabilite.service;

import com.example.comptable.comptabilite.domain.CompteComptable;
import com.example.comptable.comptabilite.domain.ExerciceComptable;
import com.example.comptable.comptabilite.domain.NatureCompte;
import com.example.comptable.comptabilite.domain.StatutExercice;
import com.example.comptable.comptabilite.repository.CompteComptableRepository;
import com.example.comptable.comptabilite.repository.ExerciceComptableRepository;
import com.example.comptable.comptabilite.repository.LigneEcritureRepository;
import com.example.comptable.comptabilite.web.dto.BilanDto;
import com.example.comptable.comptabilite.web.dto.CompteResultatDto;
import com.example.comptable.comptabilite.web.dto.PosteFinancierDto;
import com.example.comptable.comptabilite.web.dto.SectionFinanciereDto;
import com.example.comptable.entreprise.service.EntrepriseAccessService;
import com.example.comptable.security.UtilisateurPrincipal;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;

/**
 * Bilan et Compte de Resultat SYSCOHADA (systeme normal, presentation simplifiee) generes a
 * partir des soldes reels des ecritures validees. Classement par classe de compte (1-5 pour le
 * bilan, 6-7 pour le compte de resultat) : la classe est lue sur le referentiel quand le compte
 * en decoule, ou deduite du numero pour les sous-comptes auxiliaires de tiers (crees par
 * TiersService sans reference, ex: 411001) qui n'ont pas de lien vers plan_comptable_reference.
 */
@Service
@RequiredArgsConstructor
public class EtatsFinanciersService {

    private final EntrepriseAccessService entrepriseAccessService;
    private final ExerciceComptableRepository exerciceComptableRepository;
    private final CompteComptableRepository compteComptableRepository;
    private final LigneEcritureRepository ligneEcritureRepository;

    @Transactional(readOnly = true)
    public BilanDto genererBilan(UUID entrepriseId, UUID exerciceId, UtilisateurPrincipal principal) {
        entrepriseAccessService.verifierAcces(entrepriseId, principal);
        ExerciceComptable exercice = chargerExercice(entrepriseId, exerciceId);

        List<CompteComptable> comptes = compteComptableRepository.findByEntrepriseId(entrepriseId);
        LocalDate dateArrete = exercice.getDateFin();

        List<PosteFinancierDto> immobilisations = new ArrayList<>();
        List<PosteFinancierDto> actifCirculant = new ArrayList<>();
        List<PosteFinancierDto> tresorerieActif = new ArrayList<>();
        List<PosteFinancierDto> ressourcesDurables = new ArrayList<>();
        List<PosteFinancierDto> passifCirculant = new ArrayList<>();
        List<PosteFinancierDto> tresoreriePassif = new ArrayList<>();

        for (CompteComptable compte : comptes) {
            int classe = determinerClasse(compte);
            if (classe < 1 || classe > 5) {
                continue; // comptes de charges/produits (6, 7) : hors bilan
            }

            BigDecimal net = ligneEcritureRepository.soldeNetAvant(compte.getId(), dateArrete.plusDays(1));
            if (net.signum() == 0) {
                continue;
            }

            if (classe == 1) {
                // Convention credit-normal uniforme (capital, reserves, resultat...) : un solde
                // debiteur (ex: 129 perte) ressort donc naturellement negatif, en reduction des
                // ressources durables, sans avoir besoin de brancher sur la nature du compte.
                ressourcesDurables.add(new PosteFinancierDto(compte.getNumero(), compte.getIntitule(), net.negate()));
                continue;
            }

            NatureCompte nature = determinerNature(compte);
            if (nature != NatureCompte.ACTIF && nature != NatureCompte.PASSIF) {
                continue;
            }
            BigDecimal montant = nature == NatureCompte.ACTIF ? net : net.negate();
            PosteFinancierDto poste = new PosteFinancierDto(compte.getNumero(), compte.getIntitule(), montant);

            switch (classe) {
                case 2 -> immobilisations.add(poste);
                case 3, 4 -> {
                    if (classe == 4 && nature == NatureCompte.PASSIF) {
                        passifCirculant.add(poste);
                    } else {
                        actifCirculant.add(poste);
                    }
                }
                case 5 -> {
                    if (nature == NatureCompte.ACTIF) {
                        tresorerieActif.add(poste);
                    } else {
                        tresoreriePassif.add(poste);
                    }
                }
                default -> { }
            }
        }

        if (exercice.getStatut() == StatutExercice.OUVERT) {
            BigDecimal resultatProvisoire = genererCompteResultatInterne(exercice).resultatNet();
            if (resultatProvisoire.signum() != 0) {
                String libelle = resultatProvisoire.signum() >= 0
                        ? "Resultat net de l'exercice (benefice, non affecte)"
                        : "Resultat net de l'exercice (perte, non affectee)";
                ressourcesDurables.add(new PosteFinancierDto("120/129", libelle, resultatProvisoire));
            }
        }

        SectionFinanciereDto secImmo = section("Actif immobilise", immobilisations);
        SectionFinanciereDto secActifCirc = section("Actif circulant (stocks, creances)", actifCirculant);
        SectionFinanciereDto secTresoActif = section("Tresorerie - Actif", tresorerieActif);
        SectionFinanciereDto secRessources = section("Ressources durables (capitaux propres & dettes financieres)", ressourcesDurables);
        SectionFinanciereDto secPassifCirc = section("Passif circulant", passifCirculant);
        SectionFinanciereDto secTresoPassif = section("Tresorerie - Passif", tresoreriePassif);

        BigDecimal totalActif = secImmo.total().add(secActifCirc.total()).add(secTresoActif.total());
        BigDecimal totalPassif = secRessources.total().add(secPassifCirc.total()).add(secTresoPassif.total());

        return new BilanDto(dateArrete, secImmo, secActifCirc, secTresoActif, totalActif,
                secRessources, secPassifCirc, secTresoPassif, totalPassif);
    }

    @Transactional(readOnly = true)
    public CompteResultatDto genererCompteResultat(UUID entrepriseId, UUID exerciceId, UtilisateurPrincipal principal) {
        entrepriseAccessService.verifierAcces(entrepriseId, principal);
        ExerciceComptable exercice = chargerExercice(entrepriseId, exerciceId);
        return genererCompteResultatInterne(exercice);
    }

    CompteResultatDto genererCompteResultatInterne(ExerciceComptable exercice) {
        List<CompteComptable> comptes = compteComptableRepository.findByEntrepriseId(exercice.getEntreprise().getId());

        List<PosteFinancierDto> chargesExploitation = new ArrayList<>();
        List<PosteFinancierDto> chargesFinancieres = new ArrayList<>();
        List<PosteFinancierDto> produitsExploitation = new ArrayList<>();
        List<PosteFinancierDto> produitsFinanciers = new ArrayList<>();

        for (CompteComptable compte : comptes) {
            int classe = determinerClasse(compte);
            if (classe != 6 && classe != 7) {
                continue;
            }
            BigDecimal mouvement = ligneEcritureRepository.mouvementNetPeriode(
                    compte.getId(), exercice.getDateDebut(), exercice.getDateFin());
            if (mouvement.signum() == 0) {
                continue;
            }

            // Simplification assumee : les comptes 66x/77x sont traites comme "financiers",
            // le reste des classes 6/7 comme "exploitation". Pas de section HAO (classe 8, non
            // seedee actuellement) : a ajouter le jour ou des comptes HAO sont utilises.
            boolean financier = compte.getNumero().startsWith("66") || compte.getNumero().startsWith("77");
            if (classe == 6) {
                PosteFinancierDto poste = new PosteFinancierDto(compte.getNumero(), compte.getIntitule(), mouvement.negate());
                (financier ? chargesFinancieres : chargesExploitation).add(poste);
            } else {
                PosteFinancierDto poste = new PosteFinancierDto(compte.getNumero(), compte.getIntitule(), mouvement);
                (financier ? produitsFinanciers : produitsExploitation).add(poste);
            }
        }

        SectionFinanciereDto secChargesExpl = section("Charges d'exploitation", chargesExploitation);
        SectionFinanciereDto secProduitsExpl = section("Produits d'exploitation", produitsExploitation);
        SectionFinanciereDto secChargesFin = section("Charges financieres", chargesFinancieres);
        SectionFinanciereDto secProduitsFin = section("Produits financiers", produitsFinanciers);

        BigDecimal resultatExploitation = secProduitsExpl.total().subtract(secChargesExpl.total());
        BigDecimal resultatFinancier = secProduitsFin.total().subtract(secChargesFin.total());
        BigDecimal resultatNet = resultatExploitation.add(resultatFinancier);

        return new CompteResultatDto(exercice.getDateDebut(), exercice.getDateFin(),
                secChargesExpl, secProduitsExpl, resultatExploitation,
                secChargesFin, secProduitsFin, resultatFinancier, resultatNet);
    }

    private ExerciceComptable chargerExercice(UUID entrepriseId, UUID exerciceId) {
        ExerciceComptable exercice = exerciceComptableRepository.findById(exerciceId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Exercice introuvable"));
        if (!exercice.getEntreprise().getId().equals(entrepriseId)) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Exercice introuvable");
        }
        return exercice;
    }

    private SectionFinanciereDto section(String libelle, List<PosteFinancierDto> postes) {
        List<PosteFinancierDto> tries = postes.stream()
                .sorted(Comparator.comparing(PosteFinancierDto::numero))
                .toList();
        BigDecimal total = tries.stream().map(PosteFinancierDto::montant).reduce(BigDecimal.ZERO, BigDecimal::add);
        return new SectionFinanciereDto(libelle, tries, total);
    }

    /** Repli sur le numero pour les sous-comptes de tiers (411xxx/401xxx) sans reference. */
    private NatureCompte determinerNature(CompteComptable compte) {
        if (compte.getNature() != null) {
            return compte.getNature();
        }
        String numero = compte.getNumero();
        if (numero.startsWith("41")) {
            return NatureCompte.ACTIF;
        }
        if (numero.startsWith("40")) {
            return NatureCompte.PASSIF;
        }
        return null;
    }

    private int determinerClasse(CompteComptable compte) {
        if (compte.getReference() != null) {
            return compte.getReference().getClasse();
        }
        return Character.getNumericValue(compte.getNumero().charAt(0));
    }
}
