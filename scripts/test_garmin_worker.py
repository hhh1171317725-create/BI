"""Offline contract and data-correctness checks for the read-only Garmin worker."""
import contextlib
import datetime as dt
import io
import json
from pathlib import Path
import tempfile
import unittest
from unittest.mock import MagicMock, patch

import garmin_worker as worker


class GarminConnectAuthenticationError(Exception):
    pass


class GarminConnectTooManyRequestsError(Exception):
    pass


class GarminConnectConnectionError(Exception):
    pass


class HttpError(Exception):
    def __init__(self, status):
        self.response = type("Response", (), {"status_code": status})()


class WorkerTests(unittest.TestCase):
    def setUp(self):
        test_root = Path(__file__).resolve().parents[1] / ".runtime" / "garmin-tests"
        test_root.mkdir(parents=True, exist_ok=True)
        self.directory = tempfile.TemporaryDirectory(dir=test_root)
        self.assertTrue(Path(self.directory.name).resolve().is_relative_to(test_root.resolve()))
        self.addCleanup(self.directory.cleanup)
        self.path = Path(self.directory.name).absolute() / "tokens"

    def command(self, action="connect"):
        return {"action": action, "email": "fixture@example.invalid", "password": "never-log-this-password", "region": "cn", "sessionDir": str(self.path), "startDate": "2026-01-01", "endDate": "2026-01-02"}

    def token_client(self, *, mfa=False):
        client = MagicMock()
        client.login.return_value = ("needs_mfa" if mfa else None, None)

        def dump(directory):
            (Path(directory) / "garmin_tokens.json").write_text('{"fixture":"not-a-real-token"}', encoding="utf-8")

        client.client.dump.side_effect = dump
        return client

    def test_connect_explicitly_saves_and_verifies_token_without_password(self):
        client, verifier = self.token_client(), MagicMock()
        factory = MagicMock(side_effect=[client, verifier])
        command = self.command()
        output = io.StringIO()
        with contextlib.redirect_stdout(output):
            worker.connect(command, factory)
        self.assertEqual(json.loads(output.getvalue()), {"type": "connected"})
        client.client.dump.assert_called_once_with(str(self.path))
        verifier.login.assert_called_once_with(str(self.path))
        self.assertNotIn("password", command)
        self.assertNotIn("password", factory.call_args_list[1].kwargs)

    def test_mfa_retry_is_bound_to_same_client_and_never_echoes_code(self):
        client, verifier = self.token_client(mfa=True), MagicMock()
        client.resume_login.side_effect = [GarminConnectAuthenticationError(), (None, None)]
        output = io.StringIO()
        with contextlib.redirect_stdout(output), patch("sys.stdin", io.StringIO('{"action":"mfa","code":"111111"}\n{"action":"mfa","code":"222222"}\n')):
            worker.connect(self.command(), MagicMock(side_effect=[client, verifier]))
        events = [json.loads(line) for line in output.getvalue().splitlines()]
        self.assertEqual([event["type"] for event in events], ["mfa", "mfa", "connected"])
        self.assertTrue(events[1]["invalidCode"])
        self.assertEqual(client.resume_login.call_count, 2)
        self.assertNotIn("222222", output.getvalue())

    def test_mfa_fails_closed_after_three_bad_codes(self):
        client = self.token_client(mfa=True)
        client.resume_login.side_effect = GarminConnectAuthenticationError()
        with contextlib.redirect_stdout(io.StringIO()), patch("sys.stdin", io.StringIO('{"action":"mfa","code":"123456"}\n' * 3)):
            with self.assertRaises(worker.WorkerError) as failure:
                worker.connect(self.command(), MagicMock(return_value=client))
        self.assertEqual(failure.exception.code, "MFA")
        client.client.dump.assert_not_called()

    def test_verification_failure_never_emits_connected(self):
        client, verifier = self.token_client(), MagicMock()
        verifier.login.side_effect = GarminConnectAuthenticationError()
        output = io.StringIO()
        with contextlib.redirect_stdout(output), self.assertRaises(GarminConnectAuthenticationError):
            worker.connect(self.command(), MagicMock(side_effect=[client, verifier]))
        self.assertEqual(output.getvalue(), "")

    def test_region_and_session_path_validation_before_network(self):
        for invalid in ("../tokens", "tokens", ""):
            with self.assertRaises(worker.WorkerError):
                worker.session_dir(invalid, connecting=True)
        factory = MagicMock()
        command = self.command()
        command["region"] = "https://attacker.invalid"
        with self.assertRaises(worker.WorkerError):
            worker.connect(command, factory)
        factory.assert_not_called()

    def test_daily_units_nulls_zero_and_sleep_gmt(self):
        day = "2026-01-01"
        start = 1767225600000
        value = worker.normalize_day(day,
            {"totalSteps": 0, "activeKilocalories": 0, "totalKilocalories": 2200, "averageStressLevel": -1, "restingHeartRate": 55},
            {"dailySleepDTO": {"sleepTimeSeconds": 25200, "awakeSleepSeconds": 900, "sleepStartTimestampGMT": start, "sleepStartTimestampLocal": start + 57600000, "sleepScores": {"overall": {"value": 80}}}},
            {"heartRateValues": [[1, 60], [2, None], [3, -1], [4, 80], [5, float("nan")]]},
            {"hrvSummary": {"lastNightAvg": 42}},
            {"dateWeightList": [{"date": 1, "weight": 65000}, {"date": 2, "weight": 66000}]},
            [{"date": day, "bodyBatteryValuesArray": [[5, 20], [1, 80], [7, None]]}], [], [])
        summary = value["summary"]
        self.assertEqual(summary["steps"], 0)
        self.assertEqual(summary["activeCalories"], 0)
        self.assertEqual(summary["totalCalories"], 2200)
        self.assertIsNone(summary["stress"])
        self.assertEqual(summary["heartRateAverage"], 70)
        self.assertEqual(summary["sleepSeconds"], 25200)  # awake time is never added
        self.assertEqual(summary["sleepStartTime"], "2026-01-01T00:00:00Z")
        self.assertEqual(summary["weightKg"], 66)
        self.assertEqual(summary["bodyBattery"], 20)  # latest point, not maximum
        self.assertEqual(summary["hrvMs"], 42)
        self.assertEqual(summary["workoutSeconds"], 0)
        json.dumps(value, allow_nan=False)

    def test_missing_workouts_is_not_zero(self):
        value = worker.normalize_day("2026-01-01", {}, {}, {}, {}, {}, [], None, ["activities"])
        self.assertIsNone(value["summary"]["workoutSeconds"])
        self.assertIsNone(value["summary"]["activeCalories"])
        self.assertIsNone(value["summary"]["weightKg"])

    def test_optional_unsupported_metric_differs_from_failed_sync(self):
        warnings = []
        self.assertIsNone(worker.optional_call(lambda: (_ for _ in ()).throw(HttpError(404)), "hrv", warnings))
        self.assertEqual(warnings, ["hrv"])
        for error in (HttpError(401), HttpError(429), HttpError(500), GarminConnectConnectionError()):
            with self.assertRaises(type(error)):
                worker.optional_call(lambda: (_ for _ in ()).throw(error), "sleep", [])

    def test_wrapped_http_status_is_recognized(self):
        error = GarminConnectConnectionError("redacted")
        error.__cause__ = HttpError(404)
        self.assertEqual(worker.status_code(error), 404)
        self.assertEqual(worker.classify(GarminConnectTooManyRequestsError()), "RATE_LIMIT")
        self.assertEqual(worker.classify(GarminConnectAuthenticationError(), sync=True), "SESSION_EXPIRED")
        self.assertEqual(worker.status_code(GarminConnectConnectionError("API Error 404 - secret body")), 404)
        self.assertEqual(worker.classify(GarminConnectConnectionError("API Error 403 - secret body"), sync=True), "SESSION_EXPIRED")
        self.assertEqual(worker.classify(GarminConnectConnectionError("API Error 429 - secret body")), "RATE_LIMIT")

    def test_no_heart_rate_samples_is_a_gap_not_a_network_failure(self):
        warnings = []
        self.assertIsNone(worker.optional_call(lambda: (_ for _ in ()).throw(GarminConnectConnectionError("No heart rate data received")), "heart_rate", warnings))
        self.assertEqual(warnings, ["heart_rate"])

    def test_check_is_local_only(self):
        output = io.StringIO()
        with patch("sys.argv", ["garmin_worker.py", "--check"]), patch("sys.stdout", output), patch.object(worker, "garmin_factory", return_value=MagicMock()) as factory:
            self.assertEqual(worker.main(), 0)
        self.assertEqual(json.loads(output.getvalue()), {"type": "ready", "version": "0.3.17"})
        factory.assert_called_once_with()

    def test_dates_bounded_future_and_non_iso_rejected(self):
        for start, end in (("2026-1-01", "2026-01-02"), ("2026-01-02", "2026-01-01"), ("2026-01-01", "2026-04-01"), ("2099-01-01", "2099-01-02")):
            with self.assertRaises(worker.WorkerError):
                worker.date_range({"startDate": start, "endDate": end})

    def test_activity_pagination_rejects_duplicates(self):
        client = MagicMock()
        client.connectapi.side_effect = [[{"activityId": index} for index in range(100)], [{"activityId": 0}]]
        with self.assertRaises(worker.WorkerError):
            worker.activity_range(client, "2026-01-01", "2026-01-02")
        self.assertEqual(client.connectapi.call_count, 2)

    def test_activity_exact_limit_and_overflow_are_distinguished(self):
        client = MagicMock()
        pages = [[{"activityId": offset + item} for item in range(100)] for offset in range(0, worker.MAX_ACTIVITIES, 100)]
        client.connectapi.side_effect = pages + [[]]
        self.assertEqual(len(worker.activity_range(client, "2026-01-01", "2026-01-02")), worker.MAX_ACTIVITIES)
        client.connectapi.side_effect = pages + [[{"activityId": worker.MAX_ACTIVITIES + 1}]]
        with self.assertRaises(worker.WorkerError):
            worker.activity_range(client, "2026-01-01", "2026-01-02")

    def test_sync_emits_every_requested_day_and_complete_done(self):
        self.path.mkdir()
        (self.path / "garmin_tokens.json").write_text("fixture", encoding="utf-8")
        client = MagicMock()
        client.connectapi.return_value = []
        client.get_body_battery.return_value = []
        client.get_stats.side_effect = lambda day: {"calendarDate": day, "totalSteps": 10}
        client.get_sleep_data.return_value = {}
        client.get_heart_rates.return_value = {}
        client.get_hrv_data.return_value = None
        client.get_daily_weigh_ins.return_value = {}
        output = io.StringIO()
        with contextlib.redirect_stdout(output):
            worker.sync(self.command("sync"), MagicMock(return_value=client), pause=lambda _: None)
        events = [json.loads(line) for line in output.getvalue().splitlines()]
        self.assertEqual([event.get("date") for event in events[:-1]], ["2026-01-01", "2026-01-02"])
        self.assertEqual(events[-1], {"type": "done", "startDate": "2026-01-01", "endDate": "2026-01-02", "dayCount": 2})
        self.assertEqual(events[0]["data"]["warnings"], ["hrv"])

    def test_main_error_is_redacted_and_library_stdout_cannot_break_protocol(self):
        def leaking_connect(command, factory):
            print("library-private-value")
            raise GarminConnectAuthenticationError("secret-provider-response")
        output, error_output = io.StringIO(), io.StringIO()
        with patch("sys.stdin", io.StringIO(json.dumps(self.command()) + "\n")), patch("sys.stdout", output), patch("sys.stderr", error_output), patch.object(worker, "garmin_factory", return_value=MagicMock()), patch.object(worker, "connect", side_effect=leaking_connect):
            self.assertEqual(worker.main(), 1)
        self.assertEqual(json.loads(output.getvalue()), {"type": "error", "code": "AUTH"})
        self.assertNotIn("secret-provider-response", output.getvalue() + error_output.getvalue())
        self.assertNotIn("library-private-value", output.getvalue())

    def test_details_preserve_health_fields_and_remove_nested_secrets(self):
        record = worker.detail_record({"score": 0, "unknownMetric": 42, "samples": [[1, -1], [2, 80]],
                                       "nested": {"accessToken": "secret", "email": "private", "bodyFat": 12.5}})
        self.assertEqual(record["state"], "available")
        self.assertEqual(record["value"]["unknownMetric"], 42)
        self.assertEqual(record["value"]["score"], 0)
        self.assertEqual(record["value"]["nested"], {"bodyFat": 12.5})
        self.assertNotIn("secret", json.dumps(record))
        self.assertEqual(worker.detail_record(None)["state"], "unavailable")

    def test_detail_bounds_are_explicit_and_valid_json(self):
        record = worker.detail_record({"samples": [[i, i % 100] for i in range(12000)], "score": 55})
        self.assertTrue(record["truncated"])
        self.assertEqual(record["value"]["score"], 55)
        self.assertLessEqual(len(record["value"]["samples"]), 5000)
        self.assertLessEqual(len(json.dumps(record["value"], ensure_ascii=True)), 120000)
        self.assertIsNone(worker.detail_record({"value": float("nan")})["value"]["value"])
        nested = {"score": 42}
        for _ in range(30):
            nested = {"nested": nested}
        bounded = worker.detail_record(nested)
        self.assertTrue(bounded["truncated"])
        node = bounded["value"]
        for _ in range(12):
            node = node["nested"]
        self.assertIsNone(node)

    def detailed_client(self):
        self.path.mkdir()
        (self.path / "garmin_tokens.json").write_text("fixture", encoding="utf-8")
        client = MagicMock()
        client.connectapi.return_value = []
        client.get_body_battery.return_value = []
        client.get_stats.side_effect = lambda day: {"calendarDate": day, "totalSteps": 10}
        for method in ["get_sleep_data", "get_heart_rates", "get_hrv_data", "get_daily_weigh_ins", *worker.DETAIL_METHODS.values(),
                       "get_body_composition", "get_blood_pressure", "get_race_predictions", "get_running_tolerance",
                       "get_functional_threshold_power_range", "get_lactate_threshold"]:
            getattr(client, method).return_value = {"score": 50, "newProviderField": 88}
        return client

    def test_full_sync_collects_all_supported_categories_and_exact_daily_ranges(self):
        client = self.detailed_client()
        command = self.command("sync") | {"includeDetails": True}
        output = io.StringIO()
        with contextlib.redirect_stdout(output):
            worker.sync(command, MagicMock(return_value=client), pause=lambda _: None)
        events = [json.loads(line) for line in output.getvalue().splitlines()]
        details = events[0]["data"]["details"]
        self.assertEqual(len(details), 33)
        self.assertEqual(details["spo2"]["value"]["newProviderField"], 88)
        client.get_race_predictions.assert_any_call("2026-01-01", "2026-01-01", "daily")
        client.get_running_tolerance.assert_any_call("2026-01-01", "2026-01-01", "daily")
        client.get_functional_threshold_power_range.assert_any_call("2026-01-01", "2026-01-01", sport="CYCLING")
        client.get_lactate_threshold.assert_any_call(latest=False, start_date="2026-01-01", end_date="2026-01-01", aggregation="daily")
        self.assertEqual(events[-1]["type"], "done")

    def test_full_sync_optional_unsupported_gap_but_auth_failure_aborts(self):
        client = self.detailed_client()
        command = self.command("sync") | {"includeDetails": True}
        client.get_spo2_data.side_effect = HttpError(404)
        output = io.StringIO()
        with contextlib.redirect_stdout(output):
            worker.sync(command, MagicMock(return_value=client), pause=lambda _: None)
        self.assertEqual(json.loads(output.getvalue().splitlines()[0])["data"]["details"]["spo2"]["state"], "unavailable")
        client.get_spo2_data.side_effect = HttpError(401)
        with contextlib.redirect_stdout(io.StringIO()), self.assertRaises(HttpError):
            worker.sync(command, MagicMock(return_value=client), pause=lambda _: None)

    def test_activity_detail_is_only_fetched_for_a_returned_activity(self):
        client = self.detailed_client()
        command = self.command("sync") | {"endDate": "2026-01-01", "activityId": "123"}
        with contextlib.redirect_stdout(io.StringIO()), self.assertRaises(worker.WorkerError):
            worker.sync(command, MagicMock(return_value=client), pause=lambda _: None)
        client.get_activity.assert_not_called()
        client.connectapi.return_value = [{"activityId": 123, "startTimeLocal": "2026-01-01T08:00:00", "startTimeGMT": "2026-01-01T00:00:00", "duration": 100}]
        for method in [*worker.ACTIVITY_METHODS.values(), "get_activity_details"]:
            getattr(client, method).return_value = {"metric": 12}
        output = io.StringIO()
        with contextlib.redirect_stdout(output):
            worker.sync(command, MagicMock(return_value=client), pause=lambda _: None)
        activity = json.loads(output.getvalue().splitlines()[0])["data"]["activities"][0]
        self.assertEqual(len(activity["details"]), 10)
        client.get_activity_details.assert_called_once_with("123", maxchart=1000, maxpoly=1000)
        client.get_activity.return_value = {"activityId": 999}
        with contextlib.redirect_stdout(io.StringIO()), self.assertRaises(worker.WorkerError):
            worker.sync(command, MagicMock(return_value=client), pause=lambda _: None)

    def test_protocol_size_and_invalid_json(self):
        for line in ("x" * (worker.MAX_INPUT + 1), "{", "[]", ""):
            with patch("sys.stdin", io.StringIO(line)), self.assertRaises(worker.WorkerError):
                worker.read_command()


if __name__ == "__main__":
    unittest.main()
