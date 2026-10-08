package com.example.comptable.fiscalite.vat;

import com.example.comptable.collecte.CollecteTestBase;
import com.example.comptable.comptabilite.domain.CompteComptable;
import com.example.comptable.comptabilite.parametrage.domain.AccountPurpose;
import com.example.comptable.comptabilite.parametrage.service.AccountingAccountMappingService;
import com.example.comptable.comptabilite.parametrage.web.dto.CreerAccountingAccountMappingRequest;
import com.example.comptable.comptabilite.service.EcritureComptableService;
import com.example.comptable.comptabilite.web.dto.CreerEcritureRequest;
import com.example.comptable.comptabilite.web.dto.LigneEcritureRequest;
import com.example.comptable.fiscalite.core.TaxFrameworkService;
import com.example.comptable.fiscalite.core.TaxFrameworkService.CreateRequest;
import com.example.comptable.fiscalite.core.TaxFrameworkService.TaxFramework;
import com.example.comptable.fiscalite.core.VatRules;
import com.example.comptable.fiscalite.vat.VatDtos.OpenRequest;
import com.example.comptable.fiscalite.vat.VatDtos.PeriodView;
import com.example.comptable.identite.domain.Tenant;
import com.example.comptable.security.UtilisateurPrincipal;
import org.springframework.beans.factory.annotation.Autowired;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Outils des tests TVA. TOUS les cadres fiscaux créés ici sont des FIXTURES {@code TEST_ONLY} : version préfixée {@code TEST_ONLY-}, codes de taux {@code TEST_ONLY_*}, valeurs inventées pour le test,
 * propres au cabinet de test. Ils ne sont jamais disponibles comme pack réglementaire réel et aucune application ne les sème.
 */
abstract class VatTestBase extends CollecteTestBase {

    @Autowired VatWorkspaceService tva;
    @Autowired VatSettlementService reglement;
    @Autowired TaxFrameworkService cadres;
    @Autowired EcritureComptableService ecritureService;
    @Autowired AccountingAccountMappingService mappingService;

    protected static final LocalDate DEBUT = LocalDate.now().withDayOfMonth(1);
    protected static final LocalDate FIN = DEBUT.plusMonths(1).minusDays(1);

    protected static BigDecimal n(String s) {
        return new BigDecimal(s);
    }

    protected UUID compte(Dossier d, String numero) {
        return compteComptableRepository.findByEntrepriseIdAndNumero(d.id(), numero).orElseGet(() -> {
            CompteComptable c = new CompteComptable();
            c.setEntreprise(entrepriseRepository.getReferenceById(d.id()));
            c.setNumero(numero);
            c.setIntitule("Compte de test " + numero);
            c.setLettrable(false);
            return compteComptableRepository.save(c);
        }).getId();
    }

    /** Dossier dont les comptes de TVA sont configurés ; un cadre FIXTURE {@code TEST_ONLY} VERIFIED y est ajouté si demandé. */
    protected Dossier dossier(boolean cadreVerifie) {
        return dossier(cadreVerifie, null, null);
    }

    protected Dossier dossier(boolean cadreVerifie, VatRules.DueRule echeance, Boolean report) {
        Dossier d = nouveauDossier(false);
        mappingService.creer(d.id(), new CreerAccountingAccountMappingRequest(AccountPurpose.TVA_COLLECTEE, null, compte(d, "443900"), null), d.expert());
        mappingService.creer(d.id(), new CreerAccountingAccountMappingRequest(AccountPurpose.TVA_RECUPERABLE, null, compte(d, "445900"), null), d.expert());
        if (cadreVerifie) {
            verifier(d, "TEST_ONLY-" + UUID.randomUUID().toString().substring(0, 6), echeance, report);
        }
        return d;
    }

