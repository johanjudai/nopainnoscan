"""
Scoring "sport / sèche", pas un Nutri-Score générique : bonus protéines et fibres,
malus sucre, gras saturés et densité calorique, pondérés selon la phase.
"""

from .models import Product
from .schemas import NutrientsIn

# Plus le multiplicateur est haut, plus le critère pèse dans la note.
GOAL_WEIGHTS = {
    "cut": {"kcal": 1.3, "sugar": 1.3, "protein": 1.2, "satfat": 1.1},
    "maintenance": {"kcal": 1.0, "sugar": 1.0, "protein": 1.0, "satfat": 1.0},
    "bulk": {"kcal": 0.5, "sugar": 0.8, "protein": 1.3, "satfat": 0.9},
}

THRESHOLDS = (("parfait", 70), ("pas_mal", 50), ("a_eviter", 30))


def compute_score(nutrients: NutrientsIn | Product, goal: str = "maintenance") -> dict:
    w = GOAL_WEIGHTS.get(goal, GOAL_WEIGHTS["maintenance"])

    kcal = max(nutrients.kcal_100g, 1)  # évite la division par zéro
    score = 50.0
    breakdown: dict[str, float] = {}

    # Part des kcal venant des protéines (4 kcal/g) : le critère n°1 en sèche.
    protein_ratio = min((nutrients.protein_100g * 4) / kcal, 1.0)
    protein_bonus = protein_ratio * 40 * w["protein"]
    score += protein_bonus
    breakdown["bonus_proteines"] = round(protein_bonus, 1)

    fiber_bonus = min(nutrients.fiber_100g * 2, 10)
    score += fiber_bonus
    breakdown["bonus_fibres"] = round(fiber_bonus, 1)

    sugar_penalty = min(nutrients.sugars_100g / 2, 25) * w["sugar"]
    score -= sugar_penalty
    breakdown["malus_sucre"] = round(-sugar_penalty, 1)

    satfat_penalty = min(nutrients.saturated_fat_100g * 2, 20) * w["satfat"]
    score -= satfat_penalty
    breakdown["malus_gras_satures"] = round(-satfat_penalty, 1)

    if kcal > 300:
        kcal_penalty = min((kcal - 300) / 15, 25) * w["kcal"]
        score -= kcal_penalty
        breakdown["malus_densite_calorique"] = round(-kcal_penalty, 1)

    score = max(0.0, min(100.0, score))

    category = "a_ne_pas_manger"
    for name, threshold in THRESHOLDS:
        if score >= threshold:
            category = name
            break

    return {"score": round(score, 1), "category": category, "breakdown": breakdown}
