package com.example.comptable.dossier.controles;

import com.example.comptable.agent.finding.domain.Finding;
import com.example.comptable.agent.finding.domain.StatutFinding;
import com.example.comptable.agent.finding.domain.TypeFinding;
import com.example.comptable.agent.finding.repository.FindingRepository;
import com.example.comptable.comptabilite.depreciation.domain.Depreciation;
import com.example.comptable.comptabilite.depreciation.domain.StatutDepreciation;
import com.example.comptable.comptabilite.depreciation.repository.DepreciationRepository;
import com.example.comptable.comptabilite.domain.CompteComptable;
import com.example.comptable.comptabilite.domain.ExerciceComptable;
import com.example.comptable.comptabilite.domain.Immobilisation;
import com.example.comptable.comptabilite.domain.LigneEcriture;
import com.example.comptable.comptabilite.domain.LigneReleve;
import com.example.comptable.comptabilite.domain.ModeAmortissement;
import com.example.comptable.comptabilite.domain.ReleveBancaire;
import com.example.comptable.comptabilite.domain.StatutExercice;
import com.example.comptable.comptabilite.domain.Tiers;
import com.example.comptable.comptabilite.domain.TypeTiers;
import com.example.comptable.comptabilite.parametrage.domain.AccountPurpose;
import com.example.comptable.comptabilite.parametrage.domain.AccountingAccountMapping;
import com.example.comptable.comptabilite.parametrage.repository.AccountingAccountMappingRepository;
import com.example.comptable.comptabilite.provision.domain.Provision;
import com.example.comptable.comptabilite.provision.domain.StatutProvision;
import com.example.comptable.comptabilite.provision.repository.ProvisionRepository;
import com.example.comptable.comptabilite.repository.DotationAmortissementRepository;
import com.example.comptable.comptabilite.repository.EcritureComptableRepository;
import com.example.comptable.comptabilite.repository.ImmobilisationRepository;
import com.example.comptable.comptabilite.repository.LigneEcritureRepository;
import com.example.comptable.comptabilite.repository.ReleveBancaireRepository;
import com.example.comptable.document.domain.Document;
import com.example.comptable.document.domain.StatutDocument;
import com.example.comptable.document.domain.TypeDocument;
import com.example.comptable.document.repository.DocumentRepository;
import com.example.comptable.dossier.web.dto.ControlItem;
import com.example.comptable.entreprise.domain.Entreprise;
import com.example.comptable.fiscalite.domain.DeclarationFiscale;
import com.example.comptable.fiscalite.domain.StatutDeclaration;
import com.example.comptable.fiscalite.domain.TypeImpot;
import com.example.comptable.fiscalite.repository.DeclarationFiscaleRepository;
import com.example.comptable.stock.inventaire.domain.Inventaire;
import com.example.comptable.stock.inventaire.domain.StatutInventaire;
import com.example.comptable.stock.inventaire.repository.InventaireRepository;
import com.example.comptable.stock.repository.ArticleRepository;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Phase 1, lots 1.10/1.11 : chaque controle de pre-cloture est teste dans ses quatre issues
 * (OK / WARNING / BLOCKING / NOT_APPLICABLE) et dans son EXPLICABILITE (message chiffre, objet
 * principal, objets lies, metadonnees).
 */
class ControlesClotureTest {

    private static final UUID ENTREPRISE = UUID.randomUUID();

    private static ExerciceComptable exercice() {
        ExerciceComptable e = new ExerciceComptable();
        e.setId(UUID.randomUUID());
        e.setDateDebut(LocalDate.of(2026, 1, 1));
        e.setDateFin(LocalDate.of(2026, 12, 31));
        e.setStatut(StatutExercice.OUVERT);
        return e;
    }

    private static ControlItem item(List<ControlItem> items, String code) {
        return items.stream().filter(i -> i.code().equals(code)).findFirst()
                .orElseThrow(() -> new AssertionError("Point absent : " + code + " dans " + items.stream().map(ControlItem::code).toList()));
    }

    private static CompteComptable compte(String numero, boolean actif) {
        CompteComptable c = new CompteComptable();
        c.setId(UUID.randomUUID());
        c.setNumero(numero);
        c.setActif(actif);
        return c;
    }

    // =================================================================== ecritures / parametrage

