package com.mediamesh.ingestion.common.exception;

import com.mediamesh.ingestion.security.RequestIdFilter;
import jakarta.validation.ConstraintViolation;
import jakarta.validation.ConstraintViolationException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
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

import java.util.ArrayList;
import java.util.List;

/**
 * Centralized {@link RestControllerAdvice} translating domain, validation,
 * request binding, and unexpected exceptions into standardized {@link ErrorResponse}
 * payloads correlated with {@code X-Request-Id}.
 */
@Slf4j
@RestControllerAdvice
public class GlobalExceptionHandler {

    /** Handles explicit business/domain errors thrown via {@link ApiException}. */
    @ExceptionHandler(ApiException.class)
    public ResponseEntity<ErrorResponse> handleApiException(ApiException ex) {
        String requestId = RequestIdFilter.getCurrentRequestId();
        if (ex.getStatus().is5xxServerError()) {
            log.error("API Exception [{}]: {} (requestId={})", ex.getCode(), ex.getMessage(), requestId, ex);
        } else {
            log.warn("API Exception [{}]: {} (requestId={})", ex.getCode(), ex.getMessage(), requestId);
        }
        return buildResponse(ex.getStatus(), ex.getCode(), ex.getMessage(), ex.getDetails(), requestId);
    }

    /** Maps Bean Validation ({@code @Valid}) failures to {@code 400 VALIDATION_FAILED} with details. */
    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ErrorResponse> handleValidationException(MethodArgumentNotValidException ex) {
        List<ValidationErrorDetail> details = new ArrayList<>();

        for (FieldError fieldError : ex.getBindingResult().getFieldErrors()) {
            String code = fieldError.getCode() != null ? fieldError.getCode().toUpperCase() : "INVALID";
            details.add(new ValidationErrorDetail(fieldError.getField(), code, fieldError.getDefaultMessage()));
        }
        for (ObjectError globalError : ex.getBindingResult().getGlobalErrors()) {
            String code = globalError.getCode() != null ? globalError.getCode().toUpperCase() : "INVALID";
            details.add(new ValidationErrorDetail(globalError.getObjectName(), code, globalError.getDefaultMessage()));
        }

        return buildResponse(
                ErrorCode.VALIDATION_FAILED,
                "One or more fields are invalid.",
                details);
    }

    /** Maps constraint violations on query/path parameters or headers to {@code 400 VALIDATION_FAILED}. */
    @ExceptionHandler(ConstraintViolationException.class)
    public ResponseEntity<ErrorResponse> handleConstraintViolation(ConstraintViolationException ex) {
        List<ValidationErrorDetail> details = new ArrayList<>();
        for (ConstraintViolation<?> violation : ex.getConstraintViolations()) {
            String field = violation.getPropertyPath() != null ? violation.getPropertyPath().toString() : "parameter";
            details.add(new ValidationErrorDetail(field, "CONSTRAINT_VIOLATION", violation.getMessage()));
        }
        return buildResponse(ErrorCode.VALIDATION_FAILED, "One or more parameters are invalid.", details);
    }

    /** Maps unreadable or invalid JSON request bodies to {@code 400 MALFORMED_JSON}. */
    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<ErrorResponse> handleNotReadable(HttpMessageNotReadableException ex) {
        return buildResponse(ErrorCode.MALFORMED_JSON, "Malformed JSON request body.", null);
    }

    /** Maps missing required headers (such as {@code X-User-Id}) to {@code 400 INVALID_PARAMETER}. */
    @ExceptionHandler(MissingRequestHeaderException.class)
    public ResponseEntity<ErrorResponse> handleMissingHeader(MissingRequestHeaderException ex) {
        return buildResponse(
                ErrorCode.INVALID_PARAMETER,
                "Missing required header: " + ex.getHeaderName(),
                null);
    }

    /** Maps missing required query parameters to {@code 400 INVALID_PARAMETER}. */
    @ExceptionHandler(MissingServletRequestParameterException.class)
    public ResponseEntity<ErrorResponse> handleMissingParam(MissingServletRequestParameterException ex) {
        return buildResponse(
                ErrorCode.INVALID_PARAMETER,
                "Missing required parameter: " + ex.getParameterName(),
                null);
    }

    /** Maps type conversion failures (such as malformed UUID path/query params) to {@code 400 INVALID_PARAMETER}. */
    @ExceptionHandler(MethodArgumentTypeMismatchException.class)
    public ResponseEntity<ErrorResponse> handleTypeMismatch(MethodArgumentTypeMismatchException ex) {
        return buildResponse(
                ErrorCode.INVALID_PARAMETER,
                "Invalid value for parameter: " + ex.getName(),
                null);
    }

    /** Maps unknown routes to {@code 404 RESOURCE_NOT_FOUND}. */
    @ExceptionHandler(NoResourceFoundException.class)
    public ResponseEntity<ErrorResponse> handleNoResourceFound(NoResourceFoundException ex) {
        return buildResponse(ErrorCode.RESOURCE_NOT_FOUND, "Resource not found.", null);
    }

    /** Maps unsupported HTTP methods to {@code 405 METHOD_NOT_ALLOWED}. */
    @ExceptionHandler(HttpRequestMethodNotSupportedException.class)
    public ResponseEntity<ErrorResponse> handleMethodNotSupported(HttpRequestMethodNotSupportedException ex) {
        return buildResponse(ErrorCode.METHOD_NOT_ALLOWED, "HTTP method not supported for this endpoint.", null);
    }

    /** Catch-all fallback that hides internal stack traces behind a generic {@code 500} response. */
    @ExceptionHandler(Exception.class)
    public ResponseEntity<ErrorResponse> handleGenericException(Exception ex) {
        String requestId = RequestIdFilter.getCurrentRequestId();
        log.error("Unhandled server error (requestId={})", requestId, ex);
        return buildResponse(
                ErrorCode.INTERNAL_SERVER_ERROR.getStatus(),
                ErrorCode.INTERNAL_SERVER_ERROR.getCode(),
                "An unexpected error occurred.",
                null,
                requestId);
    }

    private ResponseEntity<ErrorResponse> buildResponse(
            ErrorCode errorCode,
            String message,
            List<ValidationErrorDetail> details) {
        String requestId = RequestIdFilter.getCurrentRequestId();
        log.warn("Request rejected [{}]: {} (requestId={})", errorCode.getCode(), message, requestId);
        return buildResponse(errorCode.getStatus(), errorCode.getCode(), message, details, requestId);
    }

    private ResponseEntity<ErrorResponse> buildResponse(
            HttpStatus status,
            String code,
            String message,
            List<ValidationErrorDetail> details,
            String requestId) {
        ErrorResponse response = new ErrorResponse(code, message, details, requestId);
        return ResponseEntity.status(status).body(response);
    }
}
