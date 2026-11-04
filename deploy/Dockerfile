# syntax=docker/dockerfile:1
# restaurantPRO backend image (D32). Built by deploy/compose.yaml with context ../backend and the
# extra build context "deploy" (this directory). Standalone:
#   docker build -f deploy/Dockerfile --build-context deploy=deploy -t restaurantpro-backend backend
# A Dockerfile rather than buildpacks: two pinned base images, non-root user and the secret-file
# entrypoint are visible in one file and need no builder image download at the installation.

FROM eclipse-temurin:25.0.5_8-jdk-alpine AS build
WORKDIR /src
COPY .mvn .mvn
COPY mvnw pom.xml ./
COPY src src
RUN --mount=type=cache,target=/root/.m2 ./mvnw -B -q -DskipTests package \
 && cp target/backend-*.jar /app.jar

FROM eclipse-temurin:25.0.5_8-jre-alpine
RUN addgroup -S -g 10001 restaurantpro && adduser -S -D -H -u 10001 -G restaurantpro restaurantpro
COPY --from=build /app.jar /app/app.jar
COPY --from=deploy --chmod=0755 backend-entrypoint.sh /usr/local/bin/backend-entrypoint.sh
USER restaurantpro
# 8080 application (behind Caddy), 8081 management/actuator (internal network only, D33)
EXPOSE 8080 8081
ENTRYPOINT ["backend-entrypoint.sh"]
