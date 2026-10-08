package com.example.comptable.etatsfinanciers;

import com.example.comptable.banque.BankTestBase;
import com.example.comptable.comptabilite.balance.AccountingBalanceEngine;
import com.example.comptable.comptabilite.balance.ReportingView;
import com.example.comptable.comptabilite.domain.CompteComptable;
import com.example.comptable.comptabilite.domain.StatutExercice;
import com.example.comptable.comptabilite.repository.ExerciceComptableRepository;
import com.example.comptable.comptabilite.service.ClotureExerciceService;
import com.example.comptable.comptabilite.service.EcritureComptableService;
import com.example.comptable.comptabilite.web.dto.CreerEcritureRequest;
import com.example.comptable.comptabilite.web.dto.LigneEcritureRequest;
import com.example.comptable.etatsfinanciers.service.BalanceSheetService;
import com.example.comptable.etatsfinanciers.service.IncomeStatementService;
import com.example.comptable.etatsfinanciers.service.StatementResult;
import com.example.comptable.etatsfinanciers.service.StatementResult.Status;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * La cloture s'appuie sur le moteur de balance canonique (aucun second moteur de resultat) : le resultat poste en 120/129 est celui de l'activite des comptes de gestion (classes 6, 7 et 8),
 * l'ecriture technique de cloture ne change ni l'activite mesuree ni le Compte de resultat (XI), et le Bilan reste coherent (CJ = XI, BZ = DZ) avant comme apres.
 */
class ClotureMoteurCanoniqueTest extends BankTestBase {

    @Autowired EcritureComptableService ecritureService;
    @Autowired ClotureExerciceService clotureService;
    @Autowired IncomeStatementService incomeStatementService;
    @Autowired BalanceSheetService balanceSheetService;
    @Autowired AccountingBalanceEngine balanceEngine;
    @Autowired ExerciceComptableRepository exerciceRepository;

    private UUID compte(Dossier d, String prefixe) {
        String numero = prefixe + "900";
        return compteComptableRepository.findByEntrepriseIdAndNumero(d.id(), numero).orElseGet(() -> {
            CompteComptable c = new CompteComptable();
            c.setEntreprise(entrepriseRepository.getReferenceById(d.id()));
            c.setNumero(numero);
            c.setIntitule("Compte de test " + numero);
            c.setLettrable(false);
            return compteComptableRepository.save(c);
        }).getId();
    }

    private void ecrire(Dossier d, Map<String, Integer> debits, Map<String, Integer> credits) {
        List<LigneEcritureRequest> lignes = new ArrayList<>();
        debits.forEach((k, v) -> lignes.add(new LigneEcritureRequest(compte(d, k), "D " + k, new BigDecimal(v), BigDecimal.ZERO, null)));
        credits.forEach((k, v) -> lignes.add(new LigneEcritureRequest(compte(d, k), "C " + k, BigDecimal.ZERO, new BigDecimal(v), null)));
        UUID journal = jdbc.queryForObject("select id from journal where entreprise_id = ? order by code limit 1", UUID.class, d.id());
        UUID id = ecritureService.creerManuelle(d.id(), new CreerEcritureRequest(journal, LocalDate.now(), "Scenario cloture", null, lignes), d.expert()).id();
        ecritureService.valider(d.id(), id, d.expert());
    }

    private static Map<String, Integer> m(Object... kv) {
        Map<String, Integer> r = new LinkedHashMap<>();
        for (int i = 0; i < kv.length; i += 2) {
            r.put((String) kv[i], (Integer) kv[i + 1]);
        }
        return r;
    }

    private static BigDecimal ligne(StatementResult etat, String code) {
        return etat.lines().stream().filter(l -> l.code().equals(code)).findFirst().orElseThrow().amountN();
    }

    @Test
    void cloture_resultatCanonique_XIinchange_CJcoherent_etClasse8Cloturee() {
        Dossier d = nouveauDossier(false);
        var ex = exerciceRepository.findByEntrepriseIdAndDate(d.id(), LocalDate.now()).orElseThrow();
        ecrire(d, m("521", 1_000_000), m("101", 1_000_000));
        ecrire(d, m("521", 600_000), m("701", 600_000));                  // produits d'exploitation
        ecrire(d, m("601", 250_000), m("521", 250_000));                  // charges d'exploitation
        ecrire(d, m("81", 30_000), m("521", 30_000));                     // charge HAO (classe 8)
        ecrire(d, m("521", 50_000), m("82", 50_000));                     // produit HAO (classe 8)

        StatementResult avantCr = incomeStatementService.generer(d.id(), ex.getId(), d.expert());
        var avantBilan = balanceSheetService.bilan(d.id(), ex.getId(), d.expert());
        BigDecimal xiAvant = ligne(avantCr, "XI");
        assertThat(xiAvant).isEqualByComparingTo("370000");              // 600 000 - 250 000 - 30 000 + 50 000
        assertThat(avantBilan.resultatBilanN()).isEqualByComparingTo(xiAvant);
        assertThat(avantBilan.status()).isEqualTo(Status.READY);

        clotureService.cloturerAvecConfirmation(d.id(), ex.getId(), d.expert(), new ClotureExerciceService.ConfirmationCloture(true, "Test cloture canonique"));

        assertThat(exerciceRepository.findById(ex.getId()).orElseThrow().getStatut()).isEqualTo(StatutExercice.CLOTURE);
        assertThat(jdbc.queryForObject("select count(*) from ecriture_comptable where entreprise_id = ? and nature = 'CLOSING'", Long.class, d.id())).isEqualTo(1L);
        // le resultat poste au grand livre legal (120 benefice) est celui du moteur canonique, classe 8 incluse
        assertThat(jdbc.queryForObject("select coalesce(sum(l.credit - l.debit), 0) from ligne_ecriture l join compte_comptable c on c.id = l.compte_id join ecriture_comptable e on e.id = l.ecriture_id "
                + "where c.entreprise_id = ? and e.nature = 'CLOSING' and c.numero like '12%'", BigDecimal.class, d.id())).isEqualByComparingTo("370000");
        // les comptes de gestion, y compris la classe 8, sont soldes au grand livre legal
        for (String numero : List.of("701900", "601900", "81900", "82900")) {
            var legal = balanceEngine.balance(d.id(), compteComptableRepository.findByEntrepriseIdAndNumero(d.id(), numero).orElseThrow().getId(), ex.getDateDebut(), ex.getDateFin(),
                    ReportingView.LEGAL_LEDGER).orElseThrow();
            assertThat(legal.netBalance()).as(numero).isEqualByComparingTo("0");
        }

        // reporting apres cloture : XI identique, Bilan toujours coherent (CJ = XI, BZ = DZ)
        StatementResult apresCr = incomeStatementService.generer(d.id(), ex.getId(), d.expert());
        var apresBilan = balanceSheetService.bilan(d.id(), ex.getId(), d.expert());
        assertThat(ligne(apresCr, "XI")).isEqualByComparingTo(xiAvant);
        assertThat(apresBilan.resultatBilanN()).isEqualByComparingTo(xiAvant);
        assertThat(apresBilan.resultatBilanN()).isEqualByComparingTo(apresBilan.resultatCompteResultatN());
        assertThat(apresBilan.status()).isEqualTo(Status.READY);
        assertThat(apresBilan.totalActifN()).isEqualByComparingTo(apresBilan.totalPassifN());
        assertThat(apresBilan.totalActifN()).isEqualByComparingTo(avantBilan.totalActifN());
    }
}
