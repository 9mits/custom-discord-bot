"""Where each kind of Minecraft log ends up.

The point of routing is that an upgrade changes nothing until somebody changes
something, so most of what is pinned here is the inheritance: a server that has
never opened `/mgxadmin logs` must keep writing exactly where it wrote before.
"""

import asyncio
import re
import unittest
from pathlib import Path
from types import SimpleNamespace
from unittest.mock import AsyncMock

from minecraft_bot import logroutes
from minecraft_bot.audit import CommandAuditRecord, SOURCE_SERVER, deliver_server_event
from minecraft_bot.settings import SETTING_KEYS, MinecraftSettings


BRIDGE = Path(__file__).resolve().parents[1] / "minecraft-bridge" / "src" / "main" / "java"
ACCESS_BRIDGE = BRIDGE / "bot" / "mgx" / "accessbridge"


def settings(**overrides) -> MinecraftSettings:
    return MinecraftSettings(**overrides)


class TopicRegistryTests(unittest.TestCase):
    def test_every_topic_is_unique_and_described(self):
        keys = [topic.key for topic in logroutes.TOPICS]
        self.assertEqual(len(keys), len(set(keys)))
        for topic in logroutes.TOPICS:
            self.assertTrue(topic.label.strip(), topic.key)
            self.assertTrue(topic.description.strip(), topic.key)
            self.assertEqual(topic.key, topic.key.casefold())

    def test_discord_can_offer_every_topic_as_a_choice(self):
        # A slash-command choice list is capped at 25 and its descriptions at 100.
        self.assertLessEqual(len(logroutes.TOPICS), 25)
        for topic in logroutes.TOPICS:
            self.assertLessEqual(len(topic.description), 100, topic.key)

    def test_the_streams_the_bot_already_had_still_name_their_old_setting(self):
        for key, fallback in (
            ("important", "critical_log_channel_id"),
            ("command", "command_log_channel_id"),
            ("application", "application_log_channel_id"),
            ("verification", "verification_log_channel_id"),
            ("session", "player_log_channel_id"),
        ):
            self.assertEqual(logroutes.BY_KEY[key].fallback, fallback)
            self.assertIn(fallback, SETTING_KEYS)

    def test_every_category_the_plugin_reports_resolves_to_a_topic(self):
        source = (ACCESS_BRIDGE / "ServerEvent.java").read_text(encoding="utf-8")
        categories = re.findall(r'CATEGORY_[A-Z_]+ = "([a-z_]+)"', source)
        self.assertGreaterEqual(len(categories), 9)
        for category in categories:
            self.assertIn(logroutes.topic_for_category(category), logroutes.BY_KEY, category)

    def test_an_unknown_category_lands_somewhere_rather_than_nowhere(self):
        # A category added to the plugin before this table catches up must still be
        # readable; silently dropping it is the failure nobody notices.
        self.assertEqual(logroutes.topic_for_category("something_new"), "world")
        self.assertEqual(logroutes.topic_for_category(""), "world")
        self.assertEqual(logroutes.topic_for_category("server"), "world")

    def test_aliases_point_at_real_topics(self):
        for alias, topic in logroutes.CATEGORY_ALIASES.items():
            self.assertIn(topic, logroutes.BY_KEY, alias)
            self.assertNotIn(alias, logroutes.BY_KEY, alias)


class InheritanceTests(unittest.TestCase):
    def test_an_unconfigured_server_writes_everything_where_it_used_to(self):
        current = settings(command_log_channel_id=10, critical_log_channel_id=20)

        for topic in ("clan", "economy", "combat", "mining", "crate", "world"):
            self.assertEqual(logroutes.resolve(current, topic), 10, topic)
        self.assertEqual(logroutes.resolve(current, "clan", important=True), 20)

    def test_a_stream_with_its_own_old_setting_keeps_it(self):
        current = settings(command_log_channel_id=10, player_log_channel_id=11)

        self.assertEqual(logroutes.resolve(current, "session"), 11)
        self.assertEqual(logroutes.resolve(current, "combat"), 10)

    def test_nothing_configured_at_all_sends_nothing(self):
        self.assertEqual(logroutes.resolve(settings(), "combat"), 0)


class RoutingTests(unittest.TestCase):
    def test_an_explicit_route_beats_what_it_would_inherit(self):
        current = settings(command_log_channel_id=10, log_routes={"combat": 55})

        self.assertEqual(logroutes.resolve(current, "combat"), 55)
        self.assertEqual(logroutes.resolve(current, "mining"), 10)

    def test_a_muted_stream_is_written_nowhere(self):
        current = settings(command_log_channel_id=10, log_routes={"mining": 0})

        self.assertEqual(logroutes.resolve(current, "mining"), 0)
        self.assertEqual(logroutes.resolve(current, "combat"), 10)

    def test_muting_important_returns_those_lines_to_their_own_stream(self):
        # Access-changing events must not become droppable by a routing decision
        # that reads like tidying up.
        current = settings(
            command_log_channel_id=10,
            critical_log_channel_id=20,
            log_routes={"important": 0, "clan": 33},
        )

        self.assertEqual(logroutes.resolve(current, "clan", important=True), 33)
        self.assertEqual(logroutes.resolve(current, "combat", important=True), 10)

    def test_an_important_route_beats_the_topic_route(self):
        current = settings(log_routes={"important": 99, "combat": 55})

        self.assertEqual(logroutes.resolve(current, "combat", important=True), 99)
        self.assertEqual(logroutes.resolve(current, "combat"), 55)

    def test_a_category_alias_routes_as_its_topic(self):
        current = settings(command_log_channel_id=10, log_routes={"crate": 77})

        self.assertEqual(logroutes.resolve(current, "lootbox"), 77)


