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
- **Les pages déjà consultées, en lecture seule** : tableau de bord et dossiers du cabinet, équipe, liste des entreprises, tableau de bord / pièces / écritures / comptes / journaux / exercices / tiers / demandes de pièces / constats de revue / rapports / états financiers / synthèse bancaire / périodes de TVA d'une entreprise. Ce sont les **dernières données vues** sur cet appareil (conservées 30 jours, 800 réponses au plus), ou celles du bouton « Télécharger ce dossier ».
- Un bandeau jaune « **Mode hors ligne** » l'indique ; il disparaît au retour d'Internet. Une page jamais consultée affiche un message clair (« hors ligne et cette page n'a pas encore été consultée »).

## Travailler hors ligne et synchroniser au retour d'Internet

### Télécharger un dossier avant de partir

Dans un dossier, le bouton **« Télécharger ce dossier »** (bas du menu latéral, section *Hors ligne*) charge d'un coup les pages de lecture du dossier : tableau de bord, documents et collecte, tiers, plan comptable, écritures (avec leur détail), facturation, achats, stocks, immobilisations, régularisations, trésorerie, recouvrement, révision, banque, périodes de TVA et états financiers de l'exercice ouvert. Ces données restent lisibles **30 jours** (800 réponses au plus par appareil). Il faut être en ligne pour le faire, avec une session ouverte ; la date du dernier téléchargement est affichée.

### Saisies autorisées hors ligne (file d'attente)

Seules **deux actions**, sûres à rejouer plus tard, sont acceptées hors ligne. Elles sont gardées sur l'appareil (IndexedDB, survit à la fermeture de l'application et au redémarrage) puis envoyées automatiquement au retour d'Internet :

| Action | Pourquoi elle est sûre |
|---|---|
| **Dépôt d'une pièce** (photo ou PDF) | le serveur détecte lui-même les fichiers en double (constat « Fichier déjà reçu ») |
| **Création d'une écriture en brouillon** | un brouillon ne compte dans aucun état tant qu'il n'est pas validé en ligne |

Un bandeau bleu indique « N élément(s) en attente d'envoi », avec un bouton **Détail** (liste, **Supprimer**, **Réessayer**) et **Envoyer maintenant**. À chaque retour du réseau (évènement `online`), au démarrage et toutes les 60 s tant qu'il reste des éléments, la file est envoyée **un élément à la fois, dans l'ordre de saisie**. Un rapport s'affiche : « N élément(s) envoyé(s) ».

Règles de synchronisation :

- Le serveur reste juge : un élément refusé (exercice clôturé, donnée invalide…) passe en **« Refusé »** avec le message du serveur, sans bloquer les suivants. Il reste visible pour être corrigé (le supprimer et ressaisir) ou réessayé.
- Réseau coupé en cours d'envoi, serveur indisponible (502/503/504) ou session expirée (401) : l'élément **reste en attente** et l'envoi s'arrête ; il reprend tout seul (ou après reconnexion du même utilisateur).
- Les éléments appartiennent à l'utilisateur qui les a saisis (e-mail) : seule sa session les envoie. **Un autre utilisateur qui se connecte sur le même appareil supprime la file de l'utilisateur précédent.** Les éléments du même utilisateur survivent à une déconnexion ou à l'expiration de la session.
- Risque résiduel : si la connexion tombe **après** que le serveur a traité un élément mais **avant** que la réponse n'arrive, l'élément est renvoyé au prochain passage. Une pièce en double est alors signalée par le contrôle de doublon ; une écriture en brouillon en double se supprime depuis l'écran Écritures (aucun effet comptable tant qu'elle n'est pas validée).

## Ce qui ne fonctionne PAS sans Internet (volontairement)

- **Toute autre modification** : validation d'écriture, clôture, import bancaire, rapprochement, facturation (devis, factures, avoirs), achats, stocks, tiers, changement de mot de passe, envois… est refusée immédiatement avec un message clair. Rejouer plus tard ces actions sur des données qui ont pu changer (période clôturée, version modifiée par un collègue, numérotation des factures, stock) serait dangereux.
- La **lecture par l'IA** d'une pièce déposée hors ligne n'a lieu qu'à l'envoi (donc après le retour d'Internet).
- Les **téléchargements** de pièces et d'exports ne sont pas gardés hors ligne.
- La **connexion** d'un utilisateur nécessite Internet (la session déjà ouverte, elle, reste utilisable).
- Les notifications ne se rafraîchissent pas.

## Confidentialité sur un appareil partagé

Les données de lecture gardées pour le hors ligne sont **supprimées à chaque changement de session** : déconnexion, nouvelle connexion, session expirée, changement de mot de passe. La file d'attente des saisies non envoyées, elle, est conservée pour le même utilisateur et supprimée pour tout autre. Les données d'un cabinet ne restent donc pas lisibles pour la personne qui se connecte ensuite sur le même appareil. Sur un appareil perdu ou volé, la session reste valable jusqu'à son expiration (8 h par défaut) : désactiver l'utilisateur depuis l'écran Équipe révoque sa session côté serveur ; les données déjà gardées sur l'appareil ne sont effacées qu'à sa prochaine connexion ou déconnexion. Ne pas installer l'application sur un appareil non maîtrisé.

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
- La synchronisation s'exécute quand l'application est ouverte : pas de synchronisation en arrière-plan application fermée, pas de notifications push.
- Le téléchargement du dossier ne garde que les vues par défaut (mois courant, première page) ; une page ou un filtre jamais consulté reste indisponible hors ligne.
- Seules les routes de la liste blanche (`cache-lectures.interceptor.ts`) sont gardées ; une nouvelle page de lecture doit y être ajoutée pour fonctionner hors ligne.
