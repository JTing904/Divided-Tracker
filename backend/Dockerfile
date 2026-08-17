# syntax=docker/dockerfile:1

# ---- build ------------------------------------------------------------------
# Full JDK, and only the files Gradle needs to resolve dependencies first, so a
# code-only change does not re-download the whole dependency graph.
FROM eclipse-temurin:21-jdk AS build
WORKDIR /build

COPY gradlew ./
COPY gradle ./gradle
COPY build.gradle.kts settings.gradle.kts ./
RUN chmod +x gradlew && ./gradlew --no-daemon dependencies --configuration runtimeClasspath > /dev/null 2>&1 || true

COPY src ./src
# Tests are not run here: they start an embedded PostgreSQL, which is slow and pointless
# inside a deploy pipeline. Run `./gradlew test` in CI or locally instead.
RUN ./gradlew --no-daemon bootJar -x test

# ---- run --------------------------------------------------------------------
# JRE only: no compiler, no build tools, smaller image and less to attack.
FROM eclipse-temurin:21-jre
WORKDIR /app

# Never run as root. A container escape should not land on a privileged account.
RUN groupadd --system app && useradd --system --gid app --home /app app

COPY --from=build /build/build/libs/*.jar app.jar
RUN chown -R app:app /app
USER app

# Most hosts inject the port to listen on; Spring reads SERVER_PORT from application.yml.
ENV SERVER_PORT=8080
EXPOSE 8080

# Container-aware heap sizing. Without this the JVM sizes itself from the host's memory and
# gets OOM-killed on a small free-tier instance.
ENV JAVA_OPTS="-XX:MaxRAMPercentage=70 -XX:+UseSerialGC"

# Required, and deliberately not defaulted: JwtService refuses to start without JWT_SECRET
# outside the local profiles, so a misconfigured deploy fails loudly instead of signing
# tokens with a key that changes on every restart.
#   DATABASE_URL       jdbc:postgresql://<neon-host>/neondb?sslmode=require
#   DATABASE_USERNAME
#   DATABASE_PASSWORD
#   JWT_SECRET         openssl rand -base64 48
#   MARKET_DATA_PROVIDER=yahoo
#   DIVIDEND_BACKFILL=false
#   CORS_ALLOWED_ORIGINS  only if a browser client is ever added

ENTRYPOINT ["sh", "-c", "exec java $JAVA_OPTS -jar app.jar"]
