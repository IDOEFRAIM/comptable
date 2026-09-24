# Build multi-etapes : etape 1 compile le jar avec le meme wrapper Maven que le developpement
# (mvnw, meme version epinglee dans .mvn/wrapper/maven-wrapper.properties), etape 2 ne garde que
# le jar + un JRE minimal - l'image finale n'a jamais le JDK complet ni le code source.
FROM eclipse-temurin:21-jdk AS build
WORKDIR /build

# Couche dependances separee du code source : un changement de code seul ne re-telecharge pas
# tout le repository Maven (cache Docker efficace sur les rebuilds).
COPY mvnw pom.xml ./
COPY .mvn .mvn
RUN ./mvnw -B -q dependency:go-offline

COPY src src
RUN ./mvnw -B -q -o package -DskipTests

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
