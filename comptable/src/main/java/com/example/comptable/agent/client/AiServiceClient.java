package com.example.comptable.agent.client;

import com.example.comptable.agent.fraude.AlerteFraude;
import com.example.comptable.agent.matching.dto.MatchingRequestDTO;
import com.example.comptable.agent.matching.dto.MatchingResponseDTO;
import com.example.comptable.agent.rapprochement.AppariementSemantique;
import com.example.comptable.agent.rapprochement.dto.RapprochementSemantiqueRequestDTO;
import com.example.comptable.agent.whatsapp.dto.AlerteFraudeDTO;
import com.example.comptable.copilot.dto.CopilotExplainRequestDTO;
import com.example.comptable.copilot.dto.CopilotExplainResponseDTO;
import com.example.comptable.document.domain.TypeDocument;
import com.example.comptable.document.ocr.OcrResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

import java.net.http.HttpClient;
import java.time.Duration;
import java.util.List;
import java.util.Optional;

/**
 * Point d'acces unique, cote Spring Boot, au microservice IA (ai-service, FastAPI) : extraction
 * VLM, analyse visuelle de fraude, rapprochement semantique, envoi de templates WhatsApp. Chaque
 * appel porte le header {@code X-Internal-Api-Key} attendu par ai-service (cf.
 * {@code app/core/security.py::require_internal_api_key}).
 * <p>
 * Toutes les methodes SAUF {@link #envoyerTemplate} degradent silencieusement (resultat "vide"/
 * "echec", jamais d'exception) en cas de panne du microservice : c'est la meme philosophie que le
 * code qu'elles remplacent ({@code VlmOcrExtractionService}, {@code AnalyseFraudeService},
 * {@code MatchingSemantiqueService} avant migration, qui absorbaient deja une panne de
 * ChatClient de la meme facon). {@link #envoyerTemplate} est le seul appel dont l'appelant
 * ({@code RelancePieceService}) a explicitement besoin de savoir qu'il a echoue (pour tracer un
 * statut ECHEC) : voir {@code WhatsAppCloudApiClient}, qui traduit une panne ici en
 * {@code WhatsAppEnvoiIndisponibleException}.
 */
@Component
public class AiServiceClient {

    private static final Logger log = LoggerFactory.getLogger(AiServiceClient.class);

    private final RestClient restClient;

    public AiServiceClient(RestClient.Builder restClientBuilder,
                            @Value("${ai-service.base-url:http://localhost:8000}") String baseUrl,
                            @Value("${ai-service.outbound-api-key:}") String apiKey,
                            @Value("${ai-service.connect-timeout-ms:5000}") long connectTimeoutMs,
                            @Value("${ai-service.read-timeout-ms:30000}") long readTimeoutMs) {
        HttpClient httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofMillis(connectTimeoutMs))
                // HTTP/1.1 force : le JDK HttpClient tente HTTP/2 (upgrade h2c) par defaut, que
                // uvicorn/h11 (HTTP/1.1 uniquement) ne gere pas proprement - decouvert en testant
                // reellement l'extraction VLM (agent.vlm.enabled=true) pour la premiere fois :
                // la requete multipart etait corrompue en transit ("Unsupported upgrade request" /
                // "Invalid HTTP request received" cote ai-service, 422 en retour), jamais visible
                // avant puisque ce flag est desactive par defaut et jamais teste en conditions
                // reelles jusqu'ici.
                .version(HttpClient.Version.HTTP_1_1)
                .build();
        JdkClientHttpRequestFactory requestFactory = new JdkClientHttpRequestFactory(httpClient);
        requestFactory.setReadTimeout(Duration.ofMillis(readTimeoutMs));

