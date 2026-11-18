# Reprise d'une balance d'ouverture

Un cabinet qui change d'outil apporte la balance de son ancien logiciel au lieu de tout ressaisir. Écran : **Réglages du dossier → Reprise de balance**. API : `POST /api/entreprises/{id}/reprise-balance/apercu` puis `POST /api/entreprises/{id}/reprise-balance` (envoi du fichier en `multipart`, champ `fichier`).

## Fichier accepté
Excel (`.xlsx`) ou CSV (séparateur `;`, tabulation, `,` ou `|`, UTF-8 ou Windows-1252). Colonnes reconnues par leur titre, dans n'importe quel ordre : `Compte`, `Débit`, `Crédit`, et facultativement `Libellé` et `Tiers`. Sans titres : Compte, Libellé, Débit, Crédit. Les lignes « Total » sont ignorées. Montants « 1 234 567,50 » ou « 1234567.50 ». Un montant négatif est refusé (un solde créditeur va dans la colonne Crédit). Le format Excel ancien (`.xls`) n'est pas lu.

## Ce que fait l'import
1. **Aperçu** : rien n'est écrit. Chaque ligne est expliquée (OK, erreur, tiers à créer) ; la balance doit être équilibrée.
2. **Import** : une seule écriture d'à-nouveaux, de nature `OPENING`, **en brouillon**, datée du premier jour de l'exercice, journal `OD`, numéro `AN-<année>`. Le comptable la relit puis la valide comme toute écriture. Les clients et fournisseurs inconnus sont créés avec leur compte.

## Règles de sécurité
- **Comptes de bilan seulement (classes 1 à 5).** L'écriture est de nature `OPENING`, exclue de l'activité de la période : y mettre des comptes de gestion (6, 7) ferait disparaître cette activité des résultats. Une balance en cours d'année se reprend à la date d'ouverture de l'exercice ; les mouvements depuis se saisissent normalement.
- **Clients (411…) et fournisseurs (401…) : toujours sur le compte d'un tiers**, jamais sur le compte collectif. Sans cela, la balance âgée, le lettrage et le recouvrement ne retrouveraient pas ces soldes. Colonne `Tiers` obligatoire ; pour un solde global, un nom comme « Clients divers (reprise) ».
- **Aucun compte d'attente inventé** : une balance déséquilibrée est refusée avec son écart.
- **Une seule reprise par exercice.** Pour recommencer, supprimer d'abord l'écriture d'ouverture si elle est encore en brouillon.
- Compte absent du plan comptable du dossier : signalé sur sa ligne, à ajouter dans « Plan comptable » (aucun compte n'est créé en silence).
- Réservé au cabinet (refusé à un utilisateur du portail client) ; tracé dans le journal d'audit (`REPRISE_BALANCE_IMPORTEE`).
- Fichier plafonné à 5 Mo, 5 000 lignes ; classeur Excel lu sans DTD ni entités externes.

## Limites connues
- Pas de reprise des **factures ouvertes** une à une : un client est repris par son solde global. La balance âgée le classe à la date d'ouverture (donc en « plus de 90 jours ») tant que ses factures ne sont pas ressaisies.
- Pas de reprise de l'historique des écritures de l'ancien exercice ni des immobilisations.
- Le format du fichier de chaque logiciel d'origine (Sage, Ciel, Odoo…) n'a pas été testé : seul le format générique ci-dessus est garanti.
