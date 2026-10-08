package com.example.comptable.cabinet.workspace;

import com.example.comptable.cabinet.workspace.CabinetWorkspaceDtos.*;
import com.example.comptable.cabinet.workspace.CabinetWorkspaceService.DossierQuery;
import com.example.comptable.cabinet.workspace.CabinetWorkspaceService.Filtre;
import com.example.comptable.cabinet.workspace.CabinetWorkspaceService.Tri;
import com.example.comptable.collecte.CollecteTestBase;
import com.example.comptable.collecte.service.CollecteService;
import com.example.comptable.comptabilite.repository.ExerciceComptableRepository;
import com.example.comptable.entreprise.web.dto.CreerEntrepriseRequest;
import com.example.comptable.entreprise.web.dto.EntrepriseDto;
import com.example.comptable.identite.domain.Tenant;
import com.example.comptable.identite.domain.Utilisateur;
import com.example.comptable.security.UtilisateurPrincipal;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.server.ResponseStatusException;

import java.time.LocalDate;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Cabinet Workspace sur une vraie base PostgreSQL : statut dérivé de l'état réel (constats, demandes de pièces, notes, snapshot), file « À traiter », portefeuille (filtres, recherche, tri, pagination),
 * affectations et échéances tracées dans l'audit, droits par rôle et isolation entre cabinets.
 */
class CabinetWorkspaceIntegrationTest extends CollecteTestBase {

    @Autowired CabinetWorkspaceService workspace;
    @Autowired CollecteService collecteService;
    @Autowired ExerciceComptableRepository exerciceRepository;

    private long evenementsSur(String action, String entiteId) {
        return auditEventRepository.findAll().stream().filter(e -> action.equals(e.getAction()) && entiteId.equals(e.getEntiteId())).count();
    }

    private UUID tenantDe(Dossier d) {
        return d.expert().getTenantId();
    }

    private Tenant tenant(Dossier d) {
        return tenantRepository.findById(tenantDe(d)).orElseThrow();
    }

    private UtilisateurPrincipal collaborateur(Dossier d) {
        return new UtilisateurPrincipal(utilisateur(tenant(d), "COLLABORATEUR", null));
    }

    private EntrepriseDto autreEntreprise(Dossier d, String nom) {
        return entrepriseService.creer(new CreerEntrepriseRequest(nom, null, null, null, null, null, "CI", "XOF"), d.expert());
    }

    private UUID exercice(UUID entrepriseId) {
        return exerciceRepository.findByEntrepriseIdAndDate(entrepriseId, LocalDate.now()).orElseThrow().getId();
    }

    private DossierSummaryDto dossier(UtilisateurPrincipal p, UUID entrepriseId) {
        return workspace.dossiers(p, new DossierQuery(Filtre.TOUS, null, null, null, null, null, Tri.ENTREPRISE, false, 0, 100)).items().stream()
                .filter(x -> x.entrepriseId().equals(entrepriseId) && x.courant()).findFirst().orElseThrow();
    }

    /** Les constats des états financiers (publiés par la synchronisation proactive) sont hors sujet des scénarios de pièces : on les ferme pour isoler la pièce manquante. */
    private void fermerLesConstatsDesEtats(UUID entrepriseId) {
        jdbc.update("update finding set status = 'RESOLVED', resolved_at = now() where entreprise_id = ? and source_type = 'ETAT_FINANCIER'", entrepriseId);
    }

    private void fermerLesConstats(UUID entrepriseId) {
        jdbc.update("update finding set status = 'RESOLVED', resolved_at = now() where entreprise_id = ?", entrepriseId);
    }

    /** Les lignes de notes existent deja (créées par la synchronisation proactive dès la première écriture) : on les met à l'état voulu (upsert). */
    private void note(UUID entrepriseId, UUID exerciceId, String code, String applicabilite, String completion, boolean revue) {
        jdbc.update("""
                insert into financial_statement_note (id, entreprise_id, exercice_id, framework, definition_version, note_code, applicability_status, completion_status, reviewed_at, created_at, updated_at)
                values (?, ?, ?, 'SYSCOHADA_REVISE_AUDCIF_2017', '2', ?, ?, ?, case when ? then now() else null end, now(), now())
                on conflict (entreprise_id, exercice_id, framework, definition_version, note_code)
                do update set applicability_status = excluded.applicability_status, completion_status = excluded.completion_status, reviewed_at = excluded.reviewed_at
                """, UUID.randomUUID(), entrepriseId, exerciceId, code, applicabilite, completion, revue);
    }

