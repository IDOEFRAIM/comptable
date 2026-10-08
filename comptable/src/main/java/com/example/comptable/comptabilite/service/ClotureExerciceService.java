package com.example.comptable.comptabilite.service;

import com.example.comptable.audit.AuditTrailService;
import com.example.comptable.comptabilite.domain.CompteComptable;
import com.example.comptable.comptabilite.domain.ExerciceComptable;
import com.example.comptable.comptabilite.domain.StatutExercice;
import com.example.comptable.comptabilite.operation.cloture.ClotureRule;
import com.example.comptable.comptabilite.operation.common.AccountingOperationEngine;
import com.example.comptable.comptabilite.operation.common.AccountingSource;
import com.example.comptable.comptabilite.operation.common.AccountingOperationType;
import com.example.comptable.comptabilite.operation.common.ResultatRegle;
import com.example.comptable.comptabilite.operation.common.domain.AccountingOperationProposalLigne;
import com.example.comptable.comptabilite.repository.CompteComptableRepository;
import com.example.comptable.comptabilite.repository.ExerciceComptableRepository;
import com.example.comptable.comptabilite.balance.AccountBalance;
import com.example.comptable.comptabilite.balance.AccountingBalanceEngine;
import com.example.comptable.comptabilite.balance.ReportingView;
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
import java.util.Map;
import java.util.UUID;

/**
 * Cloture un exercice : consulte d'abord la checklist de cloture ({@link ClotureReadinessService})
 * et refuse (422) si un item est BLOCKING (section 15 - la cloture n'est jamais automatique et
 * ne contourne jamais un blocage), transfere le resultat net (charges/produits de la periode) vers
 * le compte de resultat via {@link ClotureRule} + {@link AccountingOperationEngine} (mission
 * cloture Etape 4, section 17 - remplace l'ancienne construction manuelle d'{@code EcritureComptable}
 * et les numeros de compte/journal codes en dur), puis marque l'exercice CLOTURE. Aucune ecriture
 * "a nouveaux" separee n'est necessaire : les comptes de bilan (classes 1-5) se reportent
 * automatiquement d'un exercice a l'autre car {@link AccountingBalanceEngine} (vue LEGAL_LEDGER)
 * cumule sur toute l'historique sans jamais repartir de zero a une frontiere d'exercice -
 * en poster une ferait donc doublonner les soldes. Seuls les comptes de charges/produits
 * (classes 6-7) sont ainsi soldes, puisque le compte de resultat, lui, est recalcule a
 * chaque fois sur la seule periode de l'exercice (vue PERIOD_ACTIVITY du {@link AccountingBalanceEngine}, l'ecriture de cloture etant de nature CLOSING).
 */
@Service
@RequiredArgsConstructor
public class ClotureExerciceService {

    private final EntrepriseAccessService entrepriseAccessService;
    private final ExerciceComptableRepository exerciceComptableRepository;
    private final CompteComptableRepository compteComptableRepository;
    private final AccountingBalanceEngine accountingBalanceEngine;
    private final ClotureReadinessService clotureReadinessService;
    private final ClotureRule clotureRule;
    private final AccountingOperationEngine accountingOperationEngine;
    private final AuditTrailService auditTrailService;

    /**
     * Confirmation explicite des avertissements : sans elle, une cloture qui comporte des avertissements est REFUSEE (409) avec la liste de ce qu'il faut
     * accepter. Le motif est obligatoire et trace, avec l'auteur et la date (journal d'audit).
     */
    public record ConfirmationCloture(Boolean avertissementsConfirmes, String motif) {
        public boolean confirme() {
            return Boolean.TRUE.equals(avertissementsConfirmes);
        }
    }

    /** Cloture historique (appel interne) : les avertissements ne bloquent pas ; seuls les points BLOCKING refusent. */
    @Transactional
    public ExerciceComptableDto cloturer(UUID entrepriseId, UUID exerciceId, UtilisateurPrincipal principal) {
        return cloturer(entrepriseId, exerciceId, principal, null, false);
    }

    /**
     * Workflow de cloture : controles relances -> preparation evaluee -> BLOCKING ? refus avec la liste -> avertissements ? confirmation motivee -> cloture.
     * C'est le point d'entree de l'API.
     */
    @Transactional
    public ExerciceComptableDto cloturerAvecConfirmation(UUID entrepriseId, UUID exerciceId, UtilisateurPrincipal principal, ConfirmationCloture confirmation) {
        return cloturer(entrepriseId, exerciceId, principal, confirmation, true);
    }

    private ExerciceComptableDto cloturer(UUID entrepriseId, UUID exerciceId, UtilisateurPrincipal principal, ConfirmationCloture confirmation,
                                          boolean confirmationRequise) {
        Entreprise entreprise = entrepriseAccessService.verifierAcces(entrepriseId, principal);
        // Serialise les clotures concurrentes de ce meme exercice : la seconde attend, puis voit l'exercice cloture.
        exerciceComptableRepository.verrouiller(exerciceId);
        ExerciceComptable exercice = chargerExercice(entrepriseId, exerciceId);

        if (exercice.getStatut() == StatutExercice.CLOTURE) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Cet exercice est deja cloture");
        }

