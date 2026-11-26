package com.mediamesh.ingestion.storage.verification;

import com.mediamesh.ingestion.common.exception.ApiException;
import com.mediamesh.ingestion.common.exception.ErrorCode;
import java.util.Set;
import org.apache.tika.Tika;
import org.apache.tika.mime.MediaType;
import org.apache.tika.mime.MediaTypeRegistry;

/**
 * Verifies that the leading bytes of an uploaded object match the container magic bytes
 * expected for its declared MIME type using Apache Tika, preventing spoofed or corrupted payloads.
 */
public final class MediaSignatureVerifier {

    private static final Tika TIKA = new Tika();
    private static final MediaTypeRegistry REGISTRY = MediaTypeRegistry.getDefaultRegistry();

    private static final Set<String> MEDIA_TYPES = Set.of("video", "audio", "image");
    private static final Set<MediaType> MEDIA_CONTAINER_APPLICATIONS = Set.of(
            MediaType.application("ogg"),
            MediaType.application("mp4"),
            MediaType.application("x-matroska"));

    private MediaSignatureVerifier() {}

    /**
     * Validates {@code headerBytes} against {@code declaredContentType}.
     * Throws {@link ApiException} with {@link ErrorCode#INVALID_MEDIA_CONTAINER} on mismatch.
     */
    public static void verify(String declaredContentType, byte[] headerBytes) {
        if (headerBytes == null || headerBytes.length < 4) {
            throw ApiException.of(
                    ErrorCode.INVALID_MEDIA_CONTAINER,
                    "Uploaded object is too small to contain a valid media header");
        }

        MediaType detected = MediaType.parse(TIKA.detect(headerBytes));
        if (detected == null || MediaType.OCTET_STREAM.equals(detected.getBaseType())) {
            throw ApiException.of(
                    ErrorCode.INVALID_MEDIA_CONTAINER,
                    "Uploaded object signature could not be recognized for declared contentType: "
                            + declaredContentType);
        }

        MediaType declared = parseDeclared(declaredContentType);
        boolean valid = declared == null ? isSupportedMedia(detected) : isCompatible(detected, declared);

        if (!valid) {
            throw ApiException.of(
                    ErrorCode.INVALID_MEDIA_CONTAINER,
                    "Uploaded object signature ("
                            + detected.getBaseType()
                            + ") does not match declared contentType: "
                            + declaredContentType);
        }
    }

    private static MediaType parseDeclared(String declaredContentType) {
        if (declaredContentType == null || declaredContentType.isBlank()) {
            return null;
        }
        MediaType parsed = MediaType.parse(declaredContentType);
        return parsed == null ? null : parsed.getBaseType();
    }

    private static boolean isSupportedMedia(MediaType type) {
        MediaType base = REGISTRY.normalize(type.getBaseType());
        return MEDIA_TYPES.contains(base.getType()) || MEDIA_CONTAINER_APPLICATIONS.contains(base);
    }

    private static boolean isCompatible(MediaType detected, MediaType declared) {
        MediaType normDetected = REGISTRY.normalize(detected.getBaseType());
        MediaType normDeclared = REGISTRY.normalize(declared.getBaseType());

        return normDetected.equals(normDeclared)
                || REGISTRY.isSpecializationOf(normDetected, normDeclared)
                || REGISTRY.isSpecializationOf(normDeclared, normDetected);
    }
}
