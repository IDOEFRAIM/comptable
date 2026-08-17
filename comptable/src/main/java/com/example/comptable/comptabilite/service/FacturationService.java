package com.example.comptable.comptabilite.service;

import com.example.comptable.comptabilite.domain.CompteComptable;
import com.example.comptable.comptabilite.domain.EcritureComptable;
import com.example.comptable.comptabilite.domain.ExerciceComptable;
import com.example.comptable.comptabilite.domain.Facture;
import com.example.comptable.comptabilite.domain.Journal;
import com.example.comptable.comptabilite.domain.LigneEcriture;
import com.example.comptable.comptabilite.domain.LigneFacture;
import com.example.comptable.comptabilite.domain.StatutEcriture;
import com.example.comptable.comptabilite.domain.StatutExercice;
import com.example.comptable.comptabilite.domain.StatutFacture;
import com.example.comptable.comptabilite.domain.Tiers;
import com.example.comptable.comptabilite.domain.TypeFacture;
import com.example.comptable.comptabilite.repository.CompteComptableRepository;
import com.example.comptable.comptabilite.repository.EcritureComptableRepository;
import com.example.comptable.comptabilite.repository.ExerciceComptableRepository;
import com.example.comptable.comptabilite.repository.FactureRepository;
import com.example.comptable.comptabilite.repository.JournalRepository;
import com.example.comptable.comptabilite.repository.TiersRepository;
import com.example.comptable.comptabilite.web.dto.CreerFactureRequest;
import com.example.comptable.comptabilite.web.dto.FactureDto;
import com.example.comptable.comptabilite.web.dto.LigneFactureRequest;
import com.example.comptable.entreprise.domain.Entreprise;
import com.example.comptable.entreprise.service.EntrepriseAccessService;
import com.example.comptable.identite.domain.Utilisateur;
import com.example.comptable.identite.repository.UtilisateurRepository;
import com.example.comptable.security.UtilisateurPrincipal;
import com.example.comptable.stock.domain.MouvementStock;
import com.example.comptable.stock.domain.TypeMouvementStock;
import com.example.comptable.stock.service.MouvementStockService;
import com.example.comptable.stock.web.dto.CreerMouvementStockRequest;
import com.example.comptable.stock.web.dto.MouvementStockDto;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/**
 * Devis et factures clients. Un devis suit BROUILLON -> ENVOYE -> ACCEPTE|REFUSE ; un devis
 * ACCEPTE peut etre converti en facture (nouvelle Facture de type FACTURE, copie des lignes,
 * devisOrigineId renseigne, devis marque CONVERTI). Une facture suit BROUILLON -> VALIDEE
 * (genere l'ecriture de vente, journal VTE, et pour chaque ligne liee a un article de stock
 * une sortie automatique via MouvementStockService) -> PAYEE, ou VALIDEE -> ANNULEE (reintegre
 * le stock consomme sans toucher a l'ecriture de vente elle-meme).
 */
@Service
@RequiredArgsConstructor
public class FacturationService {

    private static final String COMPTE_TVA_COLLECTEE = "443";

    private final EntrepriseAccessService entrepriseAccessService;
    private final FactureRepository factureRepository;
    private final TiersRepository tiersRepository;
    private final CompteComptableRepository compteComptableRepository;
    private final ExerciceComptableRepository exerciceComptableRepository;
    private final JournalRepository journalRepository;
    private final EcritureComptableRepository ecritureComptableRepository;
    private final UtilisateurRepository utilisateurRepository;
    private final MouvementStockService mouvementStockService;

