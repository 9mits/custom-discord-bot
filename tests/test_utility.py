import tempfile
import unittest
from datetime import datetime, timedelta, timezone
from pathlib import Path
from types import SimpleNamespace

import discord

from core import context
from core.actions import get_action_spec, validate_registered_actions
from core.context import set_bot


class EmbedRuntimeMixin:
    """Embed builders read the branding theme off the active bot runtime."""

    def setUp(self):
        super().setUp()
        self._previous_bot = context._active_bot
        set_bot(SimpleNamespace(data_manager=SimpleNamespace(config={})))

    def tearDown(self):
        set_bot(self._previous_bot)
        super().tearDown()


def make_user(user_id=42, *, name="member"):
    return SimpleNamespace(
        id=user_id,
        name=name,
        mention=f"<@{user_id}>",
        display_name=name,
        display_avatar=SimpleNamespace(url="https://cdn.example/avatar.png"),
        created_at=datetime(2020, 1, 1, tzinfo=timezone.utc),
        __str__=lambda self: name,
    )


class PollParsingTests(unittest.TestCase):
    def setUp(self):
        import cogs.utility as utility

        self.utility = utility

    def test_pipe_separated_choices_are_split_and_trimmed(self):
        self.assertEqual(self.utility.parse_poll_options("Yes | No | Maybe"), ["Yes", "No", "Maybe"])

    def test_newlines_work_as_a_separator_too(self):
        self.assertEqual(self.utility.parse_poll_options("Yes\nNo"), ["Yes", "No"])

    def test_blank_and_duplicate_choices_are_dropped(self):
        self.assertEqual(self.utility.parse_poll_options("Yes ||  | Yes | No"), ["Yes", "No"])

    def test_choices_are_capped_at_the_number_of_available_reactions(self):
        options = self.utility.parse_poll_options(" | ".join(str(index) for index in range(20)))

        self.assertEqual(len(options), self.utility.MAX_POLL_OPTIONS)
        self.assertLessEqual(len(options), len(self.utility.POLL_REACTIONS))

    def test_a_single_choice_is_not_enough_for_a_poll(self):
        self.assertEqual(len(self.utility.parse_poll_options("Only one")), 1)

    def test_long_choices_are_truncated(self):
        (option,) = self.utility.parse_poll_options("x" * 400)

        self.assertEqual(len(option), 100)


class InfoEmbedTests(EmbedRuntimeMixin, unittest.TestCase):
    def setUp(self):
        super().setUp()
        import cogs.utility as utility

        self.utility = utility

    def test_a_non_member_is_reported_as_not_being_in_the_server(self):
        embed = self.utility.build_userinfo_embed(make_user(), guild=None)
        rendered = {field.name: field.value for field in embed.fields}

        self.assertEqual(rendered["Membership"], "Not a member of this server.")
        self.assertNotIn("Roles (0)", rendered)

    def test_case_and_note_counts_are_only_shown_when_supplied(self):
        without = self.utility.build_userinfo_embed(make_user(), guild=None)
        with_counts = self.utility.build_userinfo_embed(make_user(), guild=None, case_count=3, note_count=1)

        self.assertNotIn("Moderation Cases", {field.name for field in without.fields})
        rendered = {field.name: field.value for field in with_counts.fields}
        self.assertEqual(rendered["Moderation Cases"], "3")
        self.assertEqual(rendered["Staff Notes"], "1")

    def test_key_permissions_lists_only_the_meaningful_ones(self):
        permissions = discord.Permissions.none()
        permissions.update(ban_members=True, add_reactions=True)

        self.assertEqual(self.utility.key_permissions(permissions), "Ban Members")

    def test_no_key_permissions_reads_as_none(self):
        self.assertEqual(self.utility.key_permissions(discord.Permissions.none()), "None")

    def test_a_missing_timestamp_does_not_crash_the_embed(self):
        self.assertEqual(self.utility.format_timestamp(None), "Unknown")

    def test_timestamps_render_as_absolute_and_relative_codes(self):
        moment = datetime(2024, 6, 1, tzinfo=timezone.utc)

        rendered = self.utility.format_timestamp(moment)

        self.assertIn(f"<t:{int(moment.timestamp())}:F>", rendered)
        self.assertIn(f"<t:{int(moment.timestamp())}:R>", rendered)


