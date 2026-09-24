package com.example.comptable.agent.comptabilisation;

import com.example.comptable.agent.comptabilisation.domain.AccountingProposal;
import com.example.comptable.agent.comptabilisation.domain.StatutAccountingProposal;
import com.example.comptable.agent.comptabilisation.repository.AccountingProposalRepository;
import com.example.comptable.comptabilite.domain.CompteComptable;
import com.example.comptable.comptabilite.domain.Journal;
import com.example.comptable.comptabilite.domain.Tiers;
import com.example.comptable.comptabilite.repository.JournalRepository;
import com.example.comptable.comptabilite.repository.LigneEcritureRepository;
import com.example.comptable.comptabilite.repository.TiersRepository;
import com.example.comptable.comptabilite.service.CompteComptableValidationService;
import com.example.comptable.document.domain.Document;
import com.example.comptable.document.domain.StatutDocument;
import com.example.comptable.document.domain.TypeDocument;
import com.example.comptable.entreprise.domain.Entreprise;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Transforme un {@link Document} extrait en {@link AccountingProposal} (mission P0, section 14) :
 * tiers et compte de charge/produit resolus par heuristique Java pure sur l'historique deja en
 * base - JAMAIS d'appel LLM ici (l'extraction/classification est deja faite en amont par
 * ai-service ; ce moteur ne fait que rapprocher le fournisseur OCR contre les tiers connus et le
 * compte le plus souvent utilise pour ce tiers, exactement le type de garde-fou deterministe que
 * {@code ComptabilisationService.comptabiliserAutomatiquement} exigeait sans jamais l'avoir).
 * <p>
 * Reprend volontairement la meme technique de normalisation que
 * {@code agent.matching.MatchingContextBuilder#resoudreTiersProbable} plutot que de refactorer ce
 * dernier : la duplication (quelques lignes) est jugee plus sure qu'un changement d'une classe deja
 * en production (mission P0, contrainte "ne pas refondre pour refondre").
 */
@Component
public class AccountingProposalBuilder {

    /** Nombre minimal d'ecritures passees utilisant le meme compte de charge/produit pour ce
     * tiers avant de considerer cet historique "fiable" (niveau 3, section 15). */
    private static final int SEUIL_OCCURRENCES_COMPTE_FIABLE = 3;
    private static final double SEUIL_CONFIANCE_NIVEAU3 = 0.9;

    private final TiersRepository tiersRepository;
    private final LigneEcritureRepository ligneEcritureRepository;
    private final CompteComptableValidationService compteComptableValidationService;
    private final JournalRepository journalRepository;
    private final AccountingProposalRepository accountingProposalRepository;

    public AccountingProposalBuilder(TiersRepository tiersRepository,
                                      LigneEcritureRepository ligneEcritureRepository,
                                      CompteComptableValidationService compteComptableValidationService,
                                      JournalRepository journalRepository,
                                      AccountingProposalRepository accountingProposalRepository) {
        this.tiersRepository = tiersRepository;
        this.ligneEcritureRepository = ligneEcritureRepository;
        this.compteComptableValidationService = compteComptableValidationService;
        this.journalRepository = journalRepository;
        this.accountingProposalRepository = accountingProposalRepository;
    }

    /** Ne propose rien pour un document pas encore extrait avec succes ou deja comptabilise.
     * Idempotent : une proposition PROPOSED existante pour ce document est retournee telle quelle
     * plutot que dupliquee. */
    @Transactional
    public Optional<AccountingProposal> proposer(Entreprise entreprise, Document document) {
        if (document.getStatut() != StatutDocument.TRAITE || document.getEcritureId() != null) {
            return Optional.empty();
        }
        Optional<AccountingProposal> existante = accountingProposalRepository
                .findByDocumentIdAndStatus(document.getId(), StatutAccountingProposal.PROPOSED);
        if (existante.isPresent()) {
            return existante;
        }

        Optional<Tiers> tiersResolu = resoudreTiers(entreprise, extraireFournisseur(document));
        Optional<CompteResolu> compteResolu = tiersResolu.flatMap(t -> resoudreCompte(entreprise.getId(), t.getId()));
        Optional<Journal> journal = resoudreJournal(entreprise, document.getTypeDocument());

        List<String> raisons = new ArrayList<>();
        double confidence;
        if (tiersResolu.isPresent() && compteResolu.isPresent() && compteResolu.get().occurrences() >= SEUIL_OCCURRENCES_COMPTE_FIABLE) {
            confidence = 0.95;
            raisons.add("tiers_identifie_par_historique");
            raisons.add("compte_recurrent_" + compteResolu.get().occurrences() + "_occurrences");
        } else if (tiersResolu.isPresent() && compteResolu.isPresent()) {
            confidence = 0.6;
            raisons.add("tiers_identifie_par_historique");
            raisons.add("compte_deja_utilise_mais_historique_insuffisant");
        } else if (tiersResolu.isPresent()) {
            confidence = 0.3;
            raisons.add("tiers_identifie_mais_aucun_historique_de_compte");
        } else {
            confidence = 0.0;
            raisons.add("aucun_tiers_identifiable_dans_le_libelle_ocr");
        }
        if (journal.isEmpty()) {
            raisons.add("journal_non_determine");
        }

        AccountingProposal proposition = new AccountingProposal();
        proposition.setEntreprise(entreprise);
        proposition.setDocument(document);
        journal.ifPresent(proposition::setJournal);
        tiersResolu.ifPresent(proposition::setTiers);
        compteResolu.ifPresent(c -> proposition.setCompteChargeProduit(c.compte()));
        proposition.setMontantHt(document.getMontantHt());
        proposition.setMontantTva(document.getMontantTva());
        proposition.setMontantTtc(document.getMontantTtc());
        proposition.setConfidence(confidence);
        proposition.setReasonsJson(raisons);
        return Optional.of(accountingProposalRepository.save(proposition));
    }

    /** Niveau 3 (section 15) : seul cas ou {@code ComptabilisationService.comptabiliserAutomatiquement}
     * peut reellement poser une ecriture sans intervention humaine - tiers ET compte ET journal
     * resolus sans ambiguite par un historique coherent, aucun appel LLM implique dans cette
     * decision (garde-fou Java pur, dans l'esprit des autres garde-fous de ce projet). */
    public boolean eligibleNiveau3(AccountingProposal proposition) {
        return proposition.getTiers() != null
                && proposition.getCompteChargeProduit() != null
                && proposition.getJournal() != null
                && proposition.getConfidence() >= SEUIL_CONFIANCE_NIVEAU3
                && proposition.getMontantTtc() != null;
    }

    @Transactional
    public void marquerAppliquee(AccountingProposal proposition) {
        proposition.setStatus(StatutAccountingProposal.APPLIED);
        proposition.setValidatedAt(java.time.OffsetDateTime.now());
        accountingProposalRepository.save(proposition);
    }

    private Optional<Tiers> resoudreTiers(Entreprise entreprise, String fournisseurOcr) {
        if (fournisseurOcr == null || fournisseurOcr.isBlank()) {
            return Optional.empty();
        }
        String normalise = normaliser(fournisseurOcr);
        return tiersRepository.findByEntrepriseId(entreprise.getId()).stream()
                .filter(t -> !normaliser(t.getNom()).isBlank())
                .filter(t -> normalise.contains(normaliser(t.getNom())))
                .findFirst();
    }

    private record CompteResolu(CompteComptable compte, long occurrences) {
    }

    private Optional<CompteResolu> resoudreCompte(UUID entrepriseId, UUID tiersId) {
        List<UUID> historique = ligneEcritureRepository.rechercherComptesChargeProduitHistoriquesPourTiers(tiersId);
        if (historique.isEmpty()) {
            return Optional.empty();
        }
        Map<UUID, Long> frequences = historique.stream()
                .collect(Collectors.groupingBy(Function.identity(), Collectors.counting()));
        Map.Entry<UUID, Long> meilleur = frequences.entrySet().stream()
                .max(Map.Entry.comparingByValue())
                .orElseThrow();
        // Verification centralisee (tenant + actif + utilisable en detail) au lieu du findById brut
        // d'origine. Degrade gracieusement (Optional vide) plutot que de laisser une exception
        // remonter : ce moteur d'heuristique doit toujours pouvoir produire une proposition a
        // confiance degradee si le compte historique n'est plus utilisable, jamais echouer.
        try {
            CompteComptable compte = compteComptableValidationService
                    .chargerCompteUtilisable(entrepriseId, meilleur.getKey(), true);
            return Optional.of(new CompteResolu(compte, meilleur.getValue()));
        } catch (ResponseStatusException e) {
            return Optional.empty();
        }
    }

    /** FACTURE_ACHAT uniquement pour ce P0 : seul type de document dont le journal (ACH) est sans
     * ambiguite deductible du type de piece. */
    private Optional<Journal> resoudreJournal(Entreprise entreprise, TypeDocument typeDocument) {
        if (typeDocument != TypeDocument.FACTURE_ACHAT) {
            return Optional.empty();
        }
        return journalRepository.findByEntrepriseIdAndCode(entreprise.getId(), "ACH");
    }

    private String extraireFournisseur(Document document) {
        if (document.getDonneesOcr() == null) {
            return null;
        }
        Object valeur = document.getDonneesOcr().get("fournisseur");
        return valeur != null ? String.valueOf(valeur) : null;
    }

    private String normaliser(String valeur) {
        return valeur == null ? "" : valeur.toUpperCase(Locale.ROOT).replaceAll("[^A-Z0-9]", "");
    }
}
