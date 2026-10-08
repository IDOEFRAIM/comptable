package com.example.comptable.document.hub;

import com.example.comptable.agent.finding.domain.Finding;
import com.example.comptable.agent.finding.domain.StatutFinding;
import com.example.comptable.agent.finding.repository.FindingRepository;
import com.example.comptable.comptabilite.domain.Facture;
import com.example.comptable.comptabilite.domain.Tiers;
import com.example.comptable.comptabilite.repository.FactureRepository;
import com.example.comptable.comptabilite.repository.TiersRepository;
import com.example.comptable.controle.ControlCodes;
import com.example.comptable.controle.ControlRunService;
import com.example.comptable.document.domain.CanalSource;
import com.example.comptable.document.domain.Document;
import com.example.comptable.document.domain.QualiteClassification;
import com.example.comptable.document.domain.StatutDocument;
import com.example.comptable.document.domain.StatutRapprochementDocument;
import com.example.comptable.document.domain.TypeDocument;
import com.example.comptable.document.hub.DocumentHubDto.Association;
import com.example.comptable.document.hub.DocumentHubDto.Ligne;
import com.example.comptable.document.repository.DocumentRepository;
import com.example.comptable.document.service.DocumentMatcher;
import com.example.comptable.entreprise.domain.Entreprise;
import com.example.comptable.entreprise.service.EntrepriseAccessService;
import com.example.comptable.security.UtilisateurPrincipal;
import com.example.comptable.stock.domain.FactureFournisseur;
import com.example.comptable.stock.repository.FactureFournisseurRepository;
import jakarta.persistence.criteria.Predicate;
import jakarta.persistence.criteria.Root;
import jakarta.persistence.criteria.Subquery;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.YearMonth;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * Hub documents (Phase C, lot C14) : « ce que j'ai recu, ce qui est traite, ce qui demande une decision ». Lecture seule :
 * pagination et filtres en base (Specification), compteurs par requetes de comptage, libelles (tiers, factures, constats)
 * charges par LOT pour toute une page - jamais un OCR, jamais un chargement complet, jamais une requete par ligne.
 */
@Service
public class DocumentHubService {

    /** Filtres cliquables du Hub ; les compteurs de la synthese portent les memes noms. */
    public enum Tuile { RECUS, TRAITES, A_VERIFIER, DOUBLONS, NON_RAPPROCHES, A_CONFIRMER }

    static final int TAILLE_MAX = 100;
    static final Set<String> CODES_DOUBLON = Set.of(ControlCodes.EXACT_DUPLICATE, ControlCodes.DUPLICATE_INVOICE, ControlCodes.POSSIBLE_DUPLICATE);

    public record Filtre(Tuile tuile, TypeDocument type, CanalSource canal, UUID tiersId, String periode, String q) {
    }

    private final EntrepriseAccessService entrepriseAccessService;
    private final DocumentRepository documentRepository;
    private final TiersRepository tiersRepository;
    private final FactureFournisseurRepository factureFournisseurRepository;
    private final FactureRepository factureRepository;
    private final FindingRepository findingRepository;
    private final ControlRunService controlRunService;

    public DocumentHubService(EntrepriseAccessService entrepriseAccessService, DocumentRepository documentRepository,
                              TiersRepository tiersRepository, FactureFournisseurRepository factureFournisseurRepository,
                              FactureRepository factureRepository, FindingRepository findingRepository,
                              ControlRunService controlRunService) {
        this.entrepriseAccessService = entrepriseAccessService;
        this.documentRepository = documentRepository;
        this.tiersRepository = tiersRepository;
        this.factureFournisseurRepository = factureFournisseurRepository;
        this.factureRepository = factureRepository;
        this.findingRepository = findingRepository;
        this.controlRunService = controlRunService;
    }

