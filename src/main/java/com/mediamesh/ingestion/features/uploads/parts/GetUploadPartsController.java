package com.mediamesh.ingestion.features.uploads.parts;

import com.mediamesh.ingestion.security.IdentityHeaderResolver;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Endpoint for listing uploaded multipart parts and their CRC32C checksums for resume.
 */
@RestController
@RequestMapping("/api/v1/uploads")
@RequiredArgsConstructor
public class GetUploadPartsController {

    private final GetUploadPartsHandler handler;

    @GetMapping("/{uploadId}/parts")
    public ResponseEntity<PartsBatchResponse> listUploadedParts(
            @RequestHeader(IdentityHeaderResolver.USER_ID_HEADER) String userIdHeader,
            @PathVariable UUID uploadId,
            @RequestParam(required = false) Integer partNumberMarker,
            @RequestParam(defaultValue = "1000") int maxParts) {
        UUID userId = IdentityHeaderResolver.parseUserId(userIdHeader);
        PartsBatchResponse response = handler.handle(uploadId, userId, partNumberMarker, maxParts);

        return ResponseEntity.ok()
                .cacheControl(CacheControl.noStore())
                .body(response);
    }
}
