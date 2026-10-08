package com.example.comptable.document.service;

import com.example.comptable.comptabilite.domain.Facture;
import com.example.comptable.comptabilite.domain.TypeFacture;
import com.example.comptable.comptabilite.repository.FactureRepository;
import com.example.comptable.document.domain.Document;
import com.example.comptable.document.domain.TypeDocument;
import com.example.comptable.stock.domain.FactureFournisseur;
import com.example.comptable.stock.repository.FactureFournisseurRepository;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;

/**
 * Rapprochement DETERMINISTE Document &lt;-&gt; Facture (Phase C, lot C8), dans la philosophie de
 * {@code MatchDecisionEngine} : la regle decide, l'IA n'intervient que pour lire la piece.
 *
 * <p>Facture d'achat ({@link FactureFournisseur}) - son {@code numero} est INTERNE, le numero du fournisseur est
 * {@code numeroFournisseur} (facultatif) :
 * <ul>
 *   <li>numeros des deux cotes presents : egaux et meme TTC = {@code EXACT} ; egaux mais TTC different = ecart de montant
 *       (jamais un rapprochement) ; differents = autre facture ;</li>
 *   <li>numero absent d'un cote : meme TTC ET date a moins de {@link #FENETRE_JOURS} jours = {@code PROBABLE}.</li>
 * </ul>
 * Facture de vente ({@link Facture}) : le numero est le notre, il doit etre present et egal ; meme TTC = {@code EXACT}.
 *
 * <p>Decision globale : un seul EXACT -&gt; {@code EXACT} ; plusieurs -&gt; {@code AMBIGUOUS} ; sinon un seul PROBABLE -&gt;
 * {@code PROBABLE}, plusieurs -&gt; {@code AMBIGUOUS} ; aucun -&gt; {@code UNMATCHED}. Jamais « la premiere ».</p>
 */
@Component
public class DocumentMatcher {

    public static final int FENETRE_JOURS = 5;
    static final BigDecimal TOLERANCE_MONTANT = new BigDecimal("0.01");

    public enum Decision { EXACT, PROBABLE, AMBIGUOUS, UNMATCHED }

    public enum Niveau { EXACT, PROBABLE, POSSIBLE }

    public static final String CIBLE_FACTURE_FOURNISSEUR = "FACTURE_FOURNISSEUR";
    public static final String CIBLE_FACTURE = "FACTURE";

    /** Une facture candidate, avec le niveau auquel elle correspond a la piece. */
    public record Candidat(String cibleType, UUID id, String numero, String numeroFournisseur, LocalDate date,
                           BigDecimal montantTtc, UUID ecritureId, Niveau niveau, String raison) {
    }

    public record Proposition(Decision decision, List<Candidat> candidats) {
        public Candidat unique() {
            return candidats.size() == 1 ? candidats.get(0) : null;
        }
    }

    private final FactureFournisseurRepository factureFournisseurRepository;
    private final FactureRepository factureRepository;

    public DocumentMatcher(FactureFournisseurRepository factureFournisseurRepository, FactureRepository factureRepository) {
        this.factureFournisseurRepository = factureFournisseurRepository;
        this.factureRepository = factureRepository;
    }

    /** La decision du systeme pour cette piece (exclut la candidate que l'utilisateur a deja refusee). */
    public Proposition proposer(Document document) {
        List<Candidat> exacts = new ArrayList<>();
        List<Candidat> probables = new ArrayList<>();
        for (Candidat c : tous(document)) {
            if (c.id().equals(document.getCibleRejeteeId())) {
                continue;
            }
            if (c.niveau() == Niveau.EXACT) {
                exacts.add(c);
            } else if (c.niveau() == Niveau.PROBABLE) {
                probables.add(c);
            }
        }
        if (exacts.size() == 1) {
            return new Proposition(Decision.EXACT, exacts);
        }
        if (exacts.size() > 1) {
            return new Proposition(Decision.AMBIGUOUS, exacts);
        }
        if (probables.size() == 1) {
            return new Proposition(Decision.PROBABLE, probables);
        }
        if (probables.size() > 1) {
            return new Proposition(Decision.AMBIGUOUS, probables);
        }
        return new Proposition(Decision.UNMATCHED, List.of());
    }

    /** Toutes les factures que l'utilisateur peut choisir pour cette piece, les plus pertinentes d'abord (selecteur, jamais d'id saisi). */
    public List<Candidat> tous(Document document) {
        List<Candidat> candidats = new ArrayList<>();
        UUID entrepriseId = document.getEntreprise().getId();
        if (document.getTypeDocument() == TypeDocument.FACTURE_ACHAT && document.getTiersId() != null) {
            for (FactureFournisseur f : factureFournisseurRepository.rechercherCandidates(entrepriseId, document.getTiersId(), document.getId())) {
                candidats.add(evaluerAchat(document, f));
            }
        } else if (document.getTypeDocument() == TypeDocument.FACTURE_VENTE && document.getNumeroFactureNormalise() != null) {
            for (Facture f : factureRepository.rechercherParNumero(entrepriseId, TypeFacture.FACTURE, document.getNumeroFactureNormalise())) {
                candidats.add(evaluerVente(document, f));
            }
        }
        candidats.sort(Comparator.comparing(Candidat::niveau).thenComparing(Candidat::date, Comparator.nullsLast(Comparator.reverseOrder())));
        return candidats;
    }

    private Candidat evaluerAchat(Document d, FactureFournisseur f) {
        String numeroDoc = d.getNumeroFactureNormalise();
        String numeroFacture = f.getNumeroFournisseurNormalise();
        boolean montant = memeMontant(d.getMontantTtc(), f.getMontantTtc());
        boolean dateProche = d.getDatePiece() != null && f.getDateFacture() != null
                && Math.abs(ChronoUnit.DAYS.between(d.getDatePiece(), f.getDateFacture())) <= FENETRE_JOURS;
        Niveau niveau = Niveau.POSSIBLE;
        String raison = "meme fournisseur";
        if (numeroDoc != null && numeroFacture != null) {
            if (numeroDoc.equals(numeroFacture)) {
                niveau = montant ? Niveau.EXACT : Niveau.POSSIBLE;
                raison = montant ? "meme fournisseur, meme numero, meme montant" : "meme numero mais montant different";
            } else {
                raison = "numero different";
            }
        } else if (montant && dateProche) {
            niveau = Niveau.PROBABLE;
            raison = "meme fournisseur, meme montant, date proche (numero absent d'un cote)";
        } else if (montant) {
            raison = "meme montant, date eloignee";
        }
        return new Candidat(CIBLE_FACTURE_FOURNISSEUR, f.getId(), f.getNumero(), f.getNumeroFournisseur(), f.getDateFacture(),
                f.getMontantTtc(), f.getEcritureId(), niveau, raison);
    }

    private Candidat evaluerVente(Document d, Facture f) {
        boolean montant = memeMontant(d.getMontantTtc(), f.montantTtc());
        Niveau niveau = montant ? Niveau.EXACT : Niveau.POSSIBLE;
        return new Candidat(CIBLE_FACTURE, f.getId(), f.getNumero(), null, f.getDateEmission(), f.montantTtc(), f.getEcritureId(), niveau,
                montant ? "meme numero, meme montant" : "meme numero mais montant different");
    }

    static boolean memeMontant(BigDecimal a, BigDecimal b) {
        return a != null && b != null && a.subtract(b).abs().compareTo(TOLERANCE_MONTANT) <= 0;
    }
}
