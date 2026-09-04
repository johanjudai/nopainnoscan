import httpx

from .config import OFF_API_URL, OFF_TIMEOUT_S, OFF_USER_AGENT

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

# Client partagé : pool de connexions réutilisé entre les requêtes.
_client = httpx.Client(timeout=OFF_TIMEOUT_S, headers={"User-Agent": OFF_USER_AGENT})


def fetch_product_from_off(barcode: str) -> dict | None:
    """Appelé uniquement si le code-barres n'est pas déjà en cache local."""
    try:
        resp = _client.get(f"{OFF_API_URL}/{barcode}.json")
    except httpx.HTTPError:
        return None
    if resp.status_code != 200:
        return None

    data = resp.json()
    if data.get("status") != 1:
        return None

    product = data["product"]
    nutriments = product.get("nutriments", {})
    return {
        "name": (product.get("product_name") or "Produit inconnu")[:255],
        "category": (product.get("pnns_groups_2") or None),
        **{col: float(nutriments.get(key) or 0) for col, key in _FIELDS.items()},
    }
