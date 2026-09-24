package com.example.comptable.comptabilite.operation.common;

import com.example.comptable.comptabilite.domain.EcritureComptable;
import com.example.comptable.comptabilite.domain.ExerciceComptable;
import com.example.comptable.comptabilite.domain.Journal;
import com.example.comptable.comptabilite.domain.LigneEcriture;
import com.example.comptable.comptabilite.domain.StatutEcriture;
import com.example.comptable.comptabilite.domain.StatutExercice;
import com.example.comptable.comptabilite.domain.Tiers;
import com.example.comptable.comptabilite.domain.TypeTiers;
import com.example.comptable.comptabilite.operation.achat.AchatMultiLigneOperationRequest;
import com.example.comptable.comptabilite.operation.achat.AchatOperationRequest;
import com.example.comptable.comptabilite.operation.achat.AchatRule;
import com.example.comptable.comptabilite.operation.avoir.AvoirOperationRequest;
import com.example.comptable.comptabilite.operation.avoir.AvoirRule;
import com.example.comptable.comptabilite.operation.common.domain.AccountingOperationProposal;
import com.example.comptable.comptabilite.operation.common.domain.AccountingOperationProposalLigne;
import com.example.comptable.comptabilite.operation.common.domain.StatutOperationProposal;
import com.example.comptable.comptabilite.operation.common.repository.AccountingOperationProposalRepository;
import com.example.comptable.comptabilite.operation.common.web.dto.AccountingOperationProposalDto;
import com.example.comptable.comptabilite.operation.inventaire.InventaireRegularisationRule;
import com.example.comptable.comptabilite.operation.reglement.ReglementOperationRequest;
import com.example.comptable.comptabilite.operation.reglement.ReglementRule;
import com.example.comptable.comptabilite.operation.regularisation.DepreciationRule;
import com.example.comptable.comptabilite.operation.regularisation.ProvisionRule;
import com.example.comptable.comptabilite.operation.regularisation.RegularisationRule;
import com.example.comptable.comptabilite.operation.vente.VenteOperationRequest;
import com.example.comptable.comptabilite.operation.vente.VenteRule;
import com.example.comptable.comptabilite.depreciation.domain.Depreciation;
import com.example.comptable.comptabilite.provision.domain.Provision;
import com.example.comptable.comptabilite.regularisation.domain.Regularisation;
import com.example.comptable.comptabilite.repository.EcritureComptableRepository;
import com.example.comptable.comptabilite.repository.ExerciceComptableRepository;
import com.example.comptable.comptabilite.repository.TiersRepository;
import com.example.comptable.comptabilite.service.CompteComptableValidationService;
import com.example.comptable.comptabilite.service.NumeroPieceGenerator;
import com.example.comptable.entreprise.domain.Entreprise;
import com.example.comptable.entreprise.repository.EntrepriseRepository;
import com.example.comptable.entreprise.service.EntrepriseAccessService;
import com.example.comptable.identite.domain.Utilisateur;
import com.example.comptable.identite.repository.UtilisateurRepository;
import com.example.comptable.security.UtilisateurPrincipal;
import com.example.comptable.stock.inventaire.domain.Inventaire;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

/**
 * Orchestrateur unique : Operation metier -&gt; regle -&gt; AccountingOperationProposal -&gt;
 * controles -&gt; validation humaine -&gt; EcritureComptable. Ne cree jamais une ecriture reelle
 * directement depuis {@code proposerXxx} - seul {@link #valider} le fait, apres confirmation
 * humaine explicite.
 */
@Service
@RequiredArgsConstructor
public class AccountingOperationEngine {

