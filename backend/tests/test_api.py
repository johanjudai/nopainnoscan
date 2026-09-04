from app import off_client, services

CHICKEN = {"name": "Blanc de poulet", "category": "Meat", "kcal_100g": 110, "protein_100g": 23}
SAUSAGE = {
    "name": "Saucisse",
    "category": "Meat",
    "kcal_100g": 320,
    "protein_100g": 12,
    "fat_100g": 28,
    "saturated_fat_100g": 11,
}


def test_health_is_public(client):
    assert client.get("/health").json() == {"status": "ok"}


def test_protected_routes_require_api_key(client):
    assert client.get("/me").status_code == 401
    assert client.get("/me", headers={"X-Api-Key": "wrong"}).status_code == 401


def test_me(client, headers):
    assert client.get("/me", headers=headers).json()["name"] == "pierre"


def test_profile_is_per_user(client, headers, db):
    from app.auth import hash_key
    from app.models import User

    db.add(User(name="copine", api_key_hash=hash_key("test-key-copine")))
    db.commit()
    other = {"X-Api-Key": "test-key-copine"}

    assert client.get("/profile", headers=headers).status_code == 404
    body = {
        "sex": "male",
        "age": 30,
        "weight_kg": 78,
        "height_cm": 180,
        "goal": "cut",
        "neck_cm": 38,
        "waist_cm": 82,
        "activity": "moderate",
    }
    resp = client.put("/profile", json=body, headers=headers)
    assert resp.status_code == 200
    assert resp.json()["estimate"]["body_fat_pct"] == 13.7
    assert resp.json()["estimate"]["kcal_target"] == 2180
    body2 = {"sex": "female", "age": 28, "weight_kg": 60, "height_cm": 165, "goal": "maintenance"}
    assert client.put("/profile", json=body2, headers=other).status_code == 200

    assert client.get("/profile", headers=headers).json()["goal"] == "cut"
    mine = client.get("/profile", headers=other).json()
    assert mine["weight_kg"] == 60
    assert mine["estimate"]["body_fat_pct"] is None  # pas de mensurations


def test_profile_rejects_absurd_values(client, headers):
    resp = client.put(
        "/profile",
        json={"sex": "male", "age": 30, "weight_kg": -5, "height_cm": 180},
        headers=headers,
    )
    assert resp.status_code == 422


def test_estimate_endpoint_does_not_save(client, headers):
    body = {
        "sex": "male",
        "age": 30,
        "weight_kg": 78,
        "height_cm": 180,
        "goal": "cut",
        "daily_kcal_target": 1500,
    }
    resp = client.post("/profile/estimate", json=body, headers=headers)
    assert resp.status_code == 200
    assert resp.json()["messages"][0]["level"] == "warning"
    assert client.get("/profile", headers=headers).status_code == 404


def test_manual_scan_records_history_and_store(client, headers):
    resp = client.post("/scan/manual", params={"store": "lidl"}, json=SAUSAGE, headers=headers)
    assert resp.status_code == 200
    body = resp.json()
    assert body["store"] == "lidl"
    assert body["alternatives"] == []

    history = client.get("/scans", headers=headers).json()
    assert len(history) == 1
    assert history[0]["product_name"] == "Saucisse"
    assert history[0]["store"] == "lidl"


def test_unknown_store_is_rejected(client, headers):
    resp = client.post("/scan/manual", params={"store": "casino"}, json=SAUSAGE, headers=headers)
    assert resp.status_code == 422


