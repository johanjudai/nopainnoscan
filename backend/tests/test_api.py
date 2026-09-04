from app import off_client, services

CHICKEN = {"name": "Blanc de poulet", "category": "Meat", "kcal_100g": 110, "protein_100g": 23}
SAUSAGE = {
    "name": "Saucisse",
    "category": "Meat",
    "kcal_100g": 320,
    "protein_100g": 12,
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


def test_alternatives_only_from_same_store_and_better(client, headers):
    client.post("/scan/manual", params={"store": "lidl"}, json=CHICKEN, headers=headers)

    # Chez Leclerc : le poulet n'a été vu que chez Lidl, donc repli « vu ailleurs ».
    at_leclerc = client.post(
        "/scan/manual", params={"store": "leclerc"}, json=SAUSAGE, headers=headers
    ).json()
    assert at_leclerc["alternatives_scope"] == "any"

    # Chez Lidl : le poulet est proposé, mieux noté.
    at_lidl = client.post(
        "/scan/manual", params={"store": "lidl"}, json=SAUSAGE, headers=headers
    ).json()
    names = [alt["name"] for alt in at_lidl["alternatives"]]
    assert names == ["Blanc de poulet"]
    assert at_lidl["alternatives_scope"] == "store"
    assert at_lidl["alternatives"][0]["score"] > at_lidl["score"]

    # Sans enseigne : toutes les alternatives connues, quelle que soit l'enseigne.
    anywhere = client.post("/scan/manual", json=SAUSAGE, headers=headers).json()
    assert [alt["name"] for alt in anywhere["alternatives"]] == ["Blanc de poulet"]
    assert anywhere["alternatives_scope"] == "any"


def test_alternatives_fall_back_to_any_store(client, headers):
    client.post("/scan/manual", params={"store": "lidl"}, json=CHICKEN, headers=headers)
    at_leclerc = client.post(
        "/scan/manual", params={"store": "leclerc"}, json=SAUSAGE, headers=headers
    ).json()
    # Rien de connu chez Leclerc : repli sur ce qui a été vu ailleurs, en le disant.
    assert [alt["name"] for alt in at_leclerc["alternatives"]] == ["Blanc de poulet"]
    assert at_leclerc["alternatives_scope"] == "any"


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
    assert resp["alternatives_scope"] == "store"
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
            "nutriments": {"energy-kcal_100g": 63, "proteins_100g": "10.5", "fat_100g": None},
        },
    }
    monkeypatch.setattr(off_client._client, "get", lambda url, params=None: FakeResp(payload))
    data = off_client.fetch_product_from_off("123")
    assert data["name"] == "Skyr"
    assert data["category"] == "Dairy desserts"
    assert data["protein_100g"] == 10.5
    assert data["fat_100g"] == 0
    assert data["stores"] == {"leclerc", "carrefour"}


def test_off_client_skips_products_without_kcal(monkeypatch):
    payload = {"status": 1, "product": {"product_name": "Sans kcal", "nutriments": {}}}
    monkeypatch.setattr(off_client._client, "get", lambda url, params=None: FakeResp(payload))
    assert off_client.fetch_product_from_off("123") is None


def test_off_search_builds_store_query(monkeypatch):
    seen = {}

    def fake_get(url, params=None):
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
