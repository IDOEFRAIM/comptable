#!/usr/bin/env bash
# Démarrage « comme en production » sur environnement VIDE, puis smoke test pilote : prouve qu'une installation neuve, profil prod, fonctionne sans développeur.
#
#   1. PostgreSQL jetable (base neuve, rôle applicatif au mot de passe fort aléatoire) ;
#   2. démarrage avec une configuration FAIBLE (secret JWT et clés internes de développement, inscription ouverte) : le démarrage doit être REFUSÉ ;
#   3. démarrage avec une configuration saine (secrets aléatoires générés ici, jamais affichés) : readiness UP ;
#   4. scripts/smoke-pilot.sh (cabinet, entreprise, écriture, pièce, banque, contrôles, dossier financier, garde-fou TVA, isolation, diagnostic) ;
#   5. nettoyage (processus, conteneur, dossier de pièces).
#
# Usage :  ./scripts/smoke-prod-local.sh <chemin/vers/comptable.jar>     (jar construit par `./mvnw package -DskipTests` dans comptable/)
# Variables : PG_IMAGE (défaut postgres:18-alpine), PORT (défaut 18081), PG_PORT (défaut 55432).
# Code de sortie : 0 si le refus du démarrage faible ET le smoke test passent.
set -uo pipefail
export MSYS_NO_PATHCONV=1
ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$ROOT"
JAR="${1:-}"
[ -f "$JAR" ] || { echo "Usage : $0 <comptable.jar>" >&2; exit 1; }
chemin() { if command -v cygpath >/dev/null 2>&1; then cygpath -m "$1"; else printf '%s' "$1"; fi; }   # Git Bash : java (Windows) attend un chemin Windows
JAR="$(chemin "$JAR")"
PG_IMAGE="${PG_IMAGE:-postgres:18-alpine}"; PORT="${PORT:-18081}"; PORT_PROD="$PORT"; PG_PORT="${PG_PORT:-55432}"
aleatoire() { head -c "$1" /dev/urandom | od -An -tx1 | tr -d ' \n'; }
NOM="comptable-smoke-$(date +%s)"
PG_PWD="$(aleatoire 18)"; JWT="$(aleatoire 40)"; K1="$(aleatoire 24)"; K2="$(aleatoire 24)"; TOKEN_PROV="$(aleatoire 20)"
DOCS="$(mktemp -d)"; JOURNAL="$(mktemp)"; PID=""
DOCS_JAVA="$(chemin "$DOCS")"
PIDS=""
arreter() {   # termine les JVM lances par demarrer. Git Bash : kill ne touche pas le processus Windows natif ; on arrete donc par pid Windows puis, a coup sur, par port d'ecoute.
	for p in $PIDS; do
		if [ -r "/proc/$p/winpid" ]; then taskkill //PID "$(cat "/proc/$p/winpid")" //F //T >/dev/null 2>&1; else kill "$p" 2>/dev/null; fi
	done
	for port in "$PORT_PROD" "$((PORT_PROD + 1))"; do
		for w in $(netstat -ano 2>/dev/null | grep -E "[:.]$port +.*LISTEN" | awk '{print $NF}' | sort -u); do
			[ "$w" -gt 0 ] 2>/dev/null && taskkill //PID "$w" //F //T >/dev/null 2>&1
		done
	done
	PIDS=""
}
cleanup() { arreter; docker rm -f "$NOM" >/dev/null 2>&1; rm -rf "$DOCS" "$JOURNAL"; }
trap cleanup EXIT

echo "== PostgreSQL jetable ($PG_IMAGE) =="
docker run -d --name "$NOM" -e POSTGRES_USER=comptable_app -e POSTGRES_PASSWORD="$PG_PWD" -e POSTGRES_DB=comptable -p "$PG_PORT:5432" "$PG_IMAGE" >/dev/null || exit 1
for _ in $(seq 1 60); do docker exec "$NOM" pg_isready -U comptable_app -d comptable >/dev/null 2>&1 && break; sleep 1; done
sleep 2

demarrer() { # <fichier d'environnement sous forme de VAR=valeur...> : lance le jar, attend soit la disponibilité, soit l'arrêt
	( env "$@" java -jar "$JAR" --server.port="$PORT" >> "$JOURNAL" 2>&1 ) &
	PID=$!; PIDS="$PIDS $PID"
}

echo "== Étape 1 : configuration FAIBLE -> démarrage refusé attendu =="
demarrer SPRING_PROFILES_ACTIVE=prod SPRING_DATASOURCE_URL="jdbc:postgresql://localhost:$PG_PORT/comptable" SPRING_DATASOURCE_USERNAME=comptable_app SPRING_DATASOURCE_PASSWORD="$PG_PWD" \
	COMPTABLE_REGISTRATION_MODE=OPEN DOCUMENTS_STORAGE_DIR="$DOCS_JAVA"
for _ in $(seq 1 120); do kill -0 "$PID" 2>/dev/null || break; sleep 1; done
if kill -0 "$PID" 2>/dev/null; then
	echo "  [ECHEC] le serveur a démarré avec une configuration faible"; exit 1
