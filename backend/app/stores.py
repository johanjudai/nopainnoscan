"""Enseignes connues et correspondance avec les tags `stores_tags` d'Open Food Facts."""

import re

STORE_LABELS: dict[str, str] = {
    "leclerc": "E.Leclerc",
    "lidl": "Lidl",
    "grand_frais": "Grand Frais",
    "auchan": "Auchan",
    "carrefour": "Carrefour",
    "thiriet": "Thiriet",
}

# Premier alias = tag utilisé pour la recherche OFF ; les autres servent au rapprochement.
STORE_OFF_TAGS: dict[str, tuple[str, ...]] = {
    "leclerc": ("e-leclerc", "leclerc"),
    "lidl": ("lidl",),
    "grand_frais": ("grand-frais",),
    "auchan": ("auchan",),
    "carrefour": (
        "carrefour",
        "carrefour-market",
        "carrefour-city",
        "carrefour-contact",
        "carrefour-express",
    ),
    "thiriet": ("thiriet",),
}


def slugify(value: str) -> str:
    value = value.lower().replace("é", "e").replace("è", "e").replace("ê", "e").replace("à", "a")
    return re.sub(r"[^a-z0-9]+", "-", value).strip("-")


def match_store_tags(tags: list[str] | None) -> set[str]:
    """`['E.Leclerc', 'Carrefour Market']` -> `{'leclerc', 'carrefour'}`."""
    slugs = {slugify(t) for t in tags or []}
    return {store for store, aliases in STORE_OFF_TAGS.items() if slugs & set(aliases)}
