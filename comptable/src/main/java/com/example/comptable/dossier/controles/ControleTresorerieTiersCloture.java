package com.example.comptable.dossier.controles;

import com.example.comptable.comptabilite.domain.LigneEcriture;
import com.example.comptable.comptabilite.domain.LigneReleve;
import com.example.comptable.comptabilite.domain.ReleveBancaire;
import com.example.comptable.comptabilite.domain.Tiers;
import com.example.comptable.comptabilite.domain.TypeTiers;
import com.example.comptable.comptabilite.repository.LigneEcritureRepository;
import com.example.comptable.comptabilite.repository.ReleveBancaireRepository;
import com.example.comptable.dossier.web.dto.ControlItem;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** Banque, lettrage et soldes de tiers (lot 1.10, explicabilite lot 1.11). */
@Component
public class ControleTresorerieTiersCloture implements ClotureControle {

    private final ReleveBancaireRepository releveBancaireRepository;
    private final LigneEcritureRepository ligneEcritureRepository;
    private final com.example.comptable.banque.BankReconciliationRepository reconciliationRepository;
    private final com.example.comptable.investigation.AccountingInvestigationService investigationService;

    @org.springframework.beans.factory.annotation.Autowired
    public ControleTresorerieTiersCloture(ReleveBancaireRepository releveBancaireRepository,
                                          LigneEcritureRepository ligneEcritureRepository,
                                          com.example.comptable.banque.BankReconciliationRepository reconciliationRepository,
                                          com.example.comptable.investigation.AccountingInvestigationService investigationService) {
        this.releveBancaireRepository = releveBancaireRepository;
        this.ligneEcritureRepository = ligneEcritureRepository;
        this.reconciliationRepository = reconciliationRepository;
        this.investigationService = investigationService;
    }

    /** Sans le suivi des propositions ni le debugger (tests unitaires du controle seul). */
    public ControleTresorerieTiersCloture(ReleveBancaireRepository releveBancaireRepository, LigneEcritureRepository ligneEcritureRepository) {
        this(releveBancaireRepository, ligneEcritureRepository, null, null);
    }

    @Override
    public List<ControlItem> evaluer(Contexte contexte) {
        List<LigneEcriture> enInstance = ligneEcritureRepository
                .rechercherLignesEnInstance(contexte.entrepriseId(), contexte.exercice().getDateFin());
        return List.of(banque(contexte), lettrage(enInstance), soldesAnormaux("fournisseurs", "Comptes fournisseurs",
                        TypeTiers.FOURNISSEUR, enInstance, true),
                soldesAnormaux("clients", "Comptes clients", TypeTiers.CLIENT, enInstance, false));
    }

