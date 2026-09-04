from app import services

CHICKEN = {"name": "Blanc de poulet", "category": "Meat", "kcal_100g": 110, "protein_100g": 23}
SAUSAGE = {
    "name": "Saucisse",
    "category": "Meat",
    "kcal_100g": 320,
    "protein_100g": 12,
    "fat_100g": 28,
    "saturated_fat_100g": 11,
}


def test_categories_are_listed_with_french_labels(client):
    cats = client.get("/categories").json()
    assert {"slug": "Meat", "label": "Viande"} in cats
    assert len(cats) > 30


def test_thiriet_is_a_known_store(client, headers):
    stores = client.get("/stores").json()
    assert {"slug": "thiriet", "label": "Thiriet"} in stores
    resp = client.post("/scan/manual", params={"store": "thiriet"}, json=CHICKEN, headers=headers)
    assert resp.status_code == 200


def test_recommendations_ranked_for_goal_and_store(client, headers, off_product):
    off_product("Saucisse", "Meat", 320, 12, stores=["lidl"], saturated_fat_100g=11)
    off_product("Blanc de poulet", "Meat", 110, 23, stores=["lidl"])

    resp = client.get(
        "/recommendations", params={"category": "Meat", "store": "lidl"}, headers=headers
    )
    assert resp.status_code == 200
    body = resp.json()
    assert [i["name"] for i in body["items"]] == ["Blanc de poulet", "Saucisse"]
    assert body["items"][0]["score"] > body["items"][1]["score"]
    assert body["items"][0]["kcal_100g"] == 110
    assert body["items_elsewhere"] == []


def test_recommendations_split_store_and_elsewhere(client, headers, monkeypatch):
    calls = []

    def fake_search(category, store):
        calls.append((category, store))
        return [
            {
                "barcode": "555",
                "name": "Filet de dinde",
                "category": "Meat",
                "kcal_100g": 105,
                "protein_100g": 24,
                "carbs_100g": 0,
                "sugars_100g": 0,
                "fat_100g": 1,
                "saturated_fat_100g": 0.3,
                "fiber_100g": 0,
                "salt_100g": 0.2,
                "stores": set(),
            },
        ]

    monkeypatch.setattr(services, "search_products", fake_search)
    body = client.get(
        "/recommendations", params={"category": "Meat", "store": "thiriet"}, headers=headers
    ).json()
    assert calls == [("Meat", "thiriet")]
    # Rien de connu chez Thiriet : la liste principale est vide, le reste part sous « ailleurs ».
    assert body["items"] == []
    assert [i["name"] for i in body["items_elsewhere"]] == ["Filet de dinde"]


def test_recommendations_reject_unknown_category(client, headers):
    assert (
        client.get("/recommendations", params={"category": "Yolo"}, headers=headers).status_code
        == 422
    )


def test_every_store_has_off_tags_and_a_search_query(monkeypatch):
    """Régression : `thiriet` avait un libellé mais pas de tag OFF -> KeyError en production."""
    from typing import get_args

    from app import off_client
    from app.schemas import Store
    from app.stores import STORE_LABELS, STORE_OFF_TAGS

    assert set(STORE_LABELS) == set(STORE_OFF_TAGS) == set(get_args(Store))
    seen = []

    class Empty:
        status_code = 200

        @staticmethod
        def json():
            return {"products": []}

    def fake_get(url, params=None, **kw):
        seen.append(params)
        return Empty()

    monkeypatch.setattr(off_client._client, "get", fake_get)
    for store in STORE_LABELS:
        off_client.search_products("Meat", store)
    assert [p["stores_tags"] for p in seen] == [STORE_OFF_TAGS[s][0] for s in STORE_LABELS]
