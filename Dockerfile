# ─── Stage 1: Build ───────────────────────────────────────────────────────────
FROM maven:3.9-eclipse-temurin-17-alpine AS builder
WORKDIR /build

# Copy parent POM and all sub-module POMs first to leverage layer cache
COPY pom.xml .
COPY docrank-core/pom.xml docrank-core/
COPY docrank-memory/pom.xml docrank-memory/
COPY docrank-agent/pom.xml docrank-agent/
COPY docrank-mcp/pom.xml docrank-mcp/
COPY docrank-spring-boot-starter/pom.xml docrank-spring-boot-starter/
COPY docrank-webui/pom.xml docrank-webui/
COPY docrank-eval/pom.xml docrank-eval/
COPY docrank-langchain4j/pom.xml docrank-langchain4j/
COPY docrank-spring-ai/pom.xml docrank-spring-ai/

# Pre-download dependencies (cached unless POM changes)
RUN mvn dependency:go-offline -q

# Copy source and build only docrank-mcp (includes docrank-agent transitively)
COPY . .
RUN mvn clean package -DskipTests -pl docrank-mcp -am -q

# ─── Stage 2: Runtime ─────────────────────────────────────────────────────────
FROM eclipse-temurin:17-jre-alpine
LABEL maintainer="DocRank"

# Create non-root user
RUN addgroup -S docrank && adduser -S docrank -G docrank

WORKDIR /app
COPY --from=builder /build/docrank-mcp/target/docrank-mcp-*.jar app.jar

# Mount points for models and persistent data
VOLUME ["/opt/docrank/models", "/opt/docrank/data"]

EXPOSE 8080

HEALTHCHECK --interval=30s --timeout=10s --start-period=60s --retries=3 \
    CMD wget -qO- http://localhost:8080/mcp/kb_stats || exit 1

USER docrank

ENTRYPOINT ["java", \
    "-XX:+UseContainerSupport", \
    "-XX:MaxRAMPercentage=75.0", \
    "-jar", "app.jar"]
