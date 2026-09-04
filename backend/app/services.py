"""Logique métier partagée entre les routes : cache produit, alternatives, historique."""

from sqlalchemy.orm import Session

from . import models, scoring
from .config import ALTERNATIVES_LIMIT
from .off_client import fetch_product_from_off
from .schemas import AlternativeOut, NutrientsIn


def get_or_fetch_product(db: Session, barcode: str) -> models.Product | None:
    product = db.query(models.Product).filter(models.Product.barcode == barcode).first()
    if product:
        return product

    off_data = fetch_product_from_off(barcode)
    if not off_data:
        return None
    product = models.Product(barcode=barcode, source="off", **off_data)
    db.add(product)
    db.flush()
    return product


def create_manual_product(db: Session, nutrients: NutrientsIn) -> models.Product:
    product = models.Product(barcode=None, source="manual", **nutrients.model_dump())
    db.add(product)
    db.flush()
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


def find_alternatives(
    db: Session, product: models.Product, goal: str, store: str | None, current_score: float
) -> list[AlternativeOut]:
    """Produits de la même famille, mieux notés pour ce profil, vus dans l'enseigne si donnée."""
    if not product.category:
        return []

    query = db.query(models.Product).filter(
        models.Product.category == product.category, models.Product.id != product.id
    )
    if store:
        query = query.join(
            models.ProductStore, models.ProductStore.product_id == models.Product.id
        ).filter(models.ProductStore.store == store)

    # Le cache reste petit (usage perso) : le scoring en Python sur la famille suffit.
    scored = (
        AlternativeOut(product_id=p.id, name=p.name, **_score_only(p, goal)) for p in query.all()
    )
    better = [alt for alt in scored if alt.score > current_score]
    better.sort(key=lambda alt: alt.score, reverse=True)
    return better[:ALTERNATIVES_LIMIT]


def _score_only(product: models.Product, goal: str) -> dict:
    result = scoring.compute_score(product, goal)
    return {"score": result["score"], "category": result["category"]}
