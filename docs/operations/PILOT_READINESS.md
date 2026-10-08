# PILOT_READINESS — Comptable

État au 2026-10-08. Ce document est la liste de contrôle centrale de mise en pilote (1 à 3 cabinets réels). Il distingue ce qui est **prouvé par un test ou une exécution réelle** de ce qui est **documenté seulement**.

Légende : **READY** = prouvé et exploitable ; **PARTIAL_READY** = utilisable avec une limite connue et écrite ; **BLOCKED** = ne doit pas être promis.

## 1. Audit initial (avant la phase P0)

| Domaine | EXISTANT | PARTIEL | ABSENT | RISQUE PILOTE |
|---|---|---|---|---|
| Déploiement | compose (postgres, backend, ai-service, nginx, Caddy), `make`, `generate-env.sh`, CI de base | profil prod non verrouillé | profils staging/prod, jeton d'approvisionnement | secrets de développement acceptés en production |
| Base / migrations | Flyway V1→V68, schéma validé par JPA | — | test base neuve vs base migrée | **jeu de démonstration (V8) présent dans toute base neuve** |
| Sauvegarde | `backup.sh` / `restore.sh` | jamais éprouvés | exercice de restauration, contrôles d'intégrité | sauvegarde non restaurable sans le savoir |
| Sécurité | JWT, BCrypt, RBAC par URL, isolation par entreprise | — | verrouillage de compte, mot de passe temporaire, réinitialisation, révocation de session, débit limité | **création de cabinet ouverte à tous**, force brute possible |
| Observabilité | journaux Spring | — | identifiant de corrélation, format d'erreur unique, journal d'accès, diagnostic | support impossible sans développeur |
| Onboarding | assistant d'entreprise, écran Équipe | mot de passe fixé à la main | création de cabinet sans API, réinitialisation | dépendance à Swagger/Postman |
| Fiscal | cadre TVA technique, Regulatory Pack non vérifié | — | — | finalisation fiscale non conforme (verrouillée) |
| Envois clients | outbox idempotente `SKIP LOCKED` | — | mode bac à sable / liste blanche | message envoyé à un vrai client depuis un environnement de test |

## 2. Résultat par domaine (15)

