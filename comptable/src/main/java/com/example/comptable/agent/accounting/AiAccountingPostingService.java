package com.example.comptable.agent.accounting;

import com.example.comptable.agent.accounting.web.dto.AiAccountingPostingResponseDto;
import com.example.comptable.agent.accounting.web.dto.AiAccountingProposalLineRequest;
import com.example.comptable.agent.accounting.web.dto.AiAccountingValidationResponseDto;
import com.example.comptable.agent.accounting.web.dto.PostAiAccountingProposalRequest;
import com.example.comptable.agent.accounting.web.dto.ValidateAiAccountingProposalRequest;
import com.example.comptable.comptabilite.domain.CompteComptable;
import com.example.comptable.comptabilite.operation.common.AccountingOperationEngine;
import com.example.comptable.comptabilite.operation.common.AccountingOperationType;
import com.example.comptable.comptabilite.operation.common.ResultatRegle;
import com.example.comptable.comptabilite.operation.common.domain.AccountingOperationProposalLigne;
import com.example.comptable.comptabilite.operation.common.web.dto.AccountingOperationProposalDto;
import com.example.comptable.comptabilite.domain.TypeJournal;
import com.example.comptable.comptabilite.parametrage.AccountContext;
import com.example.comptable.comptabilite.parametrage.AccountingAccountResolver;
import com.example.comptable.comptabilite.parametrage.ResolvedAccount;
import com.example.comptable.comptabilite.parametrage.domain.AccountPurpose;
import com.example.comptable.comptabilite.repository.EcritureComptableRepository;
import com.example.comptable.comptabilite.service.CompteComptableValidationService;
import com.example.comptable.comptabilite.domain.EcritureComptable;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;

/**
 * Posting reel d'une proposition comptable AI Accounting (STEP 7 du plan d'implementation) -
 * Controller -&gt; Service -&gt; {@link AccountingOperationEngine} -&gt; Database, exactement comme
 * demande. N'implemente aucune logique de posting propre : construit un {@link ResultatRegle}
 * (memes briques que AchatRule - resolution de comptes via {@link AccountingAccountResolver},
 * lignes via {@link AccountingOperationProposalLigne#debit}/{@code credit}) puis delegue
 * integralement a {@code AccountingOperationEngine.proposerEtValiderImmediatementSysteme}, qui est
 * l'autorite finale de l'idempotence (source_type/source_id/type). Jamais
 * {@code ComptabilisationService}, jamais un deuxieme moteur, jamais un troisieme modele de
 * proposition.
 *
 * <p>Limite assumee et documentee : aucun {@code Tiers} (fournisseur) reel n'est identifie a ce
 * stade du workflow IA (seul un {@link AccountPurpose#FOURNISSEUR} - un compte racine - est
 * connu, jamais un fournisseur precis) ; l'ecriture est donc postee sans {@code Tiers} associe
 * (tiers=null, identique au chemin {@code proposerRegularisationInventaire} deja existant), et
 * {@code createdBy}/{@code validatedBy} restent null (aucun utilisateur interactif n'existe pour
 * cet appel serveur-a-serveur - meme discipline que
 * {@code ComptabilisationService.comptabiliserAutomatiquement} pour les postings automatiques
 * deja existants, jamais une identite humaine fabriquee).
 */
@Service
@RequiredArgsConstructor
public class AiAccountingPostingService {

    private static final String SOURCE_TYPE = "AI_ACCOUNTING_PROPOSAL";
    private static final BigDecimal TOLERANCE_MONTANT = new BigDecimal("1.00");

    private final AiAccountingProposalValidationService validationService;
    private final AccountingAccountResolver accountingAccountResolver;
    private final CompteComptableValidationService compteComptableValidationService;
    private final AccountingOperationEngine accountingOperationEngine;
    private final EcritureComptableRepository ecritureComptableRepository;

