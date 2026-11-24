package com.mediamesh.ingestion.common.exception;

import com.mediamesh.ingestion.security.RequestIdFilter;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Positive;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;
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
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class GlobalExceptionHandlerTest {

    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders
                .standaloneSetup(new TestController())
                .setControllerAdvice(new GlobalExceptionHandler())
                .addFilters(new RequestIdFilter())
                .build();
    }

    @AfterEach
    void tearDown() {
        MDC.clear();
    }

    @Test
    void apiException_sizeMismatch_returns422WithRequestIdAndTimestamp() throws Exception {
        mockMvc.perform(get("/test/size-mismatch")
                        .header(RequestIdFilter.REQUEST_ID_HEADER, "req-1234"))
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.code").value("SIZE_MISMATCH"))
                .andExpect(jsonPath("$.requestId").value("req-1234"))
                .andExpect(jsonPath("$.timestamp").isNotEmpty());
    }

    @Test
    void apiException_storageProviderError_returns502WithoutLeakingCause() throws Exception {
        mockMvc.perform(get("/test/provider-error"))
                .andExpect(status().isBadGateway())
                .andExpect(jsonPath("$.code").value("STORAGE_PROVIDER_ERROR"))
                .andExpect(jsonPath("$.message").value("Object storage unavailable."))
                .andExpect(jsonPath("$.requestId").isNotEmpty());
    }

    @Test
    void validationException_returns400WithFieldDetails() throws Exception {
        mockMvc.perform(post("/test/validate")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"\",\"size\":0}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"))
                .andExpect(jsonPath("$.details").isArray())
                .andExpect(jsonPath("$.details.length()").value(2));
    }

    @Test
    void malformedJson_returns400() throws Exception {
        mockMvc.perform(post("/test/validate")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{invalid"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("MALFORMED_JSON"));
    }

    @Test
    void missingHeader_returns400InvalidParameter() throws Exception {
        mockMvc.perform(get("/test/header"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_PARAMETER"))
                .andExpect(jsonPath("$.message").value("Missing required header: X-User-Id"));
    }

    @Test
    void invalidUuidPathVariable_returns400InvalidParameter() throws Exception {
        mockMvc.perform(get("/test/uploads/not-a-uuid"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_PARAMETER"))
                .andExpect(jsonPath("$.message").value("Invalid value for parameter: id"));
    }

    @Test
    void unhandledException_returns500WithGenericMessage() throws Exception {
        mockMvc.perform(get("/test/boom"))
                .andExpect(status().isInternalServerError())
                .andExpect(jsonPath("$.code").value("INTERNAL_SERVER_ERROR"))
                .andExpect(jsonPath("$.message").value("An unexpected error occurred."));
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
            throw ApiException.storageProviderError(
                    "Object storage unavailable.",
                    new RuntimeException("internal S3 secret detail"));
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
