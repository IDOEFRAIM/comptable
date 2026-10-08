package com.example.comptable.banque;

import com.example.comptable.audit.AuditTrailService;
import com.example.comptable.comptabilite.domain.CompteComptable;
import com.example.comptable.comptabilite.domain.LigneReleve;
import com.example.comptable.comptabilite.domain.ReleveBancaire;
import com.example.comptable.comptabilite.repository.CompteComptableRepository;
import com.example.comptable.comptabilite.repository.LigneReleveRepository;
import com.example.comptable.comptabilite.repository.ReleveBancaireRepository;
import com.example.comptable.entreprise.domain.Entreprise;
import com.example.comptable.entreprise.service.EntrepriseAccessService;
import com.example.comptable.security.UtilisateurPrincipal;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.server.ResponseStatusException;

import java.io.IOException;
import java.math.BigDecimal;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * Import d'un releve bancaire CSV : aperçu (rien n'est ecrit), puis import tracable par lot, idempotent.
 *
 * <p>Une transaction deja connue (meme empreinte) n'est jamais reimportee : un releve importe deux fois ne cree aucun doublon. Une
 * transaction qui ressemble a une autre (meme compte, date, montant et sens, mais autre libelle ou reference) est importee et marquee
 * {@code POSSIBLE_DUPLICATE} : le montant seul ne suffit jamais a declarer un doublon. Aucune ligne n'est ignoree en silence.</p>
 */
@Service
public class BankStatementImporter {

    static final int REJETS_MAX_AFFICHES = 20;
    static final int REJETS_MAX_CONSERVES = 200;

    /** Parametres d'un import ; tout est facultatif sauf le compte (les dates et soldes se deduisent du fichier quand ils manquent). */
    public record DemandeImport(UUID compteId, String libelle, LocalDate dateDebut, LocalDate dateFin, BigDecimal soldeOuverture,
                                BigDecimal soldeCloture, BankCsvMapping mapping) {
    }

    /** Ce que le comptable voit AVANT de valider. */
    public record Apercu(int lignesTotal, int valides, int invalides, int doublonsExacts, int doublonsPossibles, int aImporter, String devise,
                         LocalDate debut, LocalDate fin, BigDecimal totalCredits, BigDecimal totalDebits, BigDecimal soldeOuverture,
                         BigDecimal soldeCloture, Boolean soldesCoherents, BigDecimal ecartSolde, boolean dejaImporteIntegralement,
                         List<BankCsvParser.Rejet> rejets, BankCsvMapping mapping) {
    }

    /** {@code releve} est null quand le fichier n'apporte rien de nouveau. */
    public record ImportEffectue(BankImportBatch lot, ReleveBancaire releve, Apercu apercu) {
    }

    private record LigneAnalysee(BankCsvParser.LigneParsee ligne, String libelleNormalise, BankLabelNormalizer.Indices indices,
                                 String empreinte, String statut) {
    }

    private record Analyse(byte[] contenu, String hash, BankCsvParser.Resultat lecture, List<LigneAnalysee> lignes, Apercu apercu) {
    }

    private final EntrepriseAccessService entrepriseAccessService;
    private final CompteComptableRepository compteComptableRepository;
    private final LigneReleveRepository ligneReleveRepository;
    private final ReleveBancaireRepository releveBancaireRepository;
    private final BankImportBatchRepository batchRepository;
    private final AuditTrailService auditTrailService;

    public BankStatementImporter(EntrepriseAccessService entrepriseAccessService, CompteComptableRepository compteComptableRepository,
                                 LigneReleveRepository ligneReleveRepository, ReleveBancaireRepository releveBancaireRepository,
                                 BankImportBatchRepository batchRepository, AuditTrailService auditTrailService) {
        this.entrepriseAccessService = entrepriseAccessService;
        this.compteComptableRepository = compteComptableRepository;
        this.ligneReleveRepository = ligneReleveRepository;
        this.releveBancaireRepository = releveBancaireRepository;
        this.batchRepository = batchRepository;
        this.auditTrailService = auditTrailService;
    }

    @Transactional(readOnly = true)
    public Apercu apercu(UUID entrepriseId, DemandeImport demande, MultipartFile fichier, UtilisateurPrincipal principal) {
        Entreprise entreprise = entrepriseAccessService.verifierAcces(entrepriseId, principal);
        CompteComptable compte = chargerCompte(entrepriseId, demande.compteId());
        return analyser(entreprise, compte, demande, fichier).apercu();
    }

    @Transactional
    public ImportEffectue importer(UUID entrepriseId, DemandeImport demande, MultipartFile fichier, UtilisateurPrincipal principal) {
        Entreprise entreprise = entrepriseAccessService.verifierAcces(entrepriseId, principal);
        CompteComptable compte = chargerCompte(entrepriseId, demande.compteId());
        Analyse analyse = analyser(entreprise, compte, demande, fichier);
        Apercu a = analyse.apercu();
        if (a.valides() == 0) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Le fichier ne contient aucune ligne exploitable");
        }