    @Nested
    class Ecritures {
        private final EcritureComptableRepository ecritures = mock(EcritureComptableRepository.class);
        private final AccountingAccountMappingRepository mappings = mock(AccountingAccountMappingRepository.class);
        private final ControleEcrituresCloture controle = new ControleEcrituresCloture(ecritures, mappings);
        private final ExerciceComptable exercice = exercice();

        private List<ControlItem> evaluer() {
            return controle.evaluer(new ClotureControle.Contexte(ENTREPRISE, exercice));
        }

        @Test
        void tout_est_ok_sans_anomalie() {
            assertThat(evaluer()).extracting(ControlItem::statut).containsOnly("OK");
        }

        @Test
        void une_ecriture_desequilibree_est_BLOQUANTE_et_listee() {
            UUID id = UUID.randomUUID();
            when(ecritures.rechercherEcrituresDesequilibrees(exercice.getId())).thenReturn(List.of(id));

            ControlItem point = item(evaluer(), "equilibre_ecritures");

            assertThat(point.statut()).isEqualTo("BLOCKING");
            assertThat(point.severite()).isEqualTo("CRITICAL");
            assertThat(point.entityType()).isEqualTo("ECRITURE");
            assertThat(point.entityId()).isEqualTo(id.toString());
            assertThat(point.relatedEntityIds()).containsExactly(id.toString());
            assertThat(point.metadata()).containsEntry("nbEcrituresDesequilibrees", 1);
        }

        @Test
        void un_compte_desactive_utilise_est_signale_avec_son_numero_et_le_nombre_d_ecritures() {
            when(ecritures.rechercherComptesDesactivesUtilises(exercice.getId()))
                    .thenReturn(java.util.Collections.singletonList(new Object[]{"601", 4L}));

            ControlItem point = item(evaluer(), "comptes_desactives");

            assertThat(point.statut()).isEqualTo("WARNING");
            assertThat(point.message()).contains("601");
            assertThat(point.relatedEntityIds()).containsExactly("601");
            assertThat(point.metadata().toString()).contains("601=4");
        }

        @Test
        void un_mapping_actif_vers_un_compte_desactive_est_signale() {
            AccountingAccountMapping mapping = new AccountingAccountMapping();
            mapping.setId(UUID.randomUUID());
            mapping.setPurpose(AccountPurpose.TVA_RECUPERABLE);
            mapping.setCompte(compte("4456", false));
            mapping.setActif(true);
            AccountingAccountMapping inactif = new AccountingAccountMapping();
            inactif.setId(UUID.randomUUID());
            inactif.setPurpose(AccountPurpose.BANQUE);
            inactif.setCompte(compte("521", false));
            inactif.setActif(false);
            when(mappings.findByEntrepriseId(ENTREPRISE)).thenReturn(List.of(mapping, inactif));

            ControlItem point = item(evaluer(), "mappings_parametrage");

            assertThat(point.statut()).isEqualTo("WARNING");
            assertThat(point.message()).contains("TVA_RECUPERABLE -> 4456").doesNotContain("BANQUE");
            assertThat(point.relatedEntityIds()).containsExactly(mapping.getId().toString());
        }
    }

    // =================================================================== documents

    @Nested
    class Documents {
        private final DocumentRepository documents = mock(DocumentRepository.class);
        private final FindingRepository findings = mock(FindingRepository.class);
        private final ControleDocumentsCloture controle = new ControleDocumentsCloture(documents, findings);
        private final ExerciceComptable exercice = exercice();

        private List<ControlItem> evaluer() {
            return controle.evaluer(new ClotureControle.Contexte(ENTREPRISE, exercice));
        }

        private Document document(LocalDate date, String ttc) {
            Document d = new Document();
            d.setId(UUID.randomUUID());
            d.setTypeDocument(TypeDocument.FACTURE_ACHAT);
            d.setStatut(StatutDocument.TRAITE);
            d.setDatePiece(date);
            d.setMontantTtc(ttc != null ? new BigDecimal(ttc) : null);
            return d;
        }

        @Test
        void ok_sans_facture_en_attente_ni_piece_manquante() {
            assertThat(evaluer()).extracting(ControlItem::statut).containsOnly("OK");
        }

