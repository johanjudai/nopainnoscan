"""Logique métier partagée entre les routes : cache produit, alternatives, historique."""

import logging
from datetime import UTC, datetime, timedelta

from sqlalchemy.orm import Session

from . import models, scoring
from .config import ALTERNATIVES_LIMIT, SEED_TTL_DAYS
from .off_client import fetch_product_from_off, search_products
from .schemas import AlternativeOut, NutrientsIn, RecommendationOut

logger = logging.getLogger(__name__)


def get_or_fetch_product(db: Session, barcode: str) -> models.Product | None:
    product = db.query(models.Product).filter(models.Product.barcode == barcode).first()
    if product:
        return product

    off_data = fetch_product_from_off(barcode)
    if not off_data:
        return None
    return _insert_product(db, barcode=barcode, source="off", data=off_data)


def create_manual_product(db: Session, nutrients: NutrientsIn) -> models.Product:
    product = models.Product(barcode=None, source="manual", **nutrients.model_dump())
    db.add(product)
    db.flush()
    return product


def _insert_product(db: Session, barcode: str, source: str, data: dict) -> models.Product:
    stores = data.pop("stores", set())
    product = models.Product(barcode=barcode, source=source, **data)
    db.add(product)
    db.flush()
    for store in stores:
        db.merge(models.ProductStore(product_id=product.id, store=store))
    return product


def record_scan(
    db: Session, user: models.User, product: models.Product, store: str | None, result: dict
) -> None:
    db.add(
        models.Scan(
            user_id=user.id,
            product_id=product.id,
            store=store,
            score=result["score"],
            category=result["category"],
        )
    )
    if store:
        # Le scan en magasin vaut « vu dans cette enseigne » : upsert sans erreur si déjà connu.
        db.merge(models.ProductStore(product_id=product.id, store=store))


def seed_alternatives(db: Session, category: str | None, store: str | None) -> None:
    """Démarrage à froid : importe les produits OFF de la famille, au plus une fois par semaine."""
    if not category:
        return
    key = store or ""
    seed = db.get(models.AlternativeSeed, (category, key))
    if seed and seed.fetched_at:
        fetched = seed.fetched_at
        if fetched.tzinfo is None:  # SQLite renvoie des datetimes naïfs
            fetched = fetched.replace(tzinfo=UTC)
        if fetched > datetime.now(UTC) - timedelta(days=SEED_TTL_DAYS):
            return

    # Au mieux : un import raté ne doit jamais faire échouer le scan qui l'a déclenché.
    try:
        with db.begin_nested():
            _import_products(db, search_products(category, store))
            db.merge(
                models.AlternativeSeed(category=category, store=key, fetched_at=datetime.now(UTC))
            )
    except Exception:
        logger.exception("Amorçage OFF impossible pour %s / %s", category, store or "toutes")


def _import_products(db: Session, found: list[dict]) -> None:
    if not found:
        return
    known: dict[str, models.Product] = {
        p.barcode: p
        for p in db.query(models.Product)
        .filter(models.Product.barcode.in_([p["barcode"] for p in found]))
        .all()
    }
    for data in found:
        barcode = data.pop("barcode")
        existing = known.get(barcode)
        if existing:
            if not existing.image_url and data.get("image_url"):
                existing.image_url = data["image_url"]  # rattrape les fiches importées sans photo
            continue
        known[barcode] = _insert_product(db, barcode=barcode, source="off", data=data)
    db.flush()


def find_alternatives(
    db: Session, product: models.Product, goal: str, store: str | None, current_score: float
) -> tuple[list[AlternativeOut], str]:
    """Même famille, mieux notés pour ce profil ; dans l'enseigne si possible, sinon partout."""
    if not product.category:
        return [], "any"
    if store:
        found = _better_in_category(db, product, goal, store, current_score)
        if found:
            return found, "store"
    return _better_in_category(db, product, goal, None, current_score), "any"


def _better_in_category(
    db: Session, product: models.Product, goal: str, store: str | None, current_score: float
) -> list[AlternativeOut]:
    query = db.query(models.Product).filter(
        models.Product.category == product.category, models.Product.id != product.id
    )
    if store:
        query = query.join(
            models.ProductStore, models.ProductStore.product_id == models.Product.id
        ).filter(models.ProductStore.store == store)

    # Le cache reste petit (usage perso) : le scoring en Python sur la famille suffit.
    scored = (
        AlternativeOut(product_id=p.id, name=p.name, image_url=p.image_url, **_score_only(p, goal))
        for p in query.all()
    )
    better = [alt for alt in scored if alt.score > current_score]
    better.sort(key=lambda alt: alt.score, reverse=True)
    return better[:ALTERNATIVES_LIMIT]


def _score_only(product: models.Product, goal: str) -> dict:
    result = scoring.compute_score(product, goal)
    return {"score": result["score"], "category": result["category"]}


def recommend(
    db: Session, category: str, goal: str, store: str | None, limit: int
) -> tuple[list[RecommendationOut], str]:
    """Meilleurs produits d'une famille pour ce profil ; enseigne si possible, sinon partout."""
    seed_alternatives(db, category, store)
    if store:
        items = _ranked_in_category(db, category, goal, store, limit)
        if items:
            return items, "store"
    return _ranked_in_category(db, category, goal, None, limit), "any"


def _ranked_in_category(
    db: Session, category: str, goal: str, store: str | None, limit: int
) -> list[RecommendationOut]:
    query = db.query(models.Product).filter(models.Product.category == category)
    if store:
        query = query.join(
            models.ProductStore, models.ProductStore.product_id == models.Product.id
        ).filter(models.ProductStore.store == store)
    items = [
        RecommendationOut(
            product_id=p.id,
            name=p.name,
            kcal_100g=p.kcal_100g,
            protein_100g=p.protein_100g,
            image_url=p.image_url,
            **_score_only(p, goal),
        )
        for p in query.all()
    ]
    items.sort(key=lambda item: item.score, reverse=True)
    return items[:limit]
