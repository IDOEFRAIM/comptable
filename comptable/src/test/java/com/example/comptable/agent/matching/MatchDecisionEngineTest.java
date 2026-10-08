package com.example.comptable.agent.matching;

import com.example.comptable.agent.matching.domain.DecisionRapprochementIA;
import com.example.comptable.agent.matching.dto.MatchProposalDTO;
import com.example.comptable.agent.matching.dto.MatchingCandidateDTO;
import com.example.comptable.agent.matching.dto.MatchingContextDTO;
import com.example.comptable.agent.matching.dto.MatchingRequestDTO;
import com.example.comptable.agent.matching.dto.MatchingResponseDTO;
import com.example.comptable.agent.matching.dto.MatchingSourceDTO;
import com.example.comptable.agent.matching.repository.AiMatchProposalRepository;
import com.example.comptable.comptabilite.domain.CompteComptable;
import com.example.comptable.comptabilite.domain.Facture;
import com.example.comptable.comptabilite.domain.LigneEcriture;
import com.example.comptable.comptabilite.domain.LigneReleve;
import com.example.comptable.comptabilite.domain.StatutFacture;
import com.example.comptable.comptabilite.repository.FactureRepository;
import com.example.comptable.comptabilite.repository.LigneEcritureRepository;
import com.example.comptable.document.domain.Document;
import com.example.comptable.document.repository.DocumentRepository;
import com.example.comptable.entreprise.domain.Entreprise;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.when;

/** Les 12 cas de la section 18 de la mission "brancher le Universal Matching Engine au Core". */
@ExtendWith(MockitoExtension.class)
class MatchDecisionEngineTest {

    @Mock
    private AiMatchProposalRepository aiMatchProposalRepository;
    @Mock
    private LigneEcritureRepository ligneEcritureRepository;
    @Mock
    private FactureRepository factureRepository;
    @Mock
    private DocumentRepository documentRepository;

    @org.mockito.Mock
    private com.example.comptable.banque.BankReconciliationService reconciliationService;

    private void reconciliationPoseLePointeur() {
        org.mockito.Mockito.lenient().when(reconciliationService.confirmerParLeSysteme(any(), any(), any(), any(), any(), any())).thenAnswer(inv -> {
            ((LigneReleve) inv.getArgument(0)).setLigneEcritureId(inv.getArgument(1));
            return new com.example.comptable.banque.BankReconciliation();
        });
    }

    private MatchDecisionEngine engine;
    private Entreprise entreprise;
    private LigneReleve ligne;

