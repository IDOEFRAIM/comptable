package com.example.comptable.comptabilite.service;

import com.example.comptable.comptabilite.domain.CompteComptable;
import com.example.comptable.comptabilite.domain.EcritureComptable;
import com.example.comptable.comptabilite.domain.ExerciceComptable;
import com.example.comptable.comptabilite.domain.Facture;
import com.example.comptable.comptabilite.domain.FactureAvoir;
import com.example.comptable.comptabilite.domain.Journal;
import com.example.comptable.comptabilite.domain.LigneEcriture;
import com.example.comptable.comptabilite.domain.LigneFacture;
import com.example.comptable.comptabilite.domain.LigneFactureAvoir;
import com.example.comptable.comptabilite.domain.StatutEcriture;
import com.example.comptable.comptabilite.domain.StatutExercice;
import com.example.comptable.comptabilite.domain.StatutFacture;
import com.example.comptable.comptabilite.domain.StatutFactureAvoir;
import com.example.comptable.comptabilite.domain.TypeFacture;
import com.example.comptable.comptabilite.repository.CompteComptableRepository;
import com.example.comptable.comptabilite.repository.EcritureComptableRepository;
import com.example.comptable.comptabilite.repository.ExerciceComptableRepository;
import com.example.comptable.comptabilite.repository.FactureAvoirRepository;
import com.example.comptable.comptabilite.repository.FactureRepository;
import com.example.comptable.comptabilite.repository.JournalRepository;
import com.example.comptable.comptabilite.web.dto.CreerFactureAvoirRequest;
import com.example.comptable.comptabilite.web.dto.FactureAvoirDto;
import com.example.comptable.comptabilite.web.dto.LigneAvoirRequest;
import com.example.comptable.entreprise.domain.Entreprise;
import com.example.comptable.entreprise.service.EntrepriseAccessService;
import com.example.comptable.identite.domain.Utilisateur;
import com.example.comptable.identite.repository.UtilisateurRepository;
import com.example.comptable.security.UtilisateurPrincipal;
import com.example.comptable.stock.domain.Article;
import com.example.comptable.stock.domain.TypeMouvementStock;
import com.example.comptable.stock.repository.ArticleRepository;
import com.example.comptable.stock.service.MouvementStockService;
import com.example.comptable.stock.web.dto.CreerMouvementStockRequest;
import lombok.RequiredArgsConstructor;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.font.PDFont;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.apache.pdfbox.pdmodel.font.Standard14Fonts;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.math.BigDecimal;
import java.text.Normalizer;
import java.time.LocalDate;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Avoirs commerciaux : extourne totale ou partielle d'une facture de vente VALIDEE ou PAYEE.
 * La validation genere l'ecriture inverse exacte de la vente (701/706 + 443 au debit, 411 au
 * credit) en s'appuyant sur {@link Facture#getCompteProduitId()} trace lors de la validation de
 * la facture d'origine - sans cette trace, impossible de savoir quel compte de produit extourner.
 * Si {@code retourStock}, chaque ligne dont la ligne d'origine porte un article est reintegree en
 * stock au PUMP courant via {@link MouvementStockService} (une entree valorisee au PUMP en
 * vigueur ne modifie pas ce PUMP, cf. PumpCalculator).
 */
@Service
@RequiredArgsConstructor
public class FactureAvoirService {

    private static final String COMPTE_TVA_COLLECTEE = "443";

    private final EntrepriseAccessService entrepriseAccessService;
    private final FactureRepository factureRepository;
    private final FactureAvoirRepository factureAvoirRepository;
    private final CompteComptableRepository compteComptableRepository;
    private final ExerciceComptableRepository exerciceComptableRepository;
    private final JournalRepository journalRepository;
    private final EcritureComptableRepository ecritureComptableRepository;
    private final UtilisateurRepository utilisateurRepository;
    private final MouvementStockService mouvementStockService;
    private final ArticleRepository articleRepository;
    private final LettrageService lettrageService;

