import unittest
from types import SimpleNamespace

import discord

from core import context
from core.actions import get_action_spec
from core.context import set_bot
from core.services import _KNOWN_IMPORT_KEYS


class ConfigRuntimeMixin:
    """Tag and self-role helpers read the live config off the bot runtime."""

    config: dict = {}

    def setUp(self):
        super().setUp()
        self._previous_bot = context._active_bot
        set_bot(SimpleNamespace(data_manager=SimpleNamespace(config=dict(self.config))))

    def tearDown(self):
        set_bot(self._previous_bot)
        super().tearDown()


class FakeRole:
    """Stands in for discord.Role, which compares by position — the self-role
    helpers rely on `role >= me.top_role` to skip roles the bot cannot grant."""

    def __init__(self, role_id, *, name="Role", managed=False, default=False, position=1, admin=False):
        self.id = role_id
        self.name = name
        self.mention = f"<@&{role_id}>"
        self.managed = managed
        self.position = position
        self.permissions = discord.Permissions.none()
        if admin:
            self.permissions.update(administrator=True)
        self._default = default

    def is_default(self):
        return self._default

    def __ge__(self, other):
        return self.position >= other.position

    def __lt__(self, other):
        return self.position < other.position


def make_role(role_id, **kwargs):
    return FakeRole(role_id, **kwargs)


def make_guild(roles):
    lookup = {role.id: role for role in roles}
    return SimpleNamespace(
        id=1,
        icon=None,
        me=SimpleNamespace(top_role=make_role(9999, name="Bot", position=100)),
        get_role=lookup.get,
    )


class TagNameTests(unittest.TestCase):
    def setUp(self):
        import cogs.tags as tags

        self.tags = tags

    def test_names_are_lowercased_and_trimmed(self):
        self.assertEqual(self.tags.validate_tag_name("  Appeal-Process  "), "appeal-process")

    def test_hyphens_underscores_and_digits_are_allowed(self):
        for name in ("rules", "appeal-process", "faq_1", "a"):
            with self.subTest(name=name):
                self.assertEqual(self.tags.validate_tag_name(name), name)

    def test_spaces_and_punctuation_are_rejected(self):
        for name in ("two words", "bad!", "with.dot", "", "   "):
            with self.subTest(name=name):
                self.assertIsNone(self.tags.validate_tag_name(name))

    def test_a_name_may_not_start_with_a_separator(self):
        self.assertIsNone(self.tags.validate_tag_name("-leading"))
        self.assertIsNone(self.tags.validate_tag_name("_leading"))

    def test_overlong_names_are_rejected(self):
        self.assertIsNotNone(self.tags.validate_tag_name("a" * 31))
        self.assertIsNone(self.tags.validate_tag_name("a" * 32))


class TagLookupTests(ConfigRuntimeMixin, unittest.TestCase):
    config = {
        "tags": {
            "rules": {"content": "Read the rules.", "title": "Rules"},
            "appeal": {"content": "Open a ticket."},
            "broken": {"title": "No content"},
            "alsobroken": "not a dict",
        }
    }

    def setUp(self):
        super().setUp()
        import cogs.tags as tags

        self.tags = tags

    def test_malformed_entries_are_ignored(self):
        names = set(self.tags.get_tags())

        self.assertEqual(names, {"rules", "appeal"})

    def test_a_missing_tags_key_yields_nothing(self):
        self.assertEqual(self.tags.get_tags({}), {})
        self.assertEqual(self.tags.get_tags({"tags": "nope"}), {})

    def test_search_matches_a_substring_and_sorts(self):
        self.assertEqual(self.tags.search_tag_names(""), ["appeal", "rules"])
        self.assertEqual(self.tags.search_tag_names("rul"), ["rules"])
        self.assertEqual(self.tags.search_tag_names("zzz"), [])

    def test_search_is_case_insensitive(self):
        self.assertEqual(self.tags.search_tag_names("RUL"), ["rules"])

    def test_search_respects_the_autocomplete_limit(self):
        config = {"tags": {f"tag{index}": {"content": "x"} for index in range(40)}}

        self.assertEqual(len(self.tags.search_tag_names("", config, limit=25)), 25)

    def test_a_tag_without_a_title_falls_back_to_its_name(self):
        embed = self.tags.build_tag_embed("appeal", {"content": "Open a ticket."}, guild=None)

        self.assertEqual(embed.title, "appeal")
        self.assertEqual(embed.description, "Open a ticket.")

    def test_a_title_is_used_when_present(self):
        embed = self.tags.build_tag_embed("rules", {"content": "Read.", "title": "Rules"}, guild=None)

        self.assertEqual(embed.title, "Rules")

    def test_the_list_names_every_valid_tag(self):
        embed = self.tags.build_tag_list_embed(None, guild=None)

        self.assertIn("Tags (2)", embed.title)
        self.assertIn("`rules`", embed.description)
        self.assertIn("`appeal`", embed.description)

    def test_an_empty_list_points_at_the_command_that_creates_one(self):
        embed = self.tags.build_tag_list_embed({}, guild=None)

        self.assertIn("/tag-admin create", embed.description)


