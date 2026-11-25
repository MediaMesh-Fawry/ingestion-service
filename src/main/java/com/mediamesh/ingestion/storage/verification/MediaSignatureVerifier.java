package com.mediamesh.ingestion.storage.verification;

import com.mediamesh.ingestion.common.exception.ApiException;
import com.mediamesh.ingestion.common.exception.ErrorCode;
import java.nio.charset.StandardCharsets;
import java.util.Locale;

/**
 * Verifies that the leading bytes of an uploaded object match the container magic bytes
 * expected for its declared MIME type, preventing spoofed or corrupted payloads.
 */
public final class MediaSignatureVerifier {

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

        String mime = normalizeMime(declaredContentType);
        boolean valid = switch (mime) {
            case "video/mp4", "video/quicktime", "video/x-m4v", "video/3gpp", "video/3gpp2",
                    "audio/mp4", "audio/x-m4a", "image/avif", "image/heif", "image/heic" ->
                    isIsoBmff(headerBytes);
            case "video/webm", "video/x-matroska", "audio/webm", "audio/x-matroska" ->
                    startsWith(headerBytes, 0x1A, 0x45, 0xDF, 0xA3);
            case "video/x-msvideo", "video/avi" -> isRiffWithType(headerBytes, "AVI ");
            case "video/ogg", "audio/ogg", "application/ogg" -> startsWithAscii(headerBytes, "OggS");
            case "video/x-flv" -> startsWithAscii(headerBytes, "FLV");
            case "video/mp2t" -> isMpegTs(headerBytes);
            case "audio/mpeg", "audio/mp3" -> isMp3(headerBytes);
            case "audio/flac" -> startsWithAscii(headerBytes, "fLaC");
            case "audio/wav", "audio/x-wav", "audio/wave" -> isRiffWithType(headerBytes, "WAVE");
            case "audio/aac" -> isAdts(headerBytes) || isIsoBmff(headerBytes);
            case "image/jpeg", "image/jpg" -> startsWith(headerBytes, 0xFF, 0xD8, 0xFF);
            case "image/png" -> startsWith(headerBytes, 0x89, 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A);
            case "image/gif" -> startsWithAscii(headerBytes, "GIF87a") || startsWithAscii(headerBytes, "GIF89a");
            case "image/webp" -> isRiffWithType(headerBytes, "WEBP");
            case "image/bmp" -> startsWithAscii(headerBytes, "BM");
            case "image/tiff" ->
                    startsWith(headerBytes, 0x49, 0x49, 0x2A, 0x00)
                            || startsWith(headerBytes, 0x4D, 0x4D, 0x00, 0x2A);
            default -> isAnyKnownMediaSignature(headerBytes);
        };

        if (!valid) {
            throw ApiException.of(
                    ErrorCode.INVALID_MEDIA_CONTAINER,
                    "Uploaded object signature does not match declared contentType: " + declaredContentType);
        }
    }

    private static boolean isAnyKnownMediaSignature(byte[] b) {
        return isIsoBmff(b)
                || startsWith(b, 0x1A, 0x45, 0xDF, 0xA3)
                || isRiffWithType(b, "AVI ")
                || isRiffWithType(b, "WAVE")
                || isRiffWithType(b, "WEBP")
                || startsWithAscii(b, "OggS")
                || startsWithAscii(b, "FLV")
                || startsWithAscii(b, "fLaC")
                || isMp3(b)
                || startsWith(b, 0xFF, 0xD8, 0xFF)
                || startsWith(b, 0x89, 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A)
                || startsWithAscii(b, "GIF87a")
                || startsWithAscii(b, "GIF89a")
                || startsWithAscii(b, "BM");
    }

    private static boolean isIsoBmff(byte[] b) {
        return b.length >= 12
                && b[4] == 'f'
                && b[5] == 't'
                && b[6] == 'y'
                && b[7] == 'p';
    }

    private static boolean isRiffWithType(byte[] b, String fourCc) {
        return b.length >= 12
                && startsWithAscii(b, "RIFF")
                && b[8] == fourCc.charAt(0)
                && b[9] == fourCc.charAt(1)
                && b[10] == fourCc.charAt(2)
                && b[11] == fourCc.charAt(3);
    }

    private static boolean isMp3(byte[] b) {
        if (startsWithAscii(b, "ID3")) {
            return true;
        }
        return b.length >= 2 && (b[0] & 0xFF) == 0xFF && (b[1] & 0xE0) == 0xE0;
    }

    private static boolean isAdts(byte[] b) {
        return b.length >= 2 && (b[0] & 0xFF) == 0xFF && (b[1] & 0xF6) == 0xF0;
    }

    private static boolean isMpegTs(byte[] b) {
        if (b.length < 377) {
            return (b[0] & 0xFF) == 0x47;
        }
        return (b[0] & 0xFF) == 0x47 && (b[188] & 0xFF) == 0x47;
    }

    private static boolean startsWith(byte[] b, int... prefix) {
        if (b.length < prefix.length) {
            return false;
        }
        for (int i = 0; i < prefix.length; i++) {
            if ((b[i] & 0xFF) != prefix[i]) {
                return false;
            }
        }
        return true;
    }

    private static boolean startsWithAscii(byte[] b, String prefix) {
        byte[] p = prefix.getBytes(StandardCharsets.US_ASCII);
        if (b.length < p.length) {
            return false;
        }
        for (int i = 0; i < p.length; i++) {
            if (b[i] != p[i]) {
                return false;
            }
        }
        return true;
    }

    private static String normalizeMime(String contentType) {
        if (contentType == null) {
            return "";
        }
        int semicolon = contentType.indexOf(';');
        String base = semicolon >= 0 ? contentType.substring(0, semicolon) : contentType;
        return base.trim().toLowerCase(Locale.ROOT);
    }
}
