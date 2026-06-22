# ──────────────────────────────────────────────
# Stage 1 — Build the JAR with Maven
# ──────────────────────────────────────────────
FROM maven:3.9-eclipse-temurin-21-alpine AS builder

WORKDIR /app

# Copy Maven wrapper & POM first so dependency layer is cached
COPY pom.xml .
# Download dependencies without building the project (cache-friendly)
RUN --mount=type=cache,target=/root/.m2 \
    mvn dependency:go-offline -B 2>/dev/null || true

# Copy source and build
COPY src ./src
RUN --mount=type=cache,target=/root/.m2 \
    mvn package -B -DskipTests

# ──────────────────────────────────────────────
# Stage 2 — Lean runtime image
# ──────────────────────────────────────────────
FROM eclipse-temurin:21-jre-alpine AS runtime

LABEL maintainer="codetouml"
LABEL description="code-to-uml Spring Boot backend"

# Graphviz powers PlantUML's "dot" layout (UML_LAYOUT=dot) — far more robust than the
# pure-Java Smetana engine for large repo diagrams.
RUN apk add --no-cache graphviz ttf-dejavu

# Create a non-root user for security
RUN addgroup -S appgroup && adduser -S appuser -G appgroup

WORKDIR /app

# Copy the fat JAR from the builder stage
COPY --from=builder /app/target/*.jar app.jar

# Give ownership to non-root user
RUN chown appuser:appgroup app.jar

# Writable dir for the H2 saved-diagram DB (a named volume mounts here in prod)
RUN mkdir -p /data && chown appuser:appgroup /data

USER appuser

EXPOSE 8080

# Use exec form so signals (SIGTERM) reach the JVM
ENTRYPOINT ["java", "-jar", "app.jar"]
