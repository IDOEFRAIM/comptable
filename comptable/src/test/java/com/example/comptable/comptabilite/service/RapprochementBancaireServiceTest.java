package com.example.comptable.comptabilite.service;

import com.example.comptable.agent.client.AiServiceClient;
import com.example.comptable.agent.finding.MissingEvidenceAnalyzer;
import com.example.comptable.agent.finding.WhatsAppResolutionService;
import com.example.comptable.agent.matching.MatchDecisionEngine;
import com.example.comptable.agent.matching.MatchingContextBuilder;
import com.example.comptable.agent.rapprochement.MatchingSemantiqueService;
import com.example.comptable.comptabilite.domain.CompteComptable;
import com.example.comptable.comptabilite.domain.EcritureComptable;
import com.example.comptable.comptabilite.domain.LigneEcriture;
import com.example.comptable.comptabilite.domain.LigneReleve;
import com.example.comptable.comptabilite.domain.ReleveBancaire;
import com.example.comptable.comptabilite.repository.CompteComptableRepository;
import com.example.comptable.comptabilite.repository.LigneEcritureRepository;
import com.example.comptable.comptabilite.repository.LigneReleveRepository;
import com.example.comptable.comptabilite.repository.ReleveBancaireRepository;
import com.example.comptable.comptabilite.web.dto.ImporterReleveRequest;
import com.example.comptable.comptabilite.web.dto.ReleveBancaireDto;
import com.example.comptable.entreprise.domain.Entreprise;
import com.example.comptable.entreprise.service.EntrepriseAccessService;
import com.example.comptable.identite.domain.Role;
import com.example.comptable.identite.domain.Utilisateur;
import com.example.comptable.security.UtilisateurPrincipal;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.web.server.ResponseStatusException;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class RapprochementBancaireServiceTest {

    @Mock
    private EntrepriseAccessService entrepriseAccessService;
    @Mock
    private CompteComptableRepository compteComptableRepository;
    @Mock
    private ReleveBancaireRepository releveBancaireRepository;
    @Mock
    private LigneReleveRepository ligneReleveRepository;
    @Mock
    private LigneEcritureRepository ligneEcritureRepository;
    @Mock
    private MatchingSemantiqueService matchingSemantiqueService;
    @Mock
    private MatchingContextBuilder matchingContextBuilder;
    @Mock
    private MatchDecisionEngine matchDecisionEngine;
    @Mock
    private AiServiceClient aiServiceClient;
    @Mock
    private MissingEvidenceAnalyzer missingEvidenceAnalyzer;
    @Mock
    private WhatsAppResolutionService whatsAppResolutionService;

    // Construction explicite (pas @InjectMocks) : le constructeur de RapprochementBancaireService
    // n'est plus le constructeur "tout mockable genere par Lombok" depuis la mission Universal
    // Matching Engine - il porte un booleen @Value (matchingIaActif) que Mockito ne sait pas
    // instancier automatiquement ("Cannot instantiate @InjectMocks field ... has no default
    // constructor"), meme s'il n'est associe a aucun @Mock ici. false desactive le filet IA :
    // comportement identique a avant cette mission pour tous les tests de cette classe.
    private RapprochementBancaireService rapprochementBancaireService;

    private UUID entrepriseId;
    private Entreprise entreprise;
    private UtilisateurPrincipal principal;
    private CompteComptable compteBanque;

    @BeforeEach
    void setUp() {
        rapprochementBancaireService = new RapprochementBancaireService(entrepriseAccessService,
                compteComptableRepository, releveBancaireRepository, ligneReleveRepository, ligneEcritureRepository,
                matchingSemantiqueService, matchingContextBuilder, matchDecisionEngine, aiServiceClient,
                missingEvidenceAnalyzer, whatsAppResolutionService, new SimpleMeterRegistry(), false);

        entrepriseId = UUID.randomUUID();
        entreprise = new Entreprise();
        entreprise.setId(entrepriseId);

        Role role = new Role();
        role.setCode("EXPERT_COMPTABLE");
        Utilisateur utilisateur = new Utilisateur();
        utilisateur.setId(UUID.randomUUID());
        utilisateur.setEmail("test@cabinet.ci");
        utilisateur.setMotDePasse("hash");
        utilisateur.setNom("Nom");
        utilisateur.setPrenom("Prenom");
        utilisateur.setRoles(Set.of(role));
        principal = new UtilisateurPrincipal(utilisateur);

        compteBanque = new CompteComptable();
        compteBanque.setId(UUID.randomUUID());
        compteBanque.setEntreprise(entreprise);
        compteBanque.setNumero("521");
        compteBanque.setIntitule("Banques");

        when(entrepriseAccessService.verifierAcces(entrepriseId, principal)).thenReturn(entreprise);
    }

    private ImporterReleveRequest requete() {
        return new ImporterReleveRequest(compteBanque.getId(), "Releve janvier",
                LocalDate.of(2027, 1, 1), LocalDate.of(2027, 1, 31),
                new BigDecimal("10000"), new BigDecimal("40000"));
    }

    private LigneEcriture ligneEcritureValidee(BigDecimal debit, BigDecimal credit, LocalDate date) {
        EcritureComptable ecriture = new EcritureComptable();
        ecriture.setId(UUID.randomUUID());
        ecriture.setEntreprise(entreprise);
        ecriture.setNumeroPiece("BQ-001");
        ecriture.setDateEcriture(date);
        ecriture.setLibelle("Reglement");
        ecriture.setStatut(com.example.comptable.comptabilite.domain.StatutEcriture.VALIDEE);

        LigneEcriture ligne = new LigneEcriture();
        ligne.setId(UUID.randomUUID());
        ligne.setEcriture(ecriture);
        ligne.setCompte(compteBanque);
        ligne.setDebit(debit);
        ligne.setCredit(credit);
        return ligne;
    }

    @Test
    void importe_un_csv_et_rapproche_automatiquement_les_lignes_correspondantes() {
        MockMultipartFile fichier = new MockMultipartFile("fichier", "releve.csv", "text/csv",
                ("date;libelle;montant\n"
                        + "2027-01-05;Virement client;30000\n"
                        + "2027-01-10;Frais bancaires;-1500\n").getBytes(StandardCharsets.UTF_8));

        LigneEcriture mouvementCorrespondant = ligneEcritureValidee(new BigDecimal("30000"), BigDecimal.ZERO, LocalDate.of(2027, 1, 5));

        when(compteComptableRepository.findById(compteBanque.getId())).thenReturn(Optional.of(compteBanque));
        when(releveBancaireRepository.save(any())).thenAnswer(inv -> {
            ReleveBancaire r = inv.getArgument(0);
            r.setId(UUID.randomUUID());
            return r;
        });
        when(ligneEcritureRepository.rechercherMouvementsValides(any(), any(), any()))
                .thenReturn(List.of(mouvementCorrespondant));
        when(ligneReleveRepository.rechercherLigneEcritureIdsPointees(compteBanque.getId())).thenReturn(List.of());
        when(ligneEcritureRepository.findAllById(any())).thenReturn(List.of(mouvementCorrespondant));
        when(ligneEcritureRepository.soldeNetAvant(any(), any())).thenReturn(new BigDecimal("40000"));

        ReleveBancaireDto resultat = rapprochementBancaireService.importer(entrepriseId, requete(), fichier, principal);

        assertThat(resultat.lignes()).hasSize(2);
        assertThat(resultat.lignes()).anyMatch(l -> l.montant().compareTo(new BigDecimal("30000")) == 0 && l.pointee());
        assertThat(resultat.lignes()).anyMatch(l -> l.montant().compareTo(new BigDecimal("-1500")) == 0 && !l.pointee());
        assertThat(resultat.ecart()).isEqualByComparingTo("0");
    }

    @Test
    void refuse_un_fichier_vide() {
        MockMultipartFile fichier = new MockMultipartFile("fichier", "vide.csv", "text/csv", new byte[0]);
        when(compteComptableRepository.findById(compteBanque.getId())).thenReturn(Optional.of(compteBanque));

        assertThatThrownBy(() -> rapprochementBancaireService.importer(entrepriseId, requete(), fichier, principal))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("Fichier manquant");
    }

    @Test
    void pointer_manuellement_une_ligne() {
        ReleveBancaire releve = new ReleveBancaire();
        releve.setId(UUID.randomUUID());
        releve.setEntreprise(entreprise);
        releve.setCompte(compteBanque);
        releve.setDateDebut(LocalDate.of(2027, 1, 1));
        releve.setDateFin(LocalDate.of(2027, 1, 31));
        LigneReleve ligneReleve = new LigneReleve(LocalDate.of(2027, 1, 8), "Virement", new BigDecimal("15000"));
        ligneReleve.setId(UUID.randomUUID());
        releve.ajouterLigne(ligneReleve);

        LigneEcriture ligneEcriture = ligneEcritureValidee(new BigDecimal("15000"), BigDecimal.ZERO, LocalDate.of(2027, 1, 8));

        when(releveBancaireRepository.findById(releve.getId())).thenReturn(Optional.of(releve));
        when(ligneEcritureRepository.findById(ligneEcriture.getId())).thenReturn(Optional.of(ligneEcriture));
        when(ligneReleveRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        var resultat = rapprochementBancaireService.pointer(entrepriseId, releve.getId(), ligneReleve.getId(),
                ligneEcriture.getId(), principal);

        assertThat(resultat.pointee()).isTrue();
        assertThat(resultat.ligneEcritureId()).isEqualTo(ligneEcriture.getId());
    }
}
