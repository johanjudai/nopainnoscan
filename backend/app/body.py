"""
Estimations corporelles et cibles : masse grasse (US Navy), métabolisme (Mifflin-St Jeor),
dépense (facteur d'activité), cibles kcal / protéines selon l'objectif, et messages
d'information ou d'alerte quand l'utilisateur modifie les cibles à la main.
"""

from math import log10

ACTIVITY_FACTORS = {
    "sedentary": 1.2,
    "light": 1.375,
    "moderate": 1.55,
    "active": 1.725,
    "athlete": 1.9,
}

# Ajustement kcal par rapport à la dépense totale, et protéines en g/kg de poids.
GOAL_KCAL_FACTOR = {"cut": 0.80, "maintenance": 1.0, "bulk": 1.10}
GOAL_PROTEIN_G_PER_KG = {"cut": 2.2, "maintenance": 1.8, "bulk": 2.0}


def body_fat_us_navy(
    sex: str,
    height_cm: float,
    neck_cm: float | None,
    waist_cm: float | None,
    hips_cm: float | None = None,
) -> float | None:
    """None si les mensurations manquent ou sont incohérentes (formule non définie)."""
    if not neck_cm or not waist_cm:
        return None
    if sex == "female":
        if not hips_cm or waist_cm + hips_cm <= neck_cm:
            return None
        denom = 1.29579 - 0.35004 * log10(waist_cm + hips_cm - neck_cm) + 0.22100 * log10(height_cm)
    else:
        if waist_cm <= neck_cm:
            return None
        denom = 1.0324 - 0.19077 * log10(waist_cm - neck_cm) + 0.15456 * log10(height_cm)
    if denom <= 0:
        return None
    pct = 495 / denom - 450
    return round(min(max(pct, 2.0), 60.0), 1)


def bmr_mifflin(sex: str, weight_kg: float, height_cm: float, age: int) -> int:
    base = 10 * weight_kg + 6.25 * height_cm - 5 * age
    return round(base + (5 if sex == "male" else -161))


def tdee(bmr: int, activity: str) -> int:
    return round(bmr * ACTIVITY_FACTORS.get(activity, ACTIVITY_FACTORS["moderate"]))


def kcal_target(tdee_kcal: int, goal: str) -> int:
    return round(tdee_kcal * GOAL_KCAL_FACTOR.get(goal, 1.0) / 10) * 10


def protein_target(weight_kg: float, goal: str) -> int:
    return round(weight_kg * GOAL_PROTEIN_G_PER_KG.get(goal, 1.8))


def assess_kcal(kcal: float, bmr: int, tdee_kcal: int, goal: str) -> list[dict]:
    """Messages sur une cible kcal saisie à la main. Vide si elle est raisonnable."""
    msgs: list[dict] = []
    delta_pct = round((kcal - tdee_kcal) / tdee_kcal * 100)

    if kcal < bmr:
        msgs.append(
            _warn("kcal", f"Sous ton métabolisme de base ({bmr} kcal) : perte de muscle probable.")
        )
    elif delta_pct < -25:
        msgs.append(_warn("kcal", f"Déficit de {-delta_pct} % : très agressif, vise 15 à 20 %."))

    if goal == "cut" and delta_pct >= 0:
        msgs.append(_info("kcal", "Au niveau de ta dépense ou au-dessus : pas de sèche possible."))
    elif goal == "bulk" and delta_pct > 20:
        msgs.append(
            _warn("kcal", f"Surplus de {delta_pct} % : prise de gras rapide, 5 à 10 % suffisent.")
        )
    elif goal == "bulk" and delta_pct < 0:
        msgs.append(_info("kcal", "Sous ta dépense : pas de prise de masse possible."))
    elif goal == "maintenance" and abs(delta_pct) > 10:
        msgs.append(_info("kcal", f"À {delta_pct:+d} % de ta dépense : ce n'est plus du maintien."))
    return msgs


def assess_protein(protein_g: float, weight_kg: float) -> list[dict]:
    per_kg = protein_g / weight_kg
    if per_kg < 1.2:
        return [
            _warn(
                "protein",
                f"{per_kg:.1f} g/kg : insuffisant pour préserver le muscle (minimum 1,6).",
            )
        ]
    if per_kg < 1.6:
        return [_info("protein", f"{per_kg:.1f} g/kg : un peu bas, vise 1,6 à 2,2 g/kg.")]
    if per_kg > 3.0:
        return [
            _warn("protein", f"{per_kg:.1f} g/kg : au-delà de 3 g/kg, aucun bénéfice démontré.")
        ]
    return []


def _info(field: str, text: str) -> dict:
    return {"level": "info", "field": field, "text": text}


def _warn(field: str, text: str) -> dict:
    return {"level": "warning", "field": field, "text": text}


def estimate(p) -> dict:
    """Tout le calcul dérivé d'un profil (ProfileIn ou modèle Profile, mêmes attributs)."""
    fat = body_fat_us_navy(p.sex, p.height_cm, p.neck_cm, p.waist_cm, p.hips_cm)
    bmr = bmr_mifflin(p.sex, p.weight_kg, p.height_cm, p.age)
    total = tdee(bmr, p.activity)
    kcal_auto = kcal_target(total, p.goal)
    protein_auto = protein_target(p.weight_kg, p.goal)
    kcal = p.daily_kcal_target if p.daily_kcal_target is not None else kcal_auto
    protein = p.daily_protein_target_g if p.daily_protein_target_g is not None else protein_auto

    messages: list[dict] = []
    if p.daily_kcal_target is not None:
        messages += assess_kcal(kcal, bmr, total, p.goal)
    if p.daily_protein_target_g is not None:
        messages += assess_protein(protein, p.weight_kg)

    return {
        "body_fat_pct": fat,
        "lean_mass_kg": round(p.weight_kg * (1 - fat / 100), 1) if fat is not None else None,
        "bmr_kcal": bmr,
        "tdee_kcal": total,
        "kcal_target_auto": kcal_auto,
        "protein_target_auto": protein_auto,
        "kcal_target": round(kcal),
        "protein_target_g": round(protein),
        "messages": messages,
    }
