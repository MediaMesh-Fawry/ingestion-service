package com.mediamesh.ingestion.config;

import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Info;
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
}
