package com.example.comptable.comptabilite.service;

import com.example.comptable.audit.AuditTrailService;
import com.example.comptable.comptabilite.domain.CompteComptable;
import com.example.comptable.comptabilite.domain.NatureCompte;
import com.example.comptable.comptabilite.domain.StatutEcriture;
import com.example.comptable.comptabilite.domain.Tiers;
import com.example.comptable.comptabilite.domain.TypeJournal;
import com.example.comptable.comptabilite.domain.TypeTiers;
import com.example.comptable.comptabilite.operation.achat.AchatRule;
import com.example.comptable.comptabilite.operation.common.AccountingOperationEngine;
import com.example.comptable.comptabilite.operation.common.Blocages;
import com.example.comptable.comptabilite.operation.common.AccountingSource;
import com.example.comptable.comptabilite.operation.common.AccountingOperationType;
import com.example.comptable.comptabilite.operation.common.ResultatRegle;
import com.example.comptable.comptabilite.operation.common.domain.AccountingOperationProposalLigne;
import com.example.comptable.comptabilite.operation.vente.VenteRule;
import com.example.comptable.comptabilite.operation.common.web.dto.AccountingOperationProposalDto;
import com.example.comptable.comptabilite.parametrage.AccountContext;
import com.example.comptable.comptabilite.parametrage.domain.AccountPurpose;
import com.example.comptable.comptabilite.parametrage.AccountingAccountResolver;
import com.example.comptable.comptabilite.parametrage.ResolvedAccount;
import com.example.comptable.comptabilite.repository.CompteComptableRepository;
import com.example.comptable.comptabilite.repository.EcritureComptableRepository;
import com.example.comptable.comptabilite.repository.TiersRepository;
import com.example.comptable.comptabilite.web.dto.ComptabiliserDocumentRequest;
import com.example.comptable.comptabilite.web.dto.EcritureComptableDto;
import com.example.comptable.document.domain.Document;
import com.example.comptable.document.domain.TypeDocument;
import com.example.comptable.document.repository.DocumentRepository;
import com.example.comptable.entreprise.domain.Entreprise;
import com.example.comptable.entreprise.service.EntrepriseAccessService;
import com.example.comptable.security.UtilisateurPrincipal;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Transforme un Document valide (montants extraits + confirmes) en ecriture comptable
 * equilibree, en s'appuyant sur le tiers et le compte de charge/produit choisis par
 * l'utilisateur (l'OCR ne peut pas deviner ces deux informations de maniere fiable).
 * L'ecriture est toujours creee en BROUILLON : {@code EcritureComptableService.valider} est
 * l'etape separee qui la rend intangible.
 * <p>
 * Depuis la Phase 1 (unification du chemin comptable), ce service est un ORCHESTRATEUR : il valide
 * le document et les choix de l'utilisateur, resout le compte de TVA via
 * {@link AccountingAccountResolver} (purposes TVA_RECUPERABLE / TVA_COLLECTEE, jamais un numero en
 * dur), construit les lignes via {@link AchatRule}/{@link VenteRule}, puis confie la proposition, la
 * resolution du journal, le controle d'exercice ouvert, la numerotation et la creation de
 * l'ecriture a {@link AccountingOperationEngine}. Il ne construit plus d'{@code EcritureComptable}
 * lui-meme. Le comportement observable historique est fige par
 * {@code ComptabilisationDocumentCaracterisationTest}.
 * <p>
 * Compte de TVA non configure/ambigu/invalide : la proposition passe en NEEDS_REVIEW et la
 * comptabilisation est refusee (422) avec l'explication du resolveur - jamais de repli silencieux
 * sur un numero code en dur.
 */
@Service
@RequiredArgsConstructor
public class ComptabilisationService {

    private final EntrepriseAccessService entrepriseAccessService;
    private final DocumentRepository documentRepository;
    private final TiersRepository tiersRepository;
    private final CompteComptableRepository compteComptableRepository;
    private final EcritureComptableRepository ecritureComptableRepository;
    private final AccountingAccountResolver accountingAccountResolver;
    private final AccountingOperationEngine accountingOperationEngine;
    private final AchatRule achatRule;
    private final VenteRule venteRule;
    private final AuditTrailService auditTrailService;

