# Sandbox aislado donde CodePhoenix compila y ejecuta tanto el COBOL original
# (GnuCOBOL) como el código Java generado. Se ejecuta siempre sin red.

# Cucumber (escenarios BDD ejecutables) se descarga con Maven en una etapa aparte.
FROM maven:3.9-eclipse-temurin-21 AS bdd
COPY bdd-pom.xml /tmp/pom.xml
RUN mvn -q -f /tmp/pom.xml dependency:copy-dependencies -DoutputDirectory=/opt/cucumber \
      -DexcludeGroupIds=org.junit.platform,org.opentest4j,org.apiguardian

FROM eclipse-temurin:21-jdk-noble

RUN apt-get update \
    && apt-get install -y --no-install-recommends gnucobol curl ca-certificates \
    && rm -rf /var/lib/apt/lists/*

ARG JUNIT_VERSION=1.11.4
ARG JACOCO_VERSION=0.8.12
ARG SPOTBUGS_VERSION=4.8.6
ARG FINDSECBUGS_VERSION=1.13.0
RUN mkdir -p /opt/tools \
    && curl -fsSL -o /opt/tools/junit-console.jar \
       https://repo1.maven.org/maven2/org/junit/platform/junit-platform-console-standalone/${JUNIT_VERSION}/junit-platform-console-standalone-${JUNIT_VERSION}.jar \
    && curl -fsSL -o /opt/tools/jacocoagent.jar \
       https://repo1.maven.org/maven2/org/jacoco/org.jacoco.agent/${JACOCO_VERSION}/org.jacoco.agent-${JACOCO_VERSION}-runtime.jar \
    && curl -fsSL -o /opt/tools/jacococli.jar \
       https://repo1.maven.org/maven2/org/jacoco/org.jacoco.cli/${JACOCO_VERSION}/org.jacoco.cli-${JACOCO_VERSION}-nodeps.jar \
    && curl -fsSL https://github.com/spotbugs/spotbugs/releases/download/${SPOTBUGS_VERSION}/spotbugs-${SPOTBUGS_VERSION}.tgz \
       | tar -xz -C /opt && mv /opt/spotbugs-${SPOTBUGS_VERSION} /opt/spotbugs \
    && curl -fsSL -o /opt/tools/findsecbugs-plugin.jar \
       https://repo1.maven.org/maven2/com/h3xstream/findsecbugs/findsecbugs-plugin/${FINDSECBUGS_VERSION}/findsecbugs-plugin-${FINDSECBUGS_VERSION}.jar

COPY --from=bdd /opt/cucumber /opt/cucumber

RUN useradd -m sandbox
USER sandbox
WORKDIR /work
