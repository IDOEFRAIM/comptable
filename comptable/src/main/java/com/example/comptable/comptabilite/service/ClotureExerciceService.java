package com.example.comptable.comptabilite.service;

import com.example.comptable.comptabilite.domain.CompteComptable;
import com.example.comptable.comptabilite.domain.ExerciceComptable;
import com.example.comptable.comptabilite.domain.StatutExercice;
import com.example.comptable.comptabilite.operation.cloture.ClotureRule;
import com.example.comptable.comptabilite.operation.common.AccountingOperationEngine;
import com.example.comptable.comptabilite.operation.common.AccountingOperationType;
import com.example.comptable.comptabilite.operation.common.ResultatRegle;
import com.example.comptable.comptabilite.operation.common.domain.AccountingOperationProposalLigne;
import com.example.comptable.comptabilite.repository.CompteComptableRepository;
import com.example.comptable.comptabilite.repository.ExerciceComptableRepository;
import com.example.comptable.comptabilite.repository.LigneEcritureRepository;
import com.example.comptable.comptabilite.web.dto.CompteResultatDto;
import com.example.comptable.comptabilite.web.dto.ExerciceComptableDto;
import com.example.comptable.dossier.ClotureReadinessService;
import com.example.comptable.dossier.web.dto.ClotureReadinessDto;
import com.example.comptable.entreprise.domain.Entreprise;
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
import java.util.List;
import java.util.UUID;

/**
 * Cloture un exercice : consulte d'abord la checklist de cloture ({@link ClotureReadinessService})
 * et refuse (422) si un item est BLOCKING (section 15 - la cloture n'est jamais automatique et
 * ne contourne jamais un blocage), transfere le resultat net (charges/produits de la periode) vers
 * le compte de resultat via {@link ClotureRule} + {@link AccountingOperationEngine} (mission
 * cloture Etape 4, section 17 - remplace l'ancienne construction manuelle d'{@code EcritureComptable}
 * et les numeros de compte/journal codes en dur), puis marque l'exercice CLOTURE. Aucune ecriture
 * "a nouveaux" separee n'est necessaire : les comptes de bilan (classes 1-5) se reportent
 * automatiquement d'un exercice a l'autre car {@link LigneEcritureRepository#soldeNetAvant}
 * cumule sur toute l'historique sans jamais repartir de zero a une frontiere d'exercice -
 * en poster une ferait donc doublonner les soldes. Seuls les comptes de charges/produits
 * (classes 6-7) sont ainsi soldes, puisque le compte de resultat, lui, est recalcule a
 * chaque fois sur la seule periode de l'exercice ({@link EtatsFinanciersService}).
 */
@Service
@RequiredArgsConstructor
public class ClotureExerciceService {

    private final EntrepriseAccessService entrepriseAccessService;
    private final ExerciceComptableRepository exerciceComptableRepository;
    private final CompteComptableRepository compteComptableRepository;
    private final LigneEcritureRepository ligneEcritureRepository;
    private final EtatsFinanciersService etatsFinanciersService;
    private final ClotureReadinessService clotureReadinessService;
    private final ClotureRule clotureRule;
    private final AccountingOperationEngine accountingOperationEngine;

    @Transactional
    public ExerciceComptableDto cloturer(UUID entrepriseId, UUID exerciceId, UtilisateurPrincipal principal) {
        Entreprise entreprise = entrepriseAccessService.verifierAcces(entrepriseId, principal);
        ExerciceComptable exercice = chargerExercice(entrepriseId, exerciceId);

        if (exercice.getStatut() == StatutExercice.CLOTURE) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Cet exercice est deja cloture");
        }

