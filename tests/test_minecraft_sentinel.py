"""Sentinel's Discord side, the log digests, and the log explorer.

Sentinel reports abuse by people who can reach every admin panel, so most of what is
pinned here is that nothing on this side can quietly lose an incident: it is stored
before it is sent, the Security stream cannot be muted, a data wipe leaves it alone,
and a flood of findings still posts every card.
"""

import asyncio
import re
import tempfile
import unittest
from pathlib import Path
from types import SimpleNamespace
from unittest.mock import AsyncMock

import discord

from minecraft_bot import logroutes
from minecraft_bot.audit import CommandAuditRecord, SOURCE_COMMAND, SOURCE_SERVER, deliver, deliver_server_event
from minecraft_bot.data import MinecraftDataManager
from minecraft_bot.logdigest import LogDigest
from minecraft_bot.logexplorer import STREAMS, filters_for, row_line
from minecraft_bot.sentinel import (
    PING_LIMIT,
    STATUS_ACKNOWLEDGED,
    STATUS_FALSE_POSITIVE,
    Incident,
    SentinelFeed,
    category_for,
    dashboard_embed,
    incident_embed,
    incident_view,
)
from minecraft_bot.settings import MinecraftSettings


PLAYER = "123e4567-e89b-12d3-a456-426614174000"
JAVA = Path(__file__).resolve().parents[1] / "minecraft-bridge" / "src" / "main" / "java" / "bot" / "mgx" / "accessbridge"


def plugin_details(**overrides):
    details = {
        "severity": "CRITICAL",
        "incident": "a1b2c3d4",
        "risk": "42.5",
        "evidence_2": "Unexplained: 1,401 Shards",
        "evidence_1": "Holding 1,403 Shards, up from a recent peak of 2 Shards",
        "evidence_10": "tenth line sorts after the ninth",
    }
    details.update(overrides)
    return details


def make_incident(**overrides):
    fields = dict(
        actor_uuid=PLAYER,
        actor_name="MinimumOrc",
        summary="Unexplained Shards gain",
        details=plugin_details(),
        occurred_at=1_700_000_000,
        discord_id=4242,
    )
    fields.update(overrides)
    return Incident.from_event("security_unexplained_gain", **fields)


class IncidentParsingTests(unittest.TestCase):
    def test_a_plugin_event_becomes_an_incident_with_ordered_evidence(self):
        incident = make_incident()

        self.assertEqual(incident.incident_id, "a1b2c3d4")
        self.assertEqual(incident.rule, "unexplained_gain")
        self.assertEqual(incident.severity, "CRITICAL")
        self.assertEqual(incident.risk, 42.5)
        self.assertEqual(incident.discord_id, "4242")
        self.assertEqual(incident.evidence[0], "Holding 1,403 Shards, up from a recent peak of 2 Shards")
        self.assertEqual(incident.evidence[-1], "tenth line sorts after the ninth")
        self.assertEqual(category_for(incident.rule), "Duplication")

    def test_ordinary_events_are_not_incidents(self):
        self.assertIsNone(Incident.from_event(
            "crate_open", actor_uuid="", actor_name="", summary="", details={}, occurred_at=1,
        ))

    def test_bad_values_degrade_rather_than_raise(self):
        incident = make_incident(details={"severity": "apocalyptic", "risk": "lots", "incident": "../x"},
                                 discord_id=0)
        self.assertEqual(incident.severity, "MEDIUM")
        self.assertEqual(incident.risk, 0.0)
        self.assertEqual(incident.incident_id, "x")
        self.assertEqual(incident.discord_id, "")

    def test_every_rule_the_plugin_reports_has_a_category(self):
        source = (JAVA / "SentinelService.java").read_text(encoding="utf-8") \
            + (JAVA / "SentinelEngine.java").read_text(encoding="utf-8")
        rules = set(re.findall(r'new (?:SentinelEngine\.)?Finding\("([a-z_]+)"', source))
        self.assertGreaterEqual(len(rules), 12)
        for rule in rules:
            self.assertNotEqual(category_for(rule), "Security", rule)
        self.assertEqual(category_for("command_items"), "Command")