        // Checklist scopee sur l'exercice REELLEMENT cloture (pas celui qui couvre la date du
        // jour) : une cloture tardive de l'exercice N-1 doit verifier les brouillons/regularisations
        // de N-1, et ne jamais etre bloquee par l'etat de l'exercice courant.
        // La cloture s'appuie sur des constats a jour (detecteurs + rapprochements certains), pas sur un cache.
        clotureReadinessService.actualiserControles(entrepriseId);
        ClotureReadinessDto checklist = clotureReadinessService.construireChecklist(entrepriseId, exercice);
        if (!checklist.blockingItems().isEmpty()) {
            // Refus trace en transaction independante : la 422 ci-dessous annule la transaction courante.
            auditTrailService.enregistrerRefus(principal.getTenantId(), principal.getId(), "CLOTURE_REFUSEE", "EXERCICE",
                    exerciceId.toString(), Map.of("exercice", exercice.getDateDebut() + "/" + exercice.getDateFin(),
                            "bloquants", checklist.blockingItems()));
            throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY,
                    "La cloture est bloquee : " + String.join("; ", checklist.blockingItems()));
        }

        if (!checklist.warnings().isEmpty()) {
            boolean confirme = confirmation != null && confirmation.confirme();
            if (confirmationRequise && !confirme) {
                throw new ResponseStatusException(HttpStatus.CONFLICT,
                        "AVERTISSEMENTS_A_CONFIRMER : " + checklist.warnings().size() + " avertissement(s) a accepter explicitement avant la cloture : "
                                + String.join("; ", checklist.warnings()));
            }
            if (confirmationRequise) {
                if (confirmation.motif() == null || confirmation.motif().isBlank()) {
                    throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY, "Un motif est obligatoire pour accepter les avertissements de cloture");
                }
                auditTrailService.enregistrerActionHumaine(principal.getTenantId(), principal.getId(), "CLOTURE_AVERTISSEMENTS_ACCEPTES", "EXERCICE",
                        exerciceId.toString(), Map.of("exercice", exercice.getDateDebut() + "/" + exercice.getDateFin(),
                                "avertissements", checklist.warnings(), "motif", confirmation.motif().trim()));
            }
        }

        genererEcritureDeterminationResultat(entreprise, exercice, principal);

        exercice.setStatut(StatutExercice.CLOTURE);
        exerciceComptableRepository.save(exercice);

        assurerExerciceSuivant(entreprise, exercice);

        auditTrailService.enregistrerActionHumaine(principal.getTenantId(), principal.getId(), "CLOTURE_TERMINEE", "EXERCICE",
                exerciceId.toString(), Map.of("exercice", exercice.getDateDebut() + "/" + exercice.getDateFin(),
                        "avertissements", checklist.warnings().size()));

        return ExerciceComptableDto.from(exercice);
    }

    private void genererEcritureDeterminationResultat(Entreprise entreprise, ExerciceComptable exercice,
                                                       UtilisateurPrincipal principal) {
        List<CompteComptable> comptes = compteComptableRepository.findByEntrepriseId(entreprise.getId());

        // Activite economique de l'exercice (vue PERIOD_ACTIVITY : ecritures techniques de cloture exclues), en UNE agregation : credit - debit par compte.
        java.util.Map<UUID, BigDecimal> mouvements = accountingBalanceEngine
                .balances(entreprise.getId(), exercice.getDateDebut(), exercice.getDateFin(), ReportingView.PERIOD_ACTIVITY).stream()
                .collect(java.util.stream.Collectors.toMap(AccountBalance::accountId, b -> b.periodNet().negate()));

        // Le resultat de l'exercice est l'activite economique mesuree par le moteur de balance canonique (vue PERIOD_ACTIVITY) sur TOUS les comptes de gestion : charges (6), produits (7)
        // et comptes HAO / impots (8) - la meme identite que le Compte de resultat (XI) et le Bilan (CJ). Aucun second moteur de resultat.
        BigDecimal resultatNet = BigDecimal.ZERO;
        List<AccountingOperationProposalLigne> lignesASolder = new ArrayList<>();
        for (CompteComptable compte : comptes) {
            int classe = classe(compte);
            if (classe != 6 && classe != 7 && classe != 8) {
                continue;
            }
            BigDecimal mouvement = mouvements.getOrDefault(compte.getId(), BigDecimal.ZERO);
            if (mouvement.signum() == 0) {
                continue;
            }
            resultatNet = resultatNet.add(mouvement);
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

        ResultatRegle resultat = clotureRule.construire(entreprise, lignesASolder, resultatNet);
        accountingOperationEngine.proposerEtValiderImmediatement(entreprise.getId(), AccountingOperationType.CLOTURE_EXERCICE,
                null, exercice.getDateFin(), "Determination du resultat de l'exercice", null, resultat,
                AccountingSource.de("EXERCICE", exercice.getId(), "CLOTURE"), principal);
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