    @Transactional
    public DocumentHubDto.Synthese synthese(UUID entrepriseId, String periode, UtilisateurPrincipal principal) {
        Entreprise entreprise = entrepriseAccessService.verifierAcces(entrepriseId, principal);
        controlRunService.executerSiPerime(entreprise);

        long recus = documentRepository.count(specification(entrepriseId, new Filtre(Tuile.RECUS, null, null, null, periode, null)));
        long traites = documentRepository.count(specification(entrepriseId, new Filtre(Tuile.TRAITES, null, null, null, periode, null)));
        long aVerifier = documentRepository.count(specification(entrepriseId, new Filtre(Tuile.A_VERIFIER, null, null, null, periode, null)));
        long aConfirmer = documentRepository.count(specification(entrepriseId, new Filtre(Tuile.A_CONFIRMER, null, null, null, periode, null)));
        long nonRapproches = documentRepository.count(specification(entrepriseId, new Filtre(Tuile.NON_RAPPROCHES, null, null, null, periode, null)));
        long doublons = documentRepository.count(specification(entrepriseId, new Filtre(Tuile.DOUBLONS, null, null, null, periode, null)));
        long automatiques = documentRepository.count(specification(entrepriseId, new Filtre(Tuile.RECUS, null, null, null, periode, null))
                .and((root, query, cb) -> cb.and(cb.equal(root.get("rapprochePar"), com.example.comptable.document.domain.OrigineRapprochement.SYSTEME),
                        cb.isNull(root.get("valideAt")))));

        // Pieces manquantes : operations sans justificatif (constats actifs), agregees ou non.
        long manquantes = 0;
        for (Finding f : findingRepository.findActifsParCodes(entrepriseId, List.of(ControlCodes.MISSING_DOCUMENT))) {
            Object n = f.getEvidenceJson() != null ? f.getEvidenceJson().get("nbEcritures") : null;
            manquantes += n instanceof Number nombre ? nombre.longValue() : 1;
        }
        return new DocumentHubDto.Synthese(periode, recus, traites, aVerifier, doublons, nonRapproches, manquantes, aConfirmer, automatiques);
    }

    @Transactional(readOnly = true)
    public DocumentHubDto.Page lister(UUID entrepriseId, Filtre filtre, int page, int taille, UtilisateurPrincipal principal) {
        entrepriseAccessService.verifierAcces(entrepriseId, principal);
        int tailleBornee = Math.max(1, Math.min(TAILLE_MAX, taille));
        Page<Document> resultat = documentRepository.findAll(specification(entrepriseId, filtre),
                PageRequest.of(Math.max(0, page), tailleBornee, Sort.by(Sort.Direction.DESC, "createdAt").and(Sort.by("id"))));
        return new DocumentHubDto.Page(lignes(entrepriseId, resultat.getContent()), resultat.getNumber(), resultat.getSize(),
                resultat.getTotalElements(), resultat.getTotalPages());
    }

    /** Pieces susceptibles d'etre l'ORIGINAL de celle-ci (meme fichier, meme facture, meme tiers et montant) pour « marquer doublon ». */
    @Transactional(readOnly = true)
    public List<DocumentHubDto.DocumentSimple> originauxPossibles(UUID entrepriseId, UUID documentId, UtilisateurPrincipal principal) {
        entrepriseAccessService.verifierAcces(entrepriseId, principal);
        Document d = documentRepository.findByIdAndEntrepriseId(documentId, entrepriseId)
                .orElseThrow(() -> new org.springframework.web.server.ResponseStatusException(org.springframework.http.HttpStatus.NOT_FOUND, "Document introuvable"));
        List<DocumentHubDto.DocumentSimple> originaux = new ArrayList<>();
        Specification<Document> autres = (root, query, cb) -> {
            List<Predicate> ou = new ArrayList<>();
            if (d.getContentHash() != null) {
                ou.add(cb.equal(root.get("contentHash"), d.getContentHash()));
            }
            if (d.getTiersId() != null && d.getNumeroFactureNormalise() != null) {
                ou.add(cb.and(cb.equal(root.get("tiersId"), d.getTiersId()), cb.equal(root.get("numeroFactureNormalise"), d.getNumeroFactureNormalise())));
            }
            if (d.getTiersId() != null && d.getMontantTtc() != null) {
                ou.add(cb.and(cb.equal(root.get("tiersId"), d.getTiersId()), cb.equal(root.get("montantTtc"), d.getMontantTtc())));
            }
            if (ou.isEmpty()) {
                return cb.disjunction();
            }
            return cb.and(cb.equal(root.get("entreprise").get("id"), entrepriseId), cb.notEqual(root.get("id"), d.getId()),
                    cb.isNull(root.get("doublonDeId")), cb.or(ou.toArray(new Predicate[0])));
        };
        for (Document o : documentRepository.findAll(autres, Sort.by("createdAt"))) {
            String raison = d.getContentHash() != null && d.getContentHash().equals(o.getContentHash()) ? "meme fichier"
                    : d.getNumeroFactureNormalise() != null && d.getNumeroFactureNormalise().equals(o.getNumeroFactureNormalise()) ? "meme numero de facture"
                    : "meme tiers et meme montant";
            originaux.add(new DocumentHubDto.DocumentSimple(o.getId(), o.getNomFichierOriginal(), o.getCreatedAt(), o.getMontantTtc(), o.getNumeroFacture(), raison));
        }
        return originaux;
    }

