package com.example.comptable.investigation;

import com.example.comptable.banque.BankLineageService;
import com.example.comptable.comptabilite.domain.EcritureComptable;
import com.example.comptable.comptabilite.domain.Facture;
import com.example.comptable.comptabilite.domain.Immobilisation;
import com.example.comptable.comptabilite.domain.LigneEcriture;
import com.example.comptable.comptabilite.domain.StatutFacture;
import com.example.comptable.comptabilite.domain.StatutImmobilisation;
import com.example.comptable.comptabilite.lineage.AccountingLineageDto;
import com.example.comptable.comptabilite.lineage.AccountingLineageService;
import com.example.comptable.comptabilite.operation.common.repository.AccountingOperationProposalRepository;
import com.example.comptable.comptabilite.repository.EcritureComptableRepository;
import com.example.comptable.comptabilite.repository.FactureRepository;
import com.example.comptable.comptabilite.repository.ImmobilisationRepository;
import com.example.comptable.comptabilite.repository.LigneEcritureRepository;
import com.example.comptable.demande.domain.DemandePiece;
import com.example.comptable.demande.repository.DemandePieceRepository;
import com.example.comptable.document.domain.Document;
import com.example.comptable.document.domain.StatutDocument;
import com.example.comptable.document.domain.TypeDocument;
import com.example.comptable.document.repository.DocumentRepository;
import com.example.comptable.investigation.InvestigationResult.Basis;
import com.example.comptable.investigation.InvestigationResult.Cause;
import com.example.comptable.investigation.InvestigationResult.Evidence;
import com.example.comptable.investigation.InvestigationResult.Nature;
import com.example.comptable.investigation.InvestigationResult.TimelineEvent;
import com.example.comptable.security.UtilisateurPrincipal;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

/**
 * Investigation d'une PIECE comptable : ecriture (sa source, son document, son rapprochement bancaire, ses affectations), facture, document
 * et immobilisation. Tout part des liens enregistres du lineage ; l'absence d'un lien est un fait verifiable (« aucune piece rattachee »), jamais
 * une supposition.
 */
@Component
class PieceInvestigator {

    private final EcritureComptableRepository ecritureRepository;
    private final LigneEcritureRepository ligneEcritureRepository;
    private final FactureRepository factureRepository;
    private final DocumentRepository documentRepository;
    private final DemandePieceRepository demandeRepository;
    private final ImmobilisationRepository immobilisationRepository;
    private final AccountingOperationProposalRepository proposalRepository;
    private final AccountingLineageService lineageService;
    private final BankLineageService bankLineageService;
    private final TiersInvestigator tiersInvestigator;

    PieceInvestigator(EcritureComptableRepository ecritureRepository, LigneEcritureRepository ligneEcritureRepository, FactureRepository factureRepository,
                      DocumentRepository documentRepository, DemandePieceRepository demandeRepository, ImmobilisationRepository immobilisationRepository,
                      AccountingOperationProposalRepository proposalRepository, AccountingLineageService lineageService, BankLineageService bankLineageService,
                      TiersInvestigator tiersInvestigator) {
        this.ecritureRepository = ecritureRepository;
        this.ligneEcritureRepository = ligneEcritureRepository;
        this.factureRepository = factureRepository;
        this.documentRepository = documentRepository;
        this.demandeRepository = demandeRepository;
        this.immobilisationRepository = immobilisationRepository;
        this.proposalRepository = proposalRepository;
        this.lineageService = lineageService;
        this.bankLineageService = bankLineageService;
        this.tiersInvestigator = tiersInvestigator;
    }

    // ------------------------------------------------------------------------------------------------ ecriture

