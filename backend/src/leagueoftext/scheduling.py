"""Gestió de les regles d'Amazon EventBridge que encadenen les Lambdes.

* Regles *one-shot* (``MatchIsLiveScheduler-<matchId>``): s'executen una sola vegada
  una hora abans d'un partit.
* Regles de *polling* (``MatchIsLive``, ``GameIsLive``, ``UpdateGameState``): s'executen
  cada minut amb un objectiu (*target*) per partit o joc actiu, i es desactiven quan
  no en queda cap.

Els ARN de les funcions es dedueixen de l'ARN de la Lambda que s'està executant
(abans estaven escrits al codi amb l'id del compte d'AWS).
"""
from __future__ import annotations

import json
import logging
import os
from datetime import datetime, timedelta, timezone
from functools import lru_cache

import boto3
from botocore.exceptions import ClientError

log = logging.getLogger(__name__)

MATCH_IS_LIVE_SCHEDULER = "MatchIsLiveScheduler"
MATCH_IS_LIVE = "MatchIsLive"
UPDATE_MATCH_STATE = "UpdateMatchState"
GAME_IS_LIVE = "GameIsLive"
UPDATE_GAME_STATE = "UpdateGameState"

POLLING_RATE = "rate(1 minute)"


def function_name(logical: str) -> str:
    """Nom real de la funció. Es pot sobreescriure amb ``FUNCTION_<NOM>`` (p. ex. si el stack afegeix un prefix)."""
    return os.environ.get(f"FUNCTION_{logical.upper()}", logical)


def rule_name(logical: str) -> str:
    prefix = os.environ.get("RULE_PREFIX", "")
    return f"{prefix}{logical}"[:64]


@lru_cache(maxsize=1)
def _events():
    return boto3.client("events")


@lru_cache(maxsize=1)
def _lambda():
    return boto3.client("lambda")


class Scheduler:
    def __init__(self, invoked_function_arn: str):
        # arn:aws:lambda:<regió>:<compte>:function:<nom>[:<alias>]
        parts = invoked_function_arn.split(":")
        self.region = parts[3]
        self.account = parts[4]
        self._granted: set[str] = set()

    @classmethod
    def from_context(cls, context) -> "Scheduler":
        return cls(context.invoked_function_arn)

    def function_arn(self, logical: str) -> str:
        return f"arn:aws:lambda:{self.region}:{self.account}:function:{function_name(logical)}"

    # ------------------------------------------------------------------ permisos

    def _ensure_invoke_permission(self, logical: str) -> None:
        """Una sola declaració per funció (``rule/*``) en lloc d'una per partit.

        La versió anterior afegia un permís per cada partit programat i la política de
        recursos de la Lambda (màxim 20 KB) s'acabava omplint.
        """
        if logical in self._granted:
            return
        try:
            _lambda().add_permission(
                FunctionName=function_name(logical),
                StatementId=f"{function_name(logical)}-eventbridge",
                Action="lambda:InvokeFunction",
                Principal="events.amazonaws.com",
                SourceArn=f"arn:aws:events:{self.region}:{self.account}:rule/*",
                SourceAccount=self.account,
            )
        except ClientError as exc:
            if exc.response["Error"]["Code"] != "ResourceConflictException":
                raise
        self._granted.add(logical)

    # ------------------------------------------------------------------ regles one-shot

    def schedule_once(self, rule: str, when: datetime, target_logical: str, payload: dict) -> None:
        when = when.astimezone(timezone.utc)
        expression = f"cron({when.minute} {when.hour} {when.day} {when.month} ? {when.year})"
        self._ensure_invoke_permission(target_logical)
        _events().put_rule(Name=rule, ScheduleExpression=expression, State="ENABLED")
        _events().put_targets(
            Rule=rule,
            Targets=[{"Id": target_logical, "Arn": self.function_arn(target_logical), "Input": json.dumps(payload)}],
        )
        log.info("Regla %s programada per a %s", rule, expression)

    def delete_rule(self, rule: str) -> None:
        try:
            targets = _events().list_targets_by_rule(Rule=rule).get("Targets", [])
            if targets:
                _events().remove_targets(Rule=rule, Ids=[t["Id"] for t in targets])
            _events().delete_rule(Name=rule)
        except ClientError as exc:
            if exc.response["Error"]["Code"] != "ResourceNotFoundException":
                raise

    # ------------------------------------------------------------------ regles de polling

    def add_polling_target(self, logical: str, target_id: str, payload: dict) -> None:
        """Afegeix (o actualitza) un objectiu a la regla de *polling* ``logical`` i l'activa."""
        rule = rule_name(logical)
        self._ensure_invoke_permission(logical)
        try:
            enabled = _events().describe_rule(Name=rule)["State"] == "ENABLED"
        except ClientError as exc:
            if exc.response["Error"]["Code"] != "ResourceNotFoundException":
                raise
            enabled = False
            _events().put_rule(Name=rule, ScheduleExpression=POLLING_RATE, State="DISABLED")
        response = _events().put_targets(
            Rule=rule,
            Targets=[{"Id": target_id[:64], "Arn": self.function_arn(logical), "Input": json.dumps(payload)}],
        )
        if response.get("FailedEntryCount"):
            raise RuntimeError(f"No s'ha pogut afegir l'objectiu {target_id} a {rule}: {response}")
        if not enabled:
            _events().enable_rule(Name=rule)

    def remove_polling_target(self, logical: str, target_id: str) -> None:
        """Treu un objectiu i desactiva la regla si ja no en té cap."""
        rule = rule_name(logical)
        try:
            _events().remove_targets(Rule=rule, Ids=[target_id[:64]])
            remaining = _events().list_targets_by_rule(Rule=rule).get("Targets", [])
            if not remaining:
                _events().disable_rule(Name=rule)
                log.info("Regla %s desactivada: no hi queden objectius", rule)
        except ClientError as exc:
            if exc.response["Error"]["Code"] != "ResourceNotFoundException":
                raise

    # ------------------------------------------------------------------ invocació directa

    def invoke_async(self, logical: str, payload: dict) -> None:
        _lambda().invoke(
            FunctionName=function_name(logical),
            InvocationType="Event",
            Payload=json.dumps(payload).encode(),
        )
        log.info("Invocada %s amb %s", logical, payload)


def utcnow() -> datetime:
    return datetime.now(timezone.utc)


def is_soon(when: datetime, margin: timedelta = timedelta(minutes=2)) -> bool:
    return when <= utcnow() + margin
