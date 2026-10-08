# Runbook d'exploitation — pilote Comptable

Public : l'opérateur de la plateforme (la personne qui héberge et supporte 1 à 3 cabinets pilotes).
Périmètre : installer, démarrer, surveiller, sauvegarder, restaurer, mettre à jour, dépanner. Aucune étape ne demande SQL manuel, Swagger ou Postman.

> **Limites annoncées du pilote** (à dire aux cabinets) :
> - **TVA Burkina Faso : cadre réglementaire non vérifié, finalisation indisponible.** Les périodes de TVA se préparent et se contrôlent ; aucune déclaration ne peut être finalisée tant qu'un cadre réglementaire vérifié n'est pas installé. L'écran le dit (« les montants sont comptabilisés, non déclarables »).
> - Pas d'engagement de niveau de service. Les durées de sauvegarde/restauration ci-dessous sont des mesures indicatives sur la machine de test.
> - Envoi automatique de messages aux clients : désactivé par défaut.

## 1. Environnements

| Profil Spring | Usage | Garde-fous |
|---|---|---|
| (aucun, `dev`) | poste du développeur | PostgreSQL via `compose.yaml`, inscription ouverte |
| `test` | tests automatiques | base jetable |
| `staging` | répétition avant production, formation | mêmes contrôles de secrets que la production ; envois aux clients limités à une liste blanche |
| `prod` | cabinets pilotes | **démarrage refusé** si un secret est faible/absent, si l'inscription est ouverte, si le stockage ou l'URL publique manque, si des cadres fiscaux de test existent en base, si la base contient des données de démonstration utilisées |

