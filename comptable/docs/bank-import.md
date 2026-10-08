# Import de relevés bancaires (Phase D)

## Principe

L'import se fait en **deux temps** : un **aperçu** qui ne remplace rien et n'écrit rien, puis la **confirmation**. Chaque import est un **lot**
tracé (`bank_import_batch`) : qui, quand, quel fichier (empreinte SHA-256), combien de lignes lues, valides, rejetées, déjà connues, importées.
Un import qui n'apporte rien de nouveau est tout de même tracé (`NOTHING_NEW`). Aucune ligne n'est ignorée en silence : chaque ligne invalide
est rapportée avec son numéro et sa raison.

```
fichier CSV ──▶ BankCsvParser (colonnes, montants, dates)
            ──▶ normalisation du libellé + indices (BankLabelNormalizer)
            ──▶ empreinte de chaque ligne (BankFingerprint)
            ──▶ doublons exacts / possibles (BankStatementImporter)
            ──▶ Apercu ──(confirmation)──▶ BankImportBatch + ReleveBancaire + LigneReleve
            ──▶ rapprochement automatique des cas certains, propositions pour les probables
```

## Colonnes

Sans configuration, le mapping est **déduit de l'en-tête** (français ou anglais) : `Date opération`, `Date valeur`, `Libellé`/`Description`,
`Référence`, `Débit`/`Crédit` ou `Montant`, `Solde`. Sans en-tête exploitable, le format historique `date;libellé;montant` s'applique.
Un mapping explicite se donne par **positions** (`colDate`, `colLibelle`, `colMontant`, `colDebit`, `colCredit`, `colReference`, `colDateValeur`,
`colSolde`, `enTete`, `separateur`). Aucune règle métier ne lit `colonne[2]` : tout passe par `BankCsvMapping`.

* Séparateur détecté (`;`, `,`, tabulation), champs entre guillemets, BOM UTF-8, repli ISO-8859-1 si le fichier n'est pas de l'UTF-8 valide.
* Montants tolérants : `1 234 567`, `1 234,56`, `1.234,56`, `1,234.56`, `(100)`, `-100`, `500 FCFA`. Le **dernier** séparateur est le séparateur décimal.
* Dates : `aaaa-mm-jj`, `jj/mm/aaaa`, `jj-mm-aaaa`, `jj.mm.aaaa`.
* Débit et crédit sur la même ligne : ligne rejetée.

Hors périmètre de la phase (volontairement) : PDF bancaire, OFX, MT940, CAMT, Open Banking.

## Identité d'une transaction

`content_fingerprint` = SHA-256 de `compte | date | montant | sens | référence normalisée | libellé normalisé | rang d'occurrence`.

* **Le montant seul ne suffit jamais** à déclarer un doublon.
* Le **rang d'occurrence** distingue deux lignes strictement identiques d'un même relevé (deux prélèvements identiques le même jour sont deux
  opérations) tout en restant **stable** d'un import à l'autre : réimporter le même fichier redonne les mêmes empreintes.
* Unicité en base : index `(entreprise_id, compte_id, content_fingerprint)` — un import concurrent du même fichier est refusé proprement (409).

| Cas | Traitement |
|---|---|
| `EXACT_DUPLICATE` : empreinte déjà connue | **non importée**, comptée dans le lot |
| `POSSIBLE_DUPLICATE` : même compte, jour, montant et sens qu'une transaction connue, mais autre libellé/référence | **importée** et marquée ; constat `BANK_DUPLICATE_TRANSACTION` tant qu'elle n'est pas rapprochée ; le comptable confirme « deux opérations distinctes » |
| nouvelle | importée |

Les lignes historiques (avant la Phase D) n'ont pas d'empreinte : elles ne sont pas protégées contre un réimport.

## Normalisation et indices

`libelle` (original) n'est **jamais modifié**. `libelle_normalise` : majuscules, sans accents, ponctuation et espaces superflus supprimés (tirets et
barres des numéros conservés). Indices extraits (jamais des vérités) : nom probable du tiers, numéro de facture probable, référence, moyen de
paiement (`VIREMENT`, `CHEQUE`, `PRELEVEMENT`, `CARTE`, `RETRAIT_ESPECES`, `VERSEMENT_ESPECES`, `MOBILE_MONEY`, `FRAIS`).
Un retrait n'est **pas** une charge : aucune classification comptable n'est faite à partir du libellé.

## Soldes

`solde_debut` / `solde_fin` sont **facultatifs** (« si disponibles »). Quand ils existent — fournis ou déduits d'une colonne solde —
l'aperçu vérifie `ouverture + crédits − débits = clôture` et affiche l'écart.

## API (cabinet uniquement)

| Route | Rôle |
|---|---|
| `POST /api/entreprises/{id}/banque/imports/apercu` | aperçu chiffré, rien n'est écrit |
| `POST /api/entreprises/{id}/banque/imports` | import + lot ; `201` si un relevé est créé, `200` si rien de nouveau |
| `GET  /api/entreprises/{id}/banque/imports` | historique des lots |

L'ancien `POST …/rapprochements` reste valable (même importeur) ; il répond `409` si le fichier n'apporte rien de nouveau.

## Sécurité des données

Les relevés sont des données sensibles : routes réservées aux rôles cabinet (`CLIENT` → 403), isolation par entreprise (compte d'une autre
entreprise → 404), aucune ligne brute n'est écrite dans les journaux (seuls des compteurs et l'empreinte du fichier).