    /**
     * Rapprochement bancaire : lignes de releve de la periode non pointees sur une ecriture. Detail par
     * releve (compte, lignes pointees/non pointees, montant non pointe) pour que le comptable sache
     * quel releve traiter ; {@code relatedEntityIds} liste les lignes non pointees.
     */
    private ControlItem banque(Contexte c) {
        LocalDate debut = c.exercice().getDateDebut();
        LocalDate fin = c.exercice().getDateFin();
        List<ReleveBancaire> releves = releveBancaireRepository.findByEntrepriseIdOrderByDateFinDesc(c.entrepriseId()).stream()
                .filter(r -> !r.getDateFin().isBefore(debut) && !r.getDateDebut().isAfter(fin))
                .toList();
        if (releves.isEmpty()) {
            return ClotureControle.nonApplicable("banque", "Rapprochement bancaire",
                    "Aucun releve bancaire importe pour cet exercice : rien a rapprocher, ce n'est pas un rapprochement acheve.");
        }

        // Propositions du systeme en attente d'une decision : en une requete pour tous les releves.
        java.util.Set<java.util.UUID> propositions = new java.util.HashSet<>();
        if (reconciliationRepository != null) {
            List<java.util.UUID> idsLignes = releves.stream().flatMap(r -> r.getLignes().stream()).map(LigneReleve::getId).toList();
            if (!idsLignes.isEmpty()) {
                reconciliationRepository.findActifsDe(idsLignes).stream()
                        .filter(r -> r.getStatus() == com.example.comptable.banque.StatutReconciliation.PROPOSED)
                        .forEach(r -> propositions.add(r.getBankTransactionId()));
            }
        }
        int aConfirmer = 0;
        int nbLignes = 0;
        int nonPointees = 0;
        BigDecimal montantNonPointe = BigDecimal.ZERO;
        List<String> lignesNonPointees = new ArrayList<>();
        List<Map<String, Object>> detailReleves = new ArrayList<>();
        String premierReleve = null;
        for (ReleveBancaire releve : releves) {
            int relevesNonPointees = 0;
            BigDecimal montantReleve = BigDecimal.ZERO;
            // Solde comptable du compte a la date de fin du releve vs solde annonce par la banque (meme calcul que l'ecran de rapprochement).
            BigDecimal soldeComptable = java.util.Objects.requireNonNullElse(
                    ligneEcritureRepository.soldeNetAvant(releve.getCompte().getId(), releve.getDateFin().plusDays(1)), BigDecimal.ZERO);
            // Le solde du releve est facultatif : sans lui, seul le pointage des lignes est controle.
            BigDecimal ecartSolde = releve.getSoldeFin() != null ? releve.getSoldeFin().subtract(soldeComptable) : BigDecimal.ZERO;
            for (LigneReleve ligne : releve.getLignes()) {
                nbLignes++;
                if (ligne.getLigneEcritureId() == null) {
                    if (propositions.contains(ligne.getId())) {
                        aConfirmer++;
                    }
                    nonPointees++;
                    relevesNonPointees++;
                    montantReleve = montantReleve.add(ligne.getMontant());
                    lignesNonPointees.add(ligne.getId().toString());
                }
            }
            montantNonPointe = montantNonPointe.add(montantReleve);
            if (relevesNonPointees > 0 || ecartSolde.signum() != 0) {
                if (premierReleve == null) {
                    premierReleve = releve.getId().toString();
                }
                Map<String, Object> detail = new LinkedHashMap<>();
                detail.put("releveId", releve.getId().toString());
                detail.put("compte", releve.getCompte().getNumero());
                detail.put("libelle", releve.getLibelle());
                detail.put("lignes", releve.getLignes().size());
                detail.put("nonPointees", relevesNonPointees);
                detail.put("montantNonPointe", montantReleve);
                detail.put("soldeComptable", soldeComptable);
                detail.put("soldeReleve", releve.getSoldeFin());
                detail.put("ecartSolde", ecartSolde);
                detail.put("ecartExpliqueParNonPointees", ecartSolde.abs().compareTo(montantReleve.abs()) == 0);
                // Causes structurees (memes codes que « Pourquoi ? ») : le point de cloture dit POURQUOI l'ecart existe, pas seulement qu'il existe.
                if (investigationService != null) {
                    detail.put("causes", investigationService.causesDuReleve(c.entrepriseId(), releve.getId()));
                }
                detailReleves.add(detail);
            }
        }

        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put("nbReleves", releves.size());
        metadata.put("nbRelevesAvecEcart", detailReleves.size());
        metadata.put("nbLignes", nbLignes);
        metadata.put("nbLignesPointees", nbLignes - nonPointees);
        metadata.put("nbLignesNonPointees", nonPointees);
        metadata.put("nbPropositionsAConfirmer", aConfirmer);
        metadata.put("montantNonPointe", montantNonPointe);
        metadata.put("releves", detailReleves);

        if (nonPointees == 0 && detailReleves.isEmpty()) {
            return ClotureControle.ok("banque", "Rapprochement bancaire",
                    releves.size() + " releve(s) : les " + nbLignes + " lignes sont pointees et les soldes concordent.", metadata);
        }
        String message = nonPointees > 0
                ? nonPointees + " ligne(s) de releve non pointee(s) sur " + detailReleves.size() + " releve(s)"
                        + (aConfirmer > 0 ? " (dont " + aConfirmer + " proposition(s) a confirmer)." : ".")
                : "Ecart entre le solde du releve et le solde comptable sur " + detailReleves.size() + " releve(s).";
        StringBuilder explication = new StringBuilder(message)
                .append(" ").append(releves.size() - detailReleves.size()).append(" releve(s) sur ").append(releves.size())
                .append(" entierement rapproche(s). Montant non pointe : ").append(montantNonPointe.toPlainString()).append(".");
        for (Map<String, Object> d : detailReleves) {
            explication.append(" Compte ").append(d.get("compte")).append(" : solde comptable ").append(d.get("soldeComptable"))
                    .append(" contre solde du releve ").append(d.get("soldeReleve")).append(", ecart ").append(d.get("ecartSolde"))
                    .append(" ; ").append(d.get("nonPointees")).append("/").append(d.get("lignes")).append(" ligne(s) non pointee(s) (")
                    .append(d.get("montantNonPointe")).append(")")
                    .append(Boolean.TRUE.equals(d.get("ecartExpliqueParNonPointees")) ? ", l'ecart s'explique par ces lignes." : ".");
            Object causes = d.get("causes");
            if (causes instanceof List<?> liste && !liste.isEmpty()) {
                explication.append(" Causes : ");
                explication.append(liste.stream().map(x -> ((Map<?, ?>) x).get("code") + " (" + ((Map<?, ?>) x).get("montant") + ")")
                        .collect(java.util.stream.Collectors.joining(", "))).append(".");
            }
        }
        return ClotureControle.avertissement("banque", "Rapprochement bancaire", message, explication.toString(),
                "RELEVE", premierReleve, lignesNonPointees, metadata);
    }