    InvestigationResult.Builder ecriture(UUID entrepriseId, UUID ecritureId, UtilisateurPrincipal principal) {
        EcritureComptable e = ecritureRepository.findById(ecritureId).filter(x -> x.getEntreprise().getId().equals(entrepriseId))
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Ecriture introuvable"));
        AccountingLineageDto.DepuisEcriture lignee = lineageService.depuisEcriture(entrepriseId, ecritureId, principal);
        List<LigneEcriture> lignes = ligneEcritureRepository.chargerDeLEcriture(ecritureId);
        BigDecimal debit = lignes.stream().map(LigneEcriture::getDebit).reduce(BigDecimal.ZERO, BigDecimal::add);
        BigDecimal credit = lignes.stream().map(LigneEcriture::getCredit).reduce(BigDecimal.ZERO, BigDecimal::add);
        String libelle = "Ecriture " + e.getNumeroPiece() + " du " + e.getDateEcriture();
        InvestigationResult.Builder b = new InvestigationResult.Builder("ECRITURE", ecritureId.toString(), libelle);
        b.related("ECRITURE", ecritureId.toString(), libelle);
        b.comparison("Total des debits", debit, "Total des credits", credit);
        Evidence preuve = new Evidence("ENTRY", "ECRITURE", ecritureId.toString(), libelle + " : " + e.getLibelle(), debit, e.getDateEcriture());
        b.evidence(preuve);

        if (lignee.proposition() != null) {
            AccountingLineageDto.Proposition p = lignee.proposition();
            String source = p.source() != null ? p.source().type() + (p.source().event() != null ? " / " + p.source().event() : "") : "inconnue";
            b.event(new TimelineEvent(e.getDateEcriture(), "SOURCE", "Operation source : " + source, null, p.source() != null ? p.source().type() : null,
                    p.source() != null && p.source().id() != null ? p.source().id().toString() : null));
            b.event(new TimelineEvent(e.getDateEcriture(), "PROPOSITION", "Proposition comptable " + p.type() + " (" + p.statut() + ")", debit, "ECRITURE", ecritureId.toString()));
        } else {
            b.event(new TimelineEvent(e.getDateEcriture(), "SAISIE", "Saisie manuelle : aucune operation source enregistree", debit, "ECRITURE", ecritureId.toString()));
        }
        b.event(new TimelineEvent(e.getDateEcriture(), "ECRITURE", libelle + " (" + e.getStatut() + ")", debit, "ECRITURE", ecritureId.toString()));

        // Piece justificative : lien enregistre (document) sinon constat d'absence + suite donnee.
        boolean achat = e.getJournal().getType() == com.example.comptable.comptabilite.domain.TypeJournal.ACHATS;
        if (achat && lignee.documents().isEmpty()) {
            DemandePiece d = demandeRepository.deLEcritures(entrepriseId, List.of(ecritureId)).stream().findFirst().orElse(null);
            String suite = d == null ? "Aucune demande de piece n'a ete faite." : "Une demande de piece existe (statut " + d.getStatut() + ").";
            b.cause(new Cause(CauseCodes.MISSING_DOCUMENT, Nature.ANOMALY, "L'ecriture " + e.getNumeroPiece() + " ne s'appuie sur aucune piece justificative. " + suite,
                    Basis.EXACT, debit, List.of(preuve)));
            if (d != null) {
                b.event(new TimelineEvent(d.getCreatedAt().toLocalDate(), "DOCUMENT_REQUEST", "Demande de piece creee (" + d.getStatut() + ")", d.getMontant(), "DEMANDE_PIECE", d.getId().toString()));
                b.related("DEMANDE_PIECE", d.getId().toString(), "Demande de piece pour " + e.getNumeroPiece());
            }
            b.action(d == null ? "REQUEST_DOCUMENT" : "FOLLOW_UP_DOCUMENT_REQUEST", d == null ? "Demander la piece au client" : "Relancer la demande de piece", "Demandes de pieces",
                    "ECRITURE", ecritureId.toString());
        } else {
            for (AccountingLineageDto.DocumentOrigine doc : lignee.documents()) {
                b.related("DOCUMENT", doc.id().toString(), "Piece " + doc.nomFichier());
                b.event(new TimelineEvent(e.getDateEcriture(), "DOCUMENT", "Piece justificative : " + doc.nomFichier() + " (" + doc.lien() + ")", null, "DOCUMENT", doc.id().toString()));
            }
        }

        // Rapprochement bancaire et affectations de cette ecriture.
        BankLineageService.DepuisEcritureBanque banque = bankLineageService.depuisEcriture(entrepriseId, ecritureId, principal);
        for (BankLineageService.TransactionLiee t : banque.transactions()) {
            b.event(new TimelineEvent(t.date(), "BANK_TRANSACTION", "Transaction bancaire « " + t.libelle() + " » (" + t.statut() + ")", t.montant(), "LIGNE_RELEVE", t.transactionId().toString()));
            b.related("LIGNE_RELEVE", t.transactionId().toString(), "Transaction « " + t.libelle() + " »");
        }
        for (BankLineageService.AffectationLiee a : banque.affectations()) {
            b.event(new TimelineEvent(e.getDateEcriture(), "ALLOCATION", "Affectation de " + Fmt.m(a.montant()) + " (" + a.role() + ") avec la piece " + a.contrepartiePiece(),
                    a.montant(), "ALLOCATION", a.allocationId().toString()));
            if (a.contrepartieEcritureId() != null) {
                b.related("ECRITURE", a.contrepartieEcritureId().toString(), "Piece " + a.contrepartiePiece());
            }
        }

        // Ligne de tiers d'une facture ou d'un reglement : on reprend l'analyse du tiers, centree sur cette ligne.
        lignes.stream().filter(l -> l.getTiers() != null || l.getCompte().getNumero().startsWith("40") || l.getCompte().getNumero().startsWith("41")).findFirst().ifPresent(tiersLigne -> {
            InvestigationResult.Builder dessous = tiersInvestigator.investiguerLigne(entrepriseId, tiersLigne.getId());
            InvestigationResult r = dessous.build();
            r.causes().stream().filter(c -> !CauseCodes.MISSING_DOCUMENT.equals(c.code())).forEach(b::cause);
            r.relatedEntities().forEach(x -> b.related(x.type(), x.id(), x.label()));
            r.suggestedActions().stream().filter(a -> !a.code().contains("DOCUMENT")).forEach(a -> b.action(a.code(), a.label(), a.operation(), a.targetType(), a.targetId()));
            r.timeline().stream().filter(t -> !"ECRITURE".equals(t.type()) && !"FACTURE".equals(t.type()) && !"REGLEMENT".equals(t.type()) && !"AVOIR".equals(t.type())).forEach(b::event);
        });
        b.summary(b.aUneAnomalie() ? libelle + " : un point demande une decision." : libelle + " : sa chaine (source, piece, banque, affectations) est complete.");
        return b;
    }