class EditingTests(unittest.TestCase):
    def test_a_channel_routes_a_stream_and_none_puts_it_back(self):
        current = settings(command_log_channel_id=10)

        routed = logroutes.with_route(current, "combat", 55)
        self.assertEqual(routed, {"combat": 55})

        cleared = logroutes.with_route(settings(log_routes=routed), "combat", None)
        self.assertEqual(cleared, {})

    def test_zero_mutes_rather_than_clearing(self):
        routed = logroutes.with_route(settings(), "mining", 0)

        self.assertEqual(routed, {"mining": 0})
        self.assertEqual(logroutes.resolve(settings(log_routes=routed), "mining"), 0)

    def test_an_unknown_stream_is_refused(self):
        with self.assertRaises(ValueError):
            logroutes.with_route(settings(), "nonsense", 5)

    def test_clearing_a_stream_that_was_never_routed_is_not_an_error(self):
        self.assertEqual(logroutes.with_route(settings(), "combat", None), {})


class StoredTableTests(unittest.TestCase):
    def test_routes_are_a_persisted_setting(self):
        self.assertIn("log_routes", SETTING_KEYS)
        self.assertEqual(MinecraftSettings().log_routes, {})

    def test_a_table_round_trips_through_stored_values(self):
        stored = {"log_routes": {"combat": 55, "mining": 0}}

        current = MinecraftSettings.from_sources(SimpleNamespace(), stored)

        self.assertEqual(current.log_routes, {"combat": 55, "mining": 0})
        self.assertEqual(current.persistent_values()["log_routes"], {"combat": 55, "mining": 0})

    def test_a_corrupt_table_is_dropped_rather_than_raised(self):
        # Settings round-trip as JSON, so this has to survive a table written by an
        # older build. Refusing to start is a worse answer than logging somewhere.
        self.assertEqual(logroutes.normalize(None), {})
        self.assertEqual(logroutes.normalize("combat"), {})
        self.assertEqual(
            logroutes.normalize({"combat": "55", "gone": 1, "mining": "x", "world": -3}),
            {"combat": 55},
        )

    def test_a_topic_is_matched_case_insensitively(self):
        self.assertEqual(logroutes.normalize({"COMBAT": 5}), {"combat": 5})


class LabelTests(unittest.TestCase):
    def test_a_label_says_whether_a_channel_was_chosen_or_inherited(self):
        current = settings(command_log_channel_id=10, log_routes={"combat": 55, "mining": 0})

        self.assertEqual(logroutes.destination_label(current, "combat"), "<#55>")
        self.assertEqual(logroutes.destination_label(current, "mining"), "Muted")
        self.assertEqual(logroutes.destination_label(current, "crate"), "<#10> (inherited)")
        self.assertEqual(logroutes.destination_label(settings(), "crate"), "Not set")

    def test_the_summary_covers_every_stream(self):
        rows = logroutes.summary_rows(settings())

        self.assertEqual(len(rows), len(logroutes.TOPICS))


class ServerEventDeliveryTests(unittest.TestCase):
    def _client(self, **channels):
        return SimpleNamespace(
            settings=settings(**channels),
            data=SimpleNamespace(record_command_log=AsyncMock()),
            _send_configured_log=AsyncMock(),
        )

    def _deliver(self, client, *, event, category, important=False):
        record = CommandAuditRecord(
            source=SOURCE_SERVER,
            command=event,
            user_id=0,
            user_label="TestPlayer",
            correlation_id=category,
            risk="destructive" if important else "read_only",
        )
        asyncio.run(deliver_server_event(
            client,
            record,
            minecraft_uuid="123e4567-e89b-12d3-a456-426614174000",
            minecraft_username="TestPlayer",
            summary="something happened",
        ))
        return [call.args[0] for call in client._send_configured_log.await_args_list]

    def test_each_kind_of_action_follows_its_own_route(self):
        client = self._client(
            command_log_channel_id=10,
            log_routes={"combat": 55, "mining": 56, "crate": 57},
        )

        self.assertEqual(self._deliver(client, event="player_kill", category="combat"), [55])
        client._send_configured_log.reset_mock()
        self.assertEqual(self._deliver(client, event="ores_mined", category="mining"), [56])
        client._send_configured_log.reset_mock()
        self.assertEqual(self._deliver(client, event="crate_reward", category="crate"), [57])
        client._send_configured_log.reset_mock()
        self.assertEqual(self._deliver(client, event="clan_join", category="clan"), [10])

    def test_a_muted_stream_is_still_persisted(self):
        # The routing table decides what is read, not what is kept: the command log
        # is the audit trail and muting a channel must not erase it.
        client = self._client(command_log_channel_id=10, log_routes={"mining": 0})

        self.assertEqual(self._deliver(client, event="ores_mined", category="mining"), [])
        client.data.record_command_log.assert_awaited_once()


if __name__ == "__main__":
    unittest.main()
