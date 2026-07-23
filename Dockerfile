# syntax=docker/dockerfile:1

# ── Build stage ──────────────────────────────────────────────────────────────
# Must be JDK 21 — the Maven enforcer pins the build to [21,22).
FROM maven:3.9-eclipse-temurin-21 AS build
WORKDIR /build

# Copy the whole reactor and build only the app module plus the modules it needs.
COPY . .
RUN mvn -B -ntp -pl app -am clean package -DskipTests

# ── Runtime stage ────────────────────────────────────────────────────────────
FROM eclipse-temurin:21-jre
WORKDIR /app

# The spring-boot-maven-plugin repackage produces the executable app jar
# (app-<version>.jar); the plain jar is app-<version>.jar.original and is skipped.
COPY --from=build /build/app/target/app-*.jar app.jar

# Container-aware heap; Render injects PORT (app binds to it via application-staging.yml).
ENV JAVA_OPTS="-XX:MaxRAMPercentage=75.0"
EXPOSE 8080

ENTRYPOINT ["sh", "-c", "java $JAVA_OPTS -jar app.jar"]
