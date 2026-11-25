package com.mediamesh.ingestion.storage.verification;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.mediamesh.ingestion.common.exception.ApiException;
import com.mediamesh.ingestion.common.exception.ErrorCode;
import com.mediamesh.ingestion.storage.verification.Crc32cCombiner.PartChecksum;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Random;
import org.junit.jupiter.api.Test;

class Crc32cCombinerTest {

    @Test
    void combinesMultiplePartsToMatchFullUnspiltFileCrc32c() {
        Random random = new Random(42L);
        byte[] part1 = new byte[8 * 1024 * 1024];
        byte[] part2 = new byte[3 * 1024 * 1024 + 137];
        byte[] part3 = new byte[17];
        random.nextBytes(part1);
        random.nextBytes(part2);
        random.nextBytes(part3);

        byte[] combinedBytes = new byte[part1.length + part2.length + part3.length];
        System.arraycopy(part1, 0, combinedBytes, 0, part1.length);
        System.arraycopy(part2, 0, combinedBytes, part1.length, part2.length);
        System.arraycopy(part3, 0, combinedBytes, part1.length + part2.length, part3.length);

        String expectedFull = Crc32cCombiner.computeBase64(combinedBytes);
        String combinedFromParts = Crc32cCombiner.combineBase64(List.of(
                new PartChecksum(Crc32cCombiner.computeBase64(part1), part1.length),
                new PartChecksum(Crc32cCombiner.computeBase64(part2), part2.length),
                new PartChecksum(Crc32cCombiner.computeBase64(part3), part3.length)));

        assertThat(combinedFromParts).isEqualTo(expectedFull);
    }

    @Test
    void detectsSwappedPartOrderOrCorruptedBit() {
        byte[] part1 = "first-part-payload-12345".getBytes(StandardCharsets.UTF_8);
        byte[] part2 = "second-part-payload-67890".getBytes(StandardCharsets.UTF_8);

        String crc1 = Crc32cCombiner.computeBase64(part1);
        String crc2 = Crc32cCombiner.computeBase64(part2);

        String normal = Crc32cCombiner.combineBase64(List.of(
                new PartChecksum(crc1, part1.length),
                new PartChecksum(crc2, part2.length)));
        String swapped = Crc32cCombiner.combineBase64(List.of(
                new PartChecksum(crc2, part2.length),
                new PartChecksum(crc1, part1.length)));

        assertThat(swapped).isNotEqualTo(normal);
    }

    @Test
    void validatesBase64Length() {
        assertThatThrownBy(() -> Crc32cCombiner.normalizeAndValidateBase64("not-4-bytes"))
                .isInstanceOf(ApiException.class)
                .extracting(e -> ((ApiException) e).getErrorCode())
                .isEqualTo(ErrorCode.VALIDATION_ERROR);
    }
}