class IncidentCardTests(unittest.TestCase):
    def test_the_card_carries_severity_evidence_and_the_player(self):
        embed = incident_embed(make_incident())
        text = f"{embed.author.name} {embed.title} {embed.description}"

        self.assertIn("CRITICAL", text)
        self.assertIn("Duplication", text)
        self.assertIn("1,401 Shards", text)
        self.assertIn("<@4242>", text)
        self.assertIn("#a1b2c3d4", text)
        self.assertEqual(embed.colour.value, 0xE74C3C)
        self.assertTrue(embed.thumbnail.url)

    def test_an_open_card_offers_acknowledge_and_false_positive(self):
        labels = [item.item.label for item in incident_view(make_incident()).children]
        self.assertEqual(labels, ["Acknowledge", "False positive", "Player history"])

    def test_a_handled_card_offers_reopen_and_names_the_handler(self):
        incident = make_incident()
        incident.status = STATUS_ACKNOWLEDGED
        incident.handled_by = "77"
        incident.handled_at = 1_700_000_100
        labels = [item.item.label for item in incident_view(incident).children]
        self.assertEqual(labels, ["Reopen", "Player history"])
        self.assertIn("Acknowledged by <@77>", incident_embed(incident).description)

    def test_card_custom_ids_are_short_enough_for_discord(self):
        for item in incident_view(make_incident()).children:
            self.assertLessEqual(len(item.item.custom_id), 100)


class FakeChannel:
    def __init__(self, guild):
        self.guild = guild
        self.sent = []

    async def send(self, **kwargs):
        self.sent.append(kwargs)
        return SimpleNamespace(id=900 + len(self.sent))


class SentinelFeedTests(unittest.IsolatedAsyncioTestCase):
    def feed(self, *, role_id=None, security_channel=55):
        self.role = SimpleNamespace(id=321, mention="<@&321>")
        self.owner = SimpleNamespace(id=1, mention="<@1>")
        guild = SimpleNamespace(
            get_role=lambda value: self.role if int(value) == 321 else None,
            owner=self.owner,
        )
        self.channel = FakeChannel(guild)
        stored = {}

        async def record(row):
            if row["incident_id"] in stored:
                return False
            stored[row["incident_id"]] = row
            return True

        bot = SimpleNamespace(
            settings=MinecraftSettings(log_routes={"security": security_channel}),
            data=SimpleNamespace(
                record_security_incident=AsyncMock(side_effect=record),
                set_security_message=AsyncMock(),
                get_config=AsyncMock(return_value=role_id),
            ),
            _configured_channel=AsyncMock(return_value=self.channel),
            _send_configured_log=AsyncMock(),
        )
        return SentinelFeed(bot, clock=lambda: 1000.0), bot

    async def test_a_critical_incident_is_stored_then_posted_with_a_role_ping(self):
        feed, bot = self.feed(role_id=321)

        self.assertTrue(await feed.handle(make_incident()))

        bot.data.record_security_incident.assert_awaited_once()
        self.assertEqual(self.channel.sent[0]["content"], "<@&321>")
        self.assertIsNotNone(self.channel.sent[0]["view"])
        bot.data.set_security_message.assert_awaited_once_with("a1b2c3d4", 55, 901)

    async def test_a_repeat_delivery_is_not_posted_twice(self):
        feed, _bot = self.feed()
        await feed.handle(make_incident())
        self.assertFalse(await feed.handle(make_incident()))
        self.assertEqual(len(self.channel.sent), 1)

    async def test_medium_incidents_never_ping(self):
        feed, _bot = self.feed(role_id=321)
        await feed.handle(make_incident(details=plugin_details(severity="MEDIUM")))
        self.assertIsNone(self.channel.sent[0]["content"])

    async def test_without_a_role_only_critical_reaches_the_owner(self):
        feed, _bot = self.feed()
        await feed.handle(make_incident(details=plugin_details(severity="HIGH", incident="b1")))
        await feed.handle(make_incident(details=plugin_details(severity="CRITICAL", incident="b2")))
        self.assertEqual([sent["content"] for sent in self.channel.sent], [None, "<@1>"])

    async def test_a_flood_still_posts_every_card_but_stops_pinging(self):
        feed, _bot = self.feed(role_id=321)
        for index in range(PING_LIMIT + 3):
            await feed.handle(make_incident(details=plugin_details(incident=f"f{index}")))
        contents = [sent["content"] for sent in self.channel.sent]
        self.assertEqual(len(contents), PING_LIMIT + 3)
        self.assertEqual(contents.count("<@&321>"), PING_LIMIT)

    async def test_an_unreachable_channel_queues_the_card(self):
        feed, bot = self.feed()
        bot._configured_channel = AsyncMock(return_value=None)
        await feed.handle(make_incident())
        bot._send_configured_log.assert_awaited_once()


class SecurityRoutingTests(unittest.TestCase):
    def test_security_follows_the_important_log_until_routed(self):
        current = MinecraftSettings(critical_log_channel_id=20, command_log_channel_id=10)
        self.assertEqual(logroutes.resolve(current, "security"), 20)

    def test_security_cannot_be_muted_by_the_panel_or_a_stored_table(self):
        current = MinecraftSettings(critical_log_channel_id=20)
        with self.assertRaises(ValueError):
            logroutes.with_route(current, "security", logroutes.MUTED)
        self.assertEqual(logroutes.normalize({"security": 0, "mining": 0}), {"mining": 0})
        self.assertEqual(logroutes.resolve(MinecraftSettings(critical_log_channel_id=20,
                                                             log_routes={"security": 0}), "security"), 20)

    def test_every_topic_belongs_to_a_panel_group(self):
        for topic in logroutes.TOPICS:
            self.assertIn(topic.group, logroutes.GROUPS, topic.key)


