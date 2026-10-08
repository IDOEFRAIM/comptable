package com.example.comptable.common.web;

import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.web.HttpMediaTypeNotAcceptableException;
import org.springframework.web.HttpMediaTypeNotSupportedException;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.multipart.MaxUploadSizeExceededException;
import org.springframework.web.servlet.resource.NoResourceFoundException;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;

/**
 * Format d'erreur unique de l'API : {@code {code, message, fieldErrors, correlationId}}. Les messages métier des {@link ResponseStatusException} sont conservés (ils s'adressent à l'utilisateur) ; toute autre
 * exception devient un message générique : jamais de SQL, de trace, de nom de table ni d'exception interne côté client. Le détail technique reste dans le journal, retrouvable par l'identifiant de corrélation.
 */
@RestControllerAdvice
public class ApiExceptionHandler {

    private static final Logger LOG = LoggerFactory.getLogger(ApiExceptionHandler.class);

    public record FieldError(String field, String message) {
    }

    public record ApiError(String code, String message, List<FieldError> fieldErrors, String correlationId) {
    }

    private static ResponseEntity<ApiError> reponse(HttpStatusCode statut, String code, String message, List<FieldError> champs) {
        return ResponseEntity.status(statut).body(new ApiError(code, message, champs == null ? List.of() : champs, MDC.get(CorrelationIdFilter.CLE_MDC)));
    }

    private static String codeDe(HttpStatusCode statut) {
        HttpStatus s = HttpStatus.resolve(statut.value());
        return s == null ? "ERREUR" : s.name();
    }

    @ExceptionHandler(ResponseStatusException.class)
    ResponseEntity<ApiError> statut(ResponseStatusException e) {
        String message = e.getReason() == null || e.getReason().isBlank() ? HttpStatus.valueOf(e.getStatusCode().value()).getReasonPhrase() : e.getReason();
        if (e.getStatusCode().is5xxServerError()) {
            LOG.error("Erreur {} : {}", e.getStatusCode().value(), message, e);
            return reponse(e.getStatusCode(), "INTERNAL_ERROR", "Une erreur interne est survenue. Communiquez l'identifiant de corrélation au support.", null);
        }
        return reponse(e.getStatusCode(), codeDe(e.getStatusCode()), message, null);
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    ResponseEntity<ApiError> validation(MethodArgumentNotValidException e) {
        List<FieldError> champs = e.getBindingResult().getFieldErrors().stream().map(f -> new FieldError(f.getField(), f.getDefaultMessage() == null ? "invalide" : f.getDefaultMessage())).toList();
        return reponse(HttpStatus.BAD_REQUEST, "VALIDATION_ERROR", "Certaines informations sont invalides ou manquantes.", champs);
    }

    @ExceptionHandler({HttpMessageNotReadableException.class, MissingServletRequestParameterException.class, MethodArgumentTypeMismatchException.class})
    ResponseEntity<ApiError> requeteIllisible(Exception e) {
        return reponse(HttpStatus.BAD_REQUEST, "BAD_REQUEST", "La requête est mal formée.", null);
    }

    @ExceptionHandler(HttpRequestMethodNotSupportedException.class)
    ResponseEntity<ApiError> methode(HttpRequestMethodNotSupportedException e) {
        return reponse(HttpStatus.METHOD_NOT_ALLOWED, "METHOD_NOT_ALLOWED", "Méthode non autorisée pour cette ressource.", null);
    }

    @ExceptionHandler(HttpMediaTypeNotSupportedException.class)
    ResponseEntity<ApiError> media(HttpMediaTypeNotSupportedException e) {
        return reponse(HttpStatus.UNSUPPORTED_MEDIA_TYPE, "UNSUPPORTED_MEDIA_TYPE", "Type de contenu non supporté.", null);
    }

    @ExceptionHandler(NoResourceFoundException.class)
    ResponseEntity<ApiError> introuvable(NoResourceFoundException e) {
        return reponse(HttpStatus.NOT_FOUND, "NOT_FOUND", "Ressource introuvable.", null);
    }

    @ExceptionHandler(HttpMediaTypeNotAcceptableException.class)
    ResponseEntity<ApiError> nonAcceptable(HttpMediaTypeNotAcceptableException e) {
        return reponse(HttpStatus.NOT_ACCEPTABLE, "NOT_ACCEPTABLE", "Format de réponse non disponible.", null);
    }

    @ExceptionHandler(MaxUploadSizeExceededException.class)
    ResponseEntity<ApiError> tropGros(MaxUploadSizeExceededException e) {
        return reponse(HttpStatus.PAYLOAD_TOO_LARGE, "PAYLOAD_TOO_LARGE", "Le fichier dépasse la taille maximale autorisée.", null);
    }

    @ExceptionHandler(AccessDeniedException.class)
    ResponseEntity<ApiError> interdit(AccessDeniedException e) {
        return reponse(HttpStatus.FORBIDDEN, "FORBIDDEN", "Vous n'avez pas le droit d'effectuer cette action.", null);
    }

    @ExceptionHandler({OptimisticLockingFailureException.class, DataIntegrityViolationException.class})
    ResponseEntity<ApiError> conflit(Exception e) {
        LOG.warn("Conflit de données : {}", e.getClass().getSimpleName());
        return reponse(HttpStatus.CONFLICT, "CONFLICT", "L'opération est en conflit avec l'état actuel des données. Rechargez puis réessayez.", null);
    }

    @ExceptionHandler(Exception.class)
    ResponseEntity<ApiError> inattendue(Exception e, HttpServletRequest requete) {
        LOG.error("Erreur inattendue sur {} {}", requete.getMethod(), AccessLogFilter.normaliser(requete.getRequestURI()), e);
        return reponse(HttpStatus.INTERNAL_SERVER_ERROR, "INTERNAL_ERROR", "Une erreur interne est survenue. Communiquez l'identifiant de corrélation au support.", null);
    }
}
