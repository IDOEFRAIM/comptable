package com.example.comptable.investigation;

import com.example.comptable.agent.finding.domain.Finding;
import com.example.comptable.banque.BankTestBase;
import com.example.comptable.comptabilite.allocation.PaymentAllocationService;
import com.example.comptable.comptabilite.allocation.SourceAllocation;
import com.example.comptable.comptabilite.domain.ModeAmortissement;
import com.example.comptable.comptabilite.domain.TypeFacture;
import com.example.comptable.comptabilite.domain.TypeImmobilisation;
import com.example.comptable.comptabilite.domain.TypeTiers;
import com.example.comptable.comptabilite.operation.common.AccountingOperationType;
import com.example.comptable.comptabilite.operation.common.MoyenPaiement;
import com.example.comptable.comptabilite.operation.reglement.ReglementOperationRequest;
import com.example.comptable.comptabilite.service.FacturationService;
import com.example.comptable.comptabilite.service.ImmobilisationService;
import com.example.comptable.comptabilite.service.RapprochementBancaireService;
import com.example.comptable.comptabilite.web.dto.CreerFactureRequest;
import com.example.comptable.comptabilite.web.dto.CreerImmobilisationRequest;
import com.example.comptable.comptabilite.web.dto.FactureDto;
import com.example.comptable.comptabilite.web.dto.ImmobilisationDto;
import com.example.comptable.comptabilite.web.dto.ImporterReleveRequest;
import com.example.comptable.comptabilite.web.dto.LigneFactureRequest;
import com.example.comptable.comptabilite.web.dto.ReleveBancaireDto;
import com.example.comptable.comptabilite.web.dto.TiersDto;
import com.example.comptable.controle.ControlCodes;
import com.example.comptable.investigation.InvestigationResult.Cause;
import com.example.comptable.investigation.InvestigationResult.Nature;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.web.server.ResponseStatusException;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Accounting Debugger : chaque investigation reconstitue la chaine source -> consequences -> rapprochements -> affectations -> solde -> anomalie, avec des
 * causes a code stable et leurs preuves. Les situations NORMALES (facture partiellement payee, solde correct) ne produisent aucune anomalie.
 */
class AccountingInvestigationTest extends BankTestBase {

    @Autowired AccountingInvestigationService investigation;
    @Autowired FacturationService facturationService;
    @Autowired PaymentAllocationService allocationService;
    @Autowired RapprochementBancaireService rapprochementService;
    @Autowired ImmobilisationService immobilisationService;

    // ------------------------------------------------------------------------------------------------ outils

    private FactureDto factureClient(Dossier d, TiersDto client, int prixHt) {
        UUID produit = compteComptableRepository.findByEntrepriseIdAndNumero(d.id(), "701").orElseThrow().getId();
        FactureDto brouillon = facturationService.creer(d.id(), TypeFacture.FACTURE, new CreerFactureRequest(client.id(), LocalDate.now().minusDays(45),
                LocalDate.now().minusDays(15), "Vente", List.of(new LigneFactureRequest("Marchandise", BigDecimal.ONE, new BigDecimal(prixHt), BigDecimal.ZERO, null))),
                d.expert());
        return facturationService.validerFacture(d.id(), brouillon.id(), produit, d.expert());
    }

    private UUID ligneClient(UUID ecritureId, String cote) {
        return jdbc.queryForObject("select l.id from ligne_ecriture l join compte_comptable c on c.id = l.compte_id where l.ecriture_id = ? and c.numero like '41%' and l."
                + cote + " > 0", UUID.class, ecritureId);
    }

    private UUID encaissement(Dossier d, TiersDto client, int montant) {
        UUID ecriture = engine.valider(d.id(), engine.proposerReglement(d.id(), new ReglementOperationRequest(AccountingOperationType.REGLEMENT_CLIENT, client.id(),
                LocalDate.now(), "ENC-" + UUID.randomUUID().toString().substring(0, 5), "Encaissement", new BigDecimal(montant), MoyenPaiement.VIREMENT), d.expert()).id(),
                d.expert()).ecritureId();
        return ligneClient(ecriture, "credit");
    }

    private List<Cause> causes(InvestigationResult r, String code) {
        return r.causes().stream().filter(c -> c.code().equals(code)).toList();
    }

    private boolean aucuneAnomalie(InvestigationResult r) {
        return r.causes().stream().noneMatch(c -> c.nature() == Nature.ANOMALY);
    }

    // ------------------------------------------------------------------------------------------------ tiers

