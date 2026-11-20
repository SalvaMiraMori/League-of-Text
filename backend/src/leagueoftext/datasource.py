"""Tria la font de les dades dels partits segons ``config.data_source()``.

Els dos mòduls (:mod:`grid` i :mod:`lolesports`) exposen les mateixes funcions i retornen les
dades amb la mateixa forma, de manera que els *handlers* no depenen de quina es faci servir.
"""
from __future__ import annotations

from types import ModuleType

from . import config, grid, lolesports


def api() -> ModuleType:
    return grid if config.data_source() == "grid" else lolesports


def is_grid() -> bool:
    return config.data_source() == "grid"
