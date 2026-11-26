package com.mediamesh.ingestion.features.uploads.presign;

import com.mediamesh.ingestion.security.IdentityHeaderResolver;
import jakarta.validation.Valid;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Endpoint for presigning multipart part PUT URLs bound to client CRC32C checksums.
 */
@RestController
@RequestMapping("/api/v1/uploads")
@RequiredArgsConstructor
public class PresignPartsController {

    private final PresignPartsHandler handler;

    @PostMapping("/{uploadId}/parts/presign")
    public ResponseEntity<PresignPartsResponse> presignParts(
            @RequestHeader(IdentityHeaderResolver.USER_ID_HEADER) String userIdHeader,
            @PathVariable UUID uploadId,
            @Valid @RequestBody PresignPartsRequest request) {
        UUID userId = IdentityHeaderResolver.parseUserId(userIdHeader);
        PresignPartsResponse response = handler.handle(uploadId, userId, request);

        return ResponseEntity.ok()
                .cacheControl(CacheControl.noStore())
                .body(response);
    }
}
