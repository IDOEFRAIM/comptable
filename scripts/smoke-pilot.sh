#!/usr/bin/env bash
# Smoke test « pilote » sur une instance DÉJÀ DÉMARRÉE (profil prod / staging) : parcourt par l'API réelle ce que fait un cabinet, sans SQL, Swagger ni Postman.
#
#   sondes de santé -> création de cabinet refusée sans jeton, acceptée avec -> connexion (mauvais mot de passe refusé) -> entreprise -> écriture validée -> pièce -> relevé bancaire (aperçu + import,
#   réimport sans doublon) -> contrôles -> dossier financier -> TVA : cadre réglementaire non vérifié, finalisation impossible -> isolation entre cabinets -> diagnostic de support.
#
# Usage :  URL=http://localhost:8081 COMPTABLE_PROVISIONING_TOKEN=... ./scripts/smoke-pilot.sh
#          (jeton lu dans .env s'il n'est pas fourni). Crée un cabinet jetable « smoke-<hasard> » : à n'exécuter que sur une instance de test, de staging, ou une installation neuve avant remise au cabinet.
# Code de sortie : 0 si tout passe, 1 sinon. Aucun secret n'est affiché.
set -uo pipefail
ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$ROOT"
[ -f .env ] && [ -z "${COMPTABLE_PROVISIONING_TOKEN:-}" ] && { set -a; . ./.env; set +a; }
URL="${URL:-http://localhost:${HTTP_PORT:-80}}"
JETON="${COMPTABLE_PROVISIONING_TOKEN:-}"
PY="$(command -v python3 || command -v python || true)"
[ -n "$PY" ] || { echo "python3 requis pour lire les réponses JSON." >&2; exit 1; }
command -v curl >/dev/null || { echo "curl requis." >&2; exit 1; }
[ -n "$JETON" ] || { echo "COMPTABLE_PROVISIONING_TOKEN absent (variable ou .env)." >&2; exit 1; }

ECHECS=0; N=0
ok()   { N=$((N + 1)); echo "  [OK]    $*"; }
fail() { N=$((N + 1)); ECHECS=$((ECHECS + 1)); echo "  [ECHEC] $*"; }
SUFFIXE="$(head -c 6 /dev/urandom | od -An -tx1 | tr -d ' \n')"
MDP="Smk-$(head -c 9 /dev/urandom | od -An -tx1 | tr -d ' \n')-Aa1!"
MDP2="Smk-$(head -c 9 /dev/urandom | od -An -tx1 | tr -d ' \n')-Bb2!"
TMP="$(mktemp -d)"; command -v cygpath >/dev/null 2>&1 && TMP="$(cygpath -m "$TMP")"   # Git Bash : curl et python (Windows) attendent un chemin Windows
trap 'rm -rf "$TMP"' EXIT

