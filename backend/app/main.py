from fastapi import Depends, FastAPI, HTTPException, Query
from sqlalchemy.orm import Session, joinedload

from . import body, meal, models, schemas, scoring, services
from .auth import current_user
from .categories import CATEGORY_LABELS
from .database import engine, get_db
from .stores import STORE_LABELS

models.Base.metadata.create_all(bind=engine)

app = FastAPI(title="NoPainNoScan API", docs_url="/docs", redoc_url=None)

# Routes sync volontairement : SQLAlchemy est bloquant, FastAPI les exécute dans un threadpool
# sans geler la boucle d'événements.


def _goal(user: models.User) -> str:
    return user.profile.goal if user.profile else "maintenance"


def _score_response(
    db: Session, user: models.User, product: models.Product, store: str | None, record: bool
) -> schemas.ScoreOut:
    goal = _goal(user)
    result = scoring.compute_score(product, goal)
    if record:
        services.record_scan(db, user, product, store, result)
    services.seed_alternatives(db, product.category, store)
    alternatives, scope = services.find_alternatives(db, product, goal, store, result["score"])
    db.commit()
    return schemas.ScoreOut(
        product_id=product.id,
        product_name=product.name,
        source=product.source,
        store=store,
        alternatives=alternatives,
        alternatives_scope=scope,
        meal=meal.suggest(product, user.profile),
        **result,
    )


@app.get("/health")
def health():
    return {"status": "ok"}


@app.get("/me", response_model=schemas.UserOut)
def me(user: models.User = Depends(current_user)):
    return user


@app.get("/stores", response_model=list[schemas.StoreOut])
def stores():
    return [{"slug": slug, "label": label} for slug, label in STORE_LABELS.items()]


@app.get("/categories", response_model=list[schemas.CategoryOut])
def categories():
    return [{"slug": slug, "label": label} for slug, label in CATEGORY_LABELS.items()]


@app.get("/recommendations", response_model=schemas.RecommendationsOut)
def recommendations(
    category: str = Query(min_length=1, max_length=128),
    store: schemas.Store | None = Query(default=None),
    limit: int = Query(default=20, ge=1, le=50),
    user: models.User = Depends(current_user),
    db: Session = Depends(get_db),
):
    """Les meilleurs produits d'une famille pour ce profil, dans l'enseigne si possible."""
    if category not in CATEGORY_LABELS:
        raise HTTPException(422, "Famille inconnue : voir GET /categories.")
    items, scope = services.recommend(db, category, _goal(user), store, limit)
    db.commit()
    return schemas.RecommendationsOut(category=category, store=store, scope=scope, items=items)


# ---------- Profil (propre à chaque utilisateur) ----------


def _profile_out(profile: models.Profile) -> schemas.ProfileOut:
    return schemas.ProfileOut.model_validate(
        {
            **{c.key: getattr(profile, c.key) for c in models.Profile.__table__.columns},
            "estimate": body.estimate(profile),
        }
    )


@app.get("/profile", response_model=schemas.ProfileOut)
def get_profile(user: models.User = Depends(current_user)):
    if not user.profile:
        raise HTTPException(404, "Aucun profil configuré. PUT /profile pour en créer un.")
    return _profile_out(user.profile)


@app.put("/profile", response_model=schemas.ProfileOut)
def upsert_profile(
    data: schemas.ProfileIn,
    user: models.User = Depends(current_user),
    db: Session = Depends(get_db),
):
    profile = user.profile
    if profile:
        for field, value in data.model_dump().items():
            setattr(profile, field, value)
    else:
        profile = models.Profile(user_id=user.id, **data.model_dump())
        db.add(profile)
    db.commit()
    db.refresh(profile)
    return _profile_out(profile)


@app.post("/profile/estimate", response_model=schemas.Estimate)
def estimate_profile(data: schemas.ProfileIn, user: models.User = Depends(current_user)):
    """Aperçu en direct pendant la saisie : rien n'est enregistré."""
    return body.estimate(data)


# ---------- Scan ----------


@app.get("/scan/barcode/{barcode}", response_model=schemas.ScoreOut)
def scan_barcode(
    barcode: str,
    store: schemas.Store | None = Query(default=None),
    user: models.User = Depends(current_user),
    db: Session = Depends(get_db),
):
    if not barcode.isdigit() or not 8 <= len(barcode) <= 14:
        raise HTTPException(422, "Code-barres invalide (EAN-8 / EAN-13 / GTIN-14 attendu).")

    product = services.get_or_fetch_product(db, barcode)
    if not product:
        raise HTTPException(404, "Produit introuvable (cache local + Open Food Facts).")
    return _score_response(db, user, product, store, record=True)


@app.post("/scan/manual", response_model=schemas.ScoreOut)
def scan_manual(
    nutrients: schemas.NutrientsIn,
    store: schemas.Store | None = Query(default=None),
    user: models.User = Depends(current_user),
    db: Session = Depends(get_db),
):
    """Tableau nutritionnel lu directement (OCR ou saisie) : le produit rejoint le cache partagé."""
    product = services.create_manual_product(db, nutrients)
    return _score_response(db, user, product, store, record=True)


@app.get("/products/{product_id}", response_model=schemas.ScoreOut)
def product_detail(
    product_id: int,
    store: schemas.Store | None = Query(default=None),
    user: models.User = Depends(current_user),
    db: Session = Depends(get_db),
):
    """Fiche d'un produit connu (historique) : score actuel + alternatives, sans scan."""
    product = db.get(models.Product, product_id)
    if not product:
        raise HTTPException(404, "Produit inconnu.")
    return _score_response(db, user, product, store, record=False)


@app.get("/scans", response_model=list[schemas.ScanOut])
def scan_history(
    limit: int = Query(default=50, ge=1, le=200),
    user: models.User = Depends(current_user),
    db: Session = Depends(get_db),
):
    scans = (
        db.query(models.Scan)
        .options(joinedload(models.Scan.product))
        .filter(models.Scan.user_id == user.id)
        .order_by(models.Scan.created_at.desc(), models.Scan.id.desc())
        .limit(limit)
        .all()
    )
    return [
        schemas.ScanOut(
            id=s.id,
            product_id=s.product_id,
            product_name=s.product.name,
            store=s.store,
            score=s.score,
            category=s.category,
            created_at=s.created_at,
        )
        for s in scans
    ]
