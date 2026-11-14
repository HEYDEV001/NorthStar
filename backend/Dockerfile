# Northstar's backend image (step 1.15). One image for every App Platform component: the api service and the worker
# run start-app (NORTHSTAR_WORKER_ENABLED decides which), and the pre-deploy migrate job runs migrate.
#
# Build from the repo root: docker build -f backend/Dockerfile -t northstar .
# The repo-root .dockerignore is an allowlist, so no .env file or other local file can reach the image. Base images are
# pinned by digest; to update one, take the new digest from Docker Hub and change it here.

ARG JDK_IMAGE=eclipse-temurin:21-jdk-noble@sha256:06c1f157dacf6b68198bd9a4186dd4c34ef24e65d7bbbe2a0cf8e62571ad38c9
ARG JRE_IMAGE=eclipse-temurin:21-jre-noble@sha256:0d57a8e90c2bb844c5a8e1b2b71e11eff06dad7edcfd803cd21ed4ae203b2515

FROM ${JDK_IMAGE} AS build
WORKDIR /src
COPY backend/mvnw backend/pom.xml backend/
COPY backend/.mvn backend/.mvn
COPY backend/src/main backend/src/main
COPY db/migrate/pom.xml db/migrate/
COPY db/migrate/src/main db/migrate/src/main
# CI has already run the tests on this commit; the image build only packages. Maven's downloads stay in a build cache.
RUN --mount=type=cache,target=/root/.m2 \
    cd backend \
    && ./mvnw -B -ntp -Dmaven.test.skip=true package \
    && ./mvnw -B -ntp -Dmaven.test.skip=true -f ../db/migrate/pom.xml package
# Spring Boot's layers: dependencies change rarely, the application layer on every commit.
RUN java -Djarmode=tools -jar backend/target/northstar-0.0.1-SNAPSHOT.jar extract --layers --launcher \
    --destination /layers

FROM ${JRE_IMAGE}
RUN groupadd --system --gid 10001 northstar \
    && useradd --system --uid 10001 --gid northstar --no-create-home --shell /usr/sbin/nologin northstar
WORKDIR /opt/northstar/app
COPY --from=build /layers/dependencies/ ./
COPY --from=build /layers/spring-boot-loader/ ./
COPY --from=build /layers/snapshot-dependencies/ ./
COPY --from=build /layers/application/ ./
# The migration runner and the migrations, outside the app's classpath: the app itself never migrates.
COPY --from=build /src/db/migrate/target/lib/ /opt/northstar/migrate/lib/
COPY --from=build /src/db/migrate/target/northstar-migrate-0.0.1-SNAPSHOT.jar /opt/northstar/migrate/lib/
COPY db/migrations/*.sql /opt/northstar/migrations/
COPY --chmod=0555 backend/docker/start-app backend/docker/migrate /opt/northstar/bin/
# The runner ships only flyway-core, whose telemetry is a no-op; this also opts out should that ever change.
ENV REDGATE_DISABLE_TELEMETRY=true
USER 10001:10001
EXPOSE 8080
CMD ["/opt/northstar/bin/start-app"]