    /** Toutes les autres notes sont décidées (non applicables) : seul le jeu de notes du scénario reste à préparer ou à revoir. */
    private void toutesLesAutresNotesDecidees(UUID entrepriseId, Set<String> sauf) {
        jdbc.update("update financial_statement_note set applicability_status = 'NOT_APPLICABLE', completion_status = 'NOT_APPLICABLE', reviewed_at = null where entreprise_id = ? and note_code <> all (?)",
                entrepriseId, sauf.toArray(new String[0]));
    }

    // -------------------------------------------------------------------------------------------------------------------------------------------------- statut

    @Test
    void dossierSansActivite_aDemarrer_puisPieceManquante_attentionRequise_attenteClient_etRecalcule() {
        Dossier d = nouveauDossier(true);
        EntrepriseDto vierge = autreEntreprise(d, "Vierge " + UUID.randomUUID().toString().substring(0, 5));
        DossierSummaryDto neuf = dossier(d.expert(), vierge.id());
        assertThat(neuf.statut()).isEqualTo("A_DEMARRER");
        assertThat(neuf.etapesPretes()).isZero();

        acheterSansPiece(d, 5000);
        List<UUID> constats = piecesManquantes(d).stream().map(f -> f.getId()).toList();
        assertThat(constats).hasSize(1);
        fermerLesConstatsDesEtats(d.id());
        DossierSummaryDto avant = dossier(d.expert(), d.id());
        assertThat(avant.statut()).isIn("ATTENTION_REQUISE", "EN_COURS");                        // le constat est à traiter par le cabinet
        assertThat(avant.findingsActifs()).isEqualTo(1);

        // la pièce est demandée au client : le cabinet n'a plus rien à faire seul -> EN_ATTENTE_CLIENT
        var resultat = collecteService.demander(d.id(), constats, d.expert());
        DossierSummaryDto attente = dossier(d.expert(), d.id());
        assertThat(attente.statut()).isEqualTo("EN_ATTENTE_CLIENT");
        assertThat(attente.piecesManquantes()).isEqualTo(1);
        assertThat(attente.findingsActifs()).isZero();                                          // le constat attend le client : il n'est plus une action du cabinet

        // la pièce arrive, la demande est résolue : le statut est recalculé
        collecteService.marquerRecue(d.id(), resultat.demandeIds().get(0), "recue hors systeme", d.expert());
        DossierSummaryDto apres = dossier(d.expert(), d.id());
        assertThat(apres.statut()).isNotEqualTo("EN_ATTENTE_CLIENT");
        assertThat(apres.piecesManquantes()).isZero();
    }

    @Test
    void preparationTerminee_pretPourRevue_enRevue_pretACloturer_cloture_finalise() {
        Dossier d = nouveauDossier(false);
        acheterSansPiece(d, 1000);
        fermerLesConstats(d.id());
        UUID ex = exercice(d.id());
        note(d.id(), ex, "1", "APPLICABLE", "READY", false);
        note(d.id(), ex, "2", "APPLICABLE", "READY", false);
        toutesLesAutresNotesDecidees(d.id(), Set.of("1", "2"));

        DossierSummaryDto pret = dossier(d.expert(), d.id());
        assertThat(pret.statut()).isEqualTo("PRET_POUR_REVUE");
        assertThat(pret.etapesPretes()).isEqualTo(6);
        assertThat(pret.etapes()).filteredOn(e -> !e.prete()).extracting(EtapeDto::code).containsExactly("FINALISATION");

        jdbc.update("update financial_statement_note set reviewed_at = now() where entreprise_id = ? and note_code = '1'", d.id());
        assertThat(dossier(d.expert(), d.id()).statut()).isEqualTo("EN_REVUE");
        jdbc.update("update financial_statement_note set reviewed_at = now() where entreprise_id = ? and note_code = '2'", d.id());
        assertThat(dossier(d.expert(), d.id()).statut()).isEqualTo("PRET_A_CLOTURER");

        jdbc.update("update exercice_comptable set statut = 'CLOTURE' where id = ?", ex);
        assertThat(dossier(d.expert(), d.id()).statut()).isEqualTo("CLOTURE");

        jdbc.update("""
                insert into financial_statement_snapshot (id, entreprise_id, exercice_id, statement_type, framework, definition_version, definition_hash, status, dataset_fingerprint, payload, payload_sha256, created_at)
                values (?, ?, ?, 'DOSSIER_COMPLET', 'SYSCOHADA_REVISE_AUDCIF_2017', '2', 'h', 'FINAL', 'f', '{}', 's', now())
                """, UUID.randomUUID(), d.id(), ex);
        assertThat(dossier(d.expert(), d.id()).statut()).isEqualTo("FINALISE");
    }

