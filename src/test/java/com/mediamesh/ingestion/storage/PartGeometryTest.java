package com.mediamesh.ingestion.storage;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.mediamesh.ingestion.common.exception.ApiException;
import com.mediamesh.ingestion.common.exception.ErrorCode;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class PartGeometryTest {

    private static final long MIB = 1024L * 1024L;
    private static final long GIB = 1024L * MIB;

    @Test
    void computesMinimumEightMibPartSizeForSmallAndMediumFiles() {
        var plan = PartGeometry.compute(12 * MIB);
        assertThat(plan.partSizeBytes()).isEqualTo(8 * MIB);
        assertThat(plan.totalParts()).isEqualTo(2);
        assertThat(PartGeometry.expectedPartSize(12 * MIB, plan.partSizeBytes(), plan.totalParts(), 1))
                .isEqualTo(8 * MIB);
        assertThat(PartGeometry.expectedPartSize(12 * MIB, plan.partSizeBytes(), plan.totalParts(), 2))
                .isEqualTo(4 * MIB);
    }

    @Test
    void alignsPartSizeToEightMibMultiplesAboveEightyGibAndCapsAtTenThousandParts() {
        long hundredGib = 100L * GIB;
        var plan = PartGeometry.compute(hundredGib);
        assertThat(plan.partSizeBytes() % (8 * MIB)).isZero();
        assertThat(plan.partSizeBytes()).isEqualTo(16 * MIB);
        assertThat(plan.totalParts()).isLessThanOrEqualTo(PartGeometry.MAX_PARTS);
    }

    @Test
    void validatesSizeLimitsAndMediaMimeTypes() {
        assertThatCode(() -> PartGeometry.validateUploadRequest(1024L, 5L * GIB, "video/mp4"))
                .doesNotThrowAnyException();

        assertThatThrownBy(() -> PartGeometry.validateUploadRequest(6L * GIB, 5L * GIB, "video/mp4"))
                .isInstanceOf(ApiException.class)
                .extracting(e -> ((ApiException) e).getErrorCode())
                .isEqualTo(ErrorCode.MAX_OBJECT_SIZE_EXCEEDED);

        assertThatThrownBy(() -> PartGeometry.validateUploadRequest(1024L, 5L * GIB, "application/pdf"))
                .isInstanceOf(ApiException.class)
                .extracting(e -> ((ApiException) e).getErrorCode())
                .isEqualTo(ErrorCode.INVALID_MEDIA_CONTAINER);
    }

    @Test
    void buildsOpaqueObjectKeysWithoutUserFilenames() {
        UUID groupId = UUID.randomUUID();
        UUID mediaId = UUID.randomUUID();
        assertThat(PartGeometry.rawObjectKey(groupId, mediaId))
                .isEqualTo("raw/" + groupId + "/" + mediaId);
        assertThat(PartGeometry.processedPrefix(groupId, mediaId))
                .isEqualTo("processed/" + groupId + "/" + mediaId + "/");
    }
}
