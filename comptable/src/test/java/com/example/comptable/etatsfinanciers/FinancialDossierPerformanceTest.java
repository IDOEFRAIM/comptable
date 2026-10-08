package com.example.comptable.etatsfinanciers;

import com.example.comptable.banque.BankTestBase;
import com.example.comptable.comptabilite.domain.CompteComptable;
import com.example.comptable.comptabilite.repository.ExerciceComptableRepository;
import com.example.comptable.etatsfinanciers.dossier.FinancialDossierService;
import com.example.comptable.etatsfinanciers.service.BalanceSheetService;
import com.example.comptable.etatsfinanciers.service.CashFlowStatementService;
import com.example.comptable.etatsfinanciers.service.IncomeStatementService;
import com.example.comptable.etatsfinanciers.notes.NoteWorkspaceService;
import jakarta.persistence.EntityManagerFactory;
import org.hibernate.SessionFactory;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Performance de la synthese du dossier financier : le nombre de requetes (critere stable, jamais le temps) reste borne et independant du volume (comptes, ecritures). Les temps sont
 * seulement journalises pour le profilage (docs/financial-review-workspace.md).
 */
class FinancialDossierPerformanceTest extends BankTestBase {

    @Autowired FinancialDossierService dossierService;
    @Autowired IncomeStatementService incomeStatementService;
    @Autowired BalanceSheetService balanceSheetService;
    @Autowired CashFlowStatementService cashFlowStatementService;
    @Autowired NoteWorkspaceService noteWorkspaceService;
    @Autowired ExerciceComptableRepository exerciceRepository;
    @Autowired EntityManagerFactory entityManagerFactory;

    private long statements() {
        return entityManagerFactory.unwrap(SessionFactory.class).getStatistics().getPrepareStatementCount();
    }

    private UUID compte(Dossier d, String numero) {
        return compteComptableRepository.findByEntrepriseIdAndNumero(d.id(), numero).orElseGet(() -> {
            CompteComptable c = new CompteComptable();
            c.setEntreprise(entrepriseRepository.getReferenceById(d.id()));
            c.setNumero(numero);
            c.setIntitule("Compte " + numero);
            c.setLettrable(false);
            return compteComptableRepository.save(c);
        }).getId();
    }

    /** Dossier volumineux par insertion en masse : {@code ventes} ecritures client/produit sur {@code comptes} comptes de produits, et {@code banque} encaissements. */
    private void remplir(Dossier d, int comptes, int ventes, int banque) {
        UUID exercice = exerciceRepository.findByEntrepriseIdAndDate(d.id(), LocalDate.now()).orElseThrow().getId();
        UUID journal = jdbc.queryForObject("select id from journal where entreprise_id = ? order by code limit 1", UUID.class, d.id());
        UUID client = compte(d, "411900");
        UUID bank = compte(d, "521");
        List<UUID> produits = new ArrayList<>();
        for (int i = 0; i < comptes; i++) {
            produits.add(compte(d, "7011" + String.format("%03d", i)));
        }
        List<Object[]> ecritures = new ArrayList<>();
        List<Object[]> lignes = new ArrayList<>();
        for (int i = 0; i < ventes + banque; i++) {
            UUID id = UUID.randomUUID();
            ecritures.add(new Object[]{id, d.id(), exercice, journal, "PERF-" + i, LocalDate.now(), "Perf " + i});
            if (i < ventes) {
                lignes.add(new Object[]{UUID.randomUUID(), id, client, 10, 0, 1});
                lignes.add(new Object[]{UUID.randomUUID(), id, produits.get(i % comptes), 0, 10, 2});
            } else {
                lignes.add(new Object[]{UUID.randomUUID(), id, bank, 10, 0, 1});
                lignes.add(new Object[]{UUID.randomUUID(), id, client, 0, 10, 2});
            }
        }
        jdbc.batchUpdate("insert into ecriture_comptable (id, entreprise_id, exercice_id, journal_id, numero_piece, date_ecriture, libelle, statut) values (?, ?, ?, ?, ?, ?, ?, 'VALIDEE')", ecritures);
        jdbc.batchUpdate("insert into ligne_ecriture (id, ecriture_id, compte_id, libelle, debit, credit, numero_ligne) values (?, ?, ?, 'L', ?, ?, ?)", lignes);
    }

