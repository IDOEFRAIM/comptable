package com.example.comptable.comptabilite.service;

import com.example.comptable.audit.AuditTrailService;
import com.example.comptable.comptabilite.domain.CompteComptable;
import com.example.comptable.comptabilite.domain.DotationAmortissement;
import com.example.comptable.comptabilite.domain.Immobilisation;
import com.example.comptable.comptabilite.domain.MotifSortie;
import com.example.comptable.comptabilite.domain.StatutEcriture;
import com.example.comptable.comptabilite.domain.StatutImmobilisation;
import com.example.comptable.comptabilite.domain.Tiers;
import com.example.comptable.comptabilite.domain.TypeTiers;
import com.example.comptable.comptabilite.operation.common.AccountingOperationEngine;
import com.example.comptable.comptabilite.operation.common.AccountingSource;
import com.example.comptable.comptabilite.operation.common.AccountingOperationType;
import com.example.comptable.comptabilite.operation.common.domain.AccountingOperationProposal;
import com.example.comptable.comptabilite.operation.common.domain.StatutOperationProposal;
import com.example.comptable.comptabilite.operation.common.repository.AccountingOperationProposalRepository;
import com.example.comptable.comptabilite.operation.common.ResultatRegle;
import com.example.comptable.comptabilite.operation.common.web.dto.AccountingOperationProposalDto;
import com.example.comptable.comptabilite.operation.immobilisation.ImmobilisationRule;
import com.example.comptable.comptabilite.repository.CompteComptableRepository;
import com.example.comptable.comptabilite.repository.DotationAmortissementRepository;
import com.example.comptable.comptabilite.repository.ExerciceComptableRepository;
import com.example.comptable.comptabilite.repository.ImmobilisationRepository;
import com.example.comptable.comptabilite.repository.TiersRepository;
import com.example.comptable.comptabilite.web.dto.CandidatAcquisitionDto;
import com.example.comptable.comptabilite.web.dto.ComptabiliserAcquisitionRequest;
import com.example.comptable.comptabilite.web.dto.CreerImmobilisationRequest;
import com.example.comptable.comptabilite.web.dto.GenererDotationsResultDto;
import com.example.comptable.comptabilite.web.dto.ImmobilisationDto;
import com.example.comptable.comptabilite.web.dto.LierAcquisitionRequest;
import com.example.comptable.comptabilite.web.dto.LigneAmortissementDto;
import com.example.comptable.comptabilite.web.dto.MettreEnServiceRequest;
import com.example.comptable.comptabilite.web.dto.SortirImmobilisationRequest;
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
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * Cycle de vie d'une immobilisation : enregistrement (ACQUISE), comptabilisation de l'acquisition,
 * mise en service (EN_SERVICE), dotations aux amortissements, sortie (SORTIE).
 * <p>
 * Ce service possede le DOMAINE (statuts, dates, plan d'amortissement, montants) ; il ne construit
 * jamais d'ecriture : acquisition et dotations sont des commandes confiees a
 * {@link AccountingOperationEngine} via {@link ImmobilisationRule} (comptes resolus par purpose,
 * journal et exercice par le moteur). Invariant transactionnel : l'etat de l'immobilisation (statut,
 * lien d'ecriture, ligne de dotation) et l'ecriture correspondante sont enregistres dans la meme
 * transaction - une comptabilisation refusee (compte non configure, exercice cloture) n'en laisse
 * aucune trace partielle.
 */
@Service
@RequiredArgsConstructor
public class ImmobilisationService {

    /** Source des propositions du moteur : l'immobilisation ; evenements ACQUISITION puis DOTATION_<annee>. */
    public static final String SOURCE_TYPE_IMMOBILISATION = "IMMOBILISATION";
    public static final String EVENT_DOTATION_PREFIX = "DOTATION_";

    private final EntrepriseAccessService entrepriseAccessService;
    private final ImmobilisationRepository immobilisationRepository;
    private final DotationAmortissementRepository dotationAmortissementRepository;
    private final CompteComptableRepository compteComptableRepository;
    private final ExerciceComptableRepository exerciceComptableRepository;
    private final TiersRepository tiersRepository;
    private final AccountingOperationEngine accountingOperationEngine;
    private final ImmobilisationRule immobilisationRule;
    private final AuditTrailService auditTrailService;
    private final AccountingOperationProposalRepository proposalRepository;

    @Transactional
    public ImmobilisationDto creer(UUID entrepriseId, CreerImmobilisationRequest request, UtilisateurPrincipal principal) {
        Entreprise entreprise = entrepriseAccessService.verifierAcces(entrepriseId, principal);
        CompteComptable compte = chargerCompte(entrepriseId, request.compteId());
        CompteComptable compteAmortissement = chargerCompte(entrepriseId, request.compteAmortissementId());

        Immobilisation immobilisation = new Immobilisation();
        immobilisation.setEntreprise(entreprise);
        immobilisation.setCompte(compte);
        immobilisation.setCompteAmortissement(compteAmortissement);
        immobilisation.setLibelle(request.libelle());
        immobilisation.setType(request.type());
        immobilisation.setDateAcquisition(request.dateAcquisition());
        immobilisation.setValeurOrigine(request.valeurOrigine());
        immobilisation.setDureeAnnees(request.dureeAnnees());
        immobilisation.setMode(request.mode());
        immobilisation.setStatut(StatutImmobilisation.ACQUISE);

        immobilisation = immobilisationRepository.save(immobilisation);
        auditer(principal, "IMMOBILISATION_ENREGISTREE", immobilisation, Map.of("valeurOrigine", immobilisation.getValeurOrigine()));
        return toDto(immobilisation, List.of());
    }

    /** Une seule requete pour les dotations de toutes les immobilisations listees (pas une par
     * immobilisation) : indispensable des que le registre depasse quelques dizaines d'actifs. */
    @Transactional(readOnly = true)
    public List<ImmobilisationDto> lister(UUID entrepriseId, UtilisateurPrincipal principal) {
        entrepriseAccessService.verifierAcces(entrepriseId, principal);
        List<Immobilisation> immobilisations = immobilisationRepository.findByEntrepriseIdOrderByDateAcquisitionAsc(entrepriseId);

        List<UUID> immobilisationIds = immobilisations.stream().map(Immobilisation::getId).toList();
        Map<UUID, List<DotationAmortissement>> dotationsParImmobilisation = dotationAmortissementRepository
                .findByImmobilisationIdIn(immobilisationIds).stream()
                .collect(Collectors.groupingBy(d -> d.getImmobilisation().getId()));

        return immobilisations.stream()
                .map(immo -> toDto(immo, dotationsParImmobilisation.getOrDefault(immo.getId(), List.of())))
                .toList();
    }

    /**
     * Passe l'ecriture d'acquisition (actif / TVA recuperable / fournisseur) par le moteur. A n'utiliser
     * que si l'acquisition n'est pas deja comptabilisee par ailleurs (par exemple via la facture
     * fournisseur correspondante) : le service refuse seulement un second appel pour la meme
     * immobilisation, il ne peut pas deviner qu'une facture l'a deja portee.
     */
    @Transactional
    public ImmobilisationDto comptabiliserAcquisition(UUID entrepriseId, UUID immobilisationId,
                                                       ComptabiliserAcquisitionRequest request, UtilisateurPrincipal principal) {
        Entreprise entreprise = entrepriseAccessService.verifierAcces(entrepriseId, principal);
        Immobilisation immobilisation = charger(entrepriseId, immobilisationId);
        if (immobilisation.getAcquisitionProposal() != null) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "L'acquisition de cette immobilisation est deja rattachee a la comptabilisation "
                    + immobilisation.getAcquisitionProposal().getSourceType() + "/" + immobilisation.getAcquisitionProposal().getSourceEvent());
        }
        if (immobilisation.getStatut() == StatutImmobilisation.SORTIE) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Cette immobilisation est sortie du patrimoine");
        }
        Tiers fournisseur = tiersRepository.findById(request.fournisseurId())
                .filter(t -> t.getEntreprise().getId().equals(entrepriseId))
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Tiers introuvable"));
        if (fournisseur.getType() != TypeTiers.FOURNISSEUR) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Le tiers selectionne doit etre un fournisseur");
        }

        // Double comptage : une facture d'achat approuvee a-t-elle deja debite ce compte de cette valeur sans etre
        // rattachee a un bien ? Le comptable doit alors la lier, ou attester explicitement qu'il s'agit d'un autre achat.
        List<AccountingOperationProposal> candidates = proposalRepository.rechercherAchatsDebitant(entrepriseId,
                immobilisation.getCompte().getId(), immobilisation.getValeurOrigine()).stream()
                .filter(p -> immobilisationRepository.findByAcquisitionProposalId(p.getId()).isEmpty())
                .toList();
        if (!candidates.isEmpty() && !Boolean.TRUE.equals(request.confirmerAbsenceDeFacture())) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Une facture d'achat deja comptabilisee semble porter cette acquisition ("
                    + candidates.stream().map(p -> p.getSourceType() + "/" + p.getSourceId()).collect(java.util.stream.Collectors.joining(", "))
                    + ") : liez-la a l'immobilisation, ou confirmez qu'il s'agit d'un autre achat pour eviter un double comptage.");
        }

        BigDecimal montantTva = request.montantTva() != null ? request.montantTva() : BigDecimal.ZERO;
        String libelle = "Acquisition " + immobilisation.getLibelle() + " - " + fournisseur.getNom();
        ResultatRegle resultat = immobilisationRule.construireAcquisition(entreprise, immobilisation, fournisseur, montantTva, libelle);
        AccountingOperationProposalDto proposition = accountingOperationEngine.proposerEtValider(entrepriseId,
                AccountingOperationType.IMMOBILISATION_ACQUISITION, fournisseur, immobilisation.getDateAcquisition(), libelle,
                null, resultat, AccountingSource.de(SOURCE_TYPE_IMMOBILISATION, immobilisation.getId(), "ACQUISITION"), StatutEcriture.VALIDEE, principal);

        immobilisation.setAcquisitionProposal(proposalRepository.getReferenceById(proposition.id()));
        immobilisationRepository.save(immobilisation);
        auditer(principal, "IMMOBILISATION_ACQUISE", immobilisation,
                Map.of("ecritureId", String.valueOf(proposition.ecritureId()), "propositionId", String.valueOf(proposition.id()),
                        "montantTva", montantTva, "confirmationSansFacture", Boolean.TRUE.equals(request.confirmerAbsenceDeFacture())));
        return toDto(immobilisation, dotationAmortissementRepository.findByImmobilisationId(immobilisation.getId()));
    }

    /** Selecteur de « Lier a une facture » : achats comptabilises qui debitent le compte d'actif du bien, avec la valeur encore disponible. */
    @Transactional(readOnly = true)
    public List<CandidatAcquisitionDto> candidatsAcquisition(UUID entrepriseId, UUID immobilisationId, UtilisateurPrincipal principal) {
        entrepriseAccessService.verifierAcces(entrepriseId, principal);
        Immobilisation immobilisation = charger(entrepriseId, immobilisationId);
        List<CandidatAcquisitionDto> candidats = new ArrayList<>();
        for (AccountingOperationProposal achat : proposalRepository.rechercherAchatsSurCompte(entrepriseId, immobilisation.getCompte().getId())) {
            BigDecimal debite = achat.getLignes().stream().filter(l -> l.getCompte().getId().equals(immobilisation.getCompte().getId()))
                    .map(l -> l.getDebit()).reduce(BigDecimal.ZERO, BigDecimal::add);
            BigDecimal dejaAffecte = immobilisationRepository.findByAcquisitionProposalId(achat.getId()).stream()
                    .filter(i -> i.getCompte().getId().equals(immobilisation.getCompte().getId()))
                    .map(Immobilisation::getValeurOrigine).reduce(BigDecimal.ZERO, BigDecimal::add);
            BigDecimal reste = debite.subtract(dejaAffecte);
            candidats.add(new CandidatAcquisitionDto(achat.getSourceType(), achat.getSourceId(), achat.getSourceEvent(), achat.getLibelle(),
                    achat.getDateOperation(), debite, dejaAffecte, reste, reste.compareTo(immobilisation.getValeurOrigine()) >= 0));
        }
        return candidats;
    }

    /**
     * Rattache l'acquisition a la comptabilisation d'une facture d'achat DEJA passee : l'immobilisation reference
     * cette consequence comptable existante et ne comptabilise donc jamais une seconde acquisition. La facture doit
     * avoir une proposition d'achat approuvee, debitant le compte d'actif de l'immobilisation pour au moins sa valeur
     * (en tenant compte des autres biens deja rattaches a la meme facture).
     */
    @Transactional
    public ImmobilisationDto lierAcquisition(UUID entrepriseId, UUID immobilisationId, LierAcquisitionRequest request,
                                              UtilisateurPrincipal principal) {
        entrepriseAccessService.verifierAcces(entrepriseId, principal);
        Immobilisation immobilisation = charger(entrepriseId, immobilisationId);
        if (immobilisation.getAcquisitionProposal() != null) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "L'acquisition de cette immobilisation est deja rattachee a une comptabilisation");
        }
        List<AccountingOperationProposal> achats = proposalRepository
                .findByEntrepriseIdAndSourceTypeAndSourceIdOrderByCreatedAtAsc(entrepriseId, request.sourceType(), request.sourceId()).stream()
                .filter(p -> p.getType() == AccountingOperationType.ACHAT && p.getStatut() == StatutOperationProposal.APPROVED)
                .toList();
        if (achats.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY,
                    "Aucune comptabilisation d'achat approuvee pour " + request.sourceType() + "/" + request.sourceId()
                            + " : comptabilisez d'abord la facture, ou utilisez la comptabilisation de l'acquisition.");
        }
        if (achats.size() > 1) {
            throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY, "Plusieurs comptabilisations d'achat pour cette source : rattachement ambigu");
        }
        AccountingOperationProposal achat = achats.get(0);
        BigDecimal debitSurCompte = achat.getLignes().stream()
                .filter(l -> l.getCompte().getId().equals(immobilisation.getCompte().getId()))
                .map(l -> l.getDebit()).reduce(BigDecimal.ZERO, BigDecimal::add);
        BigDecimal dejaAffecte = immobilisationRepository.findByAcquisitionProposalId(achat.getId()).stream()
                .filter(i -> i.getCompte().getId().equals(immobilisation.getCompte().getId()))
                .map(Immobilisation::getValeurOrigine).reduce(BigDecimal.ZERO, BigDecimal::add);
        if (debitSurCompte.signum() == 0) {
            throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY, "Cette facture ne debite pas le compte " + immobilisation.getCompte().getNumero()
                    + " de l'immobilisation");
        }
        if (dejaAffecte.add(immobilisation.getValeurOrigine()).compareTo(debitSurCompte) > 0) {
            throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY, "Valeur insuffisante sur la facture : " + debitSurCompte.toPlainString()
                    + " debites sur " + immobilisation.getCompte().getNumero() + ", dont " + dejaAffecte.toPlainString()
                    + " deja affectes a d'autres immobilisations, pour " + immobilisation.getValeurOrigine().toPlainString() + " demandes");
        }
        immobilisation.setAcquisitionProposal(achat);
        immobilisationRepository.save(immobilisation);
        auditer(principal, "SOURCE_COMPTABLE_LIEE", immobilisation, Map.of("propositionId", achat.getId().toString(),
                "sourceType", achat.getSourceType(), "sourceId", achat.getSourceId().toString(), "sourceEvent", achat.getSourceEvent()));
        return toDto(immobilisation, dotationAmortissementRepository.findByImmobilisationId(immobilisation.getId()));
    }

    @Transactional
    public ImmobilisationDto mettreEnService(UUID entrepriseId, UUID immobilisationId, MettreEnServiceRequest request,
                                              UtilisateurPrincipal principal) {
        entrepriseAccessService.verifierAcces(entrepriseId, principal);
        Immobilisation immobilisation = charger(entrepriseId, immobilisationId);
        if (immobilisation.getStatut() != StatutImmobilisation.ACQUISE) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "Seule une immobilisation acquise (non encore mise en service) peut etre mise en service : statut actuel "
                            + immobilisation.getStatut());
        }
        if (request.dateMiseEnService().isBefore(immobilisation.getDateAcquisition())) {
            throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY,
                    "La date de mise en service (" + request.dateMiseEnService() + ") ne peut pas preceder la date d'acquisition ("
                            + immobilisation.getDateAcquisition() + ")");
        }
        immobilisation.setDateMiseEnService(request.dateMiseEnService());
        immobilisation.setStatut(StatutImmobilisation.EN_SERVICE);
        immobilisationRepository.save(immobilisation);
        auditer(principal, "IMMOBILISATION_MISE_EN_SERVICE", immobilisation, Map.of("dateMiseEnService", request.dateMiseEnService().toString()));
        return toDto(immobilisation, dotationAmortissementRepository.findByImmobilisationId(immobilisation.getId()));
    }

    /**
     * Genere les dotations de l'annee pour chaque immobilisation concernee (une ecriture par immobilisation,
     * via le moteur). Concernees : mises en service, dont le plan prevoit une dotation cette annee et qui n'en
     * ont pas deja une ; une immobilisation SORTIE n'est plus amortie a partir de l'annee de sa sortie (le
     * prorata de cette annee-la releve des regles de cession, non verifiees). Tout ou rien : un compte non
     * configure sur une immobilisation annule la generation entiere avec l'explication.
     */
    @Transactional
    public GenererDotationsResultDto genererDotations(UUID entrepriseId, int annee, UtilisateurPrincipal principal) {
        Entreprise entreprise = entrepriseAccessService.verifierAcces(entrepriseId, principal);
        LocalDate dateEcriture = LocalDate.of(annee, 12, 31);
        ExerciceOuvertGuard.exigerOuvert(exerciceComptableRepository, entrepriseId, dateEcriture);

        List<UUID> ecritureIds = new ArrayList<>();
        BigDecimal total = BigDecimal.ZERO;
        for (Immobilisation immobilisation : immobilisationRepository.findByEntrepriseIdOrderByDateAcquisitionAsc(entrepriseId)) {
            if (!amortissableEn(immobilisation, annee)
                    || dotationAmortissementRepository.existsByImmobilisationIdAndAnnee(immobilisation.getId(), annee)) {
                continue;
            }
            BigDecimal dotation = PlanAmortissementCalculator.calculer(immobilisation.getValeurOrigine(),
                            immobilisation.getDateMiseEnService(), immobilisation.getDureeAnnees(), immobilisation.getMode())
                    .stream().filter(l -> l.annee() == annee).findFirst()
                    .map(PlanAmortissementCalculator.LigneAmortissement::dotation).orElse(null);
            if (dotation == null || dotation.signum() == 0) {
                continue;
            }

            String libelle = "Dotation amortissement " + annee + " - " + immobilisation.getLibelle();
            ResultatRegle resultat = immobilisationRule.construireDotation(entreprise, immobilisation, dotation, libelle);
            AccountingOperationProposalDto proposition = accountingOperationEngine.proposerEtValider(entrepriseId,
                    AccountingOperationType.AMORTISSEMENT_DOTATION, null, dateEcriture, libelle, null, resultat,
                    AccountingSource.de(SOURCE_TYPE_IMMOBILISATION, immobilisation.getId(), EVENT_DOTATION_PREFIX + annee), StatutEcriture.VALIDEE, principal);

            DotationAmortissement trace = new DotationAmortissement();
            trace.setImmobilisation(immobilisation);
            trace.setAnnee(annee);
            trace.setMontant(dotation);
            trace.setEcritureId(proposition.ecritureId());
            dotationAmortissementRepository.save(trace);

            auditer(principal, "AMORTISSEMENT_COMPTABILISE", immobilisation,
                    Map.of("annee", annee, "montant", dotation, "ecritureId", String.valueOf(proposition.ecritureId())));
            ecritureIds.add(proposition.ecritureId());
            total = total.add(dotation);
        }
        return new GenererDotationsResultDto(ecritureIds, annee, total, ecritureIds.size());
    }

    /**
     * Sortie du patrimoine (cession ou mise au rebut) : enregistre la date, le motif et le prix de cession
     * eventuel, arrete l'amortissement. Aucune ecriture n'est generee : valeur nette comptable, plus ou
     * moins-value et reprise d'amortissements dependent de regles non verifiees et se saisissent a la main.
     */
    @Transactional
    public ImmobilisationDto sortir(UUID entrepriseId, UUID immobilisationId, SortirImmobilisationRequest request,
                                     UtilisateurPrincipal principal) {
        entrepriseAccessService.verifierAcces(entrepriseId, principal);
        Immobilisation immobilisation = charger(entrepriseId, immobilisationId);
        if (immobilisation.getStatut() == StatutImmobilisation.SORTIE) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Cette immobilisation est deja sortie du patrimoine");
        }
        LocalDate depart = immobilisation.getDateMiseEnService() != null
                ? immobilisation.getDateMiseEnService() : immobilisation.getDateAcquisition();
        if (request.dateSortie().isBefore(depart)) {
            throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY,
                    "La date de sortie (" + request.dateSortie() + ") ne peut pas preceder " + depart);
        }
        if (request.motif() == MotifSortie.MISE_AU_REBUT && request.montantCession() != null) {
            throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY, "Une mise au rebut n'a pas de montant de cession");
        }
        immobilisation.setStatut(StatutImmobilisation.SORTIE);
        immobilisation.setDateSortie(request.dateSortie());
        immobilisation.setMotifSortie(request.motif());
        immobilisation.setMontantCession(request.montantCession());
        immobilisationRepository.save(immobilisation);

        Map<String, Object> details = new HashMap<>();
        details.put("dateSortie", request.dateSortie().toString());
        details.put("motif", request.motif().name());
        if (request.montantCession() != null) {
            details.put("montantCession", request.montantCession());
        }
        details.put("ecrituresGenerees", false);
        auditer(principal, "IMMOBILISATION_SORTIE", immobilisation, details);
        return toDto(immobilisation, dotationAmortissementRepository.findByImmobilisationId(immobilisation.getId()));
    }

    // ------------------------------------------------------------------------------------ internes

    /** Mise en service effective et pas sortie avant (ou pendant) cette annee. */
    private static boolean amortissableEn(Immobilisation immobilisation, int annee) {
        if (immobilisation.getDateMiseEnService() == null) {
            return false;
        }
        return switch (immobilisation.getStatut()) {
            case EN_SERVICE -> true;
            case SORTIE -> immobilisation.getDateSortie() != null && annee < immobilisation.getDateSortie().getYear();
            case ACQUISE -> false;
        };
    }

    private ImmobilisationDto toDto(Immobilisation immobilisation, List<DotationAmortissement> dotationsGenerees) {
        List<PlanAmortissementCalculator.LigneAmortissement> planCalcule = immobilisation.getDateMiseEnService() == null
                ? List.of()
                : PlanAmortissementCalculator.calculer(immobilisation.getValeurOrigine(), immobilisation.getDateMiseEnService(),
                        immobilisation.getDureeAnnees(), immobilisation.getMode());

        Map<Integer, DotationAmortissement> genereesParAnnee = dotationsGenerees.stream()
                .collect(Collectors.toMap(DotationAmortissement::getAnnee, d -> d));

        List<LigneAmortissementDto> plan = planCalcule.stream()
                .map(l -> new LigneAmortissementDto(l.annee(), l.dotation(), l.valeurNetteComptableFin(),
                        genereesParAnnee.containsKey(l.annee())))
                .toList();

        BigDecimal totalGenere = genereesParAnnee.values().stream()
                .map(DotationAmortissement::getMontant)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        return ImmobilisationDto.from(immobilisation, immobilisation.getValeurOrigine().subtract(totalGenere), plan);
    }

    private Immobilisation charger(UUID entrepriseId, UUID immobilisationId) {
        return immobilisationRepository.findByIdAndEntrepriseId(immobilisationId, entrepriseId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Immobilisation introuvable"));
    }

    private CompteComptable chargerCompte(UUID entrepriseId, UUID compteId) {
        return compteComptableRepository.findById(compteId)
                .filter(c -> c.getEntreprise().getId().equals(entrepriseId))
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Compte introuvable"));
    }

    private void auditer(UtilisateurPrincipal principal, String action, Immobilisation immobilisation, Map<String, Object> details) {
        auditTrailService.enregistrerActionHumaine(principal.getTenantId(), principal.getId(), action, "IMMOBILISATION",
                immobilisation.getId().toString(), details);
    }
}
