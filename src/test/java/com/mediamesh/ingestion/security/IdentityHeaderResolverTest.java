package com.mediamesh.ingestion.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.mediamesh.ingestion.common.exception.ApiException;
import com.mediamesh.ingestion.common.exception.ErrorCode;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class IdentityHeaderResolverTest {

    @Test
    void parseUserId_validUuid_returnsUuid() {
        UUID expected = UUID.randomUUID();
        assertThat(IdentityHeaderResolver.parseUserId(expected.toString())).isEqualTo(expected);
    }

    @Test
    void parseUserId_validUuidWithWhitespace_trimsAndParses() {
        UUID expected = UUID.randomUUID();
        assertThat(IdentityHeaderResolver.parseUserId("  " + expected + " \t")).isEqualTo(expected);
    }

    @Test
    void parseUserId_nullOrBlank_throwsUnauthorized() {
        assertThatThrownBy(() -> IdentityHeaderResolver.parseUserId(null))
                .isInstanceOf(ApiException.class)
                .extracting(ex -> ((ApiException) ex).getErrorCode())
                .isEqualTo(ErrorCode.UNAUTHORIZED);

        assertThatThrownBy(() -> IdentityHeaderResolver.parseUserId("   "))
                .isInstanceOf(ApiException.class)
                .extracting(ex -> ((ApiException) ex).getErrorCode())
                .isEqualTo(ErrorCode.UNAUTHORIZED);
    }

    @Test
    void parseUserId_invalidUuid_throwsUnauthorized() {
        assertThatThrownBy(() -> IdentityHeaderResolver.parseUserId("not-a-uuid"))
                .isInstanceOf(ApiException.class)
                .extracting(ex -> ((ApiException) ex).getErrorCode())
                .isEqualTo(ErrorCode.UNAUTHORIZED);
    }
}