        @Test
        void factures_non_comptabilisees_sont_comptees_et_leur_total_ttc_est_donne_hors_exercice_ignore() {
            Document a = document(LocalDate.of(2026, 3, 1), "118000");
            Document b = document(null, "50000");
            Document horsExercice = document(LocalDate.of(2024, 3, 1), "999");
            when(documents.rechercherNonComptabilises(eq(ENTREPRISE), any(), any())).thenReturn(List.of(a, b, horsExercice));

            ControlItem point = item(evaluer(), "documents_non_comptabilises");

            assertThat(point.statut()).isEqualTo("WARNING");
            assertThat(point.message()).contains("2 facture(s)").contains("168000");
            assertThat(point.relatedEntityIds()).containsExactly(a.getId().toString(), b.getId().toString());
            assertThat(point.metadata()).containsEntry("nbDocuments", 2);
        }

        @Test
        void pieces_manquantes_ouvertes_sont_listees() {
            Finding f = new Finding();
            f.setId(UUID.randomUUID());
            f.setType(TypeFinding.MISSING_EVIDENCE);
            Finding autre = new Finding();
            autre.setId(UUID.randomUUID());
            autre.setType(TypeFinding.ANOMALY);
            when(findings.findByEntrepriseIdAndStatusOrderBySeverityAscCreatedAtDesc(ENTREPRISE, StatutFinding.OPEN))
                    .thenReturn(List.of(f, autre));

            ControlItem point = item(evaluer(), "pieces_manquantes");

            assertThat(point.statut()).isEqualTo("WARNING");
            assertThat(point.relatedEntityIds()).containsExactly(f.getId().toString());
        }
    }

    // =================================================================== banque / tiers

    @Nested
    class TresorerieTiers {
        private final ReleveBancaireRepository releves = mock(ReleveBancaireRepository.class);
        private final LigneEcritureRepository lignes = mock(LigneEcritureRepository.class);
        private final ControleTresorerieTiersCloture controle = new ControleTresorerieTiersCloture(releves, lignes);
        private final ExerciceComptable exercice = exercice();

        private List<ControlItem> evaluer() {
            return controle.evaluer(new ClotureControle.Contexte(ENTREPRISE, exercice));
        }

        private ReleveBancaire releve(String compte, LigneReleve... lignesReleve) {
            ReleveBancaire r = new ReleveBancaire();
            r.setId(UUID.randomUUID());
            r.setCompte(compte(compte, true));
            r.setLibelle("Releve " + compte);
            r.setDateDebut(LocalDate.of(2026, 1, 1));
            r.setDateFin(LocalDate.of(2026, 1, 31));
            r.setSoldeDebut(java.math.BigDecimal.ZERO);
            r.setSoldeFin(java.math.BigDecimal.ZERO);
            for (LigneReleve l : lignesReleve) {
                r.ajouterLigne(l);
            }
            return r;
        }

        private LigneReleve ligne(String montant, boolean pointee) {
            LigneReleve l = new LigneReleve();
            l.setId(UUID.randomUUID());
            l.setMontant(new BigDecimal(montant));
            l.setDateOperation(LocalDate.of(2026, 1, 10));
            l.setLigneEcritureId(pointee ? UUID.randomUUID() : null);
            return l;
        }

        @Test
        void banque_sans_releve_est_NOT_APPLICABLE_et_le_dit() {
            ControlItem point = item(evaluer(), "banque");

            assertThat(point.statut()).isEqualTo("NOT_APPLICABLE");
            assertThat(point.message()).contains("Aucun releve bancaire");
        }

        @Test
        void banque_ok_quand_toutes_les_lignes_sont_pointees() {
            when(releves.findByEntrepriseIdOrderByDateFinDesc(ENTREPRISE))
                    .thenReturn(List.of(releve("521", ligne("100", true), ligne("-50", true))));

            ControlItem point = item(evaluer(), "banque");

            assertThat(point.statut()).isEqualTo("OK");
            assertThat(point.metadata()).containsEntry("nbLignesNonPointees", 0);
        }

