package com.example.comptable.comptabilite.service;

import com.example.comptable.comptabilite.domain.Facture;
import com.example.comptable.comptabilite.domain.LigneFacture;
import com.example.comptable.comptabilite.domain.StatutFacture;
import com.example.comptable.comptabilite.domain.Tiers;
import com.example.comptable.comptabilite.domain.TypeFacture;
import com.example.comptable.comptabilite.repository.FactureRepository;
import com.example.comptable.entreprise.domain.Entreprise;
import com.example.comptable.entreprise.service.EntrepriseAccessService;
import com.example.comptable.security.UtilisateurPrincipal;
import lombok.RequiredArgsConstructor;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.font.PDFont;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.apache.pdfbox.pdmodel.font.Standard14Fonts;
import org.apache.pdfbox.util.Matrix;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.UUID;

/**
 * Le devis ou la facture imprimable, à envoyer au client : l'émetteur avec ses mentions légales (IFU, RCCM, forme juridique, régime fiscal, adresse, contacts), le client, les lignes, les totaux
 * avec la TVA par taux, le montant en toutes lettres, les coordonnées bancaires et les mentions libres de l'entreprise.
 * <p>
 * Un document encore en brouillon est marqué « BROUILLON » : il ne peut pas être pris pour une facture valable. Ce document ne prétend pas être « facture normalisée » au sens d'une réglementation
 * locale : il présente fidèlement ce qui est enregistré. Les mentions manquantes (IFU, RCCM, adresse) restent visibles comme absentes de la fiche de l'entreprise, jamais inventées.
 */
@Service
@RequiredArgsConstructor
public class FactureDocumentPdfService {

    private static final DateTimeFormatter DATE = DateTimeFormatter.ofPattern("dd/MM/yyyy");
    private static final float MARGE = 45f;
    private static final float LARGEUR = PDRectangle.A4.getWidth();
    private static final float HAUT = PDRectangle.A4.getHeight();
    private static final float BAS = 60f;

    private final EntrepriseAccessService entrepriseAccessService;
    private final FactureRepository factureRepository;

    @Transactional(readOnly = true)
    public byte[] generer(UUID entrepriseId, UUID factureId, UtilisateurPrincipal principal) {
        Entreprise entreprise = entrepriseAccessService.verifierAcces(entrepriseId, principal);
        Facture facture = factureRepository.findById(factureId).filter((f) -> f.getEntreprise().getId().equals(entrepriseId))
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Document introuvable"));
        try (PDDocument document = new PDDocument()) {
            new Mise(document, entreprise, facture).dessiner();
            ByteArrayOutputStream sortie = new ByteArrayOutputStream();
            document.save(sortie);
            return sortie.toByteArray();
        } catch (IOException e) {
            throw new IllegalStateException("Impossible de générer le PDF du document", e);
        }
    }

    public static String nomFichier(Facture facture) {
        return facture.getNumero().replaceAll("[^A-Za-z0-9._-]", "_") + ".pdf";
    }

    // ------------------------------------------------------------------------------------------------------------------------------------------------------------ mise en page

    private static final class Mise {
        private final PDDocument document;
        private final Entreprise e;
        private final Facture f;
        private final PDFont normal = new PDType1Font(Standard14Fonts.FontName.HELVETICA);
        private final PDFont gras = new PDType1Font(Standard14Fonts.FontName.HELVETICA_BOLD);
        private final boolean devis;
        private final boolean brouillon;
        private PDPageContentStream cs;
        private float y;
        private final List<PDPage> pages = new ArrayList<>();

        Mise(PDDocument document, Entreprise e, Facture f) {
            this.document = document;
            this.e = e;
            this.f = f;
            this.devis = f.getType() == TypeFacture.DEVIS;
            this.brouillon = f.getStatut() == StatutFacture.BROUILLON;
        }

        void dessiner() throws IOException {
            nouvellePage();
            enTete();
            blocsParties();
            tableauLignes();
            totaux();
            pied();
            cs.close();
            numeroterPages();
        }

        // ---- sections

