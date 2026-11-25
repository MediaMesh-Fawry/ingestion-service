package com.mediamesh.ingestion.features.media.delete;

import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * endpoint for internal media deletion.
 */
@RestController
@RequestMapping("/api/v1/internal/media")
@RequiredArgsConstructor
public class DeleteMediaController {

    private final DeleteMediaHandler handler;

    @DeleteMapping("/{mediaId}")
    public ResponseEntity<Void> deleteMedia(@PathVariable UUID mediaId) {
        handler.handle(mediaId);
        return ResponseEntity.noContent().build();
    }
}
