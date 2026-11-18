package com.example.comptable.entreprise.service;

import com.example.comptable.comptabilite.domain.ExerciceComptable;
import com.example.comptable.comptabilite.domain.Journal;
import com.example.comptable.comptabilite.domain.TypeJournal;
import com.example.comptable.comptabilite.repository.ExerciceComptableRepository;
import com.example.comptable.comptabilite.repository.JournalRepository;
import com.example.comptable.comptabilite.service.PlanComptableImportService;
import com.example.comptable.entreprise.domain.Entreprise;
import com.example.comptable.entreprise.repository.EntrepriseRepository;
import com.example.comptable.entreprise.repository.UtilisateurEntrepriseRepository;
import com.example.comptable.entreprise.web.dto.CreerEntrepriseRequest;
import com.example.comptable.entreprise.web.dto.EntrepriseDto;
import com.example.comptable.identite.repository.TenantRepository;
import com.example.comptable.security.UtilisateurPrincipal;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.Year;
import java.util.List;
import java.util.UUID;

/**
 * Cree une entreprise et l'amorce completement : chaque entreprise doit repartir
 * avec son propre plan comptable (clone du referentiel SYSCOHADA), ses journaux
 * reglementaires et un exercice ouvert, sinon le moteur de comptabilisation n'a
 * rien sur quoi s'appuyer.
 */
@Service
@RequiredArgsConstructor
public class EntrepriseService {

    private final TenantRepository tenantRepository;
    private final EntrepriseRepository entrepriseRepository;
    private final UtilisateurEntrepriseRepository utilisateurEntrepriseRepository;
    private final PlanComptableImportService planComptableImportService;
    private final JournalRepository journalRepository;
    private final ExerciceComptableRepository exerciceComptableRepository;
    private final com.example.comptable.identite.service.QuotaCabinetService quotaCabinetService;
    private final EntrepriseAccessService entrepriseAccessService;
    private final com.example.comptable.audit.AuditTrailService auditTrailService;

    @Transactional
    public EntrepriseDto creer(CreerEntrepriseRequest request, UtilisateurPrincipal principal) {
        quotaCabinetService.verifierCreationDossier(principal.getTenantId());
        Entreprise entreprise = new Entreprise();
        entreprise.setTenant(tenantRepository.getReferenceById(principal.getTenantId()));
        entreprise.setRaisonSociale(request.raisonSociale());
        entreprise.setNumeroContribuable(request.numeroContribuable());
        entreprise.setRccm(request.rccm());
        entreprise.setFormeJuridique(request.formeJuridique());
        entreprise.setRegimeFiscal(request.regimeFiscal());
        entreprise.setSecteurActivite(request.secteurActivite());
        entreprise.setPays(request.pays().toUpperCase());
        if (request.devise() != null) {
            entreprise.setDevise(request.devise().toUpperCase());
        }
        entreprise = entrepriseRepository.save(entreprise);

        planComptableImportService.importerTout(entreprise);
        creerJournauxParDefaut(entreprise);
        creerExerciceCourant(entreprise);

        return EntrepriseDto.from(entreprise);
    }

    /**
     * EXPERT_COMPTABLE/COLLABORATEUR voient toutes les entreprises de leur tenant ;
     * un CLIENT ne voit que celles auxquelles il est explicitement rattache (portail).
     */
    @Transactional(readOnly = true)
    public List<EntrepriseDto> lister(UtilisateurPrincipal principal) {
        boolean estClient = principal.getAuthorities().stream()
                .anyMatch(a -> a.getAuthority().equals("ROLE_CLIENT"));

        List<Entreprise> entreprises;
        if (estClient) {
            List<UUID> entrepriseIds = utilisateurEntrepriseRepository.findByIdUtilisateurId(principal.getId())
                    .stream().map(ue -> ue.getId().getEntrepriseId()).toList();
            entreprises = entrepriseRepository.findAllById(entrepriseIds);
        } else {
            entreprises = entrepriseRepository.findByTenantId(principal.getTenantId());
        }

        return entreprises.stream().map(EntrepriseDto::from).toList();
    }

