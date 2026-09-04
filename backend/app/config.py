import os

DATABASE_URL = os.getenv(
    "DATABASE_URL",
    "postgresql+psycopg://nopainnoscan:change_me@localhost:5432/nopainnoscan",
)

OFF_API_URL = "https://world.openfoodfacts.org/api/v2"
OFF_TIMEOUT_S = 10.0

# Open Food Facts demande un User-Agent identifiant l'appli.
OFF_USER_AGENT = "NoPainNoScan/0.1 (https://github.com/johanjudai/nopainnoscan)"

# Nombre max d'alternatives renvoyées avec un score.
ALTERNATIVES_LIMIT = 3
# Amorçage du cache : produits importés d'OFF par (famille, enseigne), et fraîcheur de l'import.
SEED_PAGE_SIZE = 40
SEED_TTL_DAYS = 7
