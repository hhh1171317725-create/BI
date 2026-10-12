#!/usr/bin/env python3
"""Private NDJSON worker for the BI Garmin tool; never print credentials or raw replies.

The Java service owns directories, deadlines, authentication, concurrency and publishing.
This process only reads the consenting user's cloud data and emits bounded daily summaries.
It deliberately has no generic API method, upload, delete, or URL supplied by a browser.
"""

from __future__ import annotations

import contextlib
import datetime as dt
import importlib.metadata
import json
import logging
import math
import os
from pathlib import Path
import re
import sys
import time
from typing import Any, Callable

MAX_INPUT = 16384
MAX_DAYS = 90
MAX_ACTIVITIES = 2000
MAX_MFA_ATTEMPTS = 3
EXPECTED_VERSION = "0.3.17"
DETAIL_METHODS = {
    "steps": "get_steps_data", "floors": "get_floors", "stress": "get_all_day_stress",
    "battery_events": "get_body_battery_events", "hydration": "get_hydration_data",
    "respiration": "get_respiration_data", "spo2": "get_spo2_data", "intensity": "get_intensity_minutes_data",
    "training_readiness": "get_training_readiness", "training_status": "get_training_status",
    "training_load": "get_training_four_week_load_balance", "daily_training": "get_daily_training_status",
    "vo2max": "get_max_metrics", "fitness_age": "get_fitnessage_data",
    "endurance": "get_endurance_score", "hill": "get_hill_score", "move_iq": "get_all_day_events",
    "lifestyle": "get_lifestyle_logging_data", "menstrual": "get_menstrual_data_for_date",
    "nutrition": "get_nutrition_daily_food_log", "meals": "get_nutrition_daily_meals",
}
ACTIVITY_METHODS = {
    "overview": "get_activity", "splits": "get_activity_splits", "typed_splits": "get_activity_typed_splits",
    "split_summaries": "get_activity_split_summaries", "weather": "get_activity_weather",
    "heart_zones": "get_activity_hr_in_timezones", "power_zones": "get_activity_power_in_timezones",
    "exercise_sets": "get_activity_exercise_sets", "gear": "get_activity_gear",
}
PRIVATE_KEY = re.compile(r"password|token|secret|cookie|authorization|email|displayname|username|profileimage", re.I)
_protocol_output = None


class WorkerError(Exception):
    def __init__(self, code: str):
        self.code = code
        super().__init__(code)


def emit(event: dict[str, Any]) -> None:
    # allow_nan=False prevents invalid JSON (and propagating provider NaN into the cache).
    output = _protocol_output or sys.stdout
    output.write(json.dumps(event, ensure_ascii=True, allow_nan=False, separators=(",", ":")) + "\n")
    output.flush()


def read_command() -> dict[str, Any]:
    line = sys.stdin.readline(MAX_INPUT + 1)
    if not line or len(line) > MAX_INPUT:
        raise WorkerError("PROTOCOL")
    try:
        command = json.loads(line)
    except (ValueError, TypeError):
        raise WorkerError("PROTOCOL") from None
    if not isinstance(command, dict):
        raise WorkerError("PROTOCOL")
    return command


def status_code(error: Exception) -> int | None:
    # Only inspect numeric status, never serialize provider error text or response bodies.
    current: BaseException | None = error
    for _ in range(5):
        if current is None:
            break
        response = getattr(current, "response", None)
        status = getattr(response, "status_code", None)
        if isinstance(status, int):
            return status
        # Pinned 0.3.17 wraps API failures without attaching requests.Response.
        # Consume only its fixed numeric prefix; never emit the appended provider detail.
        if type(current).__name__ in ("GarminConnectConnectionError", "GarminConnectNotFoundError"):
            match = re.match(r"^API Error ([1-5][0-9]{2})(?:\s|$)", str(current))
            if match:
                return int(match.group(1))
        current = current.__cause__ or current.__context__
    return None


def classify(error: Exception, *, sync: bool = False) -> str:
    if isinstance(error, WorkerError):
        return error.code
    name = type(error).__name__
    status = status_code(error)
    if name == "GarminConnectTooManyRequestsError" or status == 429:
        return "RATE_LIMIT"
    if name == "GarminConnectAuthenticationError" or status in (401, 403):
        return "SESSION_EXPIRED" if sync else "AUTH"
    if isinstance(error, (ModuleNotFoundError, ImportError)):
        return "DEPENDENCY"
    if isinstance(error, OSError) and not isinstance(error, (ConnectionError, TimeoutError)):
        return "IO"
    if name in ("GarminConnectConnectionError", "ConnectionError", "Timeout", "ConnectTimeout", "ReadTimeout") or isinstance(error, (ConnectionError, TimeoutError)):
        return "NETWORK"
    return "UNKNOWN"