# appel <méthode> <chemin> [corps JSON] [jeton] -> statut dans $CODE, corps dans $TMP/r
appel() {
	local m="$1" p="$2" corps="${3:-}" tok="${4:-}"
	local args=(-sS -o "$TMP/r" -w '%{http_code}' -X "$m" "$URL$p")
	[ -n "$tok" ] && args+=(-H "Authorization: Bearer $tok")
	if [ -n "$corps" ]; then args+=(-H 'Content-Type: application/json' --data "$corps"); fi
	CODE="$(curl "${args[@]}" 2>/dev/null || echo 000)"
}
json() { "$PY" -c "import json,sys
d=json.load(open('$TMP/r',encoding='utf-8'))
for k in sys.argv[1].split('.'):
    d=d[int(k)] if k.isdigit() else d[k]
print(d if not isinstance(d,bool) else str(d).lower())" "$1" 2>/dev/null; }
attendu() { # <statut attendu> <libellé>
	[ "$CODE" = "$1" ] && ok "$2 (HTTP $CODE)" || fail "$2 : attendu $1, reçu $CODE ($(head -c 200 "$TMP/r" 2>/dev/null))"
}

echo "== Smoke test pilote sur $URL =="

echo "-- Santé"
appel GET /actuator/health/liveness;  attendu 200 "liveness"
appel GET /actuator/health/readiness; attendu 200 "readiness"
grep -q '"UP"' "$TMP/r" && ok "readiness UP" || fail "readiness pas UP"
grep -qiE 'password|jdbc|datasource' "$TMP/r" && fail "la sonde expose des détails internes" || ok "la sonde n'expose aucun détail interne"
appel GET /actuator/env; [ "$CODE" = "200" ] && fail "/actuator/env est exposé" || ok "/actuator/env non exposé (HTTP $CODE)"

echo "-- Création du cabinet (opérateur)"
CORPS="{\"raisonSocialeCabinet\":\"Smoke $SUFFIXE\",\"pays\":\"BF\",\"email\":\"smoke-$SUFFIXE@example.org\",\"motDePasse\":\"$MDP\",\"nom\":\"Expert\",\"prenom\":\"Smoke\",\"telephone\":null}"
appel POST /api/auth/register-cabinet "$CORPS"
[ "$CODE" = "403" ] && ok "création de cabinet refusée sans jeton d'approvisionnement" || fail "création de cabinet sans jeton : attendu 403, reçu $CODE"
CODE="$(curl -sS -o "$TMP/r" -w '%{http_code}' -X POST "$URL/api/auth/register-cabinet" -H 'Content-Type: application/json' -H "X-Provisioning-Token: $JETON" --data "$CORPS" 2>/dev/null || echo 000)"
attendu 201 "création du cabinet avec le jeton"

echo "-- Connexion"
appel POST /api/auth/login "{\"email\":\"smoke-$SUFFIXE@example.org\",\"motDePasse\":\"mauvais-mot-de-passe\"}"; attendu 401 "mauvais mot de passe refusé"
grep -q 'correlationId' "$TMP/r" && ok "erreur au format unique avec correlationId" || fail "format d'erreur sans correlationId"
appel POST /api/auth/login "{\"email\":\"smoke-$SUFFIXE@example.org\",\"motDePasse\":\"$MDP\"}"; attendu 200 "connexion de l'expert"
TOK="$(json token)"; [ -n "$TOK" ] && ok "jeton de session obtenu" || fail "pas de jeton"
appel GET /api/entreprises ""; [ "$CODE" = "401" ] || [ "$CODE" = "403" ] && ok "API sans jeton refusée (HTTP $CODE)" || fail "API sans jeton accessible ($CODE)"

echo "-- Entreprise, exercice, plan comptable"
appel POST /api/entreprises '{"raisonSociale":"Societe Smoke","numeroContribuable":null,"rccm":null,"formeJuridique":null,"regimeFiscal":null,"secteurActivite":null,"pays":"BF","devise":"XOF"}' "$TOK"
attendu 201 "création de l'entreprise (Burkina Faso)"; ENT="$(json id)"
B="/api/entreprises/$ENT"
appel GET "$B/exercices" "" "$TOK"; attendu 200 "exercice courant"; EXO="$(json 0.id)"
appel GET "$B/journaux" "" "$TOK"; JNL="$(json 0.id)"
appel GET "$B/comptes" "" "$TOK"; attendu 200 "plan comptable cloné"
NB="$("$PY" -c "import json;print(len(json.load(open('$TMP/r',encoding='utf-8'))))")"; [ "$NB" -gt 50 ] && ok "$NB comptes disponibles" || fail "plan comptable trop petit ($NB)"
cpt() { "$PY" -c "import json,sys
l=json.load(open('$TMP/r',encoding='utf-8'))
print(next((c['id'] for c in l if c['numero']==sys.argv[1]),''))" "$1"; }
C521="$(cpt 521)"; C101="$(cpt 101)"
if [ -z "$C521" ]; then appel POST "$B/comptes" '{"numero":"521","intitule":"Banque","lettrable":false}' "$TOK"; C521="$(json id)"; fi
if [ -z "$C101" ]; then appel POST "$B/comptes" '{"numero":"101","intitule":"Capital social","lettrable":false}' "$TOK"; C101="$(json id)"; fi

echo "-- Comptabilité minimale"
AUJ="$(date +%Y-%m-%d)"
appel POST "$B/ecritures" "{\"journalId\":\"$JNL\",\"dateEcriture\":\"$AUJ\",\"libelle\":\"Capital en numeraire\",\"numeroPiece\":null,\"lignes\":[{\"compteId\":\"$C521\",\"libelle\":\"Capital\",\"debit\":1000000,\"credit\":0,\"tiersId\":null},{\"compteId\":\"$C101\",\"libelle\":\"Capital\",\"debit\":0,\"credit\":1000000,\"tiersId\":null}]}" "$TOK"
attendu 201 "écriture équilibrée enregistrée"; ECR="$(json id)"
appel POST "$B/ecritures/$ECR/valider" '{}' "$TOK"; attendu 200 "écriture validée"
appel POST "$B/ecritures" "{\"journalId\":\"$JNL\",\"dateEcriture\":\"$AUJ\",\"libelle\":\"Desequilibree\",\"numeroPiece\":null,\"lignes\":[{\"compteId\":\"$C521\",\"libelle\":\"x\",\"debit\":10,\"credit\":0,\"tiersId\":null},{\"compteId\":\"$C101\",\"libelle\":\"x\",\"debit\":0,\"credit\":5,\"tiersId\":null}]}" "$TOK"
[ "$CODE" = "422" ] || [ "$CODE" = "400" ] && ok "écriture déséquilibrée refusée (HTTP $CODE)" || fail "écriture déséquilibrée acceptée ($CODE)"

echo "-- Pièce justificative"
printf '\x89PNG\r\n\x1a\n\x00\x00\x00\rIHDR\x00\x00\x00\x01\x00\x00\x00\x01\x08\x06\x00\x00\x00\x1f\x15\xc4\x89\x00\x00\x00\rIDATx\x9cc\xf8\xff\xff?\x00\x05\xfe\x02\xfe\xa7\x9a\xa0\xa0\x00\x00\x00\x00IEND\xaeB`\x82' > "$TMP/p.png"
CODE="$(curl -sS -o "$TMP/r" -w '%{http_code}' -X POST "$URL$B/documents" -H "Authorization: Bearer $TOK" -F typeDocument=RECU -F "fichier=@$TMP/p.png;type=image/png" 2>/dev/null || echo 000)"
[ "$CODE" = "200" ] || [ "$CODE" = "201" ] || [ "$CODE" = "202" ] && ok "pièce PNG déposée (HTTP $CODE)" || fail "dépôt de pièce : $CODE ($(head -c 200 "$TMP/r"))"
printf 'MZ\x90\x00 pas une image' > "$TMP/f.png"
CODE="$(curl -sS -o "$TMP/r" -w '%{http_code}' -X POST "$URL$B/documents" -H "Authorization: Bearer $TOK" -F typeDocument=RECU -F "fichier=@$TMP/f.png;type=image/png" 2>/dev/null || echo 000)"
case "$CODE" in 415) ok "faux PNG (exécutable renommé) refusé : HTTP 415";; *) fail "faux PNG : attendu 415, reçu $CODE";; esac

