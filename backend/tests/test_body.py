import pytest

from app import body
from app.schemas import ProfileIn

BASE = dict(
    sex="male",
    age=30,
    height_cm=180,
    weight_kg=78,
    neck_cm=38,
    waist_cm=82,
    activity="moderate",
    goal="cut",
)


def test_us_navy_male_reference_case():
    assert body.body_fat_us_navy("male", 180, 38, 82) == 13.7


def test_us_navy_female_needs_hips():
    assert body.body_fat_us_navy("female", 165, 32, 70) is None
    pct = body.body_fat_us_navy("female", 165, 32, 70, 95)
    assert 15 < pct < 30


def test_us_navy_missing_or_incoherent_measurements():
    assert body.body_fat_us_navy("male", 180, None, 82) is None
    assert body.body_fat_us_navy("male", 180, 40, 38) is None


def test_bmr_tdee_and_targets():
    bmr = body.bmr_mifflin("male", 78, 180, 30)
    assert bmr == 1760
    assert body.tdee(bmr, "moderate") == 2728
    assert body.kcal_target(2728, "cut") == 2180
    assert body.kcal_target(2728, "bulk") == 3000
    assert body.protein_target(78, "cut") == 172


def test_estimate_uses_auto_targets_without_messages():
    est = body.estimate(ProfileIn(**BASE))
    assert est["body_fat_pct"] == 13.7
    assert est["lean_mass_kg"] == 67.3
    assert est["kcal_target"] == est["kcal_target_auto"] == 2180
    assert est["protein_target_g"] == 172
    assert est["messages"] == []


@pytest.mark.parametrize(
    "kcal, expected_levels",
    [
        (1650, {"warning"}),  # sous le BMR (1760)
        (2000, {"warning"}),  # -27 % : déficit trop agressif
        (2400, set()),  # -12 % : raisonnable
        (2900, {"info"}),  # au-dessus de la dépense : pas de sèche
    ],
)
def test_kcal_messages_in_cut(kcal, expected_levels):
    est = body.estimate(ProfileIn(**BASE, daily_kcal_target=kcal))
    assert {m["level"] for m in est["messages"]} == expected_levels


def test_protein_messages():
    assert body.assess_protein(70, 78)[0]["level"] == "warning"
    assert body.assess_protein(110, 78)[0]["level"] == "info"
    assert body.assess_protein(172, 78) == []
    assert body.assess_protein(260, 78)[0]["level"] == "warning"


def test_bulk_messages():
    est = body.estimate(ProfileIn(**{**BASE, "goal": "bulk"}, daily_kcal_target=3600))
    assert [m["level"] for m in est["messages"]] == ["warning"]
    est = body.estimate(ProfileIn(**{**BASE, "goal": "bulk"}, daily_kcal_target=2500))
    assert [m["level"] for m in est["messages"]] == ["info"]