    public AiAccountingPostingResponseDto poster(PostAiAccountingProposalRequest request) {
        ValidateAiAccountingProposalRequest requeteValidation = new ValidateAiAccountingProposalRequest(
                request.workflowId(), request.documentId(), request.companyId(), request.lines(),
                request.montantTva(), request.montantTtc());
        AiAccountingValidationResponseDto validation = validationService.valider(requeteValidation);

        if (!validation.valid()) {
            return new AiAccountingPostingResponseDto(false, null, null, null, null, null, validation.errors());
        }

        List<AccountingOperationProposalLigne> lignes = new ArrayList<>();
        List<String> erreurs = new ArrayList<>();

        for (AiAccountingProposalLineRequest ligneRequete : request.lines()) {
            ResolvedAccount resolu = accountingAccountResolver.resolve(
                    request.companyId(), ligneRequete.purpose(), AccountContext.vide(), null);
            if (!resolu.isResolved()) {
                erreurs.add("Ligne '" + ligneRequete.designation() + "' (" + ligneRequete.purpose() + ") : " + resolu.explication());
                continue;
            }
            CompteComptable compte = compteComptableValidationService.chargerCompteUtilisable(request.companyId(), resolu.compteId(), true);
            lignes.add(AccountingOperationProposalLigne.debit(compte, ligneRequete.montantHt(), ligneRequete.designation()));
        }

        BigDecimal montantTva = request.montantTva() != null ? request.montantTva() : BigDecimal.ZERO;
        if (montantTva.signum() > 0) {
            ResolvedAccount resoluTva = accountingAccountResolver.resolve(
                    request.companyId(), AccountPurpose.TVA_RECUPERABLE, AccountContext.vide(), null);
            if (!resoluTva.isResolved()) {
                erreurs.add("Compte TVA deductible non configure : " + resoluTva.explication());
            } else {
                CompteComptable compteTva = compteComptableValidationService.chargerCompteUtilisable(request.companyId(), resoluTva.compteId(), true);
                lignes.add(AccountingOperationProposalLigne.debit(compteTva, montantTva, "TVA deductible - " + request.libelle()));
            }
        }

        ResolvedAccount resoluFournisseur = accountingAccountResolver.resolve(
                request.companyId(), AccountPurpose.FOURNISSEUR, AccountContext.vide(), null);
        if (!resoluFournisseur.isResolved()) {
            erreurs.add("Compte fournisseur non configure : " + resoluFournisseur.explication());
        } else {
            CompteComptable compteFournisseur = compteComptableValidationService.chargerCompteUtilisable(request.companyId(), resoluFournisseur.compteId(), true);
            lignes.add(AccountingOperationProposalLigne.credit(compteFournisseur, request.montantTtc(), request.libelle()));
        }

        if (!erreurs.isEmpty()) {
            return new AiAccountingPostingResponseDto(false, null, null, null, null, null, erreurs);
        }

        BigDecimal sommeDebit = lignes.stream()
                .filter(l -> l.getDebit().signum() > 0)
                .map(AccountingOperationProposalLigne::getDebit)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        if (sommeDebit.subtract(request.montantTtc()).abs().compareTo(TOLERANCE_MONTANT) > 0) {
            erreurs.add("Le TTC declare (" + request.montantTtc() + ") differe de la somme des debits resolus (" + sommeDebit + ").");
            return new AiAccountingPostingResponseDto(false, null, null, null, null, null, erreurs);
        }

        ResultatRegle resultat = new ResultatRegle(TypeJournal.ACHATS, lignes, List.of(), List.of());

        AccountingOperationProposalDto proposition = accountingOperationEngine.proposerEtValiderImmediatementSysteme(
                request.companyId(), AccountingOperationType.ACHAT, null, request.datePiece(), request.libelle(),
                request.documentId().toString(), resultat, SOURCE_TYPE, request.documentId());

        String numeroPiece = proposition.ecritureId() != null
                ? ecritureComptableRepository.findById(proposition.ecritureId()).map(EcritureComptable::getNumeroPiece).orElse(null)
                : null;

        return new AiAccountingPostingResponseDto(
                true, proposition.id(), proposition.ecritureId(), numeroPiece, proposition.validatedAt(),
                proposition.journalCode(), List.of());
    }
}
