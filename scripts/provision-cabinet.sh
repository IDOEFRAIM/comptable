#!/usr/bin/env bash
# Approvisionnement d'un cabinet pilote par l'OPÉRATEUR de la plateforme : crée le cabinet et son premier expert-comptable, sans SQL, sans Swagger, sans Postman.
#
# Usage :  ./scripts/provision-cabinet.sh "<Nom du cabinet>" <email-expert> <Nom> <Prénom> [PAYS=BF]
#          (ou :  make provision CABINET="Cabinet X" EMAIL=expert@x.org NOM=Dupont PRENOM=Awa PAYS=BF)
# Variables : URL (défaut http://localhost:${HTTP_PORT:-80}), MOT_DE_PASSE (sinon un mot de passe fort est généré et affiché UNE fois), TELEPHONE (facultatif).
#
# Le jeton d'approvisionnement (COMPTABLE_PROVISIONING_TOKEN dans .env) n'est jamais affiché. L'expert doit changer son mot de passe s'il lui est communiqué par l'opérateur.
# Après la création : connexion de l'expert sur l'application, puis suivre docs/operations/cabinet-onboarding.md (équipe, première entreprise, plan comptable).
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$ROOT"
set -a; [ -f .env ] && . ./.env; set +a

CABINET="${1:-}"; EMAIL="${2:-}"; NOM="${3:-}"; PRENOM="${4:-}"; PAYS="${5:-BF}"
if [ -z "$CABINET" ] || [ -z "$EMAIL" ] || [ -z "$NOM" ] || [ -z "$PRENOM" ]; then
	sed -n '2,9p' "$0" | sed 's/^# \{0,1\}//'
	exit 1
fi
[ -n "${COMPTABLE_PROVISIONING_TOKEN:-}" ] || { echo "COMPTABLE_PROVISIONING_TOKEN absent de .env (voir scripts/generate-env.sh)." >&2; exit 1; }
command -v curl >/dev/null || { echo "curl requis." >&2; exit 1; }

URL="${URL:-http://localhost:${HTTP_PORT:-80}}"
GENERE=0
if [ -z "${MOT_DE_PASSE:-}" ]; then
	MOT_DE_PASSE="$(openssl rand -base64 24 | tr -d '\n=/+' | head -c 20)Aa1!"
	GENERE=1
fi

json_escape() { printf '%s' "$1" | sed -e 's/\\/\\\\/g' -e 's/"/\\"/g'; }
TEL_JSON="null"; if [ -n "${TELEPHONE:-}" ]; then TEL_JSON="\"$(json_escape "$TELEPHONE")\""; fi
CORPS="{\"raisonSocialeCabinet\":\"$(json_escape "$CABINET")\",\"pays\":\"$(json_escape "$PAYS")\",\"email\":\"$(json_escape "$EMAIL")\",\"motDePasse\":\"$(json_escape "$MOT_DE_PASSE")\",\"nom\":\"$(json_escape "$NOM")\",\"prenom\":\"$(json_escape "$PRENOM")\",\"telephone\":$TEL_JSON}"

REPONSE="$(mktemp)"; trap 'rm -f "$REPONSE"' EXIT
CODE="$(curl -sS -o "$REPONSE" -w '%{http_code}' -X POST "$URL/api/auth/register-cabinet" -H 'Content-Type: application/json' -H "X-Provisioning-Token: $COMPTABLE_PROVISIONING_TOKEN" --data "$CORPS")"

case "$CODE" in
	201)
		echo "Cabinet « $CABINET » créé. Expert-comptable : $EMAIL"
		if [ "$GENERE" = "1" ]; then
			echo "Mot de passe initial (affiché UNE seule fois — à transmettre par un canal sûr, l'expert doit le changer) : $MOT_DE_PASSE"
		fi
		echo "Suite : connexion sur $URL/login puis docs/operations/cabinet-onboarding.md."
		;;
	403) echo "Refusé (403) : le jeton d'approvisionnement ne correspond pas à celui du serveur." >&2; exit 1 ;;
	409) echo "Refusé (409) : cette adresse e-mail est déjà utilisée." >&2; exit 1 ;;
	422) echo "Refusé (422) : $(sed -n 's/.*"message":"\([^"]*\)".*/\1/p' "$REPONSE")" >&2; exit 1 ;;
	429) echo "Refusé (429) : trop de tentatives, patientez quelques minutes." >&2; exit 1 ;;
	*)   echo "Échec ($CODE) : $(sed -n 's/.*"message":"\([^"]*\)".*/\1/p' "$REPONSE")" >&2; exit 1 ;;
esac
