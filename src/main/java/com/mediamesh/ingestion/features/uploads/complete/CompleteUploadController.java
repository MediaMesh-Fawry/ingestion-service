package com.mediamesh.ingestion.features.uploads.complete;

import com.mediamesh.ingestion.security.IdentityHeaderResolver;
import com.mediamesh.ingestion.storage.idempotency.Idempotent;
import jakarta.validation.Valid;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * endpoint for verifying and completing a multipart upload.
 */
@RestController
@RequestMapping("/api/v1/uploads")
@RequiredArgsConstructor
public class CompleteUploadController {

    private final CompleteUploadHandler handler;

    @PostMapping("/{uploadId}/complete")
    @Idempotent(operation = "upload-complete")
    public ResponseEntity<CompleteUploadResponse> completeUpload(
            @RequestHeader(IdentityHeaderResolver.USER_ID_HEADER) String userIdHeader,
            @PathVariable UUID uploadId,
            @Valid @RequestBody(required = false) CompleteUploadRequest request) {
        UUID userId = IdentityHeaderResolver.parseUserId(userIdHeader);
        return ResponseEntity.accepted().body(handler.handle(uploadId, userId, request));
    }
}
