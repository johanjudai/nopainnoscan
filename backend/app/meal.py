"""
Suggestion de repas : quelle quantité du produit scanné, et quel complément pour
boucler un repas cohérent avec le profil (budget kcal et protéines par repas).
"""

from .body import estimate

MEALS_PER_DAY = 3

# Part du budget kcal du repas laissée aux féculents, selon l'objectif.
CARB_SHARE = {"cut": 0.35, "maintenance": 0.45, "bulk": 0.50}

# Compléments génériques (kcal, protéines pour 100 g), valeurs cuites.
COMPLEMENTS = {
    "chicken": ("Blanc de poulet", 110.0, 23.0),
    "white_fish": ("Poisson blanc", 90.0, 20.0),
    "eggs": ("Œufs", 145.0, 13.0),
    "skyr": ("Skyr nature", 60.0, 10.0),
    "potatoes": ("Pommes de terre vapeur", 85.0, 2.0),
    "rice": ("Riz cuit", 130.0, 2.7),
    "pasta": ("Pâtes cuites", 150.0, 5.5),
}
CARB_COMPLEMENT_BY_GOAL = {"cut": "potatoes", "maintenance": "rice", "bulk": "pasta"}
GREENS = "Légumes verts à volonté"

ROLE_BY_CATEGORY = {
    "Meat": "protein",
    "Processed meat": "protein",
    "Fish and seafood": "protein",
    "Eggs": "protein",
    "Cheese": "protein",
    "Milk and yogurt": "protein",
    "Potatoes": "carb",
    "Cereals": "carb",
    "Bread": "carb",
    "Breakfast cereals": "carb",
    "Legumes": "carb",
    "Vegetables": "veg",
    "Soups": "veg",
    "Fruits": "fruit",
    "Dried fruits": "treat",
    "Nuts": "fat",
    "Pizza pies and quiches": "mixed",
    "One-dish meals": "mixed",
    "Sandwiches": "mixed",
    "Fats": "fat",
    "Dressings and sauces": "fat",
    "Sweets": "treat",
    "Chocolate products": "treat",
    "Biscuits and cakes": "treat",
    "Pastries": "treat",
    "Ice cream": "treat",
    "Dairy desserts": "treat",
    "Salty and fatty products": "treat",
    "Appetizers": "treat",
    "Sweetened beverages": "drink",
    "Artificially sweetened beverages": "drink",
    "Unsweetened beverages": "drink",
    "Waters and flavored waters": "drink",
    "Teas and herbal teas and coffees": "drink",
    "Fruit juices": "drink",
    "Fruit nectars": "drink",
    "Plant-based milk substitutes": "drink",
    "Alcoholic beverages": "drink",
}


def classify(product) -> str:
    role = ROLE_BY_CATEGORY.get(product.category or "")
    if role:
        return role
    kcal = max(product.kcal_100g, 1.0)
    if product.protein_100g * 4 / kcal >= 0.30:
        return "protein"
    if product.carbs_100g * 4 / kcal >= 0.50:
        return "carb"
    if product.fat_100g * 9 / kcal >= 0.60:
        return "fat"
    return "mixed"


