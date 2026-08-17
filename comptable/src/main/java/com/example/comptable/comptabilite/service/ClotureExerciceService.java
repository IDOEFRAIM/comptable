package com.example.comptable.comptabilite.service;

import com.example.comptable.comptabilite.domain.CompteComptable;
import com.example.comptable.comptabilite.domain.EcritureComptable;
import com.example.comptable.comptabilite.domain.ExerciceComptable;
import com.example.comptable.comptabilite.domain.Journal;
import com.example.comptable.comptabilite.domain.LigneEcriture;
import com.example.comptable.comptabilite.domain.StatutEcriture;
import com.example.comptable.comptabilite.domain.StatutExercice;
import com.example.comptable.comptabilite.repository.CompteComptableRepository;
import com.example.comptable.comptabilite.repository.EcritureComptableRepository;
import com.example.comptable.comptabilite.repository.ExerciceComptableRepository;
import com.example.comptable.comptabilite.repository.JournalRepository;
import com.example.comptable.comptabilite.repository.LigneEcritureRepository;
import com.example.comptable.comptabilite.web.dto.CompteResultatDto;
import com.example.comptable.comptabilite.web.dto.ExerciceComptableDto;
import com.example.comptable.entreprise.domain.Entreprise;
import com.example.comptable.entreprise.service.EntrepriseAccessService;
import com.example.comptable.identite.domain.Utilisateur;
import com.example.comptable.identite.repository.UtilisateurRepository;
import com.example.comptable.security.UtilisateurPrincipal;
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
 * Cloture un exercice : verifie qu'aucun brouillon ne subsiste, transfere le resultat net
 * (charges/produits de la periode) vers le compte 120 (benefice) ou 129 (perte) via une
 * ecriture de determination du resultat, puis marque l'exercice CLOTURE. Aucune ecriture
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

    private static final String COMPTE_RESULTAT_BENEFICE = "120";
    private static final String COMPTE_RESULTAT_PERTE = "129";

    private final EntrepriseAccessService entrepriseAccessService;
    private final ExerciceComptableRepository exerciceComptableRepository;
    private final CompteComptableRepository compteComptableRepository;
    private final LigneEcritureRepository ligneEcritureRepository;
    private final EcritureComptableRepository ecritureComptableRepository;
    private final JournalRepository journalRepository;
    private final EtatsFinanciersService etatsFinanciersService;
    private final UtilisateurRepository utilisateurRepository;

    @Transactional
    public ExerciceComptableDto cloturer(UUID entrepriseId, UUID exerciceId, UtilisateurPrincipal principal) {
        Entreprise entreprise = entrepriseAccessService.verifierAcces(entrepriseId, principal);
        ExerciceComptable exercice = chargerExercice(entrepriseId, exerciceId);

        if (exercice.getStatut() == StatutExercice.CLOTURE) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Cet exercice est deja cloture");
        }
        if (ecritureComptableRepository.countByExercice_IdAndStatut(exerciceId, StatutEcriture.BROUILLON) > 0) {
            throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY,
                    "Des ecritures en brouillon subsistent sur cet exercice : validez-les ou supprimez-les avant de cloturer");
        }

        Utilisateur utilisateur = utilisateurRepository.getReferenceById(principal.getId());
        genererEcritureDeterminationResultat(entreprise, exercice, utilisateur);

        exercice.setStatut(StatutExercice.CLOTURE);
        exerciceComptableRepository.save(exercice);

        assurerExerciceSuivant(entreprise, exercice);

        return ExerciceComptableDto.from(exercice);
    }

    private void genererEcritureDeterminationResultat(Entreprise entreprise, ExerciceComptable exercice, Utilisateur utilisateur) {
        CompteResultatDto compteResultat = etatsFinanciersService.genererCompteResultatInterne(exercice);
        List<CompteComptable> comptes = compteComptableRepository.findByEntrepriseId(entreprise.getId());

        record LigneASolder(CompteComptable compte, BigDecimal debit, BigDecimal credit) {
        }
        List<LigneASolder> lignesASolder = new java.util.ArrayList<>();
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
            if (classe == 6) {
                // charge, debit-normal : mouvement (credit-debit) est negatif, on credite l'oppose pour la solder.
                lignesASolder.add(new LigneASolder(compte, BigDecimal.ZERO, mouvement.negate()));
            } else {
                // produit, credit-normal : mouvement est positif, on le debite pour le solder.
                lignesASolder.add(new LigneASolder(compte, mouvement, BigDecimal.ZERO));
            }
        }

        if (lignesASolder.isEmpty()) {
            return; // aucune charge/produit sur la periode : rien a cloturer
        }

        Journal journal = chargerJournalOd(entreprise.getId());
        EcritureComptable ecriture = new EcritureComptable();
        ecriture.setEntreprise(entreprise);
        ecriture.setExercice(exercice);
        ecriture.setJournal(journal);
        ecriture.setDateEcriture(exercice.getDateFin());
        ecriture.setLibelle("Determination du resultat de l'exercice");
        ecriture.setCreatedBy(utilisateur);
        ecriture.setStatut(StatutEcriture.VALIDEE);
        ecriture.setNumeroPiece(genererNumeroPiece(journal));

        for (LigneASolder l : lignesASolder) {
            LigneEcriture ligne = new LigneEcriture();
            ligne.setCompte(l.compte());
            ligne.setLibelle("Solde de cloture");
            ligne.setDebit(l.debit());
            ligne.setCredit(l.credit());
            ecriture.ajouterLigne(ligne);
        }

        BigDecimal resultatNet = compteResultat.resultatNet();
        boolean benefice = resultatNet.signum() >= 0;
        CompteComptable compteResultatNet = chargerCompteResultat(entreprise.getId(),
                benefice ? COMPTE_RESULTAT_BENEFICE : COMPTE_RESULTAT_PERTE);

        LigneEcriture ligneResultat = new LigneEcriture();
        ligneResultat.setCompte(compteResultatNet);
        ligneResultat.setLibelle("Resultat de l'exercice");
        if (benefice) {
            ligneResultat.setDebit(BigDecimal.ZERO);
            ligneResultat.setCredit(resultatNet);
        } else {
            ligneResultat.setDebit(resultatNet.negate());
            ligneResultat.setCredit(BigDecimal.ZERO);
        }
        ecriture.ajouterLigne(ligneResultat);

        if (!ecriture.estEquilibree()) {
            throw new IllegalStateException("Ecriture de determination du resultat desequilibree, cloture annulee");
        }
        ecritureComptableRepository.save(ecriture);
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

    private CompteComptable chargerCompteResultat(UUID entrepriseId, String numero) {
        return compteComptableRepository.findByEntrepriseIdAndNumero(entrepriseId, numero)
                .orElseThrow(() -> new IllegalStateException(
                        "Compte de resultat " + numero + " introuvable, l'entreprise n'a pas ete initialisee correctement"));
    }

    private Journal chargerJournalOd(UUID entrepriseId) {
        return journalRepository.findByEntrepriseIdAndCode(entrepriseId, "OD")
                .orElseThrow(() -> new IllegalStateException("Journal OD introuvable, l'entreprise n'a pas ete initialisee correctement"));
    }

    private String genererNumeroPiece(Journal journal) {
        long sequence = ecritureComptableRepository.countByJournalId(journal.getId()) + 1;
        return "%s-%d-%04d".formatted(journal.getCode(), LocalDate.now().getYear(), sequence);
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
