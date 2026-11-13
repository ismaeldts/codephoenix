# Sandbox aislado donde CodePhoenix compila y ejecuta tanto el COBOL original
# (GnuCOBOL) como el código Java generado. Se ejecuta siempre sin red.
FROM eclipse-temurin:21-jdk-noble

RUN apt-get update \
    && apt-get install -y --no-install-recommends gnucobol curl ca-certificates \
    && rm -rf /var/lib/apt/lists/*

ARG JUNIT_VERSION=1.11.4
ARG JACOCO_VERSION=0.8.12
RUN mkdir -p /opt/tools \
    && curl -fsSL -o /opt/tools/junit-console.jar \
       https://repo1.maven.org/maven2/org/junit/platform/junit-platform-console-standalone/${JUNIT_VERSION}/junit-platform-console-standalone-${JUNIT_VERSION}.jar \
    && curl -fsSL -o /opt/tools/jacocoagent.jar \
       https://repo1.maven.org/maven2/org/jacoco/org.jacoco.agent/${JACOCO_VERSION}/org.jacoco.agent-${JACOCO_VERSION}-runtime.jar \
    && curl -fsSL -o /opt/tools/jacococli.jar \
       https://repo1.maven.org/maven2/org/jacoco/org.jacoco.cli/${JACOCO_VERSION}/org.jacoco.cli-${JACOCO_VERSION}-nodeps.jar

RUN useradd -m sandbox
USER sandbox
WORKDIR /work
