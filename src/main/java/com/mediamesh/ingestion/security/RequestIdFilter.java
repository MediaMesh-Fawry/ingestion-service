package com.mediamesh.ingestion.security;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.MDC;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.UUID;

/**
 * High-precedence servlet filter that assigns or propagates a unique correlation ID
 * for every incoming HTTP request.
 * <p>
 * Binds the request ID to the SLF4J Mapped Diagnostic Context (MDC) under key {@code requestId}
 * to ensure all log output emitted during request processing is correlated, echoes the ID in
 * the {@code X-Request-Id} response header, and cleans up the MDC in a {@code finally} block
 * to avoid thread-local leakage in pooled container threads.
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class RequestIdFilter extends OncePerRequestFilter {

    /** HTTP header carrying the unique request correlation identifier. */
    public static final String REQUEST_ID_HEADER = "X-Request-Id";

    /** Key used to store the request identifier in SLF4J's Mapped Diagnostic Context (MDC). */
    public static final String MDC_KEY = "requestId";

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain filterChain)
            throws ServletException, IOException {

        // Reuse client-supplied request ID or generate a compact 8-character unique identifier
        String requestId = request.getHeader(REQUEST_ID_HEADER);
        if (requestId == null || requestId.isBlank()) {
            requestId = UUID.randomUUID().toString().substring(0, 8);
        }

        MDC.put(MDC_KEY, requestId);
        response.setHeader(REQUEST_ID_HEADER, requestId);

        try {
            filterChain.doFilter(request, response);
        } finally {
            // Always purge thread-local MDC context to prevent pollution across reused container worker threads
            MDC.remove(MDC_KEY);
        }
    }

    /**
     * Retrieves the active request correlation ID from the logging MDC, or generates an 8-character
     * fallback identifier if called outside the context of an active HTTP filter chain.
     *
     * @return non-null correlation ID string
     */
    public static String getCurrentRequestId() {
        String id = MDC.get(MDC_KEY);
        return id != null ? id : UUID.randomUUID().toString().substring(0, 8);
    }
}
