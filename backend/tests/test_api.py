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
    body = {"weight_kg": 80, "height_cm": 180, "goal": "cut"}
    assert client.put("/profile", json=body, headers=headers).status_code == 200
    body2 = {"weight_kg": 60, "height_cm": 165, "goal": "maintenance"}
    assert client.put("/profile", json=body2, headers=other).status_code == 200

    assert client.get("/profile", headers=headers).json()["goal"] == "cut"
    assert client.get("/profile", headers=other).json()["weight_kg"] == 60


def test_profile_rejects_absurd_values(client, headers):
    resp = client.put("/profile", json={"weight_kg": -5, "height_cm": 180}, headers=headers)
    assert resp.status_code == 422


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

    # Chez Leclerc : le poulet n'a été vu que chez Lidl, donc rien à proposer.
    at_leclerc = client.post(
        "/scan/manual", params={"store": "leclerc"}, json=SAUSAGE, headers=headers
    ).json()
    assert at_leclerc["alternatives"] == []

    # Chez Lidl : le poulet est proposé, mieux noté.
    at_lidl = client.post(
        "/scan/manual", params={"store": "lidl"}, json=SAUSAGE, headers=headers
    ).json()
    names = [alt["name"] for alt in at_lidl["alternatives"]]
    assert names == ["Blanc de poulet"]
    assert at_lidl["alternatives"][0]["score"] > at_lidl["score"]

    # Sans enseigne : toutes les alternatives connues, quelle que soit l'enseigne.
    anywhere = client.post("/scan/manual", json=SAUSAGE, headers=headers).json()
    assert [alt["name"] for alt in anywhere["alternatives"]] == ["Blanc de poulet"]


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


def test_barcode_not_found(client, headers, monkeypatch):
    monkeypatch.setattr(services, "fetch_product_from_off", lambda barcode: None)
    assert client.get("/scan/barcode/00000000", headers=headers).status_code == 404


def test_barcode_format_validated(client, headers):
    assert client.get("/scan/barcode/abc", headers=headers).status_code == 422


def test_off_client_maps_fields(monkeypatch):
    class FakeResp:
        status_code = 200

        @staticmethod
        def json():
            return {
                "status": 1,
                "product": {
                    "product_name": "Skyr",
                    "pnns_groups_2": "Dairy desserts",
                    "nutriments": {
                        "energy-kcal_100g": 63,
                        "proteins_100g": "10.5",
                        "fat_100g": None,
                    },
                },
            }

    monkeypatch.setattr(off_client._client, "get", lambda url: FakeResp())
    data = off_client.fetch_product_from_off("123")
    assert data["name"] == "Skyr"
    assert data["category"] == "Dairy desserts"
    assert data["protein_100g"] == 10.5
    assert data["fat_100g"] == 0
