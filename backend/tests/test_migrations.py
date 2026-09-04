"""Le schéma réel peut être en retard sur les modèles : la migration doit toujours le rattraper."""

from sqlalchemy import create_engine, inspect, text
from sqlalchemy.pool import StaticPool

from app import migrations, models


def _engine():
    return create_engine(
        "sqlite://", connect_args={"check_same_thread": False}, poolclass=StaticPool
    )


def test_missing_columns_and_indexes_are_added():
    engine = _engine()
    models.Base.metadata.create_all(engine)
    with engine.begin() as conn:
        # Base « ancienne » : colonnes et index apparus après coup n'existent pas.
        conn.execute(text("ALTER TABLE alternative_seeds DROP COLUMN version"))
        conn.execute(text("ALTER TABLE products DROP COLUMN image_url"))
        conn.execute(text("DROP INDEX ix_scans_user_created"))
        conn.execute(text("INSERT INTO alternative_seeds (category, store) VALUES ('Meat', '')"))

    migrations.run(engine)

    inspector = inspect(engine)
    assert "version" in {c["name"] for c in inspector.get_columns("alternative_seeds")}
    assert "image_url" in {c["name"] for c in inspector.get_columns("products")}
    assert "ix_scans_user_created" in {i["name"] for i in inspector.get_indexes("scans")}
    with engine.connect() as conn:
        # La ligne existante a reçu le défaut du modèle, pas NULL.
        assert conn.execute(text("SELECT version FROM alternative_seeds")).scalar() == 1


def test_migration_is_idempotent_on_a_fresh_schema():
    engine = _engine()
    models.Base.metadata.create_all(engine)
    migrations.run(engine)
    migrations.run(engine)  # deux fois : rien à faire, rien ne casse


def test_every_model_column_is_migratable():
    """Chaque colonne non nullable ajoutée à une table existante doit avoir un défaut scalaire."""
    engine = _engine()
    for table in models.Base.metadata.sorted_tables:
        for column in table.columns:
            if column.primary_key or column.nullable or column.server_default is not None:
                continue
            ddl = migrations._add_column_sql(table.name, column, engine)
            assert "DEFAULT" in ddl or column.name in _created_with_table(table), (
                f"{table.name}.{column.name} : NOT NULL sans défaut, non ajoutable après coup"
            )


def _created_with_table(table) -> set[str]:
    """Colonnes présentes depuis la création de la table (jamais ajoutées après coup)."""
    original = {
        "users": {"name", "api_key_hash"},
        "profiles": {"user_id", "age", "height_cm", "weight_kg", "sex", "activity", "goal"},
        "products": {
            "name",
            "kcal_100g",
            "protein_100g",
            "carbs_100g",
            "sugars_100g",
            "fat_100g",
            "saturated_fat_100g",
            "fiber_100g",
            "salt_100g",
        },
        "product_stores": {"product_id", "store"},
        "scans": {"user_id", "product_id", "score", "category"},
        "alternative_seeds": {"category", "store"},
    }
    return original.get(table.name, set())
