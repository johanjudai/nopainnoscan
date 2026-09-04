from datetime import datetime
from typing import Literal

from pydantic import BaseModel, ConfigDict, Field

Goal = Literal["cut", "maintenance", "bulk"]
Category = Literal["parfait", "pas_mal", "a_eviter", "a_ne_pas_manger"]
Store = Literal["leclerc", "lidl", "grand_frais", "auchan", "carrefour"]

STORE_LABELS: dict[str, str] = {
    "leclerc": "E.Leclerc",
    "lidl": "Lidl",
    "grand_frais": "Grand Frais",
    "auchan": "Auchan",
    "carrefour": "Carrefour",
}


class UserOut(BaseModel):
    model_config = ConfigDict(from_attributes=True)

    id: int
    name: str


class StoreOut(BaseModel):
    slug: Store
    label: str


class ProfileIn(BaseModel):
    weight_kg: float = Field(gt=0, lt=500)
    height_cm: float = Field(gt=0, lt=300)
    current_body_fat_pct: float | None = Field(default=None, ge=0, le=100)
    target_body_fat_pct: float | None = Field(default=None, ge=0, le=100)
    goal: Goal = "maintenance"
    daily_kcal_target: float | None = Field(default=None, gt=0)
    daily_protein_target_g: float | None = Field(default=None, ge=0)


class ProfileOut(ProfileIn):
    model_config = ConfigDict(from_attributes=True)

    id: int
    user_id: int


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


class ScoreOut(BaseModel):
    product_id: int
    product_name: str
    category: Category
    score: float  # 0-100
    breakdown: dict[str, float]
    source: str
    store: Store | None = None
    alternatives: list[AlternativeOut] = []


class ScanOut(BaseModel):
    model_config = ConfigDict(from_attributes=True)

    id: int
    product_id: int
    product_name: str
    store: Store | None
    score: float
    category: Category
    created_at: datetime