| # | Domaine | Statut | Preuve | Limite connue |
|---|---|---|---|---|
| 1 | Déploiement | **READY** | `smoke-prod-local.sh` : démarrage prod sur base vide en ~45 s, configuration faible **refusée avant toute migration** (défauts listés sans valeur secrète) | une seule machine, une seule instance |
| 2 | Base de données | **READY** | `FreshDatabaseMigrationTest` (base neuve = schéma de la base migrée), 0 utilisateur / 0 cabinet par défaut en prod, V8 purgé s'il est vierge | pas de test « upgrade depuis une version publiée antérieure » (aucune version publiée) : la base de test a été migrée de V1 à V69 de façon incrémentale au fil des phases |
| 3 | Sauvegarde / restauration | **READY** | `restore-drill.sh` : restauration réelle dans une base jetable, 20+ contrôles (comptes de lignes, empreintes des snapshots, métadonnées de pièces, triggers d'immuabilité, clés étrangères) — 2 Go, 1,77 M écritures : restauration 461 s | la sauvegarde est lancée par l'opérateur (cron) ; `backup.sh` retourne un code d'erreur et écrit `LAST_BACKUP_STATUS`, `check-backup.sh` détecte un échec ou une sauvegarde trop ancienne, **mais aucune alerte n'est branchée par défaut** (à la charge de l'opérateur) |
| 4 | Sécurité | **READY** (profil pilote) | verrouillage 5 échecs / 15 min, 429 uniforme, mot de passe temporaire à changer, réinitialisation à usage unique (jeton haché, expiration), révocation de session, PasswordPolicy 12+ caractères, rate-limit login / reset / inscription / dépôt, upload à contenu vérifié, noms de fichiers assainis | limiteur en mémoire (une instance) ; pas d'authentification à deux facteurs |
| 5 | Multi-tenant | **READY** | `TenantIsolationCampaignTest` : **toutes** les routes `/api/entreprises/{id}/…` (GET/POST/PUT/PATCH/DELETE, découvertes automatiquement) appelées avec le jeton d'un autre cabinet → aucune ne répond 2xx/5xx, lectures en 403/404 ; témoin positif côté propriétaire | — |
| 6 | Stockage des documents | **READY** | volume dédié, chemin `tenant/entreprise/uuid-nom`, anti-traversée, type + taille + signature | stockage disque local (pas d'objet distant) |
| 7 | Observabilité | **PARTIAL_READY** | corrélation sur chaque réponse et journal, journal d'accès (route normalisée, durée, tenant, utilisateur), compteur 5xx, diagnostic de support | **pas de collecteur ni d'alertes automatiques** (Prometheus/alerting non fournis) : contrôle quotidien manuel |
| 8 | Jobs / planificateurs | **READY** (1 instance) | outbox `FOR UPDATE SKIP LOCKED`, clé d'idempotence unique, reprise des messages bloqués ; relances idempotentes ; envois désactivés par défaut | deux instances : limiteur de débit local (documenté) |
| 9 | Onboarding cabinet | **READY** | `provision-cabinet.sh` (jeton opérateur), écran Équipe (mot de passe temporaire généré, lien de réinitialisation), `cabinet-onboarding.md` | pas d'e-mail transactionnel : l'expert transmet le lien |
| 10 | Onboarding entreprise | **PARTIAL_READY** | assistant : identité, pays, régime, PCE cloné, journaux, exercice courant | **pas d'import CSV des tiers ni des soldes d'ouverture** (reprise manuelle par écriture OD) ; import CSV des relevés bancaires : aperçu + idempotent |
| 11 | Gestion des utilisateurs | **READY** | ajout, rôle, désactivation (effet immédiat), garde « dernier expert », réinitialisation | rôles : expert-comptable et collaborateur |
| 12 | Support / diagnostic | **READY** | `GET /api/cabinet/support/diagnostic` + référence d'erreur visible utilisateur → journaux | — |
| 13 | Performance | **PARTIAL_READY** | tests de volume existants par domaine (banque, balance, contrôles, documents, états financiers, TVA, révision, workspace) sur une base de test de **17 900 entreprises / 1,77 M écritures** dont des entreprises à 100 000 écritures | pas de test de charge concurrent (plusieurs utilisateurs simultanés) |
| 14 | Données de démonstration | **READY** | `DemoDataGuard` : jamais actif en prod ; seed V8 purgé si vierge, **refus de démarrer** s'il a été utilisé ; fixtures fiscales `TEST_ONLY` refusées en prod | — |
| 15 | Runbook | **READY** | `pilot-runbook.md`, `cabinet-onboarding.md`, incidents SEV1–3, rollback / roll-forward, rotation des secrets | — |

## 3. Garde-fous fiscaux

- **TVA Burkina Faso : cadre réglementaire non vérifié, finalisation indisponible.** Vérifié sur une instance de production neuve (`smoke-pilot.sh`) : période ouverte avec `regulatoryPackVerified = false`, finalisation refusée (HTTP 422). L'écran TVA affiche « Cadre réglementaire : non vérifié » et « les montants sont comptabilisés, non déclarables ».
- Aucune règle fiscale n'a été inventée dans cette phase ; le pays BF n'active aucun cadre fiscal factice.

## 4. Portes (gates) — clôture P0.1

Légende supplémentaire : **NOT_PROVEN_REMOTE** = configuration validée localement, jamais exécutée sur l'infrastructure distante réelle.

| Porte | Statut | Preuve / limite |
|---|---|---|
| Backend Test Baseline | **READY** | `./mvnw -o test` complet, après tous les correctifs (voir §5) |
| Frontend Baseline | **READY** | `npm run build` OK, Karma 130/130 |
| E2E Baseline | **READY** | Playwright : 19 passés, 3 ignorés (specs de captures d'écran, `CAPTURE_DIR` non défini, par conception). Pas de parcours navigateur dédié aux pièces ni à la banque : ils sont couverts par les tests HTTP backend et le smoke prod |
| Fresh DB | **READY** | `FreshDatabaseMigrationTest` (dans la suite complète) + démarrage prod sur base vide : 0 utilisateur, 0 cabinet, Flyway V69 |
| Prod Smoke | **READY** | `smoke-prod-local.sh` : 40/40 contrôles + étape staging, rejoué après tous les correctifs |
| Upload Security | **READY** | `UploadHttpTest` (multipart réel) : faux PNG refusé en 415 sur le chemin authentifié ET le lien public, sans document ni fichier conservé ; PDF/JPEG/PNG valides acceptés ; double extension, traversée de chemin, fichier vide (400) et trop gros (413) traités ; `UploadSecurityTest`, `StockageDocumentsSecuriteTest`. Ce n'est pas un antivirus |
| Tenant Isolation | **READY** | `TenantIsolationCampaignTest` (toutes les routes `/api/entreprises/{id}/…`) |
| Backup/Restore | **READY** | `backup.sh` (statut + code retour) et `restore-drill.sh` rejoués sur la stack compose après P0 ; grand exercice 2 Go du 2026-10-08 (461 s) toujours valide |
| Staging Configuration | **READY** (`STAGING_CONFIG_READY`) | profil `staging` démarré localement (readiness UP, liste blanche d'envoi). **Aucun serveur de staging réel n'a été déployé** (`STAGING_DEPLOYED` : non) |
| CI Configuration | **READY** (`CI_CONFIG_VALIDATED_LOCALLY`) | YAML valide, dépendances `docker-build ← backend, frontend, e2e, ai-service`, `e2e ← backend, frontend`, commande et variables du job e2e vérifiées localement (démarrage par `SPRING_DATASOURCE_*` sans compose : readiness 200). **Jamais exécutée sur GitHub : NOT_PROVEN_REMOTE** (`CI_E2E_PROVEN_ON_GITHUB` : non) |
| Production Operations | **PARTIAL_READY** | une instance, supervision quotidienne manuelle, pas d'alerting (voir runbook §10 bis), supervision de la sauvegarde par `check-backup.sh` à brancher par l'opérateur |
| BF Regulatory VAT | **BLOCKED** | cadre non vérifié, finalisation refusée (422) — vérifié par le smoke |

## 5. Tests de clôture (P0.1, 2026-10-08)

Chaîne exécutée **dans cet ordre, après le dernier changement de code**, sans rejouer de test isolé pour masquer un échec :

| Contrôle | Résultat |
|---|---|
| `./mvnw -o test` (suite complète, PostgreSQL réel) | **1050 tests, 0 échec, 0 erreur, 3 ignorés, `BUILD SUCCESS`, code retour 0.** Les 3 ignorés sont les jeux de démonstration (`BanqueDemoSeedTest`, `CollecteDemoSeedTest`, `HubDemoSeedTest`), inactifs par conception. Inclut `ClotureHttpTest` 4/4, `FreshDatabaseMigrationTest` 2/2, `TenantIsolationCampaignTest`, `UploadHttpTest` 5/5, `UploadSecurityTest`, `StockageDocumentsSecuriteTest`, `PilotHardeningTest` 12/12, `ProductionStartupValidatorTest` 13/13 |
| `npm run build` | OK (code 0) |
| `npm test` (Karma) | 130 / 130 |
| Playwright (`npx playwright test`) | 19 passés, 0 échec, 3 ignorés (specs de captures d'écran, désactivées sans `CAPTURE_DIR`) |
| `smoke-prod-local.sh` | 40/40 + refus avant migration + staging prêt, sans valeur secrète dans les journaux, aucun processus résiduel |
| Sauvegarde / restauration | `backup.sh` OK sur la stack compose (code 0, `LAST_BACKUP_STATUS`), chemin d'échec vérifié (code 1, statut FAIL, dossier incomplet supprimé), `check-backup.sh` OK / ALERTE, `restore-drill.sh` validé sur cette sauvegarde (34 s) ; grand exercice 2 Go inchangé |

## 6. Verdict

**COMPTABLE — PILOT READY**, pilote limité et supervisé, validé **localement**.

Tous les critères locaux sont verts : Maven complet 0 échec, build et Karma, Playwright 0 échec, base neuve, smoke prod final 100 %, faux fichier refusé sur le chemin HTTP réel (authentifié et public), isolation entre cabinets, sauvegarde / restauration valides.

Ce que ce verdict ne dit pas : la CI n'a jamais tourné sur GitHub (NOT_PROVEN_REMOTE), aucun serveur de staging ni de production réel n'a été utilisé, et le pilote reste limité par : TVA Burkina bloquée (aucune déclaration finalisée), une seule instance, supervision et alerting manuels, reprise des tiers / soldes d'ouverture manuelle, pas d'export client portable.
