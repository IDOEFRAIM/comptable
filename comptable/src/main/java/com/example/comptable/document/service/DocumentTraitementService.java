package com.example.comptable.document.service;

import com.example.comptable.agent.finding.FindingService;
import com.example.comptable.audit.AuditTrailService;
import com.example.comptable.comptabilite.domain.Facture;
import com.example.comptable.document.domain.Document;
import com.example.comptable.document.domain.OrigineRapprochement;
import com.example.comptable.document.domain.StatutRapprochementDocument;
import com.example.comptable.document.domain.TypeDocument;
import com.example.comptable.document.repository.DocumentRepository;
import com.example.comptable.document.service.DocumentMatcher.Candidat;
import com.example.comptable.comptabilite.repository.FactureRepository;
import com.example.comptable.entreprise.service.EntrepriseAccessService;
import com.example.comptable.security.UtilisateurPrincipal;
import com.example.comptable.stock.domain.FactureFournisseur;
import com.example.comptable.stock.repository.FactureFournisseurRepository;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Decisions HUMAINES sur une piece : associer a une facture (parmi des candidates, jamais un identifiant saisi), rejeter
 * une proposition, marquer un doublon, valider, retraiter. Chaque action verifie l'entreprise (404 sinon), refuse
 * l'incoherent (409/422) et est tracee. Ces actions ne creent jamais d'ecriture : elles rattachent ou ecartent.
 */
@Service
public class DocumentTraitementService {

    private final EntrepriseAccessService entrepriseAccessService;
    private final DocumentRepository documentRepository;
    private final DocumentMatcher matcher;
    private final DocumentPipeline pipeline;
    private final FactureFournisseurRepository factureFournisseurRepository;
    private final FactureRepository factureRepository;
    private final FindingService findingService;
    private final AuditTrailService auditTrailService;
    private final com.example.comptable.demande.repository.DemandePieceRepository demandePieceRepository;
    private final PieceEcritureLinker pieceEcritureLinker;
    private final com.example.comptable.collecte.service.DemandeMatcher demandeMatcher;

    public DocumentTraitementService(EntrepriseAccessService entrepriseAccessService, DocumentRepository documentRepository,
                                     DocumentMatcher matcher, DocumentPipeline pipeline,
                                     FactureFournisseurRepository factureFournisseurRepository, FactureRepository factureRepository,
                                     FindingService findingService, AuditTrailService auditTrailService,
                                     com.example.comptable.demande.repository.DemandePieceRepository demandePieceRepository,
                                     PieceEcritureLinker pieceEcritureLinker, com.example.comptable.collecte.service.DemandeMatcher demandeMatcher) {
        this.entrepriseAccessService = entrepriseAccessService;
        this.documentRepository = documentRepository;
        this.matcher = matcher;
        this.pipeline = pipeline;
        this.factureFournisseurRepository = factureFournisseurRepository;
        this.factureRepository = factureRepository;
        this.findingService = findingService;
        this.auditTrailService = auditTrailService;
        this.demandePieceRepository = demandePieceRepository;
        this.pieceEcritureLinker = pieceEcritureLinker;
        this.demandeMatcher = demandeMatcher;
    }

    /** Candidates proposees dans le selecteur « Associer a une facture ». */
    @Transactional(readOnly = true)
    public List<Candidat> candidats(UUID entrepriseId, UUID documentId, UtilisateurPrincipal principal) {
        entrepriseAccessService.verifierAcces(entrepriseId, principal);
        Document document = charger(entrepriseId, documentId);
        return matcher.tous(document);
    }

