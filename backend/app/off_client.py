import math
import threading

import httpx

from .categories import CATEGORY_LABELS
from .config import (
    OFF_API_URL,
    OFF_IMAGE_HOSTS,
    OFF_SEARCH_TIMEOUT_S,
    OFF_TIMEOUT_S,
    OFF_USER_AGENT,
    SEED_PAGE_SIZE,
)
from .stores import STORE_OFF_TAGS, match_store_tags, slugify

# Champ OFF -> colonne Product. `or 0` car OFF renvoie parfois null ou "".
_FIELDS = {
    "kcal_100g": "energy-kcal_100g",
    "protein_100g": "proteins_100g",
    "carbs_100g": "carbohydrates_100g",
    "sugars_100g": "sugars_100g",
    "fat_100g": "fat_100g",
    "saturated_fat_100g": "saturated-fat_100g",
    "fiber_100g": "fiber_100g",
    "salt_100g": "salt_100g",
}
_SEARCH_FIELDS = "code,product_name,pnns_groups_2,stores_tags,nutriments,image_front_small_url"

# Client partagé : pool de connexions réutilisé entre les requêtes.
_client = httpx.Client(timeout=OFF_TIMEOUT_S, headers={"User-Agent": OFF_USER_AGENT})
# Une seule recherche OFF à la fois : deux téléphones ne doivent pas doubler la cadence.
_search_lock = threading.Lock()


def _num(value) -> float | None:
    """OFF renvoie parfois des chaînes ("0,5", "traces", "<1") : jamais de ValueError ici."""
    if value in (None, ""):
        return None
    try:
        number = float(str(value).replace(",", ".").lstrip("<~ "))
    except ValueError:
        return None
    return number if math.isfinite(number) and 0 <= number <= 1000 else None


def _parse(product) -> dict | None:
    """Dict prêt pour `Product(**data)` + clé `stores` (à retirer avant insertion)."""
    if not isinstance(product, dict):
        return None
    nutriments = product.get("nutriments")
    if not isinstance(nutriments, dict):
        return None
    kcal = _num(nutriments.get("energy-kcal_100g"))
    if kcal is None:
        return None  # sans kcal, aucun score possible
    values = {col: _num(nutriments.get(key)) or 0.0 for col, key in _FIELDS.items()}
    values["kcal_100g"] = kcal
    category = product.get("pnns_groups_2")
    image = product.get("image_front_small_url")
    return {
        "name": str(product.get("product_name") or "Produit inconnu")[:255],
        # Seules les familles connues servent aux alternatives ("unknown" et le reste -> None).
        "category": category if category in CATEGORY_LABELS else None,
        "image_url": image
        if isinstance(image, str) and image.startswith(OFF_IMAGE_HOSTS) and len(image) <= 512
        else None,
        **values,
        "stores": match_store_tags(product.get("stores_tags")),
    }


def _get(path: str, params: dict | None = None, timeout: float = OFF_TIMEOUT_S) -> dict | None:
    """Objet JSON, ou None si OFF est injoignable, en erreur, ou renvoie autre chose qu'un objet."""
    try:
        resp = _client.get(f"{OFF_API_URL}/{path}", params=params, timeout=timeout)
    except httpx.HTTPError:
        return None
    if resp.status_code != 200:
        return None
    try:
        data = resp.json()
    except ValueError:
        return None
    return data if isinstance(data, dict) else None


def fetch_product_detail(barcode: str) -> tuple[dict | None, bool]:
    """(fiche parsée ou None, OFF a répondu) : distingue « inconnu » de « injoignable »."""
    data = _get(f"product/{barcode}.json", {"fields": _SEARCH_FIELDS})
    if data is None:
        return None, False
    if data.get("status") != 1:
        return None, True
    return _parse(data.get("product")), True


def fetch_product_from_off(barcode: str) -> dict | None:
    """Appelé uniquement si le code-barres n'est pas déjà en cache local."""
    return fetch_product_detail(barcode)[0]


def search_products(category: str, store: str | None) -> list[dict] | None:
    """Produits populaires de la même famille (et de l'enseigne si donnée) : `barcode` inclus.

    None = OFF injoignable ou en erreur (à distinguer d'une famille vide).
    """
    params = {
        "pnns_groups_2_tags": slugify(category),
        "fields": _SEARCH_FIELDS,
        "page_size": SEED_PAGE_SIZE,
        "sort_by": "unique_scans_n",
    }
    if store:
        params["stores_tags"] = STORE_OFF_TAGS[store][0]
    with _search_lock:
        data = _get("search", params, timeout=OFF_SEARCH_TIMEOUT_S)
    if data is None:
        return None
    results = []
    products = data.get("products")
    for raw in products if isinstance(products, list) else []:
        parsed = _parse(raw)
        code = raw.get("code") if isinstance(raw, dict) else None
        if parsed and code:
            parsed["barcode"] = str(code)[:32]
            if store:
                parsed["stores"].add(store)  # trouvé en filtrant sur l'enseigne
            results.append(parsed)
    return results
