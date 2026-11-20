"""Configuració del backend.

Tota la configuració sensible (claus, credencials) arriba per variables d'entorn
o per AWS Secrets Manager. Res d'això no ha d'estar mai al repositori.
"""
from __future__ import annotations

import json
import os
from functools import lru_cache

#: Lligues que es segueixen. Es pot canviar amb la variable LEAGUES (separades per comes).
DEFAULT_LEAGUES = ("LCK", "LEC", "LCS", "Worlds", "MSI", "First Stand")

#: Quants dies endavant es programen els partits.
DEFAULT_SCHEDULE_HORIZON_DAYS = 14

#: Quanta antelació (minuts) abans de l'inici es comença a vigilar si un partit és en directe.
DEFAULT_WATCH_BEFORE_MINUTES = 60


def leagues() -> tuple[str, ...]:
    raw = os.environ.get("LEAGUES")
    if not raw:
        return DEFAULT_LEAGUES
    return tuple(name.strip() for name in raw.split(",") if name.strip())


def schedule_horizon_days() -> int:
    return int(os.environ.get("SCHEDULE_HORIZON_DAYS", DEFAULT_SCHEDULE_HORIZON_DAYS))


def watch_before_minutes() -> int:
    return int(os.environ.get("WATCH_BEFORE_MINUTES", DEFAULT_WATCH_BEFORE_MINUTES))


#: Fonts de dades: ``grid`` (API oficial, Riot Games Open Access) o ``lolesports`` (API no oficial).
DATA_SOURCES = ("grid", "lolesports")
DEFAULT_DATA_SOURCE = "grid"


def data_source() -> str:
    """Font de les dades dels partits (variable ``DATA_SOURCE``)."""
    value = (os.environ.get("DATA_SOURCE") or DEFAULT_DATA_SOURCE).strip().lower()
    if value not in DATA_SOURCES:
        raise RuntimeError(f"DATA_SOURCE ha de ser una de {', '.join(DATA_SOURCES)} (és '{value}')")
    return value


def grid_api_key() -> str:
    """Clau de l'API de GRID (capçalera ``x-api-key``)."""
    key = os.environ.get("GRID_API_KEY")
    if not key:
        raise RuntimeError("Falta la variable d'entorn GRID_API_KEY (clau de Riot Games Open Access a grid.gg).")
    return key.strip()


def grid_title_id() -> str | None:
    """Id de League of Legends a GRID. Opcional: si no hi és, es consulta a l'API."""
    return (os.environ.get("GRID_TITLE_ID") or "").strip() or None


def lolesports_api_key() -> str:
    """Clau de la capçalera ``x-api-key`` de l'API de LoL Esports."""
    key = os.environ.get("LOLESPORTS_API_KEY")
    if not key:
        raise RuntimeError(
            "Falta la variable d'entorn LOLESPORTS_API_KEY (capçalera x-api-key de esports-api.lolesports.com)."
        )
    return key


@lru_cache(maxsize=1)
def firebase_credentials_info() -> dict | None:
    """Retorna el JSON del compte de servei de Firebase, o ``None`` per usar les credencials per defecte.

    Ordre de cerca:
      1. ``FIREBASE_CREDENTIALS_SECRET_ARN``: secret d'AWS Secrets Manager amb el JSON.
      2. ``FIREBASE_CREDENTIALS_JSON``: el JSON directament a la variable.
      3. ``GOOGLE_APPLICATION_CREDENTIALS``: ruta al fitxer (la gestiona firebase-admin).
    """
    secret_arn = os.environ.get("FIREBASE_CREDENTIALS_SECRET_ARN")
    if secret_arn:
        import boto3  # disponible al runtime de Lambda

        client = boto3.client("secretsmanager")
        secret = client.get_secret_value(SecretId=secret_arn)["SecretString"]
        return json.loads(secret)

    inline = os.environ.get("FIREBASE_CREDENTIALS_JSON")
    if inline:
        return json.loads(inline)

    return None
