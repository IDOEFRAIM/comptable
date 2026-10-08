#!/usr/bin/env bash
# Exercice de restauration (restore drill) : prouve qu'une sauvegarde PostgreSQL se RESTAURE vraiment, dans une base neuve et jetable, et que les données sont intactes.
#
#   1. pg_dump (format custom) de la base source ;
#   2. démarrage d'un PostgreSQL jetable (même version majeure) ;
#   3. pg_restore dans une base vierge ;
#   4. contrôles : historique Flyway identique et sans échec, comptes de lignes des tables critiques identiques, empreintes des snapshots (états financiers, déclarations fiscales)
#      identiques, triggers d'immuabilité présents, aucune migration en attente ;
#   5. suppression du PostgreSQL jetable, rapport des durées (indication de RTO) et de la taille.
#
# Usage :  ./scripts/restore-drill.sh
# Variables : SRC_CONTAINER (défaut : service postgres de docker compose), SRC_USER / SRC_DB (défaut : lus dans le conteneur), PG_IMAGE (défaut : image du conteneur source),
#             DRILL_DIR (défaut : ./backups/drill-<horodatage>), KEEP=1 pour conserver le conteneur jetable.
# Le stockage binaire des pièces (volume documents_data) est sauvegardé/restauré par scripts/backup.sh et scripts/restore.sh : ce script vérifie la BASE (métadonnées des pièces comprises).
# Code de sortie : 0 si TOUS les contrôles passent, 1 sinon.
set -euo pipefail
export MSYS_NO_PATHCONV=1   # Git Bash (Windows) : ne pas réécrire les chemins /tmp/... passés à docker

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$ROOT"

SRC_CONTAINER="${SRC_CONTAINER:-$(docker compose ps -q postgres 2>/dev/null | head -1)}"
[ -n "$SRC_CONTAINER" ] || { echo "Conteneur source introuvable : définissez SRC_CONTAINER." >&2; exit 1; }
SRC_USER="${SRC_USER:-$(docker exec "$SRC_CONTAINER" printenv POSTGRES_USER)}"
SRC_DB="${SRC_DB:-$(docker exec "$SRC_CONTAINER" printenv POSTGRES_DB)}"
PG_IMAGE="${PG_IMAGE:-$(docker inspect --format '{{.Config.Image}}' "$SRC_CONTAINER")}"
STAMP="$(date +%Y%m%d-%H%M%S)"
DRILL_DIR="${DRILL_DIR:-$ROOT/backups/drill-$STAMP}"
TMP_NAME="comptable-drill-$STAMP"
TMP_PWD="$(head -c 24 /dev/urandom | base64 | tr -d '+/=')"   # mot de passe jetable, jamais affiché ni conservé
mkdir -p "$DRILL_DIR"

now() { date +%s; }
ECHECS=0
ok()   { echo "  [OK]    $*"; }
fail() { echo "  [ECHEC] $*"; ECHECS=$((ECHECS + 1)); }
src()  { docker exec "$SRC_CONTAINER" psql -U "$SRC_USER" -d "$SRC_DB" -At -c "$1"; }
dst()  { docker exec "$TMP_NAME" psql -U drill -d restored -At -c "$1"; }

cleanup() {
	if [ "${KEEP:-0}" != "1" ]; then docker rm -f "$TMP_NAME" >/dev/null 2>&1 || true; fi
}
trap cleanup EXIT

echo "== Restore drill : $SRC_CONTAINER/$SRC_DB -> conteneur jetable $TMP_NAME ($PG_IMAGE) =="

T0=$(now)
if [ -n "${REUSE_DUMP:-}" ] && [ -f "${REUSE_DUMP}" ]; then
	echo "[1/4] Sauvegarde réutilisée : $REUSE_DUMP"
	cp "$REUSE_DUMP" "$DRILL_DIR/db.dump"
else
	echo "[1/4] Sauvegarde (pg_dump -Fc) ..."
	docker exec "$SRC_CONTAINER" pg_dump -U "$SRC_USER" -d "$SRC_DB" -Fc > "$DRILL_DIR/db.dump"
fi
T1=$(now)
SIZE="$(du -h "$DRILL_DIR/db.dump" | cut -f1)"
echo "      -> $DRILL_DIR/db.dump ($SIZE, $((T1 - T0)) s)"

echo "[2/4] PostgreSQL jetable ..."
docker run -d --name "$TMP_NAME" -e POSTGRES_USER=drill -e POSTGRES_PASSWORD="$TMP_PWD" -e POSTGRES_DB=restored "$PG_IMAGE" >/dev/null
for _ in $(seq 1 60); do
	docker exec "$TMP_NAME" pg_isready -U drill -d restored >/dev/null 2>&1 && break
	sleep 1
done
docker exec "$TMP_NAME" pg_isready -U drill -d restored >/dev/null || { echo "PostgreSQL jetable indisponible." >&2; exit 1; }

