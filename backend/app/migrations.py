"""
Mini-migrations idempotentes exécutées au démarrage, après `create_all` : celui-ci crée
les tables manquantes mais n'ajoute jamais une colonne à une table existante.
Postgres uniquement (SQLite des tests part toujours d'un schéma neuf).
"""

from sqlalchemy import Engine, text

STATEMENTS = ("ALTER TABLE products ADD COLUMN IF NOT EXISTS image_url VARCHAR(512)",)


def run(engine: Engine) -> None:
    if engine.dialect.name != "postgresql":
        return
    with engine.begin() as conn:
        for statement in STATEMENTS:
            conn.execute(text(statement))
