# Onboarding d'un cabinet pilote — liste de contrôle

Objectif : un cabinet passe de « compte créé » à « première entreprise exploitable » **sans développeur, sans SQL, sans Swagger, sans Postman**.
Qui fait quoi : **O** = opérateur de la plateforme, **E** = expert-comptable du cabinet.

## A. Avant la création (O)

- [ ] Plateforme installée, readiness UP (`docs/operations/pilot-runbook.md` §2).
- [ ] Une sauvegarde existe et un exercice de restauration a réussi (`make restore-drill`).
- [ ] Le cabinet connaît les **limites du pilote** (voir runbook) : TVA Burkina non finalisable (cadre réglementaire non vérifié), pas d'engagement de niveau de service, envois automatiques aux clients désactivés.
- [ ] Interlocuteur du cabinet identifié (nom, e-mail, téléphone) et canal de support convenu.

## B. Création du cabinet (O)

```bash
make provision CABINET="<Nom du cabinet>" EMAIL=<expert@cabinet> NOM=<Nom> PRENOM=<Prénom> PAYS=BF
```

- [ ] Le mot de passe initial (affiché une fois) est transmis **par un canal distinct** de l'e-mail du compte.
- [ ] L'expert se connecte sur `/login` et change son mot de passe (12 caractères minimum).

## C. Équipe (E) — écran « Équipe »

- [ ] Ajouter chaque collaborateur : **laisser le champ mot de passe vide** → un mot de passe temporaire est généré et affiché une seule fois ; le collaborateur doit le changer à sa première connexion (aucun accès avant).
- [ ] Mot de passe oublié : bouton de réinitialisation → lien à usage unique à transmettre au membre (validité 24 h par défaut) ; l'ancien mot de passe reste valable jusqu'à l'utilisation du lien ; l'utilisation révoque les sessions en cours.
- [ ] Rôles de l'équipe : *expert-comptable* (administration, revue, clôture, support) et *collaborateur* (saisie, pièces, banque, contrôles).  Un collaborateur ne voit que les dossiers qui lui sont affectés ; un membre d'un autre cabinet ne voit rien (réponse « introuvable »).
- [ ] Au moins **deux experts** actifs pour éviter le blocage (le dernier expert actif ne peut pas être désactivé).

## D. Première entreprise (E) — assistant « Onboarding entreprise »

1. **Identité** : raison sociale, pays, devise, régime fiscal, secteur.
   - Pays **Burkina Faso** : le régime fiscal est enregistré, mais **aucun cadre fiscal burkinabè n'est activé** ; l'écran TVA indique « Cadre réglementaire : non vérifié » et la finalisation reste impossible.
2. **Plan comptable** : le plan comptable de référence est cloné pour l'entreprise à la création, avec les journaux standards et l'exercice de l'année en cours. Vérifier l'exercice (dates) dans « Exercices » ; ajuster si l'exercice n'est pas calendaire.
3. **Paramètres comptables** (écran « Paramètres comptables ») : vérifier les comptes de TVA collectée / récupérable, banque, caisse. Par défaut des comptes système sont utilisés ; un compte non configuré provoque un message explicite à la comptabilisation (jamais d'écriture silencieuse sur un mauvais compte).
4. **Reprise des données** — ce que le produit fait aujourd'hui :
   - **Relevés bancaires (CSV)** : aperçu sans écriture (lignes valides/invalides, doublons exacts et possibles, cohérence des soldes), puis import tracé par lot et **idempotent** (un relevé importé deux fois ne crée aucun doublon ; aucune ligne n'est ignorée en silence).
   - **Plan comptable** : plan de référence cloné à la création ; ajouts de comptes à la main (écran « Plan comptable »).
   - **Tiers et soldes d'ouverture** : **pas d'import CSV dans cette version**. Les tiers se créent à l'écran ou depuis les pièces ; les soldes d'ouverture se saisissent par écriture manuelle dans le journal « Opérations diverses (OD) », datée du premier jour de l'exercice (il n'existe pas de journal d'à-nouveaux dédié dans cette version ; l'écran de saisie refuse une écriture déséquilibrée). À faire par l'expert ; les contrôles et le tableau des flux de trésorerie peuvent signaler ces écritures d'ouverture : vérifier le dossier financier après la reprise. Prévoir ce travail dans le planning de l'onboarding d'un dossier existant.

- [ ] L'entreprise apparaît dans « Dossiers » avec un statut calculé.

## E. Premier cycle de travail (E + collaborateur)

- [ ] Déposer une pièce (PDF, JPEG ou PNG, taille limitée) → extraction → proposition d'écriture → validation.
- [ ] Saisir/importer des transactions bancaires → rapprochement.
- [ ] Lancer les **contrôles** (revue) : les constats apparaissent dans le tableau de bord du cabinet.
- [ ] Ouvrir le **dossier financier** (bilan, compte de résultat, tableau des flux, notes) : les états se calculent depuis les écritures validées ; un constat bloquant empêche la finalisation, avec son explication.

## F. Lien de dépôt client

- [ ] L'URL publique (`COLLECTE_LIEN_DEPOT_BASE_URL`) est correcte. Les liens sont signés, expirent et sont limités en débit ; un lien invalide répond de façon uniforme.
- [ ] Tant que `COLLECTE_ENVOI_ACTIF=false`, les liens sont transmis manuellement par le cabinet (copie du lien).

## G. Clôture de l'onboarding

- [ ] Le diagnostic de support (écran Support / `GET /api/cabinet/support/diagnostic`) ne montre aucun problème bloquant.
- [ ] Le cabinet sait où trouver la **référence d'erreur** affichée en cas de problème et la communiquer au support.
- [ ] Date de la première sauvegarde incluant ce cabinet notée.

## G bis. Reprise de données : ce qui existe et ce qui n'existe pas

| Reprise | Statut |
|---|---|
| Relevés bancaires CSV (aperçu, lots, idempotence) | **Disponible** |
| Import des tiers (clients / fournisseurs) | **Absent** — saisie à l'écran ou création depuis les pièces |
| Import des soldes d'ouverture | **Absent** — écriture manuelle dans le journal OD au premier jour de l'exercice |
| Export portable d'un cabinet / d'une entreprise | **Absent** — la sauvegarde de la plateforme n'est **pas** un export client |

Ces trois absences peuvent devenir des demandes du terrain pendant le pilote ; elles ne sont pas promises.

## H. Hors périmètre du pilote (à ne pas promettre)

- Déclaration de TVA burkinabè finalisée / télépaiement fiscal : indisponible (aucune règle fiscale n'est inventée).
- Notification par e-mail : non fournie (les réinitialisations de mot de passe se transmettent par l'expert).
- Haute disponibilité, plusieurs instances : non fournies.