    @Transactional
    public EcritureComptableDto comptabiliser(UUID entrepriseId, UUID documentId,
                                               ComptabiliserDocumentRequest request,
                                               UtilisateurPrincipal principal) {
        Entreprise entreprise = entrepriseAccessService.verifierAcces(entrepriseId, principal);
        Document document = chargerDocument(entrepriseId, documentId);
        TypeOperation operation = determinerOperation(document.getTypeDocument());

        BigDecimal montantHt = document.getMontantHt();
        BigDecimal montantTtc = document.getMontantTtc();
        BigDecimal montantTva = document.getMontantTva() != null ? document.getMontantTva() : BigDecimal.ZERO;

        if (montantHt == null || montantTtc == null) {
            throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY,
                    "Montants insuffisants (HT/TTC) pour comptabiliser automatiquement ; corrigez le document.");
        }
        CoherenceMontants.verifier(montantHt, montantTva, montantTtc, BigDecimal.ZERO).ifPresent(ecart -> {
            throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY,
                    "Montants incoherents : " + ecart.explication() + ". Corrigez les montants avant comptabilisation.");
        });

        LocalDate dateEcriture = request.dateEcriture() != null ? request.dateEcriture() : document.getDatePiece();
        if (dateEcriture == null) {
            throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY, "Date de piece manquante");
        }

        Tiers tiers = chargerTiers(entrepriseId, request.tiersId(), operation);
        CompteComptable compteChargeOuProduit = chargerCompteChargeOuProduit(entrepriseId, request.compteChargeOuProduitId(), operation);

        String libelle = request.libelle() != null ? request.libelle()
                : document.getTypeDocument() + " - " + tiers.getNom();

        return confierAuMoteur(entreprise, document, operation, tiers, compteChargeOuProduit, dateEcriture,
                libelle, montantHt, montantTva, montantTtc, principal);
    }

    /**
     * Point d'entree pour le pipeline agentique ({@code agent.pipeline.EcritureNode}) : pas de
     * {@link UtilisateurPrincipal} (execution en tache de fond, personne n'est connecte) - le
     * {@code createdBy} de l'ecriture generee reste donc volontairement null, trace explicite
     * qu'aucun humain n'a valide cette ligne avant sa creation.
     * <p>
     * Depuis la mission P0 (section 15, "Niveau 3"), {@code tiersId}/{@code compteChargeOuProduitId}
     * sont fournis par l'appelant, jamais devines ici : c'est
     * {@code agent.comptabilisation.ImputationSuggestionBuilder#eligibleNiveau3} qui a deja etabli,
     * par un historique coherent (jamais un appel LLM), que ce couple tiers/compte est fiable pour
     * CE document precis avant d'appeler cette methode - elle re-verifie neanmoins tout
     * independamment (montants, exercice ouvert, nature du compte) exactement comme la voie
     * manuelle {@link #comptabiliser}, jamais de confiance aveugle dans l'appelant.
     *
     * @throws ComptabilisationAutomatiqueIndisponibleException si les montants sont insuffisants/
     *         incoherents, la date de piece manquante, ou tout autre garde-fou deja applique par
     *         {@link #comptabiliser} echoue - degrade toujours proprement, ne propage jamais une
     *         {@link ResponseStatusException} (executee hors contexte HTTP).
     */
    @Transactional
    public EcritureComptableDto comptabiliserAutomatiquement(UUID entrepriseId, UUID documentId,
                                                               UUID tiersId, UUID compteChargeOuProduitId) {
        Document document = chargerDocument(entrepriseId, documentId);
        Entreprise entreprise = document.getEntreprise();

        try {
            TypeOperation operation = determinerOperation(document.getTypeDocument());

            BigDecimal montantHt = document.getMontantHt();
            BigDecimal montantTtc = document.getMontantTtc();
            BigDecimal montantTva = document.getMontantTva() != null ? document.getMontantTva() : BigDecimal.ZERO;
            if (montantHt == null || montantTtc == null) {
                throw new ComptabilisationAutomatiqueIndisponibleException(
                        "Montants insuffisants (HT/TTC) pour une comptabilisation automatique.");
            }
            CoherenceMontants.verifier(montantHt, montantTva, montantTtc, BigDecimal.ZERO).ifPresent(ecart -> {
                throw new ComptabilisationAutomatiqueIndisponibleException(
                        "Montants incoherents pour une comptabilisation automatique : " + ecart.explication() + ".");
            });

            LocalDate dateEcriture = document.getDatePiece();
            if (dateEcriture == null) {
                throw new ComptabilisationAutomatiqueIndisponibleException("Date de piece manquante.");
            }

            Tiers tiers = chargerTiers(entrepriseId, tiersId, operation);
            CompteComptable compteChargeOuProduit = chargerCompteChargeOuProduit(entrepriseId, compteChargeOuProduitId, operation);
            String libelle = document.getTypeDocument() + " - " + tiers.getNom();

            return confierAuMoteur(entreprise, document, operation, tiers, compteChargeOuProduit, dateEcriture,
                    libelle, montantHt, montantTva, montantTtc, null);
        } catch (ResponseStatusException e) {
            // chargerTiers/chargerCompteChargeOuProduit et le moteur levent des
            // ResponseStatusException (voie manuelle, contexte HTTP) : hors contexte HTTP, cette
            // methode ne doit jamais laisser une telle exception remonter jusqu'au pipeline.
            throw new ComptabilisationAutomatiqueIndisponibleException(
                    "Comptabilisation automatique impossible : " + e.getReason());
        }
    }

    /**
     * Seul endroit ou le flux Documents produit une ecriture : resolution du compte de TVA par
     * purpose, construction des lignes par la regle metier, puis proposition + validation en
     * BROUILLON par le moteur. {@code principal == null} = flux systeme (pipeline), sans utilisateur.
     */
    private EcritureComptableDto confierAuMoteur(Entreprise entreprise, Document document, TypeOperation operation,
                                                   Tiers tiers, CompteComptable compteChargeOuProduit, LocalDate dateEcriture,
                                                   String libelle, BigDecimal montantHt, BigDecimal montantTva,
                                                   BigDecimal montantTtc, UtilisateurPrincipal principal) {
        boolean achat = operation == TypeOperation.ACHAT;
        AccountingOperationType type = achat ? AccountingOperationType.ACHAT : AccountingOperationType.VENTE;

        Blocages explications = new Blocages();
        CompteComptable compteTva = null;
        if (montantTva.signum() > 0) {
            compteTva = resoudreCompteTva(entreprise, document, type, tiers, achat, explications, principal);
        }

        ResultatRegle resultat;
        if (explications.isEmpty()) {
            List<AccountingOperationProposalLigne> lignes = achat
                    ? achatRule.construireLignesAchatDepuisMontantsPrecalcules(tiers, compteChargeOuProduit, montantHt,
                            compteTva, montantTva, montantTtc, libelle)
                    : venteRule.construireLignesVenteDepuisMontantsPrecalcules(tiers, compteChargeOuProduit, montantHt,
                            compteTva, montantTva, montantTtc, libelle);
            resultat = new ResultatRegle(achat ? TypeJournal.ACHATS : TypeJournal.VENTES, lignes, List.of(), List.of());
        } else {
            // NOT_CONFIGURED / AMBIGUOUS / INVALID : aucune ligne devinee, la proposition reste en
            // NEEDS_REVIEW avec l'explication et le moteur refuse (422).
            resultat = new ResultatRegle(achat ? TypeJournal.ACHATS : TypeJournal.VENTES, List.of(), explications, List.of());
        }

        AccountingOperationProposalDto proposition = principal != null
                ? accountingOperationEngine.proposerEtValider(entreprise.getId(), type, tiers, dateEcriture, libelle,
                        null, resultat, AccountingSource.document(document.getId()), StatutEcriture.BROUILLON, principal)
                : accountingOperationEngine.proposerEtValiderSysteme(entreprise.getId(), type, tiers, dateEcriture,
                        libelle, null, resultat, AccountingSource.document(document.getId()), StatutEcriture.BROUILLON);

        var ecriture = ecritureComptableRepository.findById(proposition.ecritureId())
                .orElseThrow(() -> new IllegalStateException(
                        "Proposition validee mais ecriture introuvable : " + proposition.ecritureId()));
        document.setEcritureId(ecriture.getId());
        documentRepository.save(document);
        return EcritureComptableDto.from(ecriture);
    }

    /** TVA recuperable (achat) / collectee (vente) : le COMPTE vient du parametrage de l'entreprise
     * (puis du role systeme historique), jamais d'un numero ; le TAUX/montant reste un fait de la piece. */
    private CompteComptable resoudreCompteTva(Entreprise entreprise, Document document, AccountingOperationType type, Tiers tiers,
                                               boolean achat, Blocages explications, UtilisateurPrincipal principal) {
        AccountPurpose purpose = achat ? AccountPurpose.TVA_RECUPERABLE : AccountPurpose.TVA_COLLECTEE;
        AccountContext contexte = new AccountContext(type, null, null, tiers.getType().name(), entreprise.getPays(), null, Map.of());
        ResolvedAccount resolu = accountingAccountResolver.resolve(entreprise.getId(), purpose, contexte, null);
        if (!resolu.isResolved()) {
            explications.ajouter(resolu.code(), "Compte de " + (achat ? "TVA recuperable" : "TVA collectee")
                    + " non utilisable pour cette entreprise (" + resolu.status() + ") : " + resolu.explication());
            String action = switch (resolu.status()) {
                case AMBIGUOUS -> "COMPTE_AMBIGU";
                case INVALID -> "COMPTE_INVALIDE";
                default -> "COMPTE_NON_CONFIGURE";
            };
            // Transaction independante : le refus (422) annule la transaction courante.
            auditTrailService.enregistrerRefus(entreprise.getTenant() != null ? entreprise.getTenant().getId() : null,
                    principal != null ? principal.getId() : null, action, "DOCUMENT", document.getId().toString(),
                    Map.of("purpose", purpose.name(), "statut", resolu.status().name(), "explication", resolu.explication()));
            return null;
        }
        return compteComptableRepository.findById(resolu.compteId())
                .orElseThrow(() -> new IllegalStateException("Compte de TVA resolu mais introuvable : " + resolu.compteId()));
    }

    private Document chargerDocument(UUID entrepriseId, UUID documentId) {
        Document document = documentRepository.findById(documentId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Document introuvable"));
        if (!document.getEntreprise().getId().equals(entrepriseId)) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Document introuvable");
        }
        if (document.getEcritureId() != null) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Ce document a deja ete comptabilise");
        }
        if (document.getCibleId() != null
                && document.getStatutRapprochement() == com.example.comptable.document.domain.StatutRapprochementDocument.RAPPROCHE) {
            // La facture existe deja (et porte sa propre ecriture) : la comptabiliser aussi depuis la piece compterait deux fois.
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "Cette piece est rapprochee d'une facture deja comptabilisee : elle ne se comptabilise pas separement");
        }
        return document;
    }

    private TypeOperation determinerOperation(TypeDocument typeDocument) {
        return switch (typeDocument) {
            case FACTURE_ACHAT -> TypeOperation.ACHAT;
            case FACTURE_VENTE -> TypeOperation.VENTE;
            default -> throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY,
                    "Comptabilisation automatique non disponible pour " + typeDocument
                            + " ; seules les factures d'achat et de vente sont supportees pour le moment.");
        };
    }

    private Tiers chargerTiers(UUID entrepriseId, UUID tiersId, TypeOperation operation) {
        Tiers tiers = tiersRepository.findById(tiersId)
                .filter(t -> t.getEntreprise().getId().equals(entrepriseId))
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Tiers introuvable"));

        TypeTiers typeAttendu = operation == TypeOperation.ACHAT ? TypeTiers.FOURNISSEUR : TypeTiers.CLIENT;
        if (tiers.getType() != typeAttendu) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "Le tiers selectionne doit etre un " + typeAttendu.name().toLowerCase());
        }
        return tiers;
    }

    private CompteComptable chargerCompteChargeOuProduit(UUID entrepriseId, UUID compteId, TypeOperation operation) {
        CompteComptable compte = compteComptableRepository.findById(compteId)
                .filter(c -> c.getEntreprise().getId().equals(entrepriseId))
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Compte introuvable"));

        NatureCompte natureAttendue = operation == TypeOperation.ACHAT ? NatureCompte.CHARGE : NatureCompte.PRODUIT;
        if (compte.getNature() != natureAttendue) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "Le compte selectionne doit etre de nature " + natureAttendue);
        }
        return compte;
    }
}
