package com.example.comptable.comptabilite.allocation;

import com.example.comptable.audit.AuditTrailService;
import com.example.comptable.comptabilite.domain.LigneEcriture;
import com.example.comptable.comptabilite.domain.StatutEcriture;
import com.example.comptable.comptabilite.repository.LigneEcritureRepository;
import com.example.comptable.comptabilite.service.LettrageService;
import com.example.comptable.entreprise.domain.Entreprise;
import com.example.comptable.entreprise.service.EntrepriseAccessService;
import com.example.comptable.security.UtilisateurPrincipal;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Deque;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * Affectation economique des reglements aux factures, montant par montant.
 *
 * <p>La verite du paiement reste l'ecriture comptable : une allocation relie la ligne TIERS d'un reglement a la ligne TIERS d'une
 * facture. Le statut de paiement d'une facture est CALCULE ici (jamais saisi) ; un surplus de reglement reste NON AFFECTE.
 * Invariants verifies a chaque allocation, sous verrou des deux lignes : meme entreprise, meme compte et meme tiers, sens
 * comptables opposes, montant &gt; 0, somme des allocations du paiement &lt;= montant du paiement, somme des allocations de la
 * facture &lt;= montant de la facture. Une allocation {@code REVERSED} ne compte plus.</p>
 *
 * <p>L'allocation peut alimenter le lettrage (jamais l'inverse) : quand tout un groupe de lignes reliees est integralement alloue, il
 * est lettre ; un reglement partiel ne rend jamais une facture « lettree ».</p>
 */
@Service
public class PaymentAllocationService {

    /** Etat d'une ligne de facture : montant du, alloue, reste, statut calcule. */
    public record Situation(UUID invoiceLineId, BigDecimal montant, BigDecimal alloue, BigDecimal reste, StatutPaiement statut) {
    }

    /** Etat d'une ligne de reglement : montant, alloue, reliquat non affecte. */
    public record Disponible(UUID paymentLineId, BigDecimal montant, BigDecimal alloue, BigDecimal nonAffecte) {
    }

    private final PaymentAllocationRepository allocationRepository;
    private final LigneEcritureRepository ligneEcritureRepository;
    private final EntrepriseAccessService entrepriseAccessService;
    private final AuditTrailService auditTrailService;
    private final LettrageService lettrageService;

    public PaymentAllocationService(PaymentAllocationRepository allocationRepository, LigneEcritureRepository ligneEcritureRepository,
                                    EntrepriseAccessService entrepriseAccessService, AuditTrailService auditTrailService,
                                    LettrageService lettrageService) {
        this.allocationRepository = allocationRepository;
        this.ligneEcritureRepository = ligneEcritureRepository;
        this.entrepriseAccessService = entrepriseAccessService;
        this.auditTrailService = auditTrailService;
        this.lettrageService = lettrageService;
    }

    // ------------------------------------------------------------------------------------------------ allocation

    /** Allocation decidee par un utilisateur (ou par le systeme si {@code principal} est null : cas certain). */
    @Transactional
    public PaymentAllocation allouer(UUID entrepriseId, UUID paymentLineId, UUID invoiceLineId, BigDecimal montant, SourceAllocation source,
                                     UtilisateurPrincipal principal) {
        Entreprise entreprise = principal != null ? entrepriseAccessService.verifierAcces(entrepriseId, principal) : null;
        if (montant == null || montant.signum() <= 0) {
            throw refus("Le montant alloue doit etre strictement positif");
        }
        if (paymentLineId.equals(invoiceLineId)) {
            throw refus("Un reglement ne peut pas etre alloue a lui-meme");
        }
        Map<UUID, LigneEcriture> lignes = verrouiller(entrepriseId, paymentLineId, invoiceLineId);
        LigneEcriture paiement = lignes.get(paymentLineId);
        LigneEcriture facture = lignes.get(invoiceLineId);

        verifierCompatibilite(paiement, facture);

        // Double confirmation : meme couple, meme montant -> la meme allocation, rien de plus. Autre montant -> annuler d'abord.
        var existante = allocationRepository.findActive(paymentLineId, invoiceLineId);
        if (existante.isPresent()) {
            if (existante.get().getAllocatedAmount().compareTo(montant) == 0) {
                return existante.get();
            }
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "Ces deux lignes sont deja reliees pour " + existante.get().getAllocatedAmount().toPlainString()
                            + " : annulez l'allocation existante avant d'en poser une autre");
        }