    private final EntrepriseAccessService entrepriseAccessService;
    private final EntrepriseRepository entrepriseRepository;
    private final TiersRepository tiersRepository;
    private final ExerciceComptableRepository exerciceComptableRepository;
    private final EcritureComptableRepository ecritureComptableRepository;
    private final UtilisateurRepository utilisateurRepository;
    private final AccountingOperationProposalRepository accountingOperationProposalRepository;
    private final CompteComptableValidationService compteComptableValidationService;
    private final JournalResolver journalResolver;
    private final NumeroPieceGenerator numeroPieceGenerator;
    private final AchatRule achatRule;
    private final VenteRule venteRule;
    private final ReglementRule reglementRule;
    private final AvoirRule avoirRule;
    private final InventaireRegularisationRule inventaireRegularisationRule;
    private final RegularisationRule regularisationRule;
    private final ProvisionRule provisionRule;
    private final DepreciationRule depreciationRule;

    @Transactional
    public AccountingOperationProposalDto proposerAchat(UUID entrepriseId, AchatOperationRequest request,
                                                         UtilisateurPrincipal principal) {
        Entreprise entreprise = entrepriseAccessService.verifierAcces(entrepriseId, principal);
        Tiers fournisseur = chargerTiers(entrepriseId, request.fournisseurId(), TypeTiers.FOURNISSEUR);
        chargerExerciceOuvert(entrepriseId, request.date());
        ResultatRegle resultat = achatRule.construire(entreprise, fournisseur, request);
        return assemblerEtSauvegarder(entreprise, AccountingOperationType.ACHAT, fournisseur,
                request.date(), request.libelle(), request.reference(), resultat);
    }

    @Transactional
    public AccountingOperationProposalDto proposerVente(UUID entrepriseId, VenteOperationRequest request,
                                                         UtilisateurPrincipal principal) {
        Entreprise entreprise = entrepriseAccessService.verifierAcces(entrepriseId, principal);
        Tiers client = chargerTiers(entrepriseId, request.clientId(), TypeTiers.CLIENT);
        chargerExerciceOuvert(entrepriseId, request.date());
        ResultatRegle resultat = venteRule.construire(entreprise, client, request);
        return assemblerEtSauvegarder(entreprise, AccountingOperationType.VENTE, client,
                request.date(), request.libelle(), request.reference(), resultat);
    }

    @Transactional
    public AccountingOperationProposalDto proposerReglement(UUID entrepriseId, ReglementOperationRequest request,
                                                             UtilisateurPrincipal principal) {
        Entreprise entreprise = entrepriseAccessService.verifierAcces(entrepriseId, principal);
        TypeTiers typeAttendu = request.type() == AccountingOperationType.REGLEMENT_CLIENT
                ? TypeTiers.CLIENT : TypeTiers.FOURNISSEUR;
        Tiers tiers = chargerTiers(entrepriseId, request.tiersId(), typeAttendu);
        chargerExerciceOuvert(entrepriseId, request.date());
        ResultatRegle resultat = reglementRule.construire(entreprise, tiers, request);
        return assemblerEtSauvegarder(entreprise, request.type(), tiers,
                request.date(), request.libelle(), request.reference(), resultat);
    }

    @Transactional
    public AccountingOperationProposalDto proposerAvoir(UUID entrepriseId, AvoirOperationRequest request,
                                                         UtilisateurPrincipal principal) {
        Entreprise entreprise = entrepriseAccessService.verifierAcces(entrepriseId, principal);
        TypeTiers typeAttendu = request.type() == AccountingOperationType.AVOIR_VENTE
                ? TypeTiers.CLIENT : TypeTiers.FOURNISSEUR;
        Tiers tiers = chargerTiers(entrepriseId, request.tiersId(), typeAttendu);
        chargerExerciceOuvert(entrepriseId, request.date());
        ResultatRegle resultat = avoirRule.construire(entreprise, tiers, request);
        return assemblerEtSauvegarder(entreprise, request.type(), tiers,
                request.date(), request.libelle(), request.reference(), resultat);
    }

