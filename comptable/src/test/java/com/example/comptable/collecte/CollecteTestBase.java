package com.example.comptable.collecte;

import com.example.comptable.agent.finding.domain.Finding;
import com.example.comptable.agent.finding.repository.FindingRepository;
import com.example.comptable.audit.domain.AuditEvent;
import com.example.comptable.audit.repository.AuditEventRepository;
import com.example.comptable.collecte.delivery.WhatsAppDeliveryAdapter;
import com.example.comptable.collecte.domain.CanalMessage;
import com.example.comptable.comptabilite.domain.TypeTiers;
import com.example.comptable.comptabilite.operation.achat.AchatMultiLigneOperationRequest;
import com.example.comptable.comptabilite.operation.achat.LigneAchatOperationRequest;
import com.example.comptable.comptabilite.operation.achat.NatureAchat;
import com.example.comptable.comptabilite.operation.common.AccountingOperationEngine;
import com.example.comptable.comptabilite.operation.common.AccountingSource;
import com.example.comptable.comptabilite.repository.CompteComptableRepository;
import com.example.comptable.comptabilite.service.TiersService;
import com.example.comptable.comptabilite.web.dto.TiersDto;
import com.example.comptable.controle.ControlCodes;
import com.example.comptable.controle.ControlRunService;
import com.example.comptable.entreprise.domain.UtilisateurEntreprise;
import com.example.comptable.entreprise.repository.EntrepriseRepository;
import com.example.comptable.entreprise.repository.UtilisateurEntrepriseRepository;
import com.example.comptable.entreprise.service.EntrepriseService;
import com.example.comptable.entreprise.web.dto.CreerEntrepriseRequest;
import com.example.comptable.entreprise.web.dto.EntrepriseDto;
import com.example.comptable.identite.domain.Role;
import com.example.comptable.identite.domain.Tenant;
import com.example.comptable.identite.domain.Utilisateur;
import com.example.comptable.identite.repository.RoleRepository;
import com.example.comptable.identite.repository.TenantRepository;
import com.example.comptable.identite.repository.UtilisateurRepository;
import com.example.comptable.security.UtilisateurPrincipal;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;

import static org.mockito.Mockito.when;

/**
 * Socle des tests d'integration de la collecte : vraie base PostgreSQL, vrais services, et un FAUX fournisseur d'envoi
 * (jamais WhatsApp reel). Un meme contexte Spring est partage par toutes les sous-classes.
 */
@SpringBootTest(properties = {"spring.docker.compose.skip.in-tests=false", "collecte.lien-depot.base-url=https://depot.test/d",
        "spring.jpa.properties.hibernate.generate_statistics=true"})
public abstract class CollecteTestBase {

    private static final AtomicLong TELEPHONES = new AtomicLong(2250100000000L + (System.currentTimeMillis() % 100000000L) * 10);

    /** Un dossier de test : cabinet (expert), entreprise, fournisseur, interlocuteur client (joignable ou non). */
    public record Dossier(UtilisateurPrincipal expert, EntrepriseDto entreprise, TiersDto fournisseur, String telephone) {
        public UUID id() {
            return entreprise.id();
        }
    }

    @MockitoBean
    protected WhatsAppDeliveryAdapter fournisseur;

    @Autowired protected TenantRepository tenantRepository;
    @Autowired protected UtilisateurRepository utilisateurRepository;
    @Autowired protected RoleRepository roleRepository;
    @Autowired protected UtilisateurEntrepriseRepository utilisateurEntrepriseRepository;
    @Autowired protected EntrepriseService entrepriseService;
    @Autowired protected EntrepriseRepository entrepriseRepository;
    @Autowired protected TiersService tiersService;
    @Autowired protected CompteComptableRepository compteComptableRepository;
    @Autowired protected AccountingOperationEngine engine;
    @Autowired protected ControlRunService controlRunService;
    @Autowired protected FindingRepository findingRepository;
    @Autowired protected AuditEventRepository auditEventRepository;
    @Autowired protected JdbcTemplate jdbc;

