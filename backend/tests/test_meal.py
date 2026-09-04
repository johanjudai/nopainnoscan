from types import SimpleNamespace

from app import meal
from app.schemas import ProfileIn

# Profil de référence : 2180 kcal / 172 g de protéines par jour en sèche (cf. test_body).
PROFILE = ProfileIn(
    sex="male",
    age=30,
    height_cm=180,
    weight_kg=78,
    neck_cm=38,
    waist_cm=82,
    activity="moderate",
    goal="cut",
)


def product(**kw):
    base = dict(category=None, kcal_100g=0, protein_100g=0, carbs_100g=0, fat_100g=0)
    return SimpleNamespace(**{**base, **kw})


def test_potatoes_get_a_protein_complement():
    potatoes = product(category="Potatoes", kcal_100g=85, protein_100g=2, carbs_100g=18)
    m = meal.suggest(potatoes, PROFILE)
    assert m["role"] == "carb"
    assert m["portion_g"] == 300  # 727 kcal × 35 % / 85 kcal
    assert m["complement"]["name"] == "Poisson blanc"  # sèche -> poisson blanc
    assert m["complement"]["grams"] == 260  # (57 - 6) g de protéines / 20 g pour 100 g
    assert "Légumes verts à volonté" in m["extras"]
    assert m["meal_protein_g"] >= 50
    assert m["meal_kcal"] <= m["meal_kcal_budget"]
    assert m["weekly_kcal_target"] == 2180 * 7


def test_chicken_gets_a_carb_complement_by_goal():
    chicken = product(category="Meat", kcal_100g=110, protein_100g=23)
    cut = meal.suggest(chicken, PROFILE)
    assert cut["role"] == "protein"
    assert cut["portion_g"] == 250  # 57 g de protéines / 23 g pour 100 g
    assert cut["complement"]["name"] == "Pommes de terre vapeur"

    bulk = meal.suggest(chicken, PROFILE.model_copy(update={"goal": "bulk"}))
    assert bulk["complement"]["name"] == "Pâtes cuites"
    assert bulk["meal_kcal_budget"] > cut["meal_kcal_budget"]


def test_pizza_fits_meal_budget_and_gets_protein_top_up():
    pizza = product(
        category="Pizza pies and quiches", kcal_100g=240, protein_100g=10, carbs_100g=28, fat_100g=9
    )
    m = meal.suggest(pizza, PROFILE)
    assert m["role"] == "mixed"
    assert m["portion_g"] == 300  # 727 / 240 -> 303 -> 300
    assert m["complement"]["name"] == "Skyr nature"
    assert m["share_of_day_pct"] <= 40


def test_treat_is_a_small_portion_without_complement():
    spread = product(category="Sweets", kcal_100g=539, protein_100g=6)
    m = meal.suggest(spread, PROFILE)
    assert m["role"] == "treat"
    assert m["portion_g"] <= 25
    assert m["complement"] is None


def test_role_falls_back_to_macros_without_category():
    assert meal.classify(product(kcal_100g=100, protein_100g=20)) == "protein"
    assert meal.classify(product(kcal_100g=350, carbs_100g=75)) == "carb"
    assert meal.classify(product(kcal_100g=900, fat_100g=100)) == "fat"
    assert (
        meal.classify(product(kcal_100g=200, protein_100g=5, carbs_100g=15, fat_100g=10)) == "mixed"
    )


def test_no_profile_no_suggestion():
    assert meal.suggest(product(kcal_100g=100), None) is None


def test_scan_response_includes_meal_when_profile_exists(client, headers):
    body = {
        "sex": "male",
        "age": 30,
        "weight_kg": 78,
        "height_cm": 180,
        "goal": "cut",
        "activity": "moderate",
    }
    client.put("/profile", json=body, headers=headers)
    resp = client.post(
        "/scan/manual",
        json={
            "name": "Riz",
            "category": "Cereals",
            "kcal_100g": 130,
            "protein_100g": 2.7,
            "carbs_100g": 28,
        },
        headers=headers,
    )
    m = resp.json()["meal"]
    assert m["role"] == "carb"
    assert m["complement"]["protein_g"] > 0
