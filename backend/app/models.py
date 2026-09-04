from sqlalchemy import Column, DateTime, Float, ForeignKey, Integer, String, func
from sqlalchemy.orm import relationship

from .database import Base


class User(Base):
    """Un utilisateur = une clé API. Seul le hash SHA-256 de la clé est stocké."""

    __tablename__ = "users"

    id = Column(Integer, primary_key=True)
    name = Column(String(64), unique=True, nullable=False)
    api_key_hash = Column(String(64), unique=True, index=True, nullable=False)
    created_at = Column(DateTime(timezone=True), server_default=func.now())

    profile = relationship("Profile", back_populates="user", uselist=False)


class Profile(Base):
    """Mesures et objectif par utilisateur ; masse grasse et cibles sont dérivées (body.py)."""

    __tablename__ = "profiles"

    id = Column(Integer, primary_key=True)
    user_id = Column(
        Integer, ForeignKey("users.id", ondelete="CASCADE"), unique=True, nullable=False
    )
    sex = Column(String(8), nullable=False, default="male")  # male | female
    age = Column(Integer, nullable=False)
    height_cm = Column(Float, nullable=False)
    weight_kg = Column(Float, nullable=False)
    neck_cm = Column(Float, nullable=True)
    waist_cm = Column(Float, nullable=True)
    hips_cm = Column(Float, nullable=True)
    activity = Column(String(16), nullable=False, default="moderate")
    goal = Column(String(16), nullable=False, default="maintenance")  # cut | maintenance | bulk
    target_body_fat_pct = Column(Float, nullable=True)
    # Surcharges manuelles ; None = cible calculée.
    daily_kcal_target = Column(Float, nullable=True)
    daily_protein_target_g = Column(Float, nullable=True)
    updated_at = Column(DateTime(timezone=True), server_default=func.now(), onupdate=func.now())

    user = relationship("User", back_populates="profile")


class Product(Base):
    """Cache partagé des produits : Open Food Facts n'est interrogé qu'au premier scan."""

    __tablename__ = "products"

    id = Column(Integer, primary_key=True)
    barcode = Column(String(32), unique=True, index=True, nullable=True)
    name = Column(String(255), nullable=False)
    # Famille OFF (pnns_groups_2), sert à chercher des alternatives comparables.
    category = Column(String(128), index=True, nullable=True)

    kcal_100g = Column(Float, nullable=False)
    protein_100g = Column(Float, nullable=False, default=0)
    carbs_100g = Column(Float, nullable=False, default=0)
    sugars_100g = Column(Float, nullable=False, default=0)
    fat_100g = Column(Float, nullable=False, default=0)
    saturated_fat_100g = Column(Float, nullable=False, default=0)
    fiber_100g = Column(Float, nullable=False, default=0)
    salt_100g = Column(Float, nullable=False, default=0)

    source = Column(String(16), default="off")  # off | manual
    created_at = Column(DateTime(timezone=True), server_default=func.now())


class ProductStore(Base):
    """Un produit a été vu dans une enseigne : alimenté par les scans faits « en magasin »."""

    __tablename__ = "product_stores"

    product_id = Column(Integer, ForeignKey("products.id", ondelete="CASCADE"), primary_key=True)
    store = Column(String(32), primary_key=True)
    last_seen_at = Column(DateTime(timezone=True), server_default=func.now(), onupdate=func.now())


class AlternativeSeed(Base):
    """Mémo des imports OFF par (famille, enseigne) pour ne pas re-chercher à chaque scan."""

    __tablename__ = "alternative_seeds"

    category = Column(String(128), primary_key=True)
    store = Column(String(32), primary_key=True, default="")  # "" = toutes enseignes
    fetched_at = Column(DateTime(timezone=True), server_default=func.now(), onupdate=func.now())


class Scan(Base):
    """Historique par utilisateur, score figé au moment du scan."""

    __tablename__ = "scans"

    id = Column(Integer, primary_key=True)
    user_id = Column(
        Integer, ForeignKey("users.id", ondelete="CASCADE"), index=True, nullable=False
    )
    product_id = Column(Integer, ForeignKey("products.id", ondelete="CASCADE"), nullable=False)
    store = Column(String(32), nullable=True)
    score = Column(Float, nullable=False)
    category = Column(String(24), nullable=False)
    created_at = Column(DateTime(timezone=True), server_default=func.now(), index=True)

    product = relationship("Product")