def suggest(product, profile) -> dict | None:
    """None sans profil : impossible de dimensionner un repas."""
    if profile is None:
        return None
    est = estimate(profile)
    goal = profile.goal
    meal_kcal = est["kcal_target"] / MEALS_PER_DAY
    meal_protein = est["protein_target_g"] / MEALS_PER_DAY
    role = classify(product)

    complement = None
    extras: list[str] = []
    note = ""

    if role == "carb":
        grams = _grams_for_kcal(product, meal_kcal * CARB_SHARE.get(goal, 0.45), cap=400)
        complement = _protein_complement(
            goal, meal_protein - _protein(product, grams), meal_kcal - _kcal(product, grams)
        )
        extras.append(GREENS)
        note = "Féculent : il cale le repas, la protéine vient du complément."
    elif role == "protein":
        grams = _grams_for_protein(product, meal_protein, cap=300, kcal_cap=meal_kcal * 0.7)
        key = CARB_COMPLEMENT_BY_GOAL.get(goal, "rice")
        complement = _complement(key, kcal_left=meal_kcal - _kcal(product, grams), cap=350)
        extras.append(GREENS)
        note = "Protéines : la portion couvre ta cible du repas, le féculent apporte l'énergie."
    elif role == "mixed":
        grams = _grams_for_kcal(product, meal_kcal, cap=450)
        gap = meal_protein - _protein(product, grams)
        if gap > 8:
            complement = _protein_complement(
                goal, gap, kcal_left=max(meal_kcal * 0.15, 80), prefer="skyr"
            )
        extras.append("Salade verte ou crudités")
        note = "Plat complet : la portion tient dans ton budget du repas."
    elif role == "veg":
        grams = 250
        complement = _protein_complement(
            goal, meal_protein, kcal_left=meal_kcal - _kcal(product, grams)
        )
        note = "Légume : à volonté, c'est le complément qui fait le repas."
    elif role == "fruit":
        grams = 150
        note = "Un fruit : en dessert ou en collation, pas un repas."
    elif role == "fat":
        grams = 15
        note = "Matière grasse : une cuillère à soupe suffit, à compter dans le repas."
    elif role == "drink":
        grams = 250
        note = "Boisson : un verre. Ne remplace pas un repas."
    else:  # treat
        grams = _grams_for_kcal(product, meal_kcal * 0.15, cap=50, step=5)
        note = (
            "Plaisir : une petite part en dessert, pas un repas."
            if goal != "cut"
            else "Plaisir : garde-le pour un jour sans déficit, ou une part symbolique."
        )

    portion_kcal = _kcal(product, grams)
    portion_protein = _protein(product, grams)
    total_kcal = portion_kcal + (complement["kcal"] if complement else 0)
    total_protein = portion_protein + (complement["protein_g"] if complement else 0)
    return {
        "role": role,
        "portion_g": grams,
        "portion_kcal": round(portion_kcal),
        "portion_protein_g": round(portion_protein, 1),
        "complement": complement,
        "extras": extras,
        "meal_kcal": round(total_kcal),
        "meal_protein_g": round(total_protein, 1),
        "meal_kcal_budget": round(meal_kcal),
        "meal_protein_target_g": round(meal_protein),
        "share_of_day_pct": round(total_kcal / est["kcal_target"] * 100),
        "daily_kcal_target": est["kcal_target"],
        "weekly_kcal_target": est["kcal_target"] * 7,
        "note": note,
    }


# ---------- helpers ----------


def _kcal(product, grams: float) -> float:
    return product.kcal_100g * grams / 100


def _protein(product, grams: float) -> float:
    return product.protein_100g * grams / 100


def _round(grams: float, step: int = 10) -> int:
    return max(step, int(round(grams / step) * step))


def _grams_for_kcal(product, kcal: float, cap: int, step: int = 10) -> int:
    per100 = max(product.kcal_100g, 1.0)
    return min(cap, _round(kcal / per100 * 100, step))


def _grams_for_protein(product, protein: float, cap: int, kcal_cap: float) -> int:
    if product.protein_100g <= 0:
        return _grams_for_kcal(product, kcal_cap, cap)
    grams = protein / product.protein_100g * 100
    grams = min(grams, kcal_cap / max(product.kcal_100g, 1.0) * 100)
    return min(cap, _round(grams))


def _complement(
    key: str, kcal_left: float, cap: int, protein_needed: float | None = None
) -> dict | None:
    name, kcal100, protein100 = COMPLEMENTS[key]
    if protein_needed is not None:
        grams = protein_needed / protein100 * 100
    else:
        grams = kcal_left / kcal100 * 100
    grams = min(grams, max(kcal_left, 0) / kcal100 * 100, cap)
    grams = _round(grams)
    if grams < 30:
        return None
    return {
        "name": name,
        "grams": grams,
        "kcal": round(kcal100 * grams / 100),
        "protein_g": round(protein100 * grams / 100, 1),
    }


def _protein_complement(
    goal: str, protein_needed: float, kcal_left: float, prefer: str | None = None
) -> dict | None:
    if protein_needed <= 5:
        return None
    key = prefer or ("white_fish" if goal == "cut" else "chicken")
    return _complement(key, kcal_left=kcal_left, cap=300, protein_needed=protein_needed)
