import httpx

from .config import OFF_API_URL, OFF_TIMEOUT_S, OFF_USER_AGENT, SEED_PAGE_SIZE
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


def _parse(product: dict) -> dict | None:
    """Dict prêt pour `Product(**data)` + clé `stores` (à retirer avant insertion)."""
    nutriments = product.get("nutriments") or {}
    if nutriments.get("energy-kcal_100g") in (None, ""):
        return None  # sans kcal, aucun score possible
    return {
        "name": (product.get("product_name") or "Produit inconnu")[:255],
        "category": product.get("pnns_groups_2") or None,
        "image_url": (product.get("image_front_small_url") or None),
        **{col: float(nutriments.get(key) or 0) for col, key in _FIELDS.items()},
        "stores": match_store_tags(product.get("stores_tags")),
    }


def _get(path: str, params: dict | None = None) -> dict | None:
    try:
        resp = _client.get(f"{OFF_API_URL}/{path}", params=params)
    except httpx.HTTPError:
        return None
    if resp.status_code != 200:
        return None
    try:
        return resp.json()
    except ValueError:
        return None


def fetch_product_from_off(barcode: str) -> dict | None:
    """Appelé uniquement si le code-barres n'est pas déjà en cache local."""
    data = _get(f"product/{barcode}.json", {"fields": _SEARCH_FIELDS})
    if not data or data.get("status") != 1:
        return None
    return _parse(data["product"])


def search_products(category: str, store: str | None) -> list[dict]:
    """Produits populaires de la même famille (et de l'enseigne si donnée) : `barcode` inclus."""
    params = {
        "pnns_groups_2_tags": slugify(category),
        "fields": _SEARCH_FIELDS,
        "page_size": SEED_PAGE_SIZE,
        "sort_by": "unique_scans_n",
    }
    if store:
        params["stores_tags"] = STORE_OFF_TAGS[store][0]
    data = _get("search", params)
    results = []
    for raw in (data or {}).get("products", []):
        parsed = _parse(raw)
        if parsed and raw.get("code"):
            parsed["barcode"] = str(raw["code"])
            if store:
                parsed["stores"].add(store)  # trouvé en filtrant sur l'enseigne
            results.append(parsed)
    return results