    private record Mesure(long requetes, long millis) {
    }

    private Mesure mesurer(Runnable action) {
        long avant = statements();
        long debut = System.nanoTime();
        action.run();
        return new Mesure(statements() - avant, (System.nanoTime() - debut) / 1_000_000);
    }

    @Test
    void synthese_nombreDeRequetesBorne_etIndependantDuVolume() {
        Dossier peu = nouveauDossier(false);
        Dossier beaucoup = nouveauDossier(false);
        remplir(peu, 2, 20, 5);
        remplir(beaucoup, 150, 10_000, 5_000);
        UUID exPeu = exerciceRepository.findByEntrepriseIdAndDate(peu.id(), LocalDate.now()).orElseThrow().getId();
        UUID exBeaucoup = exerciceRepository.findByEntrepriseIdAndDate(beaucoup.id(), LocalDate.now()).orElseThrow().getId();

        dossierService.synthese(peu.id(), exPeu, peu.expert());                 // creation des 46 notes + echauffement
        dossierService.synthese(beaucoup.id(), exBeaucoup, beaucoup.expert());

        Mesure petitCr = mesurer(() -> incomeStatementService.generer(peu.id(), exPeu, peu.expert()));
        Mesure petitBilan = mesurer(() -> balanceSheetService.bilan(peu.id(), exPeu, peu.expert()));
        Mesure petitTft = mesurer(() -> cashFlowStatementService.generer(peu.id(), exPeu, peu.expert()));
        Mesure petitesNotes = mesurer(() -> noteWorkspaceService.lister(peu.id(), exPeu, peu.expert()));
        Mesure petiteSynthese = mesurer(() -> dossierService.synthese(peu.id(), exPeu, peu.expert()));

        Mesure grandCr = mesurer(() -> incomeStatementService.generer(beaucoup.id(), exBeaucoup, beaucoup.expert()));
        Mesure grandBilan = mesurer(() -> balanceSheetService.bilan(beaucoup.id(), exBeaucoup, beaucoup.expert()));
        Mesure grandTft = mesurer(() -> cashFlowStatementService.generer(beaucoup.id(), exBeaucoup, beaucoup.expert()));
        Mesure grandesNotes = mesurer(() -> noteWorkspaceService.lister(beaucoup.id(), exBeaucoup, beaucoup.expert()));
        Mesure grandeSynthese = mesurer(() -> dossierService.synthese(beaucoup.id(), exBeaucoup, beaucoup.expert()));

        System.out.println("PERF petit  : CR " + petitCr + " | Bilan " + petitBilan + " | TFT " + petitTft + " | Notes " + petitesNotes + " | SYNTHESE " + petiteSynthese);
        System.out.println("PERF grand  : CR " + grandCr + " | Bilan " + grandBilan + " | TFT " + grandTft + " | Notes " + grandesNotes + " | SYNTHESE " + grandeSynthese);

        assertThat(grandeSynthese.requetes()).as("synthese : 10 000 ecritures / 150 comptes vs 20 ecritures / 2 comptes").isEqualTo(petiteSynthese.requetes());
        assertThat(grandCr.requetes()).isEqualTo(petitCr.requetes());
        assertThat(grandBilan.requetes()).isEqualTo(petitBilan.requetes());
        assertThat(grandTft.requetes()).isEqualTo(petitTft.requetes());
        assertThat(grandesNotes.requetes()).isEqualTo(petitesNotes.requetes());
        assertThat(grandeSynthese.requetes()).as("aucune requete par compte ni par note").isLessThan(150);
    }
}