    // -------------------------------------------------------------------------------------------------------------------------------------------------- work queue / dashboard

    @Test
    void workQueue_listeLesConstatsAvecEntrepriseDomaineImpactAnciennete_etExclutLesAttentesClient() {
        Dossier d = nouveauDossier(true);
        acheterSansPiece(d, 7000);
        acheterSansPiece(d, 7100);
        List<UUID> constats = piecesManquantes(d).stream().map(f -> f.getId()).toList();
        collecteService.demander(d.id(), List.of(constats.get(0)), d.expert());                // un constat part au client, l'autre reste au cabinet

        PageDto<WorkItemDto> file = workspace.workQueue(d.expert(), false, 0, 100);
        List<WorkItemDto> miens = file.items().stream().filter(i -> i.entrepriseId().equals(d.id()) && i.domaine().equals("Documents")).toList();
        assertThat(miens).hasSize(1);
        WorkItemDto item = miens.get(0);
        assertThat(item.entrepriseNom()).isEqualTo(d.entreprise().raisonSociale());
        assertThat(item.domaine()).isEqualTo("Documents");
        assertThat(item.impact()).isNotBlank();
        assertThat(item.kind()).isEqualTo("FINDING");
        assertThat(item.cible()).isEqualTo("REVISION");
        assertThat(item.ancienneteJours()).isGreaterThanOrEqualTo(0);
        assertThat(file.total()).isGreaterThanOrEqualTo(1);
    }

    @Test
    void dashboard_compteursEtFileDuCollaborateur_mesDossiersOuCabinet() {
        Dossier d = nouveauDossier(true);
        UtilisateurPrincipal collab = collaborateur(d);
        acheterSansPiece(d, 3000);
        piecesManquantes(d);

        DashboardDto cabinet = workspace.dashboard(collab, false);
        assertThat(cabinet.perimetre()).isEqualTo("CABINET");
        assertThat(cabinet.summary().dossiersActifs()).isGreaterThanOrEqualTo(1);
        assertThat(cabinet.summary().aTraiter()).isGreaterThanOrEqualTo(1);
        assertThat(cabinet.workQueue().items()).isNotEmpty();
        assertThat(cabinet.reviewQueue()).isEmpty();                                            // la file « À revoir » est celle de l'expert

        DashboardDto mes = workspace.dashboard(collab, true);
        assertThat(mes.perimetre()).isEqualTo("MES_DOSSIERS");
        assertThat(mes.summary().dossiersActifs()).isZero();                                    // rien d'affecté à ce collaborateur

        workspace.affecter(d.expert(), d.id(), exercice(d.id()), new AffectationRequest(collab.getId(), d.expert().getId()));
        DashboardDto apres = workspace.dashboard(collab, true);
        assertThat(apres.summary().dossiersActifs()).isEqualTo(1);
        assertThat(apres.workQueue().items()).allMatch(i -> i.entrepriseId().equals(d.id()));
        assertThat(apres.workQueue().items()).isNotEmpty();
    }

    @Test
    void fileDeRevue_reservee_a_lExpert_etSortLeDossierFinalise() {
        Dossier d = nouveauDossier(false);
        acheterSansPiece(d, 1000);
        fermerLesConstats(d.id());
        UUID ex = exercice(d.id());
        note(d.id(), ex, "1", "APPLICABLE", "READY", false);
        toutesLesAutresNotesDecidees(d.id(), Set.of("1"));

        DashboardDto expert = workspace.dashboard(d.expert(), false);
        assertThat(expert.reviewQueue()).extracting(DossierSummaryDto::entrepriseId).contains(d.id());
        assertThat(expert.summary().pretsPourRevue()).isGreaterThanOrEqualTo(1);
        assertThat(workspace.dashboard(collaborateur(d), false).reviewQueue()).isEmpty();

        jdbc.update("update exercice_comptable set statut = 'CLOTURE' where id = ?", ex);
        jdbc.update("""
                insert into financial_statement_snapshot (id, entreprise_id, exercice_id, statement_type, framework, definition_version, definition_hash, status, dataset_fingerprint, payload, payload_sha256, created_at)
                values (?, ?, ?, 'DOSSIER_COMPLET', 'F', '2', 'h', 'FINAL', 'f', '{}', 's', now())
                """, UUID.randomUUID(), d.id(), ex);
        assertThat(workspace.dashboard(d.expert(), false).reviewQueue()).extracting(DossierSummaryDto::entrepriseId).doesNotContain(d.id());
    }

