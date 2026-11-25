package com.mediamesh.ingestion.config;

import com.mediamesh.ingestion.storage.idempotency.Idempotent;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.media.StringSchema;
import io.swagger.v3.oas.models.parameters.Parameter;
import java.util.List;
import org.springdoc.core.customizers.OperationCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Configures OpenAPI / Swagger UI metadata for the Ingestion Service.
 */
@Configuration
public class OpenApiConfig {

    @Bean
    public OpenAPI ingestionOpenApi() {
        return new OpenAPI()
                .info(new Info()
                        .title("MediaMesh Ingestion Service API")
                        .description("Unified upload orchestration, storage quotas, and media ingestion API")
                        .version("0.0.1-SNAPSHOT"));
    }

    /**
     * Documents the idempotency header on {@link Idempotent} endpoints in OpenAPI/Swagger UI.
     */
    @Bean
    public OperationCustomizer idempotentHeaderOperationCustomizer() {
        return (operation, handlerMethod) -> {
            Idempotent idempotent = handlerMethod.getMethodAnnotation(Idempotent.class);
            if (idempotent == null) {
                return operation;
            }
            String headerName = idempotent.headerName();
            List<Parameter> existing = operation.getParameters();
            boolean alreadyPresent = existing != null && existing.stream()
                    .anyMatch(p -> "header".equalsIgnoreCase(p.getIn()) && headerName.equalsIgnoreCase(p.getName()));
            if (!alreadyPresent) {
                operation.addParametersItem(new Parameter()
                        .in("header")
                        .name(headerName)
                        .required(idempotent.required())
                        .description(idempotent.required()
                                ? "Required idempotency key for safe request retries"
                                : "Optional idempotency key for safe request retries")
                        .schema(new StringSchema()));
            }
            return operation;
        };
    }
}
