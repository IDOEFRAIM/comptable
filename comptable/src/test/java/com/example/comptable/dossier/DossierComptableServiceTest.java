package com.example.comptable.dossier;

import com.example.comptable.controle.ControlRunService;
import com.example.comptable.agent.finding.domain.Finding;
import com.example.comptable.agent.finding.domain.SeveriteFinding;
import com.example.comptable.agent.finding.domain.StatutFinding;
import com.example.comptable.agent.finding.domain.TypeFinding;
import com.example.comptable.agent.finding.repository.FindingRepository;
import com.example.comptable.comptabilite.domain.StatutFacture;
import com.example.comptable.comptabilite.domain.TypeFacture;
import com.example.comptable.comptabilite.repository.FactureRepository;
import com.example.comptable.document.domain.StatutDocument;
import com.example.comptable.document.domain.TypeDocument;
import com.example.comptable.document.repository.DocumentRepository;
import com.example.comptable.dossier.web.dto.DossierComptableDto;
import com.example.comptable.entreprise.domain.Entreprise;
import com.example.comptable.entreprise.service.EntrepriseAccessService;
import com.example.comptable.identite.domain.Role;
import com.example.comptable.identite.domain.Utilisateur;
import com.example.comptable.security.UtilisateurPrincipal;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class DossierComptableServiceTest {

    @Mock
    private EntrepriseAccessService entrepriseAccessService;
    @Mock
    private ControlRunService controlRunService;
    @Mock
    private FindingRepository findingRepository;
    @Mock
    private FactureRepository factureRepository;
    @Mock
    private DocumentRepository documentRepository;
    @Mock
    private com.example.comptable.comptabilite.repository.EcritureComptableRepository ecritureRepository;

    @Mock
    private com.example.comptable.comptabilite.allocation.StatutPaiementFactureService statutPaiementFactureService;

    @InjectMocks
    private DossierComptableService dossierComptableService;

    private UUID entrepriseId;
    private Entreprise entreprise;
    private UtilisateurPrincipal principal;

    /** N factures echues avec un reste a payer (calcule depuis les allocations : c'est la seule definition du retard). */
    private static List<com.example.comptable.comptabilite.allocation.StatutPaiementFactureService.FactureOuverte> facturesOuvertes(int n) {
        return java.util.stream.IntStream.range(0, n).mapToObj(i -> new com.example.comptable.comptabilite.allocation.StatutPaiementFactureService.FactureOuverte(new com.example.comptable.comptabilite.domain.Facture(),
                java.math.BigDecimal.TEN, java.math.BigDecimal.ZERO, java.math.BigDecimal.TEN,
                com.example.comptable.comptabilite.allocation.StatutPaiement.NON_PAYEE)).toList();
    }

    @BeforeEach
    void setUp() {
        entrepriseId = UUID.randomUUID();
        entreprise = new Entreprise();
        entreprise.setId(entrepriseId);
        entreprise.setRaisonSociale("ABC SARL");

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

        when(entrepriseAccessService.verifierAcces(entrepriseId, principal)).thenReturn(entreprise);
    }

    private Finding finding(TypeFinding type, SeveriteFinding severite, String sourceType) {
        Finding f = new Finding();
        f.setId(UUID.randomUUID());
        f.setEntreprise(entreprise);
        f.setType(type);
        f.setSeverity(severite);
        f.setSourceType(sourceType);
        f.setSourceId(UUID.randomUUID().toString());
        f.setTitle("titre");
        f.setStatus(StatutFinding.OPEN);
        f.setCreatedAt(OffsetDateTime.now());
        f.setControlCode(type.name());
        f.setCategory(com.example.comptable.controle.ControlCategory.ACCOUNTING);
        f.setControlSeverity(com.example.comptable.controle.ControlSeverity.WARNING);
        f.setLastDetectedAt(OffsetDateTime.now());
        return f;
    }

    @Test
    void dossier_propre_sans_probleme_obtient_un_score_parfait() {
        when(findingRepository.findByEntrepriseIdAndStatusOrderBySeverityAscCreatedAtDesc(entrepriseId, StatutFinding.OPEN))
                .thenReturn(List.of());
        when(statutPaiementFactureService.ouvertes(eq(entrepriseId), org.mockito.ArgumentMatchers.anyCollection()))
                .thenReturn(facturesOuvertes(0));
        when(documentRepository.countByEntrepriseIdAndStatutAndTypeDocumentInAndEcritureIdIsNull(
                entrepriseId, StatutDocument.TRAITE, List.of(TypeDocument.FACTURE_ACHAT)))
                .thenReturn(0L);

        DossierComptableDto dossier = dossierComptableService.generer(entrepriseId, principal);

        assertThat(dossier.entrepriseNom()).isEqualTo("ABC SARL");
        assertThat(dossier.aucuneActivite()).as("aucune écriture, aucune pièce, aucun constat : rien à évaluer, jamais « prêt »").isTrue();
        assertThat(dossier.scoreGlobal()).isEqualTo(100);
        assertThat(dossier.statut()).isEqualTo("PRET");
        assertThat(dossier.scoresParDomaine()).hasSize(7);
        assertThat(dossier.actionsPrioritaires()).isEmpty();
        assertThat(dossier.blockingItems()).isEmpty();
    }

    @Test
    void un_finding_critique_bloque_le_dossier_meme_avec_un_score_eleve() {
        Finding critique = finding(TypeFinding.DOCUMENT_REVIEW, SeveriteFinding.CRITICAL, "DOCUMENT");
        when(findingRepository.findByEntrepriseIdAndStatusOrderBySeverityAscCreatedAtDesc(entrepriseId, StatutFinding.OPEN))
                .thenReturn(List.of(critique));
        when(statutPaiementFactureService.ouvertes(eq(entrepriseId), org.mockito.ArgumentMatchers.anyCollection()))
                .thenReturn(facturesOuvertes(0));
        when(documentRepository.countByEntrepriseIdAndStatutAndTypeDocumentInAndEcritureIdIsNull(
                entrepriseId, StatutDocument.TRAITE, List.of(TypeDocument.FACTURE_ACHAT)))
                .thenReturn(0L);

        DossierComptableDto dossier = dossierComptableService.generer(entrepriseId, principal);

        assertThat(dossier.statut()).isEqualTo("BLOQUE");
        assertThat(dossier.blockingItems()).hasSize(1);
        assertThat(dossier.scoresParDomaine())
                .filteredOn(s -> s.domaine().equals("Documents"))
                .extracting(com.example.comptable.dossier.web.dto.ScoreDomaineDto::score)
                .containsExactly(75);
    }

    @Test
    void les_factures_clients_en_retard_degradent_le_score_clients() {
        when(findingRepository.findByEntrepriseIdAndStatusOrderBySeverityAscCreatedAtDesc(entrepriseId, StatutFinding.OPEN))
                .thenReturn(List.of());
        when(statutPaiementFactureService.ouvertes(eq(entrepriseId), org.mockito.ArgumentMatchers.anyCollection()))
                .thenReturn(facturesOuvertes(3));
        when(documentRepository.countByEntrepriseIdAndStatutAndTypeDocumentInAndEcritureIdIsNull(
                entrepriseId, StatutDocument.TRAITE, List.of(TypeDocument.FACTURE_ACHAT)))
                .thenReturn(0L);

        DossierComptableDto dossier = dossierComptableService.generer(entrepriseId, principal);

        assertThat(dossier.scoresParDomaine())
                .filteredOn(s -> s.domaine().equals("Clients"))
                .extracting(com.example.comptable.dossier.web.dto.ScoreDomaineDto::score)
                .containsExactly(76); // 100 - 3*8
    }

    @Test
    void des_ecritures_enregistrees_sans_probleme_ne_sont_pas_une_absence_d_activite() {
        when(findingRepository.findByEntrepriseIdAndStatusOrderBySeverityAscCreatedAtDesc(entrepriseId, StatutFinding.OPEN)).thenReturn(List.of());
        when(statutPaiementFactureService.ouvertes(eq(entrepriseId), org.mockito.ArgumentMatchers.anyCollection())).thenReturn(facturesOuvertes(0));
        when(ecritureRepository.countByEntrepriseId(entrepriseId)).thenReturn(42L);

        DossierComptableDto dossier = dossierComptableService.generer(entrepriseId, principal);

        assertThat(dossier.aucuneActivite()).isFalse();
        assertThat(dossier.statut()).isEqualTo("PRET");
    }

    @Test
    void chaque_domaine_expose_le_nombre_reel_d_elements_a_traiter() {
        when(findingRepository.findByEntrepriseIdAndStatusOrderBySeverityAscCreatedAtDesc(entrepriseId, StatutFinding.OPEN)).thenReturn(List.of());
        when(statutPaiementFactureService.ouvertes(eq(entrepriseId), org.mockito.ArgumentMatchers.anyCollection())).thenReturn(facturesOuvertes(3));
        when(documentRepository.countByEntrepriseIdAndStatutAndTypeDocumentInAndEcritureIdIsNull(entrepriseId, StatutDocument.TRAITE, List.of(TypeDocument.FACTURE_ACHAT))).thenReturn(5L);

        DossierComptableDto dossier = dossierComptableService.generer(entrepriseId, principal);

        assertThat(dossier.scoresParDomaine()).filteredOn(s -> s.domaine().equals("Clients")).singleElement().satisfies(s -> assertThat(s.elementsOuverts()).isEqualTo(3));
        assertThat(dossier.scoresParDomaine()).filteredOn(s -> s.domaine().equals("Fournisseurs")).singleElement().satisfies(s -> assertThat(s.elementsOuverts()).isEqualTo(5));
        assertThat(dossier.scoresParDomaine()).filteredOn(s -> s.domaine().equals("Banque")).singleElement().satisfies(s -> assertThat(s.elementsOuverts()).isZero());
    }
}