    // -------------------------------------------------------------------------------------------------------------------------------------------------- portefeuille

    @Test
    void portefeuille_filtres_recherche_tri_pagination() {
        Dossier d = nouveauDossier(false);
        String marque = "Zeta" + UUID.randomUUID().toString().substring(0, 6);
        for (int i = 0; i < 7; i++) {
            autreEntreprise(d, marque + " " + (char) ('A' + i));
        }
        PageDto<DossierSummaryDto> page1 = workspace.dossiers(d.expert(), new DossierQuery(Filtre.TOUS, marque, null, null, null, null, Tri.ENTREPRISE, false, 0, 3));
        assertThat(page1.total()).isGreaterThanOrEqualTo(7);
        assertThat(page1.items()).hasSize(3);
        assertThat(page1.items()).extracting(DossierSummaryDto::entrepriseNom).isSorted();
        PageDto<DossierSummaryDto> page3 = workspace.dossiers(d.expert(), new DossierQuery(Filtre.TOUS, marque, null, null, null, null, Tri.ENTREPRISE, false, 2, 3));
        assertThat(page3.items()).hasSize((int) (page1.total() - 6));
        assertThat(workspace.dossiers(d.expert(), new DossierQuery(Filtre.TOUS, marque + " C", null, null, null, null, Tri.ENTREPRISE, false, 0, 10)).items()).hasSize(1);
        assertThat(workspace.dossiers(d.expert(), new DossierQuery(Filtre.TOUS, "introuvable-" + UUID.randomUUID(), null, null, null, null, Tri.ENTREPRISE, false, 0, 10)).total()).isZero();

        // tri décroissant, taille plafonnée à 100, filtres de statut
        List<String> desc = workspace.dossiers(d.expert(), new DossierQuery(Filtre.TOUS, marque, null, null, null, null, Tri.ENTREPRISE, true, 0, 50)).items().stream().map(DossierSummaryDto::entrepriseNom).toList();
        assertThat(desc).isSortedAccordingTo(java.util.Comparator.reverseOrder());
        assertThat(workspace.dossiers(d.expert(), new DossierQuery(Filtre.TOUS, marque, null, null, null, null, Tri.ENTREPRISE, false, 0, 100000)).taille()).isEqualTo(100);
        assertThat(workspace.dossiers(d.expert(), new DossierQuery(Filtre.A_TRAITER, marque, null, null, null, null, Tri.PRIORITE, false, 0, 50)).total()).isZero();   // dossiers vierges : À démarrer, pas encore à traiter
        assertThat(workspace.dossiers(d.expert(), new DossierQuery(Filtre.TOUS, marque, null, null, null, StatutOperationnel.A_DEMARRER, Tri.PRIORITE, false, 0, 50)).total()).isEqualTo(page1.total());
        assertThat(workspace.dossiers(d.expert(), new DossierQuery(Filtre.FINALISES, marque, null, null, null, null, Tri.PRIORITE, false, 0, 50)).total()).isZero();
    }

