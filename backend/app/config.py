import os
from urllib.parse import quote_plus


def _database_url() -> str:
    """DATABASE_URL explicite, sinon construite depuis POSTGRES_* (mot de passe encodé)."""
    url = os.getenv("DATABASE_URL")
    if url:
        return url
    password = os.getenv("POSTGRES_PASSWORD")
    if not password:
        raise RuntimeError("Définir DATABASE_URL ou POSTGRES_PASSWORD (voir .env.example).")
    user = os.getenv("POSTGRES_USER", "nopainnoscan")
    host = os.getenv("POSTGRES_HOST", "nopainnoscan-db")
    db = os.getenv("POSTGRES_DB", "nopainnoscan")
    return f"postgresql+psycopg://{quote_plus(user)}:{quote_plus(password)}@{host}:5432/{db}"


DATABASE_URL = _database_url()

# Swagger seulement si demandé : la surface d'API n'a pas à être listée sur tout le LAN.
DEBUG = os.getenv("DEBUG", "0") == "1"

OFF_API_URL = "https://world.openfoodfacts.org/api/v2"
OFF_IMAGE_HOSTS = ("https://images.openfoodfacts.org/", "https://static.openfoodfacts.org/")
OFF_TIMEOUT_S = 6.0
OFF_SEARCH_TIMEOUT_S = 8.0  # la recherche reste dans le chemin du scan : on borne serré

# Open Food Facts demande un User-Agent identifiant l'appli.
OFF_USER_AGENT = "NoPainNoScan/1.0 (https://github.com/johanjudai/nopainnoscan)"

# Nombre max d'alternatives renvoyées avec un score.
ALTERNATIVES_LIMIT = 3
# Amorçage du cache : produits importés d'OFF par (famille, enseigne), et fraîcheur de l'import.
SEED_PAGE_SIZE = 40
SEED_TTL_DAYS = 7
# Après un échec OFF (panne, 429), on attend avant de réessayer : OFF limite la recherche à 10/min.
SEED_RETRY_MINUTES = 15
# Incrémenter pour invalider tous les amorçages (ex. nouvelle donnée importée comme la photo).
SEED_VERSION = 2
