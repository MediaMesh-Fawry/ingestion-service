package com.mediamesh.ingestion.features.uploads.presign;

import com.mediamesh.ingestion.common.exception.ApiException;
import com.mediamesh.ingestion.common.exception.ErrorCode;
import com.mediamesh.ingestion.config.S3Properties;
import com.mediamesh.ingestion.features.uploads.PresignedPartDto;
import com.mediamesh.ingestion.features.uploads.presign.PresignPartsRequest.PartPresignSpec;
import com.mediamesh.ingestion.storage.PartGeometry;
import com.mediamesh.ingestion.storage.UploadSupport;
import com.mediamesh.ingestion.storage.persistence.StorageObjectEntity;
import com.mediamesh.ingestion.storage.persistence.StorageObjectRepository;
import com.mediamesh.ingestion.storage.provider.ObjectStorageProvider;
import com.mediamesh.ingestion.storage.provider.ObjectStorageProvider.PresignedPartUrl;
import com.mediamesh.ingestion.storage.verification.Crc32cCombiner;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Presigns multipart part PUT URLs bound to client-computed CRC32C checksums.
 */
@Service
@RequiredArgsConstructor
public class PresignPartsHandler {

    private static final int MAX_INFLIGHT_PRESIGNS_PER_UPLOAD = PartGeometry.MAX_PRESIGN_PARTS;

    private final StorageObjectRepository storageObjectRepository;
    private final ObjectStorageProvider objectStorageProvider;
    private final S3Properties s3Properties;
    private final TransactionTemplate transactionTemplate;
    private final ConcurrentHashMap<UUID, AtomicInteger> inflightByUpload = new ConcurrentHashMap<>();

    public PresignPartsResponse handle(UUID uploadId, UUID userId, PresignPartsRequest request) {
        if (request == null || request.parts() == null || request.parts().isEmpty()) {
            throw ApiException.of(ErrorCode.VALIDATION_ERROR, "parts must not be empty");
        }
        if (request.parts().size() > PartGeometry.MAX_PRESIGN_PARTS) {
            throw ApiException.of(
                    ErrorCode.VALIDATION_ERROR,
                    "parts count must not exceed " + PartGeometry.MAX_PRESIGN_PARTS);
        }

        List<ValidatedSpec> validated = validateSpecs(request.parts());

        StorageObjectEntity entity = UploadSupport.requireOwnedUpload(storageObjectRepository, uploadId, userId);
        Instant now = Instant.now();
        UploadSupport.requireUploadAcceptingParts(entity, now);

        for (ValidatedSpec spec : validated) {
            if (spec.partNumber() < 1 || spec.partNumber() > entity.getTotalParts()) {
                throw ApiException.of(
                        ErrorCode.VALIDATION_ERROR,
                        "Part number " + spec.partNumber() + " out of range 1.." + entity.getTotalParts());
            }
        }

        AtomicInteger inflight = inflightByUpload.computeIfAbsent(uploadId, id -> new AtomicInteger(0));
        if (inflight.incrementAndGet() > MAX_INFLIGHT_PRESIGNS_PER_UPLOAD) {
            inflight.decrementAndGet();
            throw ApiException.conflict("Too many concurrent presign requests for upload " + uploadId);
        }

        try {
            UploadSupport.slideUploadExpiration(
                    storageObjectRepository, transactionTemplate, entity, s3Properties, now);

            List<PresignedPartDto> presignedParts = new ArrayList<>(validated.size());
            for (ValidatedSpec spec : validated) {
                long expectedSize = PartGeometry.expectedPartSize(
                        entity.getDeclaredSize(),
                        entity.getPartSize(),
                        entity.getTotalParts(),
                        spec.partNumber());
                PresignedPartUrl presigned = objectStorageProvider.presignPart(
                        entity.getBucket(),
                        entity.getObjectKey(),
                        entity.getProviderUploadId(),
                        spec.partNumber(),
                        expectedSize,
                        spec.crc32c(),
                        s3Properties.presignTtl());
                presignedParts.add(new PresignedPartDto(
                        spec.partNumber(),
                        presigned.url(),
                        presigned.headers(),
                        presigned.expiresAt(),
                        expectedSize));
            }
            return new PresignPartsResponse(presignedParts);
        } finally {
            if (inflight.decrementAndGet() <= 0) {
                inflightByUpload.remove(uploadId, inflight);
            }
        }
    }

    private static List<ValidatedSpec> validateSpecs(List<PartPresignSpec> specs) {
        Set<Integer> seen = new HashSet<>(specs.size());
        List<ValidatedSpec> result = new ArrayList<>(specs.size());
        for (int i = 0; i < specs.size(); i++) {
            PartPresignSpec spec = specs.get(i);
            if (spec.partNumber() == null || spec.partNumber() < 1) {
                throw ApiException.of(ErrorCode.VALIDATION_ERROR, "parts[" + i + "].partNumber must be >= 1");
            }
            if (!seen.add(spec.partNumber())) {
                throw ApiException.of(
                        ErrorCode.VALIDATION_ERROR, "Duplicate partNumber in presign request: " + spec.partNumber());
            }
            String normalizedCrc = Crc32cCombiner.requireValidBase64(
                    spec.crc32c(), "parts[" + i + "].crc32c");
            result.add(new ValidatedSpec(spec.partNumber(), normalizedCrc));
        }
        return result;
    }

    private record ValidatedSpec(int partNumber, String crc32c) {}
}
