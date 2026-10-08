#!/usr/bin/env bash
# Genere un fichier .env pret pour la production a partir de .env.example :
#   - les 4 secrets obligatoires (POSTGRES_PASSWORD, JWT_SECRET, AI_SERVICE_OUTBOUND_API_KEY,
#     AI_SERVICE_INBOUND_API_KEY) sont remplis avec des valeurs aleatoires fortes ;
#   - tout le reste garde la valeur d'exemple (a ajuster a la main : DOMAIN, ACME_EMAIL, cles IA...).
#
# Usage :  ./scripts/generate-env.sh [chemin_du_.env]
# Refuse d'ecraser un .env existant (le deplacer ou le supprimer d'abord).
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
EXAMPLE="$ROOT/.env.example"
TARGET="${1:-$ROOT/.env}"

[ -f "$EXAMPLE" ] || { echo "Introuvable : $EXAMPLE" >&2; exit 1; }
if [ -e "$TARGET" ]; then
	echo "Refuse : $TARGET existe deja. Supprime-le ou passe un autre chemin." >&2
	exit 1
fi

command -v openssl >/dev/null || { echo "openssl requis." >&2; exit 1; }

rand()  { openssl rand -hex 32; }          # 64 caracteres hex
randb64() { openssl rand -base64 48 | tr -d '\n' | tr '+/' 'Aa'; }  # ~64 car., sans =,+,/

PG_PWD="$(rand)"
JWT="$(randb64)"
OUTBOUND="$(rand)"
INBOUND="$(rand)"
PROVISIONING="$(rand)"

# Remplit uniquement les lignes VARIABLE= vides (ne touche jamais une valeur deja renseignee).
set_var() {
	local key="$1" val="$2"
	if grep -qE "^${key}=$" "$TARGET"; then
		# escape & et / et \ pour sed
		local esc; esc="$(printf '%s' "$val" | sed -e 's/[&/\\]/\\&/g')"
		sed -i.bak "s/^${key}=$/${key}=${esc}/" "$TARGET" && rm -f "$TARGET.bak"
		echo "  $key  genere"
	else
		echo "  $key  deja renseigne dans .env.example, laisse tel quel"
	fi
}

cp "$EXAMPLE" "$TARGET"
echo "Cree : $TARGET (copie de .env.example)"
set_var POSTGRES_PASSWORD "$PG_PWD"
set_var JWT_SECRET "$JWT"
set_var AI_SERVICE_OUTBOUND_API_KEY "$OUTBOUND"
set_var AI_SERVICE_INBOUND_API_KEY "$INBOUND"
set_var COMPTABLE_PROVISIONING_TOKEN "$PROVISIONING"

echo
echo "Secrets generes. Reste a renseigner a la main dans $TARGET :"
echo "  - DOMAIN et ACME_EMAIL          (obligatoires pour le mode HTTPS / make deploy)"
echo "  - COLLECTE_LIEN_DEPOT_BASE_URL  (obligatoire : URL publique des liens de depot, ex. https://\$DOMAIN/depot)"
echo "  - GROQ_API_KEY / ANTHROPIC_API_KEY + AI_PROVIDER / AGENT_*_ENABLED   (si IA voulue)"
echo "  - variables WHATSAPP_*          (si relance WhatsApp voulue)"