        @Test
        void banque_warning_detaille_le_releve_le_compte_le_montant_et_les_lignes_responsables() {
            LigneReleve nonPointee = ligne("124500", false);
            ReleveBancaire a = releve("521", ligne("100", true), nonPointee);
            ReleveBancaire b = releve("522", ligne("10", true));
            ReleveBancaire horsPeriode = releve("523", ligne("1", false));
            horsPeriode.setDateDebut(LocalDate.of(2024, 1, 1));
            horsPeriode.setDateFin(LocalDate.of(2024, 1, 31));
            when(releves.findByEntrepriseIdOrderByDateFinDesc(ENTREPRISE)).thenReturn(List.of(a, b, horsPeriode));

            ControlItem point = item(evaluer(), "banque");

            assertThat(point.statut()).isEqualTo("WARNING");
            assertThat(point.message()).contains("1 ligne(s) de releve non pointee(s) sur 1 releve(s)");
            assertThat(point.explanation()).contains("1 releve(s) sur 2 entierement rapproche(s)")
                    .contains("124500").contains("Compte 521");
            assertThat(point.entityType()).isEqualTo("RELEVE");
            assertThat(point.entityId()).isEqualTo(a.getId().toString());
            assertThat(point.relatedEntityIds()).containsExactly(nonPointee.getId().toString());
            assertThat(point.metadata()).containsEntry("nbReleves", 2).containsEntry("nbLignes", 3).containsEntry("nbLignesNonPointees", 1);
            assertThat(point.metadata().get("montantNonPointe")).isEqualTo(new BigDecimal("124500"));
        }

        private LigneEcriture ligneTiers(Tiers tiers, boolean debit, String montant) {
            CompteComptable compte = compte("40" + UUID.randomUUID().toString().substring(0, 3), true);
            LigneEcriture l = debit ? LigneEcriture.debit(compte, new BigDecimal(montant), "x") : LigneEcriture.credit(compte, new BigDecimal(montant), "x");
            l.setId(UUID.randomUUID());
            l.setTiers(tiers);
            return l;
        }

        private Tiers tiers(TypeTiers type, String nom) {
            Tiers t = new Tiers();
            t.setId(UUID.randomUUID());
            t.setType(type);
            t.setNom(nom);
            return t;
        }

        @Test
        void sans_ligne_en_instance_lettrage_fournisseurs_et_clients_sont_ok() {
            List<ControlItem> items = evaluer();

            assertThat(item(items, "lettrage").statut()).isEqualTo("OK");
            assertThat(item(items, "fournisseurs").statut()).isEqualTo("OK");
            assertThat(item(items, "clients").statut()).isEqualTo("OK");
        }

        @Test
        void lettrage_warning_chiffre_le_nombre_de_lignes_de_tiers_et_les_totaux() {
            Tiers fournisseur = tiers(TypeTiers.FOURNISSEUR, "Quincaillerie");
            when(lignes.rechercherLignesEnInstance(ENTREPRISE, exercice.getDateFin()))
                    .thenReturn(List.of(ligneTiers(fournisseur, false, "118000"), ligneTiers(fournisseur, false, "2000")));

            ControlItem point = item(evaluer(), "lettrage");

            assertThat(point.statut()).isEqualTo("WARNING");
            assertThat(point.message()).contains("2 ligne(s) de tiers non lettree(s)");
            assertThat(point.explanation()).contains("total credit 120000").contains("1 tiers");
            assertThat(point.relatedEntityIds()).hasSize(2);
        }

        @Test
        void fournisseur_debiteur_et_client_crediteur_sont_signales_mais_pas_les_soldes_normaux() {
            Tiers fournisseurNormal = tiers(TypeTiers.FOURNISSEUR, "Normal");
            Tiers fournisseurDebiteur = tiers(TypeTiers.FOURNISSEUR, "Avance SARL");
            Tiers clientNormal = tiers(TypeTiers.CLIENT, "Client OK");
            Tiers clientCrediteur = tiers(TypeTiers.CLIENT, "Acompte SA");
            when(lignes.rechercherLignesEnInstance(ENTREPRISE, exercice.getDateFin())).thenReturn(List.of(
                    ligneTiers(fournisseurNormal, false, "5000"),
                    ligneTiers(fournisseurDebiteur, true, "7000"),
                    ligneTiers(clientNormal, true, "9000"),
                    ligneTiers(clientCrediteur, false, "3000")));

            List<ControlItem> items = evaluer();

            ControlItem f = item(items, "fournisseurs");
            assertThat(f.statut()).isEqualTo("WARNING");
            assertThat(f.message()).contains("Avance SARL (7000)").doesNotContain("Normal");
            assertThat(f.relatedEntityIds()).containsExactly(fournisseurDebiteur.getId().toString());

            ControlItem c = item(items, "clients");
            assertThat(c.statut()).isEqualTo("WARNING");
            assertThat(c.message()).contains("Acompte SA (3000)").doesNotContain("Client OK");
            assertThat(c.relatedEntityIds()).containsExactly(clientCrediteur.getId().toString());
        }
    }

