package com.example.comptable.comptabilite.service;

import com.example.comptable.comptabilite.domain.CompteComptable;
import com.example.comptable.comptabilite.domain.EcritureComptable;
import com.example.comptable.comptabilite.domain.ExerciceComptable;
import com.example.comptable.comptabilite.domain.Journal;
import com.example.comptable.comptabilite.domain.LigneEcriture;
import com.example.comptable.comptabilite.domain.NatureCompte;
import com.example.comptable.comptabilite.domain.StatutEcriture;
import com.example.comptable.comptabilite.domain.StatutExercice;
import com.example.comptable.comptabilite.domain.Tiers;
import com.example.comptable.comptabilite.domain.TypeTiers;
import com.example.comptable.comptabilite.repository.CompteComptableRepository;
import com.example.comptable.comptabilite.repository.EcritureComptableRepository;
import com.example.comptable.comptabilite.repository.ExerciceComptableRepository;
import com.example.comptable.comptabilite.repository.JournalRepository;
import com.example.comptable.comptabilite.repository.TiersRepository;
import com.example.comptable.comptabilite.web.dto.ComptabiliserDocumentRequest;
import com.example.comptable.comptabilite.web.dto.EcritureComptableDto;
import com.example.comptable.document.domain.Document;
import com.example.comptable.document.domain.TypeDocument;
import com.example.comptable.document.repository.DocumentRepository;
import com.example.comptable.entreprise.domain.Entreprise;
import com.example.comptable.entreprise.service.EntrepriseAccessService;
import com.example.comptable.identite.repository.UtilisateurRepository;
import com.example.comptable.security.UtilisateurPrincipal;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.UUID;

/**
 * Transforme un Document valide (montants extraits + confirmes) en ecriture comptable
 * equilibree, en s'appuyant sur le tiers et le compte de charge/produit choisis par
 * l'utilisateur (l'OCR ne peut pas deviner ces deux informations de maniere fiable).
 * L'ecriture est toujours creee en BROUILLON : {@link #valider} est l'etape separee
 * qui la rend intangible.
 */
@Service
@RequiredArgsConstructor
public class ComptabilisationService {

    private static final String COMPTE_TVA_DEDUCTIBLE = "4456";
    private static final String COMPTE_TVA_COLLECTEE = "443";

