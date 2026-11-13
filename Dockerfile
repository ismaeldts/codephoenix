# CodePhoenix empaquetado. Necesita el socket de Docker del host para lanzar los sandboxes
# (GnuCOBOL/JDK y Oracle); ver docker-compose.yml.
FROM eclipse-temurin:21-jdk AS build
WORKDIR /src
COPY gradlew settings.gradle build.gradle ./
COPY gradle gradle
RUN chmod +x gradlew && ./gradlew --no-daemon -q dependencies > /dev/null
COPY src src
RUN ./gradlew --no-daemon -q bootJar -x test

FROM eclipse-temurin:21-jre
ARG DOCKER_CLI_VERSION=27.5.1
RUN apt-get update \
    && apt-get install -y --no-install-recommends curl ca-certificates \
    && curl -fsSL "https://download.docker.com/linux/static/stable/x86_64/docker-${DOCKER_CLI_VERSION}.tgz" \
       | tar -xz --strip-components=1 -C /usr/local/bin docker/docker \
    && apt-get purge -y curl && apt-get autoremove -y && rm -rf /var/lib/apt/lists/*
WORKDIR /app
COPY --from=build /src/build/libs/codephoenix-0.0.1-SNAPSHOT.jar app.jar
EXPOSE 8080
# umask 0000: el workspace es un volumen compartido con los sandboxes, que corren con otros usuarios.
ENTRYPOINT ["sh", "-c", "umask 0000 && exec java -jar /app/app.jar"]