    @Test
    void portefeuille_mesDossiers_responsableEtRechercheParResponsable() {
        Dossier d = nouveauDossier(false);
        UtilisateurPrincipal collab = collaborateur(d);
        EntrepriseDto a = autreEntreprise(d, "Affectee " + UUID.randomUUID().toString().substring(0, 5));
        autreEntreprise(d, "Libre " + UUID.randomUUID().toString().substring(0, 5));
        workspace.affecter(d.expert(), a.id(), exercice(a.id()), new AffectationRequest(collab.getId(), null));

        assertThat(workspace.dossiers(collab, new DossierQuery(Filtre.MES_DOSSIERS, null, null, null, null, null, Tri.ENTREPRISE, false, 0, 50)).items())
                .extracting(DossierSummaryDto::entrepriseId).containsExactly(a.id());
        assertThat(workspace.dossiers(d.expert(), new DossierQuery(Filtre.TOUS, null, collab.getId(), null, null, null, Tri.ENTREPRISE, false, 0, 50)).items())
                .extracting(DossierSummaryDto::entrepriseId).containsExactly(a.id());
        Utilisateur u = utilisateurRepository.findById(collab.getId()).orElseThrow();
        String nomResponsable = (u.getNom() + " " + u.getPrenom()).trim();
        assertThat(workspace.dossiers(d.expert(), new DossierQuery(Filtre.TOUS, nomResponsable.substring(0, 4), null, null, null, null, Tri.ENTREPRISE, false, 0, 50)).items())
                .extracting(DossierSummaryDto::entrepriseId).contains(a.id());
        assertThat(workspace.dossiers(d.expert(), new DossierQuery(Filtre.TOUS, null, null, collab.getId(), null, null, Tri.ENTREPRISE, false, 0, 50)).total()).isZero();
    }

    // -------------------------------------------------------------------------------------------------------------------------------------------------- affectation + audit

    @Test
    void affectation_tracee_enMasse_etRefusUnCollaborateur() {
        Dossier d = nouveauDossier(false);
        UtilisateurPrincipal collab = collaborateur(d);
        UtilisateurPrincipal autre = collaborateur(d);
        EntrepriseDto a = autreEntreprise(d, "Masse A " + UUID.randomUUID().toString().substring(0, 5));
        EntrepriseDto b = autreEntreprise(d, "Masse B " + UUID.randomUUID().toString().substring(0, 5));
        UUID exA = exercice(a.id());

        DossierSummaryDto resume = workspace.affecter(d.expert(), a.id(), exA, new AffectationRequest(collab.getId(), d.expert().getId()));
        assertThat(resume.collaborateur().id()).isEqualTo(collab.getId());
        assertThat(resume.expert().id()).isEqualTo(d.expert().getId());
        assertThat(evenementsSur("DOSSIER_AFFECTE", exA.toString())).isEqualTo(1);

        workspace.affecter(d.expert(), a.id(), exA, new AffectationRequest(autre.getId(), d.expert().getId()));
        assertThat(evenementsSur("RESPONSABLE_DOSSIER_MODIFIE", exA.toString())).isEqualTo(1);
        workspace.affecter(d.expert(), a.id(), exA, new AffectationRequest(autre.getId(), d.expert().getId()));      // inchangé : aucun événement de plus
        assertThat(evenementsSur("RESPONSABLE_DOSSIER_MODIFIE", exA.toString())).isEqualTo(1);

        int n = workspace.affecterEnMasse(d.expert(), new AffectationEnMasseRequest(List.of(new DossierRef(a.id(), exA), new DossierRef(b.id(), exercice(b.id()))), collab.getId(), null));
        assertThat(n).isEqualTo(2);
        assertThat(dossier(d.expert(), a.id()).expert().id()).isEqualTo(d.expert().getId());                           // l'expert est conservé par l'affectation en masse
        assertThat(dossier(d.expert(), b.id()).collaborateur().id()).isEqualTo(collab.getId());

        assertThatThrownBy(() -> workspace.affecter(collab, a.id(), exA, new AffectationRequest(collab.getId(), null)))
                .isInstanceOf(ResponseStatusException.class).hasMessageContaining("expert-comptable");
        assertThatThrownBy(() -> workspace.affecter(d.expert(), a.id(), exA, new AffectationRequest(null, collab.getId())))   // un collaborateur n'est pas expert
                .isInstanceOf(ResponseStatusException.class).hasMessageContaining("expert-comptable");
    }

    // -------------------------------------------------------------------------------------------------------------------------------------------------- échéances

