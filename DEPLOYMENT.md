# Guide de déploiement

Ce document explique comment déployer l'ensemble du produit — le SaaS (Spring Boot + Angular)
**et** la couche IA (`ai-service`) — en une seule commande, sur n'importe quelle machine avec
Docker. Rien ici n'a été testé en conditions réelles de production (pas de client en ligne à ce
jour) : ce guide décrit le mécanisme de déploiement, pas un déploiement déjà validé sur un vrai
serveur.

> **Pilote (1 à 3 cabinets)** : l'exploitation courante (profils prod / staging, création d'un cabinet par l'opérateur, sauvegarde et exercice de restauration, supervision, incidents, mise à jour et retour arrière) est décrite dans
> [`docs/operations/pilot-runbook.md`](docs/operations/pilot-runbook.md) ; l'accueil d'un cabinet dans [`docs/operations/cabinet-onboarding.md`](docs/operations/cabinet-onboarding.md) ;
> l'état de préparation au pilote dans [`docs/operations/PILOT_READINESS.md`](docs/operations/PILOT_READINESS.md). Un démarrage « production » sur base vide a été vérifié par `scripts/smoke-prod-local.sh`.

## 0. Fichiers de déploiement (tous à la racine du dépôt)

| Fichier | Rôle |
|---|---|
| [`docker-compose.yml`](docker-compose.yml) | Les 4 services applicatifs. Suffisant seul pour un déploiement HTTP. |
| [`docker-compose.prod.yml`](docker-compose.prod.yml) | Surcouche : ajoute un reverse proxy **Caddy** (HTTPS automatique Let's Encrypt) devant le frontend. |
| [`Caddyfile`](Caddyfile) | Config du reverse proxy (domaine, en-têtes de sécurité, limite d'upload). Lu uniquement avec la surcouche prod. |
| [`.env.example`](.env.example) | Modèle de configuration. `cp .env.example .env` puis remplir. |
| [`Makefile`](Makefile) | Raccourcis : `make env`, `make deploy`, `make update`, `make backup`, `make logs`, `make ps`, `make down`… (`make` seul affiche l'aide). |
| [`scripts/generate-env.sh`](scripts/generate-env.sh) | Génère `.env` avec des secrets forts (`openssl rand`). |
| [`scripts/backup.sh`](scripts/backup.sh) | Dump Postgres + archive des documents → `./backups/comptable-<date>/`. Rétention 14. |
| [`scripts/restore.sh`](scripts/restore.sh) | Restaure une sauvegarde dans la stack courante. |
| `comptable/Dockerfile`, `comptable/frontend/Dockerfile`, `ai-service/Dockerfile` | Construction des 3 images applicatives. |
| `.github/workflows/ci.yml` | CI : tests backend + build frontend + pytest ai-service + build des images à chaque push/PR. |

Rien d'autre n'est nécessaire côté dépôt. Sur le serveur : seulement Docker.

## 1. Ce que ça déploie

Quatre conteneurs, un seul fichier [`docker-compose.yml`](docker-compose.yml) à la racine
(cinq avec la surcouche prod, qui ajoute Caddy) :

| Service | Rôle | Construit depuis |
|---|---|---|
| `postgres` | Base de données (image officielle, aucun code applicatif) | `postgres:18-alpine` |
| `backend` | API Spring Boot (le cœur métier — comptabilité, dossiers, findings...) | `comptable/Dockerfile` |
| `ai-service` | Microservice FastAPI (extraction, fraude, rapprochement, Copilot) | `ai-service/Dockerfile` |
| `frontend` | Application Angular compilée, servie par nginx | `comptable/frontend/Dockerfile` |

Seul `frontend` est exposé sur le réseau (port 80 par défaut) — `backend`, `ai-service` et
`postgres` ne sont accessibles qu'entre conteneurs, jamais directement depuis l'extérieur. nginx
relaie les appels `/api/*` vers `backend` (même principe que `proxy.conf.json` en développement).

## 2. Prérequis

- Docker Engine 24+ et Docker Compose v2 (`docker compose`, pas l'ancien `docker-compose`
  séparé) sur la machine de déploiement.
- Aucun autre prérequis logiciel — Java, Node et Python ne sont nécessaires que dans les images
  Docker elles-mêmes (construites automatiquement), pas sur la machine hôte.

## 3. Démarrage

```bash
git clone https://github.com/IDOEFRAIM/comptable/ saas-comptable
cd saas-comptable
./scripts/generate-env.sh          # crée .env avec des secrets forts déjà remplis
```

`generate-env.sh` remplit les 4 secrets obligatoires (`POSTGRES_PASSWORD`, `JWT_SECRET`,
`AI_SERVICE_OUTBOUND_API_KEY`, `AI_SERVICE_INBOUND_API_KEY` — deux valeurs **distinctes** pour
les deux dernières). Sans ces variables, `docker compose` refuse de démarrer plutôt que de
tourner avec un secret faible — c'est voulu. Les clés IA et, en mode HTTPS, `DOMAIN` /
`ACME_EMAIL` restent à compléter à la main dans `.env`.

**Mode HTTP** (local, IP nue, ou reverse proxy externe déjà en place) :

```bash
make up            # = docker compose up -d --build
make ps
```

L'application répond sur `http://<adresse-du-serveur>:${HTTP_PORT:-80}`.

**Mode production HTTPS** (nom de domaine + certificat automatique) — renseigner d'abord
`DOMAIN` (le DNS doit déjà pointer vers l'IP du serveur) et `ACME_EMAIL` dans `.env`, puis :

```bash
make deploy         # = docker compose -f docker-compose.yml -f docker-compose.prod.yml up -d --build
make ps
```

Caddy obtient le certificat Let's Encrypt au premier démarrage (quelques secondes), redirige
HTTP→HTTPS et renouvelle tout seul. L'application répond sur `https://<DOMAIN>`.

Dans les deux cas, le premier démarrage construit les 3 images applicatives (quelques minutes),
démarre Postgres, attend son `healthcheck`, lance le backend qui applique **automatiquement**
les migrations Flyway (aucune commande manuelle), puis `ai-service` et `frontend`.

## 4. Activer les fonctionnalités IA

Par défaut, **tout tourne sans clé IA** — `AI_PROVIDER=mock` et tous les `AGENT_*_ENABLED`
sont à `false` dans `.env.example`. Le produit reste pleinement fonctionnel (comptabilité,
dossiers, rapprochement manuel) sans dépendre d'un fournisseur externe.

Pour activer l'extraction automatique de pièces (Groq) et/ou des réponses réelles du Copilot :

1. Renseigner `GROQ_API_KEY` dans `.env` (voir `ai-service/README.md` pour le détail du
   provider et ses limites connues).
2. Passer `AGENT_VLM_ENABLED=true` (extraction), `AGENT_FRAUDE_ANALYSE_VISUELLE_ENABLED=true`
   (analyse visuelle de fraude) et/ou `AI_PROVIDER=groq` (Copilot + rapprochement IA).
3. `docker compose up -d` (recrée uniquement les conteneurs dont la config a changé, pas de
   rebuild nécessaire pour un simple changement de variable d'environnement).

## 5. Mettre à jour un déploiement existant

```bash
make update          # git pull + rebuild des images changées + prune (mode HTTP)
make update-prod     # idem en mode HTTPS
```

Reconstruit uniquement les images dont le code source a changé (cache Docker par couche), les
données (`postgres_data`, `documents_data`, `caddy_data`, volumes nommés) survivent au
redéploiement. Les migrations Flyway s'appliquent automatiquement au redémarrage du backend.

Rollback : `git checkout <tag-precedent> && make update` (les migrations Flyway ne se
dé-appliquent pas — un rollback qui traverse une migration destructive nécessite une
restauration de sauvegarde, voir §6).

## 6. Sauvegardes

```bash
make backup                         # -> ./backups/comptable-<date>/{db.dump, documents.tar.gz}
make restore SRC=backups/comptable-20260911-020000
```

`scripts/backup.sh` fait un `pg_dump -Fc` de la base **et** une archive du volume des pièces
justificatives, avec une rétention des 14 dernières. À planifier via `cron` sur l'hôte :

```cron
0 2 * * * cd /opt/saas-comptable && ./scripts/backup.sh /var/backups/comptable >> /var/log/comptable-backup.log 2>&1
```

Copier ensuite `/var/backups/comptable` hors du serveur (rsync/rclone/S3) — une sauvegarde qui
ne quitte jamais la machine ne protège pas d'une perte de la machine.

## 7. Nom de domaine et HTTPS

Fourni par [`docker-compose.prod.yml`](docker-compose.prod.yml) + [`Caddyfile`](Caddyfile) :
un conteneur **Caddy** prend les ports 80/443, obtient et renouvelle seul le certificat
Let's Encrypt pour `DOMAIN`, force HTTPS, ajoute les en-têtes HSTS / X-Content-Type-Options /
X-Frame-Options, et relaie vers `frontend` (qui n'est alors plus exposé sur l'hôte).

```bash
# .env : DOMAIN=compta.mondomaine.com   ACME_EMAIL=admin@mondomaine.com
make deploy
```

Prérequis : le port 80 **et** le port 443 de la machine sont joignables depuis Internet, et
l'enregistrement DNS de `DOMAIN` pointe déjà vers son IP (Let's Encrypt valide via HTTP-01).
Pour un CDN/proxy tiers devant (Cloudflare…), rester en `make up` (HTTP) et laisser le tiers
gérer le TLS.

## 8. Limites connues de ce packaging

- **Vérifié en local (`docker compose up`), jamais sur un vrai serveur distant** — la stack
  complète (les 4 services, migrations Flyway comprises) démarre, devient `healthy`, le frontend
  relaie correctement `/api/*` vers le backend, et un parcours réel (inscription cabinet → JWT →
  création d'entreprise → lecture plan comptable/états) passe de bout en bout, logs sans erreur.
  Ce qui n'est **pas** couvert : la surcouche Caddy/HTTPS (nécessite un domaine public réel),
  DNS, charge concurrente, redémarrage d'un serveur physique.
- **Un seul serveur, pas de haute disponibilité** — `docker-compose.yml` est pensé pour un
  déploiement sur une seule machine (cohérent avec l'échelle actuelle : 10-500 clients par
  cabinet, quelques cabinets). Une charge plus importante nécessiterait une architecture
  différente (bases de données managées, plusieurs instances backend derrière un load
  balancer) — pas construit par anticipation.
- **Sauvegarde : script fourni, planification à faire** — `scripts/backup.sh` existe et gère la
  rétention, mais le `cron` sur l'hôte et la copie hors-serveur restent à mettre en place (§6).
- **`AI_PROVIDER=anthropic` n'a jamais été testé avec une vraie clé** dans ce projet (contrainte
  budgétaire, voir `comptable/docs/P1_9_AUDIT.md`) — `mock` et `groq` sont les deux options
  vérifiées en conditions réelles.
