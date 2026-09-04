from datetime import datetime
from typing import Literal

from pydantic import BaseModel, ConfigDict, Field

Goal = Literal["cut", "maintenance", "bulk"]
Category = Literal["parfait", "pas_mal", "a_eviter", "a_ne_pas_manger"]
Store = Literal["leclerc", "lidl", "grand_frais", "auchan", "carrefour", "thiriet"]


class UserOut(BaseModel):
    model_config = ConfigDict(from_attributes=True)

    id: int
    name: str


class StoreOut(BaseModel):
    slug: Store
    label: str


Sex = Literal["male", "female"]
Activity = Literal["sedentary", "light", "moderate", "active", "athlete"]


class ProfileIn(BaseModel):
    sex: Sex = "male"
    age: int = Field(ge=14, le=100)
    height_cm: float = Field(gt=100, lt=250)
    weight_kg: float = Field(gt=30, lt=300)
    neck_cm: float | None = Field(default=None, gt=20, lt=70)
    waist_cm: float | None = Field(default=None, gt=40, lt=200)
    hips_cm: float | None = Field(default=None, gt=50, lt=200)
    activity: Activity = "moderate"
    goal: Goal = "maintenance"
    target_body_fat_pct: float | None = Field(default=None, ge=3, le=50)
    daily_kcal_target: float | None = Field(default=None, gt=0, lt=10000)
    daily_protein_target_g: float | None = Field(default=None, ge=0, lt=1000)


class Message(BaseModel):
    level: Literal["info", "warning"]
    field: Literal["kcal", "protein"]
    text: str


class Estimate(BaseModel):
    """Valeurs dérivées du profil : jamais saisies, toujours recalculées."""

    body_fat_pct: float | None
    lean_mass_kg: float | None
    bmr_kcal: int
    tdee_kcal: int
    kcal_target_auto: int
    protein_target_auto: int
    kcal_target: int
    protein_target_g: int
    messages: list[Message]


class ProfileOut(ProfileIn):
    model_config = ConfigDict(from_attributes=True)

    id: int
    user_id: int
    estimate: Estimate


class NutrientsIn(BaseModel):
    """Valeurs pour 100 g, telles que lues sur l'étiquette (OCR ou saisie manuelle)."""

    name: str = Field(min_length=1, max_length=255)
    category: str | None = Field(default=None, max_length=128)
    kcal_100g: float = Field(ge=0)
    protein_100g: float = Field(default=0, ge=0)
    carbs_100g: float = Field(default=0, ge=0)
    sugars_100g: float = Field(default=0, ge=0)
    fat_100g: float = Field(default=0, ge=0)
    saturated_fat_100g: float = Field(default=0, ge=0)
    fiber_100g: float = Field(default=0, ge=0)
    salt_100g: float = Field(default=0, ge=0)


class AlternativeOut(BaseModel):
    product_id: int
    name: str
    score: float
    category: Category


class ComplementOut(BaseModel):
    name: str
    grams: int
    kcal: int
    protein_g: float


class MealOut(BaseModel):
    """Quantité conseillée pour un repas et complément pour le boucler, selon le profil."""

    role: Literal["protein", "carb", "veg", "fruit", "mixed", "fat", "treat", "drink"]
    portion_g: int
    portion_kcal: int
    portion_protein_g: float
    complement: ComplementOut | None
    extras: list[str]
    meal_kcal: int
    meal_protein_g: float
    meal_kcal_budget: int
    meal_protein_target_g: int
    share_of_day_pct: int
    daily_kcal_target: int
    weekly_kcal_target: int
    note: str


class ScoreOut(BaseModel):
    product_id: int
    product_name: str
    category: Category
    score: float  # 0-100
    breakdown: dict[str, float]
    source: str
    store: Store | None = None
    alternatives: list[AlternativeOut] = []
    # "store" : vues dans l'enseigne demandée ; "any" : repli sur toutes les enseignes.
    alternatives_scope: Literal["store", "any"] = "any"
    meal: MealOut | None = None  # None sans profil


class ScanOut(BaseModel):
    model_config = ConfigDict(from_attributes=True)

    id: int
    product_id: int
    product_name: str
    store: Store | None
    score: float
    category: Category
    created_at: datetime


class CategoryOut(BaseModel):
    slug: str  # valeur `pnns_groups_2` d'Open Food Facts
    label: str


class RecommendationOut(BaseModel):
    product_id: int
    name: str
    score: float
    category: Category
    kcal_100g: float
    protein_100g: float


class RecommendationsOut(BaseModel):
    category: str
    store: Store | None
    # "store" : produits vus dans l'enseigne ; "any" : repli sur toutes les enseignes.
    scope: Literal["store", "any"]
    items: list[RecommendationOut]