    @Transactional
    public Document associer(UUID entrepriseId, UUID documentId, String cibleType, UUID cibleId, UtilisateurPrincipal principal) {
        entrepriseAccessService.verifierAcces(entrepriseId, principal);
        Document document = chargerActif(entrepriseId, documentId);
        Candidat cible = resoudreCible(document, entrepriseId, cibleType, cibleId);
        if (document.getStatutRapprochement() == StatutRapprochementDocument.RAPPROCHE && cibleId.equals(document.getCibleId())) {
            return document; // idempotent
        }
        boolean dejaPrise = !documentRepository.findByEntrepriseIdAndCibleTypeAndCibleId(entrepriseId, cibleType, cibleId).stream()
                .filter(d -> !d.getId().equals(documentId) && d.getStatutRapprochement() == StatutRapprochementDocument.RAPPROCHE)
                .toList().isEmpty();
        if (dejaPrise) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Cette facture est deja rapprochee d'une autre piece");
        }
        if (document.getEcritureId() != null && cible.ecritureId() != null && !document.getEcritureId().equals(cible.ecritureId())) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "Cette piece justifie deja une autre ecriture : elle ne peut pas etre rapprochee de cette facture");
        }
        pipeline.lier(document, cible, OrigineRapprochement.HUMAIN, 1.0, principal.getId());
        auditTrailService.enregistrerActionHumaine(principal.getTenantId(), principal.getId(), "DOCUMENT_RAPPROCHE", "DOCUMENT",
                documentId.toString(), Map.of("cibleType", cibleType, "cibleId", cibleId.toString(), "niveau", cible.niveau().name()));
        findingService.resoudreParSource(entrepriseId, "DOCUMENT", documentId.toString());
        return document;
    }

    /** L'utilisateur refuse la candidate proposee : elle ne sera plus reproposee pour cette piece. */
    @Transactional
    public Document rejeterProposition(UUID entrepriseId, UUID documentId, UtilisateurPrincipal principal) {
        entrepriseAccessService.verifierAcces(entrepriseId, principal);
        Document document = chargerActif(entrepriseId, documentId);
        if (document.getStatutRapprochement() != StatutRapprochementDocument.A_CONFIRMER) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Aucune proposition a rejeter pour cette piece");
        }
        UUID rejetee = document.getCibleId();
        document.setCibleRejeteeId(rejetee);
        document.setCibleType(null);
        document.setCibleId(null);
        document.setConfianceRapprochement(null);
        document.setStatutRapprochement(StatutRapprochementDocument.NON_RAPPROCHE);
        documentRepository.save(document);
        auditTrailService.enregistrerActionHumaine(principal.getTenantId(), principal.getId(), "DOCUMENT_RAPPROCHEMENT_REJETE", "DOCUMENT",
                documentId.toString(), Map.of("cibleRejeteeId", String.valueOf(rejetee)));
        return document;
    }

    /** Confirme que cette piece est un doublon de {@code doublonDeId} : elle sort du travail a faire, sans etre supprimee. */
    @Transactional
    public Document marquerDoublon(UUID entrepriseId, UUID documentId, UUID doublonDeId, UtilisateurPrincipal principal) {
        entrepriseAccessService.verifierAcces(entrepriseId, principal);
        if (documentId.equals(doublonDeId)) {
            throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY, "Une piece ne peut pas etre le doublon d'elle-meme");
        }
        Document document = charger(entrepriseId, documentId);
        Document original = charger(entrepriseId, doublonDeId);
        if (original.getDoublonDeId() != null) {
            throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY, "L'original choisi est lui-meme un doublon : choisissez la piece de reference");
        }
        if (document.getEcritureId() != null && original.getEcritureId() == null) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Cette piece porte deja une ecriture : l'original doit etre celle-ci");
        }
        document.setDoublonDeId(doublonDeId);
        document.setStatutRapprochement(StatutRapprochementDocument.NON_RAPPROCHE);
        if (document.getEcritureId() == null) {
            document.setCibleType(null);
            document.setCibleId(null);
        }
        documentRepository.save(document);
        findingService.resoudreParSource(entrepriseId, "DOCUMENT", documentId.toString());
        auditTrailService.enregistrerActionHumaine(principal.getTenantId(), principal.getId(), "DOCUMENT_MARQUE_DOUBLON", "DOCUMENT",
                documentId.toString(), Map.of("doublonDeId", doublonDeId.toString()));
        return document;
    }

    /** Valide la piece : confirme la proposition en attente, ou clot la revue d'une piece deja rapprochee / sans association a faire. */
    @Transactional
    public Document valider(UUID entrepriseId, UUID documentId, UtilisateurPrincipal principal) {
        entrepriseAccessService.verifierAcces(entrepriseId, principal);
        Document document = chargerActif(entrepriseId, documentId);
        validerInterne(document, entrepriseId, principal);
        return document;
    }

    /**
     * Validation en masse : uniquement les pieces dont le rapprochement est CERTAIN (deja rapproche automatiquement, non
     * encore valide). Les identifiants viennent de l'apercu ({@link #apercuValidationLot}) que l'utilisateur a vu ; toute
     * piece qui n'est plus dans cet etat est ignoree et signalee, jamais validee a l'aveugle.
     */
    @Transactional
    public LotResultat validerLot(UUID entrepriseId, List<UUID> documentIds, UtilisateurPrincipal principal) {
        entrepriseAccessService.verifierAcces(entrepriseId, principal);
        List<UUID> valides = new ArrayList<>();
        List<UUID> ignores = new ArrayList<>();
        for (UUID id : documentIds) {
            Document document = documentRepository.findByIdAndEntrepriseId(id, entrepriseId).orElse(null);
            if (document == null || !estValidableEnLot(document)) {
                ignores.add(id);
                continue;
            }
            validerInterne(document, entrepriseId, principal);
            valides.add(id);
        }
        return new LotResultat(valides, ignores);
    }

    public record LotResultat(List<UUID> valides, List<UUID> ignores) {
    }

    @Transactional(readOnly = true)
    public List<Document> apercuValidationLot(UUID entrepriseId, UtilisateurPrincipal principal) {
        entrepriseAccessService.verifierAcces(entrepriseId, principal);
        return documentRepository.rechercherRapprochesNonValides(entrepriseId);
    }

    /** Demande candidate pour une piece recue, avec le niveau auquel elle correspond (selecteur : aucun identifiant a saisir). */
    public record CandidateDemande(java.util.UUID demandeId, String description, java.math.BigDecimal montant, java.time.LocalDate date,
                                   String tiers, String statut, String niveau) {
    }

    /** Demandes de pieces ouvertes de l'entreprise : EXACT / PROBABLE (regle du matcher) d'abord, puis les autres (POSSIBLE). */
    @Transactional(readOnly = true)
    public List<CandidateDemande> candidatsDemandes(UUID entrepriseId, UUID documentId, UtilisateurPrincipal principal) {
        entrepriseAccessService.verifierAcces(entrepriseId, principal);
        Document document = charger(entrepriseId, documentId);
        com.example.comptable.collecte.service.DemandeMatcher.Proposition p = demandeMatcher.proposer(document, document.getDemandeId());
        java.util.Set<UUID> retenues = p.candidates().stream().map(com.example.comptable.demande.domain.DemandePiece::getId)
                .collect(java.util.stream.Collectors.toSet());
        String niveau = switch (p.decision()) {
            case EXACT -> "EXACT";
            case AMBIGUOUS -> "AMBIGUOUS";
            default -> "PROBABLE";
        };
        return demandePieceRepository.findOuvertes(entrepriseId, java.util.EnumSet.of(com.example.comptable.demande.domain.StatutDemandePiece.A_ENVOYER,
                        com.example.comptable.demande.domain.StatutDemandePiece.EN_ATTENTE, com.example.comptable.demande.domain.StatutDemandePiece.RECU,
                        com.example.comptable.demande.domain.StatutDemandePiece.ECHEC_ENVOI)).stream()
                .map(d -> new CandidateDemande(d.getId(), d.getDescriptionOperation() != null ? d.getDescriptionOperation() : d.getLibelle(), d.getMontant(),
                        d.getDateOperation(), d.getTiersNom(), d.getStatut().name(), retenues.contains(d.getId()) ? niveau : "POSSIBLE"))
                .sorted(java.util.Comparator.comparing((CandidateDemande c) -> "POSSIBLE".equals(c.niveau()))
                        .thenComparing(CandidateDemande::date, java.util.Comparator.nullsLast(java.util.Comparator.naturalOrder())))
                .toList();
    }

    /**
     * Le comptable associe la piece a une demande (confirmation d'une proposition, ou choix parmi des candidates ambigues).
     * Decision HUMAINE : le constat de piece manquante est resolu par l'utilisateur. Refuse une demande d'une autre entreprise (404),
     * une demande close (409) et une ecriture deja justifiee par une autre piece (409).
     */
    @Transactional
    public Document associerDemande(UUID entrepriseId, UUID documentId, UUID demandeId, UtilisateurPrincipal principal) {
        entrepriseAccessService.verifierAcces(entrepriseId, principal);
        Document document = chargerActif(entrepriseId, documentId);
        com.example.comptable.demande.domain.DemandePiece demande = demandePieceRepository.findByIdAndEntrepriseId(demandeId, entrepriseId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Demande introuvable"));
        if (!demande.getStatut().estOuverte()) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Cette demande est deja close");
        }
        if (demande.getEcritureId() != null) {
            if (!pieceEcritureLinker.lier(document, demande.getEcritureId(), principal.getId())) {
                throw new ResponseStatusException(HttpStatus.CONFLICT, "Cette operation est deja justifiee par une autre piece");
            }
        } else {
            java.time.OffsetDateTime maintenant = java.time.OffsetDateTime.now();
            demande.setStatut(com.example.comptable.demande.domain.StatutDemandePiece.RAPPROCHEE);
            demande.setDocumentId(document.getId());
            demande.setReceivedAt(maintenant);
            demande.setResolueAt(maintenant);
            demande.setResoluPar("HUMAIN");
            demande.setProchaineRelanceAt(null);
            demandePieceRepository.save(demande);
            document.setDemandeId(demande.getId());
            document.setStatutDemande(com.example.comptable.document.domain.StatutDemandeDocument.LIEE);
            documentRepository.save(document);
        }
        findingService.resoudreParSource(entrepriseId, "DOCUMENT", documentId.toString());
        return document;
    }

    /** Relance l'identification et le rapprochement (apres creation du fournisseur, par exemple). */
    @Transactional
    public Document retraiter(UUID entrepriseId, UUID documentId, UtilisateurPrincipal principal) {
        entrepriseAccessService.verifierAcces(entrepriseId, principal);
        Document document = chargerActif(entrepriseId, documentId);
        if (document.getStatutRapprochement() == StatutRapprochementDocument.RAPPROCHE) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Cette piece est deja rapprochee");
        }
        // Le refus d'une candidate est durable : un retraitement ne la repropose pas.
        pipeline.analyser(document);
        return document;
    }

    static boolean estValidableEnLot(Document d) {
        return d.getStatutRapprochement() == StatutRapprochementDocument.RAPPROCHE && d.getValideAt() == null
                && d.getRapprochePar() == OrigineRapprochement.SYSTEME && d.getDoublonDeId() == null;
    }

    private void validerInterne(Document document, UUID entrepriseId, UtilisateurPrincipal principal) {
        if (document.getStatutRapprochement() == StatutRapprochementDocument.AMBIGU) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "Plusieurs factures correspondent : associez la piece a la bonne facture avant de la valider");
        }
        if (document.getStatutRapprochement() == StatutRapprochementDocument.A_CONFIRMER && document.getCibleId() != null) {
            Candidat cible = resoudreCible(document, entrepriseId, document.getCibleType(), document.getCibleId());
            pipeline.lier(document, cible, OrigineRapprochement.HUMAIN, 1.0, principal.getId());
        }
        document.setValideAt(OffsetDateTime.now());
        document.setValidePar(principal.getId());
        documentRepository.save(document);
        auditTrailService.enregistrerActionHumaine(principal.getTenantId(), principal.getId(), "DOCUMENT_VALIDE", "DOCUMENT",
                document.getId().toString(), Map.of("statutRapprochement", document.getStatutRapprochement().name()));
    }

    private Candidat resoudreCible(Document document, UUID entrepriseId, String cibleType, UUID cibleId) {
        if (DocumentMatcher.CIBLE_FACTURE_FOURNISSEUR.equals(cibleType)) {
            if (document.getTypeDocument() != TypeDocument.FACTURE_ACHAT) {
                throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY, "Seule une facture d'achat se rapproche d'une facture fournisseur");
            }
            FactureFournisseur f = factureFournisseurRepository.findByIdAndEntrepriseId(cibleId, entrepriseId)
                    .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Facture introuvable"));
            return matcher.tous(document).stream().filter(c -> c.id().equals(f.getId())).findFirst()
                    .orElseGet(() -> new Candidat(cibleType, f.getId(), f.getNumero(), f.getNumeroFournisseur(), f.getDateFacture(),
                            f.getMontantTtc(), f.getEcritureId(), DocumentMatcher.Niveau.POSSIBLE, "choix de l'utilisateur"));
        }
        if (DocumentMatcher.CIBLE_FACTURE.equals(cibleType)) {
            if (document.getTypeDocument() != TypeDocument.FACTURE_VENTE) {
                throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY, "Seule une facture de vente se rapproche d'une facture client");
            }
            Facture f = factureRepository.chargerAvecLignes(cibleId, entrepriseId)
                    .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Facture introuvable"));
            return new Candidat(cibleType, f.getId(), f.getNumero(), null, f.getDateEmission(), f.montantTtc(), f.getEcritureId(),
                    DocumentMatcher.Niveau.POSSIBLE, "choix de l'utilisateur");
        }
        throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY, "Type de cible inconnu : " + cibleType);
    }

    private Document charger(UUID entrepriseId, UUID documentId) {
        return documentRepository.findByIdAndEntrepriseId(documentId, entrepriseId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Document introuvable"));
    }

    private Document chargerActif(UUID entrepriseId, UUID documentId) {
        Document document = charger(entrepriseId, documentId);
        if (document.getDoublonDeId() != null) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Cette piece est un doublon confirme : aucune action possible");
        }
        return document;
    }
}
