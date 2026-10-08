#!/usr/bin/env bash
# Supervision de la sauvegarde : echoue (code 1) si la derniere sauvegarde a echoue, est introuvable ou plus vieille que MAX_HEURES (defaut 26).
# Usage :  ./scripts/check-backup.sh [repertoire_de_sauvegardes]     (a brancher sur cron / une supervision externe ; ecrit une ligne OK ou ALERTE)
set -uo pipefail
ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
BASE="${1:-$ROOT/backups}"
MAX_HEURES="${MAX_HEURES:-26}"
F="$BASE/LAST_BACKUP_STATUS"
[ -f "$F" ] || { echo "ALERTE : aucune sauvegarde enregistree ($F absent)"; exit 1; }
read -r ETAT QUAND DOSSIER < "$F"
[ "$ETAT" = "OK" ] || { echo "ALERTE : la derniere sauvegarde a ECHOUE ($QUAND)"; exit 1; }
[ -s "$DOSSIER/db.dump" ] || { echo "ALERTE : $DOSSIER/db.dump absent ou vide"; exit 1; }
EPOCH="$(date -u -d "$QUAND" +%s 2>/dev/null || date -u -j -f %Y-%m-%dT%H:%M:%SZ "$QUAND" +%s 2>/dev/null || echo 0)"
AGE=$(( ($(date -u +%s) - EPOCH) / 3600 ))
[ "$EPOCH" -gt 0 ] && [ "$AGE" -le "$MAX_HEURES" ] || { echo "ALERTE : derniere sauvegarde trop ancienne (${AGE} h, maximum ${MAX_HEURES} h)"; exit 1; }
echo "OK : derniere sauvegarde $QUAND (${AGE} h), $DOSSIER"
