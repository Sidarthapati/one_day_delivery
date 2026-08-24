# syntax=docker/dockerfile:1

# ── Build stage ──────────────────────────────────────────────────────────────
# Must be JDK 21 — the Maven enforcer pins the build to [21,22).
FROM maven:3.9-eclipse-temurin-21 AS build
WORKDIR /build

# --- Dependency layer (cached across builds until a pom.xml changes) ----------
# Copy ONLY the build config + every module's pom.xml first, preserving paths.
# Because this layer's inputs are just the poms, Render's Docker layer cache
# reuses it on any source-only push — so a normal code change no longer
# re-downloads the world from Maven Central. (Enumerated, not globbed: COPY
# can't preserve the per-module directory structure with a wildcard.)
COPY settings.xml pom.xml ./
COPY common/pom.xml     common/
COPY auth/pom.xml       auth/
COPY pricing/pom.xml    pricing/
COPY grid/pom.xml       grid/
COPY barcode/pom.xml    barcode/
COPY orders/pom.xml     orders/
COPY dispatch/pom.xml   dispatch/
COPY routing/pom.xml    routing/
COPY hub/pom.xml        hub/
COPY airline/pom.xml    airline/
COPY shuttle/pom.xml    shuttle/
COPY sla/pom.xml        sla/
COPY exceptions/pom.xml exceptions/
COPY vision/pom.xml     vision/
COPY app/pom.xml        app/

# Warm the dependency cache into THIS image layer (deliberately no cache mount —
# a mount would keep the downloads out of the layer and defeat the reuse). All
# Central traffic is mirrored to Google's GCS CDN via settings.xml, which serves
# the identical artifacts without Central's per-IP 429 throttle; the retry loop
# is a safety net if the mirror ever hiccups.
RUN ok=0; \
    for i in 1 2 3 4 5; do \
      echo "── go-offline attempt $i ──"; \
      if mvn -B -ntp -s settings.xml -pl app -am -DskipTests \
             dependency:go-offline \
             -Dmaven.wagon.http.retryHandler.count=5; then ok=1; break; fi; \
      echo "Attempt $i failed; sleeping $((i*15))s before retry"; sleep $((i*15)); \
    done; \
    [ "$ok" = "1" ]

# --- Build layer (re-runs on any source change) ------------------------------
# Now bring in the source. Dependencies are already resolved in the layer above,
# so this step compiles/packages with (near-)zero network — only artifacts that
# go-offline could not pre-fetch are pulled, through the same mirror.
COPY . .
RUN ok=0; \
    for i in 1 2 3 4 5; do \
      echo "── Maven package attempt $i ──"; \
      if mvn -B -ntp -s settings.xml -pl app -am clean package -DskipTests \
             -Dmaven.wagon.http.retryHandler.count=5; then ok=1; break; fi; \
      echo "Attempt $i failed; sleeping $((i*15))s before retry"; sleep $((i*15)); \
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