def session_dir(value: Any, *, connecting: bool) -> Path:
    if not isinstance(value, str) or not value or len(value) > 4096:
        raise WorkerError("PROTOCOL")
    path = Path(value)
    if not path.is_absolute() or ".." in path.parts:
        raise WorkerError("PROTOCOL")
    for parent in (path, *path.parents):
        if parent.is_symlink():
            raise WorkerError("IO")
    if connecting:
        path.mkdir(mode=0o700, parents=True, exist_ok=True)
    elif not path.is_dir() or not (path / "garmin_tokens.json").is_file():
        raise WorkerError("SESSION_EXPIRED")
    if os.name == "posix":
        path.chmod(0o700)
    return path


def garmin_factory() -> Callable[..., Any]:
    try:
        if sys.version_info < (3, 12) or importlib.metadata.version("garminconnect") != EXPECTED_VERSION:
            raise WorkerError("DEPENDENCY")
        from garminconnect import Garmin
    except (ModuleNotFoundError, ImportError, importlib.metadata.PackageNotFoundError):
        raise WorkerError("DEPENDENCY") from None
    return Garmin


def connect(command: dict[str, Any], factory: Callable[..., Any]) -> None:
    email, password = command.get("email"), command.get("password")
    region = command.get("region")
    if not isinstance(email, str) or not 1 <= len(email) <= 254 or not isinstance(password, str) or not 1 <= len(password) <= 1024 or region not in ("global", "cn"):
        raise WorkerError("PROTOCOL")
    token_path = session_dir(command.get("sessionDir"), connecting=True)
    client = factory(email=email, password=password, is_cn=region == "cn", return_on_mfa=True, retry_attempts=1)
    # No account password is saved. Java creates a fresh staging directory for each attempt.
    command.pop("password", None)
    password = None
    result, state = client.login(str(token_path))
    if result == "needs_mfa":
        for attempt in range(MAX_MFA_ATTEMPTS):
            emit({"type": "mfa", "invalidCode": attempt > 0})
            challenge = read_command()
            code = challenge.get("code")
            if challenge.get("action") != "mfa" or not isinstance(code, str) or not re.fullmatch(r"[0-9]{6,8}", code):
                raise WorkerError("MFA")
            try:
                client.resume_login(state or {}, code)
                break
            except Exception as error:
                if type(error).__name__ == "GarminConnectAuthenticationError" and attempt + 1 < MAX_MFA_ATTEMPTS:
                    continue
                raise WorkerError("MFA" if classify(error) == "AUTH" else classify(error)) from None
            finally:
                challenge.clear()
                code = None
        else:
            raise WorkerError("MFA")
    elif result is not None:
        raise WorkerError("PROTOCOL")
    # The library's return_on_mfa branch skips automatic token persistence and profile load.
    # Explicit dump + credential-free login verifies both before Java can publish the session.
    client.client.dump(str(token_path))
    client.password = None
    client.username = None
    verifier = factory(is_cn=region == "cn", retry_attempts=1)
    verifier.login(str(token_path))
    tokens = token_path / "garmin_tokens.json"
    if not tokens.is_file() or tokens.stat().st_size == 0:
        raise WorkerError("IO")
    if os.name == "posix":
        tokens.chmod(0o600)
    emit({"type": "connected"})


def number(value: Any, *, maximum: float | None = None, minimum: float = 0) -> float | int | None:
    if isinstance(value, bool) or not isinstance(value, (int, float)) or not math.isfinite(value) or value < minimum or (maximum is not None and value > maximum):
        return None
    return value


def obj(value: Any) -> dict[str, Any]:
    return value if isinstance(value, dict) else {}


def text(value: Any, maximum: int = 200) -> str | None:
    if not isinstance(value, str):
        return None
    return re.sub(r"[\x00-\x1f\x7f]", "", value)[:maximum] or None


def epoch_iso(value: Any) -> str | None:
    stamp = number(value)
    if stamp is None or stamp < 946684800000 or stamp > 4102444800000:
        return None
    try:
        return dt.datetime.fromtimestamp(stamp / 1000, dt.timezone.utc).isoformat().replace("+00:00", "Z")
    except (ValueError, OSError, OverflowError):
        return None