echo "-- Banque (relevé CSV)"
appel GET "$B/comptes" "" "$TOK"
cat > "$TMP/releve.csv" <<CSV
date;libelle;debit;credit
$AUJ;Virement client Smoke;;250000
$AUJ;Frais bancaires;1500;
CSV
CODE="$(curl -sS -o "$TMP/r" -w '%{http_code}' -X POST "$URL$B/banque/imports/apercu" -H "Authorization: Bearer $TOK" -F "compteId=$C521" -F "fichier=@$TMP/releve.csv;type=text/csv" 2>/dev/null || echo 000)"
[ "$CODE" = "200" ] && ok "aperçu de l'import bancaire (rien n'est écrit)" || fail "aperçu bancaire : $CODE ($(head -c 200 "$TMP/r"))"
CODE="$(curl -sS -o "$TMP/r" -w '%{http_code}' -X POST "$URL$B/banque/imports" -H "Authorization: Bearer $TOK" -F "compteId=$C521" -F "fichier=@$TMP/releve.csv;type=text/csv" 2>/dev/null || echo 000)"
[ "$CODE" = "201" ] && ok "relevé importé" || fail "import bancaire : $CODE ($(head -c 200 "$TMP/r"))"
CODE="$(curl -sS -o "$TMP/r" -w '%{http_code}' -X POST "$URL$B/banque/imports" -H "Authorization: Bearer $TOK" -F "compteId=$C521" -F "fichier=@$TMP/releve.csv;type=text/csv" 2>/dev/null || echo 000)"
[ "$CODE" = "200" ] && ok "réimport du même relevé : aucun doublon créé" || fail "réimport : attendu 200 (rien de nouveau), reçu $CODE"

