package com.mediamesh.ingestion.storage.verification;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.mediamesh.ingestion.common.exception.ApiException;
import com.mediamesh.ingestion.common.exception.ErrorCode;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;

class MediaSignatureVerifierTest {

    @Test
    void acceptsValidContainerHeaders() {
        byte[] mp4 = new byte[] {0, 0, 0, 0x18, 'f', 't', 'y', 'p', 'i', 's', 'o', 'm'};
        byte[] webm = new byte[] {0x1A, 0x45, (byte) 0xDF, (byte) 0xA3, 0x01, 0x00, 0x00, 0x00};
        byte[] png = new byte[] {(byte) 0x89, 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A, 0x00};
        byte[] jpeg = new byte[] {(byte) 0xFF, (byte) 0xD8, (byte) 0xFF, (byte) 0xE0, 0x00, 0x10};
        byte[] flac = new byte[] {'f', 'L', 'a', 'C', 0x00, 0x00, 0x00, 0x22};

        assertThatCode(() -> MediaSignatureVerifier.verify("video/mp4", mp4)).doesNotThrowAnyException();
        assertThatCode(() -> MediaSignatureVerifier.verify("video/mp4; codecs=\"avc1.42E01E\"", mp4))
                .doesNotThrowAnyException();
        assertThatCode(() -> MediaSignatureVerifier.verify("video/webm", webm)).doesNotThrowAnyException();
        assertThatCode(() -> MediaSignatureVerifier.verify("image/png", png)).doesNotThrowAnyException();
        assertThatCode(() -> MediaSignatureVerifier.verify("image/jpeg", jpeg)).doesNotThrowAnyException();
        assertThatCode(() -> MediaSignatureVerifier.verify("audio/flac", flac)).doesNotThrowAnyException();
        assertThatCode(() -> MediaSignatureVerifier.verify(null, png)).doesNotThrowAnyException();
    }

    @Test
    void rejectsSpoofedOrCorruptedHeaders() {
        byte[] html = "<html><body>not a video</body></html>".getBytes(StandardCharsets.UTF_8);
        byte[] png = new byte[] {(byte) 0x89, 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A, 0x00};

        assertThatThrownBy(() -> MediaSignatureVerifier.verify("video/mp4", html))
                .isInstanceOf(ApiException.class)
                .extracting(e -> ((ApiException) e).getErrorCode())
                .isEqualTo(ErrorCode.INVALID_MEDIA_CONTAINER);

        assertThatThrownBy(() -> MediaSignatureVerifier.verify("video/mp4", png))
                .isInstanceOf(ApiException.class)
                .extracting(e -> ((ApiException) e).getErrorCode())
                .isEqualTo(ErrorCode.INVALID_MEDIA_CONTAINER);

        assertThatThrownBy(() -> MediaSignatureVerifier.verify("video/mp4", new byte[] {0x00, 0x01}))
                .isInstanceOf(ApiException.class)
                .extracting(e -> ((ApiException) e).getErrorCode())
                .isEqualTo(ErrorCode.INVALID_MEDIA_CONTAINER);
    }
}
