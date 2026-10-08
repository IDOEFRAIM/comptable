package com.example.comptable.comptabilite.service;

import com.example.comptable.comptabilite.domain.CompteComptable;
import com.example.comptable.comptabilite.domain.EcritureComptable;
import com.example.comptable.comptabilite.domain.ExerciceComptable;
import com.example.comptable.comptabilite.domain.Journal;
import com.example.comptable.comptabilite.domain.LigneEcriture;
import com.example.comptable.comptabilite.domain.StatutEcriture;
import com.example.comptable.comptabilite.domain.StatutExercice;
import com.example.comptable.comptabilite.domain.Tiers;
import com.example.comptable.comptabilite.repository.EcritureComptableRepository;
import com.example.comptable.comptabilite.repository.ExerciceComptableRepository;
import com.example.comptable.comptabilite.repository.JournalRepository;
import com.example.comptable.comptabilite.repository.TiersRepository;
import com.example.comptable.comptabilite.web.dto.CreerEcritureRequest;
import com.example.comptable.comptabilite.web.dto.EcritureComptableDto;
import com.example.comptable.comptabilite.web.dto.LigneEcritureRequest;
import com.example.comptable.document.repository.DocumentRepository;
import com.example.comptable.entreprise.domain.Entreprise;
import com.example.comptable.entreprise.service.EntrepriseAccessService;
import com.example.comptable.identite.repository.UtilisateurRepository;
import com.example.comptable.security.UtilisateurPrincipal;
import com.example.comptable.comptabilite.operation.common.repository.AccountingOperationProposalRepository;
import com.example.comptable.audit.AuditTrailService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/**
 * Cycle de vie d'une ecriture : saisie manuelle (journal OD/BQ/CA... hors comptabilisation
 * automatique d'un document), validation (BROUILLON -> VALIDEE) et suppression d'un brouillon.
 * Une fois VALIDEE, l'ecriture est intangible : ni modification, ni suppression n'est exposee
 * (principe SYSCOHADA de non-alteration des ecritures validees).
 */
@Service
@RequiredArgsConstructor
public class EcritureComptableService {

    private final EntrepriseAccessService entrepriseAccessService;
    private final EcritureComptableRepository ecritureComptableRepository;
    private final DocumentRepository documentRepository;
    private final JournalRepository journalRepository;
    private final CompteComptableValidationService compteComptableValidationService;
    private final ExerciceComptableRepository exerciceComptableRepository;
    private final TiersRepository tiersRepository;
    private final UtilisateurRepository utilisateurRepository;
    private final AccountingOperationProposalRepository accountingOperationProposalRepository;
    private final AuditTrailService auditTrailService;

    @Transactional(readOnly = true)
    public EcritureComptableDto obtenir(UUID entrepriseId, UUID ecritureId, UtilisateurPrincipal principal) {
        entrepriseAccessService.verifierAcces(entrepriseId, principal);
        return EcritureComptableDto.from(chargerEcriture(entrepriseId, ecritureId));
    }

    @Transactional(readOnly = true)
    public List<EcritureComptableDto> lister(UUID entrepriseId, UtilisateurPrincipal principal) {
        entrepriseAccessService.verifierAcces(entrepriseId, principal);
        return ecritureComptableRepository.findByEntrepriseIdFetchAll(entrepriseId).stream()
                .map(EcritureComptableDto::from)
                .toList();
    }

    /**
     * Saisie manuelle multi-lignes (journal OD, correction, operation bancaire non liee a un
     * document...). Contrairement a la comptabilisation automatique d'un document, l'utilisateur
     * choisit lui-meme chaque compte et chaque montant ; le seul invariant impose reste
     * l'equilibre partie double.
     */
    @Transactional
    public EcritureComptableDto creerManuelle(UUID entrepriseId, CreerEcritureRequest request,
                                               UtilisateurPrincipal principal) {
        Entreprise entreprise = entrepriseAccessService.verifierAcces(entrepriseId, principal);

        Journal journal = journalRepository.findById(request.journalId())
                .filter(j -> j.getEntreprise().getId().equals(entrepriseId))
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Journal introuvable"));

        ExerciceComptable exercice = chargerExerciceOuvert(entrepriseId, request.dateEcriture());

        EcritureComptable ecriture = new EcritureComptable();
        ecriture.setEntreprise(entreprise);
        ecriture.setExercice(exercice);
        ecriture.setJournal(journal);
        ecriture.setDateEcriture(request.dateEcriture());
        ecriture.setLibelle(request.libelle());
        ecriture.setCashFlowEvent(request.cashFlowEvent());
        ecriture.setCreatedBy(utilisateurRepository.getReferenceById(principal.getId()));
        ecriture.setStatut(StatutEcriture.BROUILLON);
        ecriture.setNumeroPiece(request.numeroPiece() != null && !request.numeroPiece().isBlank()
                ? request.numeroPiece().trim()
                : genererNumeroPiece(journal));

        int numeroLigne = 1;
        for (LigneEcritureRequest ligneRequest : request.lignes()) {
            ecriture.ajouterLigne(construireLigne(entrepriseId, ligneRequest, numeroLigne++));
        }

        if (!ecriture.estEquilibree()) {
            throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY,
                    "Ecriture desequilibree : total debit (%s) different du total credit (%s)"
                            .formatted(ecriture.totalDebit(), ecriture.totalCredit()));
        }

