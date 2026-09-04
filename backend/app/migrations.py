"""
Mini-migrations au démarrage, déduites des modèles : `create_all` crée les tables manquantes
mais n'ajoute jamais une colonne ni un index à une table existante. On compare donc le schéma
réel (inspection) aux modèles et on ajoute ce qui manque. Idempotent, sans liste à maintenir.

Limites assumées : pas de suppression, pas de changement de type ni de contrainte.
"""

import logging

from sqlalchemy import Engine, inspect, text
from sqlalchemy.schema import CreateIndex

from .models import Base

logger = logging.getLogger(__name__)


def run(engine: Engine) -> None:
    inspector = inspect(engine)
    existing_tables = set(inspector.get_table_names())
    with engine.begin() as conn:
        for table in Base.metadata.sorted_tables:
            if table.name not in existing_tables:
                continue  # create_all vient de la créer complète
            present = {c["name"] for c in inspector.get_columns(table.name)}
            for column in table.columns:
                if column.name in present:
                    continue
                ddl = _add_column_sql(table.name, column, engine)
                logger.warning("Migration : %s", ddl)
                conn.execute(text(ddl))
            indexes = {i["name"] for i in inspector.get_indexes(table.name)}
            for index in table.indexes:
                if index.name in indexes:
                    continue
                logger.warning("Migration : index %s sur %s", index.name, table.name)
                conn.execute(CreateIndex(index, if_not_exists=True))


def _add_column_sql(table: str, column, engine: Engine) -> str:
    """Ajoutée nullable, sauf défaut scalaire dans le modèle : alors NOT NULL DEFAULT."""
    col_type = column.type.compile(dialect=engine.dialect)
    ddl = f'ALTER TABLE {table} ADD COLUMN "{column.name}" {col_type}'
    default = (
        column.default.arg if column.default is not None and column.default.is_scalar else None
    )
    if default is not None and not column.nullable:
        literal = f"'{default}'" if isinstance(default, str) else str(default)
        ddl += f" NOT NULL DEFAULT {literal}"
    return ddl
