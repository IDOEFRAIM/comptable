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

### 2. Cabinets et abonnements — *livré*
- **Liste des cabinets** (`/admin/cabinets`) : plan, fin d'essai, membres et dossiers actifs face à leurs limites, état, dernière activité ; recherche et filtres (actifs, suspendus, essai expiré).
- **Suspension d'un cabinet** : tous ses comptes sont refusés tout de suite (le filtre JWT revérifie le cabinet à chaque requête) et ne peuvent plus se connecter ; le message « l'accès de votre cabinet est suspendu » n'est donné qu'après un mot de passe correct, pour ne rien révéler à un inconnu. Les données restent intactes ; la réactivation rend les sessions d'avant valables.
- **Plan, fin d'essai et limites** (`ESSAI`, `STANDARD`, `PARTENAIRE`) : limite de **membres actifs** (experts et collaborateurs, pas les clients du portail) et de **dossiers actifs**, vide = illimité. Un refus explique la limite et dit de contacter Warimara. Abaisser une limite sous l'existant ne coupe rien : cela bloque les ajouts suivants. Réactiver un membre le recompte dans la limite.
- **Essai expiré** : signalé à l'administrateur (carte de l'aperçu, filtre, étiquette), **jamais appliqué automatiquement** : c'est l'administrateur qui décide de suspendre.
- **Création d'un cabinet** depuis l'interface : cabinet + premier expert (mot de passe temporaire affiché une seule fois, changement obligatoire), essai de 30 jours par défaut. Remplace `scripts/provision-cabinet.sh`, qui reste utilisable.
- Les cabinets déjà existants ne sont **ni limités ni en fin d'essai** (limites et date vides) tant que l'administrateur ne les renseigne pas.
- Non couvert : limite de **pièces** par mois (volume, à définir avec les tarifs).

### 3. IA, coûts et santé — *à venir*
Usage de l'IA par cabinet (requêtes, jetons, coût estimé) avec plafonds ; état des services (backend, ai-service, base) ; files hors ligne en erreur ; sauvegarde de la base visible.

### 4. Durcissement — *à prévoir*
Authentification à deux facteurs obligatoire pour les administrateurs, journal d'audit infalsifiable (chaînage), alertes (comptes verrouillés en masse, connexions anormales).
