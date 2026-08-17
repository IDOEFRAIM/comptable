package com.example.comptable.comptabilite.service;

import com.example.comptable.comptabilite.domain.CompteComptable;
import com.example.comptable.comptabilite.domain.EcritureComptable;
import com.example.comptable.comptabilite.domain.ExerciceComptable;
import com.example.comptable.comptabilite.domain.Journal;
import com.example.comptable.comptabilite.domain.LigneEcriture;
import com.example.comptable.comptabilite.domain.StatutEcriture;
import com.example.comptable.comptabilite.domain.StatutExercice;
import com.example.comptable.comptabilite.domain.Tiers;
import com.example.comptable.comptabilite.domain.TypeJournal;
import com.example.comptable.comptabilite.domain.TypeTiers;
import com.example.comptable.comptabilite.repository.EcritureComptableRepository;
import com.example.comptable.comptabilite.repository.ExerciceComptableRepository;
import com.example.comptable.comptabilite.web.dto.FecExportDto;
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

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ExportFecServiceTest {

    @Mock
    private EntrepriseAccessService entrepriseAccessService;
    @Mock
    private ExerciceComptableRepository exerciceComptableRepository;
    @Mock
    private EcritureComptableRepository ecritureComptableRepository;

    @InjectMocks
    private ExportFecService exportFecService;

    private UUID entrepriseId;
    private UUID exerciceId;
    private Entreprise entreprise;
    private ExerciceComptable exercice;
    private UtilisateurPrincipal principal;
    private Journal journalVentes;
    private CompteComptable compteClient;
    private CompteComptable compteVente;

    @BeforeEach
    void setUp() {
        entrepriseId = UUID.randomUUID();
        exerciceId = UUID.randomUUID();

        entreprise = new Entreprise();
        entreprise.setId(entrepriseId);
        entreprise.setNumeroContribuable("CI-2026-B-1234");

        exercice = new ExerciceComptable();
        exercice.setId(exerciceId);
        exercice.setEntreprise(entreprise);
        exercice.setDateDebut(LocalDate.of(2026, 1, 1));
        exercice.setDateFin(LocalDate.of(2026, 12, 31));
        exercice.setStatut(StatutExercice.OUVERT);

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

        journalVentes = new Journal();
        journalVentes.setId(UUID.randomUUID());
        journalVentes.setEntreprise(entreprise);
        journalVentes.setCode("VTE");
        journalVentes.setLibelle("Journal des ventes");
        journalVentes.setType(TypeJournal.VENTES);

        compteClient = new CompteComptable();
        compteClient.setId(UUID.randomUUID());
        compteClient.setEntreprise(entreprise);
        compteClient.setNumero("411001");
        compteClient.setIntitule("Client Alpha");
        compteClient.setLettrable(true);

        compteVente = new CompteComptable();
        compteVente.setId(UUID.randomUUID());
        compteVente.setEntreprise(entreprise);
        compteVente.setNumero("701000");
        compteVente.setIntitule("Ventes de marchandises");
        compteVente.setLettrable(false);

        when(entrepriseAccessService.verifierAcces(entrepriseId, principal)).thenReturn(entreprise);
        when(exerciceComptableRepository.findById(exerciceId)).thenReturn(Optional.of(exercice));
    }

    private EcritureComptable ecriture(String numeroPiece, LocalDate date, String libelle) {
        EcritureComptable ecriture = new EcritureComptable();
        ecriture.setId(UUID.randomUUID());
        ecriture.setEntreprise(entreprise);
        ecriture.setExercice(exercice);
        ecriture.setJournal(journalVentes);
        ecriture.setNumeroPiece(numeroPiece);
        ecriture.setDateEcriture(date);
        ecriture.setLibelle(libelle);
        ecriture.setStatut(StatutEcriture.VALIDEE);
        ecriture.setCreatedAt(OffsetDateTime.parse("2026-03-02T10:00:00Z"));
        return ecriture;
    }

    @Test
    void exporte_lentete_et_les_lignes_au_format_fec() {
        EcritureComptable ecriture = ecriture("VTE-2026-0001", LocalDate.of(2026, 3, 1), "Facture client Alpha");
        Tiers client = new Tiers();
        client.setId(UUID.randomUUID());
        client.setEntreprise(entreprise);
        client.setType(TypeTiers.CLIENT);
        client.setNom("Client Alpha");
        client.setCompteComptable(compteClient);

        LigneEcriture ligneDebit = new LigneEcriture();
        ligneDebit.setId(UUID.randomUUID());
        ligneDebit.setCompte(compteClient);
        ligneDebit.setTiers(client);
        ligneDebit.setDebit(new BigDecimal("59000"));
        ligneDebit.setCredit(BigDecimal.ZERO);
        ligneDebit.setNumeroLigne(1);
        ecriture.ajouterLigne(ligneDebit);

        LigneEcriture ligneCredit = new LigneEcriture();
        ligneCredit.setId(UUID.randomUUID());
        ligneCredit.setCompte(compteVente);
        ligneCredit.setDebit(BigDecimal.ZERO);
        ligneCredit.setCredit(new BigDecimal("59000"));
        ligneCredit.setNumeroLigne(2);
        ecriture.ajouterLigne(ligneCredit);

        when(ecritureComptableRepository.findByExerciceIdAndStatutFetchAll(exerciceId, StatutEcriture.VALIDEE))
                .thenReturn(List.of(ecriture));

        FecExportDto export = exportFecService.exporter(entrepriseId, exerciceId, principal);

        assertThat(export.nomFichier()).isEqualTo("CI2026B1234FEC20261231.txt");

        String[] lignes = export.contenu().split("\n");
        assertThat(lignes).hasSize(3);
        assertThat(lignes[0]).isEqualTo(
                "JournalCode\tJournalLib\tEcritureNum\tEcritureDate\tCompteNum\tCompteLib\tCompAuxNum\tCompAuxLib\t"
                        + "PieceRef\tPieceDate\tEcritureLib\tDebit\tCredit\tEcritureLet\tDateLet\tValidDate\tMontantdevise\tIdevise");

        assertThat(lignes[1]).isEqualTo(
                "VTE\tJournal des ventes\t1\t20260301\t411001\tClient Alpha\t411001\tClient Alpha\t"
                        + "VTE-2026-0001\t20260301\tFacture client Alpha\t59000.00\t0.00\t\t\t20260302\t\t");

        assertThat(lignes[2]).isEqualTo(
                "VTE\tJournal des ventes\t1\t20260301\t701000\tVentes de marchandises\t\t\t"
                        + "VTE-2026-0001\t20260301\tFacture client Alpha\t0.00\t59000.00\t\t\t20260302\t\t");
    }

    @Test
    void incremente_le_numero_de_sequence_par_journal_ecriture_apres_ecriture() {
        EcritureComptable e1 = ecriture("VTE-2026-0001", LocalDate.of(2026, 1, 5), "Premiere ecriture");
        e1.ajouterLigne(ligneSimple(compteVente, BigDecimal.ZERO, new BigDecimal("1000")));
        EcritureComptable e2 = ecriture("VTE-2026-0002", LocalDate.of(2026, 1, 6), "Deuxieme ecriture");
        e2.ajouterLigne(ligneSimple(compteVente, BigDecimal.ZERO, new BigDecimal("2000")));

        when(ecritureComptableRepository.findByExerciceIdAndStatutFetchAll(exerciceId, StatutEcriture.VALIDEE))
                .thenReturn(List.of(e1, e2));

        FecExportDto export = exportFecService.exporter(entrepriseId, exerciceId, principal);

        String[] lignes = export.contenu().split("\n");
        assertThat(lignes[1]).startsWith("VTE\tJournal des ventes\t1\t");
        assertThat(lignes[2]).startsWith("VTE\tJournal des ventes\t2\t");
    }

    @Test
    void inclut_le_code_et_la_date_de_lettrage_quand_la_ligne_est_lettree() {
        EcritureComptable ecriture = ecriture("VTE-2026-0003", LocalDate.of(2026, 2, 10), "Reglement");
        LigneEcriture ligne = ligneSimple(compteClient, new BigDecimal("10000"), BigDecimal.ZERO);
        ligne.setLettrage("A");
        ligne.setDateLettrage(LocalDate.of(2026, 2, 15));
        ecriture.ajouterLigne(ligne);

        when(ecritureComptableRepository.findByExerciceIdAndStatutFetchAll(exerciceId, StatutEcriture.VALIDEE))
                .thenReturn(List.of(ecriture));

        FecExportDto export = exportFecService.exporter(entrepriseId, exerciceId, principal);

        assertThat(export.contenu()).contains("\tA\t20260215\t");
    }

    @Test
    void refuse_un_exercice_dune_autre_entreprise() {
        exercice.setEntreprise(new Entreprise());
        exercice.getEntreprise().setId(UUID.randomUUID());

        assertThatThrownBy(() -> exportFecService.exporter(entrepriseId, exerciceId, principal))
                .hasMessageContaining("introuvable");
    }

    @Test
    void refuse_un_exercice_introuvable() {
        when(exerciceComptableRepository.findById(exerciceId)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> exportFecService.exporter(entrepriseId, exerciceId, principal))
                .hasMessageContaining("introuvable");
    }

    private LigneEcriture ligneSimple(CompteComptable compte, BigDecimal debit, BigDecimal credit) {
        LigneEcriture ligne = new LigneEcriture();
        ligne.setId(UUID.randomUUID());
        ligne.setCompte(compte);
        ligne.setDebit(debit);
        ligne.setCredit(credit);
        return ligne;
    }
}
