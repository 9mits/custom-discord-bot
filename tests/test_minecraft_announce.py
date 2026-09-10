import time
import unittest
from pathlib import Path
from tempfile import TemporaryDirectory
from types import SimpleNamespace
from unittest.mock import AsyncMock, patch

import discord

from minecraft_bot.announce import (
    DEFAULT_COLOUR,
    RECIPIENT_COOLDOWN_SECONDS,
    UpdateAnnouncer,
    announcer_for,
    build_announcement_embed,
)
from minecraft_bot.updatenotice import (
    ConfirmUpdateOptOutView,
    FinalUpdateOptOutView,
    UpdateNoticeView,
    build_notice_embed,
    build_notice_embeds,
    find_template,
    load_update_templates,
)


class Recipient:
    """A member that records what it was sent instead of calling Discord."""

    def __init__(self, member_id: int) -> None:
        self.id = member_id
        self.bot = False
        self.received: list[discord.Embed] = []
        self.views: list[object] = []

    async def send(self, content=None, embed=None, embeds=None, view=None):
        self.received.extend(list(embeds) if embeds is not None else [embed])
        self.views.append(view)


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
        async def no_optouts():
            return set()

        bot = SimpleNamespace(
            data=SimpleNamespace(
                get_config=self._config(enabled), update_optout_ids=no_optouts
            ),
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

    async def test_a_multi_embed_story_is_still_one_dm(self):
        announcer = self._announcer(enabled="1")
        member = Recipient(7)
        story = [
            build_announcement_embed(title="Hook"),
            build_announcement_embed(title="Feature"),
            build_announcement_embed(title="Come Back"),
        ]

        result = await announcer.send(embeds=story, targets=[member])

        self.assertEqual(1, result.delivered)
        self.assertEqual(["Hook", "Feature", "Come Back"], [item.title for item in member.received])

    async def test_every_successful_announcement_dm_is_logged(self):
        send_log = AsyncMock(return_value=True)
        bot = SimpleNamespace(
            data=SimpleNamespace(
                get_config=self._config("1"),
                update_optout_ids=AsyncMock(return_value=set()),
            ),
            settings=SimpleNamespace(command_log_channel_id=91, log_routes={}),
            _send_configured_log=send_log,
        )
        announcer = UpdateAnnouncer(bot)
        members = [Recipient(7), Recipient(8)]

        with patch("minecraft_bot.announce.asyncio.sleep", AsyncMock()):
            result = await announcer.send(
                embed=build_announcement_embed(title="Amethyst Dragon"),
                targets=members,
                actor="owner",
            )

        self.assertEqual(2, result.delivered)
        self.assertEqual(2, send_log.await_count)
        self.assertTrue(all(call.args[0] == 91 for call in send_log.await_args_list))
        self.assertTrue(all(
            call.args[1].title == "Update DM Sent" for call in send_log.await_args_list
        ))

    async def test_a_successful_preview_dm_is_logged_too(self):
        send_log = AsyncMock(return_value=True)
        bot = SimpleNamespace(
            settings=SimpleNamespace(command_log_channel_id=91, log_routes={}),
            _send_configured_log=send_log,
        )
        announcer = UpdateAnnouncer(bot)

        await announcer.preview(
            embed=build_announcement_embed(title="Amethyst Dragon"),
            member=Recipient(7),
        )

        self.assertEqual("Announcement Preview DM Sent", send_log.await_args.args[1].title)

    async def test_discords_ten_embed_limit_is_enforced_before_a_preview(self):
        announcer = self._announcer()
        member = Recipient(7)
        with self.assertRaisesRegex(ValueError, "no more than 10 embeds"):
            await announcer.preview(
                embeds=[build_announcement_embed(title=str(index)) for index in range(11)],
                member=member,
            )
        self.assertEqual([], member.received)

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



class UpdateTemplateTests(unittest.TestCase):
    """The notice is derived from the post, so it can only describe a real update."""

    def _posts(self, **files):
        holder = TemporaryDirectory()
        self.addCleanup(holder.cleanup)
        directory = Path(holder.name)
        for name, text in files.items():
            (directory / name).write_text(text, encoding="utf-8")
        return directory

    POST = (
        "---\n"
        "title: The Amethyst Dragon Update\n"
        "tagline: The Dragon has awakened!\n"
        "date: 2026-09-08\n"
        "category: Update\n"
        "cover: cover.png\n"
        "notice_spotlight_title: Amethyst Dragon\n"
        "notice_spotlight: A Feature\n"
        "notice_group_1: Reasons To Return | Ranked Wins | Safe Bases\n"
        "draft: true\n"
        "---\n\n"
        "## Something Stirred At Spawn\n\nBody.\n\n"
        "### A Feature\n\nMore body. Another sentence.\n\n![](dragon.png)\n\n"
        "## Ranked PvP\n\nBody.\n\n"
        "### Ranked Wins\n\nFight people. Climb the ranks.\n\n![](ranked.png)\n\n"
        "### Safe Bases\n\nGriefing is over. Build safely.\n"
    )

    def test_only_update_posts_become_templates(self):
        directory = self._posts(**{
            "2026-09-08-update-7.md": self.POST,
            "2026-09-01-an-event.md": (
                "---\ntitle: Event\ndate: 2026-09-01\ncategory: Event\n---\n\n## Beat\n"
            ),
        })
        templates = load_update_templates(directory)
        self.assertEqual(["The Amethyst Dragon Update"], [t.title for t in templates])

    def test_sections_are_the_beats_not_every_feature_heading(self):
        # A long post has far too many '###' features to be a summary.
        directory = self._posts(**{"2026-09-08-update-7.md": self.POST})
        template = load_update_templates(directory)[0]
        self.assertEqual(
            ("Something Stirred At Spawn", "Ranked PvP"), template.highlights
        )

    def test_a_post_with_no_sections_falls_back_to_features(self):
        directory = self._posts(**{
            "2026-09-08-x.md": (
                "---\ntitle: X\ndate: 2026-09-08\ncategory: Update\n---\n\n"
                "### Only A Feature\n\nBody.\n"
            )
        })
        self.assertEqual(
            ("Only A Feature",), load_update_templates(directory)[0].highlights
        )

    def test_the_slug_comes_from_the_dated_filename(self):
        directory = self._posts(**{"2026-09-08-update-7.md": self.POST})
        template = load_update_templates(directory)[0]
        self.assertEqual("update-7", template.slug)
        self.assertEqual("https://mysterioussmpx.blog/update-7/", template.url)

    def test_the_post_title_is_accepted_as_a_template_value(self):
        directory = self._posts(**{"2026-09-08-update-7.md": self.POST})
        template = find_template("The Amethyst Dragon Update", directory)
        self.assertIsNotNone(template)
        self.assertEqual("update-7", template.slug)

    def test_the_amethyst_notice_is_the_banner_then_the_collage(self):
        # This workstation has the private Update 7 post beside the published Update
        # 5 post with this exact old title. The stable alias must still select the new
        # comeback draft—the production checkout has no private post at all.
        template = find_template("Amethyst Update")
        self.assertIsNotNone(template)
        self.assertEqual("update-7", template.slug)
        self.assertTrue(template.draft)

        embeds = build_notice_embeds(template)
        self.assertEqual(2, len(embeds))

        lead, showcase = embeds
        self.assertEqual("New Mysterious SMP X update! - Amethyst Dragon", lead.title)
        self.assertEqual(0xB531FF, lead.colour.value)
        self.assertEqual(
            "https://mysterioussmpx.blog/media/update-7/banner.png", lead.image.url
        )
        self.assertEqual(template.url, lead.url)

        self.assertEqual(
            "https://mysterioussmpx.blog/media/update-7/notice-collage.png",
            showcase.image.url,
        )
        self.assertIsNone(showcase.url, "only the lead links the article")
        self.assertEqual(template.details, showcase.description)
        # It has to end by sending them somewhere, or the notice is the whole update.
        self.assertIn("on the site", showcase.description.lower())
        self.assertLessEqual(sum(len(embed) for embed in embeds), 6000)

    def test_the_collage_the_notice_points_at_actually_exists(self):
        # A notice referring to a missing image is a broken notice, and the composer
        # writes this file rather than a human remembering to.
        template = find_template("Amethyst Update")
        collage = (
            Path(__file__).resolve().parent.parent
            / "devblog" / "media" / template.slug / template.notice_collage
        )
        self.assertTrue(collage.is_file(), f"{collage} is missing")

    def test_a_draft_is_carried_through_and_labelled(self):
        directory = self._posts(**{"2026-09-08-update-7.md": self.POST})
        template = load_update_templates(directory)[0]
        self.assertTrue(template.draft)
        self.assertIn("(draft)", template.label)

    def test_the_notice_carries_the_tagline_beats_and_link(self):
        directory = self._posts(**{"2026-09-08-update-7.md": self.POST})
        embed = build_notice_embed(load_update_templates(directory)[0])
        self.assertEqual("The Amethyst Dragon Update is live!", embed.title)
        self.assertEqual("https://mysterioussmpx.blog/update-7/", embed.url)
        self.assertIn("The Dragon has awakened!", embed.description)
        self.assertIn("> Ranked PvP", embed.description)

    def test_an_editorial_notice_becomes_one_message_with_a_short_embed_story(self):
        directory = self._posts(**{"2026-09-08-update-7.md": self.POST})
        embeds = build_notice_embeds(load_update_templates(directory)[0])

        self.assertEqual(3, len(embeds))
        self.assertEqual("New Mysterious SMP X update! - Amethyst Dragon", embeds[0].title)
        self.assertEqual(
            "https://mysterioussmpx.blog/media/update-7/cover.png",
            embeds[0].image.url,
        )
        self.assertEqual("Amethyst Dragon", embeds[1].title)
        self.assertEqual("A Feature", embeds[1].fields[0].name)
        self.assertEqual("Reasons To Return", embeds[2].title)
        self.assertEqual(["Ranked Wins", "Safe Bases"], [field.name for field in embeds[2].fields])
        self.assertTrue(all(embed.url is None for embed in embeds[1:]))
        self.assertIn("stop future update DMs", embeds[-1].footer.text)

    def test_every_card_quotes_its_copy_one_beat_per_line(self):
        # '>' quotes a single line, so a section card written in the blog's
        # line-per-beat voice would come out half-quoted.
        directory = self._posts(**{"2026-09-08-update-7.md": self.POST})
        embeds = build_notice_embeds(load_update_templates(directory)[0])

        self.assertEqual(
            ">>> More body.\nAnother sentence.", embeds[1].fields[0].value
        )
        self.assertEqual(
            [">>> Fight people.\nClimb the ranks.", ">>> Griefing is over.\nBuild safely."],
            [field.value for field in embeds[2].fields],
        )

    def test_the_lead_card_always_opens_the_notice(self):
        # The banner card is the notice's face and the only thing carrying the
        # article link, so no amount of trimming may drop it.
        for template in (find_template("Amethyst Update"), find_template("update-7")):
            embeds = build_notice_embeds(template)
            lead = embeds[0]
            self.assertTrue(lead.image.url, "the lead card is missing its banner")
            self.assertEqual(template.url, lead.url)
            self.assertIn("New Mysterious SMP X update!", lead.title)
            self.assertEqual(template.tagline, lead.description)
            self.assertTrue(all(embed.url is None for embed in embeds[1:]))

    def test_a_trimmed_notice_stays_short_enough_to_read(self):
        embeds = build_notice_embeds(find_template("Amethyst Update"))
        self.assertLessEqual(len(embeds), 5, "the notice is a headline, not the post")
        self.assertTrue(all(embed.image.url for embed in embeds), "every card earns a shot")
        for embed in embeds[1:]:
            self.assertLessEqual(
                len(embed.fields), 4, "more beats than this reads as a wall of text"
            )

    def test_posts_without_notice_metadata_keep_the_single_embed_summary(self):
        post = self.POST.replace("notice_spotlight: A Feature\n", "").replace(
            "notice_group_1: Reasons To Return | Ranked Wins | Safe Bases\n", ""
        )
        directory = self._posts(**{"2026-09-08-update-7.md": post})
        self.assertEqual(1, len(build_notice_embeds(load_update_templates(directory)[0])))

    def test_the_real_update_7_post_makes_a_usable_notice(self):
        # Guards the parser against the repo's own front matter, not just a fixture.
        template = find_template("update-7")
        if template is None:
            self.skipTest("update-7 is not in this checkout")
        self.assertTrue(template.highlights)
        self.assertIn("Amethyst", build_notice_embed(template).title)


class UpdateNoticeViewTests(unittest.IsolatedAsyncioTestCase):
    def test_the_view_is_persistent_so_old_buttons_keep_working(self):
        # A notice outlives the process that sent it. A non-persistent view would
        # leave every previously sent opt-out button dead after a restart.
        view = UpdateNoticeView(SimpleNamespace(), "https://mysterioussmpx.blog/update-7/")
        self.assertTrue(view.is_persistent())
        self.assertIsNone(view.timeout)

    def test_it_offers_the_update_and_dm_settings_without_a_play_again_button(self):
        view = UpdateNoticeView(SimpleNamespace(), "https://mysterioussmpx.blog/update-7/")
        styles = {item.style for item in view.children}
        self.assertIn(discord.ButtonStyle.link, styles)
        self.assertIn(discord.ButtonStyle.secondary, styles)
        links = {
            item.label: item.url
            for item in view.children
            if item.style is discord.ButtonStyle.link
        }
        self.assertEqual("https://mysterioussmpx.blog/update-7/", links["Read the full update"])
        self.assertNotIn("Play again", [item.label for item in view.children])

    async def test_stopping_requires_a_clear_confirmation(self):
        recorded = {}

        async def set_update_optout(user_id, opted_out=True):
            recorded["user_id"] = user_id
            recorded["opted_out"] = opted_out

        send_log = AsyncMock(return_value=True)
        bot = SimpleNamespace(
            data=SimpleNamespace(
                is_update_opted_out=AsyncMock(return_value=False),
                set_update_optout=set_update_optout,
            ),
            settings=SimpleNamespace(command_log_channel_id=91, log_routes={}),
            _send_configured_log=send_log,
        )
        view = UpdateNoticeView(bot)
        interaction = SimpleNamespace(
            user=SimpleNamespace(id=77),
            response=SimpleNamespace(send_message=AsyncMock()),
        )
        button = [
            item for item in view.children if item.custom_id == "mgx:update-notice:optout"
        ][0]

        await button.callback(interaction)

        self.assertEqual({}, recorded, "one press must never silence anything")
        first = interaction.response.send_message.await_args.kwargs["view"]
        self.assertIsInstance(first, ConfirmUpdateOptOutView)
        self.assertTrue(interaction.response.send_message.await_args.kwargs["ephemeral"])

        # Every step offers staying first, and staying is the green one.
        self.assertEqual("Keep them on", first.children[0].label)
        self.assertIs(discord.ButtonStyle.success, first.children[0].style)

        second_interaction = SimpleNamespace(
            user=SimpleNamespace(id=77),
            response=SimpleNamespace(edit_message=AsyncMock()),
        )
        await first.children[1].callback(second_interaction)
        self.assertEqual({}, recorded, "two presses must not silence anything either")
        second = second_interaction.response.edit_message.await_args.kwargs["view"]
        self.assertIsInstance(second, FinalUpdateOptOutView)
        self.assertEqual("Actually, keep them on", second.children[0].label)

        confirm_interaction = SimpleNamespace(
            user=SimpleNamespace(id=77),
            response=SimpleNamespace(edit_message=AsyncMock()),
        )
        await second.children[1].callback(confirm_interaction)

        self.assertEqual({"user_id": 77, "opted_out": True}, recorded)
        reply = confirm_interaction.response.edit_message.await_args.kwargs["embed"]
        # The promise that matters: this silences updates, not the bot.
        self.assertIn("update notices only", reply.description.lower())
        # And the way back has to be advertised at the moment they leave.
        self.assertIn("one press", reply.description.lower())
        self.assertEqual("Update DMs Disabled", send_log.await_args.args[1].title)

    async def test_backing_out_at_either_step_leaves_them_subscribed(self):
        recorded = {}

        async def set_update_optout(user_id, opted_out=True):
            recorded["user_id"] = user_id

        bot = SimpleNamespace(
            data=SimpleNamespace(
                is_update_opted_out=AsyncMock(return_value=False),
                set_update_optout=set_update_optout,
            ),
            settings=SimpleNamespace(command_log_channel_id=91, log_routes={}),
            _send_configured_log=AsyncMock(return_value=True),
        )
        for view in (ConfirmUpdateOptOutView(bot), FinalUpdateOptOutView(bot)):
            interaction = SimpleNamespace(
                user=SimpleNamespace(id=77),
                response=SimpleNamespace(edit_message=AsyncMock()),
            )
            await view.children[0].callback(interaction)
            embed = interaction.response.edit_message.await_args.kwargs["embed"]
            self.assertEqual("Nothing Changed", embed.title)
        self.assertEqual({}, recorded)

    async def test_the_same_button_turns_update_dms_back_on_in_one_click(self):
        set_update_optout = AsyncMock()
        send_log = AsyncMock(return_value=True)
        bot = SimpleNamespace(
            data=SimpleNamespace(
                is_update_opted_out=AsyncMock(return_value=True),
                set_update_optout=set_update_optout,
            ),
            settings=SimpleNamespace(command_log_channel_id=91, log_routes={}),
            _send_configured_log=send_log,
        )
        view = UpdateNoticeView(bot)
        interaction = SimpleNamespace(
            user=SimpleNamespace(id=77),
            response=SimpleNamespace(send_message=AsyncMock()),
        )
        button = next(
            item for item in view.children if item.custom_id == "mgx:update-notice:optout"
        )

        await button.callback(interaction)

        set_update_optout.assert_awaited_once_with(77, False)
        reply = interaction.response.send_message.await_args.kwargs["embed"]
        self.assertEqual("Update DMs Switched Back On", reply.title)
        self.assertEqual("Update DMs Re-enabled", send_log.await_args.args[1].title)


class OptOutFilteringTests(unittest.IsolatedAsyncioTestCase):
    async def test_opted_out_members_are_removed_before_the_send(self):
        """A refusal the person asked for must not count towards the abort rate."""
        staying, leaving = Recipient(1), Recipient(2)
        role = SimpleNamespace(members=[staying, leaving])

        async def optouts():
            return {"2"}

        bot = SimpleNamespace(
            data=SimpleNamespace(update_optout_ids=optouts),
            settings=SimpleNamespace(member_role_id=5),
            _configured_guild=AsyncMock(
                return_value=SimpleNamespace(get_role=lambda _id: role)
            ),
        )
        self.assertEqual([staying], await UpdateAnnouncer(bot).recipients())

if __name__ == "__main__":
    unittest.main()