def test_alternatives_only_from_same_store_and_better(client, headers, off_product):
    off_product("Blanc de poulet", "Meat", 110, 23, stores=["lidl"])

    # Chez Leclerc : le poulet n'a été vu que chez Lidl -> groupe « ailleurs », pas en tête.
    at_leclerc = client.post(
        "/scan/manual", params={"store": "leclerc"}, json=SAUSAGE, headers=headers
    ).json()
    assert at_leclerc["alternatives"] == []
    assert [alt["name"] for alt in at_leclerc["alternatives_elsewhere"]] == ["Blanc de poulet"]

    # Chez Lidl : le poulet est proposé en tête, mieux noté.
    at_lidl = client.post(
        "/scan/manual", params={"store": "lidl"}, json=SAUSAGE, headers=headers
    ).json()
    assert [alt["name"] for alt in at_lidl["alternatives"]] == ["Blanc de poulet"]
    assert at_lidl["alternatives"][0]["score"] > at_lidl["score"]
    assert at_lidl["alternatives_elsewhere"] == []

    # Sans enseigne : toutes les alternatives connues, quelle que soit l'enseigne.
    anywhere = client.post("/scan/manual", json=SAUSAGE, headers=headers).json()
    assert [alt["name"] for alt in anywhere["alternatives"]] == ["Blanc de poulet"]
    assert anywhere["alternatives_elsewhere"] == []


def test_cold_start_seeds_cache_from_off(client, headers, monkeypatch, db):
    from app.models import AlternativeSeed, Product, ProductStore

    calls = []

    def fake_search(category, store):
        calls.append((category, store))
        return [
            {
                "barcode": "111",
                "name": "Poulet rôti",
                "category": "Meat",
                "kcal_100g": 150,
                "protein_100g": 25,
                "carbs_100g": 0,
                "sugars_100g": 0,
                "fat_100g": 5,
                "saturated_fat_100g": 1.5,
                "fiber_100g": 0,
                "salt_100g": 1,
                "stores": {"leclerc"},
            },
        ]

    monkeypatch.setattr(services, "search_products", fake_search)

    resp = client.post(
        "/scan/manual", params={"store": "leclerc"}, json=SAUSAGE, headers=headers
    ).json()
    assert calls == [("Meat", "leclerc")]
    assert [alt["name"] for alt in resp["alternatives"]] == ["Poulet rôti"]
    assert resp["alternatives_elsewhere"] == []
    assert db.query(Product).filter(Product.barcode == "111").one().source == "off"
    assert db.query(ProductStore).filter_by(store="leclerc").count() == 2  # saucisse + poulet
    assert db.query(AlternativeSeed).count() == 1

    # Deuxième scan dans la même famille / enseigne : pas de nouvelle recherche OFF.
    client.post("/scan/manual", params={"store": "leclerc"}, json=SAUSAGE, headers=headers)
    assert len(calls) == 1


def test_product_detail_does_not_record_scan(client, headers):
    created = client.post("/scan/manual", json=SAUSAGE, headers=headers).json()
    detail = client.get(
        f"/products/{created['product_id']}", params={"store": "lidl"}, headers=headers
    )
    assert detail.status_code == 200
    assert detail.json()["product_name"] == "Saucisse"
    assert detail.json()["store"] == "lidl"
    assert len(client.get("/scans", headers=headers).json()) == 1
    assert client.get("/products/9999", headers=headers).status_code == 404


def test_barcode_uses_cache_after_first_fetch(client, headers, monkeypatch):
    calls = []

    def fake_fetch(barcode):
        calls.append(barcode)
        return {**CHICKEN, "sugars_100g": 0}

    monkeypatch.setattr(services, "fetch_product_from_off", fake_fetch)

    first = client.get("/scan/barcode/3017620422003", headers=headers).json()
    second = client.get("/scan/barcode/3017620422003", headers=headers).json()
    assert first["product_id"] == second["product_id"]
    assert first["category"] == "parfait"
    assert calls == ["3017620422003"]


def test_barcode_not_found(client, headers):
    assert client.get("/scan/barcode/00000000", headers=headers).status_code == 404


def test_barcode_format_validated(client, headers):
    assert client.get("/scan/barcode/abc", headers=headers).status_code == 422


class FakeResp:
    status_code = 200

    def __init__(self, payload):
        self.payload = payload

    def json(self):
        return self.payload