        BigDecimal disponible = montantDe(paiement).subtract(allocationRepository.totalAlloueDuPaiement(paymentLineId));
        if (montant.compareTo(disponible) > 0) {
            throw refus("Le montant alloue (" + montant.toPlainString() + ") depasse le montant disponible du reglement ("
                    + disponible.toPlainString() + ")");
        }
        BigDecimal ouvert = montantDe(facture).subtract(allocationRepository.totalAlloueDeLaFacture(invoiceLineId));
        if (montant.compareTo(ouvert) > 0) {
            throw refus("Le montant alloue (" + montant.toPlainString() + ") depasse le montant ouvert de la facture ("
                    + ouvert.toPlainString() + ")");
        }

        PaymentAllocation allocation = new PaymentAllocation();
        allocation.setEntrepriseId(entrepriseId);
        allocation.setPaymentLineId(paymentLineId);
        allocation.setInvoiceLineId(invoiceLineId);
        allocation.setAllocatedAmount(montant);
        allocation.setCurrency(facture.getEcriture().getEntreprise().getDevise());
        allocation.setSource(source);
        allocation.setCreatedBy(principal != null ? principal.getId() : null);
        allocation = allocationRepository.save(allocation);

        Map<String, Object> details = new HashMap<>();
        details.put("paymentLineId", paymentLineId.toString());
        details.put("invoiceLineId", invoiceLineId.toString());
        details.put("montant", montant.toPlainString());
        details.put("origine", source.name());
        auditer(facture, principal, "PAYMENT_ALLOCATED", allocation.getId(), details);

