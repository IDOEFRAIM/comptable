#!/usr/bin/env bash
# Restaure une sauvegarde produite par backup.sh dans la stack ACTUELLE.
#
# Usage :  ./scripts/restore.sh <repertoire_de_sauvegarde>
#          (le repertoire doit contenir db.dump et documents.tar.gz)
#
# ATTENTION : ecrase la base et les documents actuels. Demande une confirmation explicite.
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$ROOT"

SRC="${1:?Usage: ./scripts/restore.sh <repertoire_de_sauvegarde>}"
[ -f "$SRC/db.dump" ] || { echo "Absent : $SRC/db.dump" >&2; exit 1; }
[ -f "$SRC/documents.tar.gz" ] || { echo "Absent : $SRC/documents.tar.gz" >&2; exit 1; }

set -a; [ -f .env ] && . ./.env; set +a
PG_USER="${POSTGRES_USER:-comptable}"
PG_DB="${POSTGRES_DB:-comptable}"
COMPOSE="docker compose"

echo "Cette operation VA ECRASER la base '$PG_DB' et le volume documents_data actuels."
read -r -p "Taper 'restaurer' pour confirmer : " ans
[ "$ans" = "restaurer" ] || { echo "Annule."; exit 1; }

echo "[1/3] Arret du backend (libere les connexions a la base) ..."
$COMPOSE stop backend

echo "[2/3] Restauration Postgres ..."
$COMPOSE exec -T postgres psql -U "$PG_USER" -d postgres -v ON_ERROR_STOP=1 \
	-c "DROP DATABASE IF EXISTS \"$PG_DB\";" -c "CREATE DATABASE \"$PG_DB\" OWNER \"$PG_USER\";"
$COMPOSE exec -T postgres pg_restore -U "$PG_USER" -d "$PG_DB" --no-owner --clean --if-exists < "$SRC/db.dump"

echo "[3/3] Restauration des documents ..."
VOL="$(docker volume ls --format '{{.Name}}' | grep -E '_documents_data$' | head -1)"
[ -n "$VOL" ] || { echo "Volume *_documents_data introuvable." >&2; exit 1; }
docker run --rm -v "$VOL":/data -v "$SRC":/backup:ro alpine sh -c \
	'rm -rf /data/* /data/..?* 2>/dev/null; tar xzf /backup/documents.tar.gz -C /data'

echo "Redemarrage ..."
$COMPOSE up -d
echo "OK. Verifier :  docker compose ps  &&  docker compose logs -f backend"
