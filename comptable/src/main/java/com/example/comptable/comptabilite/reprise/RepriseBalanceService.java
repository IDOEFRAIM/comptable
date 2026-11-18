package com.example.comptable.comptabilite.reprise;

import com.example.comptable.audit.AuditTrailService;
import com.example.comptable.comptabilite.domain.CompteComptable;
import com.example.comptable.comptabilite.domain.EcritureComptable;
import com.example.comptable.comptabilite.domain.ExerciceComptable;
import com.example.comptable.comptabilite.domain.Journal;
import com.example.comptable.comptabilite.domain.NatureEcriture;
import com.example.comptable.comptabilite.domain.StatutExercice;
import com.example.comptable.comptabilite.domain.Tiers;
import com.example.comptable.comptabilite.domain.TypeTiers;
import com.example.comptable.comptabilite.repository.CompteComptableRepository;
import com.example.comptable.comptabilite.repository.EcritureComptableRepository;
import com.example.comptable.comptabilite.repository.ExerciceComptableRepository;
import com.example.comptable.comptabilite.repository.JournalRepository;
import com.example.comptable.comptabilite.repository.TiersRepository;
import com.example.comptable.comptabilite.reprise.BalanceOuvertureParser.LigneBrute;
import com.example.comptable.comptabilite.reprise.RepriseDtos.ApercuReprise;
import com.example.comptable.comptabilite.reprise.RepriseDtos.LigneApercu;
import com.example.comptable.comptabilite.reprise.RepriseDtos.ResultatReprise;
import com.example.comptable.comptabilite.service.CompteComptableValidationService;
import com.example.comptable.comptabilite.service.EcritureComptableService;
import com.example.comptable.comptabilite.service.TiersService;
import com.example.comptable.comptabilite.web.dto.CreerEcritureRequest;
import com.example.comptable.comptabilite.web.dto.EcritureComptableDto;
import com.example.comptable.comptabilite.web.dto.LigneEcritureRequest;
import com.example.comptable.entreprise.domain.Entreprise;
import com.example.comptable.entreprise.service.EntrepriseAccessService;
import com.example.comptable.security.UtilisateurPrincipal;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.math.BigDecimal;
import java.text.Normalizer;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Reprise d'une balance d'ouverture : un cabinet qui change d'outil apporte la balance de son ancien logiciel (Excel ou CSV) au lieu de tout ressaisir.
 * <p>
 * Deux temps, sans état entre les deux : un <b>aperçu</b> (rien n'est écrit : chaque ligne est expliquée, les erreurs sont dites) puis l'<b>import</b>, qui relit le fichier et crée UNE écriture d'à-nouveaux
 * en <b>brouillon</b>, datée du premier jour de l'exercice, que le comptable relit puis valide comme n'importe quelle écriture.
 * <p>
 * Garde-fous : seuls les comptes de bilan (classes 1 à 5) sont repris, car l'écriture est de nature OPENING, exclue de l'activité de la période ; la balance doit être équilibrée (aucun compte « d'attente »
 * n'est inventé pour absorber un écart) ; une seule reprise par exercice ; un compte collectif client ou fournisseur exige le nom du tiers, créé s'il n'existe pas.
 */
@Service
@RequiredArgsConstructor
public class RepriseBalanceService {

    private final EntrepriseAccessService entrepriseAccessService;
    private final CompteComptableRepository compteRepository;
    private final CompteComptableValidationService compteValidation;
    private final TiersRepository tiersRepository;
    private final TiersService tiersService;
    private final ExerciceComptableRepository exerciceRepository;
    private final JournalRepository journalRepository;
    private final EcritureComptableService ecritureService;
    private final EcritureComptableRepository ecritureRepository;
    private final AuditTrailService audit;
    private final JdbcTemplate jdbc;

    // ------------------------------------------------------------------------------------------------------------------------------------------------------------ aperçu

    @Transactional(readOnly = true)
    public ApercuReprise apercu(UUID entrepriseId, byte[] fichier, String nomFichier, UUID exerciceId, UtilisateurPrincipal principal) {
        Entreprise entreprise = entrepriseAccessService.verifierAcces(entrepriseId, principal);
        exigerCabinet(principal);
        return analyser(entreprise, fichier, nomFichier, exerciceId).apercu();
    }

    // ------------------------------------------------------------------------------------------------------------------------------------------------------------ import

    @Transactional
    public ResultatReprise importer(UUID entrepriseId, byte[] fichier, String nomFichier, UUID exerciceId, UtilisateurPrincipal principal) {
        Entreprise entreprise = entrepriseAccessService.verifierAcces(entrepriseId, principal);
        exigerCabinet(principal);
        Analyse a = analyser(entreprise, fichier, nomFichier, exerciceId);
        if (!a.apercu().importable()) {
            throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY, a.apercu().blocage() != null ? a.apercu().blocage() : "La balance n'est pas importable : corrigez les erreurs signalées.");
        }

        int tiersCrees = 0;
        Map<String, UUID[]> comptesTiers = new HashMap<>(); // clé « TYPE|nom normalisé » → {compteId, tiersId}, pour ne créer un tiers qu'une fois
        List<LigneEcritureRequest> lignes = new ArrayList<>();
        for (Resolue r : a.resolues()) {
            UUID compteId = r.compteId();
            UUID tiersId = r.tiersId();
            if (r.tiersACreer() != null) {
                String cle = r.tiersACreer().name() + "|" + normaliser(r.tiersNom());
                UUID[] deja = comptesTiers.get(cle);
                if (deja == null) {
                    var cree = tiersService.creer(entrepriseId, r.tiersACreer(), r.tiersNom().trim(), null, principal);
                    deja = new UUID[] {cree.compteComptableId(), cree.id()};
                    comptesTiers.put(cle, deja);
                    tiersCrees++;
                }
                compteId = deja[0];
                tiersId = deja[1];
            }
            lignes.add(new LigneEcritureRequest(compteId, r.libelle(), r.debit(), r.credit(), tiersId));
        }

        Journal journal = journalOuverture(entrepriseId);
        LocalDate date = a.exercice().getDateDebut();
        EcritureComptableDto dto = ecritureService.creerManuelle(entrepriseId,
                new CreerEcritureRequest(journal.getId(), date, "Balance d'ouverture (reprise)", "AN-" + date.getYear(), lignes), principal);
        EcritureComptable ecriture = ecritureRepository.findById(dto.id()).orElseThrow();
        ecriture.setNature(NatureEcriture.OPENING);
        ecritureRepository.save(ecriture);
        audit.enregistrerActionHumaine(principal.getTenantId(), principal.getId(), "REPRISE_BALANCE_IMPORTEE", "EcritureComptable", dto.id().toString(),
                Map.of("lignes", lignes.size(), "tiersCrees", tiersCrees, "totalDebit", a.apercu().totalDebit().toPlainString(), "fichier", nomFichier == null ? "" : nomFichier));
        return new ResultatReprise(dto, lignes.size(), tiersCrees);
    }

    // ------------------------------------------------------------------------------------------------------------------------------------------------------------ analyse

    private record Resolue(UUID compteId, UUID tiersId, TypeTiers tiersACreer, String tiersNom, String libelle, BigDecimal debit, BigDecimal credit) {
    }

    private record Analyse(ApercuReprise apercu, ExerciceComptable exercice, List<Resolue> resolues) {
    }

    private Analyse analyser(Entreprise entreprise, byte[] fichier, String nomFichier, UUID exerciceId) {
        UUID entrepriseId = entreprise.getId();
        ExerciceComptable exercice = choisirExercice(entrepriseId, exerciceId);
        var lecture = BalanceOuvertureParser.lire(fichier, nomFichier);

        Map<String, CompteComptable> plan = new HashMap<>();
        for (CompteComptable c : compteRepository.findByEntrepriseId(entrepriseId)) {
            plan.put(c.getNumero(), c);
        }
        Map<String, Tiers> tiersParCle = new HashMap<>();
        for (Tiers t : tiersRepository.findByEntrepriseId(entrepriseId)) {
            tiersParCle.put(t.getType().name() + "|" + normaliser(t.getNom()), t);
        }

        List<LigneApercu> apercus = new ArrayList<>();
        List<Resolue> resolues = new ArrayList<>();
        List<String> avertissements = new ArrayList<>(lecture.ignorees());
        BigDecimal totalDebit = BigDecimal.ZERO;
        BigDecimal totalCredit = BigDecimal.ZERO;
        long erreurs = 0;
        java.util.Set<String> aCreer = new java.util.HashSet<>();

        for (LigneBrute l : lecture.lignes()) {
            if (l.erreur() != null) {
                apercus.add(new LigneApercu(l.ligne(), l.compte(), l.libelle(), l.tiers(), null, null, "ERREUR", l.erreur(), null));
                erreurs++;
                continue;
            }
            if (l.debit().signum() > 0 && l.credit().signum() > 0) {
                apercus.add(erreur(l, "Débit et crédit renseignés sur la même ligne : séparez-les en deux lignes ou ne gardez que le solde."));
                erreurs++;
                continue;
            }
            if (l.debit().signum() == 0 && l.credit().signum() == 0) {
                avertissements.add("Ligne " + l.ligne() + " (compte " + l.compte() + ") ignorée : montant nul.");
                continue;
            }
            char classe = l.compte().charAt(0);
            if (classe < '1' || classe > '5') {
                apercus.add(erreur(l, "Seuls les comptes de bilan (classes 1 à 5) sont repris. Les comptes de gestion (classes 6 à 8) se saisissent par les écritures de l'exercice."));
                erreurs++;
                continue;
            }

            String libelle = l.libelle() == null || l.libelle().isBlank() ? "Reprise " + l.compte() : l.libelle();
            CompteComptable compte = plan.get(l.compte());
            if (compte != null && !compte.isActif()) {
                apercus.add(erreur(l, "Le compte " + l.compte() + " est désactivé dans le plan comptable du dossier."));
                erreurs++;
                continue;
            }
            TypeTiers type = typeTiers(l.compte());
            if (type != null) {
                // Clients (411…) et fournisseurs (401…) : toujours passés sur le compte d'un tiers, jamais sur le compte collectif. Sans tiers, la balance âgée, le lettrage et le recouvrement ne retrouveraient
                // pas ces soldes plus tard.
                Tiers lie = compte == null ? null : tiersRepository.findFirstByEntrepriseIdAndCompteComptableId(entrepriseId, compte.getId()).orElse(null);
                if (lie != null) {
                    resolues.add(new Resolue(compte.getId(), lie.getId(), null, null, libelle, l.debit(), l.credit()));
                    apercus.add(new LigneApercu(l.ligne(), l.compte(), libelle, lie.getNom(), l.debit(), l.credit(), "OK", null, compte.getNumero() + " " + compte.getIntitule()));
                    totalDebit = totalDebit.add(l.debit());
                    totalCredit = totalCredit.add(l.credit());
                    continue;
                }
                String nomTiers = !l.tiers().isBlank() ? l.tiers() : (l.compte().length() > 3 ? l.libelle() : "");
                if (nomTiers.isBlank()) {
                    String qui = type == TypeTiers.CLIENT ? "client" : "fournisseur";
                    apercus.add(erreur(l, "Le compte " + l.compte() + " regroupe les " + qui + "s : indiquez le nom du " + qui + " dans la colonne « Tiers » (une ligne par " + qui
                            + "). Pour un solde global sans détail, mettez un nom comme « " + (type == TypeTiers.CLIENT ? "Clients divers" : "Fournisseurs divers") + " (reprise) »."));
                    erreurs++;
                    continue;
                }
                totalDebit = totalDebit.add(l.debit());
                totalCredit = totalCredit.add(l.credit());
                ajouterTiers(l, libelle, type, nomTiers, tiersParCle, aCreer, apercus, resolues);
                continue;
            }
            if (compte == null) {
                apercus.add(erreur(l, "Le compte " + l.compte() + " n'existe pas dans le plan comptable du dossier. Ajoutez-le dans « Plan comptable », puis relancez l'aperçu."));
                erreurs++;
                continue;
            }
            if (!compteValidation.isUsableCommeDetail(compte)) {
                apercus.add(erreur(l, "Le compte " + l.compte() + " est un compte de regroupement : indiquez un compte de détail (sous-compte)."));
                erreurs++;
                continue;
            }
            Tiers lie = tiersRepository.findFirstByEntrepriseIdAndCompteComptableId(entrepriseId, compte.getId()).orElse(null);
            resolues.add(new Resolue(compte.getId(), lie == null ? null : lie.getId(), null, null, libelle, l.debit(), l.credit()));
            apercus.add(new LigneApercu(l.ligne(), l.compte(), libelle, lie == null ? l.tiers() : lie.getNom(), l.debit(), l.credit(), "OK", null, compte.getNumero() + " " + compte.getIntitule()));
            totalDebit = totalDebit.add(l.debit());
            totalCredit = totalCredit.add(l.credit());
        }

        BigDecimal ecart = totalDebit.subtract(totalCredit);
        String blocage = null;
        String existante = ouvertureExistante(entrepriseId, exercice.getId());
        if (existante != null) {
            blocage = "Une balance d'ouverture existe déjà pour cet exercice (écriture " + existante + "). Supprimez-la si elle est encore en brouillon avant d'en importer une autre.";
        } else if (erreurs > 0) {
            blocage = erreurs + " ligne(s) en erreur : corrigez le fichier ou le plan comptable, puis relancez l'aperçu.";
        } else if (resolues.isEmpty()) {
            blocage = "Aucune ligne à importer.";
        } else if (ecart.signum() != 0) {
            blocage = "La balance n'est pas équilibrée : total débit " + totalDebit.toPlainString() + ", total crédit " + totalCredit.toPlainString() + " (écart " + ecart.abs().toPlainString() + "). "
                    + "Corrigez le fichier : aucun compte d'attente n'est inventé pour masquer un écart.";
        }
        if (exercice.getStatut() != StatutExercice.OUVERT) {
            blocage = "L'exercice choisi n'est pas ouvert.";
        }
        ApercuReprise apercu = new ApercuReprise(exercice.getId(), exercice.getDateDebut(), apercus, totalDebit, totalCredit, ecart, erreurs, aCreer.size(), blocage == null, blocage, avertissements);
        return new Analyse(apercu, exercice, resolues);
    }

    private void ajouterTiers(LigneBrute l, String libelle, TypeTiers type, String nomTiers, Map<String, Tiers> existants, java.util.Set<String> aCreer, List<LigneApercu> apercus, List<Resolue> resolues) {
        Tiers existant = existants.get(type.name() + "|" + normaliser(nomTiers));
        if (existant != null) {
            resolues.add(new Resolue(existant.getCompteComptable().getId(), existant.getId(), null, null, libelle, l.debit(), l.credit()));
            apercus.add(new LigneApercu(l.ligne(), l.compte(), libelle, existant.getNom(), l.debit(), l.credit(), "OK", null, existant.getCompteComptable().getNumero() + " " + existant.getNom()));
        } else {
            aCreer.add(type.name() + "|" + normaliser(nomTiers));
            resolues.add(new Resolue(null, null, type, nomTiers, libelle, l.debit(), l.credit()));
            apercus.add(new LigneApercu(l.ligne(), l.compte(), libelle, nomTiers.trim(), l.debit(), l.credit(), "TIERS_A_CREER",
                    (type == TypeTiers.CLIENT ? "Client" : "Fournisseur") + " « " + nomTiers.trim() + " » inconnu : il sera créé avec son compte.", null));
        }
    }

    private static LigneApercu erreur(LigneBrute l, String message) {
        return new LigneApercu(l.ligne(), l.compte(), l.libelle(), l.tiers(), l.debit(), l.credit(), "ERREUR", message, null);
    }

    /** 411… = client, 401… = fournisseur ; tout le reste n'est pas un tiers reconnu. */
    private static TypeTiers typeTiers(String compte) {
        if (compte.startsWith("411")) {
            return TypeTiers.CLIENT;
        }
        if (compte.startsWith("401")) {
            return TypeTiers.FOURNISSEUR;
        }
        return null;
    }

    private ExerciceComptable choisirExercice(UUID entrepriseId, UUID exerciceId) {
        List<ExerciceComptable> exercices = exerciceRepository.findByEntrepriseId(entrepriseId);
        if (exerciceId != null) {
            return exercices.stream().filter((e) -> e.getId().equals(exerciceId)).findFirst().orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Exercice introuvable"));
        }
        return exercices.stream().filter((e) -> e.getStatut() == StatutExercice.OUVERT).min((a, b) -> a.getDateDebut().compareTo(b.getDateDebut()))
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY, "Aucun exercice ouvert : créez ou rouvrez un exercice avant d'importer une balance d'ouverture."));
    }

    private Journal journalOuverture(UUID entrepriseId) {
        return journalRepository.findByEntrepriseIdAndCode(entrepriseId, "OD").or(() -> journalRepository.findByEntrepriseId(entrepriseId).stream().findFirst())
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY, "Aucun journal disponible pour passer l'écriture d'ouverture."));
    }

    private String ouvertureExistante(UUID entrepriseId, UUID exerciceId) {
        List<String> numeros = jdbc.queryForList("select numero_piece from ecriture_comptable where entreprise_id = ? and exercice_id = ? and nature = 'OPENING' limit 1", String.class, entrepriseId, exerciceId);
        return numeros.isEmpty() ? null : numeros.get(0);
    }

    private static void exigerCabinet(UtilisateurPrincipal principal) {
        if (principal.getAuthorities().stream().anyMatch((a) -> a.getAuthority().equals("ROLE_CLIENT"))) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Seul le cabinet peut importer une balance d'ouverture");
        }
    }

    static String normaliser(String s) {
        return Normalizer.normalize(s == null ? "" : s, Normalizer.Form.NFD).replaceAll("\\p{M}", "").toLowerCase().replaceAll("\\s+", " ").trim();
    }
}
