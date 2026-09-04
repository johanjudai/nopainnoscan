import os

# Doit précéder l'import de l'app : database.py lit DATABASE_URL à l'import.
os.environ["DATABASE_URL"] = "sqlite://"

import pytest
from fastapi.testclient import TestClient
from sqlalchemy import create_engine
from sqlalchemy.orm import sessionmaker
from sqlalchemy.pool import StaticPool

from app import database, models
from app.auth import hash_key
from app.main import app

_engine = create_engine(
    "sqlite://", connect_args={"check_same_thread": False}, poolclass=StaticPool
)
_Session = sessionmaker(bind=_engine, autoflush=False)


@pytest.fixture(autouse=True)
def db():
    models.Base.metadata.create_all(bind=_engine)
    session = _Session()
    app.dependency_overrides[database.get_db] = lambda: session
    yield session
    session.close()
    models.Base.metadata.drop_all(bind=_engine)
    app.dependency_overrides.clear()


@pytest.fixture
def client():
    return TestClient(app)


@pytest.fixture
def api_key(db):
    key = "test-key-pierre"
    db.add(models.User(name="pierre", api_key_hash=hash_key(key)))
    db.commit()
    return key


@pytest.fixture
def headers(api_key):
    return {"X-Api-Key": api_key}