fi
if grep -q "démarrage refusé" "$JOURNAL"; then
	echo "  [OK]    démarrage refusé, défauts listés :"
	grep -E "^\s*-|JWT_SECRET|SPRING_DATASOURCE_PASSWORD|OPEN interdit|AI_SERVICE" "$JOURNAL" | sed 's/^.*Caused by: //' | head -12 | cut -c1-160
	grep -q "$PG_PWD" "$JOURNAL" && { echo "  [ECHEC] le mot de passe de base apparaît dans les journaux"; exit 1; }
else
	echo "  [ECHEC] arrêt sans le message « démarrage refusé » :"; tail -20 "$JOURNAL" | cut -c1-200; exit 1
fi
PID=""
if [ "$(docker exec "$NOM" psql -U comptable_app -d comptable -At -c "select to_regclass('public.flyway_schema_history') is null")" = "t" ]; then
	echo "  [OK]    aucune migration appliquée : la configuration est refusée AVANT toute modification de la base"
else
	echo "  [ECHEC] la base a été migrée alors que la configuration était invalide"; exit 1
fi

echo "== Étape 2 : configuration saine, base vide =="
demarrer SPRING_PROFILES_ACTIVE=prod SPRING_DATASOURCE_URL="jdbc:postgresql://localhost:$PG_PORT/comptable" SPRING_DATASOURCE_USERNAME=comptable_app SPRING_DATASOURCE_PASSWORD="$PG_PWD" \
	JWT_SECRET="$JWT" AI_SERVICE_OUTBOUND_API_KEY="$K1" AI_SERVICE_API_KEY="$K2" COMPTABLE_REGISTRATION_MODE=PROVISIONING COMPTABLE_PROVISIONING_TOKEN="$TOKEN_PROV" \
	DOCUMENTS_STORAGE_DIR="$DOCS_JAVA" COLLECTE_LIEN_DEPOT_BASE_URL="https://pilote.example.org/depot"
T0=$(date +%s)
PRET=0
for _ in $(seq 1 240); do
	kill -0 "$PID" 2>/dev/null || break
	[ "$(curl -s -o /dev/null -w '%{http_code}' "http://localhost:$PORT/actuator/health/readiness" 2>/dev/null)" = "200" ] && { PRET=1; break; }
	sleep 1
done
if [ "$PRET" != "1" ]; then echo "  [ECHEC] pas prêt :"; tail -25 "$JOURNAL" | cut -c1-220; exit 1; fi
echo "  [OK]    readiness UP après $(( $(date +%s) - T0 )) s (migrations appliquées sur base vide)"
docker exec "$NOM" psql -U comptable_app -d comptable -At -c "select 'comptes par défaut : ' || (select count(*) from utilisateur) || ' utilisateur(s), ' || (select count(*) from tenant) || ' cabinet(s), version Flyway ' || (select max(version::int) from flyway_schema_history where success)"

echo "== Étape 3 : smoke test pilote =="
URL="http://localhost:$PORT" COMPTABLE_PROVISIONING_TOKEN="$TOKEN_PROV" ./scripts/smoke-pilot.sh
CODE=$?

echo "== Étape 4 : profil staging (liste blanche d'envoi, même base) =="
PORT=$((PORT + 1))   # le JVM prod reste actif jusqu'au nettoyage : le staging écoute sur le port suivant
demarrer SPRING_PROFILES_ACTIVE=staging SPRING_DATASOURCE_URL="jdbc:postgresql://localhost:$PG_PORT/comptable" SPRING_DATASOURCE_USERNAME=comptable_app SPRING_DATASOURCE_PASSWORD="$PG_PWD" 	JWT_SECRET="$JWT" AI_SERVICE_OUTBOUND_API_KEY="$K1" AI_SERVICE_API_KEY="$K2" COMPTABLE_REGISTRATION_MODE=PROVISIONING COMPTABLE_PROVISIONING_TOKEN="$TOKEN_PROV" 	DOCUMENTS_STORAGE_DIR="$DOCS_JAVA" COLLECTE_LIEN_DEPOT_BASE_URL="https://staging.example.org/depot" COLLECTE_SANDBOX_ALLOWLIST="22670000000"
PRET=0
for _ in $(seq 1 240); do
	kill -0 "$PID" 2>/dev/null || break
	[ "$(curl -s -o /dev/null -w '%{http_code}' "http://localhost:$PORT/actuator/health/readiness" 2>/dev/null)" = "200" ] && { PRET=1; break; }
	sleep 1
done
if [ "$PRET" = "1" ]; then echo "  [OK]    staging : readiness UP (profil staging, liste blanche d'envoi renseignée)"; else echo "  [ECHEC] staging : pas prêt"; tail -15 "$JOURNAL" | cut -c1-200; CODE=1; fi

echo "== Journaux : aucune fuite =="
for motif in "$PG_PWD" "$JWT" "$K1" "$K2" "$TOKEN_PROV" "Smk-"; do
	grep -q -- "$motif" "$JOURNAL" && { echo "  [ECHEC] une valeur secrète (ou un mot de passe de test) apparaît dans les journaux"; CODE=1; }
done
grep -qE "Exception|at com\.example" "$JOURNAL" && echo "  [NOTE]  exceptions présentes dans les journaux (voir ci-dessous)" && grep -E "ERROR|Exception" "$JOURNAL" | head -5 | cut -c1-220
[ "$CODE" = "0" ] && echo "  [OK]    aucune valeur secrète dans les journaux"
exit "$CODE"
