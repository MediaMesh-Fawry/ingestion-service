package com.mediamesh.ingestion.security;

import static org.assertj.core.api.Assertions.assertThat;

import jakarta.servlet.FilterChain;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

class CorrelationIdFilterTest {

    private final CorrelationIdFilter filter = new CorrelationIdFilter();

    @AfterEach
    void tearDown() {
        MDC.clear();
    }

    @Test
    void generatesCorrelationIdWhenHeaderMissingAndClearsMdc() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest();
        MockHttpServletResponse response = new MockHttpServletResponse();
        StringBuilder capturedMdc = new StringBuilder();

        FilterChain chain = (req, res) -> capturedMdc.append(MDC.get(CorrelationIdFilter.MDC_KEY));
        filter.doFilter(request, response, chain);

        String echoed = response.getHeader(CorrelationIdFilter.CORRELATION_ID_HEADER);
        assertThat(echoed).isNotBlank();
        assertThat(capturedMdc.toString()).isEqualTo(echoed);
        assertThat(MDC.get(CorrelationIdFilter.MDC_KEY)).isNull();
    }

    @Test
    void propagatesAndTruncatesIncomingCorrelationId() throws Exception {
        String longId = "x".repeat(150);
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader(CorrelationIdFilter.CORRELATION_ID_HEADER, "  " + longId + "  ");
        MockHttpServletResponse response = new MockHttpServletResponse();

        filter.doFilter(request, response, (req, res) -> {});

        String echoed = response.getHeader(CorrelationIdFilter.CORRELATION_ID_HEADER);
        assertThat(echoed).hasSize(128).isEqualTo("x".repeat(128));
        assertThat(MDC.get(CorrelationIdFilter.MDC_KEY)).isNull();
    }
}
