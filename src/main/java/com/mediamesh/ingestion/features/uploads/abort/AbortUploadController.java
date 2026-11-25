package com.mediamesh.ingestion.features.uploads.abort;

import com.mediamesh.ingestion.security.IdentityHeaderResolver;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * endpoint for aborting an active multipart upload.
 */
@RestController
@RequestMapping("/api/v1/uploads")
@RequiredArgsConstructor
public class AbortUploadController {

    private final AbortUploadHandler handler;

    @PostMapping("/{uploadId}/abort")
    public AbortUploadResponse abortUpload(
            @RequestHeader(IdentityHeaderResolver.USER_ID_HEADER) String userIdHeader,
            @PathVariable UUID uploadId) {
        UUID userId = IdentityHeaderResolver.parseUserId(userIdHeader);
        return handler.handle(uploadId, userId);
    }
}
