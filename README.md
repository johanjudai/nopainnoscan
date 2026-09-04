# NoPainNoScan

Scanner nutrition perso orienté **sèche / performance**, à deux (ou plus) sur le
même serveur : tu pointes la caméra du téléphone sur un produit, l'app lit le
code-barres en live (sans prendre de photo) et rend une note en 4 catégories,
calculée selon **ton** profil. Si tu indiques l'enseigne où tu es, elle propose
des alternatives mieux notées déjà vues dans ce magasin.

| Catégorie          | Score    |
| ------------------ | -------- |
| `parfait`          | ≥ 70     |
| `pas_mal`          | 50 – 69  |
| `a_eviter`         | 30 – 49  |
| `a_ne_pas_manger`  | < 30     |

Ce n'est pas un Nutri-Score : le ratio protéines / kcal est le critère n°1, le
sucre, les gras saturés et la densité calorique sont pénalisés, et les
pondérations changent selon la phase (`cut` / `maintenance` / `bulk`).

---

## Architecture

```
Android (CameraX + ML Kit)  →  API FastAPI (Docker, serveur perso)  →  Postgres
         X-Api-Key                              ↓ si produit inconnu
                                         Open Food Facts (API publique)
```

**Partagé sur le serveur** : cache produits, scoring, enseignes, disponibilité
produit ↔ enseigne. **Propre à chaque utilisateur** : clé API, profil
(poids, %MG, objectif…), historique de scans.

```
.
├── backend/            # FastAPI + SQLAlchemy : auth, scoring, alternatives, client OFF
│   ├── app/
│   └── tests/          # pytest (API en SQLite mémoire + scoring)
├── android/            # Projet Android Studio complet (Kotlin, Gradle KTS)
├── docker-compose.yml  # API + Postgres
└── .github/workflows/  # CI, garde-fou main, build APK
```

---

## 1. Backend : déployer sur ton serveur

Prérequis : Docker + Docker Compose.

```bash
git clone https://github.com/johanjudai/nopainnoscan.git /opt/nopainnoscan
cd /opt/nopainnoscan
cp .env.example .env          # puis changer POSTGRES_PASSWORD
docker compose up -d --build
curl http://localhost:8088/health   # → {"status":"ok"}
```

Créer un utilisateur par personne. La clé n'est affichée **qu'une fois** (seul
son hash SHA-256 est stocké), à coller dans l'app (Réglages) :

```bash
docker compose exec nopainnoscan-api python -m app.cli create-user user1
docker compose exec nopainnoscan-api python -m app.cli create-user user2
docker compose exec nopainnoscan-api python -m app.cli rotate-key user1   # révoque l'ancienne
```

L'API écoute sur `API_PORT` (8088). Mets-la derrière ton reverse proxy habituel,
idéalement en TLS : la clé API transite dans un header.

### Endpoints

Toutes les routes sauf `/health` exigent le header `X-Api-Key`.

| Méthode | Route                              | Rôle                                                              |
| ------- | ---------------------------------- | ----------------------------------------------------------------- |
| GET     | `/health`                          | Liveness (public)                                                 |
| GET     | `/me`                              | Utilisateur associé à la clé                                      |
| GET     | `/stores`                          | Enseignes connues (`leclerc`, `lidl`, `grand_frais`, `auchan`, `carrefour`) |
| GET     | `/profile` · PUT `/profile`        | Profil de l'utilisateur courant, avec `estimate` (dérivés)        |
| POST    | `/profile/estimate`                | Aperçu des dérivés pour un profil non enregistré (saisie live)    |
| GET     | `/scan/barcode/{code}?store=`      | Cache → Open Food Facts → score + alternatives, historise le scan |
| POST    | `/scan/manual?store=`              | Score depuis des valeurs / 100 g (OCR ou saisie)                  |
| GET     | `/scans?limit=`                    | Historique de l'utilisateur                                       |