    private final EntrepriseAccessService entrepriseAccessService;
    private final DocumentRepository documentRepository;
    private final TiersRepository tiersRepository;
    private final CompteComptableRepository compteComptableRepository;
    private final ExerciceComptableRepository exerciceComptableRepository;
    private final JournalRepository journalRepository;
    private final EcritureComptableRepository ecritureComptableRepository;
    private final UtilisateurRepository utilisateurRepository;

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
        if (montantHt.add(montantTva).compareTo(montantTtc) != 0) {
            throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY,
                    "Le total HT + TVA ne correspond pas au TTC du document ; corrigez les montants avant comptabilisation.");
        }

        LocalDate dateEcriture = request.dateEcriture() != null ? request.dateEcriture() : document.getDatePiece();
        if (dateEcriture == null) {
            throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY, "Date de piece manquante");
        }

        Tiers tiers = chargerTiers(entrepriseId, request.tiersId(), operation);
        CompteComptable compteChargeOuProduit = chargerCompteChargeOuProduit(entrepriseId, request.compteChargeOuProduitId(), operation);
        ExerciceComptable exercice = chargerExerciceOuvert(entrepriseId, dateEcriture);
        Journal journal = chargerJournal(entrepriseId, operation);
        CompteComptable compteTva = montantTva.compareTo(BigDecimal.ZERO) > 0
                ? chargerCompteTva(entrepriseId, operation)
                : null;

        String libelle = request.libelle() != null ? request.libelle()
                : document.getTypeDocument() + " - " + tiers.getNom();

        EcritureComptable ecriture = new EcritureComptable();
        ecriture.setEntreprise(entreprise);
        ecriture.setExercice(exercice);
        ecriture.setJournal(journal);
        ecriture.setNumeroPiece(genererNumeroPiece(journal));
        ecriture.setDateEcriture(dateEcriture);
        ecriture.setLibelle(libelle);
        ecriture.setCreatedBy(utilisateurRepository.getReferenceById(principal.getId()));
        ecriture.setStatut(StatutEcriture.BROUILLON);

        genererLignes(ecriture, operation, compteChargeOuProduit, compteTva, tiers, montantHt, montantTva, montantTtc, libelle);

        if (!ecriture.estEquilibree()) {
            // Ne devrait jamais se produire vu les controles ci-dessus ; filet de securite explicite.
            throw new IllegalStateException("Ecriture generee desequilibree, comptabilisation annulee");
        }

        ecriture = ecritureComptableRepository.save(ecriture);

        document.setEcritureId(ecriture.getId());
        documentRepository.save(document);

        return EcritureComptableDto.from(ecriture);
    }

    /**
     * Point d'entree pour le pipeline agentique ({@code agent.pipeline.EcritureNode}) : pas de
     * {@link UtilisateurPrincipal} (execution en tache de fond, personne n'est connecte) - le
     * {@code createdBy} de l'ecriture generee reste donc volontairement null, trace explicite
     * qu'aucun humain n'a valide cette ligne avant sa creation.
     * <p>
     * Depuis la mission P0 (section 15, "Niveau 3"), {@code tiersId}/{@code compteChargeOuProduitId}
     * sont fournis par l'appelant, jamais devines ici : c'est
     * {@code agent.comptabilisation.AccountingProposalBuilder#eligibleNiveau3} qui a deja etabli,
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
            if (montantHt == null || montantTtc == null || montantHt.add(montantTva).compareTo(montantTtc) != 0) {
                throw new ComptabilisationAutomatiqueIndisponibleException(
                        "Montants insuffisants ou incoherents (HT/TVA/TTC) pour une comptabilisation automatique.");
            }

            LocalDate dateEcriture = document.getDatePiece();
            if (dateEcriture == null) {
                throw new ComptabilisationAutomatiqueIndisponibleException("Date de piece manquante.");
            }

            Tiers tiers = chargerTiers(entrepriseId, tiersId, operation);
            CompteComptable compteChargeOuProduit = chargerCompteChargeOuProduit(entrepriseId, compteChargeOuProduitId, operation);
            ExerciceComptable exercice = chargerExerciceOuvert(entrepriseId, dateEcriture);
            Journal journal = chargerJournal(entrepriseId, operation);
            CompteComptable compteTva = montantTva.compareTo(BigDecimal.ZERO) > 0
                    ? chargerCompteTva(entrepriseId, operation)
                    : null;

            String libelle = document.getTypeDocument() + " - " + tiers.getNom();

            EcritureComptable ecriture = new EcritureComptable();
            ecriture.setEntreprise(entreprise);
            ecriture.setExercice(exercice);
            ecriture.setJournal(journal);
            ecriture.setNumeroPiece(genererNumeroPiece(journal));
            ecriture.setDateEcriture(dateEcriture);
            ecriture.setLibelle(libelle);
            ecriture.setStatut(StatutEcriture.BROUILLON);

            genererLignes(ecriture, operation, compteChargeOuProduit, compteTva, tiers, montantHt, montantTva, montantTtc, libelle);

            if (!ecriture.estEquilibree()) {
                throw new IllegalStateException("Ecriture generee desequilibree, comptabilisation automatique annulee");
            }

            ecriture = ecritureComptableRepository.save(ecriture);
            document.setEcritureId(ecriture.getId());
            documentRepository.save(document);

            return EcritureComptableDto.from(ecriture);
        } catch (ResponseStatusException e) {
            // chargerTiers/chargerCompteChargeOuProduit/chargerExerciceOuvert levent des
            // ResponseStatusException (voie manuelle, contexte HTTP) : hors contexte HTTP, cette
            // methode ne doit jamais laisser une telle exception remonter jusqu'au pipeline.
            throw new ComptabilisationAutomatiqueIndisponibleException(
                    "Comptabilisation automatique impossible : " + e.getReason());
        }
    }

    private void genererLignes(EcritureComptable ecriture, TypeOperation operation, CompteComptable compteChargeOuProduit,
                                CompteComptable compteTva, Tiers tiers, BigDecimal montantHt, BigDecimal montantTva,
                                BigDecimal montantTtc, String libelle) {
        if (operation == TypeOperation.ACHAT) {
            ecriture.ajouterLigne(LigneEcriture.debit(compteChargeOuProduit, montantHt, libelle));
            if (compteTva != null) {
                ecriture.ajouterLigne(LigneEcriture.debit(compteTva, montantTva, libelle));
            }
            LigneEcriture ligneTiers = LigneEcriture.credit(tiers.getCompteComptable(), montantTtc, libelle);
            ligneTiers.setTiers(tiers);
            ecriture.ajouterLigne(ligneTiers);
        } else {
            LigneEcriture ligneTiers = LigneEcriture.debit(tiers.getCompteComptable(), montantTtc, libelle);
            ligneTiers.setTiers(tiers);
            ecriture.ajouterLigne(ligneTiers);
            ecriture.ajouterLigne(LigneEcriture.credit(compteChargeOuProduit, montantHt, libelle));
            if (compteTva != null) {
                ecriture.ajouterLigne(LigneEcriture.credit(compteTva, montantTva, libelle));
            }
        }
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

    private CompteComptable chargerCompteTva(UUID entrepriseId, TypeOperation operation) {
        String numero = operation == TypeOperation.ACHAT ? COMPTE_TVA_DEDUCTIBLE : COMPTE_TVA_COLLECTEE;
        return compteComptableRepository.findByEntrepriseIdAndNumero(entrepriseId, numero)
                .orElseThrow(() -> new IllegalStateException(
                        "Compte TVA " + numero + " introuvable, l'entreprise n'a pas ete initialisee correctement"));
    }

    private ExerciceComptable chargerExerciceOuvert(UUID entrepriseId, LocalDate date) {
        ExerciceComptable exercice = exerciceComptableRepository.findByEntrepriseIdAndDate(entrepriseId, date)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY,
                        "Aucun exercice comptable ne couvre la date " + date));
        if (exercice.getStatut() != StatutExercice.OUVERT) {
            throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY, "L'exercice couvrant cette date est cloture");
        }
        return exercice;
    }

    private Journal chargerJournal(UUID entrepriseId, TypeOperation operation) {
        String code = operation == TypeOperation.ACHAT ? "ACH" : "VTE";
        return journalRepository.findByEntrepriseIdAndCode(entrepriseId, code)
                .orElseThrow(() -> new IllegalStateException(
                        "Journal " + code + " introuvable, l'entreprise n'a pas ete initialisee correctement"));
    }

    private String genererNumeroPiece(Journal journal) {
        long sequence = ecritureComptableRepository.countByJournalId(journal.getId()) + 1;
        return "%s-%d-%04d".formatted(journal.getCode(), LocalDate.now().getYear(), sequence);
    }
}