class RoleSummaryTests(EmbedRuntimeMixin, unittest.TestCase):
    def setUp(self):
        super().setUp()
        import cogs.utility as utility

        self.utility = utility

    def _member(self, role_count):
        roles = [SimpleNamespace(mention="@everyone", is_default=lambda: True)]
        roles += [
            SimpleNamespace(mention=f"<@&{index}>", is_default=lambda: False)
            for index in range(role_count)
        ]
        return SimpleNamespace(roles=roles)

    def test_the_default_role_is_never_listed(self):
        self.assertEqual(self.utility.summarize_roles(self._member(0)), "None")

    def test_long_role_lists_are_summarized_with_a_remainder(self):
        summary = self.utility.summarize_roles(self._member(20), limit=15)

        self.assertIn("and 5 more", summary)


class NoteEmbedTests(EmbedRuntimeMixin, unittest.TestCase):
    def setUp(self):
        super().setUp()
        import cogs.utility as utility

        self.utility = utility

    def test_an_empty_note_list_explains_itself(self):
        embed = self.utility.build_note_list_embed(make_user(), [], guild=None)

        self.assertIn("No staff notes", embed.description)

    def test_notes_render_with_their_id_and_content(self):
        notes = [{"note_id": 7, "created_at": "2024-06-01T00:00:00+00:00", "content": "Context here"}]

        embed = self.utility.build_note_list_embed(make_user(), notes, guild=None)

        self.assertIn("Note 7", embed.fields[0].name)
        self.assertEqual(embed.fields[0].value, "Context here")

    def test_an_unparseable_timestamp_does_not_break_the_list(self):
        notes = [{"note_id": 8, "created_at": "not-a-date", "content": "Still shown"}]

        embed = self.utility.build_note_list_embed(make_user(), notes, guild=None)

        self.assertIn("Unknown time", embed.fields[0].name)
        self.assertEqual(embed.fields[0].value, "Still shown")


class PollEmbedTests(EmbedRuntimeMixin, unittest.TestCase):
    def setUp(self):
        super().setUp()
        import cogs.utility as utility

        self.utility = utility

    def test_each_choice_is_paired_with_the_reaction_that_votes_for_it(self):
        options = ["Yes", "No", "Maybe"]

        embed = self.utility.build_poll_embed("Ready?", options, author=make_user(), guild=None)

        for index, option in enumerate(options):
            self.assertIn(f"{self.utility.POLL_REACTIONS[index]} {option}", embed.description)

    def test_the_question_becomes_the_title(self):
        embed = self.utility.build_poll_embed("Ready?", ["Yes", "No"], author=make_user(), guild=None)

        self.assertEqual(embed.title, "Ready?")


class ActionRegistryTests(unittest.TestCase):
    def test_every_new_command_is_documented_for_help_and_risk_routing(self):
        for name in (
            "userinfo", "avatar", "roleinfo", "channelinfo", "ping", "timestamp",
            "slowmode", "nickname", "poll", "note add", "note list", "note remove",
        ):
            with self.subTest(command=name):
                self.assertIsNotNone(get_action_spec(name), f"{name} has no ActionSpec")

    def test_staff_only_commands_declare_a_capability(self):
        for name in ("slowmode", "nickname", "poll", "note add", "note list", "note remove"):
            with self.subTest(command=name):
                self.assertTrue(get_action_spec(name).capability)

    def test_public_lookups_do_not_require_a_capability(self):
        for name in ("userinfo", "avatar", "roleinfo", "channelinfo", "ping", "timestamp"):
            with self.subTest(command=name):
                self.assertIsNone(get_action_spec(name).capability)

    def test_deleting_a_note_is_classified_destructive(self):
        self.assertEqual(get_action_spec("note remove").risk_level.value, "destructive")

    def test_the_utility_cog_registers_exactly_what_it_documents(self):
        import asyncio

        from core.bot import EXTENSIONS, create_bot

        async def runner():
            runtime = create_bot()
            try:
                for extension in EXTENSIONS:
                    await runtime.load_extension(extension)
                runtime._remove_disabled_application_commands()
                undocumented, unavailable = validate_registered_actions(runtime.tree.walk_commands())
                self.assertEqual(undocumented, set())
                self.assertEqual(unavailable, set())
            finally:
                await runtime.close()

        asyncio.run(runner())


