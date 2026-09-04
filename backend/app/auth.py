import hashlib
import secrets

from fastapi import Depends, HTTPException, Security
from fastapi.security import APIKeyHeader
from sqlalchemy.orm import Session, joinedload

from .database import get_db
from .models import User

api_key_header = APIKeyHeader(name="X-Api-Key", auto_error=False)


def hash_key(raw_key: str) -> str:
    return hashlib.sha256(raw_key.encode()).hexdigest()


def generate_key() -> str:
    return secrets.token_urlsafe(32)


def current_user(
    api_key: str | None = Security(api_key_header), db: Session = Depends(get_db)
) -> User:
    # On cherche par hash : la clé en clair n'est jamais stockée ni comparée directement.
    user = None
    if api_key:
        user = (
            db.query(User)
            .options(joinedload(User.profile))
            .filter(User.api_key_hash == hash_key(api_key))
            .first()
        )
    if not user:
        raise HTTPException(401, "Clé API manquante ou invalide.")
    return user