    // ------------------------------------------------------------------------------------------ specifications

    static Specification<Document> specification(UUID entrepriseId, Filtre f) {
        return (root, query, cb) -> {
            List<Predicate> p = new ArrayList<>();
            p.add(cb.equal(root.get("entreprise").get("id"), entrepriseId));
            if (f.periode() != null && !f.periode().isBlank()) {
                YearMonth mois = YearMonth.parse(f.periode());
                p.add(cb.greaterThanOrEqualTo(root.get("createdAt"), mois.atDay(1).atStartOfDay().atOffset(ZoneOffset.UTC)));
                p.add(cb.lessThan(root.get("createdAt"), mois.plusMonths(1).atDay(1).atStartOfDay().atOffset(ZoneOffset.UTC)));
            }
            if (f.type() != null) {
                p.add(cb.equal(root.get("typeDocument"), f.type()));
            }
            if (f.canal() != null) {
                p.add(cb.equal(root.get("canalSource"), f.canal()));
            }
            if (f.tiersId() != null) {
                p.add(cb.equal(root.get("tiersId"), f.tiersId()));
            }
            if (f.q() != null && !f.q().isBlank()) {
                String motif = "%" + f.q().trim().toLowerCase() + "%";
                String normalise = com.example.comptable.document.service.NumeroFactureNormalizer.normaliser(f.q());
                List<Predicate> ou = new ArrayList<>();
                ou.add(cb.like(cb.lower(cb.coalesce(root.<String>get("nomFichierOriginal"), "")), motif));
                ou.add(cb.like(cb.lower(cb.coalesce(root.<String>get("numeroFacture"), "")), motif));
                if (normalise != null) {
                    ou.add(cb.like(root.<String>get("numeroFactureNormalise"), "%" + normalise + "%"));
                }
                p.add(cb.or(ou.toArray(new Predicate[0])));
            }
            Predicate pasDoublonConfirme = cb.isNull(root.get("doublonDeId"));
            Predicate facture = root.get("typeDocument").in(TypeDocument.FACTURE_ACHAT, TypeDocument.FACTURE_VENTE);
            if (f.tuile() != null) {
                switch (f.tuile()) {
                    case RECUS -> {
                    }
                    case TRAITES -> {
                        p.add(pasDoublonConfirme);
                        p.add(cb.notEqual(root.get("statut"), StatutDocument.ERREUR));
                        p.add(cb.or(cb.equal(root.get("statutRapprochement"), StatutRapprochementDocument.RAPPROCHE),
                                cb.isNotNull(root.get("ecritureId")), cb.isNotNull(root.get("valideAt"))));
                    }
                    case A_VERIFIER -> {
                        p.add(pasDoublonConfirme);
                        p.add(cb.isNull(root.get("valideAt")));
                        p.add(cb.or(
                                root.get("statut").in(StatutDocument.EN_ATTENTE_VALIDATION_HUMAINE, StatutDocument.ERREUR),
                                root.get("statutRapprochement").in(StatutRapprochementDocument.A_CONFIRMER, StatutRapprochementDocument.AMBIGU),
                                cb.and(facture, cb.isNull(root.get("ecritureId")),
                                        cb.notEqual(root.get("statutRapprochement"), StatutRapprochementDocument.RAPPROCHE),
                                        root.get("statutClassification").in(QualiteClassification.PROBABLE, QualiteClassification.AMBIGU, QualiteClassification.INCONNU))));
                    }
                    case A_CONFIRMER -> {
                        p.add(pasDoublonConfirme);
                        p.add(cb.equal(root.get("statutRapprochement"), StatutRapprochementDocument.A_CONFIRMER));
                    }
                    case NON_RAPPROCHES -> {
                        p.add(pasDoublonConfirme);
                        p.add(facture);
                        p.add(cb.isNull(root.get("ecritureId")));
                        p.add(cb.isNull(root.get("cibleId")));
                        p.add(cb.equal(root.get("statut"), StatutDocument.TRAITE));
                        p.add(cb.equal(root.get("statutRapprochement"), StatutRapprochementDocument.NON_RAPPROCHE));
                    }
                    case DOUBLONS -> {
                        Subquery<Long> sq = query.subquery(Long.class);
                        Root<Finding> constat = sq.from(Finding.class);
                        sq.select(cb.literal(1L)).where(
                                cb.equal(constat.get("entreprise").get("id"), root.get("entreprise").get("id")),
                                cb.equal(constat.get("sourceType"), "DOCUMENT"),
                                cb.equal(constat.get("sourceId"), cb.function("str", String.class, root.get("id"))),
                                constat.get("controlCode").in(CODES_DOUBLON),
                                constat.get("status").in(StatutFinding.OPEN, StatutFinding.IN_PROGRESS));
                        p.add(cb.exists(sq));
                    }
                }
            }
            return cb.and(p.toArray(new Predicate[0]));
        };
    }

