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
| GET     | `/stores`                          | Enseignes connues (`leclerc`, `lidl`, `grand_frais`, `auchan`, `carrefour`, `thiriet`) |
| GET     | `/categories`                      | Familles de produits Open Food Facts avec libellé français        |
| GET     | `/recommendations?category=&store=`| Meilleurs produits d'une famille pour ton profil, enseigne si possible |
| GET     | `/profile` · PUT `/profile`        | Profil de l'utilisateur courant, avec `estimate` (dérivés)        |
| POST    | `/profile/estimate`                | Aperçu des dérivés pour un profil non enregistré (saisie live)    |
| GET     | `/scan/barcode/{code}?store=`      | Cache → Open Food Facts → score + alternatives, historise le scan |
| POST    | `/scan/manual?store=`              | Score depuis des valeurs / 100 g (OCR ou saisie)                  |
| GET     | `/scans?limit=`                    | Historique de l'utilisateur                                       |
| GET     | `/products/{id}?store=`            | Fiche d'un produit connu : score actuel + alternatives, sans scan |

Un scan avec `store=` marque le produit comme « vu dans cette enseigne ». Une
alternative = même famille Open Food Facts (`pnns_groups_2`), mieux notée **pour
ton profil**. Avec une enseigne, la réponse sépare `alternatives` (vues dans
l'enseigne) et `alternatives_elsewhere` (vues ailleurs seulement) ; l'app affiche
les premières en tête et les secondes sous un séparateur. Même découpage pour
`/recommendations` (`items` / `items_elsewhere`).

Pour ne pas partir d'un cache vide, le serveur amorce chaque famille au premier
besoin : il importe depuis Open Food Facts les produits populaires de la famille
(filtrés sur l'enseigne quand elle est connue d'OFF), avec leurs enseignes
(`stores_tags`) et leur photo, au plus une fois par semaine par couple famille /
enseigne. Un échec réseau n'est pas mémorisé (nouvel essai au prochain appel), et
`SEED_VERSION` dans `config.py` permet d'invalider tous les amorçages.

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

### Repas conseillé

Chaque réponse de scan ou de fiche produit porte un bloc `meal` (si un profil
existe) calculé dans `backend/app/meal.py` : budget kcal et protéines du repas
(un tiers de la journée), rôle du produit déduit de sa famille ou de ses macros
(féculent, source de protéines, plat complet, légume, fruit, matière grasse,
plaisir, boisson), puis portion conseillée et complément générique pour boucler
le repas (poisson blanc ou poulet à côté d'un féculent, pommes de terre / riz /
pâtes selon l'objectif à côté d'une protéine, skyr pour relever un plat complet).
Le bloc rappelle la part du repas dans la journée et les cibles jour / semaine.
Il n'y a pas encore de journal de ce qui est réellement mangé : le suivi hebdo
réel est en roadmap.

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
./gradlew assembleDebug        # → app/build/outputs/apk/debug/app-arm64-v8a-debug.apk
adb install app/build/outputs/apk/debug/app-arm64-v8a-debug.apk
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
  Un appui sur l'aperçu force la mise au point et l'exposition à cet endroit (repère animé),
  puis l'autofocus continu reprend après 4 s.
- **Profil** : sexe et objectif en contrôles segmentés, activité en menu (de « pas de sport »
  à « athlète », facteurs 1,2 → 1,9), mensurations ;
  la masse grasse et les cibles s'affichent au fil de la saisie via `POST /profile/estimate`
  (débounce 350 ms), avec un message d'info ou d'alerte si une cible est modifiée à la main.
- **Scanner, mode « Tableau nutritionnel »** : OCR live (ML Kit Text Recognition) pour les
  produits sans code-barres. Les lignes reconnues sont regroupées par rangée ; la colonne
  « pour 100 g » est repérée depuis l'en-tête (avant ou après la portion) ; « sodium » est
  converti en sel ; les lettres lues à la place de chiffres (`O`, `l`) et les virgules
  perdues (`8 5 g`) sont réparées. Une passe de **cohérence** corrige ensuite ce que la
  physique interdit, toujours en divisant par 10 (virgule perdue) : sucres ≤ glucides,
  saturés ≤ lipides, somme ≤ 100 g, énergie ≈ 4·P + 4·G + 9·L. Deux lectures stables
  d'affilée ouvrent un formulaire de vérification qui liste les corrections appliquées ;
  à l'envoi, une incohérence restante bloque (ou avertit, pour l'énergie) avant
  `POST /scan/manual`. Le backend refuse de son côté un tableau impossible (422).
- **Saisie manuelle** : bouton sur l'accueil et dans le scanner pour remplir le tableau
  soi-même (produit sans code-barres ni étiquette lisible), même formulaire, mêmes contrôles.
- **Feuille de résultat glissante** : repliée sur la note et le détail, tirée vers le haut
  elle révèle le repas conseillé (portion + complément) puis les alternatives.
- **Fiche produit** : depuis l'historique ou une alternative, note actuelle, repas conseillé
  et alternatives. Un appui sur la photo l'ouvre en plein écran, zoomable (pleine
  résolution Open Food Facts, repli sur la vignette).
- **Recommandations** : famille de produit, enseigne facultative, liste classée des meilleurs
  produits pour ton objectif.
- **Réglages** : URL de l'API, clé, test de connexion, version installée et vérification
  manuelle des mises à jour.
- **Mise à jour automatique** : au plus toutes les 6 h, l'app interroge
  `api.github.com/repos/<dépôt>/releases/latest`. Si le tag est plus récent que la version
  installée, un dialogue propose d'installer (« Plus tard » / « Ignorer cette version »).
  L'APK est téléchargé dans le cache privé, son empreinte SHA-256 comparée à celle publiée
  par GitHub, puis remis à l'installateur système, qui refuse tout APK signé avec une autre
  clé que l'app installée. Première fois : Android demande d'autoriser l'installation
  depuis NoPainNoScan.

Sécurité côté app : clé API en stockage privé (`allowBackup=false`), un seul
client HTTP partagé avec timeouts courts. L'API peut être appelée en `http://`,
mais les Réglages ne l'acceptent que vers une adresse privée (LAN) ; ailleurs,
`https://` est exigé. L'APK release est minifié (R8) et livré pour arm64 seulement.

---

## Flux Git

- `dev` : branche de travail, tout part de là (branche par défaut).
- `main` : stable. Alimentée uniquement par PR `dev → main` (workflow `guard-main`).
- Tag `v*` sur `main` → APK signé publié en GitHub Release si les secrets
  `ANDROID_KEYSTORE_BASE64`, `ANDROID_KEYSTORE_PASSWORD`, `ANDROID_KEY_ALIAS`,
  `ANDROID_KEY_PASSWORD` sont configurés ; sinon APK debug en artefact.

---

## Roadmap

- [ ] Journal des repas pour un vrai suivi kcal / protéines à la journée et à la semaine.
- [ ] Nombre de repas par jour dans le profil (3 pour l'instant).
- [ ] Catégorie facultative sur les produits saisis par OCR (pour qu'ils aient des alternatives).
- [ ] Écran historique complet (`GET /scans`, au-delà des 5 derniers).
- [ ] Migrations (Alembic) dès que le schéma bouge.
- [ ] Import complet Open Food Facts pour du 100 % offline dès le premier scan.

## Licence

MIT — voir [LICENSE](LICENSE).