        List<LigneAnalysee> nouvelles = analyse.lignes().stream().filter(l -> !"EXACT_DUPLICATE".equals(l.statut())).toList();
        BankImportBatch lot = new BankImportBatch();
        lot.setEntrepriseId(entrepriseId);
        lot.setCompteId(compte.getId());
        lot.setFileName(fichier.getOriginalFilename());
        lot.setFileHash(analyse.hash());
        lot.setImportedBy(principal.getId());
        lot.setTotalRows(a.lignesTotal());
        lot.setValidRows(a.valides());
        lot.setRejectedRows(a.invalides());
        lot.setExactDuplicates(a.doublonsExacts());
        lot.setPossibleDuplicates(a.doublonsPossibles());
        lot.setImportedRows(nouvelles.size());
        lot.setCurrency(a.devise());
        lot.setDateMin(a.debut());
        lot.setDateMax(a.fin());
        lot.setStatus(nouvelles.isEmpty() ? BankImportBatch.NOTHING_NEW : BankImportBatch.IMPORTED);
        lot.setRejections(analyse.lecture().rejets().stream().limit(REJETS_MAX_CONSERVES)
                .map(r -> Map.<String, Object>of("ligne", r.numeroLigne(), "raison", r.raison())).toList());
        lot = batchRepository.save(lot);

        ReleveBancaire releve = null;
        if (!nouvelles.isEmpty()) {
            releve = creerReleve(entreprise, compte, demande, fichier, a, nouvelles, lot);
            try {
                releve = releveBancaireRepository.saveAndFlush(releve);
            } catch (DataIntegrityViolationException e) {
                // Deux imports simultanes du meme fichier : l'unicite de l'empreinte protege, le second est refuse proprement.
                throw new ResponseStatusException(HttpStatus.CONFLICT, "Ce releve est deja en cours d'import");
            }
        }

