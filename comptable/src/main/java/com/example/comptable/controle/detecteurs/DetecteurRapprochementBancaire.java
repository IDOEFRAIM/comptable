package com.example.comptable.controle.detecteurs;

import com.example.comptable.agent.finding.domain.Finding;
import com.example.comptable.agent.finding.repository.FindingRepository;
import com.example.comptable.comptabilite.domain.LigneEcriture;
import com.example.comptable.comptabilite.domain.LigneReleve;
import com.example.comptable.comptabilite.domain.ReleveBancaire;
import com.example.comptable.comptabilite.service.RapprochementAutomatiqueService;
import com.example.comptable.comptabilite.service.RapprochementAutomatiqueService.LigneClassee;
import com.example.comptable.comptabilite.service.RapprochementClassifier;
import com.example.comptable.comptabilite.service.RapprochementScorer.ClassementNote;
import com.example.comptable.controle.ControlCategory;
import com.example.comptable.controle.ControlCodes;
import com.example.comptable.controle.ControlDetector;
import com.example.comptable.controle.ControlResult;
import com.example.comptable.controle.ControlResultAdapters;
import com.example.comptable.controle.ControlSeverity;
import com.example.comptable.entreprise.domain.Entreprise;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Rapprochement Banque &lt;-&gt; Ecritures (Phase B, lot B4). Les lignes de releve non pointees sont
 * classees de facon deterministe ({@code RapprochementClassifier}) :
 * PROBABLE -&gt; {@code MATCH_TO_CONFIRM}, AMBIGUOUS -&gt; {@code AMBIGUOUS_BANK_MATCH} (jamais le premier
 * candidat), UNMATCHED -&gt; {@code UNMATCHED_PAYMENT}. Les EXACTS sont pointes avant la detection
 * ({@code ControlRunService}) et n'apparaissent donc jamais ici.
 *
 * <p>Un releve qui aurait plus de {@link #SEUIL_AGREGATION} lignes sans aucune correspondance est
 * resume en UN constat par releve (jamais 1500 Findings pour 1500 operations).</p>
 */
@Component
public class DetecteurRapprochementBancaire implements ControlDetector {

    static final int SEUIL_AGREGATION = 50;
    private static final int MAX_IDS_LIES = 50;

    private final RapprochementAutomatiqueService rapprochementService;
    private final FindingRepository findingRepository;
    private final com.example.comptable.comptabilite.repository.LigneReleveRepository ligneReleveRepository;

    public DetecteurRapprochementBancaire(RapprochementAutomatiqueService rapprochementService, FindingRepository findingRepository,
                                          com.example.comptable.comptabilite.repository.LigneReleveRepository ligneReleveRepository) {
        this.ligneReleveRepository = ligneReleveRepository;
        this.rapprochementService = rapprochementService;
        this.findingRepository = findingRepository;
    }

    @Override
    public String nom() {
        return "rapprochement-bancaire";
    }

    @Override
    public Set<String> codes() {
        return Set.of(ControlCodes.UNMATCHED_PAYMENT, ControlCodes.AMBIGUOUS_BANK_MATCH, ControlCodes.MATCH_TO_CONFIRM, ControlCodes.BANK_DUPLICATE_TRANSACTION);
    }

    @Override
    public List<ControlResult> detecter(Entreprise entreprise) {
        List<LigneClassee> lignes = rapprochementService.classerNonPointees(entreprise.getId());
        Map<String, Finding> proposesParIa = proposesParIa(entreprise);

        List<ControlResult> resultats = new ArrayList<>();
        Map<ReleveBancaire, List<LigneClassee>> aConfirmerParReleve = new LinkedHashMap<>();
        Map<ReleveBancaire, List<LigneClassee>> ambiguesParReleve = new LinkedHashMap<>();
        Map<ReleveBancaire, List<ClassementNote>> sansCorrespondance = new LinkedHashMap<>();
        for (LigneClassee classee : lignes) {
            ClassementNote c = classee.classement();
            String ligneId = c.ligne().getId().toString();
            if (proposesParIa.containsKey(ligneId)) {
                // Une proposition de l'IA en attente de decision humaine reste a l'Inbox (avis, jamais verite comptable).
                resultats.add(ControlResultAdapters.depuisFinding(proposesParIa.get(ligneId)));
                continue;
            }
            switch (c.statut()) {
                // Un EXACT restant (releve concurrent) reste a confirmer, comme un PROBABLE.
                case PROBABLE, EXACT -> aConfirmerParReleve.computeIfAbsent(classee.releve(), r -> new ArrayList<>()).add(classee);
                case AMBIGUOUS -> ambiguesParReleve.computeIfAbsent(classee.releve(), r -> new ArrayList<>()).add(classee);
                case UNMATCHED -> sansCorrespondance.computeIfAbsent(classee.releve(), r -> new ArrayList<>()).add(c);
            }
        }
        // Au-dela du seuil, UN constat resume le releve : l'Inbox ne recoit que ce qui demande une decision, pas 300 lignes identiques.
        aConfirmerParReleve.forEach((releve, liste) -> {
            if (liste.size() > SEUIL_AGREGATION) {
                resultats.add(paquetSimple(entreprise, releve, liste.stream().map(l -> l.classement().ligne()).toList(), ControlCodes.MATCH_TO_CONFIRM,
                        ControlSeverity.INFO, " propositions de rapprochement a confirmer",
                        " transactions du releve ont une correspondance probable. Un seul constat resume le releve ; la validation se fait en lot depuis "
                                + "l'ecran Banque, apres apercu.",
                        "Ouvrir l'ecran Banque, filtrer sur « a confirmer », valider les correspondances fortes par lot et traiter les autres une a une."));
            } else {
                liste.forEach(l -> resultats.add(aConfirmer(entreprise, l)));
            }
        });
        ambiguesParReleve.forEach((releve, liste) -> {
            if (liste.size() > SEUIL_AGREGATION) {
                resultats.add(paquetSimple(entreprise, releve, liste.stream().map(l -> l.classement().ligne()).toList(), ControlCodes.AMBIGUOUS_BANK_MATCH,
                        ControlSeverity.WARNING, " transactions ambigues",
                        " transactions du releve peuvent correspondre a plusieurs mouvements : le systeme ne choisit jamais le premier. Un seul constat "
                                + "resume le releve.",
                        "Ouvrir l'ecran Banque, filtrer sur « ambigues » et choisir pour chaque transaction le mouvement qui correspond."));
            } else {
                liste.forEach(l -> resultats.add(ambigu(entreprise, l)));
            }
        });
        sansCorrespondance.forEach((releve, classements) -> {
            if (classements.size() > SEUIL_AGREGATION) {
                resultats.add(paquet(entreprise, releve, classements));
            } else {
                classements.forEach(c -> resultats.add(sansPaiement(entreprise, releve, c)));
            }
        });
        resultats.addAll(doublonsPossibles(entreprise));
        return resultats;
    }

    /** Transactions importees qui ressemblent a une autre sans etre la meme et qui ne sont toujours pas rapprochees : UN constat par releve. */
    private List<ControlResult> doublonsPossibles(Entreprise entreprise) {
        Map<ReleveBancaire, List<LigneReleve>> parReleve = new LinkedHashMap<>();
        for (LigneReleve l : ligneReleveRepository.doublonsPossiblesNonRapproches(entreprise.getId())) {
            parReleve.computeIfAbsent(l.getReleve(), r -> new ArrayList<>()).add(l);
        }
        List<ControlResult> resultats = new ArrayList<>();
        parReleve.forEach((releve, lignes) -> {
            Map<String, Object> metadata = new LinkedHashMap<>();
            metadata.put("releveId", releve.getId().toString());
            metadata.put("compte", releve.getCompte().getNumero());
            metadata.put("nbLignes", lignes.size());
            resultats.add(ControlResult.ouvert(ControlCodes.BANK_DUPLICATE_TRANSACTION, ControlCategory.RECONCILIATION, ControlSeverity.INFO,
                    entreprise.getId(), "RELEVE", releve.getId().toString(),
                    lignes.size() + " transaction(s) peut-etre en double sur le releve " + releve.getLibelle(),
                    lignes.size() + " transaction(s) ont le meme compte, le meme jour, le meme montant et le meme sens qu'une autre, mais pas le meme libelle : "
                            + "ce sont peut-etre deux operations distinctes. Le montant seul ne suffit jamais a declarer un doublon.",
                    lignes.stream().limit(MAX_IDS_LIES).map(l -> l.getId().toString()).toList(), metadata,
                    "Confirmer que ce sont deux operations distinctes, ou ecarter la transaction en double."));
        });
        return resultats;
    }

    private ControlResult paquetSimple(Entreprise entreprise, ReleveBancaire releve, List<LigneReleve> lignes, String code, ControlSeverity gravite,
                                       String titreSuffixe, String explicationSuffixe, String action) {
        BigDecimal total = lignes.stream().map(LigneReleve::getMontant).reduce(BigDecimal.ZERO, BigDecimal::add);
        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put("releveId", releve.getId().toString());
        metadata.put("compte", releve.getCompte().getNumero());
        metadata.put("nbLignes", lignes.size());
        metadata.put("montantNet", total);
        return ControlResult.ouvert(code, ControlCategory.RECONCILIATION, gravite, entreprise.getId(), "RELEVE", releve.getId().toString(),
                lignes.size() + titreSuffixe + " sur le releve " + releve.getLibelle(),
                lignes.size() + explicationSuffixe, lignes.stream().limit(MAX_IDS_LIES).map(l -> l.getId().toString()).toList(), metadata, action);
    }

    private Map<String, Finding> proposesParIa(Entreprise entreprise) {
        return findingRepository.findActifsParCodes(entreprise.getId(), List.of(ControlCodes.MATCH_TO_CONFIRM)).stream()
                .filter(f -> "LIGNE_RELEVE".equals(f.getSourceType()))
                .filter(f -> f.getEvidenceJson() != null && f.getEvidenceJson().containsKey("aiMatchProposalId"))
                .collect(Collectors.toMap(Finding::getSourceId, f -> f, (a, b) -> a));
    }

    private ControlResult aConfirmer(Entreprise entreprise, LigneClassee classee) {
        ClassementNote c = classee.classement();
        LigneEcriture candidat = c.candidats().get(0).ligne();
        int score = c.candidats().get(0).score();
        Map<String, Object> metadata = base(classee.releve(), c.ligne());
        metadata.put("statutRapprochement", c.statut().name());
        metadata.put("confiance", score);
        metadata.put("explication", c.candidats().get(0).explication());
        metadata.put("ligneEcritureId", candidat.getId().toString());
        metadata.put("numeroPiece", candidat.getEcriture().getNumeroPiece());
        metadata.put("dateEcriture", candidat.getEcriture().getDateEcriture().toString());
        return ControlResult.ouvert(ControlCodes.MATCH_TO_CONFIRM, ControlCategory.RECONCILIATION, ControlSeverity.INFO,
                entreprise.getId(), "LIGNE_RELEVE", c.ligne().getId().toString(),
                "Rapprochement a confirmer : " + libelle(c.ligne()),
                "Un seul mouvement du compte " + classee.releve().getCompte().getNumero() + " a le meme montant ("
                        + c.ligne().getMontant().abs().toPlainString() + ") : piece " + candidat.getEcriture().getNumeroPiece()
                        + " du " + candidat.getEcriture().getDateEcriture()
                        + (c.statut() == RapprochementClassifier.Statut.PROBABLE
                                ? ", mais la date de la transaction (" + c.ligne().getDateOperation() + ") s'en ecarte de plus de "
                                        + RapprochementClassifier.FENETRE_EXACTE_JOURS + " jours."
                                : ".")
                        + " Confirmer ou rejeter ce pointage.",
                List.of(candidat.getId().toString()), metadata,
                "Pointer la ligne sur cette ecriture si elle correspond, sinon chercher la bonne piece.");
    }

    private ControlResult ambigu(Entreprise entreprise, LigneClassee classee) {
        ClassementNote c = classee.classement();
        Map<String, Object> metadata = base(classee.releve(), c.ligne());
        metadata.put("statutRapprochement", "AMBIGUOUS");
        metadata.put("nbCandidats", c.candidats().size());
        metadata.put("candidats", c.candidats().stream().map(cand -> Map.<String, Object>of(
                "ligneEcritureId", cand.ligne().getId().toString(),
                "numeroPiece", cand.ligne().getEcriture().getNumeroPiece(),
                "dateEcriture", cand.ligne().getEcriture().getDateEcriture().toString(),
                "score", cand.score())).toList());
        return ControlResult.ouvert(ControlCodes.AMBIGUOUS_BANK_MATCH, ControlCategory.RECONCILIATION, ControlSeverity.WARNING,
                entreprise.getId(), "LIGNE_RELEVE", c.ligne().getId().toString(),
                "Rapprochement ambigu : " + libelle(c.ligne()),
                c.candidats().size() + " mouvements du compte " + classee.releve().getCompte().getNumero()
                        + " peuvent correspondre a cette transaction de " + c.ligne().getMontant().abs().toPlainString()
                        + " (ou un meme mouvement est revendique par plusieurs transactions). Le systeme ne choisit jamais le premier : "
                        + "une decision humaine est necessaire.",
                c.candidats().stream().map(cand -> cand.ligne().getId().toString()).toList(), metadata,
                "Choisir l'ecriture qui correspond, depuis l'ecran de rapprochement.");
    }

    private ControlResult sansPaiement(Entreprise entreprise, ReleveBancaire releve, ClassementNote c) {
        Map<String, Object> metadata = base(releve, c.ligne());
        metadata.put("statutRapprochement", "UNMATCHED");
        return ControlResult.ouvert(ControlCodes.UNMATCHED_PAYMENT, ControlCategory.RECONCILIATION, ControlSeverity.WARNING,
                entreprise.getId(), "LIGNE_RELEVE", c.ligne().getId().toString(),
                "Transaction sans ecriture : " + libelle(c.ligne()),
                "Aucune ecriture validee du compte " + releve.getCompte().getNumero() + " ne correspond a cette transaction du "
                        + c.ligne().getDateOperation() + " (" + c.ligne().getMontant().toPlainString() + ").",
                List.of(), metadata,
                "Saisir le reglement correspondant (ou obtenir la piece) puis relancer le rapprochement.");
    }

    private ControlResult paquet(Entreprise entreprise, ReleveBancaire releve, List<ClassementNote> classements) {
        BigDecimal total = classements.stream().map(c -> c.ligne().getMontant()).reduce(BigDecimal.ZERO, BigDecimal::add);
        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put("releveId", releve.getId().toString());
        metadata.put("compte", releve.getCompte().getNumero());
        metadata.put("nbLignesNonRapprochees", classements.size());
        metadata.put("montantNet", total);
        return ControlResult.ouvert(ControlCodes.UNMATCHED_PAYMENT, ControlCategory.RECONCILIATION, ControlSeverity.WARNING,
                entreprise.getId(), "RELEVE", releve.getId().toString(),
                classements.size() + " transactions sans ecriture sur le releve " + releve.getLibelle(),
                classements.size() + " transactions du releve (compte " + releve.getCompte().getNumero()
                        + ") n'ont aucun mouvement correspondant : un seul constat resume le releve pour ne pas noyer la revue.",
                classements.stream().limit(MAX_IDS_LIES).map(c -> c.ligne().getId().toString()).toList(), metadata,
                "Importer/saisir les ecritures manquantes puis relancer le rapprochement.");
    }

    private Map<String, Object> base(ReleveBancaire releve, LigneReleve ligne) {
        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put("releveId", releve.getId().toString());
        metadata.put("compte", releve.getCompte().getNumero());
        metadata.put("montant", ligne.getMontant());
        metadata.put("dateOperation", ligne.getDateOperation().toString());
        metadata.put("libelle", ligne.getLibelle() != null ? ligne.getLibelle() : "");
        return metadata;
    }

    private String libelle(LigneReleve ligne) {
        return (ligne.getLibelle() != null ? ligne.getLibelle() : "transaction") + " (" + ligne.getMontant().toPlainString() + ")";
    }
}