    protected TaxFramework verifier(Dossier d, String version, VatRules.DueRule echeance, Boolean report) {
        VatRules regles = new VatRules(List.of(new VatRules.Rate("TEST_ONLY_T10", "Taux fictif TEST_ONLY A", n("10")), new VatRules.Rate("TEST_ONLY_T5", "Taux fictif TEST_ONLY B", n("5"))), "MONTHLY", echeance,
                n("1"), n("0.5"), List.of(), report);
        TaxFramework f = cadres.creer(d.expert(), new CreateRequest("CI", "TVA", version, LocalDate.of(2000, 1, 1), null, regles, List.of("Source fictive TEST_ONLY, aucune valeur réglementaire")));
        return cadres.verifier(d.expert(), f.id());
    }

    protected UtilisateurPrincipal collaborateur(Dossier d) {
        Tenant t = tenantRepository.findById(d.expert().getTenantId()).orElseThrow();
        return new UtilisateurPrincipal(utilisateur(t, "COLLABORATEUR", null));
    }

    protected UtilisateurPrincipal client(Dossier d) {
        Tenant t = tenantRepository.findById(d.expert().getTenantId()).orElseThrow();
        return new UtilisateurPrincipal(utilisateur(t, "CLIENT", null));
    }

    protected UUID poster(Dossier d, LocalDate date, String piece, List<Object[]> lignes) {
        List<LigneEcritureRequest> l = new ArrayList<>();
        for (Object[] x : lignes) {
            l.add(new LigneEcritureRequest(compte(d, (String) x[0]), (String) x[0], (BigDecimal) x[1], (BigDecimal) x[2], null));
        }
        UUID journal = jdbc.queryForObject("select id from journal where entreprise_id = ? order by code limit 1", UUID.class, d.id());
        UUID id = ecritureService.creerManuelle(d.id(), new CreerEcritureRequest(journal, date, "Opération " + piece, piece, l, null), d.expert()).id();
        ecritureService.valider(d.id(), id, d.expert());
        return id;
    }

    protected UUID vente(Dossier d, String piece, String ht, String taxe) {
        return vente(d, DEBUT.plusDays(2), piece, ht, taxe);
    }

    protected UUID vente(Dossier d, LocalDate date, String piece, String ht, String taxe) {
        BigDecimal ttc = n(ht).add(n(taxe));
        return poster(d, date, piece, List.of(new Object[] {"521900", ttc, BigDecimal.ZERO}, new Object[] {"701900", BigDecimal.ZERO, n(ht)}, new Object[] {"443900", BigDecimal.ZERO, n(taxe)}));
    }

    protected UUID achat(Dossier d, String piece, String ht, String taxe) {
        BigDecimal ttc = n(ht).add(n(taxe));
        return poster(d, DEBUT.plusDays(3), piece, List.of(new Object[] {"601900", n(ht), BigDecimal.ZERO}, new Object[] {"445900", n(taxe), BigDecimal.ZERO}, new Object[] {"521900", BigDecimal.ZERO, ttc}));
    }

    /** Opération touchant les deux comptes de TVA : non déterminable, à décider (TO_REVIEW). */
    protected UUID mixte(Dossier d, String piece) {
        return poster(d, DEBUT.plusDays(4), piece, List.of(new Object[] {"443900", n("10"), BigDecimal.ZERO}, new Object[] {"445900", BigDecimal.ZERO, n("10")}));
    }

    protected PeriodView ouvrir(Dossier d) {
        return tva.ouvrir(d.expert(), d.id(), new OpenRequest(DEBUT, FIN));
    }

    protected List<com.example.comptable.agent.finding.domain.Finding> constats(Dossier d, String code) {
        return findingRepository.findActifsParCodes(d.id(), List.of(code));
    }

    /** Prépare, fait réviser puis finalise une période (cadre FIXTURE vérifié requis). */
    protected PeriodView finaliser(Dossier d, PeriodView p) {
        tva.preparer(collaborateur(d), d.id(), p.id());
        tva.revoir(d.expert(), d.id(), p.id());
        return tva.finaliser(d.expert(), d.id(), p.id());
    }
}