Un scan avec `store=` marque le produit comme « vu dans cette enseigne » : c'est
ce qui alimente les alternatives. Une alternative = même famille Open Food Facts
(`pnns_groups_2`), mieux notée **pour ton profil**, vue dans l'enseigne indiquée
(ou n'importe où si aucune enseigne).

### Profil : on saisit des mesures, le serveur déduit le reste

Le profil ne contient que des choses mesurables : sexe, âge, taille, poids,
tour de cou, tour de taille (et hanches pour les femmes), niveau d'activité,
objectif (`cut` / `maintenance` / `bulk`) et % de masse grasse visé. Tout le
reste est calculé dans `backend/app/body.py` et renvoyé dans `estimate` :

- masse grasse (méthode US Navy, à partir des tours de cou / taille / hanches) et masse maigre ;
- métabolisme de base (Mifflin-St Jeor) et dépense journalière (facteur d'activité) ;
- cibles kcal (dépense × 0,80 en sèche, × 1,10 en prise) et protéines (2,2 / 1,8 / 2,0 g par kg) ;
- si l'utilisateur force une cible à la main (`daily_kcal_target`, `daily_protein_target_g`),
  des `messages` de niveau `info` ou `warning` (sous le métabolisme de base, déficit
  trop agressif, protéines insuffisantes, etc.).

Doc interactive : `http://<serveur>:8088/docs`.

### Dev local

```bash
cd backend
python -m venv .venv && . .venv/Scripts/activate      # Linux/mac : . .venv/bin/activate
pip install -r requirements-dev.txt
ruff check . && ruff format --check . && pytest
```

Pondérations et seuils : `backend/app/scoring.py` (`GOAL_WEIGHTS`, `THRESHOLDS`).
Le schéma est créé par `create_all` au démarrage (pas encore de migrations :
en cas de changement de colonne, `docker compose down -v` remet la base à zéro).

---

## 2. App Android

Projet Android Studio complet dans `android/` (minSdk 26, compileSdk 34).

```bash
cd android
./gradlew assembleDebug        # → app/build/outputs/apk/debug/app-debug.apk
adb install app/build/outputs/apk/debug/app-debug.apk
```

Au premier lancement : **Réglages** → URL de l'API (`http://…:8088/`) + clé API,
puis **Mon profil**. La valeur par défaut de l'URL vient de
`android/gradle.properties` (`API_BASE_URL`, surchargeable avec
`-PAPI_BASE_URL=…`).

Interface Material 3, direction « soft clinique » (blanc chaud, cartes arrondies,
accent corail, police Sora pour les titres et les chiffres). Les maquettes sont
dans le canvas Claude Design lié au projet.

- **Accueil** : état de connexion, carte de scan, tuiles masse grasse et cibles du jour, derniers scans.
- **Scanner** : barre d'enseignes en haut (mémorisée), aperçu caméra, feuille de résultat
  avec anneau de score, catégorie, détail bonus / malus et alternatives mieux notées dans l'enseigne.
  Le scan est **live** : CameraX pousse chaque frame (720p) à ML Kit on-device, un code doit
  être lu sur 2 frames consécutives avant d'interroger l'API.
- **Profil** : sexe et objectif en contrôles segmentés, activité en menu, mensurations ;
  la masse grasse et les cibles s'affichent au fil de la saisie via `POST /profile/estimate`
  (débounce 350 ms), avec un message d'info ou d'alerte si une cible est modifiée à la main.
- **Réglages** : URL de l'API, clé, test de connexion.

Sécurité côté app : clé API en stockage privé (`allowBackup=false`), un seul
client HTTP partagé avec timeouts courts. L'API est appelée en `http://` sur le
LAN, d'où `usesCleartextTraffic="true"` dans le manifest : à retirer dès que tu
passes en TLS via ton reverse proxy.

---

## Flux Git

- `dev` : branche de travail, tout part de là (branche par défaut).
- `main` : stable. Alimentée uniquement par PR `dev → main` (workflow `guard-main`).
- Tag `v*` sur `main` → APK signé publié en GitHub Release si les secrets
  `ANDROID_KEYSTORE_BASE64`, `ANDROID_KEYSTORE_PASSWORD`, `ANDROID_KEY_ALIAS`,
  `ANDROID_KEY_PASSWORD` sont configurés ; sinon APK debug en artefact.

---

## Roadmap

- [ ] OCR live (ML Kit Text Recognition) en fallback quand aucun code-barres n'est
      détecté : parsing du tableau nutritionnel → `POST /scan/manual`.
- [ ] Écran historique (`GET /scans`).
- [ ] Migrations (Alembic) dès que le schéma bouge.
- [ ] Import complet Open Food Facts pour du 100 % offline dès le premier scan.

## Licence

MIT — voir [LICENSE](LICENSE).