    /** Seule proposition sans tiers (voir AccountingOperationProposal.tiers, nullable). L'appelant
     * (InventaireService) a deja enregistre les mouvements de regularisation avant d'appeler cette
     * methode - ici on ne fait que construire et sauvegarder la proposition d'ecriture. */
    @Transactional
    public AccountingOperationProposalDto proposerRegularisationInventaire(UUID entrepriseId, Inventaire inventaire,
                                                                            UtilisateurPrincipal principal) {
        Entreprise entreprise = entrepriseAccessService.verifierAcces(entrepriseId, principal);
        chargerExerciceOuvert(entrepriseId, inventaire.getDate());
        ResultatRegle resultat = inventaireRegularisationRule.construire(entreprise, inventaire);
        String libelle = "Regularisation inventaire du " + inventaire.getDate();
        return assemblerEtSauvegarder(entreprise, AccountingOperationType.REGULARISATION_INVENTAIRE, null,
                inventaire.getDate(), libelle, null, resultat);
    }

    /** Facture fournisseur multi-lignes multi-natures : la generation de la facture EST deja la
     * confirmation humaine (meme discipline que FacturationService/FactureAvoirService/ClotureRule)
     * - propose et valide en une seule transaction, idempotent par sourceType/sourceId (une facture
     * regeneree/rejouee retrouve la meme ecriture au lieu d'en produire une seconde). AchatRule
     * reste le seul endroit qui construit des lignes d'achat - CommandeAchatService ne fait
     * qu'orchestrer (charger la commande, construire la requete, appeler cette methode). */
    @Transactional
    public AccountingOperationProposalDto proposerAchatEtValiderImmediatement(UUID entrepriseId,
            AchatMultiLigneOperationRequest request, UtilisateurPrincipal principal) {
        Entreprise entreprise = entrepriseAccessService.verifierAcces(entrepriseId, principal);
        Tiers fournisseur = chargerTiers(entrepriseId, request.fournisseurId(), TypeTiers.FOURNISSEUR);
        chargerExerciceOuvert(entrepriseId, request.date());
        ResultatRegle resultat = achatRule.construireMultiLigne(entreprise, fournisseur, request);
        return proposerEtValiderImmediatement(entrepriseId, AccountingOperationType.ACHAT, fournisseur,
                request.date(), request.libelle(), request.reference(), resultat,
                request.sourceType(), request.sourceId(), principal);
    }

