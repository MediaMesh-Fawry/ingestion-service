# syntax=docker/dockerfile:1

# ---- build ----
FROM eclipse-temurin:21-jdk AS build
WORKDIR /workspace

# Copy Maven wrapper and project configuration
COPY mvnw mvnw.cmd pom.xml ./
COPY .mvn .mvn

# Download dependencies for better layer caching
RUN chmod +x mvnw && ./mvnw dependency:go-offline -B

# Copy source code
COPY src src

# Build executable JAR using Maven wrapper
RUN ./mvnw package -DskipTests -B

# ---- runtime ----
FROM eclipse-temurin:21-jre
LABEL org.opencontainers.image.title="ingestion-service" \
      org.opencontainers.image.description="Ingestion Service" \
      org.opencontainers.image.authors="omar-shaieen" \
      org.opencontainers.image.vendor="com.media-mesh"
RUN apt-get update \
    && apt-get install -y --no-install-recommends curl \
    && rm -rf /var/lib/apt/lists/* \
    && useradd --system --uid 10001 ingestion-service
WORKDIR /app
COPY --from=build /workspace/target/*.jar app.jar
USER ingestion-service
EXPOSE 8080
ENTRYPOINT ["java", "-XX:MaxRAMPercentage=75", "-jar", "/app/app.jar"]
