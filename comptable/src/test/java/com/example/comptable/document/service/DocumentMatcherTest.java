package com.example.comptable.document.service;

import com.example.comptable.comptabilite.repository.FactureRepository;
import com.example.comptable.document.domain.Document;
import com.example.comptable.document.domain.TypeDocument;
import com.example.comptable.document.service.DocumentMatcher.Decision;
import com.example.comptable.entreprise.domain.Entreprise;
import com.example.comptable.stock.domain.FactureFournisseur;
import com.example.comptable.stock.repository.FactureFournisseurRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** Le matcher est deterministe : exact, probable, ambigu, sans correspondance - et il ne prend JAMAIS la premiere candidate. */
class DocumentMatcherTest {

    private static final LocalDate JOUR = LocalDate.of(2027, 5, 10);

    private FactureFournisseurRepository factures;
    private DocumentMatcher matcher;
    private Document document;
    private final UUID fournisseurId = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        factures = mock(FactureFournisseurRepository.class);
        matcher = new DocumentMatcher(factures, mock(FactureRepository.class));
        Entreprise entreprise = new Entreprise();
        entreprise.setId(UUID.randomUUID());
        document = new Document();
        document.setId(UUID.randomUUID());
        document.setEntreprise(entreprise);
        document.setTypeDocument(TypeDocument.FACTURE_ACHAT);
        document.setTiersId(fournisseurId);
        document.setNumeroFacture("FAC-001");
        document.setMontantTtc(new BigDecimal("118000"));
        document.setDatePiece(JOUR);
    }

    private FactureFournisseur facture(String numeroFournisseur, String ttc, LocalDate date) {
        FactureFournisseur f = new FactureFournisseur();
        f.setId(UUID.randomUUID());
        f.setNumero("FF-" + f.getId().toString().substring(0, 4));
        f.setNumeroFournisseur(numeroFournisseur);
        f.setMontantTtc(new BigDecimal(ttc));
        f.setDateFacture(date);
        f.setEcritureId(UUID.randomUUID());
        return f;
    }

    private void candidates(FactureFournisseur... fs) {
        when(factures.rechercherCandidates(any(), any(), any())).thenReturn(List.of(fs));
    }

    @Test
    void meme_numero_et_meme_montant_est_exact() {
        FactureFournisseur f = facture("FAC 001", "118000", JOUR.plusDays(40));
        candidates(f, facture("FAC-002", "118000", JOUR));

        DocumentMatcher.Proposition p = matcher.proposer(document);

        assertThat(p.decision()).isEqualTo(Decision.EXACT);
        assertThat(p.unique().id()).isEqualTo(f.getId());
    }

    @Test
    void meme_numero_mais_montant_different_n_est_jamais_un_rapprochement() {
        candidates(facture("FAC-001", "120000", JOUR));

        assertThat(matcher.proposer(document).decision()).isEqualTo(Decision.UNMATCHED);
    }

    @Test
    void numero_different_exclut_la_facture_meme_au_meme_montant() {
        candidates(facture("FAC-777", "118000", JOUR));

        assertThat(matcher.proposer(document).decision()).isEqualTo(Decision.UNMATCHED);
    }

    @Test
    void numero_absent_cote_facture_meme_montant_date_proche_est_probable() {
        FactureFournisseur f = facture(null, "118000", JOUR.minusDays(3));
        candidates(f);

        DocumentMatcher.Proposition p = matcher.proposer(document);

        assertThat(p.decision()).isEqualTo(Decision.PROBABLE);
        assertThat(p.unique().id()).isEqualTo(f.getId());
    }

    @Test
    void numero_absent_meme_montant_mais_date_eloignee_n_est_pas_propose() {
        candidates(facture(null, "118000", JOUR.minusDays(30)));

        assertThat(matcher.proposer(document).decision()).isEqualTo(Decision.UNMATCHED);
    }

    @Test
    void deux_factures_aussi_plausibles_sont_ambigues_et_aucune_n_est_choisie() {
        candidates(facture(null, "118000", JOUR), facture(null, "118000", JOUR.plusDays(1)));

        DocumentMatcher.Proposition p = matcher.proposer(document);

        assertThat(p.decision()).isEqualTo(Decision.AMBIGUOUS);
        assertThat(p.candidats()).hasSize(2);
        assertThat(p.unique()).isNull();
    }

    @Test
    void un_exact_unique_l_emporte_sur_des_probables() {
        FactureFournisseur exacte = facture("FAC-001", "118000", JOUR);
        candidates(exacte, facture(null, "118000", JOUR), facture(null, "118000", JOUR));

        assertThat(matcher.proposer(document).decision()).isEqualTo(Decision.EXACT);
    }

    @Test
    void la_candidate_deja_refusee_par_l_utilisateur_n_est_plus_proposee() {
        FactureFournisseur f = facture(null, "118000", JOUR);
        candidates(f);
        document.setCibleRejeteeId(f.getId());

        assertThat(matcher.proposer(document).decision()).isEqualTo(Decision.UNMATCHED);
    }

    @Test
    void sans_tiers_identifie_aucune_candidate() {
        document.setTiersId(null);
        candidates(facture("FAC-001", "118000", JOUR));

        assertThat(matcher.tous(document)).isEmpty();
    }
}