def activity_time(value: Any) -> str | None:
    raw = text(value, 40)
    if raw is None:
        return None
    try:
        parsed = dt.datetime.fromisoformat(raw.replace("Z", "+00:00"))
    except ValueError:
        return None
    # startTimeGMT is explicitly UTC even if its string omits an offset.
    if parsed.tzinfo is None:
        parsed = parsed.replace(tzinfo=dt.timezone.utc)
    return parsed.astimezone(dt.timezone.utc).isoformat().replace("+00:00", "Z")


def detail_record(raw: Any) -> dict[str, Any]:
    """Bounded read-only health fields, including negative samples/sentinels, never credentials."""
    budget = [18000]
    clipped = [False]
    def clean(value: Any, depth: int = 0) -> Any:
        budget[0] -= 1
        if budget[0] < 0 or depth > 12 or (depth == 12 and isinstance(value, (list, dict))):
            clipped[0] = True
            return None
        if value is None or isinstance(value, bool):
            return value
        if isinstance(value, (int, float)):
            return value if math.isfinite(value) else None
        if isinstance(value, str):
            if len(value) > 500:
                clipped[0] = True
            return text(value, 500)
        if isinstance(value, dict):
            result = {}
            for key, item in list(value.items())[:500]:
                if budget[0] <= 0:
                    clipped[0] = True
                    break
                if isinstance(key, str) and len(key) <= 100 and not PRIVATE_KEY.search(key):
                    result[key] = clean(item, depth + 1)
            clipped[0] |= len(value) > 500
            return result
        if isinstance(value, list):
            clipped[0] |= len(value) > 5000
            result = []
            for item in value[:5000]:
                if budget[0] <= 0:
                    clipped[0] = True
                    break
                result.append(clean(item, depth + 1))
            return result
        return None
    value = clean(raw)
    # Keep scalar metrics; thin oversized sample arrays with an explicit truncation flag.
    while len(json.dumps(value, ensure_ascii=True)) > 120000:
        arrays = []
        def find(node):
            if isinstance(node, list):
                if len(node) > 30:
                    arrays.append(node)
                for child in node:
                    find(child)
            elif isinstance(node, dict):
                for child in node.values():
                    find(child)
        find(value)
        clipped[0] = True
        if not arrays:
            value = None
            break
        largest = max(arrays, key=len)
        largest[:] = largest[::2]
    return {"state": "available" if value not in (None, {}, []) else "unavailable",
            "value": value, "truncated": clipped[0],
            "collectedAt": dt.datetime.now(dt.timezone.utc).isoformat().replace("+00:00", "Z")}


def normalize_activity(raw: dict[str, Any]) -> dict[str, Any] | None:
    activity_id = raw.get("activityId")
    if isinstance(activity_id, bool) or not isinstance(activity_id, (int, str)) or not re.fullmatch(r"[0-9]{1,30}", str(activity_id)):
        return None
    return {
        "id": str(activity_id),
        "name": text(raw.get("activityName")) or "运动",
        "type": text(obj(raw.get("activityType")).get("typeKey"), 60) or "other",
        "startTime": activity_time(raw.get("startTimeGMT")),
        "durationSeconds": number(raw.get("duration"), maximum=604800),
        "distanceMeters": number(raw.get("distance"), maximum=10000000),
        "calories": number(raw.get("calories"), maximum=100000),
        "averageHeartRate": number(raw.get("averageHR"), maximum=300, minimum=1),
        "overview": detail_record(raw),
    }


def body_battery(raw: Any, day: str) -> float | int | None:
    entries = raw if isinstance(raw, list) else []
    for entry in entries:
        if not isinstance(entry, dict) or entry.get("date") != day:
            continue
        points = entry.get("bodyBatteryValuesArray")
        if isinstance(points, list):
            values = [(number(point[0]), number(point[1], maximum=100)) for point in points if isinstance(point, list) and len(point) >= 2]
            valid = [(stamp, value) for stamp, value in values if stamp is not None and value is not None]
            if valid:
                return max(valid, key=lambda point: point[0])[1]
    return None


def weight_kg(raw: Any) -> float | None:
    weights = obj(raw).get("dateWeightList")
    if not isinstance(weights, list):
        return None
    # Garmin weight endpoints return grams, irrespective of the account's display unit.
    valid = [entry for entry in weights if isinstance(entry, dict) and number(entry.get("weight"), minimum=1, maximum=1000000) is not None]
    if not valid:
        return None
    last = max(valid, key=lambda entry: number(entry.get("date")) or number(entry.get("samplePk")) or 0)
    return round(last["weight"] / 1000, 3)


