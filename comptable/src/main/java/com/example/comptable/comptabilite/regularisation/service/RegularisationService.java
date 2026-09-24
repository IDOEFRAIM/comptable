package com.example.comptable.comptabilite.regularisation.service;

import com.example.comptable.comptabilite.domain.CompteComptable;
import com.example.comptable.comptabilite.domain.ExerciceComptable;
import com.example.comptable.comptabilite.operation.common.AccountingOperationEngine;
import com.example.comptable.comptabilite.operation.common.repository.AccountingOperationProposalRepository;
import com.example.comptable.comptabilite.operation.common.web.dto.AccountingOperationProposalDto;
import com.example.comptable.comptabilite.regularisation.domain.Regularisation;
import com.example.comptable.comptabilite.regularisation.domain.RegularisationType;
import com.example.comptable.comptabilite.regularisation.domain.StatutRegularisation;
import com.example.comptable.comptabilite.regularisation.repository.RegularisationRepository;
import com.example.comptable.comptabilite.regularisation.web.dto.CreerRegularisationRequest;
import com.example.comptable.comptabilite.regularisation.web.dto.RegularisationDto;
import com.example.comptable.comptabilite.repository.ExerciceComptableRepository;
import com.example.comptable.comptabilite.service.CompteComptableValidationService;
import com.example.comptable.entreprise.domain.Entreprise;
import com.example.comptable.entreprise.service.EntrepriseAccessService;
import com.example.comptable.identite.repository.UtilisateurRepository;
import com.example.comptable.security.UtilisateurPrincipal;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.UUID;

/**
 * Regularisations de fin de periode : creation (BROUILLON, repartition temporelle deterministe
 * calculee ici - jamais uniquement une saisie manuelle libre lorsque les dates le permettent),
 * proposition via {@link AccountingOperationEngine#proposerRegularisation}, validation/rejet
 * delegues au moteur commun (meme mecanisme que l'inventaire - jamais une seconde ecriture creee
 * directement ici). La reprise n'est jamais generee automatiquement : {@link #proposerReprise}
 * exige explicitement que {@code repriseAutomatique} soit vrai et que la regularisation soit deja
 * VALIDEE.
 */
@Service
@RequiredArgsConstructor
public class RegularisationService {

    private final EntrepriseAccessService entrepriseAccessService;
    private final RegularisationRepository regularisationRepository;
    private final ExerciceComptableRepository exerciceComptableRepository;
    private final CompteComptableValidationService compteComptableValidationService;
    private final UtilisateurRepository utilisateurRepository;
    private final AccountingOperationEngine accountingOperationEngine;
    private final AccountingOperationProposalRepository accountingOperationProposalRepository;

    @Transactional
    public RegularisationDto creer(UUID entrepriseId, CreerRegularisationRequest request, UtilisateurPrincipal principal) {
        Entreprise entreprise = entrepriseAccessService.verifierAcces(entrepriseId, principal);
        ExerciceComptable exercice = chargerExercice(entrepriseId, request.exerciceId());
        CompteComptable compteOrigine = compteComptableValidationService
                .chargerCompteUtilisable(entrepriseId, request.compteOrigineId(), true);
        CompteComptable compteContrepartie = compteComptableValidationService
                .chargerCompteUtilisable(entrepriseId, request.compteContrepartieId(), true);

        if (request.montant().signum() < 0) {
            throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY, "Montant negatif interdit");
        }

        Regularisation regularisation = new Regularisation();
        regularisation.setEntreprise(entreprise);
        regularisation.setExercice(exercice);
        regularisation.setType(request.type());
        regularisation.setDateOrigine(request.dateOrigine());
        regularisation.setDateDebut(request.dateDebut());
        regularisation.setDateFin(request.dateFin());
        regularisation.setMontant(request.montant());
        regularisation.setLibelle(request.libelle());
        regularisation.setCompteOrigine(compteOrigine);
        regularisation.setCompteContrepartie(compteContrepartie);
        regularisation.setRepriseAutomatique(Boolean.TRUE.equals(request.repriseAutomatique()));
        regularisation.setCreatedBy(utilisateurRepository.getReferenceById(principal.getId()));

