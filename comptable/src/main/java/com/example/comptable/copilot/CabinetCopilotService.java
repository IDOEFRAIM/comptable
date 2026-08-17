package com.example.comptable.copilot;

import com.example.comptable.cabinet.CabinetCockpitService;
import com.example.comptable.cabinet.InboxService;
import com.example.comptable.cabinet.web.dto.CabinetCockpitDto;
import com.example.comptable.cabinet.web.dto.ClientHealthDto;
import com.example.comptable.cabinet.web.dto.RisqueClientDto;
import com.example.comptable.cabinet.web.dto.WorkloadDto;
import com.example.comptable.copilot.domain.IntentCopilotCabinet;
import com.example.comptable.copilot.web.dto.CopilotResponseDto;
import com.example.comptable.copilot.web.dto.EvidenceRefDto;
import com.example.comptable.security.UtilisateurPrincipal;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Copilot Evidence-First niveau cabinet (mission P1, section 40) : meme pipeline que
 * {@link CopilotService} (classification deterministe -&gt; donnees reelles -&gt; LLM pour la mise
 * en mots uniquement) mais sans {@code entrepriseId} - le perimetre est le tenant du principal.
 * Reutilise integralement {@link CabinetCockpitService} et {@link InboxService} : aucune nouvelle
 * agregation cross-entreprise n'est calculee ici.
 */
@Service
public class CabinetCopilotService {

    private static final int MAX_ITEMS = 5;

    private final CabinetCockpitService cabinetCockpitService;
    private final InboxService inboxService;
    private final CopilotExplainer copilotExplainer;
    private final CopilotTelemetryRecorder telemetryRecorder;

    public CabinetCopilotService(CabinetCockpitService cabinetCockpitService, InboxService inboxService,
                                  CopilotExplainer copilotExplainer, CopilotTelemetryRecorder telemetryRecorder) {
        this.cabinetCockpitService = cabinetCockpitService;
        this.inboxService = inboxService;
        this.copilotExplainer = copilotExplainer;
        this.telemetryRecorder = telemetryRecorder;
    }

    @Transactional(readOnly = true)
    public CopilotResponseDto repondre(String question, UtilisateurPrincipal principal) {
        long debut = System.currentTimeMillis();
        IntentCopilotCabinet intent = CabinetIntentClassifier.classifier(question);
        if (intent == IntentCopilotCabinet.INCONNU) {
            CopilotResponseDto reponse = reponseIntentInconnue();
            UUID queryId = enregistrerTelemetrie(principal, question, "INCONNU", reponse, System.currentTimeMillis() - debut);
            return avecQueryId(reponse, queryId);
        }

        CabinetCockpitDto cockpit = cabinetCockpitService.generer(principal);
        FactsEtPreuves donnees = collecter(intent, cockpit, principal);
        String explication = copilotExplainer.expliquer(question, intent.name(), donnees.facts());

        CopilotResponseDto reponse = new CopilotResponseDto(explication, donnees.confidence(), donnees.evidence(), donnees.recommendedActions(), null);
        UUID queryId = enregistrerTelemetrie(principal, question, intent.name(), reponse, System.currentTimeMillis() - debut);
        return avecQueryId(reponse, queryId);
    }

    private UUID enregistrerTelemetrie(UtilisateurPrincipal principal, String question, String intent,
                                        CopilotResponseDto reponse, long latenceMs) {
        return telemetryRecorder.enregistrer(principal.getTenantId(), null, principal.getId(), "CABINET", question,
                intent, reponse.confidence(), reponse.evidence().size(), reponse.confidence() == 0.0, latenceMs);
    }

    private static CopilotResponseDto avecQueryId(CopilotResponseDto reponse, UUID queryId) {
        return new CopilotResponseDto(reponse.answer(), reponse.confidence(), reponse.evidence(),
                reponse.recommendedActions(), queryId);
    }

    private FactsEtPreuves collecter(IntentCopilotCabinet intent, CabinetCockpitDto cockpit, UtilisateurPrincipal principal) {
        return switch (intent) {
            case CLIENTS_BLOQUES -> collecterClientsBloques(cockpit);
            case DEMANDES_EN_RETARD -> collecterDemandesEnRetard(cockpit);
            case BACKLOG_COLLABORATEUR -> collecterBacklog(principal);
            case CLOTURES_A_RISQUE -> collecterCloturesARisque(cockpit);
            case ANOMALIES_IMPORTANTES -> collecterAnomalies(cockpit);
            case INCONNU -> throw new IllegalStateException("INCONNU deja intercepte avant collecte");
        };
    }

