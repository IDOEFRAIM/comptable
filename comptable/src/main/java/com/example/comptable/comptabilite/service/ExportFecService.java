package com.example.comptable.comptabilite.service;

import com.example.comptable.comptabilite.domain.EcritureComptable;
import com.example.comptable.comptabilite.domain.ExerciceComptable;
import com.example.comptable.comptabilite.domain.LigneEcriture;
import com.example.comptable.comptabilite.domain.StatutEcriture;
import com.example.comptable.comptabilite.repository.EcritureComptableRepository;
import com.example.comptable.comptabilite.repository.ExerciceComptableRepository;
import com.example.comptable.comptabilite.web.dto.FecExportDto;
import com.example.comptable.entreprise.domain.Entreprise;
import com.example.comptable.entreprise.service.EntrepriseAccessService;
import com.example.comptable.security.UtilisateurPrincipal;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.math.BigDecimal;
import java.time.format.DateTimeFormatter;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

/**
 * Genere le Fichier des Ecritures Comptables (FEC) d'un exercice, au format normalise attendu par
 * l'administration fiscale (18 colonnes tabulees, cf. arrete DGI) : une ligne par ligne d'ecriture,
 * seules les ecritures VALIDEEs sont exportees (un brouillon n'est pas opposable au fisc).
 * <p>
 * {@code EcritureNum} est une sequence recalculee a l'export, propre a chaque journal et ordonnee
 * par date puis piece : le modele de donnees actuel ne conserve pas de numero de piece sequentiel
 * distinct par journal, cette sequence en tient donc lieu.
 */
@Service
@RequiredArgsConstructor
public class ExportFecService {

    private static final List<String> ENTETES = List.of(
            "JournalCode", "JournalLib", "EcritureNum", "EcritureDate", "CompteNum", "CompteLib",
            "CompAuxNum", "CompAuxLib", "PieceRef", "PieceDate", "EcritureLib", "Debit", "Credit",
            "EcritureLet", "DateLet", "ValidDate", "Montantdevise", "Idevise");

    private static final DateTimeFormatter FORMAT_DATE = DateTimeFormatter.ofPattern("yyyyMMdd");

    private final EntrepriseAccessService entrepriseAccessService;
    private final ExerciceComptableRepository exerciceComptableRepository;
    private final EcritureComptableRepository ecritureComptableRepository;

    @Transactional(readOnly = true)
    public FecExportDto exporter(UUID entrepriseId, UUID exerciceId, UtilisateurPrincipal principal) {
        Entreprise entreprise = entrepriseAccessService.verifierAcces(entrepriseId, principal);
        ExerciceComptable exercice = chargerExercice(entrepriseId, exerciceId);

        List<EcritureComptable> ecritures = ecritureComptableRepository
                .findByExerciceIdAndStatutFetchAll(exerciceId, StatutEcriture.VALIDEE);

        StringBuilder contenu = new StringBuilder();
        contenu.append(String.join("\t", ENTETES)).append('\n');

        Map<UUID, Integer> sequenceParJournal = new HashMap<>();
        for (EcritureComptable ecriture : ecritures) {
            int numero = sequenceParJournal.merge(ecriture.getJournal().getId(), 1, Integer::sum);
            for (LigneEcriture ligne : ecriture.getLignes()) {
                contenu.append(ligneFec(ecriture, ligne, numero)).append('\n');
            }
        }

        String nomFichier = "%sFEC%s.txt".formatted(
                identifiantEntreprise(entreprise), exercice.getDateFin().format(FORMAT_DATE));

        return new FecExportDto(nomFichier, contenu.toString());
    }

    private String ligneFec(EcritureComptable ecriture, LigneEcriture ligne, int numeroSequence) {
        String dateEcriture = ecriture.getDateEcriture().format(FORMAT_DATE);
        String dateValidation = ecriture.getCreatedAt() != null
                ? ecriture.getCreatedAt().toLocalDate().format(FORMAT_DATE)
                : dateEcriture;
        String compAuxNum = ligne.getTiers() != null ? ligne.getCompte().getNumero() : "";
        String compAuxLib = ligne.getTiers() != null ? ligne.getTiers().getNom() : "";
        String libelle = ecriture.getLibelle() != null && !ecriture.getLibelle().isBlank()
                ? ecriture.getLibelle() : ligne.getLibelle();

        List<String> champs = List.of(
                ecriture.getJournal().getCode(),
                ecriture.getJournal().getLibelle(),
                String.valueOf(numeroSequence),
                dateEcriture,
                ligne.getCompte().getNumero(),
                ligne.getCompte().getIntitule(),
                compAuxNum,
                compAuxLib,
                ecriture.getNumeroPiece(),
                dateEcriture,
                nettoyer(libelle),
                montant(ligne.getDebit()),
                montant(ligne.getCredit()),
                ligne.getLettrage() != null ? ligne.getLettrage() : "",
                ligne.getDateLettrage() != null ? ligne.getDateLettrage().format(FORMAT_DATE) : "",
                dateValidation,
                "",
                "");

        return String.join("\t", champs);
    }

    private String montant(BigDecimal valeur) {
        return valeur.setScale(2, java.math.RoundingMode.HALF_UP).toString();
    }

    private String nettoyer(String texte) {
        if (texte == null) {
            return "";
        }
        return texte.replace('\t', ' ').replace('\n', ' ');
    }

    private String identifiantEntreprise(Entreprise entreprise) {
        String numero = entreprise.getNumeroContribuable();
        if (numero == null || numero.isBlank()) {
            return "ENTREPRISE";
        }
        return numero.replaceAll("[^A-Za-z0-9]", "").toUpperCase(Locale.ROOT);
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
