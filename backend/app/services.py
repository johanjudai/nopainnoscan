"""Logique métier partagée entre les routes : cache produit, alternatives, historique."""

import logging
from datetime import UTC, datetime, timedelta

from sqlalchemy.exc import IntegrityError
from sqlalchemy.orm import Session

from . import models, scoring
from .config import ALTERNATIVES_LIMIT, SEED_RETRY_MINUTES, SEED_TTL_DAYS, SEED_VERSION
from .off_client import fetch_product_detail, fetch_product_from_off, search_products
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


def ensure_image(db: Session, product: models.Product) -> None:
    """Rattrape la photo d'une fiche OFF importée avant la colonne image ; une seule tentative."""
    if product.image_url is not None or product.source != "off" or not product.barcode:
        return
    data, reached = fetch_product_detail(product.barcode)
    if not reached:
        return  # OFF injoignable : on réessaiera, ce n'est pas « pas de photo »
    # "" = déjà cherché, pas de photo : évite de retaper OFF à chaque consultation.
    product.image_url = (data or {}).get("image_url") or ""
    db.flush()


def _insert_product(db: Session, barcode: str, source: str, data: dict) -> models.Product:
    """Insère, ou récupère la ligne si un autre scan a inséré le même code-barres entre-temps."""
    stores = data.pop("stores", set())
    if source == "off" and data.get("image_url") is None:
        data["image_url"] = ""  # OFF consulté, pas de photo : ne pas re-chercher à la consultation
    product = models.Product(barcode=barcode, source=source, **data)
    try:
        with db.begin_nested():
            db.add(product)
            db.flush()
    except IntegrityError:
        product = db.query(models.Product).filter(models.Product.barcode == barcode).one()
    for store in stores:
        link_store(db, product.id, store)
    return product


def link_store(db: Session, product_id: int, store: str) -> None:
    """« Vu dans cette enseigne », idempotent et tolérant à un lien posé en parallèle."""
    if db.get(models.ProductStore, (product_id, store)):
        return
    try:
        with db.begin_nested():
            db.add(models.ProductStore(product_id=product_id, store=store))
            db.flush()
    except IntegrityError:
        pass


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
        link_store(db, product.id, store)  # le scan en magasin vaut « vu dans cette enseigne »


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
        age = datetime.now(UTC) - fetched
        if seed.version == SEED_VERSION and age < timedelta(days=SEED_TTL_DAYS):
            return
        if seed.version == SEED_FAILED and age < timedelta(minutes=SEED_RETRY_MINUTES):
            return  # dernier essai en échec : on laisse OFF respirer

    # Au mieux : un import raté ne doit jamais faire échouer le scan qui l'a déclenché.
    try:
        found = search_products(category, store)
        with db.begin_nested():
            if found is None:
                logger.warning("OFF injoignable pour %s / %s", category, store or "toutes")
                version = SEED_FAILED
            else:
                _import_products(db, found)
                version = SEED_VERSION
            db.merge(
                models.AlternativeSeed(
                    category=category, store=key, version=version, fetched_at=datetime.now(UTC)
                )
            )
    except Exception:
        logger.exception("Amorçage OFF impossible pour %s / %s", category, store or "toutes")


# Valeur de `version` marquant un dernier essai en échec (mémo court, cf. SEED_RETRY_MINUTES).
SEED_FAILED = 0


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
        stores = data.get("stores", set())
        existing = known.get(barcode)
        if existing:
            if not existing.image_url and data.get("image_url"):
                existing.image_url = data["image_url"]  # rattrape les fiches importées sans photo
            for store in stores:
                link_store(db, existing.id, store)
            continue
        known[barcode] = _insert_product(db, barcode=barcode, source="off", data=data)
    db.flush()


def find_alternatives(
    db: Session, product: models.Product, goal: str, store: str | None, current_score: float
) -> tuple[list[AlternativeOut], list[AlternativeOut]]:
    """(dans l'enseigne, ailleurs). Sans enseigne : (toutes, [])."""
    if not product.category:
        return [], []
    better = [
        AlternativeOut(product_id=p.id, name=p.name, image_url=p.image_url or None, **score)
        for p, score in _ranked(db, product.category, goal, exclude_id=product.id)
        if score["score"] > current_score
    ]
    return _split_by_store(db, product.category, store, better, ALTERNATIVES_LIMIT)


def recommend(
    db: Session, category: str, goal: str, store: str | None, limit: int
) -> tuple[list[RecommendationOut], list[RecommendationOut]]:
    """(dans l'enseigne, ailleurs), chaque groupe classé par note décroissante."""
    seed_alternatives(db, category, store)
    items = [
        RecommendationOut(
            product_id=p.id,
            name=p.name,
            image_url=p.image_url or None,
            kcal_100g=p.kcal_100g,
            protein_100g=p.protein_100g,
            **score,
        )
        for p, score in _ranked(db, category, goal)
    ]
    return _split_by_store(db, category, store, items, limit)


# ---------- helpers ----------


def _ranked(
    db: Session, category: str, goal: str, exclude_id: int | None = None
) -> list[tuple[models.Product, dict]]:
    """Produits de la famille avec leur score pour l'objectif, du meilleur au moins bon."""
    # Les saisies manuelles (OCR) ne servent jamais de référence : valeurs non vérifiées.
    query = db.query(models.Product).filter(
        models.Product.category == category, models.Product.source != "manual"
    )
    if exclude_id is not None:
        query = query.filter(models.Product.id != exclude_id)
    # Le cache reste petit (usage perso) : le scoring en Python sur la famille suffit.
    scored = [(p, _score_only(p, goal)) for p in query.all()]
    scored.sort(key=lambda item: item[1]["score"], reverse=True)
    return scored


def _split_by_store(db: Session, category: str, store: str | None, items: list, limit: int):
    """Sans enseigne : (tout, []). Avec : (vus dans l'enseigne, vus ailleurs seulement)."""
    if not store:
        return items[:limit], []
    rows = (
        db.query(models.ProductStore.product_id)
        .join(models.Product, models.Product.id == models.ProductStore.product_id)
        .filter(models.ProductStore.store == store, models.Product.category == category)
        .all()
    )
    in_store = {product_id for (product_id,) in rows}
    here = [i for i in items if i.product_id in in_store][:limit]
    elsewhere = [i for i in items if i.product_id not in in_store][:limit]
    return here, elsewhere


def _score_only(product: models.Product, goal: str) -> dict:
    result = scoring.compute_score(product, goal)
    return {"score": result["score"], "category": result["category"]}
