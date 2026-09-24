package com.example.comptable.agent.accounting;

import com.example.comptable.agent.accounting.web.dto.AiAccountingProposalLineRequest;
import com.example.comptable.agent.accounting.web.dto.AiAccountingValidationResponseDto;
import com.example.comptable.agent.accounting.web.dto.ValidateAiAccountingProposalRequest;
import com.example.comptable.comptabilite.parametrage.AccountContext;
import com.example.comptable.comptabilite.parametrage.AccountingAccountResolver;
import com.example.comptable.comptabilite.parametrage.ResolvedAccount;
import com.example.comptable.comptabilite.parametrage.web.dto.ResolvedAccountDto;
import com.example.comptable.entreprise.repository.EntrepriseRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;

/**
 * Validation d'un brouillon de proposition comptable produit par le workflow Python AI Accounting
 * (STEP 4 du plan d'implementation) - ne cree, ne modifie et ne poste jamais rien. Chaque ligne
 * est resolue via {@link AccountingAccountResolver} (deja existant, jamais duplique) exactement
 * comme le font deja AchatRule/FacturationService/ReglementRule/ImmobilisationService/ClotureRule ;
 * aucun nouveau moteur comptable n'est introduit ici. Le posting reel
 * ({@code AccountingOperationEngine.proposerXxx}) reste hors perimetre de cette etape.
 */
@Service
@RequiredArgsConstructor
public class AiAccountingProposalValidationService {

    /** Meme tolerance que {@code AchatRule.TOLERANCE_TTC} - coherence arithmetique lignes/totaux,
     * jamais une egalite stricte (arrondis). */
    private static final BigDecimal TOLERANCE_MONTANT = new BigDecimal("1.00");

    private final EntrepriseRepository entrepriseRepository;
    private final AccountingAccountResolver accountingAccountResolver;

    public AiAccountingValidationResponseDto valider(ValidateAiAccountingProposalRequest request) {
        if (!entrepriseRepository.existsById(request.companyId())) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Entreprise introuvable");
        }

        List<ResolvedAccountDto> comptesResolus = new ArrayList<>();
        List<String> erreurs = new ArrayList<>();
        boolean toutesLignesResolues = true;

        for (AiAccountingProposalLineRequest ligne : request.lines()) {
            ResolvedAccount resolu = accountingAccountResolver.resolve(
                    request.companyId(), ligne.purpose(), AccountContext.vide(), null);
            comptesResolus.add(ResolvedAccountDto.from(resolu));
            if (!resolu.isResolved()) {
                toutesLignesResolues = false;
                erreurs.add("Ligne '" + ligne.designation() + "' (" + ligne.purpose() + ") : " + resolu.explication());
            }
        }

        List<String> avertissements = new ArrayList<>();
        BigDecimal sommeLignesHt = request.lines().stream()
                .map(AiAccountingProposalLineRequest::montantHt)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        BigDecimal tvaAttendue = request.montantTva() != null ? request.montantTva() : BigDecimal.ZERO;
        BigDecimal ttcCalcule = sommeLignesHt.add(tvaAttendue);
        if (ttcCalcule.subtract(request.montantTtc()).abs().compareTo(TOLERANCE_MONTANT) > 0) {
            avertissements.add("Le TTC declare (" + request.montantTtc() + ") differe du TTC recalcule a partir des lignes ("
                    + ttcCalcule + ") de plus de la tolerance (" + TOLERANCE_MONTANT + ").");
        }

        boolean requiresReview = !toutesLignesResolues || !avertissements.isEmpty();
        return new AiAccountingValidationResponseDto(toutesLignesResolues, requiresReview, comptesResolus, erreurs, avertissements);
    }
}
