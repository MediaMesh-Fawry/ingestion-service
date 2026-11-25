package com.mediamesh.ingestion.storage.idempotency;

import com.mediamesh.ingestion.security.IdentityHeaderResolver;
import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Marks a controller endpoint as idempotent using an HTTP idempotency key header
 * backed by {@link IdempotencyService}.
 */
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
@Documented
public @interface Idempotent {

    /**
     * Logical operation identifier used to namespace Redis keys and payload fingerprints
     * (e.g. {@code "upload-init"}, {@code "upload-complete"}).
     */
    String operation();

    /**
     * Whether the idempotency header is mandatory for this endpoint.
     * When {@code false} (default), requests without the header bypass deduplication.
     */
    boolean required() default false;

    /**
     * HTTP request header carrying the client-supplied idempotency key.
     */
    String headerName() default IdentityHeaderResolver.IDEMPOTENCY_KEY_HEADER;
}