def test_off_client_maps_fields_and_stores(monkeypatch):
    payload = {
        "status": 1,
        "product": {
            "product_name": "Skyr",
            "pnns_groups_2": "Dairy desserts",
            "stores_tags": ["E.Leclerc", "Carrefour Market", "Magasins U"],
            "image_front_small_url": "https://images.openfoodfacts.org/x/front_fr.200.jpg",
            "nutriments": {"energy-kcal_100g": 63, "proteins_100g": "10.5", "fat_100g": None},
        },
    }
    monkeypatch.setattr(off_client._client, "get", lambda url, params=None, **kw: FakeResp(payload))
    data = off_client.fetch_product_from_off("123")
    assert data["name"] == "Skyr"
    assert data["image_url"].endswith("front_fr.200.jpg")
    assert data["category"] == "Dairy desserts"
    assert data["protein_100g"] == 10.5
    assert data["fat_100g"] == 0
    assert data["stores"] == {"leclerc", "carrefour"}


def test_off_client_skips_products_without_kcal(monkeypatch):
    payload = {"status": 1, "product": {"product_name": "Sans kcal", "nutriments": {}}}
    monkeypatch.setattr(off_client._client, "get", lambda url, params=None, **kw: FakeResp(payload))
    assert off_client.fetch_product_from_off("123") is None


def test_off_search_builds_store_query(monkeypatch):
    seen = {}

    def fake_get(url, params=None, **kw):
        seen.update(params)
        return FakeResp(
            {
                "products": [
                    {
                        "code": "42",
                        "product_name": "Pizza",
                        "pnns_groups_2": "Pizza pies and quiches",
                        "stores_tags": [],
                        "nutriments": {"energy-kcal_100g": 230},
                    },
                    {"code": "43", "product_name": "Sans kcal", "nutriments": {}},
                ]
            }
        )

    monkeypatch.setattr(off_client._client, "get", fake_get)
    results = off_client.search_products("Pizza pies and quiches", "leclerc")
    assert seen["pnns_groups_2_tags"] == "pizza-pies-and-quiches"
    assert seen["stores_tags"] == "e-leclerc"
    assert [r["barcode"] for r in results] == ["42"]
    assert results[0]["stores"] == {"leclerc"}


def test_seed_backfills_missing_image(client, headers, monkeypatch, db):
    from app.models import Product

    def fake_search(category, store):
        return [
            {
                "barcode": "777",
                "name": "Poulet",
                "category": "Meat",
                "kcal_100g": 110,
                "protein_100g": 23,
                "carbs_100g": 0,
                "sugars_100g": 0,
                "fat_100g": 1,
                "saturated_fat_100g": 0.3,
                "fiber_100g": 0,
                "salt_100g": 0.2,
                "stores": set(),
                "image_url": "https://img/777.jpg",
            },
        ]

    db.add(Product(barcode="777", name="Poulet", category="Meat", kcal_100g=110, protein_100g=23))
    db.commit()
    monkeypatch.setattr(services, "search_products", fake_search)
    resp = client.post("/scan/manual", json=SAUSAGE, headers=headers).json()
    assert db.query(Product).filter_by(barcode="777").one().image_url == "https://img/777.jpg"
    assert resp["alternatives"][0]["image_url"] == "https://img/777.jpg"


def test_seeding_failure_never_breaks_the_scan(client, headers, monkeypatch):
    def boom(category, store):
        raise RuntimeError("OFF cassé")

    monkeypatch.setattr(services, "search_products", boom)
    resp = client.post("/scan/manual", params={"store": "thiriet"}, json=SAUSAGE, headers=headers)
    assert resp.status_code == 200
    assert resp.json()["alternatives"] == []


