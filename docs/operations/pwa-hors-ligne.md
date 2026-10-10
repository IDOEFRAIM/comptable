# Warimara en application installable (PWA) et mode hors ligne

## Installer l'application

| Appareil | Comment |
|---|---|
| Ordinateur (Chrome, Edge) | bouton **« Installer l'application »** dans la barre du haut de Warimara, ou l'icône d'installation dans la barre d'adresse |
| Android (Chrome) | même bouton, ou menu ⋮ → *Installer l'application* |
| iPhone / iPad (Safari) | bouton Partager → *Sur l'écran d'accueil* (Safari ne propose pas le bouton d'installation) |

L'application installée s'ouvre dans sa propre fenêtre, avec l'icône Warimara.

**Prérequis : HTTPS.** Un navigateur n'active l'installation et le hors ligne que sur une connexion sécurisée (ou sur `localhost`). En production, utilisez le mode HTTPS (`make deploy`, Caddy) : en HTTP simple sur une adresse IP, l'application fonctionne mais ne s'installe pas.

## Ce qui fonctionne sans Internet

- **L'application elle-même** : après une première visite en ligne, tout le code et les images sont gardés sur l'appareil ; elle se rouvre et se recharge sans réseau.
- **Les pages déjà consultées, en lecture seule** : tableau de bord et dossiers du cabinet, équipe, liste des entreprises, tableau de bord / pièces / écritures / comptes / journaux / exercices / tiers / demandes de pièces / constats de revue / rapports / états financiers / synthèse bancaire / périodes de TVA d'une entreprise. Ce sont les **dernières données vues** sur cet appareil (conservées 7 jours, 300 réponses au plus).
- Un bandeau jaune « **Mode hors ligne** » l'indique ; il disparaît au retour d'Internet. Une page jamais consultée affiche un message clair (« hors ligne et cette page n'a pas encore été consultée »).

## Ce qui ne fonctionne PAS sans Internet (volontairement)

- **Toute modification** (saisie d'écriture, import, validation, envoi, changement de mot de passe, clôture…) est refusée immédiatement avec un message clair. Il n'existe **pas** de file d'écritures différées : en comptabilité, rejouer plus tard une saisie sur des données qui ont pu changer (période clôturée, version modifiée par un collègue) serait dangereux. Une saisie en cours est donc à enregistrer **avec** une connexion.
- Les **téléchargements** de pièces et d'exports ne sont pas gardés hors ligne.
- La **connexion** d'un utilisateur nécessite Internet (la session déjà ouverte, elle, reste utilisable en lecture).
- Les notifications ne se rafraîchissent pas.

## Confidentialité sur un appareil partagé

Les données gardées pour le hors ligne sont **supprimées à chaque changement de session** : déconnexion, nouvelle connexion, session expirée, changement de mot de passe. Les données d'un cabinet ne restent donc pas lisibles pour la personne qui se connecte ensuite sur le même appareil. Sur un appareil perdu ou volé, la session reste valable jusqu'à son expiration (8 h par défaut) : désactiver l'utilisateur depuis l'écran Équipe révoque sa session côté serveur ; les données déjà gardées sur l'appareil ne sont effacées qu'à sa prochaine connexion ou déconnexion. Ne pas installer l'application sur un appareil non maîtrisé.

## Mises à jour

À chaque nouvelle version, l'application la télécharge en arrière-plan et affiche « **Une nouvelle version de Warimara est prête — Mettre à jour** ». Côté serveur, `ngsw-worker.js`, `ngsw.json` et `manifest.webmanifest` sont servis sans cache (voir `comptable/frontend/nginx.conf`), sinon une mise à jour ne serait jamais détectée.

## Vérifier

- Tests unitaires : `npm test` (intercepteurs hors ligne, cache de lectures, purge).
- Test réel sur un build de production (le service worker est désactivé en développement) :

```bash
PWA_URL=http://localhost:8090 PWA_EMAIL=<expert> PWA_PASSWORD=<mot de passe> npx playwright test e2e/pwa.spec.ts
```

Il vérifie le manifeste et les icônes, l'activation du service worker, le rechargement sans réseau, les données lues depuis le cache, et la purge à la déconnexion.

## Limites connues

- Le service worker d'Angular met bien l'application en cache, mais son cache de données d'API ne s'est jamais créé lors des essais : les lectures hors ligne sont donc gérées par l'application (API Cache, voir `core/pwa/cache-lectures.interceptor.ts`).
- Pas de synchronisation en arrière-plan, pas de notifications push.
- Seules les routes de la liste blanche (`cache-lectures.interceptor.ts`) sont gardées ; une nouvelle page de lecture doit y être ajoutée pour fonctionner hors ligne.