    @Transactional
    public FactureAvoirDto creerAvoir(UUID entrepriseId, UUID factureId, CreerFactureAvoirRequest request,
                                       UtilisateurPrincipal principal) {
        Entreprise entreprise = entrepriseAccessService.verifierAcces(entrepriseId, principal);
        Facture factureOrigine = factureRepository.findById(factureId)
                .filter(f -> f.getEntreprise().getId().equals(entrepriseId))
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Facture introuvable"));
        if (factureOrigine.getType() != TypeFacture.FACTURE) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Un avoir ne peut etre emis que sur une facture");
        }
        if (factureOrigine.getStatut() != StatutFacture.VALIDEE && factureOrigine.getStatut() != StatutFacture.PAYEE) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "Seule une facture validee ou payee peut etre creditee d'un avoir");
        }

        Map<UUID, BigDecimal> dejaCredite = quantitesDejaCreditees(factureId);

        FactureAvoir avoir = new FactureAvoir();
        avoir.setEntreprise(entreprise);
        avoir.setFactureOrigine(factureOrigine);
        avoir.setTiers(factureOrigine.getTiers());
        avoir.setNumero(genererNumero(entrepriseId));
        avoir.setDateEmission(LocalDate.now());
        avoir.setMotif(request.motif());
        avoir.setRetourStock(request.retourStock());
        avoir.setStatut(StatutFactureAvoir.BROUILLON);

        if (Boolean.TRUE.equals(request.total())) {
            for (LigneFacture ligneOrigine : factureOrigine.getLignes()) {
                BigDecimal restant = ligneOrigine.getQuantite().subtract(dejaCredite.getOrDefault(ligneOrigine.getId(), BigDecimal.ZERO));
                if (restant.signum() <= 0) {
                    continue;
                }
                avoir.ajouterLigne(construireLigneAvoir(ligneOrigine, restant));
            }
            if (avoir.getLignes().isEmpty()) {
                throw new ResponseStatusException(HttpStatus.CONFLICT, "Cette facture est deja integralement creditee");
            }
        } else {
            if (request.lignes() == null || request.lignes().isEmpty()) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Un avoir partiel doit selectionner au moins une ligne");
            }
            for (LigneAvoirRequest ligneRequest : request.lignes()) {
                LigneFacture ligneOrigine = factureOrigine.getLignes().stream()
                        .filter(l -> l.getId().equals(ligneRequest.ligneFactureId()))
                        .findFirst()
                        .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Ligne de facture introuvable"));
                BigDecimal restant = ligneOrigine.getQuantite().subtract(dejaCredite.getOrDefault(ligneOrigine.getId(), BigDecimal.ZERO));
                if (ligneRequest.quantite().compareTo(restant) > 0) {
                    throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY,
                            "Quantite a crediter (%s) superieure au restant creditable (%s) pour cette ligne"
                                    .formatted(ligneRequest.quantite(), restant));
                }
                avoir.ajouterLigne(construireLigneAvoir(ligneOrigine, ligneRequest.quantite()));
            }
        }

        return FactureAvoirDto.from(factureAvoirRepository.save(avoir));
    }

    @Transactional(readOnly = true)
    public List<FactureAvoirDto> lister(UUID entrepriseId, UtilisateurPrincipal principal) {
        entrepriseAccessService.verifierAcces(entrepriseId, principal);
        return factureAvoirRepository.findByEntrepriseIdFetchAll(entrepriseId).stream()
                .map(FactureAvoirDto::from)
                .toList();
    }

    @Transactional
    public FactureAvoirDto validerAvoir(UUID entrepriseId, UUID avoirId, UtilisateurPrincipal principal) {
        entrepriseAccessService.verifierAcces(entrepriseId, principal);
        FactureAvoir avoir = chargerAvoir(entrepriseId, avoirId);
        if (avoir.getStatut() != StatutFactureAvoir.BROUILLON) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Cet avoir est deja valide");
        }

        Facture factureOrigine = avoir.getFactureOrigine();
        UUID compteProduitId = factureOrigine.getCompteProduitId();
        if (compteProduitId == null) {
            throw new IllegalStateException("La facture d'origine n'a pas de compte de produit trace, avoir impossible");
        }
        CompteComptable compteProduit = compteComptableRepository.findById(compteProduitId)
                .orElseThrow(() -> new IllegalStateException("Compte de produit de la facture d'origine introuvable"));

        BigDecimal montantHt = avoir.montantHt();
        BigDecimal montantTva = avoir.montantTva();
        BigDecimal montantTtc = avoir.montantTtc();

        ExerciceComptable exercice = exerciceComptableRepository.findByEntrepriseIdAndDate(entrepriseId, avoir.getDateEmission())
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY,
                        "Aucun exercice comptable ne couvre la date " + avoir.getDateEmission()));
        if (exercice.getStatut() != StatutExercice.OUVERT) {
            throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY, "L'exercice couvrant cette date est cloture");
        }
        Journal journal = journalRepository.findByEntrepriseIdAndCode(entrepriseId, "VTE")
                .orElseThrow(() -> new IllegalStateException("Journal VTE introuvable, l'entreprise n'a pas ete initialisee correctement"));
        Utilisateur utilisateur = utilisateurRepository.getReferenceById(principal.getId());
        String libelle = "Avoir " + avoir.getNumero() + " - " + avoir.getTiers().getNom() + " (facture " + factureOrigine.getNumero() + ")";

        EcritureComptable ecriture = new EcritureComptable();
        ecriture.setEntreprise(avoir.getEntreprise());
        ecriture.setExercice(exercice);
        ecriture.setJournal(journal);
        ecriture.setDateEcriture(avoir.getDateEmission());
        ecriture.setLibelle(libelle);
        ecriture.setCreatedBy(utilisateur);
        ecriture.setStatut(StatutEcriture.VALIDEE);
        ecriture.setNumeroPiece(genererNumeroPieceEcriture(journal));

        ecriture.ajouterLigne(LigneEcriture.debit(compteProduit, montantHt, libelle));
        if (montantTva.signum() > 0) {
            CompteComptable compteTva = compteComptableRepository.findByEntrepriseIdAndNumero(entrepriseId, COMPTE_TVA_COLLECTEE)
                    .orElseThrow(() -> new IllegalStateException(
                            "Compte TVA 443 introuvable, l'entreprise n'a pas ete initialisee correctement"));
            ecriture.ajouterLigne(LigneEcriture.debit(compteTva, montantTva, libelle));
        }
        LigneEcriture ligneClient = LigneEcriture.credit(avoir.getTiers().getCompteComptable(), montantTtc, libelle);
        ligneClient.setTiers(avoir.getTiers());
        ecriture.ajouterLigne(ligneClient);

        if (!ecriture.estEquilibree()) {
            throw new IllegalStateException("Ecriture d'avoir desequilibree, validation annulee");
        }
        ecriture = ecritureComptableRepository.save(ecriture);

        if (avoir.isRetourStock()) {
            reintegrerStock(entrepriseId, avoir, principal);
        }

        avoir.setStatut(StatutFactureAvoir.VALIDEE);
        avoir.setEcritureId(ecriture.getId());
        factureAvoirRepository.save(avoir);

        // Best-effort : compense automatiquement l'avoir avec la facture d'origine sur le compte
        // client si les deux lignes s'annulent exactement (avoir total). Ne fait rien sinon (avoir
        // partiel, ou compte deja plus complexe) - la balance agee/lettrage manuel restent la voie
        // normale pour ces cas, cf. LettrageService deja utilise partout ailleurs dans l'appli.
        lettrageService.lettrerAutomatiquement(entrepriseId, avoir.getTiers().getCompteComptable().getId(), principal);

        return FactureAvoirDto.from(avoir);
    }

    @Transactional(readOnly = true)
    public byte[] genererPdf(UUID entrepriseId, UUID avoirId, UtilisateurPrincipal principal) {
        entrepriseAccessService.verifierAcces(entrepriseId, principal);
        FactureAvoir avoir = chargerAvoir(entrepriseId, avoirId);

        try (PDDocument document = new PDDocument()) {
            PDPage page = new PDPage(PDRectangle.A4);
            document.addPage(page);
            PDFont fontTitre = new PDType1Font(Standard14Fonts.FontName.HELVETICA_BOLD);
            PDFont fontTexte = new PDType1Font(Standard14Fonts.FontName.HELVETICA);
            PDFont fontGras = new PDType1Font(Standard14Fonts.FontName.HELVETICA_BOLD);

            try (PDPageContentStream cs = new PDPageContentStream(document, page)) {
                float y = 800;
                float marge = 50;

                y = ecrireLigne(cs, fontTitre, 18, marge, y, "FACTURE D'AVOIR");
                y -= 12;
                y = ecrireLigne(cs, fontGras, 12, marge, y, "No " + avoir.getNumero() + " du " + avoir.getDateEmission());
                y -= 5;
                y = ecrireLigne(cs, fontTexte, 11, marge, y, asciiSafe(
                        "Avoir annulant la facture No " + avoir.getFactureOrigine().getNumero()
                                + " du " + avoir.getFactureOrigine().getDateEmission()));
                y = ecrireLigne(cs, fontTexte, 11, marge, y, "Client : " + asciiSafe(avoir.getTiers().getNom()));
                if (avoir.getMotif() != null && !avoir.getMotif().isBlank()) {
                    y = ecrireLigne(cs, fontTexte, 11, marge, y, "Motif : " + asciiSafe(avoir.getMotif()));
                }
                y -= 15;

                cs.beginText();
                cs.setFont(fontGras, 10);
                cs.newLineAtOffset(marge, y);
                cs.showText("Designation");
                cs.newLineAtOffset(220, 0);
                cs.showText("Qte");
                cs.newLineAtOffset(50, 0);
                cs.showText("PU HT");
                cs.newLineAtOffset(70, 0);
                cs.showText("Montant HT");
                cs.endText();
                y -= 15;

                for (LigneFactureAvoir ligne : avoir.getLignes()) {
                    cs.beginText();
                    cs.setFont(fontTexte, 10);
                    cs.newLineAtOffset(marge, y);
                    cs.showText(tronquer(asciiSafe(ligne.getDesignation()), 38));
                    cs.newLineAtOffset(220, 0);
                    cs.showText(ligne.getQuantite().toPlainString());
                    cs.newLineAtOffset(50, 0);
                    cs.showText(ligne.getPrixUnitaire().toPlainString());
                    cs.newLineAtOffset(70, 0);
                    cs.showText("-" + ligne.montantHt().toPlainString());
                    cs.endText();
                    y -= 15;
                }

                y -= 15;
                y = ecrireLigne(cs, fontGras, 11, marge, y, "Total HT : -" + avoir.montantHt().toPlainString() + " FCFA");
                y = ecrireLigne(cs, fontGras, 11, marge, y, "Total TVA : -" + avoir.montantTva().toPlainString() + " FCFA");
                ecrireLigne(cs, fontGras, 13, marge, y,
                        "Total TTC : -" + avoir.montantTtc().toPlainString() + " FCFA (a deduire du compte client)");
            }

            ByteArrayOutputStream out = new ByteArrayOutputStream();
            document.save(out);
            return out.toByteArray();
        } catch (IOException e) {
            throw new IllegalStateException("Impossible de generer le PDF de l'avoir", e);
        }
    }

    private float ecrireLigne(PDPageContentStream cs, PDFont font, float taille, float x, float y, String texte) throws IOException {
        cs.beginText();
        cs.setFont(font, taille);
        cs.newLineAtOffset(x, y);
        cs.showText(texte);
        cs.endText();
        return y - (taille + 6);
    }

    private String tronquer(String texte, int max) {
        return texte.length() > max ? texte.substring(0, max) : texte;
    }

    /** Remplace tout caractere hors ASCII imprimable (accents compris, via decomposition NFD)
     * pour eviter une IllegalArgumentException de PDFBox sur un caractere absent de l'encodage
     * standard des polices Helvetica embarquees. */
    private String asciiSafe(String texte) {
        if (texte == null) {
            return "";
        }
        String normalise = Normalizer.normalize(texte, Normalizer.Form.NFD);
        return normalise.replaceAll("\\p{M}", "").replaceAll("[^\\x20-\\x7E]", "?");
    }

    private void reintegrerStock(UUID entrepriseId, FactureAvoir avoir, UtilisateurPrincipal principal) {
        for (LigneFactureAvoir ligne : avoir.getLignes()) {
            UUID articleId = ligne.getLigneFactureOrigine().getArticleId();
            if (articleId == null) {
                continue;
            }
            Article article = articleRepository.findById(articleId)
                    .filter(a -> a.getEntreprise().getId().equals(entrepriseId))
                    .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Article introuvable"));
            mouvementStockService.enregistrer(entrepriseId,
                    new CreerMouvementStockRequest(articleId, TypeMouvementStock.ENTREE, ligne.getQuantite(), article.getPump(),
                            avoir.getDateEmission(), "Retour client - avoir " + avoir.getNumero()),
                    principal);
        }
    }

    private LigneFactureAvoir construireLigneAvoir(LigneFacture ligneOrigine, BigDecimal quantite) {
        LigneFactureAvoir ligne = new LigneFactureAvoir();
        ligne.setLigneFactureOrigine(ligneOrigine);
        ligne.setDesignation(ligneOrigine.getDesignation());
        ligne.setQuantite(quantite);
        ligne.setPrixUnitaire(ligneOrigine.getPrixUnitaire());
        ligne.setTauxTva(ligneOrigine.getTauxTva());
        return ligne;
    }

    private Map<UUID, BigDecimal> quantitesDejaCreditees(UUID factureId) {
        Map<UUID, BigDecimal> resultat = new HashMap<>();
        for (Object[] ligne : factureAvoirRepository.sommeQuantitesCrediteesParLigne(factureId, StatutFactureAvoir.VALIDEE)) {
            resultat.put((UUID) ligne[0], (BigDecimal) ligne[1]);
        }
        return resultat;
    }

    private String genererNumero(UUID entrepriseId) {
        long sequence = factureAvoirRepository.countByEntrepriseId(entrepriseId) + 1;
        return "AV-%d-%04d".formatted(LocalDate.now().getYear(), sequence);
    }

    private String genererNumeroPieceEcriture(Journal journal) {
        long sequence = ecritureComptableRepository.countByJournalId(journal.getId()) + 1;
        return "%s-%d-%04d".formatted(journal.getCode(), LocalDate.now().getYear(), sequence);
    }

    private FactureAvoir chargerAvoir(UUID entrepriseId, UUID avoirId) {
        FactureAvoir avoir = factureAvoirRepository.findById(avoirId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Avoir introuvable"));
        if (!avoir.getEntreprise().getId().equals(entrepriseId)) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Avoir introuvable");
        }
        return avoir;
    }
}