        private void enTete() throws IOException {
            texte(gras, 15, MARGE, y, nettoyer(e.getRaisonSociale()));
            float droite = LARGEUR - MARGE;
            String titre = devis ? "DEVIS" : "FACTURE";
            texteADroite(gras, 20, droite, y, titre);
            y -= 17;
            String forme = ligneNonVide(e.getFormeJuridique());
            if (forme != null) {
                texte(normal, 9, MARGE, y, nettoyer(forme));
            }
            texteADroite(gras, 11, droite, y, "N° " + nettoyer(f.getNumero()));
            y -= 12;
            List<String> identite = new ArrayList<>();
            identite.add("IFU : " + valeur(e.getNumeroContribuable()));
            identite.add("RCCM : " + valeur(e.getRccm()));
            if (ligneNonVide(e.getRegimeFiscal()) != null) {
                identite.add("Régime : " + e.getRegimeFiscal().trim());
            }
            texte(normal, 9, MARGE, y, nettoyer(String.join("   ", identite)));
            texteADroite(normal, 9, droite, y, "Date : " + f.getDateEmission().format(DATE));
            y -= 12;
            if (ligneNonVide(e.getAdresse()) != null) {
                texte(normal, 9, MARGE, y, nettoyer(e.getAdresse().trim()));
            }
            if (f.getDateEcheance() != null) {
                texteADroite(normal, 9, droite, y, (devis ? "Valable jusqu'au : " : "Échéance : ") + f.getDateEcheance().format(DATE));
            }
            y -= 12;
            List<String> contacts = new ArrayList<>();
            if (ligneNonVide(e.getTelephone()) != null) {
                contacts.add("Tél : " + e.getTelephone().trim());
            }
            if (ligneNonVide(e.getEmail()) != null) {
                contacts.add(e.getEmail().trim());
            }
            if (!contacts.isEmpty()) {
                texte(normal, 9, MARGE, y, nettoyer(String.join("   ", contacts)));
                y -= 12;
            }
            if (brouillon) {
                texteADroite(gras, 9, droite, y + 12, "BROUILLON : document non validé");
            }
            y -= 6;
            trait(y);
            y -= 18;
        }

        private void blocsParties() throws IOException {
            Tiers t = f.getTiers();
            texte(gras, 9, MARGE, y, devis ? "DEVIS ÉTABLI POUR" : "FACTURÉ À");
            y -= 14;
            texte(gras, 12, MARGE, y, nettoyer(t.getNom()));
            y -= 13;
            if (ligneNonVide(t.getAdresse()) != null) {
                for (String l : couper(normal, 10, nettoyer(t.getAdresse().trim()), 300)) {
                    texte(normal, 10, MARGE, y, l);
                    y -= 12;
                }
            }
            if (ligneNonVide(t.getNumeroContribuable()) != null) {
                texte(normal, 10, MARGE, y, "IFU : " + nettoyer(t.getNumeroContribuable().trim()));
                y -= 12;
            }
            if (ligneNonVide(f.getObjet()) != null) {
                y -= 4;
                for (String l : couper(normal, 10, "Objet : " + nettoyer(f.getObjet().trim()), LARGEUR - 2 * MARGE)) {
                    texte(normal, 10, MARGE, y, l);
                    y -= 12;
                }
            }
            y -= 10;
        }

        private static final float[] COL = {MARGE, 330, 375, 450, 500}; // désignation, quantité, prix unitaire, TVA %, total HT (alignés à droite sauf la désignation)

        private void tableauLignes() throws IOException {
            enTeteTableau();
            for (LigneFacture l : f.getLignes()) {
                List<String> libelle = couper(normal, 9.5f, nettoyer(l.getDesignation()), COL[1] - COL[0] - 10);
                float hauteur = libelle.size() * 12 + 6;
                if (y - hauteur < BAS + 40) {
                    nouvellePage();
                    enTeteTableau();
                }
                float haut = y;
                for (String ligne : libelle) {
                    texte(normal, 9.5f, COL[0], y, ligne);
                    y -= 12;
                }
                texteADroite(normal, 9.5f, COL[2] - 8, haut, nombre(l.getQuantite()));
                texteADroite(normal, 9.5f, COL[3] - 8, haut, nombre(l.getPrixUnitaire()));
                texteADroite(normal, 9.5f, COL[4] - 8, haut, nombre(l.getTauxTva()) + " %");
                texteADroite(normal, 9.5f, LARGEUR - MARGE, haut, nombre(l.montantHt()));
                traitFin(y + 7); // le trait passe sous la dernière ligne du libellé, au-dessus de la ligne suivante
                y -= 6;
            }
            y -= 8;
        }

        private void enTeteTableau() throws IOException {
            cs.setNonStrokingColor(0.93f, 0.95f, 0.94f);
            cs.addRect(MARGE, y - 5, LARGEUR - 2 * MARGE, 17);
            cs.fill();
            cs.setNonStrokingColor(0f);
            texte(gras, 9, COL[0] + 3, y, "Désignation");
            texteADroite(gras, 9, COL[2] - 8, y, "Qté");
            texteADroite(gras, 9, COL[3] - 8, y, "Prix unitaire HT");
            texteADroite(gras, 9, COL[4] - 8, y, "TVA");
            texteADroite(gras, 9, LARGEUR - MARGE - 3, y, "Total HT");
            y -= 20;
        }