    @Transactional
    public FactureDto creer(UUID entrepriseId, TypeFacture type, CreerFactureRequest request, UtilisateurPrincipal principal) {
        Entreprise entreprise = entrepriseAccessService.verifierAcces(entrepriseId, principal);
        Tiers tiers = tiersRepository.findById(request.tiersId())
                .filter(t -> t.getEntreprise().getId().equals(entrepriseId))
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Tiers introuvable"));

        Facture facture = new Facture();
        facture.setEntreprise(entreprise);
        facture.setTiers(tiers);
        facture.setType(type);
        facture.setNumero(genererNumero(entrepriseId, type));
        facture.setDateEmission(request.dateEmission());
        facture.setDateEcheance(request.dateEcheance());
        facture.setObjet(request.objet());
        facture.setStatut(StatutFacture.BROUILLON);
        ajouterLignes(facture, request.lignes());

        return FactureDto.from(factureRepository.save(facture));
    }

    @Transactional(readOnly = true)
    public List<FactureDto> lister(UUID entrepriseId, TypeFacture type, UtilisateurPrincipal principal) {
        entrepriseAccessService.verifierAcces(entrepriseId, principal);
        return factureRepository.findByEntrepriseIdAndTypeFetchAll(entrepriseId, type).stream()
                .map(FactureDto::from)
                .toList();
    }