    // ------------------------------------------------------------------------------------------------ facture

    InvestigationResult.Builder facture(UUID entrepriseId, UUID factureId, UtilisateurPrincipal principal) {
        Facture f = factureRepository.findById(factureId).filter(x -> x.getEntreprise().getId().equals(entrepriseId))
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Facture introuvable"));
        if (f.getEcritureId() != null) {
            return ecriture(entrepriseId, f.getEcritureId(), principal);
        }
        InvestigationResult.Builder b = new InvestigationResult.Builder("FACTURE", factureId.toString(), "Facture " + f.getNumero());
        b.related("FACTURE", factureId.toString(), "Facture " + f.getNumero());
        if (f.getStatut() == StatutFacture.BROUILLON) {
            b.cause(new Cause(CauseCodes.INVOICE_NOT_POSTED, Nature.INFO, "La facture " + f.getNumero() + " est encore en brouillon : elle n'est pas comptabilisee.", Basis.PERSISTED, null,
                    List.of(new Evidence("INVOICE", "FACTURE", factureId.toString(), "Facture " + f.getNumero(), null, f.getDateEmission()))));
        } else {
            b.cause(new Cause(CauseCodes.INVOICE_NOT_POSTED, Nature.ANOMALY, "La facture " + f.getNumero() + " est " + f.getStatut() + " mais aucune ecriture ne la porte : son montant manque dans les comptes.",
                    Basis.PERSISTED, null, List.of(new Evidence("INVOICE", "FACTURE", factureId.toString(), "Facture " + f.getNumero(), null, f.getDateEmission()))));
            b.action("POST_INVOICE", "Comptabiliser la facture", "Comptabilite > Facturation", "FACTURE", factureId.toString());
        }
        b.event(new TimelineEvent(f.getDateEmission(), "FACTURE", "Facture " + f.getNumero() + " (" + f.getStatut() + ")", null, "FACTURE", factureId.toString()));
        b.summary(b.aUneAnomalie() ? "La facture " + f.getNumero() + " n'est pas comptabilisee." : "La facture " + f.getNumero() + " est un brouillon : rien n'est attendu pour le moment.");
        return b;
    }