    /**
     * Enchaine proposition + validation dans la meme transaction, pour les appelants dont l'action
     * de confirmation humaine existe deja au niveau du document metier (validation d'une facture,
     * d'un avoir, cloture d'un exercice) - la double confirmation propose/valide serait redondante
     * pour ces flux. Ne s'utilise jamais pour une regularisation/provision/depreciation : celles-ci
     * exigent toujours une validation humaine separee (voir proposerRegularisation et consorts).
     * Idempotent via sourceType/sourceId : un second appel pour la meme source retrouve la
     * proposition (et son ecriture) deja creee au lieu d'en generer une seconde.
     */
    @Transactional
    public AccountingOperationProposalDto proposerEtValiderImmediatement(UUID entrepriseId, AccountingOperationType type,
            Tiers tiers, LocalDate dateOperation, String libelle, String reference, ResultatRegle resultat,
            String sourceType, UUID sourceId, UtilisateurPrincipal principal) {
        Entreprise entreprise = entrepriseAccessService.verifierAcces(entrepriseId, principal);
        AccountingOperationProposalDto proposee = assemblerEtSauvegarder(entreprise, type, tiers, dateOperation,
                libelle, reference, resultat, sourceType, sourceId);

        if (proposee.statut().equals(StatutOperationProposal.APPROVED.name())) {
            return proposee;
        }
        if (!proposee.explications().isEmpty()) {
            throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY,
                    "Proposition incomplete : " + String.join("; ", proposee.explications()));
        }
        return valider(entrepriseId, proposee.id(), principal);
    }

    /**
     * Variante de {@link #proposerEtValiderImmediatement} reservee aux flux systeme entierement
     * automatises (mission AI Accounting, STEP 7) : aucun {@link UtilisateurPrincipal} interactif
     * n'existe a ce point (appel serveur-a-serveur depuis ai-service, deja authentifie par cle
     * interne, jamais par un utilisateur). Ne verifie que l'existence de l'entreprise (aucune
     * verification de role, puisqu'aucun utilisateur n'agit ici) et ne renseigne jamais
     * createdBy/validatedBy (laisses null) - meme discipline que
     * {@code ComptabilisationService.comptabiliserAutomatiquement} pour les postings automatiques
     * deja existants, jamais une identite humaine fabriquee. Reutilise integralement
     * {@link #assemblerEtSauvegarder} (deja independant du principal) : l'idempotence par
     * (entrepriseId, sourceType, sourceId, type) est donc strictement la meme que pour tout autre
     * appelant de ce moteur.
     */
    @Transactional
    public AccountingOperationProposalDto proposerEtValiderImmediatementSysteme(UUID entrepriseId, AccountingOperationType type,
            Tiers tiers, LocalDate dateOperation, String libelle, String reference, ResultatRegle resultat,
            String sourceType, UUID sourceId) {
        Entreprise entreprise = entrepriseRepository.findById(entrepriseId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Entreprise introuvable"));
        AccountingOperationProposalDto proposee = assemblerEtSauvegarder(entreprise, type, tiers, dateOperation,
                libelle, reference, resultat, sourceType, sourceId);

        if (proposee.statut().equals(StatutOperationProposal.APPROVED.name())) {
            return proposee; // idempotence : deja postee lors d'un appel precedent (meme source)
        }
        if (!proposee.explications().isEmpty()) {
            throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY,
                    "Proposition incomplete : " + String.join("; ", proposee.explications()));
        }
        return validerSysteme(entrepriseId, proposee.id());
    }

    /** Une regularisation exige toujours une validation humaine separee (jamais auto-validee, a la
     * difference de proposerEtValiderImmediatement) - meme discipline que l'inventaire. */
    @Transactional
    public AccountingOperationProposalDto proposerRegularisation(UUID entrepriseId, Regularisation regularisation,
                                                                  UtilisateurPrincipal principal) {
        Entreprise entreprise = entrepriseAccessService.verifierAcces(entrepriseId, principal);
        // La date de rattachement comptable est la fin de l'exercice de rattachement, pas
        // necessairement la date d'origine du document source (section 19 : independance des
        // exercices - une charge/un produit peut se rattacher economiquement a un exercice sans
        // que sa date de facturation d'origine y appartienne).
        LocalDate dateRattachement = regularisation.getExercice().getDateFin();
        chargerExerciceOuvert(entrepriseId, dateRattachement);
        ResultatRegle resultat = regularisationRule.construire(entreprise, regularisation);
        return assemblerEtSauvegarder(entreprise, AccountingOperationType.REGULARISATION, null,
                dateRattachement, regularisation.getLibelle(), null, resultat,
                "REGULARISATION", regularisation.getId());
    }

    /** Contre-passation d'une regularisation deja validee - jamais generee automatiquement, voir
     * RegularisationService.proposerReprise. */
    @Transactional
    public AccountingOperationProposalDto proposerReprise(UUID entrepriseId, Regularisation regularisation,
                                                           UtilisateurPrincipal principal) {
        Entreprise entreprise = entrepriseAccessService.verifierAcces(entrepriseId, principal);
        LocalDate dateReprise = LocalDate.now();
        chargerExerciceOuvert(entrepriseId, dateReprise);
        ResultatRegle resultat = regularisationRule.construireReprise(entreprise, regularisation);
        return assemblerEtSauvegarder(entreprise, AccountingOperationType.REGULARISATION_REPRISE, null,
                dateReprise, "Reprise - " + regularisation.getLibelle(), null, resultat,
                "REGULARISATION_REPRISE", regularisation.getId());
    }

    /** Une provision (dotation/reprise) exige toujours une validation humaine separee. */
    @Transactional
    public AccountingOperationProposalDto proposerProvision(UUID entrepriseId, Provision provision,
                                                             UtilisateurPrincipal principal) {
        Entreprise entreprise = entrepriseAccessService.verifierAcces(entrepriseId, principal);
        LocalDate dateRattachement = provision.getExercice().getDateFin();
        chargerExerciceOuvert(entrepriseId, dateRattachement);
        ResultatRegle resultat = provisionRule.construire(entreprise, provision);
        String libelle = provision.getNature() + (provision.getObjet() != null ? " - " + provision.getObjet() : "");
        return assemblerEtSauvegarder(entreprise, AccountingOperationType.PROVISION, null,
                dateRattachement, libelle, null, resultat, "PROVISION", provision.getId());
    }

    /** Une depreciation exige toujours une validation humaine separee. */
    @Transactional
    public AccountingOperationProposalDto proposerDepreciation(UUID entrepriseId, Depreciation depreciation,
                                                                UtilisateurPrincipal principal) {
        Entreprise entreprise = entrepriseAccessService.verifierAcces(entrepriseId, principal);
        LocalDate dateRattachement = depreciation.getExercice().getDateFin();
        chargerExerciceOuvert(entrepriseId, dateRattachement);
        ResultatRegle resultat = depreciationRule.construire(entreprise, depreciation);
        String libelle = "Depreciation " + depreciation.getObjetType().name().toLowerCase();
        return assemblerEtSauvegarder(entreprise, AccountingOperationType.DEPRECIATION, null,
                dateRattachement, libelle, null, resultat, "DEPRECIATION", depreciation.getId());
    }

    @Transactional(readOnly = true)
    public AccountingOperationProposalDto obtenir(UUID entrepriseId, UUID propositionId, UtilisateurPrincipal principal) {
        entrepriseAccessService.verifierAcces(entrepriseId, principal);
        return AccountingOperationProposalDto.from(chargerProposition(entrepriseId, propositionId));
    }

    @Transactional
    public AccountingOperationProposalDto valider(UUID entrepriseId, UUID propositionId, UtilisateurPrincipal principal) {
        entrepriseAccessService.verifierAcces(entrepriseId, principal);
        Utilisateur utilisateur = utilisateurRepository.getReferenceById(principal.getId());
        return validerInterne(entrepriseId, propositionId, utilisateur);
    }

    /**
     * Variante de {@link #valider} reservee aux flux systeme entierement automatises (mission AI
     * Accounting, STEP 7) - voir {@link #proposerEtValiderImmediatementSysteme} pour le contexte
     * complet. Ne verifie que l'existence de l'entreprise, jamais createdBy/validatedBy renseignes.
     */
    @Transactional
    public AccountingOperationProposalDto validerSysteme(UUID entrepriseId, UUID propositionId) {
        if (!entrepriseRepository.existsById(entrepriseId)) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Entreprise introuvable");
        }
        return validerInterne(entrepriseId, propositionId, null);
    }

    private AccountingOperationProposalDto validerInterne(UUID entrepriseId, UUID propositionId, Utilisateur utilisateur) {
        AccountingOperationProposal proposition = chargerProposition(entrepriseId, propositionId);

        if (proposition.getStatut() != StatutOperationProposal.PROPOSED) {
            throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY,
                    "Cette proposition n'est pas complete (" + proposition.getStatut()
                            + ") et ne peut pas etre validee telle quelle");
        }
        if (proposition.getJournal() == null) {
            throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY, "Aucun journal resolu pour cette proposition");
        }

        // Defense en profondeur : la configuration de l'entreprise a pu changer entre la
        // proposition et la validation (compte desactive entre-temps, par exemple).
        for (AccountingOperationProposalLigne ligne : proposition.getLignes()) {
            compteComptableValidationService.chargerCompteUtilisable(entrepriseId, ligne.getCompte().getId(), true);
        }
        ExerciceComptable exercice = chargerExerciceOuvert(entrepriseId, proposition.getDateOperation());

        EcritureComptable ecriture = new EcritureComptable();
        ecriture.setEntreprise(proposition.getEntreprise());
        ecriture.setExercice(exercice);
        ecriture.setJournal(proposition.getJournal());
        ecriture.setDateEcriture(proposition.getDateOperation());
        ecriture.setLibelle(proposition.getLibelle());
        ecriture.setCreatedBy(utilisateur);
        ecriture.setNumeroPiece(numeroPieceGenerator.genererNumeroPiece(proposition.getJournal()));

        for (AccountingOperationProposalLigne ligneProposition : proposition.getLignes()) {
            LigneEcriture ligne = ligneProposition.getDebit().signum() > 0
                    ? LigneEcriture.debit(ligneProposition.getCompte(), ligneProposition.getDebit(), ligneProposition.getLibelle())
                    : LigneEcriture.credit(ligneProposition.getCompte(), ligneProposition.getCredit(), ligneProposition.getLibelle());
            ligne.setTiers(ligneProposition.getTiers());
            ecriture.ajouterLigne(ligne);
        }

        if (!ecriture.estEquilibree()) {
            throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY,
                    "Ecriture desequilibree, validation impossible");
        }
        // L'humain a deja confirme au moment de "valider" la proposition (ou la policy systeme l'a
        // autorise, cf. validerSysteme) : l'ecriture est creee directement VALIDEE, comme le font
        // deja FacturationService/FactureAvoirService.
        ecriture.setStatut(StatutEcriture.VALIDEE);
        ecriture = ecritureComptableRepository.save(ecriture);

        proposition.setEcriture(ecriture);
        proposition.setStatut(StatutOperationProposal.APPROVED);
        proposition.setValidatedAt(OffsetDateTime.now());
        proposition.setValidatedBy(utilisateur);

        return AccountingOperationProposalDto.from(accountingOperationProposalRepository.save(proposition));
    }

    @Transactional
    public AccountingOperationProposalDto rejeter(UUID entrepriseId, UUID propositionId, String motif,
                                                   UtilisateurPrincipal principal) {
        entrepriseAccessService.verifierAcces(entrepriseId, principal);
        AccountingOperationProposal proposition = chargerProposition(entrepriseId, propositionId);

        if (proposition.getStatut() != StatutOperationProposal.PROPOSED
                && proposition.getStatut() != StatutOperationProposal.NEEDS_REVIEW) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Cette proposition a deja ete traitee");
        }

        proposition.setStatut(StatutOperationProposal.REJECTED);
        proposition.setRejectionReason(motif);
        proposition.setValidatedAt(OffsetDateTime.now());
        proposition.setValidatedBy(utilisateurRepository.getReferenceById(principal.getId()));

        return AccountingOperationProposalDto.from(accountingOperationProposalRepository.save(proposition));
    }

    private AccountingOperationProposalDto assemblerEtSauvegarder(Entreprise entreprise, AccountingOperationType type,
            Tiers tiers, LocalDate dateOperation, String libelle, String reference, ResultatRegle resultat) {
        return assemblerEtSauvegarder(entreprise, type, tiers, dateOperation, libelle, reference, resultat, null, null);
    }

    /** Verifie l'idempotence (section 24) avant de construire quoi que ce soit : si une proposition
     * non rejetee existe deja pour (entreprise, sourceType, sourceId, type), elle est renvoyee telle
     * quelle plutot que d'en creer une seconde - une facture (ou un exercice, une regularisation...)
     * validee/traitee deux fois ne doit jamais produire deux ecritures. */
    private AccountingOperationProposalDto assemblerEtSauvegarder(Entreprise entreprise, AccountingOperationType type,
            Tiers tiers, LocalDate dateOperation, String libelle, String reference, ResultatRegle resultat,
            String sourceType, UUID sourceId) {
        if (sourceType != null && sourceId != null) {
            AccountingOperationProposal existante = accountingOperationProposalRepository
                    .findByEntrepriseIdAndSourceTypeAndSourceIdAndType(entreprise.getId(), sourceType, sourceId, type)
                    .orElse(null);
            if (existante != null && existante.getStatut() != StatutOperationProposal.REJECTED) {
                return AccountingOperationProposalDto.from(existante);
            }
        }

        BigDecimal totalDebit = resultat.lignes().stream().map(AccountingOperationProposalLigne::getDebit)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        BigDecimal totalCredit = resultat.lignes().stream().map(AccountingOperationProposalLigne::getCredit)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        boolean equilibree = !resultat.lignes().isEmpty() && totalDebit.compareTo(totalCredit) == 0;

        List<String> explications = new java.util.ArrayList<>(resultat.explications());
        if (resultat.explications().isEmpty() && !equilibree) {
            // Ne devrait arriver que sur un bug interne d'une regle - jamais presente comme
            // equilibree si elle ne l'est pas reellement.
            explications.add("Les lignes proposees ne sont pas equilibrees (verification interne)");
        }

        Journal journal = journalResolver.resoudre(entreprise.getId(), resultat.typeJournalAttendu()).orElse(null);
        if (journal == null) {
            explications.add("Aucun journal " + resultat.typeJournalAttendu() + " configure pour cette entreprise");
        }

        AccountingOperationProposal proposition = new AccountingOperationProposal();
        proposition.setEntreprise(entreprise);
        proposition.setType(type);
        proposition.setTiers(tiers);
        proposition.setJournal(journal);
        proposition.setDateOperation(dateOperation);
        proposition.setLibelle(libelle);
        proposition.setReference(reference);
        proposition.setMontantTotalDebit(totalDebit);
        proposition.setMontantTotalCredit(totalCredit);
        proposition.setEquilibree(equilibree);
        proposition.setSourceType(sourceType);
        proposition.setSourceId(sourceId);
        proposition.setExplicationsJson(explications);
        proposition.setWarningsJson(resultat.warnings());
        proposition.setStatut(explications.isEmpty() ? StatutOperationProposal.PROPOSED : StatutOperationProposal.NEEDS_REVIEW);

        for (AccountingOperationProposalLigne ligne : resultat.lignes()) {
            proposition.ajouterLigne(ligne);
        }

        return AccountingOperationProposalDto.from(accountingOperationProposalRepository.save(proposition));
    }

    private Tiers chargerTiers(UUID entrepriseId, UUID tiersId, TypeTiers typeAttendu) {
        Tiers tiers = tiersRepository.findById(tiersId)
                .filter(t -> t.getEntreprise().getId().equals(entrepriseId))
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Tiers introuvable"));
        if (tiers.getType() != typeAttendu) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "Le tiers selectionne doit etre un " + typeAttendu.name().toLowerCase());
        }
        return tiers;
    }

    private ExerciceComptable chargerExerciceOuvert(UUID entrepriseId, LocalDate date) {
        ExerciceComptable exercice = exerciceComptableRepository.findByEntrepriseIdAndDate(entrepriseId, date)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY,
                        "Aucun exercice comptable ne couvre la date " + date));
        if (exercice.getStatut() != StatutExercice.OUVERT) {
            throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY, "L'exercice couvrant cette date est cloture");
        }
        return exercice;
    }

    private AccountingOperationProposal chargerProposition(UUID entrepriseId, UUID propositionId) {
        return accountingOperationProposalRepository.findById(propositionId)
                .filter(p -> p.getEntreprise().getId().equals(entrepriseId))
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Proposition introuvable"));
    }
}
