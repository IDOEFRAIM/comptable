package com.example.comptable.agent.comptabilisation;

import com.example.comptable.agent.comptabilisation.domain.AccountingProposal;
import com.example.comptable.agent.comptabilisation.domain.StatutAccountingProposal;
import com.example.comptable.agent.comptabilisation.repository.AccountingProposalRepository;
import com.example.comptable.comptabilite.domain.CompteComptable;
import com.example.comptable.comptabilite.domain.Journal;
import com.example.comptable.comptabilite.domain.Tiers;
import com.example.comptable.comptabilite.domain.TypeTiers;
import com.example.comptable.comptabilite.repository.CompteComptableRepository;
import com.example.comptable.comptabilite.repository.JournalRepository;
import com.example.comptable.comptabilite.repository.LigneEcritureRepository;
import com.example.comptable.comptabilite.repository.TiersRepository;
import com.example.comptable.document.domain.Document;
import com.example.comptable.document.domain.StatutDocument;
import com.example.comptable.document.domain.TypeDocument;
import com.example.comptable.entreprise.domain.Entreprise;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class AccountingProposalBuilderTest {

    @Mock
    private TiersRepository tiersRepository;
    @Mock
    private LigneEcritureRepository ligneEcritureRepository;
    @Mock
    private CompteComptableRepository compteComptableRepository;
    @Mock
    private JournalRepository journalRepository;
    @Mock
    private AccountingProposalRepository accountingProposalRepository;

    @InjectMocks
    private AccountingProposalBuilder builder;

    private Entreprise entreprise;
    private UUID entrepriseId;

    @BeforeEach
    void setUp() {
        entrepriseId = UUID.randomUUID();
        entreprise = new Entreprise();
        entreprise.setId(entrepriseId);

        lenient().when(accountingProposalRepository.findByDocumentIdAndStatus(any(), any())).thenReturn(Optional.empty());
        lenient().when(accountingProposalRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));
    }

    private Document documentFournisseur(String fournisseur) {
        Document document = new Document();
        document.setId(UUID.randomUUID());
        document.setEntreprise(entreprise);
        document.setTypeDocument(TypeDocument.FACTURE_ACHAT);
        document.setStatut(StatutDocument.TRAITE);
        document.setMontantHt(new BigDecimal("10000"));
        document.setMontantTva(new BigDecimal("1800"));
        document.setMontantTtc(new BigDecimal("11800"));
        document.setDonneesOcr(Map.of("fournisseur", fournisseur));
        return document;
    }

    private Tiers tiers(String nom) {
        Tiers t = new Tiers();
        t.setId(UUID.randomUUID());
        t.setEntreprise(entreprise);
        t.setType(TypeTiers.FOURNISSEUR);
        t.setNom(nom);
        return t;
    }

    @Test
    void tiers_et_compte_avec_historique_coherent_est_eligible_niveau3() {
        Document document = documentFournisseur("ABC SARL Ouagadougou");
        Tiers tiers = tiers("ABC SARL");
        UUID compteId = UUID.randomUUID();
        CompteComptable compte = new CompteComptable();
        compte.setId(compteId);
        compte.setEntreprise(entreprise);
        compte.setNumero("601");
        Journal journal = new Journal();
        journal.setId(UUID.randomUUID());
        journal.setCode("ACH");

        when(tiersRepository.findByEntrepriseId(entrepriseId)).thenReturn(List.of(tiers));
        when(ligneEcritureRepository.rechercherComptesChargeProduitHistoriquesPourTiers(tiers.getId()))
                .thenReturn(List.of(compteId, compteId, compteId));
        when(compteComptableRepository.findById(compteId)).thenReturn(Optional.of(compte));
        when(journalRepository.findByEntrepriseIdAndCode(entrepriseId, "ACH")).thenReturn(Optional.of(journal));

        Optional<AccountingProposal> resultat = builder.proposer(entreprise, document);

        assertThat(resultat).isPresent();
        AccountingProposal proposition = resultat.get();
        assertThat(proposition.getTiers()).isEqualTo(tiers);
        assertThat(proposition.getCompteChargeProduit()).isEqualTo(compte);
        assertThat(proposition.getJournal()).isEqualTo(journal);
        assertThat(proposition.getConfidence()).isEqualTo(0.95);
        assertThat(builder.eligibleNiveau3(proposition)).isTrue();
    }

    @Test
    void tiers_identifie_mais_historique_de_compte_insuffisant_nest_pas_eligible_niveau3() {
        Document document = documentFournisseur("XYZ SARL");
        Tiers tiers = tiers("XYZ SARL");
        UUID compteId = UUID.randomUUID();
        CompteComptable compte = new CompteComptable();
        compte.setId(compteId);
        compte.setEntreprise(entreprise);
        compte.setNumero("602");

        when(tiersRepository.findByEntrepriseId(entrepriseId)).thenReturn(List.of(tiers));
        when(ligneEcritureRepository.rechercherComptesChargeProduitHistoriquesPourTiers(tiers.getId()))
                .thenReturn(List.of(compteId)); // une seule occurrence, pas fiable
        when(compteComptableRepository.findById(compteId)).thenReturn(Optional.of(compte));
        when(journalRepository.findByEntrepriseIdAndCode(entrepriseId, "ACH")).thenReturn(Optional.empty());

        Optional<AccountingProposal> resultat = builder.proposer(entreprise, document);

        assertThat(resultat).isPresent();
        assertThat(resultat.get().getConfidence()).isEqualTo(0.6);
        assertThat(builder.eligibleNiveau3(resultat.get())).isFalse();
    }

    @Test
    void aucun_tiers_identifiable_donne_une_confiance_nulle() {
        Document document = documentFournisseur("FOURNISSEUR INCONNU");
        when(tiersRepository.findByEntrepriseId(entrepriseId)).thenReturn(List.of());

        Optional<AccountingProposal> resultat = builder.proposer(entreprise, document);

        assertThat(resultat).isPresent();
        assertThat(resultat.get().getTiers()).isNull();
        assertThat(resultat.get().getConfidence()).isEqualTo(0.0);
        assertThat(builder.eligibleNiveau3(resultat.get())).isFalse();
    }

    @Test
    void document_non_traite_ne_produit_aucune_proposition() {
        Document document = documentFournisseur("ABC SARL");
        document.setStatut(StatutDocument.EN_ATTENTE_VALIDATION_HUMAINE);

        Optional<AccountingProposal> resultat = builder.proposer(entreprise, document);

        assertThat(resultat).isEmpty();
    }

    @Test
    void proposition_deja_existante_nest_pas_dupliquee() {
        Document document = documentFournisseur("ABC SARL");
        AccountingProposal existante = new AccountingProposal();
        existante.setId(UUID.randomUUID());
        existante.setStatus(StatutAccountingProposal.PROPOSED);
        when(accountingProposalRepository.findByDocumentIdAndStatus(document.getId(), StatutAccountingProposal.PROPOSED))
                .thenReturn(Optional.of(existante));

        Optional<AccountingProposal> resultat = builder.proposer(entreprise, document);

        assertThat(resultat).contains(existante);
    }
}
