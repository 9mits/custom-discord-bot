import unittest
from datetime import datetime, timezone

from core.services import (
    DEFAULT_SCHEMA_VERSION,
    OFFENSE_LOOKBACK_DAYS,
    calculate_offense_punishment,
    count_recent_offenses,
    export_config_payload,
    get_native_automod_settings,
    has_capability,
    import_config_payload,
    resolve_native_automod_policy,
    resolve_offense_punishment,
    run_schema_migrations,
)


FIXED_NOW = datetime(2026, 7, 8, tzinfo=timezone.utc)


def _record(days_ago: int) -> dict:
    stamp = datetime(2026, 7, 8, tzinfo=timezone.utc).timestamp() - days_ago * 86400
    return {"timestamp": datetime.fromtimestamp(stamp, tz=timezone.utc).isoformat()}


class MbxServicesTests(unittest.TestCase):
    def test_count_recent_offenses_respects_window(self):
        history = [_record(1), _record(89), _record(120)]
        self.assertEqual(count_recent_offenses(history, now=FIXED_NOW), 2)

    def test_count_recent_offenses_skips_invalid_records(self):
        history = [
            {"timestamp": "not-a-date"},
            {"reason": "no timestamp"},
            "not-a-dict",
            {"timestamp": "2026-07-01T00:00:00"},  # naive, treated as UTC, in window
        ]
        self.assertEqual(count_recent_offenses(history, now=FIXED_NOW), 1)

    def test_resolve_offense_punishment_ladder_tiers(self):
        self.assertEqual(resolve_offense_punishment(0, 0, 60), (0, False, "First Offense"))
        self.assertEqual(resolve_offense_punishment(1, 0, 60), (60, True, "Repeat Offense"))
        self.assertEqual(resolve_offense_punishment(3, 0, 60), (120, True, "Repeat Offense x2"))
        self.assertEqual(resolve_offense_punishment(5, 0, 60), (240, True, "Repeat Offense x4"))
        self.assertEqual(resolve_offense_punishment(7, 0, 60), (-1, True, "Auto Ban"))

    def test_resolve_offense_punishment_ban_rule_wins(self):
        duration, escalated, label = resolve_offense_punishment(5, -1, 40320)
        self.assertEqual(duration, -1)
        self.assertFalse(escalated)
        self.assertEqual(label, "Ban Rule")

    def test_resolve_offense_punishment_escalated_ban_passthrough(self):
        self.assertEqual(resolve_offense_punishment(1, 0, -1), (-1, True, "Repeat Offense"))

    def test_resolve_offense_punishment_caps_to_ban(self):
        duration, escalated, _ = resolve_offense_punishment(3, 10080, 40320)
        self.assertEqual(duration, -1)
        self.assertTrue(escalated)

    def test_calculate_offense_punishment_labels_prior_count(self):
        history = [_record(1), _record(2), _record(3)]
        duration, escalated, label = calculate_offense_punishment({"base": 0, "escalated": 60}, history, now=FIXED_NOW)
        self.assertEqual(duration, 120)
        self.assertTrue(escalated)
        self.assertIn(f"3 prior in {OFFENSE_LOOKBACK_DAYS}d", label)

    def test_export_config_payload_drops_legacy_matrix(self):
        payload = export_config_payload({"escalation_matrix": [{"minimum_points": 0}], "guild_id": 1})
        self.assertNotIn("escalation_matrix", payload)
        self.assertEqual(payload["guild_id"], 1)

    def test_import_config_payload_strips_bot_token(self):
        merged, warnings = import_config_payload({"feature_flags": {}}, {"bot_token": "secret", "modmail_sla_minutes": 45})
        self.assertNotIn("bot_token", merged)
        self.assertEqual(merged["modmail_sla_minutes"], 45)
        self.assertTrue(warnings)

    def test_import_config_payload_ignores_legacy_matrix(self):
        merged, warnings = import_config_payload({"feature_flags": {}}, {"escalation_matrix": [{"minimum_points": 0}]})
        self.assertNotIn("escalation_matrix", merged)
        self.assertTrue(any("escalation_matrix" in warning for warning in warnings))

    def test_native_automod_settings_normalize_rule_overrides(self):
        config = {
            "native_automod": {
                "enabled": True,
                "warning_dm_enabled": False,
                "rule_overrides": {
                    "123": {
                        "enabled": True,
                        "threshold": "5",
                        "window_minutes": "60",
                        "duration_minutes": "120",
                        "punishment_type": "timeout",
                    }
                },
            }
        }
        settings = get_native_automod_settings(config)
        policy = resolve_native_automod_policy(config, rule_id=123)
        self.assertTrue(settings["enabled"])
        self.assertFalse(settings["warning_dm_enabled"])
        self.assertTrue(policy["enabled"])
        self.assertEqual(len(policy["steps"]), 1)
        self.assertEqual(policy["steps"][0]["threshold"], 5)
        self.assertEqual(policy["steps"][0]["window_minutes"], 60)
        self.assertEqual(policy["steps"][0]["duration_minutes"], 120)
        self.assertEqual(policy["steps"][0]["punishment_type"], "timeout")

    def test_native_automod_policy_supports_multiple_steps(self):
        config = {
            "native_automod": {
                "rule_overrides": {
                    "123": {
                        "enabled": True,
                        "reason_template": "Repeated slur filter hits",
                        "steps": [
                            {"threshold": 6, "window_minutes": 1440, "punishment_type": "ban"},
                            {"threshold": 3, "window_minutes": 60, "punishment_type": "timeout", "duration_minutes": 60},
                            {"threshold": 5, "window_minutes": 720, "punishment_type": "timeout", "duration_minutes": 720},
                        ],
                    }
                }
            }
        }
        policy = resolve_native_automod_policy(config, rule_id=123)
        self.assertTrue(policy["enabled"])
        self.assertEqual(policy["reason_template"], "Repeated slur filter hits")
        self.assertEqual([step["threshold"] for step in policy["steps"]], [3, 5, 6])
        self.assertEqual(policy["steps"][1]["duration_minutes"], 720)
        self.assertEqual(policy["steps"][2]["duration_minutes"], -1)

    def test_native_automod_settings_tolerate_invalid_numeric_values(self):
        config = {
            "native_automod": {
                "default_escalation": {
                    "threshold": "invalid",
                    "window_minutes": None,
                    "duration_minutes": "oops",
                    "punishment_type": "not-real",
                },
                "rule_overrides": {
                    "123": {
                        "enabled": True,
                        "threshold": "invalid",
                        "window_minutes": "bad",
                        "duration_minutes": "oops",
                        "punishment_type": "also-not-real",
                    }
                },
                "immunity_roles": ["1", "bad", 2],
            }
        }

        settings = get_native_automod_settings(config)
        policy = resolve_native_automod_policy(config, rule_id=123)

        self.assertEqual(settings["default_escalation"]["threshold"], 3)
        self.assertEqual(settings["default_escalation"]["window_minutes"], 1440)
        self.assertEqual(settings["default_escalation"]["duration_minutes"], 0)
        self.assertEqual(settings["default_escalation"]["punishment_type"], "warn")
        self.assertEqual(settings["immunity_roles"], [1, 2])
        self.assertEqual(policy["steps"][0]["threshold"], 3)
        self.assertEqual(policy["steps"][0]["window_minutes"], 1440)
        self.assertEqual(policy["steps"][0]["duration_minutes"], 0)
        self.assertEqual(policy["steps"][0]["punishment_type"], "warn")

    def test_has_capability_ignores_invalid_role_ids(self):
        config = {
            "role_admin": "not-a-role",
            "role_owner": "still-bad",
            "role_community_manager": None,
        }
        self.assertFalse(has_capability([123], "config_panel", config))

    def test_run_schema_migrations_initializes_missing_structures(self):
        config = {}
        punishments = {"1": [{"case_id": 1, "type": "warn", "timestamp": "2026-01-01T00:00:00+00:00"}]}
        modmail = {"1": {"status": "open", "created_at": "2026-01-01T00:00:00+00:00"}}
        changed, notes = run_schema_migrations(config, punishments, modmail)
        self.assertTrue(changed)
        self.assertEqual(config["schema_version"], DEFAULT_SCHEMA_VERSION)
        self.assertIn("feature_flags", config)
        self.assertIn("native_automod", config)
        self.assertNotIn("escalation_matrix", config)
        self.assertIn("action_id", punishments["1"][0])
        self.assertIn("priority", modmail["1"])
        self.assertTrue(notes)


if __name__ == "__main__":
    unittest.main()