    @Test
    void client_deuxPaiements_uneAllocationInsuffisante_expliqueLeSoldeCrediteur() {
        Dossier d = nouveauDossier(false);
        TiersDto client = tiersService.creer(d.id(), TypeTiers.CLIENT, "Client Omega", null, d.expert());
        FactureDto facture = factureClient(d, client, 1_000_000);
        UUID ligneFacture = ligneClient(facture.ecritureId(), "debit");
        BigDecimal ttc = jdbc.queryForObject("select debit from ligne_ecriture where id = ?", BigDecimal.class, ligneFacture);
        UUID paiement1 = encaissement(d, client, 600_000);
        UUID paiement2 = encaissement(d, client, 700_000);
        allocationService.allouer(d.id(), paiement1, ligneFacture, new BigDecimal("600000"), SourceAllocation.USER, d.expert());
        allocationService.allouer(d.id(), paiement2, ligneFacture, ttc.subtract(new BigDecimal("600000")), SourceAllocation.USER, d.expert());
        BigDecimal nonAffecte = new BigDecimal("1300000").subtract(ttc);

        InvestigationResult r = investigation.investiguer(d.id(), "TIERS", client.id(), null, d.expert());

        // Le reglement de 700 000 n'est affecte qu'a hauteur du reste de la facture : le reliquat est une cause STRUCTUREE, avec preuves.
        Cause nonAlloue = causes(r, CauseCodes.UNALLOCATED_PAYMENT).get(0);
        assertThat(nonAlloue.nature()).isEqualTo(Nature.ANOMALY);
        assertThat(nonAlloue.amount()).isEqualByComparingTo(nonAffecte);
        assertThat(nonAlloue.basis()).isEqualTo(InvestigationResult.Basis.PERSISTED);
        assertThat(nonAlloue.evidence()).extracting(e -> e.kind()).contains("PAYMENT", "ALLOCATION");
        assertThat(nonAlloue.evidence().stream().filter(e -> e.kind().equals("ALLOCATION")).map(e -> e.amount())).isNotEmpty();
        // Plus aucune facture ouverte : c'est un trop-percu, pas un reglement a repartir.
        assertThat(causes(r, CauseCodes.OVERPAYMENT)).hasSize(1);
        // Le solde crediteur est EXPLIQUE avant d'etre qualifie : aucun solde « inexplique ».
        assertThat(causes(r, CauseCodes.CREDIT_BALANCE_EXPLAINED)).hasSize(1);
        assertThat(causes(r, CauseCodes.CREDIT_BALANCE_UNEXPLAINED)).isEmpty();
        assertThat(causes(r, CauseCodes.CREDIT_BALANCE_EXPLAINED).get(0).amount()).isEqualByComparingTo(nonAffecte);
        // L'equation du solde se reconstitue exactement : restant du selon les affectations = solde du grand livre.
        assertThat(r.comparison().difference()).isEqualByComparingTo("0");
        assertThat(r.comparison().actual()).isEqualByComparingTo(nonAffecte.negate());
        // Chronologie : facture, deux reglements, deux affectations.
        assertThat(r.timeline()).extracting(t -> t.type()).contains("FACTURE", "REGLEMENT", "ALLOCATION");
        assertThat(r.timeline().stream().filter(t -> t.type().equals("ALLOCATION"))).hasSize(2);
        // Objets navigables, libelles lisibles (jamais un identifiant seul).
        assertThat(r.relatedEntities()).extracting(x -> x.label()).anyMatch(l -> l.contains("Facture")).anyMatch(l -> l.contains("Reglement"));
        // Jamais une correction : l'action suggeree designe une operation existante.
        assertThat(r.suggestedActions()).extracting(a -> a.code()).contains("DECIDE_SURPLUS");
        assertThat(r.suggestedActions()).allSatisfy(a -> assertThat(a.operation()).isNotBlank());
        assertThat(r.confidence()).isEqualTo(InvestigationResult.Confidence.HIGH);
    }

