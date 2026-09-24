# Raccourcis de deploiement. `make` seul affiche l'aide.
#
# Mode HTTP simple (local, IP nue, reverse proxy externe) :   make up
# Mode production HTTPS (nom de domaine + certificat auto)  :   make deploy
#
# Le mode production superpose docker-compose.prod.yml (service Caddy) au fichier de base.

COMPOSE      := docker compose
COMPOSE_PROD := docker compose -f docker-compose.yml -f docker-compose.prod.yml

.DEFAULT_GOAL := help
.PHONY: help env up deploy update update-prod down stop restart ps logs backup restore test config pull

help: ## Affiche cette aide
	@grep -E '^[a-zA-Z_-]+:.*?## .*$$' $(MAKEFILE_LIST) | awk 'BEGIN{FS=":.*?## "}{printf "  \033[36m%-14s\033[0m %s\n", $$1, $$2}'

env: ## Genere un .env avec des secrets forts (a partir de .env.example)
	@./scripts/generate-env.sh

config: ## Valide la configuration compose (mode production)
	@$(COMPOSE_PROD) config --quiet && echo "Configuration valide."

up: ## Demarre/rebuild la stack en HTTP (frontend sur HTTP_PORT, defaut 80)
	$(COMPOSE) up -d --build

deploy: ## Demarre/rebuild la stack en production HTTPS (Caddy + Let's Encrypt)
	$(COMPOSE_PROD) up -d --build

update: ## git pull + rebuild des images changees, sans perte de donnees (HTTP)
	git pull
	$(COMPOSE) up -d --build
	$(COMPOSE) image prune -f

update-prod: ## git pull + rebuild en mode production HTTPS
	git pull
	$(COMPOSE_PROD) up -d --build
	$(COMPOSE_PROD) image prune -f

down: ## Arrete et supprime les conteneurs (les volumes/donnees sont conserves)
	$(COMPOSE_PROD) down

stop: ## Arrete les conteneurs sans les supprimer
	$(COMPOSE_PROD) stop

restart: ## Redemarre backend + frontend (recharge la config sans rebuild)
	$(COMPOSE) up -d backend frontend

ps: ## Etat des services
	$(COMPOSE_PROD) ps

logs: ## Suit les logs de tous les services (Ctrl+C pour quitter)
	$(COMPOSE_PROD) logs -f --tail=100

backup: ## Sauvegarde base + documents dans ./backups
	@./scripts/backup.sh

restore: ## Restaure une sauvegarde :  make restore SRC=backups/comptable-XXXX
	@test -n "$(SRC)" || { echo "Usage: make restore SRC=backups/comptable-YYYYmmdd-HHMMSS"; exit 1; }
	@./scripts/restore.sh "$(SRC)"

test: ## Lance les tests backend (Maven) dans un conteneur jetable
	docker run --rm -v "$$PWD/comptable":/app -w /app eclipse-temurin:21-jdk ./mvnw -B test