class UserNoteStorageTests(unittest.IsolatedAsyncioTestCase):
    async def asyncSetUp(self):
        import core.data as data

        self._tempdir = tempfile.TemporaryDirectory()
        self._original_db_file = data.DB_FILE
        data.DB_FILE = Path(self._tempdir.name) / "bot.db"
        self.data = data
        self.manager = data.DataManager(SimpleNamespace(metrics=None))

    async def asyncTearDown(self):
        await self.manager.close()
        self.data.DB_FILE = self._original_db_file
        self._tempdir.cleanup()

    async def test_notes_round_trip_newest_first(self):
        await self.manager.add_user_note(user_id=1, author_id=9, content="first")
        await self.manager.add_user_note(user_id=1, author_id=9, content="second")

        notes = await self.manager.list_user_notes(1)

        self.assertEqual([note["content"] for note in notes], ["second", "first"])
        self.assertEqual(await self.manager.count_user_notes(1), 2)

    async def test_notes_are_scoped_to_one_member(self):
        await self.manager.add_user_note(user_id=1, author_id=9, content="about one")
        await self.manager.add_user_note(user_id=2, author_id=9, content="about two")

        self.assertEqual(await self.manager.count_user_notes(1), 1)
        self.assertEqual((await self.manager.list_user_notes(2))[0]["content"], "about two")

    async def test_deleting_returns_the_removed_note_so_it_can_be_logged(self):
        note_id = await self.manager.add_user_note(user_id=1, author_id=9, content="remove me")

        deleted = await self.manager.delete_user_note(note_id)

        self.assertEqual(deleted["content"], "remove me")
        self.assertEqual(await self.manager.count_user_notes(1), 0)

    async def test_deleting_an_unknown_note_reports_nothing_rather_than_raising(self):
        self.assertIsNone(await self.manager.delete_user_note(9999))

    async def test_the_per_member_cap_drops_the_oldest_notes(self):
        cap = self.manager._NOTES_PER_USER
        for index in range(cap + 5):
            await self.manager.add_user_note(user_id=1, author_id=9, content=f"note {index}")

        self.assertEqual(await self.manager.count_user_notes(1), cap)
        newest = await self.manager.list_user_notes(1, limit=1)
        self.assertEqual(newest[0]["content"], f"note {cap + 4}")


class TimestampCommandTests(unittest.TestCase):
    def test_durations_parse_through_the_shared_helper(self):
        from core.utils import parse_duration_str

        # /timestamp builds its codes from this, so the units must line up.
        self.assertEqual(parse_duration_str("2h30m"), 150)
        self.assertEqual(parse_duration_str("3d"), 3 * 1440)
        self.assertEqual(parse_duration_str("1w"), 10080)

    def test_a_non_duration_is_rejected_before_building_a_timestamp(self):
        from core.utils import parse_duration_str

        self.assertEqual(parse_duration_str("soon"), 0)
        self.assertEqual(parse_duration_str(""), 0)

    def test_the_projected_moment_is_in_the_future(self):
        from core.utils import parse_duration_str

        minutes = parse_duration_str("2h")
        projected = datetime.now(timezone.utc) + timedelta(minutes=minutes)

        self.assertGreater(projected, datetime.now(timezone.utc))


if __name__ == "__main__":
    unittest.main()