echo "[3/4] Restauration (pg_restore) ..."
T2=$(now)
# la sauvegarde est transmise par l'entrée standard (aucun chemin d'hôte n'est exposé au conteneur : portable Linux / Windows)
docker exec -i "$TMP_NAME" pg_restore -U drill -d restored --no-owner --no-privileges --exit-on-error < "$DRILL_DIR/db.dump"
T3=$(now)
echo "      restauration terminée en $((T3 - T2)) s"

echo "[4/4] Contrôles d'intégrité ..."
# a) historique des migrations identique, sans échec
SRC_V="$(src "select max(version::int) from flyway_schema_history where success")"
DST_V="$(dst "select max(version::int) from flyway_schema_history where success")"
[ "$SRC_V" = "$DST_V" ] && ok "migrations : dernière version $DST_V identique" || fail "migrations : source $SRC_V, restauration $DST_V"
[ "$(dst "select count(*) from flyway_schema_history where not success")" = "0" ] && ok "aucune migration en échec" || fail "migration en échec dans la base restaurée"

# b) comptes de lignes des tables critiques (utilisateurs, cabinets, entreprises, comptabilité, pièces, banque, constats, états financiers, fiscalité)
for t in tenant utilisateur entreprise exercice_comptable compte_comptable journal ecriture_comptable ligne_ecriture document tiers bank_transaction finding financial_statement_note \
	financial_statement_snapshot tax_period tax_declaration_snapshot tax_payment audit_log; do
	if [ "$(src "select to_regclass('public.$t') is not null")" = "t" ]; then
		a="$(src "select count(*) from $t")"; b="$(dst "select count(*) from $t")"
		[ "$a" = "$b" ] && ok "$t : $b lignes" || fail "$t : source $a, restauration $b"
	fi
done

# c) empreintes des snapshots figés (états financiers et déclarations fiscales) : tout le contenu est identique
for t in financial_statement_snapshot tax_declaration_snapshot; do
	if [ "$(src "select to_regclass('public.$t') is not null")" = "t" ]; then
		a="$(src "select coalesce(md5(string_agg(payload_sha256 || id::text, ',' order by id)), 'vide') from $t")"
		b="$(dst "select coalesce(md5(string_agg(payload_sha256 || id::text, ',' order by id)), 'vide') from $t")"
		[ "$a" = "$b" ] && ok "$t : empreintes des snapshots identiques" || fail "$t : empreintes différentes"
	fi
done

# d) métadonnées des pièces (nom, hash du contenu, chemin) identiques
a="$(src "select coalesce(md5(string_agg(coalesce(content_hash,'') || coalesce(chemin_fichier,'') || id::text, ',' order by id)), 'vide') from document")"
b="$(dst "select coalesce(md5(string_agg(coalesce(content_hash,'') || coalesce(chemin_fichier,'') || id::text, ',' order by id)), 'vide') from document")"
[ "$a" = "$b" ] && ok "document : métadonnées (hash, chemin) identiques" || fail "document : métadonnées différentes"

# e) garde-fous restaurés : triggers d'immuabilité des snapshots, contraintes
for trg in trg_financial_statement_snapshot_immutable trg_tax_declaration_snapshot_immutable; do
	[ "$(dst "select count(*) from pg_trigger where tgname = '$trg'")" = "1" ] && ok "trigger $trg présent" || fail "trigger $trg absent"
done
FK_SRC="$(src "select count(*) from pg_constraint where contype = 'f'")"; FK_DST="$(dst "select count(*) from pg_constraint where contype = 'f'")"
[ "$FK_SRC" = "$FK_DST" ] && ok "$FK_DST clés étrangères restaurées" || fail "clés étrangères : source $FK_SRC, restauration $FK_DST"

# f) le snapshot restauré reste immuable
if [ "$(dst "select count(*) from financial_statement_snapshot")" != "0" ]; then
	if docker exec "$TMP_NAME" psql -U drill -d restored -c "update financial_statement_snapshot set payload = 'x' where id = (select id from financial_statement_snapshot limit 1)" >/dev/null 2>&1; then
		fail "snapshot restauré modifiable (trigger inopérant)"
	else
		ok "snapshot restauré toujours immuable"
	fi
fi

T4=$(now)
echo
echo "== Rapport =="
echo "  taille de la sauvegarde : $SIZE"
echo "  sauvegarde : $((T1 - T0)) s | restauration : $((T3 - T2)) s | contrôles : $((T4 - T3)) s | total : $((T4 - T0)) s"
echo "  (RTO indicatif de CE jeu de données sur CETTE machine ; ce n'est pas un engagement de niveau de service.)"
if [ "$ECHECS" -eq 0 ]; then
	echo "  RESULTAT : RESTAURATION VALIDEE (tous les contrôles passent)"
	exit 0
fi
echo "  RESULTAT : $ECHECS contrôle(s) en échec"
exit 1
