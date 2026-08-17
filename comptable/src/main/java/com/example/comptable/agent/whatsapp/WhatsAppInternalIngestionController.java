package com.example.comptable.agent.whatsapp;

import com.example.comptable.agent.whatsapp.dto.WhatsAppDocumentPayloadDTO;
import com.example.comptable.document.web.dto.DocumentDto;
import jakarta.validation.Valid;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.server.ResponseStatusException;

import java.io.IOException;
import java.util.Optional;

/**
 * Point d'entree interne appele exclusivement par le microservice IA (ai-service, FastAPI) une
 * fois qu'une piece WhatsApp a ete telechargee, extraite (VLM) et analysee visuellement pour la
 * fraude - jamais par un utilisateur ni par Meta. Authentifie par cle partagee
 * (X-Internal-Api-Key) plutot que par JWT applicatif : aucun utilisateur n'est connecte de ce
 * cote, meme principe que le webhook Meta desormais recu directement par ai-service (verification
 * de signature HMAC cote Python, cf. app/core/security.py::verify_whatsapp_signature). Exempte
 * du filtre JWT dans SecurityConfig ; la securite de cet endpoint repose entierement sur la
 * verification de cle ci-dessous.
 * <p>
 * Requete multipart/form-data a deux parties : {@code fichier} (le binaire original) et
 * {@code metadata} (JSON, Content-Type: application/json explicite cote appelant - cf.
 * ai-service/app/services/core_client.py) qui se bind automatiquement en
 * {@link WhatsAppDocumentPayloadDTO} via Jackson, valide par {@code @Valid}.
 */
@RestController
@RequestMapping("/api/v1/internal/whatsapp/documents")
public class WhatsAppInternalIngestionController {

    private static final Logger log = LoggerFactory.getLogger(WhatsAppInternalIngestionController.class);

    private final WhatsAppIngestionService ingestionService;
    private final String cleApiInterne;

    public WhatsAppInternalIngestionController(WhatsAppIngestionService ingestionService,
                                                 @Value("${ai-service.api-key:}") String cleApiInterne) {
        this.ingestionService = ingestionService;
        this.cleApiInterne = cleApiInterne;
    }

    @PostMapping(consumes = "multipart/form-data")
    public ResponseEntity<DocumentDto> ingerer(
            @RequestHeader(value = "X-Internal-Api-Key", required = false) String cleRecue,
            @RequestPart("fichier") MultipartFile fichier,
            @Valid @RequestPart("metadata") WhatsAppDocumentPayloadDTO metadata) throws IOException {

        if (cleApiInterne.isBlank() || !cleApiInterne.equals(cleRecue)) {
            log.warn("Appel a l'ingestion interne WhatsApp refuse : cle API interne absente ou invalide");
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();
        }
        if (fichier.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Fichier vide");
        }

        Optional<DocumentDto> document = ingestionService.ingererAvecExtractionExterne(
                metadata, fichier.getBytes(), fichier.getContentType());

        // Optional.empty() : message deja traite (idempotence) ou telephone non resolu a une
        // entreprise - dans les deux cas, ai-service n'a rien a rejouer (log cote WhatsAppIngestionService
        // suffit), donc 202 plutot qu'une erreur qui declencherait une remontee d'exception inutile.
        return document
                .map(ResponseEntity::ok)
                .orElseGet(() -> ResponseEntity.status(HttpStatus.ACCEPTED).build());
    }
}