    @BeforeEach
    void preparerLeFaussaire() {
        // Le worker retrouve l'adaptateur par son canal : le faux fournisseur se declare WHATSAPP.
        when(fournisseur.canal()).thenReturn(CanalMessage.WHATSAPP);
    }

    protected Dossier nouveauDossier(boolean interlocuteurJoignable) {
        Tenant tenant = new Tenant();
        tenant.setRaisonSociale("Cabinet Collecte");
        tenant.setSlug("cabinet-collecte-" + UUID.randomUUID());
        tenant.setPays("CI");
        tenant = tenantRepository.save(tenant);
        Utilisateur expert = utilisateur(tenant, "EXPERT_COMPTABLE", null);
        UtilisateurPrincipal principal = new UtilisateurPrincipal(expert);
        EntrepriseDto entreprise = entrepriseService.creer(new CreerEntrepriseRequest("PME Collecte " + UUID.randomUUID().toString().substring(0, 6),
                null, null, null, null, null, "CI", "XOF"), principal);
        String telephone = null;
        if (interlocuteurJoignable) {
            telephone = String.valueOf(TELEPHONES.incrementAndGet());
            Utilisateur client = utilisateur(tenant, "CLIENT", telephone);
            utilisateurEntrepriseRepository.save(new UtilisateurEntreprise(client.getId(), entreprise.id()));
        }
        TiersDto fournisseurTiers = tiersService.creer(entreprise.id(), TypeTiers.FOURNISSEUR, "Station XYZ", null, principal);
        return new Dossier(principal, entreprise, fournisseurTiers, telephone);
    }

    protected Utilisateur utilisateur(Tenant tenant, String role, String telephone) {
        Role r = roleRepository.findByCode(role).orElseThrow();
        Utilisateur u = new Utilisateur();
        u.setTenant(tenant);
        u.setEmail("collecte-" + UUID.randomUUID() + "@test.ci");
        u.setMotDePasse("hash");
        u.setNom("Test");
        u.setPrenom(role);
        u.setTelephone(telephone);
        u.setRoles(Set.of(r));
        return utilisateurRepository.save(u);
    }

    /** Une facture d'achat comptabilisee par le moteur SANS aucune piece : le detecteur la signalera. */
    protected UUID acheterSansPiece(Dossier d, int montantHt) {
        return acheterSansPiece(d, d.fournisseur(), montantHt);
    }

    protected UUID acheterSansPiece(Dossier d, TiersDto fournisseurDuMouvement, int montantHt) {
        UUID compteCharge = compteComptableRepository.findByEntrepriseIdAndNumero(d.id(), "601").orElseThrow().getId();
        var proposition = engine.proposerAchatEtValiderImmediatement(d.id(), new AchatMultiLigneOperationRequest(fournisseurDuMouvement.id(),
                LocalDate.now(), "A-" + montantHt, "Achat " + montantHt, List.of(new LigneAchatOperationRequest("Marchandises",
                NatureAchat.MARCHANDISE, new BigDecimal(montantHt), BigDecimal.ZERO, compteCharge, null)), null,
                AccountingSource.de("FACTURE_FOURNISSEUR", UUID.randomUUID(), "VALIDATION")), d.expert());
        return proposition.ecritureId();
    }

    protected List<Finding> piecesManquantes(Dossier d) {
        controlRunService.executer(entrepriseRepository.findById(d.id()).orElseThrow());
        return findingRepository.findActifsParCodes(d.id(), List.of(ControlCodes.MISSING_DOCUMENT));
    }

    protected List<AuditEvent> evenements(String action) {
        return auditEventRepository.findAll().stream().filter(e -> action.equals(e.getAction())).toList();
    }

    protected long evenementsPour(String action, UUID demandeId) {
        return auditEventRepository.findAll().stream()
                .filter(e -> action.equals(e.getAction()) && String.valueOf(demandeId).equals(e.getEntiteId())).count();
    }
}