def test_seeding_with_duplicate_or_bad_rows_is_tolerated(client, headers, monkeypatch, db):
    from app.models import Product

    row = {
        "name": "Poulet",
        "category": "Meat",
        "kcal_100g": 110,
        "protein_100g": 23,
        "carbs_100g": 0,
        "sugars_100g": 0,
        "fat_100g": 1,
        "saturated_fat_100g": 0.3,
        "fiber_100g": 0,
        "salt_100g": 0.2,
        "stores": set(),
        "image_url": None,
    }
    monkeypatch.setattr(
        services,
        "search_products",
        lambda c, s: [{**row, "barcode": "888"}, {**row, "barcode": "888"}],
    )
    resp = client.post("/scan/manual", json=SAUSAGE, headers=headers)
    assert resp.status_code == 200
    assert db.query(Product).filter_by(barcode="888").count() == 1


def test_off_parse_tolerates_non_numeric_values(monkeypatch):
    payload = {
        "status": 1,
        "product": {
            "product_name": "Eau",
            "nutriments": {
                "energy-kcal_100g": "0",
                "proteins_100g": "traces",
                "salt_100g": "<0,01",
                "fat_100g": "0,5",
            },
        },
    }
    monkeypatch.setattr(off_client._client, "get", lambda url, params=None, **kw: FakeResp(payload))
    data = off_client.fetch_product_from_off("1")
    assert data["kcal_100g"] == 0
    assert data["protein_100g"] == 0
    assert data["salt_100g"] == 0.01
    assert data["fat_100g"] == 0.5


def test_off_failure_is_memoized_briefly(client, headers, monkeypatch, db):
    from app.models import AlternativeSeed

    calls = []

    def flaky(category, store):
        calls.append(1)
        return None if len(calls) == 1 else []

    monkeypatch.setattr(services, "search_products", flaky)
    client.post("/scan/manual", params={"store": "lidl"}, json=SAUSAGE, headers=headers)
    seed = db.query(AlternativeSeed).one()
    assert seed.version == services.SEED_FAILED  # échec mémorisé, brièvement
    client.post("/scan/manual", params={"store": "lidl"}, json=SAUSAGE, headers=headers)
    assert len(calls) == 1  # pas de nouvel appel OFF dans le quart d'heure

    from datetime import UTC, datetime, timedelta

    seed.fetched_at = datetime.now(UTC) - timedelta(minutes=20)
    db.commit()
    client.post("/scan/manual", params={"store": "lidl"}, json=SAUSAGE, headers=headers)
    assert len(calls) == 2
    assert db.query(AlternativeSeed).one().version == services.SEED_VERSION


def test_old_seed_version_is_refreshed(client, headers, monkeypatch, db):
    from datetime import UTC, datetime

    from app.models import AlternativeSeed

    db.add(AlternativeSeed(category="Meat", store="lidl", version=1, fetched_at=datetime.now(UTC)))
    db.commit()
    calls = []
    monkeypatch.setattr(services, "search_products", lambda c, s: calls.append(1) or [])
    client.post("/scan/manual", params={"store": "lidl"}, json=SAUSAGE, headers=headers)
    assert calls == [1]
    assert db.get(AlternativeSeed, ("Meat", "lidl")).version == services.SEED_VERSION


def test_product_view_backfills_image_once(client, headers, monkeypatch, off_product, db):
    from app.models import Product

    skyr = off_product("Skyr", "Milk and yogurt", 60, 10)
    calls = []

    def detail(barcode):
        calls.append(barcode)
        return {"image_url": "https://img/skyr.jpg"}, True

    monkeypatch.setattr(services, "fetch_product_detail", detail)
    assert (
        client.get(f"/products/{skyr.id}", headers=headers).json()["image_url"]
        == "https://img/skyr.jpg"
    )
    client.get(f"/products/{skyr.id}", headers=headers)
    assert calls == [skyr.barcode]

    # Sans photo côté OFF : une seule tentative aussi, et image_url reste null pour l'app.
    lait = off_product("Lait", "Milk and yogurt", 46, 3.3)
    monkeypatch.setattr(
        services, "fetch_product_detail", lambda b: (calls.append(b) or {"image_url": None}, True)
    )
    assert client.get(f"/products/{lait.id}", headers=headers).json()["image_url"] is None
    client.get(f"/products/{lait.id}", headers=headers)
    assert calls == [skyr.barcode, lait.barcode]

    # OFF injoignable : rien n'est marqué, on réessaiera.
    beurre = off_product("Beurre", "Fats", 740, 0.7)
    monkeypatch.setattr(services, "fetch_product_detail", lambda b: (None, False))
    client.get(f"/products/{beurre.id}", headers=headers)
    assert db.get(Product, beurre.id).image_url is None


