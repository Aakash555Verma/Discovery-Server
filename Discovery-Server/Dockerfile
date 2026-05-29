# ============================================================
# Multi-stage Dockerfile for Discovery-Server
# Stage 1: Build the JAR
# Stage 2: Create minimal JRE runtime image
# ============================================================

# ---- Stage 1: Build ----
FROM eclipse-temurin:17-jdk-alpine AS builder

WORKDIR /workspace

# Copy Maven wrapper and pom first (layer caching — only re-download deps when pom changes)
COPY mvnw .
COPY .mvn .mvn
COPY pom.xml .
RUN chmod +x ./mvnw && ./mvnw dependency:go-offline -B

# Copy source and build
COPY src src
RUN ./mvnw package -DskipTests -P prod -B

# Extract layers for optimized Docker image
RUN java -Djarmode=layertools -jar target/*.jar extract --destination target/extracted

# ---- Stage 2: Runtime ----
FROM eclipse-temurin:17-jre-alpine AS runtime

# Security: run as non-root user
RUN addgroup -S eureka && adduser -S eureka -G eureka

WORKDIR /app

# Copy layers in order (least → most frequently changed)
COPY --from=builder --chown=eureka:eureka /workspace/target/extracted/dependencies/ ./
COPY --from=builder --chown=eureka:eureka /workspace/target/extracted/spring-boot-loader/ ./
COPY --from=builder --chown=eureka:eureka /workspace/target/extracted/snapshot-dependencies/ ./
COPY --from=builder --chown=eureka:eureka /workspace/target/extracted/application/ ./

# Create log directory
RUN mkdir -p /var/log/eureka && chown eureka:eureka /var/log/eureka

USER eureka

# Eureka default port
EXPOSE 8761

# JVM tuning for containers:
#   -XX:+UseContainerSupport         — Respect cgroup memory limits
#   -XX:MaxRAMPercentage=75.0        — Use 75% of container memory for heap
#   -XX:+ExitOnOutOfMemoryError      — Fail fast on OOM rather than limping along
#   -Djava.security.egd=...          — Faster entropy for AWS/GCP environments
ENV JAVA_OPTS="-XX:+UseContainerSupport \
               -XX:MaxRAMPercentage=75.0 \
               -XX:+ExitOnOutOfMemoryError \
               -Djava.security.egd=file:/dev/./urandom \
               -Dspring.profiles.active=prod"

ENTRYPOINT ["sh", "-c", "java $JAVA_OPTS org.springframework.boot.loader.launch.JarLauncher"]

HEALTHCHECK --interval=30s --timeout=5s --start-period=60s --retries=3 \
  CMD wget --quiet --tries=1 --spider http://localhost:8761/actuator/health || exit 1