    // =================================================================== actifs / fiscalite

    @Nested
    class ActifsFiscal {
        private final ArticleRepository articles = mock(ArticleRepository.class);
        private final InventaireRepository inventaires = mock(InventaireRepository.class);
        private final ImmobilisationRepository immobilisations = mock(ImmobilisationRepository.class);
        private final DotationAmortissementRepository dotations = mock(DotationAmortissementRepository.class);
        private final DepreciationRepository depreciations = mock(DepreciationRepository.class);
        private final ProvisionRepository provisions = mock(ProvisionRepository.class);
        private final DeclarationFiscaleRepository declarations = mock(DeclarationFiscaleRepository.class);
        private final ControleActifsFiscalCloture controle = new ControleActifsFiscalCloture(articles, inventaires,
                immobilisations, dotations, depreciations, provisions, declarations);
        private final ExerciceComptable exercice = exercice();

        private List<ControlItem> evaluer() {
            return controle.evaluer(new ClotureControle.Contexte(ENTREPRISE, exercice));
        }

        @Test
        void sans_objet_a_controler_tout_reste_NOT_APPLICABLE_jamais_OK() {
            assertThat(evaluer()).extracting(ControlItem::statut).containsOnly("NOT_APPLICABLE");
            assertThat(evaluer()).extracting(ControlItem::code)
                    .containsExactlyInAnyOrder("stocks", "amortissements", "depreciations", "provisions", "fiscalite");
        }

        private Inventaire inventaire(StatutInventaire statut, LocalDate date) {
            Inventaire i = new Inventaire();
            i.setId(UUID.randomUUID());
            i.setStatut(statut);
            i.setDate(date);
            return i;
        }

        @Test
        void stocks_articles_sans_inventaire_valide_donne_warning_puis_ok_apres_validation() {
            when(articles.countByEntrepriseId(ENTREPRISE)).thenReturn(12L);
            assertThat(item(evaluer(), "stocks").statut()).isEqualTo("WARNING");
            assertThat(item(evaluer(), "stocks").message()).contains("Aucun inventaire").contains("12 article(s)");

            Inventaire enCours = inventaire(StatutInventaire.EN_COURS, LocalDate.of(2026, 12, 20));
            when(inventaires.findByEntrepriseIdOrderByDateDesc(ENTREPRISE)).thenReturn(List.of(enCours));
            assertThat(item(evaluer(), "stocks").message()).contains("aucun valide");
            assertThat(item(evaluer(), "stocks").relatedEntityIds()).containsExactly(enCours.getId().toString());

            when(inventaires.findByEntrepriseIdOrderByDateDesc(ENTREPRISE))
                    .thenReturn(List.of(inventaire(StatutInventaire.VALIDE, LocalDate.of(2026, 12, 31))));
            assertThat(item(evaluer(), "stocks").statut()).isEqualTo("OK");
        }

        @Test
        void amortissements_dotation_attendue_non_generee_donne_warning_avec_l_immobilisation() {
            Immobilisation immo = new Immobilisation();
            immo.setId(UUID.randomUUID());
            immo.setLibelle("Camion");
            immo.setValeurOrigine(new BigDecimal("5000000"));
            immo.setDateAcquisition(LocalDate.of(2025, 6, 1));
            immo.setDateMiseEnService(LocalDate.of(2025, 6, 1));
            immo.setDureeAnnees(5);
            immo.setMode(ModeAmortissement.LINEAIRE);
            when(immobilisations.findByEntrepriseIdAndStatut(eq(ENTREPRISE), any())).thenReturn(List.of(immo));

            ControlItem manquante = item(evaluer(), "amortissements");
            assertThat(manquante.statut()).isEqualTo("WARNING");
            assertThat(manquante.explanation()).contains("Camion").contains("2026");
            assertThat(manquante.relatedEntityIds()).containsExactly(immo.getId().toString());

            when(dotations.existsByImmobilisationIdAndAnnee(immo.getId(), 2026)).thenReturn(true);
            assertThat(item(evaluer(), "amortissements").statut()).isEqualTo("OK");
        }

