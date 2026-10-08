package com.example.comptable.fiscalite.service;

import com.example.comptable.comptabilite.domain.CompteComptable;
import com.example.comptable.comptabilite.parametrage.AccountContext;
import com.example.comptable.comptabilite.parametrage.AccountingAccountResolver;
import com.example.comptable.comptabilite.parametrage.domain.AccountPurpose;
import com.example.comptable.comptabilite.repository.LigneEcritureRepository;
import com.example.comptable.entreprise.domain.Entreprise;
import com.example.comptable.entreprise.service.EntrepriseAccessService;
import com.example.comptable.fiscalite.core.TaxDeadlineGenerator;
import com.example.comptable.fiscalite.core.TaxFrameworkService;
import com.example.comptable.fiscalite.domain.DeclarationFiscale;
import com.example.comptable.fiscalite.domain.StatutDeclaration;
import com.example.comptable.fiscalite.domain.TypeImpot;
import com.example.comptable.fiscalite.repository.DeclarationFiscaleRepository;
import com.example.comptable.fiscalite.web.dto.CalculTvaDto;
import com.example.comptable.fiscalite.web.dto.CreerDeclarationRequest;
import com.example.comptable.fiscalite.web.dto.DeclarationFiscaleDto;
import com.example.comptable.security.UtilisateurPrincipal;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.format.DateTimeParseException;
import java.util.List;
import java.util.UUID;

/**
 * Echeancier fiscal d'une entreprise : declarations manuelles + generation automatique
 * de l'echeancier TVA mensuel. Le statut EN_RETARD n'est pas persiste : il est calcule
 * a la volee (date d'echeance passee et pas encore payee) pour eviter un job planifie.
 */
@Service
@RequiredArgsConstructor
public class DeclarationFiscaleService {

    private final EntrepriseAccessService entrepriseAccessService;
    private final DeclarationFiscaleRepository declarationFiscaleRepository;
    private final AccountingAccountResolver accountingAccountResolver;
    private final LigneEcritureRepository ligneEcritureRepository;
    private final TaxFrameworkService cadres;

    @Transactional(readOnly = true)
    public List<DeclarationFiscaleDto> lister(UUID entrepriseId, UtilisateurPrincipal principal) {
        entrepriseAccessService.verifierAcces(entrepriseId, principal);
        return declarationFiscaleRepository.findByEntrepriseIdOrderByDateEcheanceAsc(entrepriseId).stream()
                .map(DeclarationFiscaleDto::from)
                .toList();
    }