    @BeforeEach
    void setUp() {
        engine = new MatchDecisionEngine(aiMatchProposalRepository, ligneEcritureRepository, factureRepository,
                documentRepository, reconciliationService, 0.95, 0.70);
        reconciliationPoseLePointeur();

        entreprise = new Entreprise();
        entreprise.setId(UUID.randomUUID());

        ligne = new LigneReleve(LocalDate.of(2027, 1, 5), "VIR ABC SARL FAC452", new BigDecimal("15000"));
        ligne.setId(UUID.randomUUID());

        lenient().when(aiMatchProposalRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));
    }

    private MatchingSourceDTO source() {
        return source("BANK_TRANSACTION");
    }

    private MatchingSourceDTO source(String type) {
        return new MatchingSourceDTO(type, ligne.getId().toString(), ligne.getDateOperation(),
                ligne.getMontant(), "XOF", ligne.getLibelle(), null, "ABC SARL");
    }

    private MatchingCandidateDTO candidatEcriture(UUID id, BigDecimal montant) {
        return new MatchingCandidateDTO("ACCOUNTING_ENTRY", id.toString(), LocalDate.of(2027, 1, 5), montant,
                "XOF", "Vente", null, "ABC SARL", null);
    }

    private MatchingCandidateDTO candidatFacture(UUID id) {
        return new MatchingCandidateDTO("CUSTOMER_INVOICE", id.toString(), LocalDate.of(2027, 1, 4),
                new BigDecimal("15000"), "XOF", "Facture", "FAC-452", "ABC SARL", "VALIDEE");
    }

    private MatchingResponseDTO reponse(String decision, String matchType, double confidence,
                                         List<MatchProposalDTO> matches, boolean requiresReview) {
        return new MatchingResponseDTO(decision, confidence, matchType, matches, List.of("exact_amount"), List.of(),
                requiresReview);
    }

    private LigneEcriture ecritureAppartenant(UUID id, Entreprise ent) {
        CompteComptable compte = new CompteComptable();
        compte.setId(UUID.randomUUID());
        compte.setEntreprise(ent);
        LigneEcriture ligneEcriture = new LigneEcriture();
        ligneEcriture.setId(id);
        ligneEcriture.setCompte(compte);
        return ligneEcriture;
    }

    private Facture factureAppartenant(UUID id, Entreprise ent, StatutFacture statut) {
        Facture facture = new Facture();
        facture.setId(id);
        facture.setEntreprise(ent);
        facture.setStatut(statut);
        return facture;
    }

    private Document documentAppartenant(UUID id, Entreprise ent, UUID ecritureId) {
        Document document = new Document();
        document.setId(id);
        document.setEntreprise(ent);
        document.setEcritureId(ecritureId);
        return document;
    }

    // Cas 1 : transaction -> ecriture exacte
    @Test
    void cas1_transaction_vers_ecriture_exacte_declenche_match_auto() {
        UUID candidatId = UUID.randomUUID();
        MatchingRequestDTO requete = new MatchingRequestDTO(source(), List.of(candidatEcriture(candidatId, new BigDecimal("15000"))),
                new MatchingContextDTO(null, null, null, List.of()));
        MatchingResponseDTO reponse = reponse("MATCH_PROPOSED", "ONE_TO_ONE", 0.97,
                List.of(new MatchProposalDTO(candidatId.toString(), "ACCOUNTING_ENTRY", 0.97)), false);
        when(ligneEcritureRepository.findById(candidatId)).thenReturn(Optional.of(ecritureAppartenant(candidatId, entreprise)));

        ResultatDecision resultat = engine.statuer(entreprise, ligne, requete, Optional.of(reponse), new HashSet<>());

        assertThat(resultat.decision()).isEqualTo(DecisionRapprochementIA.MATCH_AUTO);
        assertThat(resultat.ligneEcriturePointee()).isEqualTo(candidatId);
        assertThat(ligne.getLigneEcritureId()).isEqualTo(candidatId);
    }

    // Cas 2 : transaction -> facture exacte (jamais auto-pointee, meme confiance parfaite)
    @Test
    void cas2_transaction_vers_facture_exacte_ne_declenche_jamais_match_auto() {
        UUID factureId = UUID.randomUUID();
        MatchingRequestDTO requete = new MatchingRequestDTO(source(), List.of(candidatFacture(factureId)),
                new MatchingContextDTO(null, null, null, List.of()));
        MatchingResponseDTO reponse = reponse("MATCH_PROPOSED", "ONE_TO_ONE", 0.99,
                List.of(new MatchProposalDTO(factureId.toString(), "CUSTOMER_INVOICE", 0.99)), false);
        when(factureRepository.findById(factureId)).thenReturn(Optional.of(factureAppartenant(factureId, entreprise, StatutFacture.VALIDEE)));

        ResultatDecision resultat = engine.statuer(entreprise, ligne, requete, Optional.of(reponse), new HashSet<>());

        assertThat(resultat.decision()).isEqualTo(DecisionRapprochementIA.MATCH_REVIEW);
        assertThat(ligne.getLigneEcritureId()).isNull();
    }

    // Cas 3 : transaction -> facture via tiers + date (confiance moderee)
    @Test
    void cas3_confiance_moderee_via_tiers_et_date_necessite_une_revue() {
        UUID factureId = UUID.randomUUID();
        MatchingRequestDTO requete = new MatchingRequestDTO(source(), List.of(candidatFacture(factureId)),
                new MatchingContextDTO(null, null, null, List.of()));
        MatchingResponseDTO reponse = reponse("MATCH_PROPOSED", "ONE_TO_ONE", 0.78,
                List.of(new MatchProposalDTO(factureId.toString(), "CUSTOMER_INVOICE", 0.78)), false);
        when(factureRepository.findById(factureId)).thenReturn(Optional.of(factureAppartenant(factureId, entreprise, StatutFacture.VALIDEE)));

        ResultatDecision resultat = engine.statuer(entreprise, ligne, requete, Optional.of(reponse), new HashSet<>());

        assertThat(resultat.decision()).isEqualTo(DecisionRapprochementIA.MATCH_REVIEW);
    }

    // Cas 4 : transaction -> aucun candidat
    @Test
    void cas4_aucun_candidat_donne_no_match() {
        MatchingRequestDTO requete = new MatchingRequestDTO(source(), List.of(), new MatchingContextDTO(null, null, null, List.of()));
        MatchingResponseDTO reponse = reponse("NO_MATCH", "NONE", 0.1, List.of(), false);

        ResultatDecision resultat = engine.statuer(entreprise, ligne, requete, Optional.of(reponse), new HashSet<>());

        assertThat(resultat.decision()).isEqualTo(DecisionRapprochementIA.NO_MATCH);
        assertThat(ligne.getLigneEcritureId()).isNull();
    }

    // Cas 5 : deux factures ambigues
    @Test
    void cas5_deux_candidats_ambigus_necessitent_une_revue() {
        UUID fac1 = UUID.randomUUID();
        UUID fac2 = UUID.randomUUID();
        MatchingRequestDTO requete = new MatchingRequestDTO(source(), List.of(candidatFacture(fac1), candidatFacture(fac2)),
                new MatchingContextDTO(null, null, null, List.of()));
        MatchingResponseDTO reponse = reponse("AMBIGUOUS_MATCH", "NONE", 0.61, List.of(
                new MatchProposalDTO(fac1.toString(), "CUSTOMER_INVOICE", 0.63),
                new MatchProposalDTO(fac2.toString(), "CUSTOMER_INVOICE", 0.60)), true);
        when(factureRepository.findById(any())).thenReturn(Optional.of(factureAppartenant(fac1, entreprise, StatutFacture.VALIDEE)));

        ResultatDecision resultat = engine.statuer(entreprise, ligne, requete, Optional.of(reponse), new HashSet<>());

        assertThat(resultat.decision()).isEqualTo(DecisionRapprochementIA.MATCH_REVIEW);
    }

    // Cas 6 : paiement partiel - jamais traite comme un paiement complet
    @Test
    void cas6_paiement_partiel_reste_en_revue_jamais_auto() {
        UUID factureId = UUID.randomUUID();
        MatchingCandidateDTO candidat = new MatchingCandidateDTO("CUSTOMER_INVOICE", factureId.toString(),
                LocalDate.of(2027, 1, 4), new BigDecimal("500000"), "XOF", "Facture", "FAC-452", "ABC SARL", "VALIDEE");
        MatchingRequestDTO requete = new MatchingRequestDTO(source(), List.of(candidat), new MatchingContextDTO(null, null, null, List.of()));
        MatchingResponseDTO reponse = reponse("PARTIAL_MATCH", "PARTIAL_PAYMENT", 0.85,
                List.of(new MatchProposalDTO(factureId.toString(), "CUSTOMER_INVOICE", 0.85)), true);
        when(factureRepository.findById(factureId)).thenReturn(Optional.of(factureAppartenant(factureId, entreprise, StatutFacture.VALIDEE)));

        ResultatDecision resultat = engine.statuer(entreprise, ligne, requete, Optional.of(reponse), new HashSet<>());

        assertThat(resultat.decision()).isEqualTo(DecisionRapprochementIA.MATCH_REVIEW);
        assertThat(ligne.getLigneEcritureId()).isNull();
    }

    // Cas 7 : facture deja payee entre-temps -> plus disponible
    @Test
    void cas7_facture_deja_payee_nest_plus_disponible() {
        UUID factureId = UUID.randomUUID();
        MatchingRequestDTO requete = new MatchingRequestDTO(source(), List.of(candidatFacture(factureId)),
                new MatchingContextDTO(null, null, null, List.of()));
        MatchingResponseDTO reponse = reponse("MATCH_PROPOSED", "ONE_TO_ONE", 0.96,
                List.of(new MatchProposalDTO(factureId.toString(), "CUSTOMER_INVOICE", 0.96)), false);
        // Statut REEL en base est deja PAYEE, meme si le candidat envoye annoncait VALIDEE.
        when(factureRepository.findById(factureId)).thenReturn(Optional.of(factureAppartenant(factureId, entreprise, StatutFacture.PAYEE)));

        ResultatDecision resultat = engine.statuer(entreprise, ligne, requete, Optional.of(reponse), new HashSet<>());

        assertThat(resultat.decision()).isEqualTo(DecisionRapprochementIA.MATCH_REVIEW);
    }

    // Cas 8 : candidat appartenant a une autre entreprise
    @Test
    void cas8_candidat_dune_autre_entreprise_est_refuse() {
        UUID candidatId = UUID.randomUUID();
        Entreprise autreEntreprise = new Entreprise();
        autreEntreprise.setId(UUID.randomUUID());
        MatchingRequestDTO requete = new MatchingRequestDTO(source(), List.of(candidatEcriture(candidatId, new BigDecimal("15000"))),
                new MatchingContextDTO(null, null, null, List.of()));
        MatchingResponseDTO reponse = reponse("MATCH_PROPOSED", "ONE_TO_ONE", 0.98,
                List.of(new MatchProposalDTO(candidatId.toString(), "ACCOUNTING_ENTRY", 0.98)), false);
        when(ligneEcritureRepository.findById(candidatId)).thenReturn(Optional.of(ecritureAppartenant(candidatId, autreEntreprise)));

        ResultatDecision resultat = engine.statuer(entreprise, ligne, requete, Optional.of(reponse), new HashSet<>());

        assertThat(resultat.decision()).isEqualTo(DecisionRapprochementIA.MATCH_REVIEW);
        assertThat(ligne.getLigneEcritureId()).isNull();
    }

    // Cas 9 : IA indisponible
    @Test
    void cas9_ia_indisponible_retombe_sur_ai_unavailable() {
        MatchingRequestDTO requete = new MatchingRequestDTO(source(), List.of(), new MatchingContextDTO(null, null, null, List.of()));

        ResultatDecision resultat = engine.statuer(entreprise, ligne, requete, Optional.empty(), new HashSet<>());

        assertThat(resultat.decision()).isEqualTo(DecisionRapprochementIA.AI_UNAVAILABLE);
        assertThat(ligne.getLigneEcritureId()).isNull();
    }

    // Cas 10 : reponse IA avec candidat inexistant (hallucination)
    @Test
    void cas10_candidat_invente_par_lia_est_rejete() {
        UUID candidatEnvoye = UUID.randomUUID();
        UUID candidatInvente = UUID.randomUUID();
        MatchingRequestDTO requete = new MatchingRequestDTO(source(), List.of(candidatEcriture(candidatEnvoye, new BigDecimal("15000"))),
                new MatchingContextDTO(null, null, null, List.of()));
        MatchingResponseDTO reponse = reponse("MATCH_PROPOSED", "ONE_TO_ONE", 0.97,
                List.of(new MatchProposalDTO(candidatInvente.toString(), "ACCOUNTING_ENTRY", 0.97)), false);

        ResultatDecision resultat = engine.statuer(entreprise, ligne, requete, Optional.of(reponse), new HashSet<>());

        assertThat(resultat.decision()).isEqualTo(DecisionRapprochementIA.MATCH_REVIEW);
        assertThat(resultat.proposition().getReasonsJson()).contains("invalid_candidate_id");
        assertThat(ligne.getLigneEcritureId()).isNull();
    }

    // Cas 11 : score eleve mais l'IA elle-meme signale une regle metier qui interdit l'auto-accept
    @Test
    void cas11_score_eleve_mais_revue_humaine_exigee_par_lia_bloque_le_match_auto() {
        UUID candidatId = UUID.randomUUID();
        MatchingRequestDTO requete = new MatchingRequestDTO(source(), List.of(candidatEcriture(candidatId, new BigDecimal("15000"))),
                new MatchingContextDTO(null, null, null, List.of()));
        MatchingResponseDTO reponse = reponse("MATCH_PROPOSED", "ONE_TO_ONE", 0.99,
                List.of(new MatchProposalDTO(candidatId.toString(), "ACCOUNTING_ENTRY", 0.99)), true);
        when(ligneEcritureRepository.findById(candidatId)).thenReturn(Optional.of(ecritureAppartenant(candidatId, entreprise)));

        ResultatDecision resultat = engine.statuer(entreprise, ligne, requete, Optional.of(reponse), new HashSet<>());

        assertThat(resultat.decision()).isEqualTo(DecisionRapprochementIA.MATCH_REVIEW);
        assertThat(ligne.getLigneEcritureId()).isNull();
    }

    // Cas 12 : transaction Mobile Money
    @Test
    void cas12_transaction_mobile_money_vers_ecriture_exacte() {
        UUID candidatId = UUID.randomUUID();
        MatchingRequestDTO requete = new MatchingRequestDTO(source("MOBILE_MONEY_TRANSACTION"),
                List.of(candidatEcriture(candidatId, new BigDecimal("15000"))), new MatchingContextDTO(null, null, null, List.of()));
        MatchingResponseDTO reponse = reponse("MATCH_PROPOSED", "ONE_TO_ONE", 0.96,
                List.of(new MatchProposalDTO(candidatId.toString(), "ACCOUNTING_ENTRY", 0.96)), false);
        when(ligneEcritureRepository.findById(candidatId)).thenReturn(Optional.of(ecritureAppartenant(candidatId, entreprise)));

        ResultatDecision resultat = engine.statuer(entreprise, ligne, requete, Optional.of(reponse), new HashSet<>());

        assertThat(resultat.decision()).isEqualTo(DecisionRapprochementIA.MATCH_AUTO);
    }

    // Cas B (mission "finaliser le matching IA sans API Anthropic disponible", section 9) :
    // SUPPLIER_INVOICE (piece fournisseur = Document, cf. MatchingContextBuilder) avec une
    // confiance elevee reste une simple proposition (MATCH_REVIEW), jamais un MATCH_AUTO - meme
    // garde-fou de type que pour CUSTOMER_INVOICE (cas2), mais exerce ici le chemin
    // documentRepository de verifierDisponibilite, jamais couvert par les 12 cas precedents.
    @Test
    void casB_supplier_invoice_haute_confiance_reste_en_revue_jamais_auto() {
        UUID documentId = UUID.randomUUID();
        MatchingCandidateDTO candidat = new MatchingCandidateDTO("SUPPLIER_INVOICE", documentId.toString(),
                LocalDate.of(2027, 1, 4), new BigDecimal("15000"), "XOF", "Facture fournisseur", "FAC-452",
                "ABC SARL", null);
        MatchingRequestDTO requete = new MatchingRequestDTO(source(), List.of(candidat),
                new MatchingContextDTO(null, null, null, List.of()));
        MatchingResponseDTO reponse = reponse("MATCH_PROPOSED", "ONE_TO_ONE", 0.99,
                List.of(new MatchProposalDTO(documentId.toString(), "SUPPLIER_INVOICE", 0.99)), false);
        // ecritureId == null : la piece n'est pas encore rattachee a une ecriture, donc disponible.
        when(documentRepository.findById(documentId))
                .thenReturn(Optional.of(documentAppartenant(documentId, entreprise, null)));

        ResultatDecision resultat = engine.statuer(entreprise, ligne, requete, Optional.of(reponse), new HashSet<>());

        assertThat(resultat.decision()).isEqualTo(DecisionRapprochementIA.MATCH_REVIEW);
        assertThat(ligne.getLigneEcritureId()).isNull();
    }

    // Cas C (mission P0, Matching V2) : un second candidat invente dans un groupe ONE_TO_MANY est
    // desormais detecte, pas seulement le premier/mieux note comme avant cette mission.
    @Test
    void casC_candidat_invente_en_deuxieme_position_dun_groupe_est_detecte() {
        UUID candidatReel = UUID.randomUUID();
        UUID candidatInvente = UUID.randomUUID();
        MatchingRequestDTO requete = new MatchingRequestDTO(source(),
                List.of(candidatFacture(candidatReel)), new MatchingContextDTO(null, null, null, List.of()));
        MatchingResponseDTO reponse = reponse("MATCH_PROPOSED", "ONE_TO_MANY", 0.9,
                List.of(
                        new MatchProposalDTO(candidatReel.toString(), "CUSTOMER_INVOICE", 0.9),
                        new MatchProposalDTO(candidatInvente.toString(), "CUSTOMER_INVOICE", 0.85)),
                false);

        ResultatDecision resultat = engine.statuer(entreprise, ligne, requete, Optional.of(reponse), new HashSet<>());

        assertThat(resultat.decision()).isEqualTo(DecisionRapprochementIA.MATCH_REVIEW);
        assertThat(resultat.proposition().getReasonsJson()).contains("invalid_candidate_id");
    }

    // Cas D (mission P0, Matching V2) : la somme des candidats d'un groupe ONE_TO_MANY doit
    // reellement correspondre au montant source - avant cette mission, aucune verification de
    // somme n'existait, seul le "meilleur" candidat individuel etait controle.
    @Test
    void casD_somme_dun_groupe_one_to_many_incoherente_est_signalee() {
        UUID fac1 = UUID.randomUUID();
        UUID fac2 = UUID.randomUUID();
        // Source a 15000 ; les deux candidats valent 15000 chacun -> somme 30000, largement hors
        // tolerance (20%) par rapport au montant source.
        MatchingRequestDTO requete = new MatchingRequestDTO(source(),
                List.of(candidatFacture(fac1), candidatFacture(fac2)), new MatchingContextDTO(null, null, null, List.of()));
        MatchingResponseDTO reponse = reponse("MATCH_PROPOSED", "ONE_TO_MANY", 0.9,
                List.of(
                        new MatchProposalDTO(fac1.toString(), "CUSTOMER_INVOICE", 0.9),
                        new MatchProposalDTO(fac2.toString(), "CUSTOMER_INVOICE", 0.9)),
                false);
        when(factureRepository.findById(fac1)).thenReturn(Optional.of(factureAppartenant(fac1, entreprise, StatutFacture.VALIDEE)));
        when(factureRepository.findById(fac2)).thenReturn(Optional.of(factureAppartenant(fac2, entreprise, StatutFacture.VALIDEE)));

        ResultatDecision resultat = engine.statuer(entreprise, ligne, requete, Optional.of(reponse), new HashSet<>());

        assertThat(resultat.decision()).isEqualTo(DecisionRapprochementIA.MATCH_REVIEW);
        assertThat(resultat.proposition().getReasonsJson()).contains("java_control:montant_incoherent");
        assertThat(ligne.getLigneEcritureId()).isNull();
    }
}