    @Transactional
    public FactureDto envoyer(UUID entrepriseId, UUID factureId, UtilisateurPrincipal principal) {
        Facture facture = charger(entrepriseId, factureId, principal);
        if (facture.getStatut() != StatutFacture.BROUILLON) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Seul un brouillon peut etre envoye");
        }
        facture.setStatut(StatutFacture.ENVOYE);
        return FactureDto.from(factureRepository.save(facture));
    }

    @Transactional
    public FactureDto accepterDevis(UUID entrepriseId, UUID devisId, UtilisateurPrincipal principal) {
        Facture devis = chargerDevis(entrepriseId, devisId, principal);
        devis.setStatut(StatutFacture.ACCEPTE);
        return FactureDto.from(factureRepository.save(devis));
    }

    @Transactional
    public FactureDto refuserDevis(UUID entrepriseId, UUID devisId, UtilisateurPrincipal principal) {
        Facture devis = chargerDevis(entrepriseId, devisId, principal);
        devis.setStatut(StatutFacture.REFUSE);
        return FactureDto.from(factureRepository.save(devis));
    }

    @Transactional
    public FactureDto convertirEnFacture(UUID entrepriseId, UUID devisId, UtilisateurPrincipal principal) {
        Facture devis = chargerDevis(entrepriseId, devisId, principal);
        if (devis.getStatut() != StatutFacture.ACCEPTE) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Seul un devis accepte peut etre converti en facture");
        }

        Facture facture = new Facture();
        facture.setEntreprise(devis.getEntreprise());
        facture.setTiers(devis.getTiers());
        facture.setType(TypeFacture.FACTURE);
        facture.setNumero(genererNumero(entrepriseId, TypeFacture.FACTURE));
        facture.setDateEmission(LocalDate.now());
        facture.setDateEcheance(devis.getDateEcheance());
        facture.setObjet(devis.getObjet());
        facture.setStatut(StatutFacture.BROUILLON);
        facture.setDevisOrigineId(devis.getId());

        for (LigneFacture ligneDevis : devis.getLignes()) {
            LigneFacture ligne = new LigneFacture();
            ligne.setDesignation(ligneDevis.getDesignation());
            ligne.setQuantite(ligneDevis.getQuantite());
            ligne.setPrixUnitaire(ligneDevis.getPrixUnitaire());
            ligne.setTauxTva(ligneDevis.getTauxTva());
            ligne.setArticleId(ligneDevis.getArticleId());
            facture.ajouterLigne(ligne);
        }

        facture = factureRepository.save(facture);

        devis.setStatut(StatutFacture.CONVERTI);
        factureRepository.save(devis);

        return FactureDto.from(facture);
    }

    @Transactional
    public FactureDto validerFacture(UUID entrepriseId, UUID factureId, UUID compteProduitId, UtilisateurPrincipal principal) {
        Facture facture = chargerFacture(entrepriseId, factureId, principal);
        if (facture.getStatut() == StatutFacture.VALIDEE || facture.getStatut() == StatutFacture.PAYEE) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Cette facture est deja validee");
        }

        CompteComptable compteProduit = compteComptableRepository.findById(compteProduitId)
                .filter(c -> c.getEntreprise().getId().equals(entrepriseId))
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Compte de produit introuvable"));

        ExerciceComptable exercice = exerciceComptableRepository.findByEntrepriseIdAndDate(entrepriseId, facture.getDateEmission())
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY,
                        "Aucun exercice comptable ne couvre la date " + facture.getDateEmission()));
        if (exercice.getStatut() != StatutExercice.OUVERT) {
            throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY, "L'exercice couvrant cette date est cloture");
        }

        Journal journal = journalRepository.findByEntrepriseIdAndCode(entrepriseId, "VTE")
                .orElseThrow(() -> new IllegalStateException("Journal VTE introuvable, l'entreprise n'a pas ete initialisee correctement"));
        Utilisateur utilisateur = utilisateurRepository.getReferenceById(principal.getId());

        BigDecimal montantHt = facture.montantHt();
        BigDecimal montantTva = facture.montantTva();
        BigDecimal montantTtc = facture.montantTtc();

        EcritureComptable ecriture = new EcritureComptable();
        ecriture.setEntreprise(facture.getEntreprise());
        ecriture.setExercice(exercice);
        ecriture.setJournal(journal);
        ecriture.setDateEcriture(facture.getDateEmission());
        ecriture.setLibelle("Facture " + facture.getNumero() + " - " + facture.getTiers().getNom());
        ecriture.setCreatedBy(utilisateur);
        ecriture.setStatut(StatutEcriture.VALIDEE);
        ecriture.setNumeroPiece(genererNumeroPieceEcriture(journal));

        LigneEcriture ligneClient = LigneEcriture.debit(facture.getTiers().getCompteComptable(), montantTtc, ecriture.getLibelle());
        ligneClient.setTiers(facture.getTiers());
        ecriture.ajouterLigne(ligneClient);

        ecriture.ajouterLigne(LigneEcriture.credit(compteProduit, montantHt, ecriture.getLibelle()));

        if (montantTva.signum() > 0) {
            CompteComptable compteTva = compteComptableRepository.findByEntrepriseIdAndNumero(entrepriseId, COMPTE_TVA_COLLECTEE)
                    .orElseThrow(() -> new IllegalStateException("Compte TVA 443 introuvable, l'entreprise n'a pas ete initialisee correctement"));
            ecriture.ajouterLigne(LigneEcriture.credit(compteTva, montantTva, ecriture.getLibelle()));
        }

        if (!ecriture.estEquilibree()) {
            throw new IllegalStateException("Ecriture de facturation desequilibree, validation annulee");
        }
        ecriture = ecritureComptableRepository.save(ecriture);

        destockerLignesArticle(entrepriseId, facture, principal);

        facture.setStatut(StatutFacture.VALIDEE);
        facture.setEcritureId(ecriture.getId());
        facture.setCompteProduitId(compteProduitId);
        return FactureDto.from(factureRepository.save(facture));
    }

    /**
     * Pour chaque ligne liee a un article de stock, declenche la sortie correspondante au PUMP
     * en vigueur (MouvementStockService verifie la disponibilite et leve StockInsuffisantException
     * sinon) et trace le mouvement genere sur la ligne pour permettre une reintegration exacte en
     * cas d'annulation. Les lignes sans articleId (prestations, services) ne sont pas concernees.
     */
    private void destockerLignesArticle(UUID entrepriseId, Facture facture, UtilisateurPrincipal principal) {
        for (LigneFacture ligne : facture.getLignes()) {
            if (ligne.getArticleId() == null) {
                continue;
            }
            MouvementStockDto mouvement = mouvementStockService.enregistrer(entrepriseId,
                    new CreerMouvementStockRequest(ligne.getArticleId(), TypeMouvementStock.SORTIE, ligne.getQuantite(), null,
                            facture.getDateEmission(), "Vente " + facture.getNumero() + " - " + facture.getTiers().getNom()),
                    principal);
            ligne.setMouvementStockId(mouvement.id());
        }
    }

    @Transactional
    public FactureDto annulerFacture(UUID entrepriseId, UUID factureId, UtilisateurPrincipal principal) {
        Facture facture = chargerFacture(entrepriseId, factureId, principal);
        if (facture.getStatut() != StatutFacture.VALIDEE) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Seule une facture validee peut etre annulee");
        }

        for (LigneFacture ligne : facture.getLignes()) {
            if (ligne.getMouvementStockId() == null) {
                continue;
            }
            MouvementStock sortieOrigine = mouvementStockService.chargerEntite(entrepriseId, ligne.getMouvementStockId());
            mouvementStockService.enregistrer(entrepriseId,
                    new CreerMouvementStockRequest(sortieOrigine.getArticle().getId(), TypeMouvementStock.ENTREE,
                            sortieOrigine.getQuantite(), sortieOrigine.getPrixUnitaireHt(), LocalDate.now(),
                            "Retour stock - annulation " + facture.getNumero()),
                    principal);
        }

        facture.setStatut(StatutFacture.ANNULEE);
        return FactureDto.from(factureRepository.save(facture));
    }

    @Transactional
    public FactureDto marquerPayee(UUID entrepriseId, UUID factureId, UtilisateurPrincipal principal) {
        Facture facture = chargerFacture(entrepriseId, factureId, principal);
        if (facture.getStatut() != StatutFacture.VALIDEE) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Seule une facture validee peut etre marquee payee");
        }
        facture.setStatut(StatutFacture.PAYEE);
        return FactureDto.from(factureRepository.save(facture));
    }

    private void ajouterLignes(Facture facture, List<LigneFactureRequest> lignes) {
        for (LigneFactureRequest ligneRequest : lignes) {
            LigneFacture ligne = new LigneFacture();
            ligne.setDesignation(ligneRequest.designation());
            ligne.setQuantite(ligneRequest.quantite());
            ligne.setPrixUnitaire(ligneRequest.prixUnitaire());
            ligne.setTauxTva(ligneRequest.tauxTva());
            ligne.setArticleId(ligneRequest.articleId());
            facture.ajouterLigne(ligne);
        }
    }

    private String genererNumero(UUID entrepriseId, TypeFacture type) {
        String prefixe = type == TypeFacture.DEVIS ? "DEV" : "FAC";
        long sequence = factureRepository.countByEntrepriseIdAndType(entrepriseId, type) + 1;
        return "%s-%d-%04d".formatted(prefixe, LocalDate.now().getYear(), sequence);
    }

    private String genererNumeroPieceEcriture(Journal journal) {
        long sequence = ecritureComptableRepository.countByJournalId(journal.getId()) + 1;
        return "%s-%d-%04d".formatted(journal.getCode(), LocalDate.now().getYear(), sequence);
    }

    private Facture charger(UUID entrepriseId, UUID factureId, UtilisateurPrincipal principal) {
        entrepriseAccessService.verifierAcces(entrepriseId, principal);
        Facture facture = factureRepository.findById(factureId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Introuvable"));
        if (!facture.getEntreprise().getId().equals(entrepriseId)) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Introuvable");
        }
        return facture;
    }

    private Facture chargerDevis(UUID entrepriseId, UUID devisId, UtilisateurPrincipal principal) {
        Facture devis = charger(entrepriseId, devisId, principal);
        if (devis.getType() != TypeFacture.DEVIS) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Ce document n'est pas un devis");
        }
        return devis;
    }

    private Facture chargerFacture(UUID entrepriseId, UUID factureId, UtilisateurPrincipal principal) {
        Facture facture = charger(entrepriseId, factureId, principal);
        if (facture.getType() != TypeFacture.FACTURE) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Ce document n'est pas une facture");
        }
        return facture;
    }
}