    @Transactional
    public DeclarationFiscaleDto creer(UUID entrepriseId, CreerDeclarationRequest request, UtilisateurPrincipal principal) {
        Entreprise entreprise = entrepriseAccessService.verifierAcces(entrepriseId, principal);

        if (declarationFiscaleRepository.existsByEntrepriseIdAndTypeImpotAndPeriode(
                entrepriseId, request.typeImpot(), request.periode())) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "Une declaration " + request.typeImpot() + " existe deja pour la periode " + request.periode());
        }

        DeclarationFiscale declaration = new DeclarationFiscale();
        declaration.setEntreprise(entreprise);
        declaration.setTypeImpot(request.typeImpot());
        declaration.setPeriode(request.periode());
        declaration.setDateEcheance(request.dateEcheance());
        declaration.setMontant(request.montant());
        return DeclarationFiscaleDto.from(declarationFiscaleRepository.save(declaration));
    }

    /**
     * Idempotent : les periodes deja presentes pour cette annee sont ignorees, pas dupliquees. Les periodes et echeances viennent UNIQUEMENT de la regle d'un cadre fiscal VERIFIED
     * (periodicite + regle d'echeance) : sans cadre verifie ou sans regle, rien n'est genere (aucune convention codee en dur).
     */
    @Transactional
    public List<DeclarationFiscaleDto> genererEcheancierTva(UUID entrepriseId, int annee, UtilisateurPrincipal principal) {
        Entreprise entreprise = entrepriseAccessService.verifierAcces(entrepriseId, principal);
        var regles = cadres.resoudre(entreprise.getTenant().getId(), entreprise.getPays(), TaxFrameworkService.TVA, LocalDate.of(annee, 12, 31))
                .filter(TaxFrameworkService.Resolution::verifie).map(r -> r.framework().rules()).orElse(null);
        var periodes = TaxDeadlineGenerator.periodesAnnuelles(regles, annee);
        if (periodes.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY,
                    "Aucun echeancier n'est genere : il n'existe pas de cadre fiscal TVA verifie fournissant une periodicite mensuelle et une regle d'echeance pour " + annee);
        }

        List<DeclarationFiscale> creees = periodes.stream()
                .filter(p -> !declarationFiscaleRepository.existsByEntrepriseIdAndTypeImpotAndPeriode(entrepriseId, TypeImpot.TVA, p.periode()))
                .map(p -> {
                    DeclarationFiscale declaration = new DeclarationFiscale();
                    declaration.setEntreprise(entreprise);
                    declaration.setTypeImpot(TypeImpot.TVA);
                    declaration.setPeriode(p.periode());
                    declaration.setDateEcheance(p.dateEcheance());
                    return declaration;
                })
                .toList();

        declarationFiscaleRepository.saveAll(creees);
        return declarationFiscaleRepository.findByEntrepriseIdOrderByDateEcheanceAsc(entrepriseId).stream()
                .map(DeclarationFiscaleDto::from)
                .toList();
    }

    @Transactional
    public DeclarationFiscaleDto marquerDeposee(UUID entrepriseId, UUID declarationId, LocalDate dateDeclaration,
                                                 UtilisateurPrincipal principal) {
        DeclarationFiscale declaration = charger(entrepriseId, declarationId, principal);

        if (declaration.getStatut() == StatutDeclaration.PAYEE) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Cette declaration est deja payee");
        }

        declaration.setStatut(StatutDeclaration.DEPOSEE);
        declaration.setDateDeclaration(dateDeclaration != null ? dateDeclaration : LocalDate.now());
        return DeclarationFiscaleDto.from(declarationFiscaleRepository.save(declaration));
    }

    @Transactional
    public DeclarationFiscaleDto marquerPayee(UUID entrepriseId, UUID declarationId, LocalDate datePaiement,
                                               BigDecimal montant, UtilisateurPrincipal principal) {
        DeclarationFiscale declaration = charger(entrepriseId, declarationId, principal);

        declaration.setStatut(StatutDeclaration.PAYEE);
        declaration.setDatePaiement(datePaiement != null ? datePaiement : LocalDate.now());
        if (montant != null) {
            declaration.setMontant(montant);
        }
        if (declaration.getDateDeclaration() == null) {
            declaration.setDateDeclaration(declaration.getDatePaiement());
        }
        return DeclarationFiscaleDto.from(declarationFiscaleRepository.save(declaration));
    }

    /**
     * Calcule le montant de TVA a payer sur une periode a partir des ecritures VALIDEES :
     * TVA collectee (compte du purpose TVA_COLLECTEE, credite sur les ventes) moins TVA
     * recuperable (purpose TVA_RECUPERABLE, debitee sur les achats). Ne persiste rien ; utilise par {@link #recalculerMontantTva} et exposable
     * en previsualisation.
     */
    @Transactional(readOnly = true)
    public CalculTvaDto calculerTva(UUID entrepriseId, String periode, UtilisateurPrincipal principal) {
        entrepriseAccessService.verifierAcces(entrepriseId, principal);
        return calculerTvaInterne(entrepriseId, periode);
    }

    private CalculTvaDto calculerTvaInterne(UUID entrepriseId, String periode) {
        YearMonth mois;
        try {
            mois = YearMonth.parse(periode);
        } catch (DateTimeParseException e) {
            throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY,
                    "Periode invalide pour un calcul de TVA (attendu AAAA-MM) : " + periode);
        }
        LocalDate dateDebut = mois.atDay(1);
        LocalDate dateFin = mois.atEndOfMonth();

        CompteComptable compteCollectee = chargerCompteTva(entrepriseId, AccountPurpose.TVA_COLLECTEE, "TVA collectee");
        CompteComptable compteDeductible = chargerCompteTva(entrepriseId, AccountPurpose.TVA_RECUPERABLE, "TVA recuperable");

        BigDecimal montantCollecte = ligneEcritureRepository.mouvementNetPeriode(compteCollectee.getId(), dateDebut, dateFin);
        BigDecimal montantDeductibleBrut = ligneEcritureRepository.mouvementNetPeriode(compteDeductible.getId(), dateDebut, dateFin);
        // Le compte de TVA recuperable est naturellement debiteur : son mouvement net (credit - debit) est
        // negatif ou nul en usage normal ; on l'exprime en valeur positive pour la soustraction.
        BigDecimal montantDeductible = montantDeductibleBrut.negate();

        return new CalculTvaDto(periode, montantCollecte, montantDeductible, montantCollecte.subtract(montantDeductible));
    }

    /** Les comptes de TVA lus pour la declaration sont ceux que l'entreprise a configures (purposes
     * TVA_COLLECTEE / TVA_RECUPERABLE), les memes que ceux utilises pour passer les ecritures : jamais un
     * numero suppose. Le taux, lui, releve de la fiscalite (TaxRule) et n'intervient pas ici. */
    private CompteComptable chargerCompteTva(UUID entrepriseId, AccountPurpose purpose, String libelle) {
        AccountingAccountResolver.CompteResolu resolu = accountingAccountResolver.resoudreCompte(entrepriseId, purpose,
                AccountContext.vide(), null);
        if (!resolu.estResolu()) {
            throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY,
                    "Calcul de la TVA impossible : " + resolu.explicationBlocage("de " + libelle));
        }
        return resolu.compte();
    }

    /** Recalcule et persiste le montant d'une declaration TVA depuis les ecritures. Impossible une fois payee. */
    @Transactional
    public DeclarationFiscaleDto recalculerMontantTva(UUID entrepriseId, UUID declarationId, UtilisateurPrincipal principal) {
        DeclarationFiscale declaration = charger(entrepriseId, declarationId, principal);

        if (declaration.getTypeImpot() != TypeImpot.TVA) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "Le recalcul automatique n'est disponible que pour les declarations TVA");
        }
        if (declaration.getStatut() == StatutDeclaration.PAYEE) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Cette declaration est deja payee, montant intangible");
        }

        CalculTvaDto calcul = calculerTvaInterne(entrepriseId, declaration.getPeriode());
        declaration.setMontant(calcul.montantAPayer());
        return DeclarationFiscaleDto.from(declarationFiscaleRepository.save(declaration));
    }

    private DeclarationFiscale charger(UUID entrepriseId, UUID declarationId, UtilisateurPrincipal principal) {
        entrepriseAccessService.verifierAcces(entrepriseId, principal);
        DeclarationFiscale declaration = declarationFiscaleRepository.findById(declarationId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Declaration introuvable"));
        if (!declaration.getEntreprise().getId().equals(entrepriseId)) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Declaration introuvable");
        }
        return declaration;
    }
}
