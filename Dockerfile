# Build stage - compile with Leiningen
FROM clojure:temurin-21-lein AS builder

WORKDIR /build

# Copy project definition first (better layer caching)
COPY project.clj .

# Download dependencies (cached unless project.clj changes)
RUN lein deps

# Copy source code
COPY src/ src/
COPY dev-resources/ dev-resources/

# Build standalone JAR
RUN lein uberjar

# Runtime stage - minimal Java image
FROM eclipse-temurin:21-jre

LABEL org.opencontainers.image.source="https://github.com/enricogasparini/mirthsync"
LABEL org.opencontainers.image.description="MirthSync - Mirth Connect configuration sync tool (with channel-id filtering)"
LABEL org.opencontainers.image.licenses="EPL-1.0"
LABEL org.opencontainers.image.version="3.5.2"

# Create non-root user
RUN groupadd -r mirthsync && useradd -r -g mirthsync mirthsync

WORKDIR /app

# Copy built JAR from builder stage
COPY --from=builder /build/target/uberjar/mirthsync-*-standalone.jar /app/mirthsync.jar

# Create data directory for pull/push operations
RUN mkdir -p /data && chown -R mirthsync:mirthsync /app /data

USER mirthsync

# Default volume for channel data
VOLUME ["/data"]

ENTRYPOINT ["java", "-jar", "/app/mirthsync.jar"]

# Default: show help
CMD ["--help"]