    @Test
    void echeances_periodes_creation_modification_audit_et_priorite() {
        Dossier d = nouveauDossier(false);
        UtilisateurPrincipal collab = collaborateur(d);
        LocalDate hui = LocalDate.now();
        creer(d, collab, "Cloture annuelle", hui.minusDays(2));
        creer(d, collab, "Aujourd'hui", hui);
        creer(d, collab, "Semaine", hui.plusDays(4));
        creer(d, collab, "Plus tard", hui.plusDays(30));

        EcheancesDto toutes = workspace.echeances(collab, null, false);
        assertThat(toutes.enRetard()).isEqualTo(1);
        assertThat(toutes.aujourdhui()).isEqualTo(1);
        assertThat(toutes.cetteSemaine()).isEqualTo(1);
        assertThat(toutes.aVenir()).isEqualTo(1);
        assertThat(toutes.items()).extracting(EcheanceDto::date).isSorted();
        assertThat(workspace.echeances(collab, "EN_RETARD", false).items()).extracting(EcheanceDto::libelle).containsExactly("Cloture annuelle");

        EcheanceDto retard = toutes.items().get(0);
        EcheanceDto faite = workspace.modifierEcheance(d.expert(), retard.id(), new EcheancePatch(null, "FAITE", null, null));
        assertThat(faite.statut()).isEqualTo("FAITE");
        assertThat(workspace.echeances(collab, null, false).enRetard()).isZero();
        assertThat(evenementsPour("ECHEANCE_DOSSIER_MODIFIEE", retard.id())).isEqualTo(1);
        assertThat(evenementsPour("ECHEANCE_DOSSIER_CREEE", retard.id())).isEqualTo(1);

        assertThatThrownBy(() -> workspace.creerEcheance(collab, new EcheanceRequest(d.id(), null, "TYPE_INCONNU", "x", hui, null))).isInstanceOf(ResponseStatusException.class);
        // une échéance proche fait monter la priorité du dossier
        assertThat(dossier(d.expert(), d.id()).prochaineEcheance()).isNotNull();
    }

    private EcheanceDto creer(Dossier d, UtilisateurPrincipal auteur, String libelle, LocalDate date) {
        return workspace.creerEcheance(auteur, new EcheanceRequest(d.id(), null, "ECHEANCE_INTERNE", libelle, date, auteur.getId()));
    }

    // -------------------------------------------------------------------------------------------------------------------------------------------------- permissions + tenant

    @Test
    void client_aucunAcces_etAutreCabinet_404() {
        Dossier d = nouveauDossier(true);
        UtilisateurPrincipal client = new UtilisateurPrincipal(utilisateur(tenant(d), "CLIENT", null));
        assertThatThrownBy(() -> workspace.dashboard(client, false)).isInstanceOf(ResponseStatusException.class).hasMessageContaining("réservé");
        assertThatThrownBy(() -> workspace.dossiers(client, new DossierQuery(null, null, null, null, null, null, null, false, 0, 10))).isInstanceOf(ResponseStatusException.class);
        assertThatThrownBy(() -> workspace.workQueue(client, false, 0, 10)).isInstanceOf(ResponseStatusException.class);
        assertThatThrownBy(() -> workspace.echeances(client, null, false)).isInstanceOf(ResponseStatusException.class);

        Dossier autreCabinet = nouveauDossier(false);
        UUID exercice = exercice(d.id());
        assertThatThrownBy(() -> workspace.affecter(autreCabinet.expert(), d.id(), exercice, new AffectationRequest(null, null)))
                .isInstanceOf(ResponseStatusException.class).hasMessageContaining("introuvable");
        assertThatThrownBy(() -> workspace.creerEcheance(autreCabinet.expert(), new EcheanceRequest(d.id(), null, "ECHEANCE_INTERNE", "x", LocalDate.now(), null)))
                .isInstanceOf(ResponseStatusException.class).hasMessageContaining("introuvable");
        // un responsable d'un autre cabinet est refusé
        EntrepriseDto mienne = autreEntreprise(autreCabinet, "Mienne " + UUID.randomUUID().toString().substring(0, 5));
        UtilisateurPrincipal etranger = collaborateur(d);
        assertThatThrownBy(() -> workspace.affecter(autreCabinet.expert(), mienne.id(), exercice(mienne.id()), new AffectationRequest(etranger.getId(), null)))
                .isInstanceOf(ResponseStatusException.class);

        // le portefeuille d'un cabinet ne contient jamais les dossiers d'un autre
        assertThat(workspace.dossiers(autreCabinet.expert(), new DossierQuery(Filtre.TOUS, null, null, null, null, null, Tri.ENTREPRISE, false, 0, 100)).items())
                .extracting(DossierSummaryDto::entrepriseId).doesNotContain(d.id());
        assertThat(workspace.workQueue(autreCabinet.expert(), false, 0, 100).items()).extracting(WorkItemDto::entrepriseId).doesNotContain(d.id());
    }
}
