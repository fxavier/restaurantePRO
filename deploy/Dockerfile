# syntax=docker/dockerfile:1
# restaurantPRO backend image (D32). Built by deploy/compose.yaml with context ../backend and the
# extra build context "deploy" (this directory). Standalone:
#   docker build -f deploy/Dockerfile --build-context deploy=deploy -t restaurantpro-backend backend
# A Dockerfile rather than buildpacks: two pinned base images, non-root user and the secret-file
# entrypoint are visible in one file and need no builder image download at the installation.

FROM eclipse-temurin:25.0.5_8-jdk-alpine AS build
WORKDIR /src
# The Maven wrapper's distribution and every dependency and plugin are resolved in their own layer, which depends
# only on the wrapper and pom.xml (FE-F31): a source change reuses it from the layer cache (local or the CI's GHA
# cache) instead of downloading Maven and the dependencies again. The package step then runs offline (-o), so a
# plugin that go-offline did not fetch fails the build here instead of silently downloading. The resolve step also
# keeps a cache mount (local builds: a clean or interrupted build resumes from it) and copies it into the layer,
# since a cache mount is neither part of the image nor exported to the CI layer cache.
COPY .mvn .mvn
COPY mvnw pom.xml ./
RUN --mount=type=cache,target=/cache/m2,sharing=locked \
    MAVEN_USER_HOME=/cache/m2 ./mvnw -B -q -Dmaven.repo.local=/cache/m2/repository dependency:go-offline \
 && cp -a /cache/m2 /root/.m2
COPY src src
RUN ./mvnw -B -q -o -DskipTests package \
 && cp target/backend-*.jar /app.jar

FROM eclipse-temurin:25.0.5_8-jre-alpine
RUN addgroup -S -g 10001 restaurantpro && adduser -S -D -H -u 10001 -G restaurantpro restaurantpro
COPY --from=build /app.jar /app/app.jar
COPY --from=deploy --chmod=0755 backend-entrypoint.sh /usr/local/bin/backend-entrypoint.sh
USER restaurantpro
# 8080 application (behind Caddy), 8081 management/actuator (internal network only, D33)
EXPOSE 8080 8081
ENTRYPOINT ["backend-entrypoint.sh"]