def normalize_day(day: str, stats: dict[str, Any], sleep: Any, heart: Any, hrv: Any, weight: Any, battery: Any, activities: list[dict[str, Any]] | None, warnings: list[str]) -> dict[str, Any]:
    sleep_dto = obj(obj(sleep).get("dailySleepDTO"))
    heart = obj(heart)
    readings = heart.get("heartRateValues")
    samples = [number(point[1], minimum=1, maximum=300) for point in readings if isinstance(point, list) and len(point) >= 2] if isinstance(readings, list) else []
    samples = [sample for sample in samples if sample is not None]
    nightly_avg = number(obj(obj(hrv).get("hrvSummary")).get("lastNightAvg"), maximum=1000)
    if nightly_avg is None:
        nightly_avg = number(sleep_dto.get("avgSleepHRV"), maximum=1000)
    durations = [activity["durationSeconds"] for activity in activities or [] if activity.get("durationSeconds") is not None]
    # An unavailable workout endpoint is distinct from a successful empty activity list.
    workout_seconds = sum(durations) if activities is not None else None
    summary = {
        "steps": number(stats.get("totalSteps")),
        "distanceMeters": number(stats.get("totalDistanceMeters")),
        "activeCalories": number(stats.get("activeKilocalories")),
        "totalCalories": number(stats.get("totalKilocalories")),
        "restingHeartRate": number(stats.get("restingHeartRate"), minimum=1, maximum=300),
        "heartRateAverage": round(sum(samples) / len(samples), 1) if samples else None,
        "heartRateMin": number(stats.get("minHeartRate"), minimum=1, maximum=300),
        "heartRateMax": number(stats.get("maxHeartRate"), minimum=1, maximum=300),
        "sleepSeconds": number(sleep_dto.get("sleepTimeSeconds"), maximum=172800),
        "deepSleepSeconds": number(sleep_dto.get("deepSleepSeconds"), maximum=172800),
        "lightSleepSeconds": number(sleep_dto.get("lightSleepSeconds"), maximum=172800),
        "remSleepSeconds": number(sleep_dto.get("remSleepSeconds"), maximum=172800),
        "awakeSleepSeconds": number(sleep_dto.get("awakeSleepSeconds"), maximum=172800),
        "sleepScore": number(obj(obj(sleep_dto.get("sleepScores")).get("overall")).get("value"), maximum=100),
        "sleepStartTime": epoch_iso(sleep_dto.get("sleepStartTimestampGMT")),
        "sleepEndTime": epoch_iso(sleep_dto.get("sleepEndTimestampGMT")),
        "stress": number(stats.get("averageStressLevel"), maximum=100),
        "bodyBattery": body_battery(battery, day),
        "hrvMs": nightly_avg,
        "weightKg": weight_kg(weight),
        "workoutSeconds": workout_seconds,
    }
    day_battery = [entry for entry in battery or [] if isinstance(entry, dict) and entry.get("date") == day] if isinstance(battery, list) else None
    details = {key: detail_record(value) for key, value in {
        "daily": stats, "sleep": sleep, "heart": heart, "hrv": hrv, "weight": weight, "battery": day_battery,
    }.items()}
    return {"summary": summary, "activities": activities or [], "warnings": sorted(set(warnings)), "details": details}


def optional_call(callback: Callable[[], Any], label: str, warnings: list[str]) -> Any:
    try:
        value = callback()
        if value is None:
            warnings.append(label)
        return value
    except Exception as error:
        if label == "heart_rate" and type(error).__name__ == "GarminConnectConnectionError" and str(error) == "No heart rate data received":
            warnings.append(label)
            return None
        if status_code(error) in (400, 404, 405, 422):
            warnings.append(label)
            return None
        # Network/auth/rate-limit failures must not silently replace good cache data with gaps.
        raise