Le validateur de démarrage (`ProductionStartupValidator`) liste **tous** les défauts en une fois, sans jamais afficher de valeur secrète.
**Ordre de validation** : les contrôles qui ne dépendent que de la configuration (secrets, mode d'inscription, stockage, URL publique, journalisation) s'exécutent **avant la migration Flyway** (`FlywayMigrationStrategy`) : une configuration invalide est refusée sans qu'aucune migration soit appliquée (prouvé par `smoke-prod-local.sh` : la table d'historique Flyway n'existe pas après le refus). Seuls deux contrôles lisent la base (cadres fiscaux de test, données de démonstration) et s'exécutent après la migration, puisqu'ils l'exigent.
**Mise à jour d'une installation antérieure à P0** : les variables `COMPTABLE_PROVISIONING_TOKEN` et `COLLECTE_LIEN_DEPOT_BASE_URL` sont désormais obligatoires dans `.env` ; sans elles, **toute** commande `docker compose` (y compris `backup.sh`) refuse de s'exécuter. Exécuter `./scripts/generate-env.sh --completer` : il ajoute au `.env` existant le jeton d'approvisionnement généré, le mode d'inscription et le profil, sans modifier aucune valeur présente ; `COLLECTE_LIEN_DEPOT_BASE_URL` (URL publique de votre domaine) reste à renseigner à la main. Vérifier ensuite avec `docker compose config --quiet`.

## 2. Installation (machine neuve)

Prérequis : Docker + Docker Compose v2, `git`, `curl`, `openssl`, 4 Go de RAM, disque ≥ 20 Go.

```bash
git clone <depot> saas-comptable && cd saas-comptable
make env                      # génère .env avec des secrets forts (jamais commité)
# éditer .env : COLLECTE_LIEN_DEPOT_BASE_URL (URL publique), DOMAIN/ACME_EMAIL si HTTPS
make config                   # valide la configuration
make deploy                   # HTTPS (Caddy)   — ou `make up` en HTTP derrière un proxy
```

Vérifier : `curl -fsS http://localhost/actuator/health/readiness` → `{"status":"UP"}` (ou via le domaine).
Le premier démarrage applique les migrations Flyway sur une base vide (aucun compte par défaut n'existe).

### Premier cabinet (sans SQL ni Postman)

```bash
make provision CABINET="Cabinet Exemple" EMAIL=expert@exemple.org NOM=Dupont PRENOM=Awa PAYS=BF
```

Le script lit `COMPTABLE_PROVISIONING_TOKEN` dans `.env` (jamais affiché), crée le cabinet et son expert, et affiche **une seule fois** un mot de passe fort s'il n'en a pas reçu. Transmettre ce mot de passe par un canal distinct de l'e-mail du compte. Suite : `docs/operations/cabinet-onboarding.md`.

## 3. Santé et surveillance

| Point d'entrée | Rôle |
|---|---|
| `/actuator/health/liveness` | le processus tourne (redémarrage si KO) |
| `/actuator/health/readiness` | prêt à servir : base, stockage des pièces, migrations. Sans détail interne exposé |
| `GET /api/cabinet/support/diagnostic` (expert connecté ; consommé par l'écran de support) | « pourquoi ce dossier ne fonctionne pas ? » : problèmes classés avec l'action à mener (envois en échec, pièces en erreur, constats bloquants, comptes verrouillés), dossiers en alerte, dernière activité du cabinet, état de l'instance (réponses 5xx, connexions base actives/max, envois actifs/sandbox, dernière migration). Aucune trace, aucun secret, aucun contenu de pièce |

Observabilité minimale : le compteur `comptable.http.reponses.5xx` (Micrometer) et le pool de connexions alimentent le diagnostic ; chaque requête est journalisée une fois (méthode, route normalisée sans identifiant, statut, durée, tenant, utilisateur). Les échecs de connexion, verrouillages, envois en échec et pièces en erreur sont visibles dans les journaux et le diagnostic. **Aucun point d'entrée de métriques n'est exposé** (seuls `health` et `info` le sont) : un collecteur externe (Prometheus, alertes) n'est pas fourni et reste à ajouter après le pilote si besoin.
Chaque réponse porte `X-Request-Id` ; chaque erreur JSON contient `correlationId` ; les journaux contiennent `correlationId`, `tenant`, `user`. **Pour un incident : demander au cabinet la référence affichée dans le message d'erreur, puis `make logs | grep <référence>`.**
Les journaux n'enregistrent ni mot de passe, ni jeton, ni contenu de pièce.

À consulter au minimum chaque jour ouvré pendant le pilote : readiness, diagnostic (envois en échec, pièces en erreur, 5xx), espace disque de l'hôte et présence de la dernière sauvegarde (non surveillée automatiquement).

## 4. Sauvegarde

```bash
make backup                  # base (pg_dump -Fc) + volume des pièces → ./backups/comptable-<horodatage>/
```

Planification (cron de l'hôte) : une sauvegarde quotidienne hors heures de travail, copie **hors machine** (autre disque ou stockage distant chiffré). Conserver 14 quotidiennes + 8 hebdomadaires. `backups/` est exclu de git : il contient des données réelles.

**Avant chaque mise à jour contenant une migration** : `make backup` obligatoire (voir §6).

**Détecter qu'une sauvegarde a échoué.** `backup.sh` retourne un code non nul en cas d'échec (stack arrêtée, dump ou archive vide), nettoie le dossier incomplet, écrit `[backup] ECHEC` sur la sortie d'erreur et consigne le résultat dans `backups/LAST_BACKUP_STATUS` (`OK|FAIL`, horodatage UTC, dossier). `scripts/check-backup.sh` lit ce fichier et sort en code 1 (« ALERTE : … ») si la dernière sauvegarde a échoué, est introuvable, a un dump vide ou date de plus de `MAX_HEURES` (26 h par défaut). Planification type :

```cron
0 2 * * *  cd /opt/saas-comptable && ./scripts/backup.sh >> /var/log/comptable-backup.log 2>&1
30 8 * * * cd /opt/saas-comptable && ./scripts/check-backup.sh || echo "ALERTE sauvegarde Comptable" | mail -s "Comptable : sauvegarde" ops@exemple.org
```

Il n'y a pas d'alerte intégrée : la commande de contrôle doit être branchée sur la messagerie ou la supervision de l'opérateur.

## 5. Restauration

### 5.1 Exercice de restauration (à faire avant le pilote, puis après chaque changement majeur)

```bash
make restore-drill            # sauvegarde → PostgreSQL jetable → restauration → contrôles → suppression
```

Contrôles : historique Flyway identique et sans échec ; comptes de lignes de toutes les tables critiques (utilisateurs, cabinets, entreprises, écritures, pièces, banque, constats, états financiers, fiscalité, audit) ; empreintes des snapshots figés ; métadonnées de pièces (hash, chemin) ; triggers d'immuabilité ; clés étrangères ; snapshot restauré toujours immuable. Code retour ≠ 0 si un contrôle échoue.

Mesure du 2026-10-08 (base de **test** volumineuse : 15 587 cabinets, 1,77 M écritures, 4,3 M lignes, 250 k lignes d'audit, 2 Go) : sauvegarde 353 Mo, **restauration 461 s**, contrôles 94 s, total 575 s. Un cabinet pilote réel représente une fraction de ce volume : prévoir **RPO = fréquence de sauvegarde (24 h)** et un **RTO de l'ordre de la dizaine de minutes** pour la base, plus le temps de copie des pièces. Indicatif, non contractuel.

### 5.2 Restauration réelle (sinistre)

```bash
make stop
make restore SRC=backups/comptable-YYYYmmdd-HHMMSS   # confirmation explicite demandée ; écrase base et pièces
make up        # ou make deploy
```

Puis : readiness UP, connexion d'un expert, ouverture d'une entreprise et d'une pièce, diagnostic sans anomalie. Informer les cabinets de la perte maximale (travail depuis la dernière sauvegarde).

## 6. Mise à jour, rollback

1. `make backup` (sauvegarde de pré-livraison, vérifiée par présence des deux fichiers).
2. `git pull` puis `make update` (ou `make update-prod`). Les migrations Flyway s'appliquent au démarrage ; un échec de migration empêche la mise en service (readiness KO) et reste visible dans le diagnostic.
3. Vérifier readiness, connexion, ouverture d'un dossier.

**Stratégie de retour arrière (réaliste)** : Flyway n'est pas réversible. On préfère la **correction en avant** (nouvelle version corrigée). Si le correctif n'est pas immédiat et que la migration était destructive ou a échoué : arrêter, restaurer la sauvegarde de pré-livraison (§5.2) avec l'ancienne image, informer les cabinets. La restauration est un recours exceptionnel : elle perd les saisies postérieures à la sauvegarde.

## 7. Dépannage rapide

| Symptôme | Cause probable | Action |
|---|---|---|
| Le backend ne démarre pas, message « démarrage refusé » | défaut de configuration listé dans les journaux | corriger `.env` selon les défauts listés ; **ne jamais** assouplir le validateur |
| readiness DOWN, composante `migrations` (si exposée par la configuration) | migration en échec | journaux Flyway ; restaurer la sauvegarde de pré-livraison si besoin |
| readiness DOWN, composante `storage` | volume des pièces absent ou plein | vérifier le volume `documents_data` et l'espace disque |
| Utilisateur verrouillé (HTTP 429, « Trop de tentatives », identique pour un compte inconnu) | trop d'échecs de connexion | attendre l'expiration (15 min, réglable `LOGIN_VERROU_MINUTES`, après 5 échecs `LOGIN_MAX_ECHECS`) ou l'expert réinitialise le mot de passe depuis l'écran Équipe |
| Mot de passe oublié | — | l'expert (écran Équipe) émet un lien à usage unique (validité limitée) à transmettre au membre. Pour un expert : l'opérateur réinitialise via un second expert ; à défaut, procédure de secours : nouvel expert via `make provision` |
| Erreur affichée avec une référence | erreur serveur | chercher la référence dans les journaux, ouvrir un incident SEV2/3 |
| Fichier refusé à l'envoi | type/taille/contenu non autorisé (PDF, JPEG, PNG uniquement, contrôle du contenu réel) | normal ; demander un export PDF/JPEG/PNG |
| Messages clients non envoyés | envoi désactivé ou liste blanche (staging) | variables `COLLECTE_*` ; diagnostic → messages en échec |

## 8. Classification des incidents

| Niveau | Définition | Exemples | Réaction |
|---|---|---|---|
| **SEV1** | perte/compromission de données, indisponibilité complète, fuite entre cabinets | accès à un autre cabinet, base inaccessible, secret exposé | immédiat ; couper l'accès si fuite ; restauration si perte ; rotation des secrets ; information des cabinets concernés |
| **SEV2** | fonction majeure inutilisable pour un cabinet, sans perte de données | impossible de se connecter, import bloqué, états financiers en erreur | même jour ; contournement ou correctif ; information du cabinet |
| **SEV3** | gêne mineure, esthétique, question d'usage | libellé, lenteur ponctuelle, aide à l'onboarding | traitement planifié |

À chaque incident : référence de corrélation, horodatage, cabinet, impact, action, cause racine, prévention. Garder une trace datée.

## 9. Rotation des secrets

Secrets : `JWT_SECRET`, `POSTGRES_PASSWORD`, clés internes IA (deux valeurs distinctes), `COMPTABLE_PROVISIONING_TOKEN`, clés WhatsApp/paiement le cas échéant.
- Changer `JWT_SECRET` ⇒ toutes les sessions sont invalidées (reconnexion de tous les utilisateurs) : à faire hors heures de travail.
- Changer `POSTGRES_PASSWORD` : modifier le mot de passe du rôle dans PostgreSQL **et** `.env`, puis redémarrer.
- Jamais de valeur réelle dans le dépôt, un ticket, une capture ou un message : le fichier `.env` reste sur le serveur, droits `600`.
- Contrôle de hygiène avant mise en service : `git grep -nE "(secret|password|token)\s*=\s*\S{8,}" -- ':!*.example' ':!*test*'` ne doit remonter aucune valeur réelle.

## 10. Tâches planifiées et plusieurs instances

Le pilote tourne avec **une seule instance backend** (recommandé). Audit réalisé :
- **Boîte d'envoi (outbox)** : un message est réservé en transaction courte avec `FOR UPDATE SKIP LOCKED`, envoyé hors transaction, libéré s'il reste bloqué (`SENDING` trop longtemps) ; contrainte d'unicité `(entreprise, clé d'idempotence)` : deux instances ne prennent jamais le même message et ne créent pas de doublon. Échecs visibles dans le diagnostic.
- **Relances** : l'évaluation est déterministe (l'échéance décide) et s'appuie sur la même clé d'idempotence ; une double exécution ne produit pas de second message.
- **Limiteur de débit** (connexion, réinitialisation, inscription, dépôt par lien) : compteur **en mémoire**, local à l'instance ; derrière plusieurs instances la limite effective est multipliée. Le verrouillage de compte, lui, est en base et fait autorité. Compléter par une limite au niveau du reverse proxy si l'exposition publique le justifie.
- Les envois et relances automatiques sont **désactivés par défaut** (`COLLECTE_ENVOI_ACTIF`, `COLLECTE_RELANCE_ACTIF`).

## 10 bis. Lacunes d'observabilité connues (pilote)

Présent : `comptable.http.reponses.5xx`, connexions base actives / max (diagnostic), readiness (base, stockage, migrations), échecs d'envoi et pièces en erreur (diagnostic), journal d'accès avec latence, identifiant de corrélation sur chaque erreur, comptes verrouillés. **Absent** : export de métriques vers un collecteur (Prometheus), tableaux de bord, alertes automatiques sur 5xx / latence / outbox, supervision d'espace disque, métrique dédiée « tâche planifiée en erreur » (les erreurs de tâches sont dans les journaux). Le pilote repose sur un contrôle quotidien manuel (§3) et sur `check-backup.sh`.

## 10 ter. Dette technique de test et d'outillage

- **Base de test locale volumineuse (~2 Go, 1,77 M écritures) et suite Maven longue (plusieurs heures)** : les tests de volume (banque, balance, contrôles, documents, états financiers, TVA, workspace) ajoutent des données à une base partagée et persistante, et certains tests (ex. `CollecteHttpTest`) parcourent des tables entières. Ce n'est pas un défaut de production. Amélioration à planifier : base de test jetable par exécution (conteneur recréé), volumétrie isolée dans un profil `benchmark` exécuté à part, suppression des `findAll()` dans les tests. Non traité dans P0 pour ne pas réécrire la suite.
- **JVM orpheline** : interrompre brutalement un `./mvnw test` depuis le shell peut laisser le JVM de test (surefire) actif et verrouiller la base. Les scripts maison ne lancent pas de processus en arrière-plan sans les arrêter (`smoke-prod-local.sh` a un `trap` de nettoyage). Après une interruption manuelle : lister les processus `java` et arrêter celui du test (Windows : `tasklist | findstr java`, `taskkill /PID <pid> /F`). Aucun gestionnaire de processus n'est ajouté.

## 11. Conservation, désactivation, export

- La désactivation d'un cabinet ou d'un utilisateur est **logique** (aucune suppression destructive) ; les écritures validées et les snapshots restent immuables.
- **Sauvegarde opérationnelle ≠ export client portable.** La sauvegarde (base + volume de pièces) sert à **restaurer cette plateforme** : elle est au format PostgreSQL, contient tous les cabinets et n'est pas remise à un client. Il n'existe **pas** aujourd'hui d'export intégral et portable d'un cabinet ou d'une entreprise (archive autonome lisible hors de l'application). Ce que le cabinet peut exporter lui-même par l'interface : états et dossier financier (PDF / snapshot), pièces (téléchargement individuel). N'engager le pilote sur rien de plus ; un export de portabilité complet serait une demande à instruire après le pilote.
- Données personnelles minimales : nom, prénom, e-mail, téléphone facultatif ; aucun mot de passe en clair ; jetons de réinitialisation stockés hachés.

## 12. Qualité avant fusion (CI) et versions de référence

Le workflow `.github/workflows/ci.yml` exécute à chaque *push* et *pull request* :

| Job | Contenu |
|---|---|
| `backend` | `./mvnw verify` : tests unitaires et d'intégration sur PostgreSQL réel, dont `FreshDatabaseMigrationTest` (migration d'une base neuve + schéma identique à la base migrée), `ProductionStartupValidatorTest`, `PilotHardeningTest`, `TenantIsolationCampaignTest` |
| `frontend` | `npm ci`, build de production (`ng build --configuration production`), `npm test` (Karma / Jasmine, Chrome headless) |
| `e2e` | Playwright sur un backend + PostgreSQL de service : `pilote`, `cabinet-workspace`, `dossier-financier` (le dossier complet de la connexion au snapshot final) |
| `ai-service` | `uv sync` + `pytest` |
| `docker-build` | construction des images avec un `.env` factice (aucun secret réel) |

**Porte de fusion recommandée** (à activer dans les paramètres du dépôt : *Branch protection rules* sur `main` → *Require status checks*) : `Backend`, `Frontend`, `E2E critiques`, `AI service`, `Images Docker`. Le job `e2e` de la CI n'a pas encore tourné sur GitHub : le premier passage doit être surveillé (la suite complète a été exécutée avec succès en local : 19 scénarios).
Suites supplémentaires en local avant une livraison : `make restore-drill`, `./scripts/smoke-prod-local.sh <jar>`, et la suite Playwright complète (`npx playwright test`, inclut TVA et responsive / accessibilité).

Versions de référence (build reproductible, dépendances figées par `package-lock.json` et le wrapper Maven) :

| Composant | Version |
|---|---|
| Java | 21 (Temurin en CI) |
| Spring Boot | 4.1.x (parent `spring-boot-starter-parent`), Flyway 12, Hibernate 7 |
| PostgreSQL | 18 (`postgres:18-alpine` dans compose ; l'exercice de restauration réutilise l'image de la base source) |
| Node | 20 en CI (24 vérifié en local) |
| Angular | 18.2, TypeScript 5.5, Karma 6.4, Playwright 1.63 |
| Navigateur E2E | Google Chrome installé sur le poste (aucun navigateur téléchargé) |
