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
    void missingChecksumOnCompleteOrPresignReturns400ChecksumRequired() throws Exception {
        String userId = UUID.randomUUID().toString();

        // /complete with no body -> CHECKSUM_REQUIRED
        mockMvc.perform(post("/api/v1/uploads/{uploadId}/complete", UUID.randomUUID())
                        .header(IdentityHeaderResolver.USER_ID_HEADER, userId))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("CHECKSUM_REQUIRED"));

        // /complete with invalid checksum length -> VALIDATION_ERROR
        String tooLongChecksum = "a".repeat(129);
        mockMvc.perform(post("/api/v1/uploads/{uploadId}/complete", UUID.randomUUID())
                        .header(IdentityHeaderResolver.USER_ID_HEADER, userId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"expectedChecksum\":\"" + tooLongChecksum + "\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"));

        // /presign with missing crc32c -> CHECKSUM_REQUIRED
        mockMvc.perform(post("/api/v1/uploads/{uploadId}/parts/presign", UUID.randomUUID())
                        .header(IdentityHeaderResolver.USER_ID_HEADER, userId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"parts\":[{\"partNumber\":1}]}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("CHECKSUM_REQUIRED"));

        // /presign with duplicate partNumber -> VALIDATION_ERROR
        String validCrc = Crc32cCombiner.computeBase64(new byte[] {1, 2, 3, 4});
        mockMvc.perform(post("/api/v1/uploads/{uploadId}/parts/presign", UUID.randomUUID())
                        .header(IdentityHeaderResolver.USER_ID_HEADER, userId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"parts":[
                                  {"partNumber":1,"crc32c":"%s"},
                                  {"partNumber":1,"crc32c":"%s"}
                                ]}
                                """.formatted(validCrc, validCrc)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"));
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
                .andExpect(jsonPath("$.recommendedConcurrency").value(4))
                .andExpect(jsonPath("$.parts").doesNotExist())
                .andReturn();

        JsonNode initJson = objectMapper.readTree(initResult.getResponse().getContentAsString());
        String uploadId = initJson.get("uploadId").asString();
        String mediaId = initJson.get("mediaId").asString();

        byte[] part1Bytes = mp4Bytes(8 * MIB, (byte) 0x11);
        byte[] part2Bytes = filledBytes(4 * MIB, (byte) 0x22);
        String crc1 = Crc32cCombiner.computeBase64(part1Bytes);
        String crc2 = Crc32cCombiner.computeBase64(part2Bytes);
        String fullCrc32c = Crc32cCombiner.combineBase64(List.of(
                new PartChecksum(crc1, part1Bytes.length),
                new PartChecksum(crc2, part2Bytes.length)));

        String part1Url = presignPart(userId, uploadId, 1, crc1);
        putPresigned(part1Url, part1Bytes, crc1);

        // Resume listing returns uploaded parts with size, no presigned URLs
        mockMvc.perform(get("/api/v1/uploads/{uploadId}/parts", uploadId)
                        .header(IdentityHeaderResolver.USER_ID_HEADER, userId))
                .andExpect(status().isOk())
                .andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(jsonPath("$.uploadedParts[0].partNumber").value(1))
                .andExpect(jsonPath("$.uploadedParts[0].size").value(8L * MIB))
                .andExpect(jsonPath("$.uploadedParts[0].url").doesNotExist());

        String part2Url = presignPart(userId, uploadId, 2, crc2);
        putPresigned(part2Url, part2Bytes, crc2);

        String completeBody = """
                {
                  "expectedChecksum": "%s",
                  "parts": [
                    {"partNumber": 1, "crc32c": "%s"},
                    {"partNumber": 2, "crc32c": "%s"}
                  ]
                }
                """.formatted(fullCrc32c, crc1, crc2);

        mockMvc.perform(post("/api/v1/uploads/{uploadId}/complete", uploadId)
                        .header(IdentityHeaderResolver.USER_ID_HEADER, userId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(completeBody))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.status").value("PROCESSING"))
                .andExpect(jsonPath("$.verifiedSizeBytes").value(12L * MIB))
                .andExpect(jsonPath("$.checksum").value(fullCrc32c))
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

        String uploadId = objectMapper.readTree(initResult.getResponse().getContentAsString())
                .get("uploadId").asString();
        byte[] bytes = mp4Bytes(2 * MIB, (byte) 0x33);
        String crc = Crc32cCombiner.computeBase64(bytes);

        String part1Url = presignPart(userId, uploadId, 1, crc);
        putPresigned(part1Url, bytes, crc);

        String completeBody = """
                {"expectedChecksum":"%s","parts":[{"partNumber":1,"crc32c":"%s"}]}
                """.formatted(crc, crc);

        String completeKey = "complete-" + UUID.randomUUID();
        MvcResult firstComplete = mockMvc.perform(post("/api/v1/uploads/{uploadId}/complete", uploadId)
                        .header(IdentityHeaderResolver.USER_ID_HEADER, userId)
                        .header(IdentityHeaderResolver.IDEMPOTENCY_KEY_HEADER, completeKey)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(completeBody))
                .andExpect(status().isAccepted())
                .andReturn();

        MvcResult replayComplete = mockMvc.perform(post("/api/v1/uploads/{uploadId}/complete", uploadId)
                        .header(IdentityHeaderResolver.USER_ID_HEADER, userId)
                        .header(IdentityHeaderResolver.IDEMPOTENCY_KEY_HEADER, completeKey)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(completeBody))
                .andExpect(status().isAccepted())
                .andReturn();

        assertThat(replayComplete.getResponse().getContentAsString())
                .isEqualTo(firstComplete.getResponse().getContentAsString());
    }

    @Test
    void completeUploadRejectsMismatchedCompositeChecksumWithBadPartsEmpty() throws Exception {
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

        String uploadId = objectMapper.readTree(initResult.getResponse().getContentAsString())
                .get("uploadId").asString();

        byte[] p1 = mp4Bytes(8 * MIB, (byte) 0x11);
        byte[] p2 = filledBytes(4 * MIB, (byte) 0x22);
        String crc1 = Crc32cCombiner.computeBase64(p1);
        String crc2 = Crc32cCombiner.computeBase64(p2);

        putPresigned(presignPart(userId, uploadId, 1, crc1), p1, crc1);
        putPresigned(presignPart(userId, uploadId, 2, crc2), p2, crc2);

        String wrongChecksum = Crc32cCombiner.computeBase64(new byte[] {1, 2, 3, 4});
        mockMvc.perform(post("/api/v1/uploads/{uploadId}/complete", uploadId)
                        .header(IdentityHeaderResolver.USER_ID_HEADER, userId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "expectedChecksum": "%s",
                                  "parts": [
                                    {"partNumber": 1, "crc32c": "%s"},
                                    {"partNumber": 2, "crc32c": "%s"}
                                  ]
                                }
                                """.formatted(wrongChecksum, crc1, crc2)))
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.code").value("COMPOSITE_CHECKSUM_INVALID"))
                .andExpect(jsonPath("$.badParts").isEmpty());
    }

    private String presignPart(String userId, String uploadId, int partNumber, String crc32c) throws Exception {
        MvcResult result = mockMvc.perform(post("/api/v1/uploads/{uploadId}/parts/presign", uploadId)
                        .header(IdentityHeaderResolver.USER_ID_HEADER, userId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"parts":[{"partNumber":%d,"crc32c":"%s"}]}
                                """.formatted(partNumber, crc32c)))
                .andExpect(status().isOk())
                .andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(jsonPath("$.parts[0].headers['x-amz-checksum-crc32c']").value(crc32c))
                .andReturn();
        return objectMapper.readTree(result.getResponse().getContentAsString())
                .get("parts").get(0).get("url").asString();
    }

    private void putPresigned(String url, byte[] bytes, String crc32c) throws Exception {
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .header("x-amz-checksum-crc32c", crc32c)
                .PUT(HttpRequest.BodyPublishers.ofByteArray(bytes))
                .build();
        HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
        assertThat(response.statusCode())
                .withFailMessage("Expected 2xx but got %d body=%s", response.statusCode(), response.body())
                .isBetween(200, 299);
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
