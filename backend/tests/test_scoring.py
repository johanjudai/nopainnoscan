from app.schemas import NutrientsIn
from app.scoring import compute_score

CHICKEN = NutrientsIn(
    name="Blanc de poulet", kcal_100g=110, protein_100g=23, fat_100g=1.5, saturated_fat_100g=0.5
)
SPREAD = NutrientsIn(
    name="Pâte à tartiner",
    kcal_100g=539,
    protein_100g=6.3,
    carbs_100g=57.5,
    sugars_100g=56.3,
    fat_100g=30.9,
    saturated_fat_100g=10.6,
)
GRANOLA = NutrientsIn(
    name="Granola",
    kcal_100g=380,
    protein_100g=5,
    carbs_100g=60,
    sugars_100g=30,
    fat_100g=12,
    saturated_fat_100g=3,
    fiber_100g=4,
)


def test_lean_protein_is_parfait():
    result = compute_score(CHICKEN)
    assert result["category"] == "parfait"
    assert result["score"] >= 70


def test_sugary_spread_is_a_ne_pas_manger():
    result = compute_score(SPREAD)
    assert result["category"] == "a_ne_pas_manger"
    assert result["score"] == 0


def test_cut_is_stricter_than_bulk_on_sugar():
    assert compute_score(GRANOLA, "cut")["score"] < compute_score(GRANOLA, "bulk")["score"]


def test_unknown_goal_falls_back_to_maintenance():
    assert compute_score(GRANOLA, "yolo") == compute_score(GRANOLA, "maintenance")


def test_score_is_bounded_and_breakdown_present():
    result = compute_score(NutrientsIn(name="Vide", kcal_100g=0))
    assert 0 <= result["score"] <= 100
    assert {"bonus_proteines", "bonus_fibres", "malus_sucre", "malus_gras_satures"} <= set(
        result["breakdown"]
    )