        UUID tenantId = entreprise.getTenant() != null ? entreprise.getTenant().getId() : null;
        Map<String, Object> details = new LinkedHashMap<>();
        details.put("lotId", lot.getId().toString());
        details.put("compteId", compte.getId().toString());
        details.put("lignesLues", a.lignesTotal());
        details.put("importees", nouvelles.size());
        details.put("rejetees", a.invalides());
        details.put("doublonsExacts", a.doublonsExacts());
        details.put("doublonsPossibles", a.doublonsPossibles());
        details.put("fichier", fichier.getOriginalFilename() != null ? fichier.getOriginalFilename() : "");
        auditTrailService.enregistrerActionHumaine(tenantId, principal.getId(), "BANK_STATEMENT_IMPORTED", "BANK_IMPORT_BATCH",
                lot.getId().toString(), details);
        return new ImportEffectue(lot, releve, a);
    }

    // ------------------------------------------------------------------------------------------------------ analyse

    private Analyse analyser(Entreprise entreprise, CompteComptable compte, DemandeImport demande, MultipartFile fichier) {
        if (fichier == null || fichier.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Fichier manquant");
        }
        byte[] contenu;
        BankCsvParser.Resultat lecture;
        try {
            contenu = fichier.getBytes();
            lecture = BankCsvParser.lire(contenu, demande.mapping());
        } catch (IOException e) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Fichier illisible");
        }
        if (demande.mapping() != null && !demande.mapping().aUnMontant()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Le mapping doit designer une colonne montant, ou debit/credit");
        }

        // Empreintes : le rang d'occurrence rend deux lignes identiques d'un meme fichier distinctes, de facon stable d'un import a l'autre.
        Map<String, Integer> occurrences = new HashMap<>();
        List<LigneAnalysee> lignes = new ArrayList<>();
        for (BankCsvParser.LigneParsee l : lecture.valides()) {
            String normalise = BankLabelNormalizer.normaliser(l.libelle());
            String cle = l.date() + "|" + l.montant().abs().setScale(2) + "|" + (l.montant().signum() >= 0) + "|" + l.reference() + "|" + normalise;
            int rang = occurrences.merge(cle, 1, Integer::sum);
            String empreinte = BankFingerprint.calculer(compte.getId(), l.date(), l.montant(), l.reference(), normalise, rang);
            lignes.add(new LigneAnalysee(l, normalise, BankLabelNormalizer.extraire(l.libelle()), empreinte, "NEW"));
        }

        Set<String> connues = lignes.isEmpty() ? Set.of() : new HashSet<>(
                ligneReleveRepository.empreintesConnues(entreprise.getId(), compte.getId(), lignes.stream().map(LigneAnalysee::empreinte).toList()));
        LocalDate debut = lignes.stream().map(l -> l.ligne().date()).min(Comparator.naturalOrder()).orElse(null);
        LocalDate fin = lignes.stream().map(l -> l.ligne().date()).max(Comparator.naturalOrder()).orElse(null);
        List<LigneReleve> existantes = debut == null ? List.of()
                : ligneReleveRepository.dejaImporteesSurPeriode(entreprise.getId(), compte.getId(), debut, fin);
        Set<String> clesExistantes = existantes.stream()
                .map(e -> e.getDateOperation() + "|" + e.getMontant().setScale(2))
                .collect(Collectors.toSet());

        List<LigneAnalysee> classees = new ArrayList<>();
        int exacts = 0;
        int possibles = 0;
        for (LigneAnalysee l : lignes) {
            String statut = "NEW";
            if (connues.contains(l.empreinte())) {
                statut = "EXACT_DUPLICATE";
                exacts++;
            } else if (clesExistantes.contains(l.ligne().date() + "|" + l.ligne().montant().setScale(2))) {
                // Meme jour, meme montant, meme sens qu'une transaction deja connue, mais pas la meme : a regarder, jamais rejete d'office.
                statut = "POSSIBLE_DUPLICATE";
                possibles++;
            }
            classees.add(new LigneAnalysee(l.ligne(), l.libelleNormalise(), l.indices(), l.empreinte(), statut));
        }

        BigDecimal credits = lignes.stream().map(l -> l.ligne().montant()).filter(m -> m.signum() > 0).reduce(BigDecimal.ZERO, BigDecimal::add);
        BigDecimal debits = lignes.stream().map(l -> l.ligne().montant()).filter(m -> m.signum() < 0).reduce(BigDecimal.ZERO, BigDecimal::add).abs();
        BigDecimal ouverture = demande.soldeOuverture();
        BigDecimal cloture = demande.soldeCloture();
        if (!lecture.valides().isEmpty() && lecture.mapping().solde() != null) {
            BankCsvParser.LigneParsee premiere = lecture.valides().get(0);
            BankCsvParser.LigneParsee derniere = lecture.valides().get(lecture.valides().size() - 1);
            if (ouverture == null && premiere.solde() != null) {
                ouverture = premiere.solde().subtract(premiere.montant());
            }
            if (cloture == null && derniere.solde() != null) {
                cloture = derniere.solde();
            }
        }
        Boolean coherents = null;
        BigDecimal ecart = null;
        if (ouverture != null && cloture != null) {
            ecart = ouverture.add(credits).subtract(debits).subtract(cloture);
            coherents = ecart.signum() == 0;
        }
        int aImporter = (int) classees.stream().filter(l -> !"EXACT_DUPLICATE".equals(l.statut())).count();
        List<BankCsvParser.Rejet> rejets = lecture.rejets().stream().limit(REJETS_MAX_AFFICHES).toList();
        Apercu apercu = new Apercu(lecture.totalLignes(), lecture.valides().size(), lecture.rejets().size(), exacts, possibles, aImporter,
                entreprise.getDevise(), debut, fin, credits, debits, ouverture, cloture, coherents, ecart,
                !lecture.valides().isEmpty() && aImporter == 0, rejets, lecture.mapping());
        return new Analyse(contenu, hash(contenu), lecture, classees, apercu);
    }

    private ReleveBancaire creerReleve(Entreprise entreprise, CompteComptable compte, DemandeImport demande, MultipartFile fichier, Apercu a,
                                       List<LigneAnalysee> nouvelles, BankImportBatch lot) {
        ReleveBancaire releve = new ReleveBancaire();
        releve.setEntreprise(entreprise);
        releve.setCompte(compte);
        String nom = fichier.getOriginalFilename();
        releve.setLibelle(demande.libelle() != null && !demande.libelle().isBlank() ? demande.libelle() : (nom != null ? nom : "Releve importe"));
        releve.setDateDebut(demande.dateDebut() != null ? demande.dateDebut() : a.debut());
        releve.setDateFin(demande.dateFin() != null ? demande.dateFin() : a.fin());
        releve.setSoldeDebut(a.soldeOuverture());
        releve.setSoldeFin(a.soldeCloture());
        releve.setImportBatchId(lot.getId());
        for (LigneAnalysee l : nouvelles) {
            LigneReleve ligne = new LigneReleve(l.ligne().date(), l.ligne().libelle(), l.ligne().montant());
            ligne.setDateValeur(l.ligne().dateValeur());
            ligne.setReference(l.ligne().reference());
            ligne.setLibelleNormalise(l.libelleNormalise());
            ligne.setCounterpartyName(l.indices().nomTiers());
            ligne.setPaymentMethod(l.indices().moyenPaiement());
            ligne.setInvoiceHint(l.indices().numeroFacture());
            ligne.setImportBatchId(lot.getId());
            ligne.setContentFingerprint(l.empreinte());
            ligne.setDuplicateStatus("POSSIBLE_DUPLICATE".equals(l.statut()) ? "POSSIBLE_DUPLICATE" : "NONE");
            releve.ajouterLigne(ligne);
        }
        return releve;
    }

    private CompteComptable chargerCompte(UUID entrepriseId, UUID compteId) {
        return compteComptableRepository.findById(compteId)
                .filter(c -> c.getEntreprise().getId().equals(entrepriseId))
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Compte introuvable"));
    }

    private static String hash(byte[] contenu) {
        try {
            return java.util.HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(contenu));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
