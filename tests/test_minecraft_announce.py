import time
import unittest
from types import SimpleNamespace
from unittest.mock import AsyncMock

import discord

from minecraft_bot.announce import (
    DEFAULT_COLOUR,
    RECIPIENT_COOLDOWN_SECONDS,
    UpdateAnnouncer,
    announcer_for,
    build_announcement_embed,
)


class Recipient:
    """A member that records what it was sent instead of calling Discord."""

    def __init__(self, member_id: int) -> None:
        self.id = member_id
        self.bot = False
        self.received: list[discord.Embed] = []

    async def send(self, content=None, embed=None):
        self.received.append(embed)


class BuildAnnouncementEmbedTests(unittest.TestCase):
    def test_a_draft_needs_words(self):
        with self.assertRaises(ValueError):
            build_announcement_embed(title="  ", description="")

    def test_either_field_alone_is_enough(self):
        self.assertEqual("Update", build_announcement_embed(title="Update").title)
        self.assertEqual("Body", build_announcement_embed(description="Body").description)

    def test_over_length_fields_are_refused(self):
        with self.assertRaises(ValueError):
            build_announcement_embed(title="x" * 257)
        with self.assertRaises(ValueError):
            build_announcement_embed(description="x" * 4001)

    def test_colour_accepts_a_leading_hash_and_defaults_to_the_house_orange(self):
        self.assertEqual(0x00FF00, build_announcement_embed(title="a", colour="#00FF00").colour.value)
        self.assertEqual(DEFAULT_COLOUR, build_announcement_embed(title="a").colour.value)
        with self.assertRaises(ValueError):
            build_announcement_embed(title="a", colour="orange")

    def test_only_https_images_are_attached(self):
        self.assertIsNone(build_announcement_embed(title="a", image="http://x/y.png").image.url)
        self.assertEqual(
            "https://x/y.png",
            build_announcement_embed(title="a", image="https://x/y.png").image.url,
        )


class PreviewTests(unittest.IsolatedAsyncioTestCase):
    def _announcer(self, *, enabled: str = "0") -> UpdateAnnouncer:
        bot = SimpleNamespace(
            data=SimpleNamespace(get_config=self._config(enabled)),
            settings=SimpleNamespace(member_role_id=0),
        )
        return UpdateAnnouncer(bot)

    @staticmethod
    def _config(value: str):
        async def get_config(key, default=None):
            return value

        return get_config

    async def test_preview_works_while_announcements_are_switched_off(self):
        # The whole point is reading the notice before arming the real send.
        announcer = self._announcer(enabled="0")
        member = Recipient(1)
        await announcer.preview(embed=build_announcement_embed(title="Update 7"), member=member)
        self.assertEqual(1, len(member.received))
        self.assertFalse(await announcer.enabled())

    async def test_preview_does_not_consume_the_recipient_cooldown(self):
        """The author usually holds the member role, and a recorded preview would make
        the real announcement skip the one person who knows it went out."""
        announcer = self._announcer()
        member = Recipient(7)
        await announcer.preview(embed=build_announcement_embed(title="a"), member=member)
        self.assertNotIn(7, announcer._last_sent)

    async def test_a_previewed_member_still_receives_the_real_announcement(self):
        announcer = self._announcer(enabled="1")
        member = Recipient(7)
        await announcer.preview(embed=build_announcement_embed(title="draft"), member=member)
        result = await announcer.send(
            embed=build_announcement_embed(title="real"), targets=[member]
        )
        self.assertEqual(1, result.delivered)
        self.assertEqual(0, result.skipped)
        self.assertEqual(["draft", "real"], [item.title for item in member.received])

    async def test_previews_repeat_so_a_draft_can_be_iterated_on(self):
        announcer = self._announcer()
        member = Recipient(7)
        for _ in range(3):
            await announcer.preview(embed=build_announcement_embed(title="a"), member=member)
        self.assertEqual(3, len(member.received))

    async def test_a_real_send_still_honours_the_cooldown(self):
        # Guards the invariant the preview exemption sits next to.
        announcer = self._announcer(enabled="1")
        member = Recipient(7)
        announcer._last_sent[7] = time.time() - (RECIPIENT_COOLDOWN_SECONDS / 2)
        result = await announcer.send(embed=build_announcement_embed(title="a"), targets=[member])
        self.assertEqual(0, result.delivered)
        self.assertEqual(1, result.skipped)
        self.assertEqual([], member.received)


