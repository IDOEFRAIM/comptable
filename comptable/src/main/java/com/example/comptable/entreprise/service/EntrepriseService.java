package com.example.comptable.entreprise.service;

import com.example.comptable.comptabilite.domain.CompteComptable;
import com.example.comptable.comptabilite.domain.ExerciceComptable;
import com.example.comptable.comptabilite.domain.Journal;
import com.example.comptable.comptabilite.domain.PlanComptableReference;
import com.example.comptable.comptabilite.domain.TypeJournal;
import com.example.comptable.comptabilite.repository.CompteComptableRepository;
import com.example.comptable.comptabilite.repository.ExerciceComptableRepository;
import com.example.comptable.comptabilite.repository.JournalRepository;
import com.example.comptable.comptabilite.repository.PlanComptableReferenceRepository;
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
    private final PlanComptableReferenceRepository planComptableReferenceRepository;
    private final CompteComptableRepository compteComptableRepository;
    private final JournalRepository journalRepository;
    private final ExerciceComptableRepository exerciceComptableRepository;

    @Transactional
    public EntrepriseDto creer(CreerEntrepriseRequest request, UtilisateurPrincipal principal) {
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

        clonerPlanComptable(entreprise);
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

    private void clonerPlanComptable(Entreprise entreprise) {
        List<PlanComptableReference> referentiel = planComptableReferenceRepository.findAll();
        List<CompteComptable> comptes = referentiel.stream().map(reference -> {
            CompteComptable compte = new CompteComptable();
            compte.setEntreprise(entreprise);
            compte.setReference(reference);
            compte.setNumero(reference.getNumero());
            compte.setIntitule(reference.getIntitule());
            compte.setLettrable(reference.getNumero().startsWith("401") || reference.getNumero().startsWith("411"));
            return compte;
        }).toList();
        compteComptableRepository.saveAll(comptes);
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
}