        this.restClient = restClientBuilder
                .baseUrl(baseUrl)
                .defaultHeader("X-Internal-Api-Key", apiKey)
                .requestFactory(requestFactory)
                .build();
    }

    public OcrResult extraire(byte[] contenu, String nomFichier, String contentType) {
        try {
            MultiValueMap<String, Object> corps = new LinkedMultiValueMap<>();
            corps.add("fichier", fichierPart(contenu, nomFichier, contentType));
            return restClient.post()
                    .uri("/api/v1/extraction/document")
                    .contentType(MediaType.MULTIPART_FORM_DATA)
                    .body(corps)
                    .retrieve()
                    .body(OcrResult.class);
        } catch (RestClientException e) {
            return OcrResult.echec("Extraction indisponible pour le moment (microservice IA : "
                    + e.getMessage() + ") ; saisie manuelle requise.");
        }
    }

    public List<AlerteFraude> analyserVisuellement(byte[] contenu, String nomFichier, String contentType,
                                                     TypeDocument typeDocument) {
        try {
            MultiValueMap<String, Object> corps = new LinkedMultiValueMap<>();
            corps.add("fichier", fichierPart(contenu, nomFichier, contentType));
            if (typeDocument != null) {
                corps.add("type_document", typeDocument.name());
            }
            AlerteFraudeDTO[] alertes = restClient.post()
                    .uri("/api/v1/fraude/analyse-visuelle")
                    .contentType(MediaType.MULTIPART_FORM_DATA)
                    .body(corps)
                    .retrieve()
                    .body(AlerteFraudeDTO[].class);
            return alertes == null ? List.of()
                    : List.of(alertes).stream().map(AlerteFraudeDTO::versAlerteFraude).toList();
        } catch (RestClientException e) {
            log.warn("Analyse visuelle de fraude indisponible pour le moment (microservice IA) : {}",
                    e.getMessage());
            return List.of();
        }
    }

    public List<AppariementSemantique> rapprocherSemantiquement(RapprochementSemantiqueRequestDTO requete) {
        try {
            AppariementSemantique[] appariements = restClient.post()
                    .uri("/api/v1/rapprochement/semantique")
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(requete)
                    .retrieve()
                    .body(AppariementSemantique[].class);
            return appariements == null ? List.of() : List.of(appariements);
        } catch (RestClientException e) {
            log.warn("Rapprochement semantique indisponible pour le moment (microservice IA) : {}",
                    e.getMessage());
            return List.of();
        }
    }

    /**
     * Universal Matching Engine (mission 2) : degrade vers {@link Optional#empty()} en cas de
     * panne (timeout, erreur reseau, reponse invalide) - c'est a l'appelant
     * ({@code MatchDecisionEngine}) de traduire cette absence en decision {@code AI_UNAVAILABLE}
     * et de retomber sur le rapprochement manuel/existant, jamais de laisser une exception
     * remonter jusqu'a l'import du releve bancaire.
     */
    public Optional<MatchingResponseDTO> analyzeMatching(MatchingRequestDTO requete) {
        try {
            MatchingResponseDTO reponse = restClient.post()
                    .uri("/api/v1/matching/analyze")
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(requete)
                    .retrieve()
                    .body(MatchingResponseDTO.class);
            if (reponse == null) {
                log.warn("Universal Matching Engine : reponse vide pour la source {}", requete.source().id());
                return Optional.empty();
            }
            return Optional.of(reponse);
        } catch (RestClientException e) {
            log.warn("Universal Matching Engine indisponible pour le moment (source {}) : {}",
                    requete.source().id(), e.getMessage());
            return Optional.empty();
        }
    }

    /** Copilot Evidence-First (mission P1) : degrade vers une explication generique cote appelant
     * si le microservice est indisponible - les faits Java restent affichables sans mise en mots
     * IA (voir {@code CopilotService.repondre}). */
    public Optional<CopilotExplainResponseDTO> copilotExplain(CopilotExplainRequestDTO requete) {
        try {
            CopilotExplainResponseDTO reponse = restClient.post()
                    .uri("/api/v1/copilot/explain")
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(requete)
                    .retrieve()
                    .body(CopilotExplainResponseDTO.class);
            return Optional.ofNullable(reponse);
        } catch (RestClientException e) {
            log.warn("Copilot indisponible pour le moment (microservice IA) : {}", e.getMessage());
            return Optional.empty();
        }
    }

    /** Contrairement aux methodes ci-dessus, laisse volontairement {@link RestClientException}
     * se propager : voir la javadoc de classe. */
    public String envoyerTemplate(String numeroDestinataire, String nomTemplate, String langue,
                                   List<String> parametresCorps) {
        SendTemplateRequest requete = new SendTemplateRequest(numeroDestinataire, nomTemplate, langue,
                parametresCorps);
        SendTemplateResponse reponse = restClient.post()
                .uri("/api/v1/whatsapp/send-template")
                .contentType(MediaType.APPLICATION_JSON)
                .body(requete)
                .retrieve()
                .body(SendTemplateResponse.class);
        if (reponse == null || reponse.wamid() == null || reponse.wamid().isBlank()) {
            throw new RestClientException("Reponse d'ai-service sans wamid pour " + numeroDestinataire);
        }
        return reponse.wamid();
    }

    private Object fichierPart(byte[] contenu, String nomFichier, String contentType) {
        ByteArrayResource resource = new ByteArrayResource(contenu) {
            @Override
            public String getFilename() {
                return nomFichier;
            }
        };
        HttpHeaders headers = new HttpHeaders();
        if (contentType != null && !contentType.isBlank()) {
            headers.setContentType(MediaType.parseMediaType(contentType));
        }
        return new HttpEntity<>(resource, headers);
    }

    /** Miroir de ai-service/app/schemas/whatsapp.py::SendTemplateRequest (memes noms de champs,
     * deja camelCase cote Java). */
    private record SendTemplateRequest(String numeroDestinataire, String nomTemplate, String langue,
                                        List<String> parametresCorps) {
    }

    private record SendTemplateResponse(String wamid) {
    }
}
