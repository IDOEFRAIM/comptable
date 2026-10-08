package com.example.comptable.collecte.service;

import com.example.comptable.agent.finding.FindingService;
import com.example.comptable.agent.finding.domain.Finding;
import com.example.comptable.agent.finding.domain.StatutFinding;
import com.example.comptable.agent.finding.repository.FindingRepository;
import com.example.comptable.audit.AuditTrailService;
import com.example.comptable.collecte.domain.CanalMessage;
import com.example.comptable.collecte.domain.DemandeMessage;
import com.example.comptable.collecte.domain.OutboundMessage;
import com.example.comptable.collecte.domain.StatutMessage;
import com.example.comptable.collecte.domain.TypeMessage;
import com.example.comptable.collecte.repository.DemandeMessageRepository;
import com.example.comptable.collecte.repository.OutboundMessageRepository;
import com.example.comptable.controle.ControlCodes;
import com.example.comptable.demande.domain.DemandePiece;
import com.example.comptable.demande.domain.StatutDemandePiece;
import com.example.comptable.demande.repository.DemandePieceRepository;
import com.example.comptable.entreprise.domain.Entreprise;
import com.example.comptable.entreprise.service.EntrepriseAccessService;
import com.example.comptable.identite.repository.UtilisateurRepository;
import com.example.comptable.security.UtilisateurPrincipal;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * Collecte active des pieces : une demande de piece produit un VRAI message (boite d'envoi), regroupe par interlocuteur, une
 * seule fois (idempotent), et reste tracable demande par demande. Ne cree jamais d'ecriture ; ne marque jamais « recu » sans
 * piece ou decision humaine.
 */
@Service
public class CollecteService {

    private static final EnumSet<StatutDemandePiece> OUVERTES = EnumSet.of(StatutDemandePiece.A_ENVOYER, StatutDemandePiece.EN_ATTENTE,
            StatutDemandePiece.RECU, StatutDemandePiece.ECHEC_ENVOI);

    /** Un element a demander, vu par le comptable (jamais l'identifiant technique cote client). */
    public record ElementApercu(UUID findingId, String libelle, BigDecimal montant, LocalDate date, String tiers, boolean dejaDemande) {
    }

    public record MessageApercu(String destinataire, String telephone, int nbDemandes, String texte) {
    }

    /** Apercu AVANT envoi : ce qui sera demande, a qui, avec quel texte. {@code blocage} non nul = rien ne pourra partir (et pourquoi). */
    public record Apercu(List<ElementApercu> elements, List<MessageApercu> messages, String blocage, int ignores) {
    }

    public record ResultatDemande(List<UUID> demandeIds, List<UUID> messageIds, int ignores, String blocage) {
    }

    private final EntrepriseAccessService entrepriseAccessService;
    private final DemandePieceRepository demandePieceRepository;
    private final FindingRepository findingRepository;
    private final FindingService findingService;
    private final OutboundMessageRepository messageRepository;
    private final DemandeMessageRepository demandeMessageRepository;
    private final ContactResolver contactResolver;
    private final CollecteConfig config;
    private final DepotLienService depotLienService;
    private final MessageTemplates templates;
    private final AuditTrailService auditTrailService;
    private final UtilisateurRepository utilisateurRepository;
    private final OutboundMessageWorker worker;

    public CollecteService(EntrepriseAccessService entrepriseAccessService, DemandePieceRepository demandePieceRepository,
                           FindingRepository findingRepository, FindingService findingService,
                           OutboundMessageRepository messageRepository, DemandeMessageRepository demandeMessageRepository,
                           ContactResolver contactResolver, CollecteConfig config, DepotLienService depotLienService,
                           MessageTemplates templates, AuditTrailService auditTrailService,
                           UtilisateurRepository utilisateurRepository, OutboundMessageWorker worker) {
        this.entrepriseAccessService = entrepriseAccessService;
        this.demandePieceRepository = demandePieceRepository;
        this.findingRepository = findingRepository;
        this.findingService = findingService;
        this.messageRepository = messageRepository;
        this.demandeMessageRepository = demandeMessageRepository;
        this.contactResolver = contactResolver;
        this.config = config;
        this.depotLienService = depotLienService;
        this.templates = templates;
        this.auditTrailService = auditTrailService;
        this.utilisateurRepository = utilisateurRepository;
        this.worker = worker;
    }

    // ------------------------------------------------------------------------------------------- demander (une ou plusieurs)

    /** Apercu d'une demande groupee : rien n'est cree ni envoye. */
    @Transactional(readOnly = true)
    public Apercu apercu(UUID entrepriseId, List<UUID> findingIds, UtilisateurPrincipal principal) {
        Entreprise entreprise = entrepriseAccessService.verifierAcces(entrepriseId, principal);
        List<Finding> constats = constatsValides(entrepriseId, findingIds);
        List<ElementApercu> elements = new ArrayList<>();
        List<DemandePiece> fictives = new ArrayList<>();
        int ignores = findingIds.size() - constats.size();
        for (Finding f : constats) {
            boolean deja = demandePieceRepository.findOuverteParEcriture(entrepriseId, UUID.fromString(f.getSourceId()), OUVERTES).isPresent();
            DemandePiece d = instantane(entreprise, f);
            elements.add(new ElementApercu(f.getId(), d.getDescriptionOperation(), d.getMontant(), d.getDateOperation(), d.getTiersNom(), deja));
            if (deja) {
                ignores++;
            } else {
                d.setId(UUID.randomUUID());
                fictives.add(d);
            }
        }
        ContactResolver.Resolution contacts = contactResolver.resoudre(entrepriseId);
        List<MessageApercu> messages = new ArrayList<>();
        if (contacts.utilisable() && !fictives.isEmpty()) {
            for (ContactResolver.Contact c : contacts.contacts()) {
                messages.add(new MessageApercu(c.nom(), masquer(c.telephone()), fictives.size(),
                        templates.rendre(TypeMessage.INITIAL, payload(entreprise, fictives, null), c.nom()).texte()));
            }
        }
        return new Apercu(elements, messages, contacts.utilisable() ? null : contacts.motif(), ignores);
    }

    /**
     * Cree une demande par constat (une seule par ecriture : un double clic ne cree rien de plus), puis UN message par
     * interlocuteur regroupant toutes les nouvelles demandes. L'envoi lui-meme est fait par le worker, apres le commit.
     */
    @Transactional
    public ResultatDemande demander(UUID entrepriseId, List<UUID> findingIds, UtilisateurPrincipal principal) {
        return demander(entrepriseId, findingIds, principal, true);
    }

    /**
     * @param envoyerMaintenant choix du comptable : {@code false} laisse les messages en boite d'envoi (partiront a la passe planifiee) ;
     *                          {@code true} declenche l'envoi tout de suite SI {@code collecte.envoi.immediat} est actif (interrupteur global).
     */
    @Transactional
    public ResultatDemande demander(UUID entrepriseId, List<UUID> findingIds, UtilisateurPrincipal principal, boolean envoyerMaintenant) {
        Entreprise entreprise = entrepriseAccessService.verifierAcces(entrepriseId, principal);
        List<Finding> constats = constatsValides(entrepriseId, findingIds);
        List<DemandePiece> creees = new ArrayList<>();
        int ignores = findingIds.size() - constats.size();
        for (Finding f : constats) {
            UUID ecritureId = UUID.fromString(f.getSourceId());
            Optional<DemandePiece> existante = demandePieceRepository.findOuverteParEcriture(entrepriseId, ecritureId, OUVERTES);
            if (existante.isPresent()) {
                ignores++;
                continue;
            }
            DemandePiece d = instantane(entreprise, f);
            d.setStatut(StatutDemandePiece.A_ENVOYER);
            d.setDemandeePar(utilisateurRepository.getReferenceById(principal.getId()));
            d = demandePieceRepository.save(d);
            auditer(entreprise, d, "DOCUMENT_REQUEST_CREATED", principal, Map.of("findingId", f.getId().toString()));
            creees.add(d);
        }
        List<OutboundMessage> messages = creees.isEmpty() ? List.of() : enfiler(entreprise, creees, TypeMessage.INITIAL);
        String blocage = !creees.isEmpty() && messages.isEmpty() ? creees.get(0).getMotifBlocage() : null;
        if (!messages.isEmpty() && envoyerMaintenant) {
            declencherApresCommit();
        }
        return new ResultatDemande(creees.stream().map(DemandePiece::getId).toList(), messages.stream().map(OutboundMessage::getId).toList(), ignores, blocage);
    }

    // ------------------------------------------------------------------------------------------- envoyer / relancer / annuler / marquer recue

    /** (Re)met une demande non partie en boite d'envoi : demande creee a la main, ou en echec d'envoi une fois la cause corrigee. */
    @Transactional
    public DemandePiece envoyer(UUID entrepriseId, UUID demandeId, UtilisateurPrincipal principal) {
        Entreprise entreprise = entrepriseAccessService.verifierAcces(entrepriseId, principal);
        DemandePiece d = charger(entrepriseId, demandeId);
        if (d.getStatut() != StatutDemandePiece.A_ENVOYER && d.getStatut() != StatutDemandePiece.ECHEC_ENVOI
                && !(d.getStatut() == StatutDemandePiece.EN_ATTENTE && d.getSentAt() == null)) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Cette demande a deja ete envoyee ou n'est plus ouverte");
        }
        d.setStatut(StatutDemandePiece.A_ENVOYER);
        d.setMotifBlocage(null);
        demandePieceRepository.save(d);
        List<OutboundMessage> messages = enfiler(entreprise, List.of(d), TypeMessage.INITIAL);
        if (!messages.isEmpty()) {
            declencherApresCommit();
        }
        return pourLecture(d);
    }

    /** Relance MANUELLE : seulement si le client n'a toujours rien envoye (etat reel recalcule), un seul message actif a la fois. */
    @Transactional
    public DemandePiece relancer(UUID entrepriseId, UUID demandeId, UtilisateurPrincipal principal) {
        Entreprise entreprise = entrepriseAccessService.verifierAcces(entrepriseId, principal);
        DemandePiece d = charger(entrepriseId, demandeId);
        if (d.getStatut() != StatutDemandePiece.EN_ATTENTE) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Seule une demande envoyee et en attente du client peut etre relancee");
        }
        List<OutboundMessage> messages = enfiler(entreprise, List.of(d), TypeMessage.REMINDER);
        if (!messages.isEmpty()) {
            declencherApresCommit();
        }
        return pourLecture(d);
    }

    @Transactional
    public DemandePiece annuler(UUID entrepriseId, UUID demandeId, UtilisateurPrincipal principal) {
        Entreprise entreprise = entrepriseAccessService.verifierAcces(entrepriseId, principal);
        DemandePiece d = charger(entrepriseId, demandeId);
        if (!d.getStatut().estOuverte() || d.getStatut() == StatutDemandePiece.RECU) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Cette demande est deja close ou deja satisfaite : impossible de l'annuler");
        }
        d.setStatut(StatutDemandePiece.ANNULEE);
        d.setResolueAt(OffsetDateTime.now());
        d.setProchaineRelanceAt(null);
        demandePieceRepository.save(d);
        auditer(entreprise, d, "DOCUMENT_REQUEST_CANCELLED", principal, Map.of());
        return d;
    }

    /**
     * Traitement manuel : la piece est arrivee hors du systeme (ou l'operation n'en exige pas). C'est une DECISION HUMAINE,
     * tracee comme telle : constat resolu par l'utilisateur, jamais « par le systeme ».
     */
    @Transactional
    public DemandePiece marquerRecue(UUID entrepriseId, UUID demandeId, String commentaire, UtilisateurPrincipal principal) {
        Entreprise entreprise = entrepriseAccessService.verifierAcces(entrepriseId, principal);
        DemandePiece d = charger(entrepriseId, demandeId);
        if (!d.getStatut().estOuverte()) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Cette demande est deja close");
        }
        OffsetDateTime maintenant = OffsetDateTime.now();
        d.setStatut(StatutDemandePiece.RAPPROCHEE);
        d.setReceivedAt(d.getReceivedAt() != null ? d.getReceivedAt() : maintenant);
        d.setResolueAt(maintenant);
        d.setResoluPar("HUMAIN");
        d.setProchaineRelanceAt(null);
        demandePieceRepository.save(d);
        if (d.getEcritureId() != null) {
            findingService.resoudreParControleManuellement(entrepriseId, ControlCodes.MISSING_DOCUMENT, "ECRITURE_COMPTABLE",
                    d.getEcritureId().toString(), principal.getId(), commentaire);
        }
        auditer(entreprise, d, "DOCUMENT_REQUEST_RECEIVED", principal, Map.of("manuel", true));
        auditer(entreprise, d, "DOCUMENT_REQUEST_RESOLVED", principal, Map.of("resolutionType", "USER"));
        return pourLecture(d);
    }

    // ------------------------------------------------------------------------------------------- boite d'envoi

    /**
     * Met en boite d'envoi UN message par interlocuteur pour ces demandes (regroupement), ou bloque les demandes si personne
     * n'est joignable. Idempotent : un message non terminal deja existant pour ces demandes est reutilise ; une cle metier unique
     * garantit en base qu'aucun doublon ne peut etre insere meme en concurrence.
     */
    public List<OutboundMessage> enfiler(Entreprise entreprise, List<DemandePiece> demandes, TypeMessage type) {
        UUID entrepriseId = entreprise.getId();
        ContactResolver.Resolution contacts = contactResolver.resoudre(entrepriseId);
        if (!contacts.utilisable()) {
            for (DemandePiece d : demandes) {
                d.setStatut(StatutDemandePiece.ECHEC_ENVOI);
                d.setMotifBlocage(contacts.motif());
                demandePieceRepository.save(d);
                auditer(entreprise, d, "DOCUMENT_REQUEST_SEND_FAILED", null, Map.of("motif", contacts.motif(), "envoye", false));
            }
            return List.of();
        }
        List<UUID> ids = demandes.stream().map(DemandePiece::getId).sorted().toList();
        List<OutboundMessage> resultat = new ArrayList<>();
        for (ContactResolver.Contact contact : contacts.contacts()) {
            Optional<OutboundMessage> actif = messageRepository.findActifsPourDemandes(entrepriseId, type, ids).stream()
                    .filter(m -> m.getRecipient().equals(contact.telephone())).findFirst();
            if (actif.isPresent()) {
                resultat.add(actif.get());
                continue;
            }
            int echecs = (int) messageRepository.findParDemande(ids.get(0)).stream()
                    .filter(m -> m.getKind() == type && m.getStatus() == StatutMessage.FAILED).count();
            int maxRelances = demandes.stream().mapToInt(DemandePiece::getNbRelances).max().orElse(0);
            String cle = cle(entrepriseId, contact.telephone(), type, ids, (type == TypeMessage.REMINDER ? "R" + (maxRelances + 1) : "I") + "-" + echecs);
            Optional<OutboundMessage> parCle = messageRepository.findByEntrepriseIdAndIdempotencyKey(entrepriseId, cle);
            if (parCle.isPresent()) {
                resultat.add(parCle.get());
                continue;
            }
            OutboundMessage m = new OutboundMessage();
            m.setEntrepriseId(entrepriseId);
            m.setChannel(CanalMessage.WHATSAPP);
            m.setRecipient(contact.telephone());
            m.setRecipientName(contact.nom());
            m.setTemplateCode(MessageTemplates.codeTemplate(type));
            m.setKind(type);
            DemandePiece premiere = demandes.stream().min(Comparator.comparing(d -> d.getId().toString())).orElseThrow();
            m.setPayload(payload(entreprise, demandes, depotLienService.lien(premiere)));
            m.setStatus(StatutMessage.PENDING);
            m.setMaxAttempts(config.maxTentatives());
            m.setIdempotencyKey(cle);
            m.setNextAttemptAt(OffsetDateTime.now());
            OutboundMessage enregistre = messageRepository.save(m);
            for (DemandePiece d : demandes) {
                demandeMessageRepository.save(new DemandeMessage(d.getId(), enregistre.getId()));
                demandePieceRepository.save(d);
            }
            resultat.add(enregistre);
        }
        return resultat;
    }

    /** Contenu structure d'un message : uniquement ce que le client comprend (jamais d'identifiant technique visible). */
    Map<String, Object> payload(Entreprise entreprise, List<DemandePiece> demandes, String lien) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("entreprise", entreprise.getRaisonSociale());
        String periode = demandes.stream().map(DemandePiece::getPeriode).filter(p -> p != null).distinct().count() == 1
                ? demandes.stream().map(DemandePiece::getPeriode).filter(p -> p != null).findFirst().orElse(null) : null;
        if (periode != null) {
            payload.put("periode", periode);
        }
        if (lien != null) {
            payload.put("lienDepot", lien);
        }
        List<Map<String, Object>> elements = new ArrayList<>();
        demandes.stream().sorted(Comparator.comparing(DemandePiece::getDateOperation, Comparator.nullsLast(Comparator.naturalOrder()))
                .thenComparing(d -> d.getId().toString())).forEach(d -> {
            Map<String, Object> e = new HashMap<>();
            e.put("demandeId", d.getId().toString()); // cle interne : filtre les demandes closes avant l'envoi, jamais rendue
            e.put("libelle", d.getDescriptionOperation() != null ? d.getDescriptionOperation() : d.getLibelle());
            if (d.getMontant() != null) {
                e.put("montant", d.getMontant());
            }
            e.put("devise", "XOF");
            if (d.getDateOperation() != null) {
                e.put("date", d.getDateOperation().toString());
            }
            if (d.getTiersNom() != null) {
                e.put("tiers", d.getTiersNom());
            }
            elements.add(e);
        });
        payload.put("elements", elements);
        return payload;
    }

    // ------------------------------------------------------------------------------------------- utilitaires

    private DemandePiece instantane(Entreprise entreprise, Finding f) {
        Map<String, Object> ev = f.getEvidenceJson() != null ? f.getEvidenceJson() : Map.of();
        DemandePiece d = new DemandePiece();
        d.setEntreprise(entreprise);
        d.setLibelle(f.getTitle());
        d.setFindingId(f.getId());
        d.setEcritureId(UUID.fromString(f.getSourceId()));
        d.setOperationType("ECRITURE_COMPTABLE");
        d.setMotif(ControlCodes.MISSING_DOCUMENT);
        d.setCanal("WHATSAPP");
        if (ev.get("montant") != null) {
            d.setMontant(new BigDecimal(String.valueOf(ev.get("montant"))));
        }
        if (ev.get("dateOperation") != null) {
            LocalDate date = LocalDate.parse(String.valueOf(ev.get("dateOperation")));
            d.setDateOperation(date);
            d.setPeriode(YearMonth.from(date).toString());
        }
        if (ev.get("tiersNom") != null) {
            d.setTiersNom(String.valueOf(ev.get("tiersNom")));
            d.setTiersId(ev.get("tiersId") != null ? UUID.fromString(String.valueOf(ev.get("tiersId"))) : null);
        }
        d.setDescriptionOperation(d.getTiersNom() != null ? "Achat aupres de " + d.getTiersNom()
                : d.getDateOperation() != null ? "Operation du " + d.getDateOperation().format(java.time.format.DateTimeFormatter.ofPattern("dd/MM/yyyy")) : "Operation");
        return d;
    }

    /** Constats MISSING_DOCUMENT ouverts, portant sur une ecriture, de CETTE entreprise (les autres sont ignores, jamais lus). */
    private List<Finding> constatsValides(UUID entrepriseId, List<UUID> findingIds) {
        return findingRepository.findAllById(findingIds).stream()
                .filter(f -> f.getEntreprise().getId().equals(entrepriseId))
                .filter(f -> ControlCodes.MISSING_DOCUMENT.equals(f.getControlCode()) && "ECRITURE_COMPTABLE".equals(f.getSourceType()))
                .filter(f -> f.getStatus() == StatutFinding.OPEN)
                .toList();
    }

    /** Lecture d'une demande de l'entreprise (404 sinon : une demande d'une autre entreprise n'existe pas pour l'appelant). */
    @Transactional(readOnly = true)
    public DemandePiece chargerPourLecture(UUID entrepriseId, UUID demandeId, UtilisateurPrincipal principal) {
        entrepriseAccessService.verifierAcces(entrepriseId, principal);
        return charger(entrepriseId, demandeId);
    }

    /** Le DTO est construit hors transaction (controleur) : le demandeur (relation paresseuse) doit etre lu ici, pas plus tard. */
    private DemandePiece pourLecture(DemandePiece d) {
        if (d.getDemandeePar() != null) {
            d.getDemandeePar().getPrenom();
        }
        return d;
    }

    DemandePiece charger(UUID entrepriseId, UUID demandeId) {
        return demandePieceRepository.findByIdAndEntrepriseId(demandeId, entrepriseId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Demande introuvable"));
    }

    private void auditer(Entreprise entreprise, DemandePiece d, String action, UtilisateurPrincipal principal, Map<String, Object> details) {
        Map<String, Object> enrichis = new HashMap<>(details);
        enrichis.put("entrepriseId", entreprise.getId().toString());
        UUID tenantId = entreprise.getTenant() != null ? entreprise.getTenant().getId() : null;
        if (principal != null) {
            auditTrailService.enregistrerActionHumaine(principal.getTenantId(), principal.getId(), action, "DEMANDE_PIECE", d.getId().toString(), enrichis);
        } else {
            auditTrailService.enregistrerEvenementSysteme(tenantId, action, "DEMANDE_PIECE", d.getId().toString(), enrichis);
        }
    }

    static String cle(UUID entrepriseId, String destinataire, TypeMessage type, List<UUID> demandeIds, String creneau) {
        try {
            String brut = entrepriseId + "|" + destinataire + "|" + type + "|" + demandeIds + "|" + creneau;
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(brut.getBytes(StandardCharsets.UTF_8)));
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    static String masquer(String telephone) {
        return telephone.length() <= 4 ? "****" : "*".repeat(telephone.length() - 4) + telephone.substring(telephone.length() - 4);
    }

    /** Le worker part apres le COMMIT : le fournisseur n'est jamais appele dans la transaction qui cree la demande. */
    private void declencherApresCommit() {
        if (config.envoiImmediat() && org.springframework.transaction.support.TransactionSynchronizationManager.isSynchronizationActive()) {
            org.springframework.transaction.support.TransactionSynchronizationManager.registerSynchronization(
                    new org.springframework.transaction.support.TransactionSynchronization() {
                        @Override
                        public void afterCommit() {
                            worker.declencherAsync();
                        }
                    });
        }
    }
}