class SecurityIncidentStoreTests(unittest.IsolatedAsyncioTestCase):
    async def asyncSetUp(self):
        self.directory = tempfile.TemporaryDirectory()
        self.data = MinecraftDataManager(Path(self.directory.name) / "minecraft.db")
        await self.data.open()

    async def asyncTearDown(self):
        await self.data.close()
        self.directory.cleanup()

    async def store(self, incident_id, *, severity="HIGH", player=PLAYER, name="Orc", risk=10.0, at=5_000):
        row = make_incident(details=plugin_details(incident=incident_id, severity=severity, risk=str(risk)),
                            actor_uuid=player, actor_name=name, occurred_at=at).as_row()
        return await self.data.record_security_incident(row)

    async def test_incidents_round_trip_and_ignore_duplicates(self):
        self.assertTrue(await self.store("one"))
        self.assertFalse(await self.store("one"))
        row = await self.data.get_security_incident("one")
        self.assertEqual(row["evidence"][0], "Holding 1,403 Shards, up from a recent peak of 2 Shards")
        self.assertEqual(row["status"], "OPEN")

    async def test_status_filters_counts_and_top_players(self):
        await self.store("c1", severity="CRITICAL", risk=60)
        await self.store("h1", severity="HIGH", risk=20, player="other", name="Steve")
        await self.store("m1", severity="MEDIUM", risk=4, at=100)
        await self.data.set_security_status("h1", STATUS_FALSE_POSITIVE, 77, now=6_000)

        counts = await self.data.security_severity_counts(since=1_000)
        self.assertEqual(counts["CRITICAL"], {"total": 1, "open": 1})
        self.assertEqual(counts["HIGH"], {"total": 1, "open": 0})
        self.assertNotIn("MEDIUM", counts)

        open_rows = await self.data.list_security_incidents(since=0, status="OPEN")
        self.assertEqual([row["incident_id"] for row in open_rows], ["c1", "m1"])
        serious = await self.data.count_security_incidents(since=0, severities=("HIGH", "CRITICAL"))
        self.assertEqual(serious, 2)

        top = await self.data.security_top_players(since=0)
        self.assertEqual([row["player_name"] for row in top], ["Orc"], "false positives do not rank a player")

        embed, pages = await dashboard_embed(self.data, period="week", severity="all", status="open", now=6_000)
        self.assertEqual(pages, 1)
        self.assertIn("1** high or critical incident still open", embed.description)

    async def test_a_data_wipe_keeps_the_security_trail(self):
        await self.store("keep")
        await self.data.wipe_all_data(actor_id=9)
        self.assertIsNotNone(await self.data.get_security_incident("keep"))

    async def test_the_command_log_filters_by_stream_importance_and_player(self):
        for command, category, risk, name in (
            ("ores_mined", "ore", "read_only", "Steve"),
            ("clan_disband", "clan", "destructive", "Alex"),
            ("player_kill", "pvp", "read_only", "Steve"),
        ):
            await self.data.record_command_log(CommandAuditRecord(
                source=SOURCE_SERVER, command=command, user_id=0, user_label=name,
                correlation_id=category, risk=risk, detail=f"{name} did {command}",
            ))
        await self.data.record_command_log(CommandAuditRecord(
            source=SOURCE_COMMAND, command="mgxstaff ban", user_id=5, user_label="Mod", outcome="denied",
        ))

        async def count(stream, view="all", player=None):
            return await self.data.count_command_log(**filters_for(stream, view, player=player))

        self.assertEqual(await count("all"), 4)
        self.assertEqual(await count("discord"), 1)
        self.assertEqual(await count("mining"), 1, "the ore alias belongs to mining")
        self.assertEqual(await count("combat"), 1)
        self.assertEqual(await count("all", "important"), 2)
        self.assertEqual(await count("all", "failed"), 1)
        self.assertEqual(await count("all", player="steve"), 2)
        page_two = await self.data.list_command_log(limit=3, offset=3)
        self.assertEqual(len(page_two), 1)
        self.assertIn("Steve", row_line((await self.data.list_command_log(limit=1, offset=1))[0]))