def test_manual_products_never_serve_as_reference(client, headers):
    """Une saisie OCR fantaisiste ne doit pas devenir la meilleure alternative de tous."""
    client.post(
        "/scan/manual", json={**CHICKEN, "protein_100g": 100, "kcal_100g": 50}, headers=headers
    )
    resp = client.post("/scan/manual", json=SAUSAGE, headers=headers).json()
    assert resp["alternatives"] == []
    reco = client.get("/recommendations", params={"category": "Meat"}, headers=headers).json()
    assert reco["items"] == []


def test_manual_values_are_bounded(client, headers):
    for bad in (
        {"kcal_100g": "inf"},
        {"kcal_100g": 5000},
        {"protein_100g": 150},
        {"category": "Yolo"},
    ):
        resp = client.post("/scan/manual", json={**CHICKEN, **bad}, headers=headers)
        assert resp.status_code == 422, bad


def test_manual_values_must_be_physically_possible(client, headers):
    """Virgule perdue à l'OCR (8,5 → 85) : le serveur est la dernière barrière."""
    base = {**CHICKEN, "carbs_100g": 24, "fat_100g": 9.5}
    for bad in (
        {"sugars_100g": 85},
        {"saturated_fat_100g": 32},
        {"protein_100g": 60, "carbs_100g": 60},
    ):
        resp = client.post("/scan/manual", json={**base, **bad}, headers=headers)
        assert resp.status_code == 422, bad
    # Arrondis d'étiquette tolérés : sucres 4,1 pour glucides 4.
    ok = {**CHICKEN, "carbs_100g": 4, "sugars_100g": 4.1}
    assert client.post("/scan/manual", json=ok, headers=headers).status_code == 200


def test_unicode_digits_are_not_a_barcode(client, headers):
    assert client.get("/scan/barcode/٣٣٣٣٣٣٣٣", headers=headers).status_code == 422


def test_health_checks_the_database(client):
    assert client.get("/health").json() == {"status": "ok"}


def test_off_parse_rejects_unknown_category_bad_image_and_garbage(monkeypatch):
    payload = {
        "status": 1,
        "product": {
            "product_name": "X",
            "pnns_groups_2": "unknown",
            "stores_tags": ["Lidl", 42],
            "image_front_small_url": "http://evil.example/x.jpg",
            "nutriments": {"energy-kcal_100g": 100, "proteins_100g": "inf"},
        },
    }
    monkeypatch.setattr(off_client._client, "get", lambda url, params=None, **kw: FakeResp(payload))
    data = off_client.fetch_product_from_off("1")
    assert data["category"] is None
    assert data["image_url"] is None
    assert data["protein_100g"] == 0
    assert data["stores"] == {"lidl"}

    monkeypatch.setattr(
        off_client._client, "get", lambda url, params=None, **kw: FakeResp([1, 2, 3])
    )
    assert off_client.fetch_product_detail("1") == (None, False)
    assert off_client.search_products("Meat", None) is None
    monkeypatch.setattr(
        off_client._client, "get", lambda url, params=None, **kw: FakeResp({"status": 1})
    )
    assert off_client.fetch_product_detail("1") == (None, True)
