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
 * High-precedence servlet filter that assigns or propagates a correlation ID
 * ({@code X-Correlation-Id}) for every incoming HTTP request.
 * <p>
 * Binds the correlation ID to SLF4J MDC under {@code correlationId}, echoes it on
 * the {@code X-Correlation-Id} response header, and clears MDC in a {@code finally} block.
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class CorrelationIdFilter extends OncePerRequestFilter {

    /** HTTP header carrying the request correlation identifier. */
    public static final String CORRELATION_ID_HEADER = "X-Correlation-Id";

    /** Key used to store the correlation identifier in SLF4J MDC. */
    public static final String MDC_KEY = "correlationId";

    private static final int MAX_LENGTH = 128;

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain filterChain)
            throws ServletException, IOException {

        String correlationId = request.getHeader(CORRELATION_ID_HEADER);
        if (correlationId == null || correlationId.isBlank()) {
            correlationId = UUID.randomUUID().toString();
        } else {
            correlationId = correlationId.strip();
            if (correlationId.length() > MAX_LENGTH) {
                correlationId = correlationId.substring(0, MAX_LENGTH);
            }
        }

        MDC.put(MDC_KEY, correlationId);
        response.setHeader(CORRELATION_ID_HEADER, correlationId);

        try {
            filterChain.doFilter(request, response);
        } finally {
            MDC.remove(MDC_KEY);
        }
    }

    /**
     * Retrieves the active correlation ID from MDC, or generates a UUID fallback
     * when called outside an active HTTP filter chain.
     */
    public static String getCurrentCorrelationId() {
        String id = MDC.get(MDC_KEY);
        return id != null ? id : UUID.randomUUID().toString();
    }
}
