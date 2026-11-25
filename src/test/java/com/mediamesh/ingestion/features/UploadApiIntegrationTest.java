package com.mediamesh.ingestion.features;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.mediamesh.ingestion.TestcontainersConfiguration;
import com.mediamesh.ingestion.security.IdentityHeaderResolver;
import com.mediamesh.ingestion.storage.verification.Crc32cCombiner;
import com.mediamesh.ingestion.storage.verification.Crc32cCombiner.PartChecksum;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Import(TestcontainersConfiguration.class)
class UploadApiIntegrationTest {

    private static final int MIB = 1024 * 1024;

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    private final HttpClient httpClient = HttpClient.newHttpClient();

    @Test
    void missingOrInvalidUserIdHeaderReturns401ProblemDetail() throws Exception {
        String body = """
                {
                  "groupId": "%s",
                  "libraryId": "%s",
                  "fileName": "video.mp4",
                  "contentType": "video/mp4",
                  "fileSizeBytes": 1024
                }
                """.formatted(UUID.randomUUID(), UUID.randomUUID());

        mockMvc.perform(post("/api/v1/uploads/init")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("UNAUTHORIZED"));

        mockMvc.perform(post("/api/v1/uploads/init")
                        .header(IdentityHeaderResolver.USER_ID_HEADER, "not-a-uuid")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("UNAUTHORIZED"));
    }

    @Test
    void invalidInitPayloadReturns400ValidationError() throws Exception {
        mockMvc.perform(post("/api/v1/uploads/init")
                        .header(IdentityHeaderResolver.USER_ID_HEADER, UUID.randomUUID().toString())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "fileName": "",
                                  "contentType": "video/mp4",
                                  "fileSizeBytes": -10
                                }
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"))
                .andExpect(jsonPath("$.errors").isArray());
    }