    // ------------------------------------------------------------------------------------------ lignes (chargement par lot)

    private List<Ligne> lignes(UUID entrepriseId, List<Document> documents) {
        if (documents.isEmpty()) {
            return List.of();
        }
        Set<UUID> tiersIds = documents.stream().map(Document::getTiersId).filter(java.util.Objects::nonNull).collect(Collectors.toSet());
        Map<UUID, String> noms = tiersRepository.findAllById(tiersIds).stream()
                .filter(t -> t.getEntreprise().getId().equals(entrepriseId))
                .collect(Collectors.toMap(Tiers::getId, Tiers::getNom));

        Map<UUID, FactureFournisseur> achats = factureFournisseurRepository.findAllById(idsCible(documents, DocumentMatcher.CIBLE_FACTURE_FOURNISSEUR)).stream()
                .collect(Collectors.toMap(FactureFournisseur::getId, f -> f));
        Map<UUID, Facture> ventes = factureRepository.findAllById(idsCible(documents, DocumentMatcher.CIBLE_FACTURE)).stream()
                .collect(Collectors.toMap(Facture::getId, f -> f));

        Map<String, List<String>> constats = new HashMap<>();
        for (Finding f : findingRepository.findActifsParObjets(entrepriseId, "DOCUMENT",
                documents.stream().map(d -> d.getId().toString()).toList())) {
            constats.computeIfAbsent(f.getSourceId(), k -> new ArrayList<>()).add(f.getControlCode());
        }

        List<Ligne> lignes = new ArrayList<>();
        for (Document d : documents) {
            Association association = null;
            if (d.getCibleId() != null) {
                if (DocumentMatcher.CIBLE_FACTURE_FOURNISSEUR.equals(d.getCibleType()) && achats.containsKey(d.getCibleId())) {
                    FactureFournisseur f = achats.get(d.getCibleId());
                    association = new Association(d.getCibleType(), f.getId(), "Facture fournisseur " + f.getNumero()
                            + (f.getNumeroFournisseur() != null ? " (n° " + f.getNumeroFournisseur() + ")" : ""));
                } else if (DocumentMatcher.CIBLE_FACTURE.equals(d.getCibleType()) && ventes.containsKey(d.getCibleId())) {
                    association = new Association(d.getCibleType(), d.getCibleId(), "Facture " + ventes.get(d.getCibleId()).getNumero());
                }
            }
            lignes.add(new Ligne(d.getId(), d.getTypeDocument().name(), d.getCanalSource().name(), d.getCreatedAt(), d.getDatePiece(),
                    d.getTiersId(), noms.get(d.getTiersId()), com.example.comptable.document.service.DocumentClassifier.nomExtrait(d),
                    d.getNumeroFacture(), d.getMontantTtc(), d.getDevise(), d.getStatut().name(), d.getStatutRapprochement().name(),
                    association, d.getEcritureId(), d.getStatutClassification() != null ? d.getStatutClassification().name() : null,
                    d.getConfianceTiers(), d.getConfianceRapprochement(), d.getValideAt() != null, d.getDoublonDeId() != null,
                    constats.getOrDefault(d.getId().toString(), List.of())));
        }
        return lignes;
    }

    private Collection<UUID> idsCible(List<Document> documents, String type) {
        return documents.stream().filter(d -> type.equals(d.getCibleType()) && d.getCibleId() != null).map(Document::getCibleId)
                .collect(Collectors.toSet());
    }

    /** Premier jour du mois courant (valeur par defaut de la periode affichee). */
    public static String periodeCourante() {
        return YearMonth.from(LocalDate.now()).toString();
    }
}