class SelfRoleResolutionTests(ConfigRuntimeMixin, unittest.TestCase):
    def setUp(self):
        super().setUp()
        import cogs.tags as tags

        self.tags = tags

    def test_ids_are_normalized_and_deduplicated(self):
        config = {"self_roles": [5, "5", "7", None, "junk", 0]}

        self.assertEqual(self.tags.get_self_role_ids(config), [5, 7])

    def test_a_missing_or_malformed_list_yields_nothing(self):
        self.assertEqual(self.tags.get_self_role_ids({}), [])
        self.assertEqual(self.tags.get_self_role_ids({"self_roles": "nope"}), [])

    def test_the_list_is_capped(self):
        config = {"self_roles": list(range(1, 100))}

        self.assertEqual(len(self.tags.get_self_role_ids(config)), self.tags.MAX_SELF_ROLES)

    def test_deleted_roles_are_skipped(self):
        guild = make_guild([make_role(10)])
        config = {"self_roles": [10, 11]}

        resolved = self.tags.resolve_self_roles(guild, config)

        self.assertEqual([role.id for role in resolved], [10])

    def test_integration_managed_and_default_roles_are_skipped(self):
        roles = [make_role(10, managed=True), make_role(11, default=True), make_role(12)]
        guild = make_guild(roles)
        config = {"self_roles": [10, 11, 12]}

        resolved = self.tags.resolve_self_roles(guild, config)

        self.assertEqual([role.id for role in resolved], [12])

    def test_roles_at_or_above_the_bot_are_skipped(self):
        # The bot cannot grant a role it does not outrank, so offering it would fail.
        roles = [make_role(10, position=100), make_role(11, position=150), make_role(12, position=5)]
        guild = make_guild(roles)
        config = {"self_roles": [10, 11, 12]}

        resolved = self.tags.resolve_self_roles(guild, config)

        self.assertEqual([role.id for role in resolved], [12])


class SelfRoleEmbedTests(ConfigRuntimeMixin, unittest.TestCase):
    def setUp(self):
        super().setUp()
        import cogs.tags as tags

        self.tags = tags

    def test_an_empty_picker_points_at_the_admin_command(self):
        embed = self.tags.build_self_role_embed([], guild=None)

        self.assertIn("/selfrole-admin add", embed.description)

    def test_configured_roles_are_listed_by_mention(self):
        embed = self.tags.build_self_role_embed([make_role(10), make_role(11)], guild=None)

        self.assertIn("<@&10>", embed.description)
        self.assertIn("<@&11>", embed.description)


class SelfRoleSelectTests(ConfigRuntimeMixin, unittest.TestCase):
    def setUp(self):
        super().setUp()
        import cogs.tags as tags

        self.tags = tags

    def test_each_role_becomes_a_selectable_option(self):
        select = self.tags.SelfRoleSelect([make_role(10, name="Alpha"), make_role(11, name="Beta")])

        self.assertEqual([option.value for option in select.item.options], ["10", "11"])
        self.assertEqual(select.item.max_values, 2)

    def test_min_values_is_zero_so_every_role_can_be_dropped(self):
        select = self.tags.SelfRoleSelect([make_role(10)])

        self.assertEqual(select.item.min_values, 0)

    def test_an_empty_picker_is_disabled_rather_than_invalid(self):
        select = self.tags.SelfRoleSelect([])

        self.assertTrue(select.item.disabled)
        self.assertEqual(len(select.item.options), 1)

    def test_the_picker_is_capped_at_the_select_limit(self):
        roles = [make_role(index) for index in range(1, 40)]

        select = self.tags.SelfRoleSelect(roles)

        self.assertEqual(len(select.item.options), self.tags.MAX_SELF_ROLES)

    def test_the_custom_id_is_stable_so_the_view_survives_a_restart(self):
        select = self.tags.SelfRoleSelect([make_role(10)])

        self.assertEqual(select.item.custom_id, self.tags.SELF_ROLE_CUSTOM_ID)


class ActionRegistryTests(unittest.TestCase):
    def test_every_new_command_is_documented(self):
        for name in (
            "tag", "tags", "tag-admin create", "tag-admin edit", "tag-admin delete",
            "selfroles", "selfrole-admin add", "selfrole-admin remove", "selfrole-admin panel",
        ):
            with self.subTest(command=name):
                self.assertIsNotNone(get_action_spec(name), f"{name} has no ActionSpec")

    def test_management_commands_require_the_config_capability(self):
        for name in (
            "tag-admin create", "tag-admin edit", "tag-admin delete",
            "selfrole-admin add", "selfrole-admin remove", "selfrole-admin panel",
        ):
            with self.subTest(command=name):
                self.assertEqual(get_action_spec(name).capability, "config_panel")

    def test_member_facing_commands_need_no_capability(self):
        for name in ("tag", "tags", "selfroles"):
            with self.subTest(command=name):
                self.assertIsNone(get_action_spec(name).capability)

    def test_deleting_a_tag_is_classified_destructive(self):
        self.assertEqual(get_action_spec("tag-admin delete").risk_level.value, "destructive")


class ConfigPortabilityTests(unittest.TestCase):
    def test_tags_and_self_roles_survive_a_settings_backup(self):
        self.assertIn("tags", _KNOWN_IMPORT_KEYS)
        self.assertIn("self_roles", _KNOWN_IMPORT_KEYS)


if __name__ == "__main__":
    unittest.main()