        synchroniserLettrage(facture, List.of(paymentLineId, invoiceLineId), principal);
        return allocation;
    }

    /** Annule une allocation : jamais supprimee, elle ne compte plus ; un lettrage qu'elle avait permis est defait. */
    @Transactional
    public PaymentAllocation annuler(UUID entrepriseId, UUID allocationId, String motif, UtilisateurPrincipal principal) {
        entrepriseAccessService.verifierAcces(entrepriseId, principal);
        if (motif == null || motif.isBlank()) {
            throw refus("Un motif est obligatoire pour annuler une allocation");
        }
        PaymentAllocation allocation = allocationRepository.findByIdAndEntrepriseId(allocationId, entrepriseId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Allocation introuvable"));
        if (allocation.getStatus() == StatutAllocation.REVERSED) {
            return allocation; // idempotent
        }
        Map<UUID, LigneEcriture> lignes = verrouiller(entrepriseId, allocation.getPaymentLineId(), allocation.getInvoiceLineId());
        allocation.setStatus(StatutAllocation.REVERSED);
        allocation.setReversedBy(principal.getId());
        allocation.setReversedAt(OffsetDateTime.now());
        allocation.setReversalReason(motif.length() > 300 ? motif.substring(0, 300) : motif);
        allocationRepository.save(allocation);

        LigneEcriture facture = lignes.get(allocation.getInvoiceLineId());
        auditer(facture, principal, "PAYMENT_ALLOCATION_REVERSED", allocation.getId(),
                Map.of("paymentLineId", allocation.getPaymentLineId().toString(), "invoiceLineId", allocation.getInvoiceLineId().toString(),
                        "montant", allocation.getAllocatedAmount().toPlainString(), "motif", motif));

        // Le groupe n'est plus integralement alloue : un lettrage qui en decoulait n'a plus lieu d'etre.
        for (LigneEcriture ligne : lignes.values()) {
            if (ligne.getLettrage() != null) {
                String code = ligne.getLettrage();
                lettrageService.delettrerGroupe(ligne.getCompte().getId(), code);
                auditer(facture, principal, "LETTRAGE_REVERSED", allocation.getId(), Map.of("code", code, "compteId", ligne.getCompte().getId().toString()));
                break;
            }
        }
        return allocation;
    }

    // ------------------------------------------------------------------------------------------------ lecture

    @Transactional(readOnly = true)
    public Situation situationFacture(UUID entrepriseId, UUID invoiceLineId, UtilisateurPrincipal principal) {
        entrepriseAccessService.verifierAcces(entrepriseId, principal);
        LigneEcriture ligne = charger(entrepriseId, invoiceLineId);
        return situation(ligne, allocationRepository.totalAlloueDeLaFacture(invoiceLineId));
    }

    /** Une seule requete pour N lignes de facture (listes). Les lignes d'une autre entreprise sont ignorees. */
    @Transactional(readOnly = true)
    public Map<UUID, Situation> situations(UUID entrepriseId, Collection<UUID> invoiceLineIds) {
        if (invoiceLineIds.isEmpty()) {
            return Map.of();
        }
        Map<UUID, BigDecimal> totaux = new HashMap<>();
        for (Object[] ligne : allocationRepository.totauxParFacture(invoiceLineIds)) {
            totaux.put((UUID) ligne[0], (BigDecimal) ligne[1]);
        }
        Map<UUID, Situation> resultat = new HashMap<>();
        for (LigneEcriture ligne : ligneEcritureRepository.findAllById(invoiceLineIds)) {
            if (ligne.getEcriture().getEntreprise().getId().equals(entrepriseId)) {
                resultat.put(ligne.getId(), situation(ligne, totaux.getOrDefault(ligne.getId(), BigDecimal.ZERO)));
            }
        }
        return resultat;
    }

    @Transactional(readOnly = true)
    public Disponible disponiblePaiement(UUID entrepriseId, UUID paymentLineId, UtilisateurPrincipal principal) {
        entrepriseAccessService.verifierAcces(entrepriseId, principal);
        LigneEcriture ligne = charger(entrepriseId, paymentLineId);
        BigDecimal alloue = allocationRepository.totalAlloueDuPaiement(paymentLineId);
        return new Disponible(paymentLineId, montantDe(ligne), alloue, montantDe(ligne).subtract(alloue));
    }

    @Transactional(readOnly = true)
    public List<PaymentAllocation> lister(UUID entrepriseId, UtilisateurPrincipal principal) {
        entrepriseAccessService.verifierAcces(entrepriseId, principal);
        return allocationRepository.findByEntreprise(entrepriseId);
    }

    /** Statut de paiement a partir du montant du et du total alloue (fonction pure). */
    public static StatutPaiement statutDe(BigDecimal montant, BigDecimal alloue) {
        if (alloue.signum() <= 0) {
            return StatutPaiement.NON_PAYEE;
        }
        return alloue.compareTo(montant) >= 0 ? StatutPaiement.PAYEE : StatutPaiement.PARTIELLEMENT_PAYEE;
    }

    // ------------------------------------------------------------------------------------------------ interne

    private Situation situation(LigneEcriture ligne, BigDecimal alloue) {
        BigDecimal montant = montantDe(ligne);
        return new Situation(ligne.getId(), montant, alloue, montant.subtract(alloue).max(BigDecimal.ZERO), statutDe(montant, alloue));
    }

    static BigDecimal montantDe(LigneEcriture ligne) {
        return ligne.getDebit().signum() > 0 ? ligne.getDebit() : ligne.getCredit();
    }

    private Map<UUID, LigneEcriture> verrouiller(UUID entrepriseId, UUID... ids) {
        List<LigneEcriture> lignes = ligneEcritureRepository.verrouiller(List.of(ids));
        Map<UUID, LigneEcriture> parId = lignes.stream().collect(Collectors.toMap(LigneEcriture::getId, l -> l));
        for (UUID id : ids) {
            LigneEcriture l = parId.get(id);
            // Une ligne d'une autre entreprise est « introuvable » : on ne revele jamais son existence.
            if (l == null || !l.getEcriture().getEntreprise().getId().equals(entrepriseId)) {
                throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Ligne d'ecriture introuvable");
            }
        }
        return parId;
    }

    private LigneEcriture charger(UUID entrepriseId, UUID ligneId) {
        return ligneEcritureRepository.findById(ligneId)
                .filter(l -> l.getEcriture().getEntreprise().getId().equals(entrepriseId))
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Ligne d'ecriture introuvable"));
    }

    private void verifierCompatibilite(LigneEcriture paiement, LigneEcriture facture) {
        if (paiement.getEcriture().getStatut() != StatutEcriture.VALIDEE || facture.getEcriture().getStatut() != StatutEcriture.VALIDEE) {
            throw refus("Seules des lignes d'ecritures validees peuvent etre reliees");
        }
        if (!paiement.getCompte().getId().equals(facture.getCompte().getId())) {
            throw refus("Le reglement et la facture ne sont pas sur le meme compte de tiers");
        }
        if (paiement.getTiers() != null && facture.getTiers() != null && !paiement.getTiers().getId().equals(facture.getTiers().getId())) {
            throw refus("Le reglement et la facture concernent des tiers differents");
        }
        boolean factureAuDebit = facture.getDebit().signum() > 0;
        boolean paiementCompatible = factureAuDebit ? paiement.getCredit().signum() > 0 : paiement.getDebit().signum() > 0;
        if (!paiementCompatible) {
            throw refus("Sens comptables incompatibles : un reglement se place du cote oppose a la facture");
        }
    }

    /**
     * Alimente le lettrage : si toutes les lignes reliees (composante connexe des allocations actives) sont integralement allouees, le
     * groupe est equilibre par construction et est lettre. Sinon (reglement ou facture partiel) rien n'est lettre.
     */
    private void synchroniserLettrage(LigneEcriture ancre, List<UUID> depart, UtilisateurPrincipal principal) {
        Set<UUID> composante = new LinkedHashSet<>();
        Deque<UUID> aVisiter = new ArrayDeque<>(depart);
        while (!aVisiter.isEmpty()) {
            UUID id = aVisiter.poll();
            if (!composante.add(id)) {
                continue;
            }
            allocationRepository.findActivesDuPaiement(id).forEach(a -> aVisiter.add(a.getInvoiceLineId()));
            allocationRepository.findActivesDeLaFacture(id).forEach(a -> aVisiter.add(a.getPaymentLineId()));
        }
        List<LigneEcriture> lignes = ligneEcritureRepository.findAllById(composante);
        List<UUID> ids = new ArrayList<>();
        for (LigneEcriture l : lignes) {
            BigDecimal alloue = allocationRepository.totalAlloueDuPaiement(l.getId()).add(allocationRepository.totalAlloueDeLaFacture(l.getId()));
            if (alloue.compareTo(montantDe(l)) != 0) {
                return; // une ligne n'est pas soldee : pas de lettrage
            }
            ids.add(l.getId());
        }
        String code = lettrageService.lettrerGroupeSolde(ancre.getEcriture().getEntreprise().getId(), ancre.getCompte().getId(), ids);
        if (code != null) {
            auditer(ancre, principal, "LETTRAGE_CREATED", null, Map.of("code", code, "nbLignes", ids.size(), "origine", "ALLOCATION"));
        }
    }

    private void auditer(LigneEcriture ligne, UtilisateurPrincipal principal, String action, UUID allocationId, Map<String, Object> details) {
        UUID tenantId = ligne.getEcriture().getEntreprise().getTenant() != null ? ligne.getEcriture().getEntreprise().getTenant().getId() : null;
        String entiteId = allocationId != null ? allocationId.toString() : ligne.getId().toString();
        String entite = allocationId != null ? "PAYMENT_ALLOCATION" : "LIGNE_ECRITURE";
        if (principal != null) {
            auditTrailService.enregistrerActionHumaine(tenantId, principal.getId(), action, entite, entiteId, details);
        } else {
            auditTrailService.enregistrerEvenementSysteme(tenantId, action, entite, entiteId, details);
        }
    }

    private static ResponseStatusException refus(String message) {
        return new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY, message);
    }
}