def activity_range(client: Any, start: str, end: str) -> list[dict[str, Any]]:
    items: list[dict[str, Any]] = []
    seen: set[str] = set()
    for offset in range(0, MAX_ACTIVITIES, 100):
        data = client.connectapi("/activitylist-service/activities/search/activities", params={"startDate": start, "endDate": end, "start": str(offset), "limit": "100", "sortOrder": "asc"})
        rows = obj(data).get("activityList") if isinstance(data, dict) else data
        if not isinstance(rows, list) or len(rows) > 100:
            raise WorkerError("PROTOCOL")
        for row in rows:
            if not isinstance(row, dict):
                raise WorkerError("PROTOCOL")
            identifier = str(row.get("activityId", ""))
            if identifier in seen:
                raise WorkerError("PROTOCOL")
            seen.add(identifier)
            items.append(row)
        if len(rows) < 100:
            return items
    # A single-item sentinel distinguishes exactly-at-limit from a truncated range.
    more = client.connectapi("/activitylist-service/activities/search/activities", params={"startDate": start, "endDate": end, "start": str(MAX_ACTIVITIES), "limit": "1", "sortOrder": "asc"})
    more_rows = obj(more).get("activityList") if isinstance(more, dict) else more
    if more_rows == []:
        return items
    raise WorkerError("PROTOCOL")


def date_range(command: dict[str, Any]) -> tuple[dt.date, dt.date]:
    try:
        start_text, end_text = command["startDate"], command["endDate"]
        if not isinstance(start_text, str) or not isinstance(end_text, str) or not re.fullmatch(r"\d{4}-\d{2}-\d{2}", start_text) or not re.fullmatch(r"\d{4}-\d{2}-\d{2}", end_text):
            raise ValueError()
        start, end = dt.date.fromisoformat(start_text), dt.date.fromisoformat(end_text)
    except (KeyError, ValueError, TypeError):
        raise WorkerError("PROTOCOL") from None
    today = dt.datetime.now(dt.timezone(dt.timedelta(hours=8))).date()
    if start > end or (end - start).days >= MAX_DAYS or start < dt.date(2000, 1, 1) or end > today:
        raise WorkerError("PROTOCOL")
    return start, end