class LogExplorerTests(unittest.TestCase):
    def test_every_server_stream_is_a_real_topic(self):
        for key in STREAMS:
            if key not in ("all", "discord"):
                self.assertIn(key, logroutes.BY_KEY)

    def test_a_server_line_reads_as_a_sentence(self):
        line = row_line({"source": "server", "command": "clan_donate", "actor_label": "Steve",
                         "actor_discord_id": "0", "detail": "Donated 4,096 to MGX", "created_at": 10})
        self.assertEqual(line, "<t:10:R> **Steve** Donated 4,096 to MGX")


class LogDigestTests(unittest.IsolatedAsyncioTestCase):
    def digest(self, **kwargs):
        self.now = 0.0
        self.sent = []

        async def send(channel_id, embed):
            self.sent.append((channel_id, embed))

        return LogDigest(send, clock=lambda: self.now, **kwargs)

    async def test_lines_wait_then_arrive_as_one_digest_per_channel(self):
        digest = self.digest(flush_seconds=10)
        await digest.add(1, "Mining", "**Steve** mined 3 diamond ore", at=100)
        await digest.add(1, "Mining", "**Alex** mined 1 ancient debris", at=101)
        await digest.add(2, "Crates", "**Steve** opened a Shard Crate", at=102)
        await digest.flush()
        self.assertEqual(self.sent, [])

        self.now = 11
        await digest.flush()
        by_channel = {channel: embed for channel, embed in self.sent}
        self.assertEqual(by_channel[1].title, "Mining · 2 actions")
        self.assertIn("<t:101:T> **Alex** mined 1 ancient debris", by_channel[1].description)
        self.assertEqual(by_channel[2].title, "Crates · 1 action")

    async def test_identical_repeats_collapse_into_a_count(self):
        digest = self.digest()
        for second in range(5):
            await digest.add(1, "Mining", "**Steve** mined coal ore", key="ores|steve", at=100 + second)
        await digest.flush(force=True)
        embed = self.sent[0][1]
        self.assertEqual(embed.title, "Mining · 5 actions")
        self.assertEqual(embed.description, "<t:104:T> **Steve** mined coal ore **×5**")

    async def test_a_full_digest_is_written_without_waiting(self):
        digest = self.digest(max_lines=3)
        for index in range(3):
            await digest.add(1, "Combat", f"**P{index}** killed a zombie")
        self.assertEqual(len(self.sent), 1)
        self.assertEqual(digest.pending_lines(1), 0)

    async def test_mixed_topics_share_a_neutral_title(self):
        digest = self.digest()
        await digest.add(1, "Mining", "a")
        await digest.add(1, "Combat", "b")
        await digest.flush(force=True)
        self.assertEqual(self.sent[0][1].title, "Server Activity · 2 actions")


class DigestDeliveryTests(unittest.TestCase):
    def client(self):
        return SimpleNamespace(
            settings=MinecraftSettings(command_log_channel_id=10, critical_log_channel_id=20),
            data=SimpleNamespace(record_command_log=AsyncMock(), list_accounts_for_user=AsyncMock(return_value=[])),
            _send_configured_log=AsyncMock(),
            log_digest=SimpleNamespace(add=AsyncMock()),
        )

    def test_routine_actions_go_to_the_digest_and_important_ones_are_sent_now(self):
        client = self.client()
        routine = CommandAuditRecord(source=SOURCE_SERVER, command="ores_mined", user_id=0,
                                     user_label="Steve", correlation_id="mining")
        important = CommandAuditRecord(source=SOURCE_SERVER, command="clan_disband", user_id=0,
                                       user_label="Steve", correlation_id="clan", risk="destructive")
        for record in (routine, important):
            asyncio.run(deliver_server_event(client, record, minecraft_uuid=PLAYER,
                                             minecraft_username="Steve", summary="Steve mined 12 coal ore"))

        client.log_digest.add.assert_awaited_once()
        channel, label, line = client.log_digest.add.await_args.args
        self.assertEqual((channel, label), (10, "Mining"))
        self.assertEqual(line, "**Steve** mined 12 coal ore")
        self.assertEqual([call.args[0] for call in client._send_configured_log.await_args_list], [20])

    def test_routine_commands_are_digested_and_denials_are_not(self):
        client = self.client()
        asyncio.run(deliver(client, CommandAuditRecord(source=SOURCE_COMMAND, command="minecraft account",
                                                       user_id=5, user_label="Member")))
        asyncio.run(deliver(client, CommandAuditRecord(source=SOURCE_COMMAND, command="mgxstaff ban",
                                                       user_id=5, user_label="Member", outcome="denied")))
        self.assertEqual(client.log_digest.add.await_count, 1)
        self.assertEqual(client._send_configured_log.await_count, 1)
        self.assertIsInstance(client._send_configured_log.await_args.args[1], discord.Embed)


if __name__ == "__main__":
    unittest.main()