echo "-- Contrôles et dossier financier"
appel POST "$B/revue/executer" '{}' "$TOK"; attendu 200 "contrôles exécutés"
for ch in dossier-financier compte-resultat bilan tft notes; do
	appel GET "$B/etats-financiers-syscohada/$ch?exerciceId=$EXO" "" "$TOK"; attendu 200 "état financier « $ch »"
done

echo "-- Garde-fou fiscal : TVA Burkina Faso"
appel POST "$B/fiscalite/tva/periodes" "{\"periodStart\":\"$(date +%Y-%m-01)\",\"periodEnd\":\"$(date +%Y-%m-28)\"}" "$TOK"
if [ "$CODE" = "201" ]; then
	PER="$(json id)"
	REG="$(json regulatoryPackVerified)"
	[ "$REG" = "false" ] && ok "cadre réglementaire non vérifié (« TVA Burkina : cadre réglementaire non vérifié, finalisation indisponible »)" || fail "cadre réglementaire annoncé vérifié pour le Burkina : $REG"
	appel POST "$B/fiscalite/tva/periodes/$PER/finaliser" '{}' "$TOK"
	case "$CODE" in 2*) fail "finalisation TVA acceptée sans cadre vérifié ($CODE)";; *) ok "finalisation TVA refusée (HTTP $CODE)";; esac
else
	fail "ouverture de la période de TVA : $CODE ($(head -c 200 "$TMP/r"))"
fi

echo "-- Isolation entre cabinets"
CORPS2="{\"raisonSocialeCabinet\":\"Smoke B $SUFFIXE\",\"pays\":\"BF\",\"email\":\"smoke-b-$SUFFIXE@example.org\",\"motDePasse\":\"$MDP2\",\"nom\":\"Autre\",\"prenom\":\"Cabinet\",\"telephone\":null}"
CODE="$(curl -sS -o "$TMP/r" -w '%{http_code}' -X POST "$URL/api/auth/register-cabinet" -H 'Content-Type: application/json' -H "X-Provisioning-Token: $JETON" --data "$CORPS2" 2>/dev/null || echo 000)"
attendu 201 "second cabinet créé"
appel POST /api/auth/login "{\"email\":\"smoke-b-$SUFFIXE@example.org\",\"motDePasse\":\"$MDP2\"}"; TOKB="$(json token)"
for chemin in "" "/ecritures" "/documents" "/comptes" "/etats-financiers-syscohada/bilan?exerciceId=$EXO"; do
	appel GET "$B$chemin" "" "$TOKB"
	[ "$CODE" = "404" ] || [ "$CODE" = "403" ] && ok "autre cabinet : GET ${chemin:-/entreprise} -> $CODE" || fail "autre cabinet : GET ${chemin:-/entreprise} -> $CODE (fuite possible)"
done

echo "-- Support"
appel GET /api/cabinet/support/diagnostic "" "$TOK"; attendu 200 "diagnostic de support (expert)"
grep -qiE 'exception|stacktrace|password|secret' "$TMP/r" && fail "le diagnostic contient des informations sensibles" || ok "le diagnostic ne contient ni trace ni secret"

echo
echo "== Résultat : $((N - ECHECS))/$N contrôles passés =="
[ "$ECHECS" -eq 0 ] && { echo "SMOKE PILOTE : OK"; exit 0; }
echo "SMOKE PILOTE : $ECHECS ÉCHEC(S)"; exit 1