def sync(command: dict[str, Any], factory: Callable[..., Any], *, pause: Callable[[float], None] = time.sleep) -> None:
    if command.get("region") not in ("global", "cn"):
        raise WorkerError("PROTOCOL")
    start, end = date_range(command)
    if "includeDetails" in command and not isinstance(command["includeDetails"], bool):
        raise WorkerError("PROTOCOL")
    activity_id = command.get("activityId")
    if activity_id is not None and (not isinstance(activity_id, str) or not re.fullmatch(r"[1-9][0-9]{0,29}", activity_id) or start != end):
        raise WorkerError("PROTOCOL")
    token_path = session_dir(command.get("sessionDir"), connecting=False)
    client = factory(is_cn=command["region"] == "cn", retry_attempts=1)
    client.login(str(token_path))
    shared_warnings: list[str] = []
    raw_activities = optional_call(lambda: activity_range(client, start.isoformat(), end.isoformat()), "activities", shared_warnings)
    battery = optional_call(lambda: client.get_body_battery(start.isoformat(), end.isoformat()), "body_battery", shared_warnings)
    by_day: dict[str, list[dict[str, Any]]] = {}
    for raw in raw_activities or []:
        # Use the provider's local calendar day; its GMT day may differ near midnight.
        local = raw.get("startTimeLocal")
        if not isinstance(local, str) or len(local) < 10:
            raise WorkerError("PROTOCOL")
        try:
            day = dt.date.fromisoformat(local[:10])
        except ValueError:
            raise WorkerError("PROTOCOL") from None
        if not start <= day <= end:
            raise WorkerError("PROTOCOL")
        activity = normalize_activity(raw)
        if activity is None:
            raise WorkerError("PROTOCOL")
        by_day.setdefault(day.isoformat(), []).append(activity)
    days = (end - start).days + 1
    for offset in range(days):
        day = (start + dt.timedelta(days=offset)).isoformat()
        stats = client.get_stats(day)
        if not isinstance(stats, dict) or not stats or stats.get("calendarDate") != day:
            raise WorkerError("PROTOCOL")
        warnings = list(shared_warnings)
        sleep = optional_call(lambda: client.get_sleep_data(day), "sleep", warnings)
        heart = optional_call(lambda: client.get_heart_rates(day), "heart_rate", warnings)
        hrv = optional_call(lambda: client.get_hrv_data(day), "hrv", warnings)
        weight = optional_call(lambda: client.get_daily_weigh_ins(day), "weight", warnings)
        for response in (obj(sleep).get("dailySleepDTO"), heart, obj(hrv).get("hrvSummary")):
            if isinstance(response, dict) and response.get("calendarDate") not in (None, day):
                raise WorkerError("PROTOCOL")
        payload = normalize_day(day, stats, sleep, heart, hrv, weight, battery, by_day.get(day, []) if raw_activities is not None else None, warnings)
        if command.get("includeDetails") is True:
            callbacks = {key: (lambda method=method: getattr(client, method)(day)) for key, method in DETAIL_METHODS.items()}
            callbacks.update({
                "composition": lambda: client.get_body_composition(day, day),
                "blood_pressure": lambda: client.get_blood_pressure(day, day),
                "race_predictions": lambda: client.get_race_predictions(day, day, "daily"),
                "running_tolerance": lambda: client.get_running_tolerance(day, day, "daily"),
                "ftp": lambda: {"running": optional_call(lambda: client.get_functional_threshold_power_range(day, day, sport="RUNNING"), "ftp", warnings),
                                "cycling": optional_call(lambda: client.get_functional_threshold_power_range(day, day, sport="CYCLING"), "ftp", warnings)},
                "lactate_threshold": lambda: client.get_lactate_threshold(latest=False, start_date=day, end_date=day, aggregation="daily"),
            })
            for key, callback in callbacks.items():
                value = optional_call(callback, key, warnings)
                if isinstance(value, dict) and value.get("calendarDate") not in (None, day):
                    raise WorkerError("PROTOCOL")
                payload["details"][key] = detail_record(value)
                pause(0.1)
        if activity_id is not None:
            activity = next((item for item in payload["activities"] if item["id"] == activity_id), None)
            if activity is None:
                raise WorkerError("PROTOCOL")
            calls = {key: (lambda method=method: getattr(client, method)(activity_id)) for key, method in ACTIVITY_METHODS.items()}
            calls["samples"] = lambda: client.get_activity_details(activity_id, maxchart=1000, maxpoly=1000)
            activity["details"] = {}
            for key, callback in calls.items():
                raw = optional_call(callback, "activities", warnings)
                if isinstance(raw, dict) and raw.get("activityId") is not None and str(raw["activityId"]) != activity_id:
                    raise WorkerError("PROTOCOL")
                activity["details"][key] = detail_record(raw)
                pause(0.1)
        # Enforce the Java line bound even when a day contains many workouts or samples.
        records = list(payload["details"].values())
        for activity in payload["activities"]:
            if activity.get("overview"):
                records.append(activity["overview"])
            records.extend(activity.get("details", {}).values())
        size = len(json.dumps(payload, ensure_ascii=True))
        for record in sorted(records, key=lambda record: len(json.dumps(record["value"], ensure_ascii=True)), reverse=True):
            if size <= 1800000:
                break
            size -= len(json.dumps(record["value"], ensure_ascii=True)) - 4
            record.update(value=None, state="unavailable", truncated=True)
        if size > 1800000:
            raise WorkerError("PROTOCOL")
        emit({"type": "day", "date": day, "data": payload})
        if offset + 1 < days:
            pause(0.25)
    emit({"type": "done", "startDate": start.isoformat(), "endDate": end.isoformat(), "dayCount": days})


def main() -> int:
    global _protocol_output
    logging.disable(logging.CRITICAL)
    os.umask(0o077)
    # Environment-only token defaults must not accidentally connect another site's account.
    os.environ.pop("GARMINTOKENS", None)
    if hasattr(sys.stdin, "reconfigure"):
        sys.stdin.reconfigure(encoding="utf-8")
    if hasattr(sys.stdout, "reconfigure"):
        sys.stdout.reconfigure(encoding="utf-8")
    command: dict[str, Any] = {}
    _protocol_output = sys.stdout
    try:
        if sys.argv[1:] == ["--check"]:
            with contextlib.redirect_stdout(sys.stderr):
                garmin_factory()
            emit({"type": "ready", "version": EXPECTED_VERSION})
            return 0
        if sys.argv[1:]:
            raise WorkerError("PROTOCOL")
        command = read_command()
        # Any unexpected library prints go to discarded stderr, never the NDJSON protocol.
        with contextlib.redirect_stdout(sys.stderr):
            factory = garmin_factory()
            if command.get("action") == "connect":
                connect(command, factory)
            elif command.get("action") == "sync":
                sync(command, factory)
            else:
                raise WorkerError("PROTOCOL")
        return 0
    except Exception as error:
        emit({"type": "error", "code": classify(error, sync=command.get("action") == "sync")})
        return 1
    finally:
        command.clear()
        _protocol_output = None


if __name__ == "__main__":
    raise SystemExit(main())