    // ------------------------------------------------------------------------------------------------ document

    InvestigationResult.Builder document(UUID entrepriseId, UUID documentId) {
        Document d = documentRepository.findByIdAndEntrepriseId(documentId, entrepriseId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Document introuvable"));
        String libelle = "Document " + d.getNomFichierOriginal();
        InvestigationResult.Builder b = new InvestigationResult.Builder("DOCUMENT", documentId.toString(), libelle);
        b.related("DOCUMENT", documentId.toString(), libelle);
        Evidence preuve = new Evidence("DOCUMENT", "DOCUMENT", documentId.toString(), libelle, d.getMontantTtc(), d.getDatePiece());
        b.evidence(preuve);
        b.event(new TimelineEvent(d.getCreatedAt().toLocalDate(), "DEPOT", "Depose (" + d.getCanalSource() + ")", d.getMontantTtc(), "DOCUMENT", documentId.toString()));
        if (d.getValideAt() != null) {
            b.event(new TimelineEvent(d.getValideAt().toLocalDate(), "VALIDATION", "Valide par un humain", d.getMontantTtc(), "DOCUMENT", documentId.toString()));
        }
        if (d.getRapprocheAt() != null) {
            b.event(new TimelineEvent(d.getRapprocheAt().toLocalDate(), "RAPPROCHEMENT", "Rapproche (" + d.getStatutRapprochement() + ")", d.getMontantTtc(), "DOCUMENT", documentId.toString()));
        }
        if (d.getEcritureId() != null) {
            b.related("ECRITURE", d.getEcritureId().toString(), "Ecriture de ce document");
            b.event(new TimelineEvent(d.getDatePiece(), "ECRITURE", "Comptabilise", d.getMontantTtc(), "ECRITURE", d.getEcritureId().toString()));
        } else if ((d.getTypeDocument() == TypeDocument.FACTURE_ACHAT || d.getTypeDocument() == TypeDocument.FACTURE_VENTE)
                && (d.getStatut() == StatutDocument.TRAITE || d.getStatut() == StatutDocument.EN_ATTENTE_VALIDATION_HUMAINE)) {
            b.cause(new Cause(CauseCodes.INVOICE_NOT_POSTED, Nature.ANOMALY, "La facture " + (d.getNumeroFacture() != null ? d.getNumeroFacture() : d.getNomFichierOriginal())
                    + " est lue et prete, mais aucune ecriture ne la porte : son montant (" + (d.getMontantTtc() != null ? Fmt.m(d.getMontantTtc()) : "non lu") + ") manque dans les comptes.",
                    Basis.PERSISTED, d.getMontantTtc(), List.of(preuve)));
            b.action("POST_DOCUMENT", "Comptabiliser la facture", "Documents > Comptabiliser", "DOCUMENT", documentId.toString());
        }
        if (d.getDoublonDeId() != null) {
            b.related("DOCUMENT", d.getDoublonDeId().toString(), "Document d'origine du doublon");
            b.cause(new Cause(CauseCodes.DUPLICATE_INVOICE, Nature.ANOMALY, "Ce document est un doublon d'un document deja depose (meme facture) : le comptabiliser doublerait la charge.",
                    Basis.PERSISTED, d.getMontantTtc(), List.of(new Evidence("ORIGINAL", "DOCUMENT", d.getDoublonDeId().toString(), "Document d'origine", d.getMontantTtc(), null))));
        }
        if (d.getDemandeId() != null) {
            demandeRepository.findByIdAndEntrepriseId(d.getDemandeId(), entrepriseId).ifPresent(dem -> {
                b.related("DEMANDE_PIECE", dem.getId().toString(), "Demande de piece");
                b.cause(new Cause(CauseCodes.DOCUMENT_REQUEST_PENDING, Nature.INFO, "Ce document repond a une demande de piece (statut " + dem.getStatut() + ").", Basis.PERSISTED, dem.getMontant(), List.of()));
            });
        }
        b.summary(b.aUneAnomalie() ? libelle + " : un point demande une decision." : libelle + " : rien a signaler.");
        return b;
    }

    // ------------------------------------------------------------------------------------------------ immobilisation

    InvestigationResult.Builder immobilisation(UUID entrepriseId, UUID immobilisationId) {
        Immobilisation i = immobilisationRepository.findByIdAndEntrepriseId(immobilisationId, entrepriseId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Immobilisation introuvable"));
        String libelle = "Immobilisation " + i.getLibelle();
        InvestigationResult.Builder b = new InvestigationResult.Builder("IMMOBILISATION", immobilisationId.toString(), libelle);
        b.related("IMMOBILISATION", immobilisationId.toString(), libelle);
        Evidence preuve = new Evidence("FIXED_ASSET", "IMMOBILISATION", immobilisationId.toString(), libelle + " (compte " + i.getCompte().getNumero() + ")", i.getValeurOrigine(), i.getDateAcquisition());
        b.evidence(preuve);
        b.event(new TimelineEvent(i.getDateAcquisition(), "ACQUISITION", "Acquisition inscrite au registre", i.getValeurOrigine(), "IMMOBILISATION", immobilisationId.toString()));
        if (i.getDateMiseEnService() != null) {
            b.event(new TimelineEvent(i.getDateMiseEnService(), "MISE_EN_SERVICE", "Mise en service", null, "IMMOBILISATION", immobilisationId.toString()));
        }
        boolean sansSource = immobilisationRepository.rechercherSansSourceComptable(entrepriseId).stream().anyMatch(x -> x.getId().equals(immobilisationId));
        boolean enService = i.getStatut() == StatutImmobilisation.EN_SERVICE;
        if (i.getAcquisitionProposal() != null) {
            b.comparison("Valeur d'origine au registre", i.getValeurOrigine(), "Montant comptabilise a l'acquisition", i.getAcquisitionProposal().getMontantTotalDebit());
            b.related("SOURCE_COMPTABLE", i.getAcquisitionProposal().getId().toString(), "Operation d'acquisition");
        } else {
            b.comparison("Valeur d'origine au registre", i.getValeurOrigine(), "Montant comptabilise a l'acquisition", sansSource ? BigDecimal.ZERO : null);
        }
        if (sansSource) {
            b.cause(new Cause(CauseCodes.FIXED_ASSET_WITHOUT_SOURCE, enService ? Nature.ANOMALY : Nature.ANOMALY,
                    "Le bien (" + Fmt.m(i.getValeurOrigine()) + ", compte " + i.getCompte().getNumero() + ") figure au registre mais aucune ecriture d'acquisition ni facture d'achat ne le porte"
                            + (enService ? " alors qu'il est deja en service et amortissable." : "."), Basis.PERSISTED, i.getValeurOrigine(), List.of(preuve)));
            b.action("LINK_ACQUISITION", "Lier le bien a la facture d'achat comptabilisee, ou comptabiliser son acquisition", "Immobilisations", "IMMOBILISATION", immobilisationId.toString());
        }
        if (immobilisationRepository.rechercherAcquisitionsDirectes(entrepriseId).stream().anyMatch(x -> x.getId().equals(immobilisationId))) {
            var achats = proposalRepository.rechercherAchatsDebitant(entrepriseId, i.getCompte().getId(), i.getValeurOrigine()).stream()
                    .filter(p -> immobilisationRepository.findByAcquisitionProposalId(p.getId()).isEmpty()).toList();
            if (!achats.isEmpty()) {
                b.cause(new Cause(CauseCodes.POSSIBLE_DOUBLE_ACQUISITION, Nature.ANOMALY, achats.size() + " facture(s) d'achat comptabilisee(s) debitent ce compte pour la meme valeur sans etre rattachee(s) a un bien : "
                        + "le meme achat est peut-etre compte deux fois.", Basis.EXACT, i.getValeurOrigine(), achats.stream()
                        .map(p -> new Evidence("PURCHASE", p.getSourceType(), p.getSourceId().toString(), "Achat source " + p.getSourceType(), i.getValeurOrigine(), p.getDateOperation())).toList()));
                achats.forEach(p -> b.related(p.getSourceType(), p.getSourceId().toString(), "Achat source " + p.getSourceType()));
            }
        }
        b.summary(b.aUneAnomalie() ? libelle + " : le registre n'est pas adosse a la comptabilite." : libelle + " : le bien est adosse a une ecriture d'acquisition.");
        return b;
    }
}