        BigDecimal[] repartition = calculerRepartition(request.type(), request.montant(), request.dateDebut(),
                request.dateFin(), exercice);
        regularisation.setMontantExercice(repartition[0]);
        regularisation.setMontantFutur(repartition[1]);

        return RegularisationDto.from(regularisationRepository.save(regularisation));
    }

    @Transactional(readOnly = true)
    public List<RegularisationDto> lister(UUID entrepriseId, UtilisateurPrincipal principal) {
        entrepriseAccessService.verifierAcces(entrepriseId, principal);
        return regularisationRepository.findByEntrepriseIdOrderByDateOrigineDesc(entrepriseId).stream()
                .map(RegularisationDto::from)
                .toList();
    }

    @Transactional(readOnly = true)
    public RegularisationDto obtenir(UUID entrepriseId, UUID regularisationId, UtilisateurPrincipal principal) {
        entrepriseAccessService.verifierAcces(entrepriseId, principal);
        return RegularisationDto.from(chargerEntite(entrepriseId, regularisationId));
    }

    @Transactional
    public RegularisationDto proposer(UUID entrepriseId, UUID regularisationId, UtilisateurPrincipal principal) {
        entrepriseAccessService.verifierAcces(entrepriseId, principal);
        Regularisation regularisation = chargerEntite(entrepriseId, regularisationId);
        if (regularisation.getStatut() != StatutRegularisation.BROUILLON) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "Seule une regularisation en brouillon peut etre proposee (statut actuel " + regularisation.getStatut() + ")");
        }
        AccountingOperationProposalDto propositionDto =
                accountingOperationEngine.proposerRegularisation(entrepriseId, regularisation, principal);
        regularisation.setProposition(accountingOperationProposalRepository.getReferenceById(propositionDto.id()));
        regularisation.setStatut(StatutRegularisation.PROPOSEE);
        return RegularisationDto.from(regularisationRepository.save(regularisation));
    }

    @Transactional
    public RegularisationDto valider(UUID entrepriseId, UUID regularisationId, UtilisateurPrincipal principal) {
        entrepriseAccessService.verifierAcces(entrepriseId, principal);
        Regularisation regularisation = chargerEntite(entrepriseId, regularisationId);
        if (regularisation.getProposition() == null) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Cette regularisation n'a pas encore ete proposee");
        }
        accountingOperationEngine.valider(entrepriseId, regularisation.getProposition().getId(), principal);
        regularisation.setStatut(StatutRegularisation.VALIDEE);
        return RegularisationDto.from(regularisationRepository.save(regularisation));
    }

    @Transactional
    public RegularisationDto rejeter(UUID entrepriseId, UUID regularisationId, String motif, UtilisateurPrincipal principal) {
        entrepriseAccessService.verifierAcces(entrepriseId, principal);
        Regularisation regularisation = chargerEntite(entrepriseId, regularisationId);
        if (regularisation.getProposition() == null) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Cette regularisation n'a pas encore ete proposee");
        }
        accountingOperationEngine.rejeter(entrepriseId, regularisation.getProposition().getId(), motif, principal);
        regularisation.setStatut(StatutRegularisation.REJETEE);
        return RegularisationDto.from(regularisationRepository.save(regularisation));
    }

    @Transactional
    public RegularisationDto proposerReprise(UUID entrepriseId, UUID regularisationId, UtilisateurPrincipal principal) {
        entrepriseAccessService.verifierAcces(entrepriseId, principal);
        Regularisation regularisation = chargerEntite(entrepriseId, regularisationId);
        if (!regularisation.isRepriseAutomatique()) {
            throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY,
                    "La reprise n'est pas parametree pour cette regularisation - aucune reprise n'est generee sans parametrage explicite");
        }
        if (regularisation.getStatut() != StatutRegularisation.VALIDEE) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Seule une regularisation validee peut etre reprise");
        }
        if (regularisation.getPropositionReprise() != null) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Cette regularisation a deja une reprise proposee");
        }
        AccountingOperationProposalDto propositionDto =
                accountingOperationEngine.proposerReprise(entrepriseId, regularisation, principal);
        regularisation.setPropositionReprise(accountingOperationProposalRepository.getReferenceById(propositionDto.id()));
        return RegularisationDto.from(regularisationRepository.save(regularisation));
    }

    @Transactional
    public RegularisationDto validerReprise(UUID entrepriseId, UUID regularisationId, UtilisateurPrincipal principal) {
        entrepriseAccessService.verifierAcces(entrepriseId, principal);
        Regularisation regularisation = chargerEntite(entrepriseId, regularisationId);
        if (regularisation.getPropositionReprise() == null) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Aucune reprise n'a ete proposee pour cette regularisation");
        }
        accountingOperationEngine.valider(entrepriseId, regularisation.getPropositionReprise().getId(), principal);
        regularisation.setDateReprise(LocalDate.now());
        return RegularisationDto.from(regularisationRepository.save(regularisation));
    }

    /** Prorata au nombre de jours - une regle de repartition universelle, pas une regle SYSCOHADA
     * specifique disputee, donc sure a coder sans deviner. Seuls CCA/PCA/PAR ont une part future :
     * FNP/CAP/PAR-recevoir sont entierement rattaches a l'exercice en cours (c'est leur raison
     * d'etre - reconnaitre une charge/un produit qui appartient a cet exercice malgre l'absence de
     * piece justificative). Si les dates ne permettent pas un calcul deterministe (absentes,
     * incoherentes, ou type non temporel), le montant entier reste attache a l'exercice. */
    private BigDecimal[] calculerRepartition(RegularisationType type, BigDecimal montant, LocalDate dateDebut,
                                              LocalDate dateFin, ExerciceComptable exercice) {
        boolean temporelle = type == RegularisationType.CHARGE_CONSTATEE_AVANCE
                || type == RegularisationType.PRODUIT_CONSTATE_AVANCE
                || type == RegularisationType.PRODUIT_A_REPORTER;
        if (!temporelle || dateDebut == null || dateFin == null || dateFin.isBefore(dateDebut)) {
            return new BigDecimal[]{montant, BigDecimal.ZERO};
        }

        long joursTotal = ChronoUnit.DAYS.between(dateDebut, dateFin) + 1;
        LocalDate borneFin = dateFin.isBefore(exercice.getDateFin()) ? dateFin : exercice.getDateFin();
        long joursExercice = borneFin.isBefore(dateDebut) ? 0 : ChronoUnit.DAYS.between(dateDebut, borneFin) + 1;
        joursExercice = Math.max(0, Math.min(joursExercice, joursTotal));

        BigDecimal montantExercice = montant.multiply(BigDecimal.valueOf(joursExercice))
                .divide(BigDecimal.valueOf(joursTotal), 2, RoundingMode.HALF_UP);
        BigDecimal montantFutur = montant.subtract(montantExercice);
        return new BigDecimal[]{montantExercice, montantFutur};
    }

    private ExerciceComptable chargerExercice(UUID entrepriseId, UUID exerciceId) {
        return exerciceComptableRepository.findById(exerciceId)
                .filter(e -> e.getEntreprise().getId().equals(entrepriseId))
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Exercice comptable introuvable"));
    }

    private Regularisation chargerEntite(UUID entrepriseId, UUID regularisationId) {
        return regularisationRepository.findById(regularisationId)
                .filter(r -> r.getEntreprise().getId().equals(entrepriseId))
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Regularisation introuvable"));
    }
}