    /** Lignes de comptes de tiers (lettrables) validees mais non lettrees a la date de fin d'exercice. */
    private ControlItem lettrage(List<LigneEcriture> enInstance) {
        if (enInstance.isEmpty()) {
            return ClotureControle.ok("lettrage", "Lettrage des comptes de tiers",
                    "Aucune ligne de compte de tiers en instance de lettrage.", Map.of());
        }
        BigDecimal debit = enInstance.stream().map(LigneEcriture::getDebit).reduce(BigDecimal.ZERO, BigDecimal::add);
        BigDecimal credit = enInstance.stream().map(LigneEcriture::getCredit).reduce(BigDecimal.ZERO, BigDecimal::add);
        long nbTiers = enInstance.stream().map(LigneEcriture::getTiers).filter(java.util.Objects::nonNull).map(Tiers::getId).distinct().count();
        String message = enInstance.size() + " ligne(s) de tiers non lettree(s) (creances/dettes ouvertes).";
        String explication = message + " Total debit " + debit.toPlainString() + ", total credit " + credit.toPlainString()
                + ", " + nbTiers + " tiers concerne(s). A justifier (facture impayee legitime) ou a lettrer avant la cloture.";
        return ClotureControle.avertissement("lettrage", "Lettrage des comptes de tiers", message, explication,
                "LIGNE_ECRITURE", enInstance.get(0).getId().toString(),
                enInstance.stream().map(l -> l.getId().toString()).toList(),
                Map.of("nbLignes", enInstance.size(), "totalDebit", debit, "totalCredit", credit, "nbTiers", nbTiers));
    }

    /**
     * Solde inhabituel parmi les lignes en instance : fournisseur DEBITEUR (avance, avoir non impute,
     * erreur d'imputation) ou client CREDITEUR (acompte, avoir, erreur). Deterministe : un signe, pas un seuil.
     */
    private ControlItem soldesAnormaux(String code, String libelle, TypeTiers type, List<LigneEcriture> enInstance,
                                       boolean anormalSiDebiteur) {
        Map<UUID, BigDecimal> soldes = new LinkedHashMap<>();
        Map<UUID, String> noms = new LinkedHashMap<>();
        for (LigneEcriture ligne : enInstance) {
            Tiers tiers = ligne.getTiers();
            if (tiers == null || tiers.getType() != type) {
                continue;
            }
            soldes.merge(tiers.getId(), ligne.getDebit().subtract(ligne.getCredit()), BigDecimal::add);
            noms.putIfAbsent(tiers.getId(), tiers.getNom());
        }
        List<String> anormaux = new ArrayList<>();
        List<String> ids = new ArrayList<>();
        Map<String, Object> montants = new LinkedHashMap<>();
        soldes.forEach((id, solde) -> {
            boolean anormal = anormalSiDebiteur ? solde.signum() > 0 : solde.signum() < 0;
            if (anormal) {
                anormaux.add(noms.get(id) + " (" + solde.abs().toPlainString() + ")");
                ids.add(id.toString());
                montants.put(noms.get(id), solde);
            }
        });
        String sens = anormalSiDebiteur ? "debiteur" : "crediteur";
        if (anormaux.isEmpty()) {
            return ClotureControle.ok(code, libelle,
                    "Aucun " + (anormalSiDebiteur ? "fournisseur debiteur" : "client crediteur") + " parmi les lignes en instance.",
                    Map.of("nbTiersAnalyses", soldes.size()));
        }
        String message = anormaux.size() + (anormalSiDebiteur ? " fournisseur(s) " : " client(s) ") + sens + "(s) : " + String.join(", ", anormaux) + ".";
        String explication = message + " Un solde " + sens + " sur ce type de tiers est inhabituel (avance, avoir non impute ou erreur d'imputation).";
        return ClotureControle.avertissement(code, libelle, message, explication, "TIERS", ids.get(0), ids,
                Map.of("soldes", montants, "nbTiersAnalyses", soldes.size()));
    }
}
