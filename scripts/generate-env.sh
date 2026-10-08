#!/usr/bin/env bash
# Genere un fichier .env pret pour la production a partir de .env.example :
#   - les 4 secrets obligatoires (POSTGRES_PASSWORD, JWT_SECRET, AI_SERVICE_OUTBOUND_API_KEY,
#     AI_SERVICE_INBOUND_API_KEY) sont remplis avec des valeurs aleatoires fortes ;
#   - tout le reste garde la valeur d'exemple (a ajuster a la main : DOMAIN, ACME_EMAIL, cles IA...).
#
# Usage :  ./scripts/generate-env.sh [chemin_du_.env]
# Refuse d'ecraser un .env existant (le deplacer ou le supprimer d'abord).
#
# Mise a jour d'une installation anterieure a P0 :  ./scripts/generate-env.sh --completer [chemin_du_.env]
#   ajoute au .env EXISTANT les variables obligatoires manquantes (jeton d'approvisionnement genere, mode d'inscription PROVISIONING) sans modifier aucune valeur deja presente.
set -euo pipefail

if [ "${1:-}" = "--completer" ]; then
	ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
	TARGET="${2:-$ROOT/.env}"
	[ -f "$TARGET" ] || { echo "Introuvable : $TARGET (utilisez ./scripts/generate-env.sh sans option pour en creer un)." >&2; exit 1; }
	command -v openssl >/dev/null || { echo "openssl requis." >&2; exit 1; }
	manque() { ! grep -qE "^$1=.+" "$TARGET"; }   # absente ou vide
	ajoute() {
		if grep -qE "^$1=" "$TARGET"; then
			local esc; esc="$(printf '%s' "$2" | sed -e 's/[&/\]/\&/g')"
			sed -i.bak "s/^$1=.*/$1=${esc}/" "$TARGET" && rm -f "$TARGET.bak"
		else
			printf '
%s=%s
' "$1" "$2" >> "$TARGET"
		fi
		echo "  $1  ajoute"
	}
	echo "Completion de $TARGET (aucune valeur existante n'est modifiee) :"
	if manque COMPTABLE_PROVISIONING_TOKEN; then ajoute COMPTABLE_PROVISIONING_TOKEN "$(openssl rand -hex 32)"; fi
	if manque COMPTABLE_REGISTRATION_MODE; then ajoute COMPTABLE_REGISTRATION_MODE PROVISIONING; fi
	if manque SPRING_PROFILES_ACTIVE; then ajoute SPRING_PROFILES_ACTIVE prod; fi
	if manque COLLECTE_LIEN_DEPOT_BASE_URL; then
		grep -qE "^COLLECTE_LIEN_DEPOT_BASE_URL=" "$TARGET" || printf '
COLLECTE_LIEN_DEPOT_BASE_URL=
' >> "$TARGET"
		echo "  COLLECTE_LIEN_DEPOT_BASE_URL  A RENSEIGNER A LA MAIN (URL publique des liens de depot, ex. https://votre-domaine/depot) : ne peut pas etre devine."
	fi
	echo "Termine. Verifiez avec : docker compose config --quiet"
	exit 0
fi

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