    private void creerJournauxParDefaut(Entreprise entreprise) {
        List<Journal> journaux = List.of(
                journal(entreprise, "ACH", "Achats", TypeJournal.ACHATS),
                journal(entreprise, "VTE", "Ventes", TypeJournal.VENTES),
                journal(entreprise, "BQ", "Banque", TypeJournal.BANQUE),
                journal(entreprise, "CA", "Caisse", TypeJournal.CAISSE),
                journal(entreprise, "OD", "Operations diverses", TypeJournal.OPERATIONS_DIVERSES)
        );
        journalRepository.saveAll(journaux);
    }

    private Journal journal(Entreprise entreprise, String code, String libelle, TypeJournal type) {
        Journal journal = new Journal();
        journal.setEntreprise(entreprise);
        journal.setCode(code);
        journal.setLibelle(libelle);
        journal.setType(type);
        return journal;
    }

    private void creerExerciceCourant(Entreprise entreprise) {
        int annee = Year.now().getValue();
        ExerciceComptable exercice = new ExerciceComptable();
        exercice.setEntreprise(entreprise);
        exercice.setDateDebut(LocalDate.of(annee, 1, 1));
        exercice.setDateFin(LocalDate.of(annee, 12, 31));
        exerciceComptableRepository.save(exercice);
    }

    @Transactional(readOnly = true)
    public com.example.comptable.entreprise.web.dto.FicheEntreprise fiche(UUID entrepriseId, UtilisateurPrincipal principal) {
        return com.example.comptable.entreprise.web.dto.FicheEntreprise.from(entrepriseAccessService.verifierAcces(entrepriseId, principal));
    }

    /** Met à jour la fiche légale. Réservé aux membres du cabinet (un utilisateur du portail client ne modifie pas l'identité de son dossier). Les champs vides sont effacés. */
    @Transactional
    public com.example.comptable.entreprise.web.dto.FicheEntreprise modifierFiche(UUID entrepriseId, com.example.comptable.entreprise.web.dto.FicheEntreprise fiche, UtilisateurPrincipal principal) {
        Entreprise e = entrepriseAccessService.verifierAcces(entrepriseId, principal);
        if (principal.getAuthorities().stream().anyMatch(a -> a.getAuthority().equals("ROLE_CLIENT"))) {
            throw new org.springframework.web.server.ResponseStatusException(org.springframework.http.HttpStatus.FORBIDDEN, "Seul le cabinet peut modifier la fiche de l'entreprise");
        }
        e.setRaisonSociale(fiche.raisonSociale().trim());
        e.setNumeroContribuable(nettoye(fiche.numeroContribuable()));
        e.setRccm(nettoye(fiche.rccm()));
        e.setFormeJuridique(nettoye(fiche.formeJuridique()));
        e.setRegimeFiscal(nettoye(fiche.regimeFiscal()));
        e.setAdresse(nettoye(fiche.adresse()));
        e.setTelephone(nettoye(fiche.telephone()));
        e.setEmail(nettoye(fiche.email()));
        e.setCoordonneesBancaires(nettoye(fiche.coordonneesBancaires()));
        e.setMentionsFacture(nettoye(fiche.mentionsFacture()));
        entrepriseRepository.save(e);
        auditTrailService.enregistrerActionHumaine(principal.getTenantId(), principal.getId(), "FICHE_ENTREPRISE_MODIFIEE", "ENTREPRISE", entrepriseId.toString(), java.util.Map.of("raisonSociale", e.getRaisonSociale()));
        return com.example.comptable.entreprise.web.dto.FicheEntreprise.from(e);
    }

    private static String nettoye(String valeur) {
        return valeur == null || valeur.isBlank() ? null : valeur.trim();
    }
}
