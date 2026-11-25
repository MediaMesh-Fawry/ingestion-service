package com.mediamesh.ingestion.security;

import static org.assertj.core.api.Assertions.assertThat;

import jakarta.servlet.FilterChain;
import java.util.concurrent.atomic.AtomicReference;
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
    void generatesCorrelationIdWhenHeaderMissingAndCleansMdc() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest();
        MockHttpServletResponse response = new MockHttpServletResponse();
        AtomicReference<String> insideMdc = new AtomicReference<>();

        FilterChain chain = (req, res) -> insideMdc.set(MDC.get(CorrelationIdFilter.MDC_KEY));
        filter.doFilter(request, response, chain);

        String header = response.getHeader(CorrelationIdFilter.CORRELATION_ID_HEADER);
        assertThat(header).isNotBlank();
        assertThat(insideMdc.get()).isEqualTo(header);
        assertThat(MDC.get(CorrelationIdFilter.MDC_KEY)).isNull();
    }

    @Test
    void propagatesAndTruncatesIncomingCorrelationId() throws Exception {
        String oversized = "a".repeat(200);
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader(CorrelationIdFilter.CORRELATION_ID_HEADER, "  " + oversized + "  ");
        MockHttpServletResponse response = new MockHttpServletResponse();

        filter.doFilter(request, response, (req, res) -> {});

        String echoed = response.getHeader(CorrelationIdFilter.CORRELATION_ID_HEADER);
        assertThat(echoed).hasSize(128).isEqualTo("a".repeat(128));
        assertThat(MDC.get(CorrelationIdFilter.MDC_KEY)).isNull();
    }
}
