package com.mediamesh.ingestion.storage.verification;

import com.mediamesh.ingestion.common.exception.ApiException;
import com.mediamesh.ingestion.common.exception.ErrorCode;
import java.nio.ByteBuffer;
import java.util.Base64;
import java.util.List;
import java.util.zip.CRC32C;

/**
 * Utility for validating Base64-encoded CRC32C digests and mathematically combining
 * per-part CRC32C checksums into a full-object CRC32C over GF(2) (Castagnoli polynomial).
 */
public final class Crc32cCombiner {

    /** Reflected Castagnoli polynomial for CRC32C. */
    private static final int CRC32C_POLY = 0x82F63B78;

    private Crc32cCombiner() {}

    /**
     * Validates that a required client-supplied checksum is present and a valid Base64-encoded
     * 32-bit CRC32C, returning its canonical Base64 representation.
     */
    public static String requireValidBase64(String base64, String fieldName) {
        if (base64 == null || base64.isBlank()) {
            throw ApiException.checksumRequired(fieldName + " is required");
        }
        try {
            byte[] decoded = Base64.getDecoder().decode(base64.trim());
            if (decoded.length != 4) {
                throw ApiException.of(
                        ErrorCode.VALIDATION_ERROR,
                        fieldName + " must be a Base64-encoded 4-byte CRC32C value");
            }
            return Base64.getEncoder().encodeToString(decoded);
        } catch (IllegalArgumentException ex) {
            throw ApiException.of(
                    ErrorCode.VALIDATION_ERROR,
                    fieldName + " is not valid Base64");
        }
    }

    /**
     * Validates that a client-supplied checksum is a valid Base64-encoded 32-bit CRC32C
     * and returns its canonical Base64 representation.
     */
    public static String normalizeAndValidateBase64(String base64) {
        if (base64 == null || base64.isBlank()) {
            return null;
        }
        try {
            byte[] decoded = Base64.getDecoder().decode(base64.trim());
            if (decoded.length != 4) {
                throw ApiException.of(
                        ErrorCode.VALIDATION_ERROR,
                        "expectedChecksum must be a Base64-encoded 4-byte CRC32C value");
            }
            return Base64.getEncoder().encodeToString(decoded);
        } catch (IllegalArgumentException ex) {
            throw ApiException.of(
                    ErrorCode.VALIDATION_ERROR,
                    "expectedChecksum is not valid Base64");
        }
    }

    /**
     * Computes Base64-encoded CRC32C for an in-memory byte array.
     */
    public static String computeBase64(byte[] data) {
        CRC32C crc = new CRC32C();
        crc.update(data, 0, data.length);
        return encodeInt((int) crc.getValue());
    }

    /**
     * Combines ordered part (CRC32C, length) pairs into the exact full-object Base64 CRC32C.
     */
    public static String combineBase64(List<PartChecksum> parts) {
        if (parts == null || parts.isEmpty()) {
            throw new IllegalArgumentException("parts must not be empty");
        }
        int combined = decodeInt(parts.get(0).base64Crc32c());
        for (int i = 1; i < parts.size(); i++) {
            PartChecksum part = parts.get(i);
            int partCrc = decodeInt(part.base64Crc32c());
            combined = combine(combined, partCrc, part.lengthBytes());
        }
        return encodeInt(combined);
    }

    /**
     * Combines two CRC32C values: {@code crc(A || B) = combine(crc(A), crc(B), len(B))}.
     */
    public static int combine(int crc1, int crc2, long len2) {
        if (len2 <= 0) {
            return crc1;
        }
        int[] even = new int[32];
        int[] odd = new int[32];

        odd[0] = CRC32C_POLY;
        int row = 1;
        for (int n = 1; n < 32; n++) {
            odd[n] = row;
            row <<= 1;
        }

        gf2MatrixSquare(even, odd);
        gf2MatrixSquare(odd, even);

        int combined = crc1;
        long remaining = len2;
        do {
            gf2MatrixSquare(even, odd);
            if ((remaining & 1L) != 0L) {
                combined = gf2MatrixTimes(even, combined);
            }
            remaining >>>= 1;
            if (remaining == 0L) {
                break;
            }
            gf2MatrixSquare(odd, even);
            if ((remaining & 1L) != 0L) {
                combined = gf2MatrixTimes(odd, combined);
            }
            remaining >>>= 1;
        } while (remaining != 0L);

        return combined ^ crc2;
    }

    private static int gf2MatrixTimes(int[] mat, int vec) {
        int sum = 0;
        int idx = 0;
        while (vec != 0) {
            if ((vec & 1) != 0) {
                sum ^= mat[idx];
            }
            vec >>>= 1;
            idx++;
        }
        return sum;
    }

    private static void gf2MatrixSquare(int[] square, int[] mat) {
        for (int n = 0; n < 32; n++) {
            square[n] = gf2MatrixTimes(mat, mat[n]);
        }
    }

    private static int decodeInt(String base64) {
        byte[] bytes = Base64.getDecoder().decode(base64.trim());
        if (bytes.length != 4) {
            throw new IllegalArgumentException("CRC32C must be 4 bytes, got " + bytes.length);
        }
        return ByteBuffer.wrap(bytes).getInt();
    }

    private static String encodeInt(int value) {
        byte[] bytes = ByteBuffer.allocate(4).putInt(value).array();
        return Base64.getEncoder().encodeToString(bytes);
    }

    /** Ordered part CRC32C and byte length. */
    public record PartChecksum(String base64Crc32c, long lengthBytes) {}
}
