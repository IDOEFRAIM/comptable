package com.example.comptable.cabinet.workspace;

import com.example.comptable.cabinet.workspace.CabinetEquipeService.MembreCreeDto;
import com.example.comptable.cabinet.workspace.CabinetEquipeService.MembreDto;
import com.example.comptable.cabinet.workspace.CabinetEquipeService.MembrePatch;
import com.example.comptable.cabinet.workspace.CabinetEquipeService.MembreRequest;
import com.example.comptable.cabinet.workspace.CabinetWorkspaceDtos.AffectationRequest;
import com.example.comptable.collecte.CollecteTestBase;
import com.example.comptable.comptabilite.repository.ExerciceComptableRepository;
import com.example.comptable.identite.domain.Tenant;
import com.example.comptable.security.UtilisateurPrincipal;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.server.ResponseStatusException;

import java.time.LocalDate;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Équipe du cabinet : ajout, validation, rôles, désactivation, droits, isolation entre cabinets et audit. */
class CabinetEquipeIntegrationTest extends CollecteTestBase {

    @Autowired CabinetEquipeService equipe;
    @Autowired CabinetWorkspaceService workspace;
    @Autowired ExerciceComptableRepository exerciceRepository;

    private Tenant tenant(Dossier d) {
        return tenantRepository.findById(d.expert().getTenantId()).orElseThrow();
    }

    private String email() {
        return "membre-" + UUID.randomUUID() + "@test.ci";
    }

    private long evenementsSur(String action, UUID id) {
        return auditEventRepository.findAll().stream().filter(e -> action.equals(e.getAction()) && id.toString().equals(e.getEntiteId())).count();
    }

    private MembreCreeDto ajouter(Dossier d, String role) {
        return equipe.ajouter(d.expert(), new MembreRequest(email(), "Kone", "Fatou", role, null));
    }

    @Test
    void ajout_motDePasseTemporaireUneSeuleFois_membreVisibleEtTrace() {
        Dossier d = nouveauDossier(false);
        String mail = email();
        MembreCreeDto cree = equipe.ajouter(d.expert(), new MembreRequest(mail.toUpperCase(), " Kone ", " Fatou ", "COLLABORATEUR", null));

        assertThat(cree.motDePasseTemporaire()).hasSize(12);
        assertThat(cree.membre().email()).isEqualTo(mail);                                       // normalisé en minuscules
        assertThat(cree.membre().role()).isEqualTo("COLLABORATEUR");
        assertThat(cree.membre().statut()).isEqualTo("ACTIF");
        assertThat(cree.membre().nom()).isEqualTo("Kone");
        assertThat(utilisateurRepository.findByEmail(mail).orElseThrow().getMotDePasse()).isNotEqualTo(cree.motDePasseTemporaire());   // jamais en clair
        assertThat(equipe.equipe(d.expert())).extracting(MembreDto::id).contains(cree.membre().id());
        assertThat(evenementsSur("MEMBRE_CABINET_AJOUTE", cree.membre().id())).isEqualTo(1);

        MembreCreeDto avecMotDePasse = equipe.ajouter(d.expert(), new MembreRequest(email(), "Diallo", "Moussa", "EXPERT_COMPTABLE", "motdepasse-choisi"));
        assertThat(avecMotDePasse.motDePasseTemporaire()).isNull();                              // le mot de passe choisi n'est jamais renvoyé
        assertThat(avecMotDePasse.membre().role()).isEqualTo("EXPERT_COMPTABLE");
    }

    @Test
    void validation_emailInvalide_doublon_roleInconnu_champsObligatoires_etMotDePasseCourt() {
        Dossier d = nouveauDossier(false);
        String mail = email();
        equipe.ajouter(d.expert(), new MembreRequest(mail, "A", "B", "COLLABORATEUR", null));

        assertThatThrownBy(() -> equipe.ajouter(d.expert(), new MembreRequest(mail, "A", "B", "COLLABORATEUR", null))).isInstanceOf(ResponseStatusException.class).hasMessageContaining("déjà utilisée");
        assertThatThrownBy(() -> equipe.ajouter(d.expert(), new MembreRequest(mail.toUpperCase(), "A", "B", "COLLABORATEUR", null))).hasMessageContaining("déjà utilisée");   // même adresse, autre casse
        assertThatThrownBy(() -> equipe.ajouter(d.expert(), new MembreRequest("pas-un-mail", "A", "B", "COLLABORATEUR", null))).hasMessageContaining("e-mail invalide");
        assertThatThrownBy(() -> equipe.ajouter(d.expert(), new MembreRequest(email(), "A", "B", "CLIENT", null))).hasMessageContaining("Rôle inconnu");
        assertThatThrownBy(() -> equipe.ajouter(d.expert(), new MembreRequest(email(), "A", "B", "SUPER_ADMIN", null))).hasMessageContaining("Rôle inconnu");
        assertThatThrownBy(() -> equipe.ajouter(d.expert(), new MembreRequest(email(), " ", "B", "COLLABORATEUR", null))).hasMessageContaining("obligatoires");
        assertThatThrownBy(() -> equipe.ajouter(d.expert(), new MembreRequest(email(), "A", "B", "COLLABORATEUR", "court"))).hasMessageContaining("8 caractères");
    }

