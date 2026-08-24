# syntax=docker/dockerfile:1

# ── Build stage ──────────────────────────────────────────────────────────────
# Must be JDK 21 — the Maven enforcer pins the build to [21,22).
FROM maven:3.9-eclipse-temurin-21 AS build
WORKDIR /build

# Copy the whole reactor and build only the app module plus the modules it needs.
COPY . .

# Maven Central (repo.maven.apache.org) IP-rate-limits cache-less build boxes with HTTP 429, which
# intermittently fails an otherwise valid build. Mitigations, in order of effect:
#  1. settings.xml mirrors Central → Google's GCS CDN mirror, which serves the identical artifacts
#     without the per-IP throttle — this is the actual fix for the 429.
#  2. A BuildKit cache mount for the local ~/.m2 repo — fetched artifacts persist across builds/retries.
#  3. A retry loop with backoff as a safety net if the mirror ever hiccups.
RUN --mount=type=cache,target=/root/.m2/repository \
    ok=0; \
    for i in 1 2 3 4 5 6; do \
      echo "── Maven build attempt $i ──"; \
      if mvn -B -ntp -s settings.xml -pl app -am clean package -DskipTests \
             -Dmaven.wagon.http.retryHandler.count=5 \
             -Dmaven.wagon.httpconnectionManager.ttlSeconds=120; then ok=1; break; fi; \
      echo "Attempt $i failed; sleeping $((i*20))s before retry"; \
      sleep $((i*20)); \
    done; \
    [ "$ok" = "1" ]

# ── Runtime stage ────────────────────────────────────────────────────────────
FROM eclipse-temurin:21-jre
WORKDIR /app

# OpenCV (vision module, bytedeco) native runtime deps — the ArUco dimension engine's objdetect/
# highgui modules won't load on a headless image without these, and measurement silently degrades to
# "photos stored, no dims". highgui links GTK2 (not GTK3); on this Ubuntu-24.04 (Noble) base the GTK2
# package carries the t64 suffix. Proven to load OpenCV headless in CI. Clean apt lists to stay small.
RUN apt-get update && apt-get install -y --no-install-recommends \
        libgl1 libgomp1 libglib2.0-0 libsm6 libxext6 libxrender1 \
    && (apt-get install -y --no-install-recommends libgtk2.0-0t64 \
        || apt-get install -y --no-install-recommends libgtk2.0-0) \
    && rm -rf /var/lib/apt/lists/*

# The spring-boot-maven-plugin repackage produces the executable app jar
# (app-<version>.jar); the plain jar is app-<version>.jar.original and is skipped.
COPY --from=build /build/app/target/app-*.jar app.jar

# Container-aware heap; Render injects PORT (app binds to it via application-staging.yml).
ENV JAVA_OPTS="-XX:MaxRAMPercentage=75.0"
EXPOSE 8080

ENTRYPOINT ["sh", "-c", "java $JAVA_OPTS -jar app.jar"]
