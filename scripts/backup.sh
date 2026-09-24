#!/usr/bin/env bash
# Sauvegarde complete de l'etat persistant du deploiement :
#   - dump SQL de la base Postgres (pg_dump, format custom compresse) ;
#   - archive tar.gz du volume des pieces justificatives uploadees (documents_data).
#
# Usage :  ./scripts/backup.sh [repertoire_de_sortie]
#          (defaut : ./backups)  -> cree <sortie>/comptable-YYYYmmdd-HHMMSS/{db.dump,documents.tar.gz}
#
# A planifier via cron sur l'hote, ex. tous les jours a 2h :
#   0 2 * * * cd /opt/saas-comptable && ./scripts/backup.sh /var/backups/comptable >> /var/log/comptable-backup.log 2>&1
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$ROOT"

OUT_BASE="${1:-$ROOT/backups}"
STAMP="$(date +%Y%m%d-%H%M%S)"
OUT="$OUT_BASE/comptable-$STAMP"
mkdir -p "$OUT"

# Charge POSTGRES_USER / POSTGRES_DB depuis .env (defauts alignes sur docker-compose.yml).
set -a; [ -f .env ] && . ./.env; set +a
PG_USER="${POSTGRES_USER:-comptable}"
PG_DB="${POSTGRES_DB:-comptable}"

COMPOSE="docker compose"
$COMPOSE ps postgres >/dev/null 2>&1 || { echo "La stack n'est pas demarree (docker compose up -d)." >&2; exit 1; }

echo "[1/2] Dump Postgres ($PG_DB) ..."
$COMPOSE exec -T postgres pg_dump -U "$PG_USER" -d "$PG_DB" -Fc > "$OUT/db.dump"

echo "[2/2] Archive du volume documents_data ..."
VOL="$($COMPOSE ps -q frontend >/dev/null 2>&1; docker volume ls --format '{{.Name}}' | grep -E '_documents_data$' | head -1)"
[ -n "$VOL" ] || { echo "Volume *_documents_data introuvable." >&2; exit 1; }
docker run --rm -v "$VOL":/data:ro -v "$OUT":/backup alpine \
	tar czf /backup/documents.tar.gz -C /data .

SIZE="$(du -sh "$OUT" | cut -f1)"
echo "OK -> $OUT ($SIZE)"
echo "Restauration :  ./scripts/restore.sh $OUT"

# Retention : garde les 14 sauvegardes les plus recentes dans OUT_BASE.
ls -1dt "$OUT_BASE"/comptable-* 2>/dev/null | tail -n +15 | while read -r old; do
	echo "Purge ancienne sauvegarde : $old"
	rm -rf "$old"
done