    @Test
    void permissions_lectureAuxMembres_mutationsALExpertSeul_clientRefuse() {
        Dossier d = nouveauDossier(false);
        UtilisateurPrincipal collab = new UtilisateurPrincipal(utilisateur(tenant(d), "COLLABORATEUR", null));
        UtilisateurPrincipal client = new UtilisateurPrincipal(utilisateur(tenant(d), "CLIENT", null));
        MembreCreeDto cible = ajouter(d, "COLLABORATEUR");

        assertThat(equipe.equipe(collab)).extracting(MembreDto::id).contains(cible.membre().id());       // lecture ouverte au cabinet
        assertThatThrownBy(() -> equipe.ajouter(collab, new MembreRequest(email(), "A", "B", "COLLABORATEUR", null))).isInstanceOf(ResponseStatusException.class).hasMessageContaining("expert-comptable");
        assertThatThrownBy(() -> equipe.modifier(collab, cible.membre().id(), new MembrePatch("EXPERT_COMPTABLE", null))).hasMessageContaining("expert-comptable");
        assertThatThrownBy(() -> equipe.modifier(collab, cible.membre().id(), new MembrePatch(null, "INACTIF"))).hasMessageContaining("expert-comptable");
        assertThatThrownBy(() -> equipe.equipe(client)).isInstanceOf(ResponseStatusException.class).hasMessageContaining("réservée");
        assertThatThrownBy(() -> equipe.ajouter(client, new MembreRequest(email(), "A", "B", "COLLABORATEUR", null))).isInstanceOf(ResponseStatusException.class);
    }

    @Test
    void role_modifieEtTrace_gardeFous_soiMemeEtDernierExpert() {
        Dossier d = nouveauDossier(false);
        MembreCreeDto cible = ajouter(d, "COLLABORATEUR");

        MembreDto promu = equipe.modifier(d.expert(), cible.membre().id(), new MembrePatch("EXPERT_COMPTABLE", null));
        assertThat(promu.role()).isEqualTo("EXPERT_COMPTABLE");
        assertThat(evenementsSur("ROLE_MEMBRE_MODIFIE", cible.membre().id())).isEqualTo(1);
        equipe.modifier(d.expert(), cible.membre().id(), new MembrePatch("EXPERT_COMPTABLE", null));        // inchangé : aucun événement de plus
        assertThat(evenementsSur("ROLE_MEMBRE_MODIFIE", cible.membre().id())).isEqualTo(1);
        assertThatThrownBy(() -> equipe.modifier(d.expert(), cible.membre().id(), new MembrePatch("CLIENT", null))).hasMessageContaining("Rôle inconnu");

        assertThatThrownBy(() -> equipe.modifier(d.expert(), d.expert().getId(), new MembrePatch("COLLABORATEUR", null))).hasMessageContaining("propre rôle");
        assertThatThrownBy(() -> equipe.modifier(d.expert(), d.expert().getId(), new MembrePatch(null, "INACTIF"))).hasMessageContaining("propre compte");
        // le dernier expert actif ne peut être ni rétrogradé ni désactivé : on désactive d'abord le nouvel expert, puis l'autre expert tente de se retirer via lui
        equipe.modifier(d.expert(), cible.membre().id(), new MembrePatch(null, "INACTIF"));
        UtilisateurPrincipal autreExpert = d.expert();
        assertThatThrownBy(() -> equipe.modifier(autreExpert, d.expert().getId(), new MembrePatch("COLLABORATEUR", null))).hasMessageContaining("propre rôle");
    }

