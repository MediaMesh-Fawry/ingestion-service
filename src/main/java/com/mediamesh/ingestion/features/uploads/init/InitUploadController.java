package com.mediamesh.ingestion.features.uploads.init;

import com.mediamesh.ingestion.security.IdentityHeaderResolver;
import com.mediamesh.ingestion.storage.idempotency.Idempotent;
import jakarta.validation.Valid;
import java.net.URI;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.support.ServletUriComponentsBuilder;

/**
 * endpoint for initiating a multipart upload.
 */
@RestController
@RequestMapping("/api/v1/uploads")
@RequiredArgsConstructor
public class InitUploadController {

    private final InitUploadHandler handler;

    @PostMapping("/init")
    @Idempotent(operation = "upload-init")
    public ResponseEntity<InitUploadResponse> initUpload(
            @RequestHeader(IdentityHeaderResolver.USER_ID_HEADER) String userIdHeader,
            @Valid @RequestBody InitUploadRequest request) {
        UUID userId = IdentityHeaderResolver.parseUserId(userIdHeader);
        InitUploadResponse response = handler.handle(userId, request);

        URI location = ServletUriComponentsBuilder.fromCurrentRequest()
                .replacePath("/api/v1/uploads/{uploadId}")
                .buildAndExpand(response.uploadId())
                .toUri();

        return ResponseEntity.created(location)
                .cacheControl(CacheControl.noStore())
                .body(response);
    }
}