        @Test
        void une_immobilisation_non_mise_en_service_n_est_pas_amortissable_donc_NOT_APPLICABLE() {
            Immobilisation acquise = new Immobilisation();
            acquise.setId(UUID.randomUUID());
            acquise.setLibelle("Camion");
            acquise.setValeurOrigine(new BigDecimal("5000000"));
            acquise.setDateAcquisition(LocalDate.of(2025, 6, 1));
            acquise.setDureeAnnees(5);
            acquise.setMode(ModeAmortissement.LINEAIRE);
            when(immobilisations.findByEntrepriseIdAndStatut(eq(ENTREPRISE), any())).thenReturn(List.of(acquise));

            ControlItem point = item(evaluer(), "amortissements");

            assertThat(point.statut()).isEqualTo("NOT_APPLICABLE");
            assertThat(point.message()).contains("mise en service");
        }

        @Test
        void provisions_et_depreciations_en_attente_donnent_warning_sinon_ok_si_toutes_traitees() {
            ExerciceComptable autre = exercice();
            Provision enAttente = new Provision();
            enAttente.setId(UUID.randomUUID());
            enAttente.setExercice(exercice);
            enAttente.setStatut(StatutProvision.PROPOSEE);
            Provision traitee = new Provision();
            traitee.setId(UUID.randomUUID());
            traitee.setExercice(exercice);
            traitee.setStatut(StatutProvision.VALIDEE);
            Provision autreExercice = new Provision();
            autreExercice.setId(UUID.randomUUID());
            autreExercice.setExercice(autre);
            autreExercice.setStatut(StatutProvision.BROUILLON);
            when(provisions.findByEntrepriseIdOrderByCreatedAtDesc(ENTREPRISE)).thenReturn(List.of(enAttente, traitee, autreExercice));

            ControlItem p = item(evaluer(), "provisions");
            assertThat(p.statut()).isEqualTo("WARNING");
            assertThat(p.relatedEntityIds()).containsExactly(enAttente.getId().toString());
            assertThat(p.metadata()).containsEntry("nbEnAttente", 1).containsEntry("nbProvisions", 2);

            Depreciation traiteeD = new Depreciation();
            traiteeD.setId(UUID.randomUUID());
            traiteeD.setExercice(exercice);
            traiteeD.setStatut(StatutDepreciation.REJETEE);
            when(depreciations.findByEntrepriseIdOrderByCreatedAtDesc(ENTREPRISE)).thenReturn(List.of(traiteeD));
            assertThat(item(evaluer(), "depreciations").statut()).isEqualTo("OK");
        }

        @Test
        void fiscalite_retard_donne_warning_avec_les_echeances_sinon_ok() {
            DeclarationFiscale enRetard = new DeclarationFiscale();
            enRetard.setId(UUID.randomUUID());
            enRetard.setTypeImpot(TypeImpot.TVA);
            enRetard.setPeriode("2026-01");
            enRetard.setDateEcheance(LocalDate.now().minusDays(10));
            enRetard.setStatut(StatutDeclaration.A_FAIRE);
            DeclarationFiscale deposee = new DeclarationFiscale();
            deposee.setId(UUID.randomUUID());
            deposee.setTypeImpot(TypeImpot.TVA);
            deposee.setPeriode("2025-12");
            deposee.setDateEcheance(LocalDate.now().minusDays(40));
            deposee.setStatut(StatutDeclaration.DEPOSEE);
            when(declarations.findByEntrepriseIdAndDateEcheanceBetween(eq(ENTREPRISE), any(), any()))
                    .thenReturn(List.of(enRetard, deposee));

            ControlItem point = item(evaluer(), "fiscalite");
            assertThat(point.statut()).isEqualTo("WARNING");
            assertThat(point.explanation()).contains("TVA 2026-01");
            assertThat(point.relatedEntityIds()).containsExactly(enRetard.getId().toString());

            enRetard.setStatut(StatutDeclaration.PAYEE);
            assertThat(item(evaluer(), "fiscalite").statut()).isEqualTo("OK");
        }
    }
}