    @Test
    void factureOuverteAvecPaiementPartiel_estNormale_aucuneAnomalie() {
        Dossier d = nouveauDossier(false);
        TiersDto client = tiersService.creer(d.id(), TypeTiers.CLIENT, "Client Sigma", null, d.expert());
        FactureDto facture = factureClient(d, client, 1_000_000);
        UUID ligneFacture = ligneClient(facture.ecritureId(), "debit");
        BigDecimal ttc = jdbc.queryForObject("select debit from ligne_ecriture where id = ?", BigDecimal.class, ligneFacture);
        allocationService.allouer(d.id(), encaissement(d, client, 600_000), ligneFacture, new BigDecimal("600000"), SourceAllocation.USER, d.expert());

        InvestigationResult r = investigation.investiguer(d.id(), "TIERS", client.id(), null, d.expert());

        assertThat(aucuneAnomalie(r)).isTrue();
        Cause partiel = causes(r, CauseCodes.PARTIAL_PAYMENT).get(0);
        assertThat(partiel.nature()).isEqualTo(Nature.INFO);
        assertThat(partiel.amount()).isEqualByComparingTo(ttc.subtract(new BigDecimal("600000")));
        assertThat(causes(r, CauseCodes.NORMAL_BALANCE)).hasSize(1);
        assertThat(r.summary()).contains("Rien a corriger");
        // Facture ouverte detaillee : montant, affecte, reste, anciennete (45 jours), reglements.
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> ouvertes = (List<Map<String, Object>>) r.details().get("facturesOuvertes");
        assertThat(ouvertes).hasSize(1);
        assertThat(((Number) ouvertes.get(0).get("ageJours")).longValue()).isEqualTo(45);
        assertThat((BigDecimal) ouvertes.get(0).get("affecte")).isEqualByComparingTo("600000");
        assertThat((List<?>) ouvertes.get(0).get("paiements")).hasSize(1);
        @SuppressWarnings("unchecked")
        Map<String, Object> agee = (Map<String, Object>) r.details().get("balanceAgeeFacturesOuvertes");
        assertThat((BigDecimal) agee.get("de31a60")).isEqualByComparingTo(ttc.subtract(new BigDecimal("600000")));
    }

    @Test
    void reglementNonAffecte_alorsQuUneFactureEstOuverte_proposeDeRepartir() {
        Dossier d = nouveauDossier(false);
        TiersDto client = tiersService.creer(d.id(), TypeTiers.CLIENT, "Client Tau", null, d.expert());
        FactureDto facture = factureClient(d, client, 1_000_000);
        UUID paiement = encaissement(d, client, 300_000);
        UUID ligneFacture = ligneClient(facture.ecritureId(), "debit");

        InvestigationResult r = investigation.investiguer(d.id(), "LIGNE_ECRITURE", paiement, null, d.expert());

        assertThat(causes(r, CauseCodes.UNALLOCATED_PAYMENT)).hasSize(1);
        assertThat(causes(r, "OPEN_INVOICES_AVAILABLE")).hasSize(1);
        assertThat(r.suggestedActions()).extracting(a -> a.code()).contains("ALLOCATE_PAYMENT");
        assertThat(r.relatedEntities()).extracting(x -> x.type()).contains("TIERS", "ECRITURE");
        assertThat(ligneFacture).isNotNull();
    }

    // ------------------------------------------------------------------------------------------------ banque

    private ReleveBancaireDto releve(Dossier d, UUID compte, BigDecimal soldeDebut, BigDecimal soldeFin, String... lignes) {
        StringBuilder csv = new StringBuilder("date;libelle;montant\n");
        int i = 1;
        for (String m : lignes) {
            csv.append(LocalDate.now()).append(";Operation ").append(i++).append(';').append(m).append('\n');
        }
        return rapprochementService.importer(d.id(), new ImporterReleveRequest(compte, "Releve du mois", LocalDate.now().minusDays(10), LocalDate.now(), soldeDebut, soldeFin),
                new MockMultipartFile("fichier", "releve.csv", "text/csv", csv.toString().getBytes(StandardCharsets.UTF_8)), d.expert());
    }

