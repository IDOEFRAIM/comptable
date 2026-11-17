# Administration de la plateforme (mode SUPER_ADMIN)

Mode réservé au personnel de Warimara (rôle `SUPER_ADMIN`, sans cabinet) pour **réguler** la plateforme : comptes, cabinets, sécurité, IA. Il vit dans la même application, sous `/admin`, et l'API sous `/api/admin/**` (déjà réservée à ce rôle dans `SecurityConfig`).

## Principes
- **Tout est tracé.** Chaque action d'administration écrit dans `audit_log` (`ADMIN_*`), avec l'administrateur, la cible et un **motif obligatoire** (5 caractères au moins) pour toute action qui change l'état d'un compte.
- **Un administrateur ne se gère pas lui-même** ni un autre administrateur depuis ces écrans (pas de prise de contrôle d'un compte privilégié).
- **Aucun secret n'est renvoyé deux fois.** Un mot de passe temporaire n'est affiché qu'à la création / réinitialisation, jamais relu ensuite.
- **Moindre surprise pour les cabinets.** L'administration ne lit pas les écritures ni les pièces d'un cabinet : elle gère les accès et les abonnements, pas la comptabilité.

## Première connexion (amorçage)
Le premier `SUPER_ADMIN` est créé au démarrage du serveur, **une seule fois**, si aucun n'existe et si ces deux variables sont définies :

| Variable | Rôle |
|---|---|
| `COMPTABLE_ADMIN_BOOTSTRAP_EMAIL` | Adresse du premier administrateur |
| `COMPTABLE_ADMIN_BOOTSTRAP_PASSWORD` | Mot de passe **provisoire** (12 caractères au moins, deux types de caractères) |

Le compte est créé avec « changement de mot de passe obligatoire » : à la première connexion, l'administrateur en choisit un nouveau. **Retirez ensuite le mot de passe du `.env`.** Les variables sont ignorées dès qu'un administrateur existe.

## Livraisons

### 1. Fondations, comptes et accès, audit — *livré*
- Amorçage du premier administrateur (ci-dessus), écran de connexion → `/admin`.
- **Aperçu** : cabinets, comptes (actifs, suspendus, verrouillés), comptes jamais connectés.
- **Utilisateurs** (tous cabinets) : recherche, filtre par statut ; suspendre / réactiver, déverrouiller, réinitialiser le mot de passe (mot de passe temporaire à usage unique), révoquer les sessions.
- **Journal d'audit global** : filtres par cabinet, par action, par période.

### 2. Cabinets et abonnements — *à venir*
Liste des cabinets (dossiers, utilisateurs, dernière activité), plan et période d'essai, suspension d'un cabinet **appliquée à la connexion**, quotas (utilisateurs, dossiers, pièces), création d'un cabinet depuis l'interface (remplace `scripts/provision-cabinet.sh`).

### 3. IA, coûts et santé — *à venir*
Usage de l'IA par cabinet (requêtes, jetons, coût estimé) avec plafonds ; état des services (backend, ai-service, base) ; files hors ligne en erreur ; sauvegarde de la base visible.

### 4. Durcissement — *à prévoir*
Authentification à deux facteurs obligatoire pour les administrateurs, journal d'audit infalsifiable (chaînage), alertes (comptes verrouillés en masse, connexions anormales).