        EcritureComptable sauvegardee = ecritureComptableRepository.save(ecriture);
        auditer(principal, "ECRITURE_MANUELLE_CREEE", sauvegardee);
        return EcritureComptableDto.from(sauvegardee);
    }

    private LigneEcriture construireLigne(UUID entrepriseId, LigneEcritureRequest ligneRequest, int numeroLigne) {
        BigDecimal debit = ligneRequest.debit();
        BigDecimal credit = ligneRequest.credit();
        if (debit.signum() < 0 || credit.signum() < 0) {
            throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY,
                    "Ligne " + numeroLigne + " : montants negatifs interdits");
        }
        if ((debit.signum() > 0) == (credit.signum() > 0)) {
            throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY,
                    "Ligne " + numeroLigne + " : renseignez soit un debit, soit un credit, jamais les deux ni aucun");
        }

        CompteComptable compte;
        try {
            compte = compteComptableValidationService.chargerCompteUtilisable(entrepriseId, ligneRequest.compteId(), true);
        } catch (ResponseStatusException e) {
            // Reformule le message avec le numero de ligne, plus utile pour une saisie
            // manuelle multi-lignes que le message generique du service de validation.
            throw new ResponseStatusException(e.getStatusCode(), "Ligne " + numeroLigne + " : " + e.getReason());
        }

        LigneEcriture ligne = new LigneEcriture();
        ligne.setCompte(compte);
        ligne.setLibelle(ligneRequest.libelle());
        ligne.setDebit(debit);
        ligne.setCredit(credit);

        if (ligneRequest.tiersId() != null) {
            Tiers tiers = tiersRepository.findById(ligneRequest.tiersId())
                    .filter(t -> t.getEntreprise().getId().equals(entrepriseId))
                    .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND,
                            "Ligne " + numeroLigne + " : tiers introuvable"));
            ligne.setTiers(tiers);
        }

        return ligne;
    }

    private ExerciceComptable chargerExerciceOuvert(UUID entrepriseId, LocalDate date) {
        return ExerciceOuvertGuard.exigerOuvert(exerciceComptableRepository, entrepriseId, date);
    }

    private String genererNumeroPiece(Journal journal) {
        long sequence = ecritureComptableRepository.countByJournalId(journal.getId()) + 1;
        return "%s-%d-%04d".formatted(journal.getCode(), LocalDate.now().getYear(), sequence);
    }

    @Transactional
    public EcritureComptableDto valider(UUID entrepriseId, UUID ecritureId, UtilisateurPrincipal principal) {
        entrepriseAccessService.verifierAcces(entrepriseId, principal);
        EcritureComptable ecriture = chargerEcriture(entrepriseId, ecritureId);

        if (ecriture.getStatut() == StatutEcriture.VALIDEE) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Cette ecriture est deja validee");
        }
        if (!ecriture.estEquilibree()) {
            throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY,
                    "Ecriture desequilibree (debit != credit), validation impossible");
        }

        ecriture.setStatut(StatutEcriture.VALIDEE);
        EcritureComptable validee = ecritureComptableRepository.save(ecriture);
        auditer(principal, "ECRITURE_VALIDEE", validee);
        return EcritureComptableDto.from(validee);
    }

    @Transactional
    public void supprimer(UUID entrepriseId, UUID ecritureId, UtilisateurPrincipal principal) {
        entrepriseAccessService.verifierAcces(entrepriseId, principal);
        EcritureComptable ecriture = chargerEcriture(entrepriseId, ecritureId);

        if (ecriture.getStatut() == StatutEcriture.VALIDEE) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "Ecriture validee : intangible, suppression impossible");
        }

        documentRepository.findByEcritureId(ecriture.getId()).ifPresent(document -> {
            document.setEcritureId(null);
            documentRepository.save(document);
        });

        // Un brouillon issu du moteur (flux Documents) est reference par sa proposition : la
        // supprimer avec l'ecriture garde le lien coherent et permet de comptabiliser a nouveau la
        // meme piece (idempotence par source), sans laisser de FK pendante.
        accountingOperationProposalRepository.deleteAll(accountingOperationProposalRepository.findByEcritureId(ecriture.getId()));
        accountingOperationProposalRepository.flush();

        auditer(principal, "ECRITURE_SUPPRIMEE", ecriture);
        ecritureComptableRepository.delete(ecriture);
    }

    private void auditer(UtilisateurPrincipal principal, String action, EcritureComptable ecriture) {
        auditTrailService.enregistrerActionHumaine(principal.getTenantId(), principal.getId(), action, "EcritureComptable",
                String.valueOf(ecriture.getId()), java.util.Map.of("numeroPiece", String.valueOf(ecriture.getNumeroPiece()),
                        "journal", ecriture.getJournal().getCode(), "statut", ecriture.getStatut().name()));
    }

    private EcritureComptable chargerEcriture(UUID entrepriseId, UUID ecritureId) {
        EcritureComptable ecriture = ecritureComptableRepository.findById(ecritureId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Ecriture introuvable"));
        if (!ecriture.getEntreprise().getId().equals(entrepriseId)) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Ecriture introuvable");
        }
        return ecriture;
    }
}