    @Test
    void releve_soldeComptableEtSoldeReleveDifferent_ecartExpliqueParLaTransactionNonRapprochee() {
        Dossier d = nouveauDossier(false);
        Reglement reglement = reglement(d, 600_000, LocalDate.now(), "Reglement fournisseur");
        BigDecimal comptable = jdbc.queryForObject("select coalesce(sum(l.debit) - sum(l.credit), 0) from ligne_ecriture l join ecriture_comptable e on e.id = l.ecriture_id "
                + "where l.compte_id = ? and e.statut = 'VALIDEE'", BigDecimal.class, reglement.compteBanqueId());
        BigDecimal soldeReleve = comptable.subtract(new BigDecimal("250000"));
        ReleveBancaireDto releve = releve(d, reglement.compteBanqueId(), BigDecimal.ZERO, soldeReleve, "-600000", "-250000");

        InvestigationResult r = investigation.investiguer(d.id(), "RELEVE", releve.id(), null, d.expert());

        assertThat(r.comparison().expected()).isEqualByComparingTo(comptable);
        assertThat(r.comparison().actual()).isEqualByComparingTo(soldeReleve);
        assertThat(r.comparison().difference()).isEqualByComparingTo("-250000");
        Cause cause = causes(r, CauseCodes.BANK_TRANSACTION_UNMATCHED).get(0);
        assertThat(cause.amount()).isEqualByComparingTo("-250000");
        assertThat(cause.evidence()).hasSize(1);
        assertThat(causes(r, CauseCodes.BANK_DIFFERENCE_UNEXPLAINED)).isEmpty();
        assertThat(r.summary()).contains("s'explique entierement");
        @SuppressWarnings("unchecked")
        Map<String, Object> equation = (Map<String, Object>) r.details().get("equationDeLEcart");
        assertThat((BigDecimal) equation.get("residuInexplique")).isEqualByComparingTo("0");

        // La transaction non rapprochee elle-meme : aucune ecriture de meme montant, action = saisir le reglement.
        UUID transaction = jdbc.queryForObject("select id from ligne_releve where releve_id = ? and montant = -250000", UUID.class, releve.id());
        InvestigationResult t = investigation.investiguer(d.id(), "LIGNE_RELEVE", transaction, null, d.expert());
        assertThat(causes(t, CauseCodes.BANK_TRANSACTION_UNMATCHED)).hasSize(1);
        assertThat(t.suggestedActions()).extracting(a -> a.code()).contains("ENTER_PAYMENT");
        assertThat(t.timeline()).extracting(x -> x.type()).contains("IMPORT", "OPERATION");
    }

    @Test
    void releve_ecartResiduel_estSignaleCommeInexplique() {
        Dossier d = nouveauDossier(false);
        Reglement reglement = reglement(d, 600_000, LocalDate.now(), "Reglement fournisseur");
        BigDecimal comptable = jdbc.queryForObject("select coalesce(sum(l.debit) - sum(l.credit), 0) from ligne_ecriture l join ecriture_comptable e on e.id = l.ecriture_id "
                + "where l.compte_id = ? and e.statut = 'VALIDEE'", BigDecimal.class, reglement.compteBanqueId());
        ReleveBancaireDto releve = releve(d, reglement.compteBanqueId(), BigDecimal.ZERO, comptable.subtract(new BigDecimal("999")), "-600000");

        InvestigationResult r = investigation.investiguer(d.id(), "RELEVE", releve.id(), null, d.expert());

        Cause inexplique = causes(r, CauseCodes.BANK_DIFFERENCE_UNEXPLAINED).get(0);
        assertThat(inexplique.amount()).isEqualByComparingTo("-999");
        assertThat(causes(r, CauseCodes.BANK_TRANSACTION_UNMATCHED)).isEmpty();
    }

    // ------------------------------------------------------------------------------------------------ documents et immobilisations

    @Test
    void ecritureSansPiece_estUneAnomalie_avecLaSuiteDonneeALaDemande() {
        Dossier d = nouveauDossier(false);
        UUID ecriture = acheterSansPiece(d, 80_000);

        InvestigationResult r = investigation.investiguer(d.id(), "ECRITURE", ecriture, null, d.expert());

        Cause manquante = causes(r, CauseCodes.MISSING_DOCUMENT).get(0);
        assertThat(manquante.nature()).isEqualTo(Nature.ANOMALY);
        assertThat(manquante.explanation()).contains("Aucune demande de piece");
        assertThat(r.suggestedActions()).extracting(a -> a.code()).contains("REQUEST_DOCUMENT");
        assertThat(r.timeline()).extracting(t -> t.type()).contains("ECRITURE");
        assertThat(r.comparison().difference()).isEqualByComparingTo("0");
    }

