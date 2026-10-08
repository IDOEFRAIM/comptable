package com.example.comptable.controle;

import com.example.comptable.agent.finding.FindingService;
import com.example.comptable.comptabilite.operation.common.AccountingOperationType;
import com.example.comptable.comptabilite.operation.common.AccountingSource;
import com.example.comptable.comptabilite.parametrage.AccountResolutionCode;
import com.example.comptable.entreprise.domain.Entreprise;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Publie en Finding les refus d'imputation du moteur comptable ({@code ACCOUNT_NOT_CONFIGURED},
 * {@code ACCOUNT_AMBIGUOUS}, {@code ACCOUNT_INVALID}). Le flux « proposer + valider » refuse en 422 et
 * annule sa transaction : le constat est donc ecrit en transaction INDEPENDANTE (comme l'audit du refus)
 * pour survivre a ce rollback. Quand la meme cause comptable aboutit plus tard, le constat se ferme
 * automatiquement ({@link #resoudre}). Une operation saisie sans cause metier (source nulle) n'est pas
 * publiee : l'utilisateur a deja la reponse en direct.
 */
@Component
public class RefusComptablePublisher {

    private final ControlResultPublisher publisher;
    private final FindingService findingService;

    public RefusComptablePublisher(ControlResultPublisher publisher, FindingService findingService) {
        this.publisher = publisher;
        this.findingService = findingService;
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void publier(Entreprise entreprise, AccountingSource source, AccountingOperationType type, List<String> explications,
                        List<AccountResolutionCode> codes) {
        if (source == null || AccountingSource.OPERATION_SAISIE.equals(source.type())) {
            return;
        }
        Map<String, List<String>> parCode = new LinkedHashMap<>();
        // Le code machine vient de la resolution de compte ; le texte n'est jamais interprete.
        for (int i = 0; i < explications.size() && i < codes.size(); i++) {
            if (codes.get(i) != null) {
                parCode.computeIfAbsent(codes.get(i).controlCode(), c -> new ArrayList<>()).add(explications.get(i));
            }
        }
        parCode.forEach((code, textes) -> {
            ControlResult constat = ControlResult.ouvert(code, ControlCategory.ACCOUNTING,
                    code.equals(ControlCodes.ACCOUNT_NOT_CONFIGURED) ? ControlSeverity.WARNING : ControlSeverity.ERROR,
                    entreprise.getId(), source.type(), source.id().toString(),
                    titre(code, source),
                    String.join(" ", textes),
                    List.of(), Map.of("typeOperation", type.name(), "evenement", source.event()),
                    "Configurer ou corriger le compte concerne dans le parametrage comptable, puis recomptabiliser.");
            publisher.publier(entreprise, constat, ControlCodes.typeFinding(code));
        });
    }

    /** La cause a abouti : ferme les constats d'imputation ouverts sur cette source. */
    public void resoudre(java.util.UUID entrepriseId, String sourceType, java.util.UUID sourceId) {
        for (String code : List.of(ControlCodes.ACCOUNT_NOT_CONFIGURED, ControlCodes.ACCOUNT_AMBIGUOUS, ControlCodes.ACCOUNT_INVALID)) {
            findingService.resoudreParControle(entrepriseId, code, sourceType, sourceId.toString());
        }
    }

    private static String titre(String code, AccountingSource source) {
        String objet = source.type() + " " + source.id().toString().substring(0, 8);
        return switch (code) {
            case ControlCodes.ACCOUNT_AMBIGUOUS -> "Imputation ambigue : " + objet;
            case ControlCodes.ACCOUNT_INVALID -> "Compte d'imputation invalide : " + objet;
            default -> "Compte non configure : " + objet;
        };
    }
}
