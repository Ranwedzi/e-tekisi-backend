# ============================================================================
# Tekisi backend — multi-stage container.
#
# Build stage: full Maven + JDK 21, runs `mvn package` with offline-friendly
# dependency caching (the `dependency:go-offline` step is cached as long as
# pom.xml is unchanged).
#
# Runtime stage: eclipse-temurin:21-jre, non-root user, minimal surface.
# Health endpoint comes from Spring Boot Actuator; the container HEALTHCHECK
# hits /actuator/health/liveness.
# ============================================================================

# ---- Build stage ----
FROM maven:3.9-eclipse-temurin-21 AS build
WORKDIR /workspace

# Copy pom first so the dependency layer caches independently of source changes.
COPY pom.xml .
RUN mvn -B -ntp dependency:go-offline

COPY src ./src
RUN mvn -B -ntp -DskipTests package \
    && cp target/*.jar /workspace/app.jar

# ---- Runtime stage ----
FROM eclipse-temurin:21-jre-alpine

# Non-root user — least privilege.
RUN addgroup -S tekisi && adduser -S -G tekisi tekisi

WORKDIR /app
COPY --from=build /workspace/app.jar /app/app.jar
RUN chown -R tekisi:tekisi /app

USER tekisi

EXPOSE 8080

# JVM defaults sized for a 512Mi container; override via JAVA_OPTS.
ENV JAVA_OPTS="-XX:MaxRAMPercentage=75.0 -XX:+ExitOnOutOfMemoryError" \
    SPRING_PROFILES_ACTIVE=prod

HEALTHCHECK --interval=30s --timeout=5s --start-period=60s --retries=3 \
    CMD wget -qO- http://127.0.0.1:8080/actuator/health/liveness || exit 1

ENTRYPOINT ["sh","-c","exec java $JAVA_OPTS -jar /app/app.jar"]