    private FactsEtPreuves collecterClientsBloques(CabinetCockpitDto cockpit) {
        List<ClientHealthDto> bloques = cockpit.clients().stream()
                .filter(c -> "BLOQUE".equals(c.statut()))
                .limit(MAX_ITEMS)
                .toList();

        Map<String, Object> facts = new LinkedHashMap<>();
        facts.put("nombreClientsBloques", cockpit.clientsBloques());
        facts.put("clients", bloques.stream().map(ClientHealthDto::raisonSociale).toList());

        List<EvidenceRefDto> evidence = bloques.stream()
                .map(c -> new EvidenceRefDto("ENTREPRISE", c.entrepriseId().toString()))
                .toList();

        return new FactsEtPreuves(facts, evidence, List.of(), cockpit.clientsBloques() == 0 ? 0.95 : 0.9);
    }

    private FactsEtPreuves collecterDemandesEnRetard(CabinetCockpitDto cockpit) {
        Map<String, Object> facts = new LinkedHashMap<>();
        facts.put("nombreDemandesEnRetard", cockpit.demandesEnRetard());

        return new FactsEtPreuves(facts, List.of(), List.of(), cockpit.demandesEnRetard() == 0 ? 0.9 : 0.7);
    }

    private FactsEtPreuves collecterBacklog(UtilisateurPrincipal principal) {
        List<WorkloadDto> charge = inboxService.chargeParCollaborateur(principal).stream().limit(MAX_ITEMS).toList();

        Map<String, Object> facts = new LinkedHashMap<>();
        facts.put("charge", charge.stream()
                .map(w -> w.collaborateurNom() + " : " + w.totalTaches() + " taches (" + w.tachesCritiques() + " critiques)")
                .toList());

        return new FactsEtPreuves(facts, List.of(), List.of(), charge.isEmpty() ? 0.9 : 0.85);
    }

    private FactsEtPreuves collecterCloturesARisque(CabinetCockpitDto cockpit) {
        List<ClientHealthDto> aRisque = cockpit.clients().stream()
                .filter(c -> !"PRET".equals(c.statut()))
                .limit(MAX_ITEMS)
                .toList();

        Map<String, Object> facts = new LinkedHashMap<>();
        facts.put("nombreCloturesARisque", cockpit.cloturesARisque());
        facts.put("clientsASurveiller", aRisque.stream().map(ClientHealthDto::raisonSociale).toList());

        List<EvidenceRefDto> evidence = aRisque.stream()
                .map(c -> new EvidenceRefDto("ENTREPRISE", c.entrepriseId().toString()))
                .toList();

        return new FactsEtPreuves(facts, evidence, List.of(), cockpit.cloturesARisque() == 0 ? 0.9 : 0.75);
    }

    private FactsEtPreuves collecterAnomalies(CabinetCockpitDto cockpit) {
        List<RisqueClientDto> top = cockpit.topRisques().stream().limit(MAX_ITEMS).toList();

        Map<String, Object> facts = new LinkedHashMap<>();
        facts.put("topAnomalies", top.stream()
                .map(r -> r.raisonSociale() + " : " + r.cause() + " (" + r.severite() + ")")
                .toList());

        List<EvidenceRefDto> evidence = top.stream()
                .map(r -> new EvidenceRefDto("ENTREPRISE", r.entrepriseId().toString()))
                .toList();

        return new FactsEtPreuves(facts, evidence, List.of(), top.isEmpty() ? 0.95 : 0.9);
    }

    private CopilotResponseDto reponseIntentInconnue() {
        return new CopilotResponseDto(
                "Je ne peux repondre qu'a un jeu de questions structurees niveau cabinet pour le moment : "
                        + "quels clients sont bloques, les demandes en retard, la charge par collaborateur, "
                        + "les clotures a risque, ou les anomalies les plus importantes.",
                0.0, List.of(), List.of(), null);
    }

    private record FactsEtPreuves(Map<String, Object> facts, List<EvidenceRefDto> evidence,
                                   List<String> recommendedActions, double confidence) {
    }
}
