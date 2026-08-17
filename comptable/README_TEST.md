# 🧪 Guide de Test - Comptable

## ⚡ Quick Start - Créer un Compte de Test en 2 minutes

### Étape 1: Démarrer l'application
```bash
# Terminal 1: Démarrer le backend
mvn spring-boot:run

# Terminal 2: Démarrer le frontend
cd frontend
npm install
npm start
```

L'application est prête sur `http://localhost:4200`

---

## 🔐 Créer un Compte de Test

### Via l'Interface Web (Recommandé)

1. Aller à `http://localhost:4200`
2. Cliquer sur **"S'enregistrer"** (ou aller à `/register`)
3. Remplir le formulaire :

```
Email:                     cabinet@example.com
Mot de passe:              Cabinet123!@#
Mot de passe (confirmer):  Cabinet123!@#
Nom:                       Dupont
Prénom:                    Jean
Téléphone:                 +225 01 23 45 67 89
Raison sociale du cabinet: Cabinet Conseil & Associés
Pays:                      CI
```

4. Cliquer sur **"S'enregistrer"**
5. ✅ Vous êtes connecté automatiquement !
6. Le cabinet et l'utilisateur sont créés avec le rôle `EXPERT_COMPTABLE`

---

## 🏢 Accéder aux Données de Test Pré-chargées

**Les données suivantes sont déjà créées via la migration `V8__seed_test_data.sql`:**

### 📋 Cabinets (Tenants)
- **Cabinet Conseil & Associés** (ID: `11111111-1111-1111-1111-111111111111`)
- **Expert Comptable Solutions** (ID: `22222222-2222-2222-2222-222222222222`)

### 🏪 Entreprises Clientes
Pour le cabinet "Cabinet Conseil & Associés" :
- **ACME Corporation** (Régime Normal - Commerce)
- **TechStart SARL** (Régime Simplifié - IT)

Pour le cabinet "Expert Comptable Solutions" :
- **Pharma Plus** (Pharmacie)

### 📊 Journaux Pré-configurés
Chaque entreprise a 5 journaux :
- `VT` - Journal de Ventes
- `AC` - Journal d'Achats
- `BQ` - Journal de Banque
- `CS` - Journal de Caisse
- `OD` - Journal d'Opérations Diverses

### 📅 Exercices Comptables
- **2024-01-01 à 2024-12-31** : OUVERT
- **2023-01-01 à 2023-12-31** : FERMÉ (pour ACME)

### 👥 Tiers de Base
- Clients et fournisseurs de test
- Pré-configurés pour les tests de comptabilisation

---

## 🧪 Scénarios de Test Recommandés

### Scénario 1: Enregistrement et Exploration (5 min)
```
1. Créer un compte via /register
2. Se connecter
3. Explorer la page d'accueil / Dashboard
4. Vérifier les entreprises pré-chargées (ACME, TechStart)
5. Vérifier les journaux et exercices comptables
```

### Scénario 2: Création d'une Écriture Comptable (10 min)
```
1. Se connecter avec le compte créé
2. Sélectionner l'entreprise "ACME Corporation"
3. Aller dans Comptabilité → Écritures
4. Créer une nouvelle écriture :
   - Numéro pièce: FAC-001
   - Date: Aujourd'hui
   - Journal: VT (Ventes)
   - Libellé: Vente de marchandises
   - Ligne 1: Débit Compte 521 (Banque) 100.000
   - Ligne 2: Crédit Compte 701 (Ventes) 100.000
5. Valider l'écriture
6. Vérifier que l'écriture apparaît dans la liste
```

### Scénario 3: Visualisation des Rapports (5 min)
```
1. Aller dans Comptabilité → Rapports
2. Consulter :
   - Balance générale (tous les soldes)
   - Grand livre par compte
   - Journal des opérations
3. Filtrer par exercice comptable
```

### Scénario 4: Upload de Document (10 min)
```
1. Aller dans Documents
2. Uploader un PDF/JPG (facture)
3. Attendre l'extraction OCR
4. Vérifier les données extraites
5. Comptabiliser le document (créer automatiquement une écriture)
6. Vérifier le lien document ↔ écriture comptable
```