        // Checklist scopee sur l'exercice REELLEMENT cloture (pas celui qui couvre la date du
        // jour) : une cloture tardive de l'exercice N-1 doit verifier les brouillons/regularisations
        // de N-1, et ne jamais etre bloquee par l'etat de l'exercice courant.
        ClotureReadinessDto checklist = clotureReadinessService.construireChecklist(entrepriseId, exercice);
        if (!checklist.blockingItems().isEmpty()) {
            throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY,
                    "La cloture est bloquee : " + String.join("; ", checklist.blockingItems()));
        }

        genererEcritureDeterminationResultat(entreprise, exercice, principal);

        exercice.setStatut(StatutExercice.CLOTURE);
        exerciceComptableRepository.save(exercice);

        assurerExerciceSuivant(entreprise, exercice);

        return ExerciceComptableDto.from(exercice);
    }

    private void genererEcritureDeterminationResultat(Entreprise entreprise, ExerciceComptable exercice,
                                                       UtilisateurPrincipal principal) {
        CompteResultatDto compteResultat = etatsFinanciersService.genererCompteResultatInterne(exercice);
        List<CompteComptable> comptes = compteComptableRepository.findByEntrepriseId(entreprise.getId());

        List<AccountingOperationProposalLigne> lignesASolder = new ArrayList<>();
        for (CompteComptable compte : comptes) {
            int classe = classe(compte);
            if (classe != 6 && classe != 7) {
                continue;
            }
            BigDecimal mouvement = ligneEcritureRepository.mouvementNetPeriode(
                    compte.getId(), exercice.getDateDebut(), exercice.getDateFin());
            if (mouvement.signum() == 0) {
                continue;
            }
            // Solde base sur le signe reel du mouvement, jamais sur une direction "normale" supposee
            // par la classe : un compte de variation de stock (classe 6) peut legitimement finir en
            // solde net crediteur si les achats de la periode depassent les sorties (l'inverse de la
            // direction "charge" habituelle). Un solde net crediteur (mouvement > 0) se solde par un
            // debit ; un solde net debiteur (mouvement < 0) se solde par un credit - vrai quelle que
            // soit la classe, donc jamais de bug d'equilibre pour un compte au comportement atypique.
            if (mouvement.signum() > 0) {
                lignesASolder.add(AccountingOperationProposalLigne.debit(compte, mouvement, "Solde de cloture"));
            } else {
                lignesASolder.add(AccountingOperationProposalLigne.credit(compte, mouvement.negate(), "Solde de cloture"));
            }
        }

        if (lignesASolder.isEmpty()) {
            return; // aucune charge/produit sur la periode : rien a cloturer
        }

        ResultatRegle resultat = clotureRule.construire(entreprise, lignesASolder, compteResultat.resultatNet());
        accountingOperationEngine.proposerEtValiderImmediatement(entreprise.getId(), AccountingOperationType.CLOTURE_EXERCICE,
                null, exercice.getDateFin(), "Determination du resultat de l'exercice", null, resultat,
                "EXERCICE", exercice.getId(), principal);
    }

    /** Cree l'exercice suivant (annee civile) s'il n'existe pas deja, par confort pour la saisie a venir. */
    private void assurerExerciceSuivant(Entreprise entreprise, ExerciceComptable exerciceClos) {
        LocalDate debutSuivant = exerciceClos.getDateFin().plusDays(1);
        if (exerciceComptableRepository.findByEntrepriseIdAndDate(entreprise.getId(), debutSuivant).isPresent()) {
            return;
        }
        ExerciceComptable suivant = new ExerciceComptable();
        suivant.setEntreprise(entreprise);
        suivant.setDateDebut(debutSuivant);
        suivant.setDateFin(debutSuivant.plusYears(1).minusDays(1));
        exerciceComptableRepository.save(suivant);
    }

    private int classe(CompteComptable compte) {
        if (compte.getReference() != null) {
            return compte.getReference().getClasse();
        }
        return Character.getNumericValue(compte.getNumero().charAt(0));
    }

    private ExerciceComptable chargerExercice(UUID entrepriseId, UUID exerciceId) {
        ExerciceComptable exercice = exerciceComptableRepository.findById(exerciceId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Exercice introuvable"));
        if (!exercice.getEntreprise().getId().equals(entrepriseId)) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Exercice introuvable");
        }
        return exercice;
    }
}