    @Test
    void invalidCompletePayloadReturns400ValidationError() throws Exception {
        String tooLongChecksum = "a".repeat(129);
        mockMvc.perform(post("/api/v1/uploads/{uploadId}/complete", UUID.randomUUID())
                        .header(IdentityHeaderResolver.USER_ID_HEADER, UUID.randomUUID().toString())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"expectedChecksum\":\"" + tooLongChecksum + "\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"))
                .andExpect(jsonPath("$.errors[0].field").value("expectedChecksum"));
    }

    @Test
    void fullHttpUploadLifecycleInitPresignedPutPartsCompleteAndDelete() throws Exception {
        String userId = UUID.randomUUID().toString();
        String initBody = """
                {
                  "groupId": "%s",
                  "libraryId": "%s",
                  "fileName": "movie.mp4",
                  "title": "Movie",
                  "contentType": "video/mp4",
                  "fileSizeBytes": %d
                }
                """.formatted(UUID.randomUUID(), UUID.randomUUID(), 12L * MIB);

        MvcResult initResult = mockMvc.perform(post("/api/v1/uploads/init")
                        .header(IdentityHeaderResolver.USER_ID_HEADER, userId)
                        .header(IdentityHeaderResolver.IDEMPOTENCY_KEY_HEADER, UUID.randomUUID().toString())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(initBody))
                .andExpect(status().isCreated())
                .andExpect(header().exists("Location"))
                .andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(jsonPath("$.status").value("UPLOADING"))
                .andExpect(jsonPath("$.totalParts").value(2))
                .andReturn();

        JsonNode initJson = objectMapper.readTree(initResult.getResponse().getContentAsString());
        String uploadId = initJson.get("uploadId").asString();
        String mediaId = initJson.get("mediaId").asString();
        String part1Url = initJson.get("parts").get(0).get("url").asString();

        byte[] part1Bytes = mp4Bytes(8 * MIB, (byte) 0x11);
        byte[] part2Bytes = filledBytes(4 * MIB, (byte) 0x22);
        String fullCrc32c = Crc32cCombiner.combineBase64(List.of(
                new PartChecksum(Crc32cCombiner.computeBase64(part1Bytes), part1Bytes.length),
                new PartChecksum(Crc32cCombiner.computeBase64(part2Bytes), part2Bytes.length)));

        putPresigned(part1Url, part1Bytes);

        mockMvc.perform(get("/api/v1/uploads/{uploadId}/parts", uploadId)
                        .header(IdentityHeaderResolver.USER_ID_HEADER, userId)
                        .param("from", "2")
                        .param("to", "2")
                        .param("includeUploaded", "false"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.uploadedParts").isEmpty())
                .andExpect(jsonPath("$.parts[0].partNumber").value(2));

        MvcResult partsResult = mockMvc.perform(get("/api/v1/uploads/{uploadId}/parts", uploadId)
                        .header(IdentityHeaderResolver.USER_ID_HEADER, userId)
                        .param("from", "2")
                        .param("to", "2"))
                .andExpect(status().isOk())
                .andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(jsonPath("$.uploadedParts[0]").value(1))
                .andReturn();

        JsonNode partsJson = objectMapper.readTree(partsResult.getResponse().getContentAsString());
        String part2Url = partsJson.get("parts").get(0).get("url").asString();

        putPresigned(part2Url, part2Bytes);

        mockMvc.perform(post("/api/v1/uploads/{uploadId}/complete", uploadId)
                        .header(IdentityHeaderResolver.USER_ID_HEADER, userId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"expectedChecksum\":\"" + fullCrc32c + "\"}"))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.status").value("PROCESSING"))
                .andExpect(jsonPath("$.verifiedSizeBytes").value(12L * MIB))
                .andExpect(jsonPath("$.checksum").isNotEmpty())
                .andExpect(jsonPath("$.etag").isNotEmpty());

        mockMvc.perform(delete("/api/v1/internal/media/{mediaId}", mediaId))
                .andExpect(status().isNoContent());
    }

    @Test
    void abortUploadReturns200AndSubsequentPartsRequestReturns410() throws Exception {
        String userId = UUID.randomUUID().toString();
        String initBody = """
                {
                  "groupId": "%s",
                  "libraryId": "%s",
                  "fileName": "aborted.mp4",
                  "contentType": "video/mp4",
                  "fileSizeBytes": %d
                }
                """.formatted(UUID.randomUUID(), UUID.randomUUID(), 8L * MIB);

        MvcResult initResult = mockMvc.perform(post("/api/v1/uploads/init")
                        .header(IdentityHeaderResolver.USER_ID_HEADER, userId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(initBody))
                .andExpect(status().isCreated())
                .andReturn();

        String uploadId = objectMapper.readTree(initResult.getResponse().getContentAsString())
                .get("uploadId")
                .asString();

        mockMvc.perform(post("/api/v1/uploads/{uploadId}/abort", uploadId)
                        .header(IdentityHeaderResolver.USER_ID_HEADER, userId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("ABORTED"));

        mockMvc.perform(get("/api/v1/uploads/{uploadId}/parts", uploadId)
                        .header(IdentityHeaderResolver.USER_ID_HEADER, userId))
                .andExpect(status().isGone())
                .andExpect(jsonPath("$.code").value("GONE"));
    }

    @Test
    void initUploadDeduplicatesSameKeyAndRejectsPayloadMismatch() throws Exception {
        String userId = UUID.randomUUID().toString();
        String otherUserId = UUID.randomUUID().toString();
        String idempotencyKey = "init-" + UUID.randomUUID();
        UUID groupId = UUID.randomUUID();
        UUID libraryId = UUID.randomUUID();

        String body = """
                {
                  "groupId": "%s",
                  "libraryId": "%s",
                  "fileName": "dedup.mp4",
                  "contentType": "video/mp4",
                  "fileSizeBytes": %d
                }
                """.formatted(groupId, libraryId, 4L * MIB);

        MvcResult first = mockMvc.perform(post("/api/v1/uploads/init")
                        .header(IdentityHeaderResolver.USER_ID_HEADER, userId)
                        .header(IdentityHeaderResolver.IDEMPOTENCY_KEY_HEADER, idempotencyKey)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isCreated())
                .andReturn();

        MvcResult replay = mockMvc.perform(post("/api/v1/uploads/init")
                        .header(IdentityHeaderResolver.USER_ID_HEADER, userId)
                        .header(IdentityHeaderResolver.IDEMPOTENCY_KEY_HEADER, idempotencyKey)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isCreated())
                .andReturn();

        JsonNode firstJson = objectMapper.readTree(first.getResponse().getContentAsString());
        JsonNode replayJson = objectMapper.readTree(replay.getResponse().getContentAsString());
        assertThat(replayJson.get("uploadId").asString()).isEqualTo(firstJson.get("uploadId").asString());
        assertThat(replayJson.get("mediaId").asString()).isEqualTo(firstJson.get("mediaId").asString());
        assertThat(replay.getResponse().getHeader("Location")).isEqualTo(first.getResponse().getHeader("Location"));

        // Different payload with the same Idempotency-Key -> 422 IDEMPOTENCY_KEY_REUSED
        String differentBody = """
                {
                  "groupId": "%s",
                  "libraryId": "%s",
                  "fileName": "other.mp4",
                  "contentType": "video/mp4",
                  "fileSizeBytes": %d
                }
                """.formatted(groupId, libraryId, 4L * MIB);

        mockMvc.perform(post("/api/v1/uploads/init")
                        .header(IdentityHeaderResolver.USER_ID_HEADER, userId)
                        .header(IdentityHeaderResolver.IDEMPOTENCY_KEY_HEADER, idempotencyKey)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(differentBody))
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.code").value("IDEMPOTENCY_KEY_REUSED"));

        // Same Idempotency-Key for a different user is isolated and allocates a new upload
        MvcResult otherUserResult = mockMvc.perform(post("/api/v1/uploads/init")
                        .header(IdentityHeaderResolver.USER_ID_HEADER, otherUserId)
                        .header(IdentityHeaderResolver.IDEMPOTENCY_KEY_HEADER, idempotencyKey)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isCreated())
                .andReturn();

        JsonNode otherUserJson = objectMapper.readTree(otherUserResult.getResponse().getContentAsString());
        assertThat(otherUserJson.get("uploadId").asString()).isNotEqualTo(firstJson.get("uploadId").asString());
    }

    @Test
    void completeUploadReplaysCachedResponseWithSameIdempotencyKey() throws Exception {
        String userId = UUID.randomUUID().toString();
        String initBody = """
                {
                  "groupId": "%s",
                  "libraryId": "%s",
                  "fileName": "complete-dedup.mp4",
                  "contentType": "video/mp4",
                  "fileSizeBytes": %d
                }
                """.formatted(UUID.randomUUID(), UUID.randomUUID(), 2L * MIB);

        MvcResult initResult = mockMvc.perform(post("/api/v1/uploads/init")
                        .header(IdentityHeaderResolver.USER_ID_HEADER, userId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(initBody))
                .andExpect(status().isCreated())
                .andReturn();

        JsonNode initJson = objectMapper.readTree(initResult.getResponse().getContentAsString());
        String uploadId = initJson.get("uploadId").asString();
        String part1Url = initJson.get("parts").get(0).get("url").asString();
        putPresigned(part1Url, mp4Bytes(2 * MIB, (byte) 0x33));

        String completeKey = "complete-" + UUID.randomUUID();
        MvcResult firstComplete = mockMvc.perform(post("/api/v1/uploads/{uploadId}/complete", uploadId)
                        .header(IdentityHeaderResolver.USER_ID_HEADER, userId)
                        .header(IdentityHeaderResolver.IDEMPOTENCY_KEY_HEADER, completeKey))
                .andExpect(status().isAccepted())
                .andReturn();

        MvcResult replayComplete = mockMvc.perform(post("/api/v1/uploads/{uploadId}/complete", uploadId)
                        .header(IdentityHeaderResolver.USER_ID_HEADER, userId)
                        .header(IdentityHeaderResolver.IDEMPOTENCY_KEY_HEADER, completeKey))
                .andExpect(status().isAccepted())
                .andReturn();

        assertThat(replayComplete.getResponse().getContentAsString())
                .isEqualTo(firstComplete.getResponse().getContentAsString());
    }

    @Test
    void completeUploadRejectsMismatchedChecksum() throws Exception {
        String userId = UUID.randomUUID().toString();
        String initBody = """
                {
                  "groupId": "%s",
                  "libraryId": "%s",
                  "fileName": "corrupted.mp4",
                  "contentType": "video/mp4",
                  "fileSizeBytes": %d
                }
                """.formatted(UUID.randomUUID(), UUID.randomUUID(), 12L * MIB);

        MvcResult initResult = mockMvc.perform(post("/api/v1/uploads/init")
                        .header(IdentityHeaderResolver.USER_ID_HEADER, userId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(initBody))
                .andExpect(status().isCreated())
                .andReturn();

        JsonNode initJson = objectMapper.readTree(initResult.getResponse().getContentAsString());
        String uploadId = initJson.get("uploadId").asString();
        String part1Url = initJson.get("parts").get(0).get("url").asString();
        String part2Url = initJson.get("parts").get(1).get("url").asString();

        putPresigned(part1Url, mp4Bytes(8 * MIB, (byte) 0x11));
        putPresigned(part2Url, filledBytes(4 * MIB, (byte) 0x22));

        // Wrong expectedChecksum is rejected with CHECKSUM_MISMATCH
        String wrongChecksum = Crc32cCombiner.computeBase64(new byte[] {1, 2, 3, 4});
        mockMvc.perform(post("/api/v1/uploads/{uploadId}/complete", uploadId)
                        .header(IdentityHeaderResolver.USER_ID_HEADER, userId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"expectedChecksum\":\"" + wrongChecksum + "\"}"))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("CHECKSUM_MISMATCH"));
    }

    private void putPresigned(String url, byte[] bytes) throws Exception {
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .PUT(HttpRequest.BodyPublishers.ofByteArray(bytes))
                .build();
        HttpResponse<Void> response = httpClient.send(request, HttpResponse.BodyHandlers.discarding());
        assertThat(response.statusCode()).isBetween(200, 299);
    }

    private static byte[] mp4Bytes(int length, byte fill) {
        byte[] data = filledBytes(length, fill);
        byte[] header = new byte[] {0, 0, 0, 0x18, 'f', 't', 'y', 'p', 'i', 's', 'o', 'm'};
        System.arraycopy(header, 0, data, 0, header.length);
        return data;
    }

    private static byte[] filledBytes(int length, byte value) {
        byte[] data = new byte[length];
        Arrays.fill(data, value);
        return data;
    }
}