        private void totaux() throws IOException {
            Map<BigDecimal, BigDecimal> tvaParTaux = new TreeMap<>();
            for (LigneFacture l : f.getLignes()) {
                tvaParTaux.merge(l.getTauxTva().stripTrailingZeros(), l.montantTva(), BigDecimal::add);
            }
            float besoin = 24 + tvaParTaux.size() * 13 + 90;
            if (y - besoin < BAS) {
                nouvellePage();
            }
            float droite = LARGEUR - MARGE;
            float xLibelle = 340;
            ligneTotal("Total HT", f.montantHt(), xLibelle, droite, false);
            for (Map.Entry<BigDecimal, BigDecimal> t : tvaParTaux.entrySet()) {
                if (t.getKey().signum() != 0 || t.getValue().signum() != 0) {
                    ligneTotal("TVA " + nombre(t.getKey()) + " %", t.getValue(), xLibelle, droite, false);
                }
            }
            ligneTotal("Total TTC", f.montantTtc(), xLibelle, droite, true);
            y -= 8;
            String phrase = (devis ? "Arrêté le présent devis à la somme de : " : "Arrêtée la présente facture à la somme de : ") + MontantEnLettres.montant(f.montantTtc(), e.getDevise()) + " TTC.";
            for (String l : couper(gras, 10, nettoyer(phrase), LARGEUR - 2 * MARGE)) {
                texte(gras, 10, MARGE, y, l);
                y -= 13;
            }
            y -= 6;
        }

        private void ligneTotal(String libelle, BigDecimal montant, float xLibelle, float droite, boolean fort) throws IOException {
            PDFont police = fort ? gras : normal;
            float taille = fort ? 11.5f : 10f;
            if (fort) {
                cs.setNonStrokingColor(0.93f, 0.95f, 0.94f);
                cs.addRect(xLibelle - 6, y - 5, droite - xLibelle + 9, 18);
                cs.fill();
                cs.setNonStrokingColor(0f);
            }
            texte(police, taille, xLibelle, y, libelle);
            texteADroite(police, taille, droite - 3, y, nombre(montant) + " " + (e.getDevise().equals("XOF") ? "FCFA" : e.getDevise()));
            y -= fort ? 20 : 14;
        }

        private void pied() throws IOException {
            List<String> blocs = new ArrayList<>();
            if (ligneNonVide(e.getCoordonneesBancaires()) != null) {
                blocs.add("Règlement : " + e.getCoordonneesBancaires().trim());
            }
            if (ligneNonVide(e.getMentionsFacture()) != null) {
                blocs.add(e.getMentionsFacture().trim());
            }
            if (devis) {
                blocs.add("Bon pour accord : date, signature et cachet du client précédés de la mention « Bon pour accord ».");
            }
            int lignes = 0;
            List<List<String>> mises = new ArrayList<>();
            for (String b : blocs) {
                List<String> c = couper(normal, 9, nettoyer(b), LARGEUR - 2 * MARGE);
                mises.add(c);
                lignes += c.size();
            }
            if (y - (lignes * 12 + (devis ? 70 : 10)) < BAS) {
                nouvellePage();
            }
            for (List<String> bloc : mises) {
                for (String l : bloc) {
                    texte(normal, 9, MARGE, y, l);
                    y -= 12;
                }
                y -= 3;
            }
            if (devis) {
                y -= 4;
                cs.addRect(LARGEUR - MARGE - 200, y - 55, 200, 60);
                cs.stroke();
                texte(normal, 8, LARGEUR - MARGE - 195, y - 10, "Signature et cachet du client");
            }
        }

        // ---- primitives

        private void nouvellePage() throws IOException {
            if (cs != null) {
                cs.close();
            }
            PDPage page = new PDPage(PDRectangle.A4);
            document.addPage(page);
            pages.add(page);
            cs = new PDPageContentStream(document, page);
            y = HAUT - 50;
            if (brouillon) {
                filigrane();
            }
        }

        private void filigrane() throws IOException {
            cs.beginText();
            cs.setNonStrokingColor(0.9f, 0.9f, 0.9f);
            cs.setFont(gras, 90);
            cs.setTextMatrix(Matrix.getRotateInstance(Math.toRadians(40), 110, 270));
            cs.showText("BROUILLON");
            cs.endText();
            cs.setNonStrokingColor(0f);
        }

