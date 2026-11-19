# Build multi-etapes : etape 1 compile le jar, etape 2 ne garde que le jar + un JRE minimal - l'image
# finale n'a jamais le JDK complet ni le code source.
#
# Etape 1 sur l'image officielle Maven (Maven + JDK 21 deja inclus) et NON via mvnw : le script mvnw
# telecharge Maven avec wget a chaque build, sans nouvelle tentative, et un simple incident reseau de
# repo.maven.apache.org (frequent depuis les machines GitHub Actions) faisait echouer tout le build.
# Version EPINGLEE a la meme que .mvn/wrapper/maven-wrapper.properties (developpement et CI) : meme Maven
# partout, build reproductible. En changeant la version du wrapper, changer ce tag aussi.
FROM maven:3.9.16-eclipse-temurin-21 AS build
WORKDIR /build
ENV MAVEN_OPTS="-Dmaven.wagon.http.retryHandler.count=5 -Dmaven.wagon.httpconnectionManager.ttlSeconds=30"

# Les dependances sont telechargees par Maven (5 tentatives) et gardees dans un CACHE BuildKit : un
# rebuild, meme apres un changement de pom.xml, ne retelecharge que ce qui manque (serveur a memoire
# et reseau limites). Le cache n'est jamais copie dans l'image finale.
COPY pom.xml ./
RUN --mount=type=cache,target=/root/.m2 mvn -B -q dependency:go-offline

COPY src src
RUN --mount=type=cache,target=/root/.m2 mvn -B -q -o package -DskipTests

FROM eclipse-temurin:21-jre AS runtime
WORKDIR /app

# curl : uniquement pour le HEALTHCHECK docker-compose (GET /actuator/health) - pas necessaire a
# l'application elle-meme.
RUN apt-get update && apt-get install -y --no-install-recommends curl \
    && rm -rf /var/lib/apt/lists/*

# Utilisateur non-root : une image de production ne doit jamais tourner en root sans raison.
RUN groupadd --system comptable && useradd --system --gid comptable comptable
COPY --from=build /build/target/*.jar app.jar

# storage.documents.root-dir (application.properties) : repertoire de stockage des pieces
# justificatives uploadees, monte en volume par docker-compose.yml pour survivre aux redeploiements.
RUN mkdir -p /app/data/documents && chown -R comptable:comptable /app
USER comptable

# server.port=8081 (application.properties) : le conteneur ecoute sur 8081, comme le
# healthcheck docker-compose (GET :8081/actuator/health) et le proxy nginx (/api -> backend:8081).
EXPOSE 8081
ENTRYPOINT ["java", "-jar", "/app/app.jar"]