    @Test
    void desactivation_traceeReversible_retireDesSelecteursEtRefuseLaConnexion() {
        Dossier d = nouveauDossier(false);
        MembreCreeDto cible = ajouter(d, "COLLABORATEUR");
        UUID exercice = exerciceRepository.findByEntrepriseIdAndDate(d.id(), LocalDate.now()).orElseThrow().getId();
        assertThat(workspace.membres(d.expert())).extracting(CabinetWorkspaceDtos.UtilisateurRef::id).contains(cible.membre().id());

        MembreDto inactif = equipe.modifier(d.expert(), cible.membre().id(), new MembrePatch(null, "INACTIF"));
        assertThat(inactif.statut()).isEqualTo("INACTIF");
        assertThat(evenementsSur("MEMBRE_CABINET_DESACTIVE", cible.membre().id())).isEqualTo(1);
        assertThat(new UtilisateurPrincipal(utilisateurRepository.findById(cible.membre().id()).orElseThrow()).isEnabled()).isFalse();      // connexion refusée
        assertThat(workspace.membres(d.expert())).extracting(CabinetWorkspaceDtos.UtilisateurRef::id).doesNotContain(cible.membre().id());  // plus proposé à l'affectation
        assertThat(equipe.equipe(d.expert())).extracting(MembreDto::id).contains(cible.membre().id());                                       // mais toujours visible dans l'équipe
        assertThatThrownBy(() -> workspace.affecter(d.expert(), d.id(), exercice, new AffectationRequest(cible.membre().id(), null))).hasMessageContaining("désactivé");

        MembreDto reactive = equipe.modifier(d.expert(), cible.membre().id(), new MembrePatch(null, "ACTIF"));
        assertThat(reactive.statut()).isEqualTo("ACTIF");
        assertThat(evenementsSur("MEMBRE_CABINET_REACTIVE", cible.membre().id())).isEqualTo(1);
        assertThatThrownBy(() -> equipe.modifier(d.expert(), cible.membre().id(), new MembrePatch(null, "SUSPENDU"))).hasMessageContaining("Statut inconnu");
    }

    @Test
    void nouveauMembre_immediatementAffectable_etDossiersAffectesComptes() {
        Dossier d = nouveauDossier(false);
        MembreCreeDto cree = ajouter(d, "COLLABORATEUR");
        UUID exercice = exerciceRepository.findByEntrepriseIdAndDate(d.id(), LocalDate.now()).orElseThrow().getId();

        assertThat(workspace.membres(d.expert())).extracting(CabinetWorkspaceDtos.UtilisateurRef::id).contains(cree.membre().id());   // sélecteur d'affectation
        assertThat(equipe.equipe(d.expert()).stream().filter(m -> m.id().equals(cree.membre().id())).findFirst().orElseThrow().dossiersAffectes()).isZero();

        workspace.affecter(d.expert(), d.id(), exercice, new AffectationRequest(cree.membre().id(), null));
        assertThat(equipe.equipe(d.expert()).stream().filter(m -> m.id().equals(cree.membre().id())).findFirst().orElseThrow().dossiersAffectes()).isEqualTo(1);
        var portefeuille = workspace.dossiers(d.expert(), new CabinetWorkspaceService.DossierQuery(CabinetWorkspaceService.Filtre.TOUS, null, cree.membre().id(), null, null, null,
                CabinetWorkspaceService.Tri.ENTREPRISE, false, 0, 25));
        assertThat(portefeuille.items()).extracting(CabinetWorkspaceDtos.DossierSummaryDto::entrepriseId).containsExactly(d.id());   // filtre « collaborateur »
        // l'expert a une action tracée : sa dernière activité est renseignée
        assertThat(equipe.equipe(d.expert()).stream().filter(m -> m.id().equals(d.expert().getId())).findFirst().orElseThrow().derniereActivite()).isNotNull();
    }

    @Test
    void autreCabinet_neVoitNeModifieNiAjouteLesMembresDuPremier() {
        Dossier d = nouveauDossier(false);
        Dossier autre = nouveauDossier(false);
        MembreCreeDto cible = ajouter(d, "COLLABORATEUR");

        assertThat(equipe.equipe(autre.expert())).extracting(MembreDto::id).doesNotContain(cible.membre().id());
        assertThat(equipe.equipe(d.expert())).extracting(MembreDto::id).doesNotContain(autre.expert().getId());
        assertThatThrownBy(() -> equipe.modifier(autre.expert(), cible.membre().id(), new MembrePatch("EXPERT_COMPTABLE", null))).isInstanceOf(ResponseStatusException.class).hasMessageContaining("introuvable");
        assertThatThrownBy(() -> equipe.modifier(autre.expert(), cible.membre().id(), new MembrePatch(null, "INACTIF"))).hasMessageContaining("introuvable");
        // un membre ajouté par l'autre cabinet appartient à l'autre cabinet
        MembreCreeDto etranger = ajouter(autre, "COLLABORATEUR");
        assertThat(utilisateurRepository.findById(etranger.membre().id()).orElseThrow().getTenant().getId()).isEqualTo(autre.expert().getTenantId());
        assertThat(equipe.equipe(d.expert())).extracting(MembreDto::id).doesNotContain(etranger.membre().id());
    }
}
