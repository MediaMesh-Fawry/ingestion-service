package com.mediamesh.ingestion.common.exception;

import com.mediamesh.ingestion.security.CorrelationIdFilter;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Positive;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class GlobalExceptionHandlerTest {

    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders
                .standaloneSetup(new TestController())
                .setControllerAdvice(new GlobalExceptionHandler())
                .addFilters(new CorrelationIdFilter())
                .build();
    }

    @AfterEach
    void tearDown() {
        MDC.clear();
    }

    @Test
    void apiException_sizeMismatch_returns422ProblemDetailWithCorrelationId() throws Exception {
        mockMvc.perform(get("/test/size-mismatch")
                        .header(CorrelationIdFilter.CORRELATION_ID_HEADER, "corr-1234"))
                .andExpect(status().isUnprocessableContent())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(header().string(CorrelationIdFilter.CORRELATION_ID_HEADER, "corr-1234"))
                .andExpect(jsonPath("$.type").value("https://mediamesh.local/problems/size-mismatch"))
                .andExpect(jsonPath("$.title").value("Size Mismatch"))
                .andExpect(jsonPath("$.status").value(422))
                .andExpect(jsonPath("$.instance").value("/test/size-mismatch"))
                .andExpect(jsonPath("$.code").value("SIZE_MISMATCH"))
                .andExpect(jsonPath("$.correlationId").value("corr-1234"));
    }

    @Test
    void apiException_storageUnavailable_returns503WithoutLeakingCause() throws Exception {
        mockMvc.perform(get("/test/provider-error"))
                .andExpect(status().isServiceUnavailable())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.code").value("STORAGE_UNAVAILABLE"))
                .andExpect(jsonPath("$.detail").value("Object storage unavailable."))
                .andExpect(jsonPath("$.correlationId").isNotEmpty());
    }

    @Test
    void apiException_commitInProgress_returns425WithRetryAfterHeader() throws Exception {
        mockMvc.perform(get("/test/commit-in-progress"))
                .andExpect(status().isTooEarly())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(header().string(HttpHeaders.RETRY_AFTER, "2"))
                .andExpect(jsonPath("$.code").value("COMMIT_IN_PROGRESS"));
    }

    @Test
    void validationException_returns400WithErrorsArray() throws Exception {
        mockMvc.perform(post("/test/validate")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"\",\"size\":0}"))
                .andExpect(status().isBadRequest())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"))
                .andExpect(jsonPath("$.errors").isArray())
                .andExpect(jsonPath("$.errors.length()").value(2));
    }

    @Test
    void malformedJson_returns400ValidationError() throws Exception {
        mockMvc.perform(post("/test/validate")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{invalid"))
                .andExpect(status().isBadRequest())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"));
    }

    @Test
    void missingUserIdHeader_returns401Unauthorized() throws Exception {
        mockMvc.perform(get("/test/header"))
                .andExpect(status().isUnauthorized())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.code").value("UNAUTHORIZED"));
    }

    @Test
    void invalidUuidPathVariable_returns400ValidationError() throws Exception {
        mockMvc.perform(get("/test/uploads/not-a-uuid"))
                .andExpect(status().isBadRequest())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"))
                .andExpect(jsonPath("$.detail").value("Invalid value for parameter: id"));
    }

    @Test
    void unhandledException_returns500WithGenericDetail() throws Exception {
        mockMvc.perform(get("/test/boom"))
                .andExpect(status().isInternalServerError())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.code").value("INTERNAL_SERVER_ERROR"))
                .andExpect(jsonPath("$.detail").value("An unexpected error occurred."));
    }

    @RestController
    @RequestMapping("/test")
    static class TestController {

        @GetMapping("/size-mismatch")
        void sizeMismatch() {
            throw ApiException.sizeMismatch(1024L, 512L);
        }

        @GetMapping("/provider-error")
        void providerError() {
            throw ApiException.storageUnavailable(
                    "Object storage unavailable.",
                    new RuntimeException("internal S3 secret detail"));
        }

        @GetMapping("/commit-in-progress")
        void commitInProgress() {
            throw ApiException.commitInProgress(2);
        }

        @PostMapping("/validate")
        void validate(@Valid @RequestBody SampleRequest request) {
        }

        @GetMapping("/header")
        void header(@RequestHeader("X-User-Id") UUID userId) {
        }

        @GetMapping("/uploads/{id}")
        void byId(@PathVariable("id") UUID id) {
        }

        @GetMapping("/boom")
        void boom() {
            throw new IllegalStateException("sensitive internal state");
        }
    }

    record SampleRequest(@NotBlank String name, @Positive long size) {
    }
}