class AnnouncerForTests(unittest.TestCase):
    def test_the_console_and_the_command_share_one_announcer(self):
        # The in-flight guard and the cooldown only mean anything on a shared object.
        bot = SimpleNamespace()
        self.assertIs(announcer_for(bot), announcer_for(bot))



class AnnouncePreviewCommandTests(unittest.IsolatedAsyncioTestCase):
    """Drives the real /mgxadmin announce-preview callback."""

    def _command(self):
        from minecraft_bot.bot import MinecraftAccessBot

        bot = object.__new__(MinecraftAccessBot)
        admin_group = next(
            group for group in bot._build_command_groups() if group.name == "mgxadmin"
        )
        command = next(
            item for item in admin_group.commands if item.name == "announce-preview"
        )
        return bot, command

    @staticmethod
    def _interaction(user):
        return SimpleNamespace(
            user=user,
            response=SimpleNamespace(defer=AsyncMock(), send_message=AsyncMock()),
            edit_original_response=AsyncMock(),
        )

    async def test_a_non_owner_is_refused_and_nothing_is_sent(self):
        bot, command = self._command()
        bot.is_owner_member = lambda member: False
        member = Recipient(5)
        interaction = self._interaction(member)

        await command.callback(interaction, title="Update 7")

        self.assertEqual([], member.received)
        embed = interaction.response.send_message.await_args.kwargs["embed"]
        self.assertEqual("Owner Access Required", embed.title)

    async def test_the_owner_receives_the_draft_as_a_direct_message(self):
        bot, command = self._command()
        bot.is_owner_member = lambda member: True
        bot.data = SimpleNamespace(get_config=AsyncMock(return_value="0"))
        bot.settings = SimpleNamespace(member_role_id=0)
        bot._configured_guild = AsyncMock(return_value=None)
        member = Recipient(5)
        interaction = self._interaction(member)

        await command.callback(
            interaction, title="Update 7", message="Line one\\nLine two"
        )

        self.assertEqual(1, len(member.received))
        sent = member.received[0]
        self.assertEqual("Update 7", sent.title)
        # A slash option cannot carry a real newline, so the author types the escape.
        self.assertEqual("Line one\nLine two", sent.description)
        reply = interaction.edit_original_response.await_args.kwargs["embed"]
        self.assertEqual("Preview Sent", reply.title)
        self.assertIn("switched off", reply.description)

    async def test_an_unusable_draft_is_reported_without_sending(self):
        bot, command = self._command()
        bot.is_owner_member = lambda member: True
        member = Recipient(5)
        interaction = self._interaction(member)

        await command.callback(interaction, title="a", colour="not-a-colour")

        self.assertEqual([], member.received)
        reply = interaction.edit_original_response.await_args.kwargs["embed"]
        self.assertEqual("Cannot Build That Notice", reply.title)

    async def test_a_closed_inbox_is_explained_rather_than_raised(self):
        bot, command = self._command()
        bot.is_owner_member = lambda member: True
        member = Recipient(5)
        member.send = AsyncMock(
            side_effect=discord.Forbidden(SimpleNamespace(status=403, reason=""), "closed")
        )
        interaction = self._interaction(member)

        await command.callback(interaction, title="Update 7")

        reply = interaction.edit_original_response.await_args.kwargs["embed"]
        self.assertEqual("Your Direct Messages Are Closed", reply.title)


if __name__ == "__main__":
    unittest.main()