        private void numeroterPages() throws IOException {
            for (int i = 0; i < pages.size(); i++) {
                try (PDPageContentStream c = new PDPageContentStream(document, pages.get(i), PDPageContentStream.AppendMode.APPEND, true)) {
                    String pied = nettoyer(e.getRaisonSociale()) + "  -  " + (devis ? "Devis " : "Facture ") + nettoyer(f.getNumero()) + "  -  Page " + (i + 1) + " / " + pages.size();
                    c.setNonStrokingColor(0.4f);
                    c.beginText();
                    c.setFont(normal, 8);
                    c.newLineAtOffset((LARGEUR - normal.getStringWidth(pied) / 1000 * 8) / 2, 30);
                    c.showText(pied);
                    c.endText();
                }
            }
        }

        private void texte(PDFont police, float taille, float x, float yy, String texte) throws IOException {
            cs.beginText();
            cs.setFont(police, taille);
            cs.newLineAtOffset(x, yy);
            cs.showText(texte);
            cs.endText();
        }

        private void texteADroite(PDFont police, float taille, float xDroit, float yy, String texte) throws IOException {
            String propre = nettoyer(texte);
            texte(police, taille, xDroit - largeur(police, taille, propre), yy, propre);
        }

        private float largeur(PDFont police, float taille, String texte) throws IOException {
            return police.getStringWidth(texte) / 1000 * taille;
        }

        private void trait(float yy) throws IOException {
            cs.setLineWidth(1f);
            cs.moveTo(MARGE, yy);
            cs.lineTo(LARGEUR - MARGE, yy);
            cs.stroke();
        }

        private void traitFin(float yy) throws IOException {
            cs.setStrokingColor(0.85f);
            cs.setLineWidth(0.5f);
            cs.moveTo(MARGE, yy);
            cs.lineTo(LARGEUR - MARGE, yy);
            cs.stroke();
            cs.setStrokingColor(0f);
        }

        /** Coupe un texte en lignes qui tiennent dans la largeur donnée (retour à la ligne entre les mots). */
        private List<String> couper(PDFont police, float taille, String texte, float largeurMax) throws IOException {
            List<String> lignes = new ArrayList<>();
            StringBuilder courante = new StringBuilder();
            for (String mot : texte.split("\\s+")) {
                String essai = courante.length() == 0 ? mot : courante + " " + mot;
                if (largeur(police, taille, essai) > largeurMax && courante.length() > 0) {
                    lignes.add(courante.toString());
                    courante = new StringBuilder(mot);
                } else {
                    courante = new StringBuilder(essai);
                }
            }
            if (courante.length() > 0) {
                lignes.add(courante.toString());
            }
            return lignes.isEmpty() ? List.of("") : lignes;
        }

        /**
         * Ne garde que les caractères que la police standard sait écrire : les accents français passent (é, è, à, ç, ô…), l'espace insécable et l'espace fine insécable deviennent des espaces, tout le
         * reste devient « ? » au lieu de faire échouer toute l'impression.
         */
        private String nettoyer(String texte) {
            if (texte == null) {
                return "";
            }
            StringBuilder sb = new StringBuilder();
            texte.codePoints().forEach((cp) -> {
                if (cp == 0x00A0 || cp == 0x202F || cp == 0x2009 || cp == '\t' || cp == '\n' || cp == '\r') {
                    sb.append(' ');
                    return;
                }
                if (cp == 0x2019) {
                    sb.append('\'');
                    return;
                }
                String s = new String(Character.toChars(cp));
                try {
                    normal.encode(s);
                    sb.append(s);
                } catch (IOException | IllegalArgumentException ex) {
                    sb.append('?');
                }
            });
            return sb.toString();
        }

        private static String ligneNonVide(String s) {
            return s == null || s.isBlank() ? null : s;
        }

        private static String valeur(String s) {
            return s == null || s.isBlank() ? "non renseigné" : s.trim();
        }
    }

    /** « 1 234 567 » (espaces ordinaires) ; deux décimales seulement quand il y en a. */
    static String nombre(BigDecimal valeur) {
        BigDecimal v = valeur.stripTrailingZeros();
        int echelle = v.scale() > 0 ? 2 : 0;
        String brut = valeur.setScale(echelle, RoundingMode.HALF_UP).abs().toPlainString();
        String entier = brut.contains(".") ? brut.substring(0, brut.indexOf('.')) : brut;
        String decimales = brut.contains(".") ? brut.substring(brut.indexOf('.')).replace('.', ',') : "";
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < entier.length(); i++) {
            if (i > 0 && (entier.length() - i) % 3 == 0) {
                sb.append(' ');
            }
            sb.append(entier.charAt(i));
        }
        return (valeur.signum() < 0 ? "-" : "") + sb + decimales;
    }
}
