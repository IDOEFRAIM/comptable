# Benchmarks de performance — séparés des tests fonctionnels

Constat de la phase E : un test de volume est devenu rouge (contrôles : 1 277 s au lieu de 5 s) parce que les **statistiques du planificateur PostgreSQL** étaient périmées après des générations massives, pas à cause du code. Un test métier ne doit jamais dépendre de cela.

## Règle

| | Test fonctionnel (par défaut) | Benchmark (`-Dbenchmark=true`) |
|---|---|---|
| Échelle | réduite (≈ 500 transactions, 1 200 écritures, 300 documents) | complète (5 000 / 20 000 / 50 000 transactions, 10 000 écritures, 3 000 documents) |
| Vérifie | résultats exacts, **nombre de requêtes SQL** (absence de N+1), taille des réponses, agrégation des constats | idem **+ bornes de durée** |
| Durées | **jamais** assertées | assertées (`BanqueVolumeTest` : import < 180 s, contrôles < 120 s, lecture < 30 s) |
| Planificateur | sans objet | `ANALYZE` lancé après chaque génération massive (`Benchmark.preparer`) |

Classes concernées : `BanqueVolumeTest`, `BanquePaginationEchelleTest`, `RevisionVolumeTest` (helper : `com.example.comptable.Benchmark`). `ControlesPerformanceTest` et `DocumentVolumeTest` ne comptent que des requêtes SQL (déterministes) et restent des tests fonctionnels.

## Lancer un benchmark

```bash
./mvnw test -Dtest='BanqueVolumeTest,BanquePaginationEchelleTest,RevisionVolumeTest' -Dbenchmark=true
```

Préparation d'une base de développement ancienne ou volumineuse (plusieurs centaines de milliers de comptes de test accumulés) :

```bash
docker exec comptable-postgres-1 psql -U myuser -d mydatabase -c "VACUUM ANALYZE"
```

`ANALYZE` seul (fait par `Benchmark.preparer`) suffit après une génération de données ; `VACUUM ANALYZE` n'est utile que si la base a beaucoup de lignes mortes (suppressions / mises à jour massives). Les chiffres d'un benchmark ne sont comparables que d'une exécution à l'autre **sur la même machine, base préparée, sans autre charge**.
