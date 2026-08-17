package com.example.comptable.comptabilite.service;

import com.example.comptable.comptabilite.domain.CompteComptable;
import com.example.comptable.comptabilite.domain.DotationAmortissement;
import com.example.comptable.comptabilite.domain.EcritureComptable;
import com.example.comptable.comptabilite.domain.ExerciceComptable;
import com.example.comptable.comptabilite.domain.Immobilisation;
import com.example.comptable.comptabilite.domain.Journal;
import com.example.comptable.comptabilite.domain.LigneEcriture;
import com.example.comptable.comptabilite.domain.StatutEcriture;
import com.example.comptable.comptabilite.domain.StatutExercice;
import com.example.comptable.comptabilite.domain.StatutImmobilisation;
import com.example.comptable.comptabilite.repository.CompteComptableRepository;
import com.example.comptable.comptabilite.repository.DotationAmortissementRepository;
import com.example.comptable.comptabilite.repository.EcritureComptableRepository;
import com.example.comptable.comptabilite.repository.ExerciceComptableRepository;
import com.example.comptable.comptabilite.repository.ImmobilisationRepository;
import com.example.comptable.comptabilite.repository.JournalRepository;
import com.example.comptable.comptabilite.web.dto.CreerImmobilisationRequest;
import com.example.comptable.comptabilite.web.dto.GenererDotationsResultDto;
import com.example.comptable.comptabilite.web.dto.ImmobilisationDto;
import com.example.comptable.comptabilite.web.dto.LigneAmortissementDto;
import com.example.comptable.entreprise.domain.Entreprise;
import com.example.comptable.entreprise.service.EntrepriseAccessService;
import com.example.comptable.identite.domain.Utilisateur;
import com.example.comptable.identite.repository.UtilisateurRepository;
import com.example.comptable.security.UtilisateurPrincipal;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * Registre des immobilisations et generation des dotations aux amortissements. Chaque annee
 * ou une dotation est due (selon le plan calcule par {@link PlanAmortissementCalculator}), le
 * cabinet declenche explicitement sa generation : une seule ecriture (journal OD) regroupe la
 * dotation de toutes les immobilisations en service pour l'annee demandee, avec deux lignes par
 * immobilisation (debit 681, credit du compte d'amortissement propre a chaque immobilisation)
 * pour garder une tracabilite actif par actif.
 */
@Service
@RequiredArgsConstructor
public class ImmobilisationService {

    private static final String COMPTE_DOTATIONS = "681";

    private final EntrepriseAccessService entrepriseAccessService;
    private final ImmobilisationRepository immobilisationRepository;
    private final DotationAmortissementRepository dotationAmortissementRepository;
    private final CompteComptableRepository compteComptableRepository;
    private final ExerciceComptableRepository exerciceComptableRepository;
    private final JournalRepository journalRepository;
    private final EcritureComptableRepository ecritureComptableRepository;
    private final UtilisateurRepository utilisateurRepository;

    @Transactional
    public ImmobilisationDto creer(UUID entrepriseId, CreerImmobilisationRequest request, UtilisateurPrincipal principal) {
        Entreprise entreprise = entrepriseAccessService.verifierAcces(entrepriseId, principal);
        CompteComptable compte = chargerCompte(entrepriseId, request.compteId());
        CompteComptable compteAmortissement = chargerCompte(entrepriseId, request.compteAmortissementId());

        Immobilisation immobilisation = new Immobilisation();
        immobilisation.setEntreprise(entreprise);
        immobilisation.setCompte(compte);
        immobilisation.setCompteAmortissement(compteAmortissement);
        immobilisation.setLibelle(request.libelle());
        immobilisation.setDateAcquisition(request.dateAcquisition());
        immobilisation.setValeurOrigine(request.valeurOrigine());
        immobilisation.setDureeAnnees(request.dureeAnnees());
        immobilisation.setMode(request.mode());

        immobilisation = immobilisationRepository.save(immobilisation);
        return toDto(immobilisation, List.of());
    }

    /** Une seule requete pour les dotations de toutes les immobilisations listees (pas une par
     * immobilisation) : indispensable des que le registre depasse quelques dizaines d'actifs. */
    @Transactional(readOnly = true)
    public List<ImmobilisationDto> lister(UUID entrepriseId, UtilisateurPrincipal principal) {
        entrepriseAccessService.verifierAcces(entrepriseId, principal);
        List<Immobilisation> immobilisations = immobilisationRepository.findByEntrepriseIdOrderByDateAcquisitionAsc(entrepriseId);

        List<UUID> immobilisationIds = immobilisations.stream().map(Immobilisation::getId).toList();
        Map<UUID, List<DotationAmortissement>> dotationsParImmobilisation = dotationAmortissementRepository
                .findByImmobilisationIdIn(immobilisationIds).stream()
                .collect(Collectors.groupingBy(d -> d.getImmobilisation().getId()));

        return immobilisations.stream()
                .map(immo -> toDto(immo, dotationsParImmobilisation.getOrDefault(immo.getId(), List.of())))
                .toList();
    }

    @Transactional
    public GenererDotationsResultDto genererDotations(UUID entrepriseId, int annee, UtilisateurPrincipal principal) {
        Entreprise entreprise = entrepriseAccessService.verifierAcces(entrepriseId, principal);

        LocalDate dateEcriture = LocalDate.of(annee, 12, 31);
        ExerciceComptable exercice = exerciceComptableRepository.findByEntrepriseIdAndDate(entrepriseId, dateEcriture)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY,
                        "Aucun exercice comptable ne couvre l'annee " + annee));
        if (exercice.getStatut() != StatutExercice.OUVERT) {
            throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY, "L'exercice de cette annee est cloture");
        }

        List<Immobilisation> immobilisations = immobilisationRepository.findByEntrepriseIdAndStatut(entrepriseId, StatutImmobilisation.EN_SERVICE);

        record Candidat(Immobilisation immobilisation, BigDecimal dotation) {
        }
        List<Candidat> candidats = immobilisations.stream()
                .filter(immo -> !dotationAmortissementRepository.existsByImmobilisationIdAndAnnee(immo.getId(), annee))
                .map(immo -> {
                    BigDecimal dotation = PlanAmortissementCalculator.calculer(
                                    immo.getValeurOrigine(), immo.getDateAcquisition(), immo.getDureeAnnees(), immo.getMode())
                            .stream()
                            .filter(l -> l.annee() == annee)
                            .findFirst()
                            .map(PlanAmortissementCalculator.LigneAmortissement::dotation)
                            .orElse(null);
                    return dotation != null ? new Candidat(immo, dotation) : null;
                })
                .filter(java.util.Objects::nonNull)
                .toList();

        if (candidats.isEmpty()) {
            return new GenererDotationsResultDto(null, annee, BigDecimal.ZERO, 0);
        }

        Journal journal = journalRepository.findByEntrepriseIdAndCode(entrepriseId, "OD")
                .orElseThrow(() -> new IllegalStateException("Journal OD introuvable, l'entreprise n'a pas ete initialisee correctement"));
        CompteComptable compteDotations = compteComptableRepository.findByEntrepriseIdAndNumero(entrepriseId, COMPTE_DOTATIONS)
                .orElseThrow(() -> new IllegalStateException("Compte 681 introuvable, l'entreprise n'a pas ete initialisee correctement"));
        Utilisateur utilisateur = utilisateurRepository.getReferenceById(principal.getId());

        EcritureComptable ecriture = new EcritureComptable();
        ecriture.setEntreprise(entreprise);
        ecriture.setExercice(exercice);
        ecriture.setJournal(journal);
        ecriture.setDateEcriture(dateEcriture);
        ecriture.setLibelle("Dotations aux amortissements " + annee);
        ecriture.setCreatedBy(utilisateur);
        ecriture.setStatut(StatutEcriture.VALIDEE);
        ecriture.setNumeroPiece(genererNumeroPiece(journal));

        for (Candidat candidat : candidats) {
            LigneEcriture ligneDebit = new LigneEcriture();
            ligneDebit.setCompte(compteDotations);
            ligneDebit.setLibelle("Dotation " + candidat.immobilisation().getLibelle());
            ligneDebit.setDebit(candidat.dotation());
            ligneDebit.setCredit(BigDecimal.ZERO);
            ecriture.ajouterLigne(ligneDebit);

            LigneEcriture ligneCredit = new LigneEcriture();
            ligneCredit.setCompte(candidat.immobilisation().getCompteAmortissement());
            ligneCredit.setLibelle("Dotation " + candidat.immobilisation().getLibelle());
            ligneCredit.setDebit(BigDecimal.ZERO);
            ligneCredit.setCredit(candidat.dotation());
            ecriture.ajouterLigne(ligneCredit);
        }

        if (!ecriture.estEquilibree()) {
            throw new IllegalStateException("Ecriture de dotation desequilibree, generation annulee");
        }
        ecriture = ecritureComptableRepository.save(ecriture);

        BigDecimal total = BigDecimal.ZERO;
        for (int i = 0; i < candidats.size(); i++) {
            Candidat candidat = candidats.get(i);
            LigneEcriture ligneCredit = ecriture.getLignes().get(i * 2 + 1);

            DotationAmortissement dotationAmortissement = new DotationAmortissement();
            dotationAmortissement.setImmobilisation(candidat.immobilisation());
            dotationAmortissement.setAnnee(annee);
            dotationAmortissement.setMontant(candidat.dotation());
            dotationAmortissement.setLigneEcritureId(ligneCredit.getId());
            dotationAmortissementRepository.save(dotationAmortissement);

            total = total.add(candidat.dotation());
        }

        return new GenererDotationsResultDto(ecriture.getId(), annee, total, candidats.size());
    }

    private ImmobilisationDto toDto(Immobilisation immobilisation, List<DotationAmortissement> dotationsGenerees) {
        List<PlanAmortissementCalculator.LigneAmortissement> planCalcule = PlanAmortissementCalculator.calculer(
                immobilisation.getValeurOrigine(), immobilisation.getDateAcquisition(),
                immobilisation.getDureeAnnees(), immobilisation.getMode());

        Map<Integer, DotationAmortissement> genereesParAnnee = dotationsGenerees.stream()
                .collect(Collectors.toMap(DotationAmortissement::getAnnee, d -> d));

        List<LigneAmortissementDto> plan = planCalcule.stream()
                .map(l -> new LigneAmortissementDto(l.annee(), l.dotation(), l.valeurNetteComptableFin(),
                        genereesParAnnee.containsKey(l.annee())))
                .toList();

        BigDecimal totalGenere = genereesParAnnee.values().stream()
                .map(DotationAmortissement::getMontant)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        BigDecimal vncActuelle = immobilisation.getValeurOrigine().subtract(totalGenere);

        return ImmobilisationDto.from(immobilisation, vncActuelle, plan);
    }

    private String genererNumeroPiece(Journal journal) {
        long sequence = ecritureComptableRepository.countByJournalId(journal.getId()) + 1;
        return "%s-%d-%04d".formatted(journal.getCode(), LocalDate.now().getYear(), sequence);
    }

    private CompteComptable chargerCompte(UUID entrepriseId, UUID compteId) {
        return compteComptableRepository.findById(compteId)
                .filter(c -> c.getEntreprise().getId().equals(entrepriseId))
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Compte introuvable"));
    }
}
