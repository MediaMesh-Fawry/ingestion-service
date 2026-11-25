package com.mediamesh.ingestion.common.exception;

import com.mediamesh.ingestion.security.CorrelationIdFilter;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.ConstraintViolation;
import jakarta.validation.ConstraintViolationException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.validation.FieldError;
import org.springframework.validation.ObjectError;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingRequestHeaderException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.servlet.resource.NoResourceFoundException;

import java.net.URI;
import java.util.ArrayList;
import java.util.List;

/**
 * Centralized {@link RestControllerAdvice} translating domain, validation,
 * request binding, and unexpected exceptions into RFC 7807 {@link ProblemDetail}
 * payloads ({@code application/problem+json}) correlated with {@code X-Correlation-Id}.
 */
@Slf4j
@RestControllerAdvice
public class GlobalExceptionHandler {

    private static final String USER_ID_HEADER = "X-User-Id";

    @ExceptionHandler(ApiException.class)
    public ResponseEntity<ProblemDetail> handleApiException(ApiException ex, HttpServletRequest request) {
        String correlationId = CorrelationIdFilter.getCurrentCorrelationId();
        if (ex.getStatus().is5xxServerError()) {
            log.error("API Exception [{}]: {} (correlationId={})", ex.getCode(), ex.getMessage(), correlationId, ex);
        } else {
            log.warn("API Exception [{}]: {} (correlationId={})", ex.getCode(), ex.getMessage(), correlationId);
        }
        return buildResponse(
                ex.getErrorCode(),
                ex.getMessage(),
                ex.getDetails(),
                ex.getRetryAfterSeconds(),
                correlationId,
                request);
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ProblemDetail> handleValidationException(
            MethodArgumentNotValidException ex, HttpServletRequest request) {
        List<ValidationErrorDetail> details = new ArrayList<>();
        for (FieldError fieldError : ex.getBindingResult().getFieldErrors()) {
            details.add(new ValidationErrorDetail(fieldError.getField(), fieldError.getDefaultMessage()));
        }
        for (ObjectError globalError : ex.getBindingResult().getGlobalErrors()) {
            details.add(new ValidationErrorDetail(globalError.getObjectName(), globalError.getDefaultMessage()));
        }
        return buildResponse(ErrorCode.VALIDATION_ERROR, "One or more fields are invalid.", details, request);
    }

    @ExceptionHandler(ConstraintViolationException.class)
    public ResponseEntity<ProblemDetail> handleConstraintViolation(
            ConstraintViolationException ex, HttpServletRequest request) {
        List<ValidationErrorDetail> details = new ArrayList<>();
        for (ConstraintViolation<?> violation : ex.getConstraintViolations()) {
            String field = violation.getPropertyPath() != null ? violation.getPropertyPath().toString() : "parameter";
            details.add(new ValidationErrorDetail(field, violation.getMessage()));
        }
        return buildResponse(ErrorCode.VALIDATION_ERROR, "One or more parameters are invalid.", details, request);
    }

    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<ProblemDetail> handleNotReadable(
            HttpMessageNotReadableException ex, HttpServletRequest request) {
        return buildResponse(ErrorCode.VALIDATION_ERROR, "Malformed JSON request body.", null, request);
    }

    @ExceptionHandler(MissingRequestHeaderException.class)
    public ResponseEntity<ProblemDetail> handleMissingHeader(
            MissingRequestHeaderException ex, HttpServletRequest request) {
        if (USER_ID_HEADER.equalsIgnoreCase(ex.getHeaderName())) {
            return buildResponse(ErrorCode.UNAUTHORIZED, "Missing required authentication header: X-User-Id",
                    null, request);
        }
        return buildResponse(
                ErrorCode.VALIDATION_ERROR,
                "Missing required header: " + ex.getHeaderName(),
                null,
                request);
    }

    @ExceptionHandler(MissingServletRequestParameterException.class)
    public ResponseEntity<ProblemDetail> handleMissingParam(
            MissingServletRequestParameterException ex, HttpServletRequest request) {
        return buildResponse(
                ErrorCode.VALIDATION_ERROR,
                "Missing required parameter: " + ex.getParameterName(),
                null,
                request);
    }

    @ExceptionHandler(MethodArgumentTypeMismatchException.class)
    public ResponseEntity<ProblemDetail> handleTypeMismatch(
            MethodArgumentTypeMismatchException ex, HttpServletRequest request) {
        return buildResponse(
                ErrorCode.VALIDATION_ERROR,
                "Invalid value for parameter: " + ex.getName(),
                null,
                request);
    }

    @ExceptionHandler(NoResourceFoundException.class)
    public ResponseEntity<ProblemDetail> handleNoResourceFound(
            NoResourceFoundException ex, HttpServletRequest request) {
        return buildResponse(ErrorCode.NOT_FOUND, "Resource not found.", null, request);
    }

    @ExceptionHandler(HttpRequestMethodNotSupportedException.class)
    public ResponseEntity<ProblemDetail> handleMethodNotSupported(
            HttpRequestMethodNotSupportedException ex, HttpServletRequest request) {
        return buildResponse(ErrorCode.METHOD_NOT_ALLOWED, "HTTP method not supported for this endpoint.",
                null, request);
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<ProblemDetail> handleGenericException(Exception ex, HttpServletRequest request) {
        String correlationId = CorrelationIdFilter.getCurrentCorrelationId();
        log.error("Unhandled server error (correlationId={})", correlationId, ex);
        return buildResponse(
                ErrorCode.INTERNAL_SERVER_ERROR,
                "An unexpected error occurred.",
                null,
                null,
                correlationId,
                request);
    }

    private ResponseEntity<ProblemDetail> buildResponse(
            ErrorCode errorCode,
            String detail,
            List<ValidationErrorDetail> errors,
            HttpServletRequest request) {
        String correlationId = CorrelationIdFilter.getCurrentCorrelationId();
        log.warn("Request rejected [{}]: {} (correlationId={})", errorCode.getCode(), detail, correlationId);
        return buildResponse(errorCode, detail, errors, null, correlationId, request);
    }

    private ResponseEntity<ProblemDetail> buildResponse(
            ErrorCode errorCode,
            String detail,
            List<ValidationErrorDetail> errors,
            Integer retryAfterSeconds,
            String correlationId,
            HttpServletRequest request) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(errorCode.getStatus(), detail);
        problem.setType(errorCode.getType());
        problem.setTitle(errorCode.getTitle());
        if (request != null && request.getRequestURI() != null) {
            problem.setInstance(URI.create(request.getRequestURI()));
        }
        problem.setProperty("code", errorCode.getCode());
        problem.setProperty("correlationId", correlationId);
        if (errors != null && !errors.isEmpty()) {
            problem.setProperty("errors", errors);
        }

        ResponseEntity.BodyBuilder builder = ResponseEntity
                .status(errorCode.getStatus())
                .contentType(MediaType.APPLICATION_PROBLEM_JSON);

        if (retryAfterSeconds != null) {
            builder.header(HttpHeaders.RETRY_AFTER, String.valueOf(retryAfterSeconds));
        }

        return builder.body(problem);
    }
}
