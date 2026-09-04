"""
Gestion des utilisateurs, à lancer dans le conteneur :
    docker compose exec nopainnoscan-api python -m app.cli create-user Pierre
La clé n'est affichée qu'une fois : seul son hash est stocké.
"""

import argparse
import sys

from . import models
from .auth import generate_key, hash_key
from .database import SessionLocal, engine


def create_user(name: str) -> None:
    with SessionLocal() as db:
        if db.query(models.User).filter(models.User.name == name).first():
            sys.exit(f"L'utilisateur « {name} » existe déjà (utiliser rotate-key).")
        key = generate_key()
        db.add(models.User(name=name, api_key_hash=hash_key(key)))
        db.commit()
    print(f"Utilisateur « {name} » créé.\nX-Api-Key: {key}")


def rotate_key(name: str) -> None:
    with SessionLocal() as db:
        user = db.query(models.User).filter(models.User.name == name).first()
        if not user:
            sys.exit(f"Utilisateur « {name} » introuvable.")
        key = generate_key()
        user.api_key_hash = hash_key(key)
        db.commit()
    print(f"Nouvelle clé pour « {name} » (l'ancienne est révoquée).\nX-Api-Key: {key}")


def list_users() -> None:
    with SessionLocal() as db:
        for user in db.query(models.User).order_by(models.User.id):
            print(f"{user.id}\t{user.name}")


def main() -> None:
    parser = argparse.ArgumentParser(prog="python -m app.cli")
    sub = parser.add_subparsers(dest="command", required=True)
    sub.add_parser("create-user").add_argument("name")
    sub.add_parser("rotate-key").add_argument("name")
    sub.add_parser("list-users")
    args = parser.parse_args()

    models.Base.metadata.create_all(bind=engine)
    if args.command == "create-user":
        create_user(args.name)
    elif args.command == "rotate-key":
        rotate_key(args.name)
    else:
        list_users()


if __name__ == "__main__":
    main()