### Scénario 5: Gestion Fiscale (5 min)
```
1. Aller dans Fiscalité → Déclarations
2. Créer une déclaration fiscale :
   - Type: TVA
   - Période: Q1 2024
3. Générer un écheancier
4. Marquer comme "Déposée" puis "Payée"
```

---

## 🔑 Credentials Supplémentaires à Créer (Optionnel)

### Compte Collaborateur (Même Cabinet)
```bash
# Pour tester les rôles différents dans l'UI
Email:     collaborateur@example.com
Mot de passe: Collab123!@#
Cabinet:   Cabinet Conseil & Associés
Rôle:      COLLABORATEUR (créé automatiquement)
```

**Note:** Vous devez manuellement assigner le rôle COLLABORATEUR à cet utilisateur via la base de données ou via un endpoint admin (à implémenter).

### Cabinet Différent (Multi-tenant)
```bash
# Pour tester l'isolation des données entre cabinets
Email:     expert@solutions.ci
Mot de passe: Expert123!@#
Cabinet:   Expert Comptable Solutions
Rôle:      EXPERT_COMPTABLE
```

---

## 🐛 Débogage et Logs

### Backend
```bash
# Voir les logs Spring Boot
mvn spring-boot:run | grep -E "ERROR|WARN|INFO"

# Ou consulter le fichier log (si configuré)
tail -f logs/application.log
```

### Frontend
```bash
# Voir les erreurs dans la Console du Navigateur
# F12 → Console tab

# Ou vérifier les logs Angular
ng serve --verbose
```

### Base de Données
```bash
# Accéder à PostgreSQL
psql -h localhost -U myuser -d mydatabase

# Requêtes utiles :
SELECT * FROM utilisateur;
SELECT * FROM tenant;
SELECT * FROM entreprise;
SELECT * FROM ecriture_comptable;
SELECT * FROM document;
```

---

## 📊 Structure des Données Pré-chargées

### Tenants (Cabinets)
```
11111111-1111-1111-1111-111111111111 → Cabinet Conseil & Associés
  └─ aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa → ACME Corporation
  └─ bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb → TechStart SARL

22222222-2222-2222-2222-222222222222 → Expert Comptable Solutions
  └─ cccccccc-cccc-cccc-cccc-cccccccccccc → Pharma Plus
```

---

## ✅ Checklist de Vérification

- [ ] Backend démarre sans erreur
- [ ] Frontend se charge sur localhost:4200
- [ ] Page d'enregistrement accessible
- [ ] Enregistrement d'un nouveau compte réussit
- [ ] Connexion fonctionne
- [ ] Dashboard affiche les données
- [ ] Entreprises pré-chargées visibles
- [ ] Création d'écriture comptable fonctionne
- [ ] Visualisation des rapports fonctionne
- [ ] Upload de document fonctionne
- [ ] Extraction OCR fonctionne
- [ ] Gestion fiscale accessible

---

## 🆘 Problèmes Courants

### Port 4200 déjà utilisé
```bash
# Solution 1: Attendre que le serveur précédent s'arrête
# Solution 2: Utiliser un port différent
ng serve --port 4201 --proxy-config proxy.conf.json
```

### Base de données vide
```bash
# Vérifier que les migrations se sont exécutées
# Les logs doivent montrer: "Flyway ... migrated 8 schema to version 8"

# Si nécessaire, réinitialiser :
docker-compose down -v  # Supprime les volumes
docker-compose up       # Recrée tout
mvn spring-boot:run     # Redémarre (migrations s'exécutent)
```

### Erreur "Identifiants invalides"
```
✗ Les comptes de test ne DOIVENT PAS être créés en dur
✓ Il faut les créer via l'endpoint /api/auth/register-cabinet
✓ Ou modifier les données pré-chargées
```

### JWT Token expiré
```bash
# Reconnectez-vous, le JWT dans localStorage est expiré
# Durée par défaut: 480 minutes (8 heures)
```

---

## 📚 Fichiers Importants pour les Tests

| Fichier | Rôle |
|---------|------|
| `V8__seed_test_data.sql` | Données de test pré-chargées |
| `DOCUMENTATION.md` | Documentation complète |
| `src/main/resources/application.properties` | Config app |
| `frontend/proxy.conf.json` | Proxy vers le backend |
| `compose.yaml` | Configuration Docker |

---

**Dernière mise à jour:** 2024-12-20  
**Version:** 0.0.1-SNAPSHOT