    @Test
    void immobilisationEnServiceSansAcquisition_estUneAnomalie() {
        Dossier d = nouveauDossier(false);
        ImmobilisationDto camion = immobilisationService.creer(d.id(), new CreerImmobilisationRequest(
                compteComptableRepository.findByEntrepriseIdAndNumero(d.id(), "244").orElseThrow().getId(),
                compteComptableRepository.findByEntrepriseIdAndNumero(d.id(), "281").orElseThrow().getId(), "Camion", TypeImmobilisation.CORPORELLE,
                LocalDate.now().withDayOfYear(1), new BigDecimal("5000000"), 5, ModeAmortissement.LINEAIRE), d.expert());
        jdbc.update("update immobilisation set statut = 'EN_SERVICE', date_mise_en_service = ? where id = ?", LocalDate.now().withDayOfYear(1), camion.id());

        InvestigationResult r = investigation.investiguer(d.id(), "IMMOBILISATION", camion.id(), null, d.expert());

        Cause cause = causes(r, CauseCodes.FIXED_ASSET_WITHOUT_SOURCE).get(0);
        assertThat(cause.nature()).isEqualTo(Nature.ANOMALY);
        assertThat(cause.explanation()).contains("deja en service");
        assertThat(r.comparison().difference()).isEqualByComparingTo("-5000000");
        assertThat(r.suggestedActions()).extracting(a -> a.code()).contains("LINK_ACQUISITION");
    }

    // ------------------------------------------------------------------------------------------------ constat -> investigation contextuelle

    @Test
    void constatDeReglementNonAffecte_ouvreLInvestigationDuReglement() {
        Dossier d = nouveauDossier(false);
        TiersDto client = tiersService.creer(d.id(), TypeTiers.CLIENT, "Client Phi", null, d.expert());
        UUID paiement = encaissement(d, client, 250_000);
        controlRunService.executer(entrepriseRepository.findById(d.id()).orElseThrow());
        Finding f = findingRepository.findActifsParCodes(d.id(), List.of(ControlCodes.PAYMENT_WITHOUT_INVOICE)).get(0);

        InvestigationResult r = investigation.depuisConstat(d.id(), f.getId(), d.expert());

        assertThat(r.subject().type()).isEqualTo("LIGNE_ECRITURE");
        assertThat(r.subject().id()).isEqualTo(paiement.toString());
        assertThat(causes(r, CauseCodes.UNALLOCATED_PAYMENT)).hasSize(1);
        assertThat(causes(r, CauseCodes.PAYMENT_WITHOUT_INVOICE)).hasSize(1);
        @SuppressWarnings("unchecked")
        Map<String, Object> constat = (Map<String, Object>) r.details().get("constat");
        assertThat(constat.get("code")).isEqualTo(ControlCodes.PAYMENT_WITHOUT_INVOICE);
    }

    // ------------------------------------------------------------------------------------------------ isolation entre entreprises

    @Test
    void objetsEtConstatsD_uneAutreEntreprise_sontIntrouvables() {
        Dossier a = nouveauDossier(false);
        Dossier b = nouveauDossier(false);
        TiersDto client = tiersService.creer(a.id(), TypeTiers.CLIENT, "Client Prive", null, a.expert());
        UUID paiement = encaissement(a, client, 100_000);
        UUID ecriture = acheterSansPiece(a, 10_000);
        controlRunService.executer(entrepriseRepository.findById(a.id()).orElseThrow());
        Finding constatA = findingRepository.findActifsParCodes(a.id(), List.of(ControlCodes.PAYMENT_WITHOUT_INVOICE)).get(0);

        // Le comptable de B interroge SON entreprise avec les identifiants de A : 404, jamais une fuite.
        assertThatThrownBy(() -> investigation.investiguer(b.id(), "TIERS", client.id(), null, b.expert())).isInstanceOf(ResponseStatusException.class)
                .extracting(e -> ((ResponseStatusException) e).getStatusCode().value()).isEqualTo(404);
        assertThatThrownBy(() -> investigation.investiguer(b.id(), "LIGNE_ECRITURE", paiement, null, b.expert())).isInstanceOf(ResponseStatusException.class)
                .extracting(e -> ((ResponseStatusException) e).getStatusCode().value()).isEqualTo(404);
        assertThatThrownBy(() -> investigation.investiguer(b.id(), "ECRITURE", ecriture, null, b.expert())).isInstanceOf(ResponseStatusException.class)
                .extracting(e -> ((ResponseStatusException) e).getStatusCode().value()).isEqualTo(404);
        assertThatThrownBy(() -> investigation.depuisConstat(b.id(), constatA.getId(), b.expert())).isInstanceOf(ResponseStatusException.class)
                .extracting(e -> ((ResponseStatusException) e).getStatusCode().value()).isEqualTo(404);
        // Et l'inverse : l'entreprise A ne voit pas non plus un objet inexistant chez elle.
        assertThatThrownBy(() -> investigation.investiguer(a.id(), "TIERS", UUID.randomUUID(), null, a.expert())).isInstanceOf(ResponseStatusException.class);
    }
}
