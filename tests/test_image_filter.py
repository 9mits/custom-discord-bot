from collections import defaultdict, deque
import io
from types import SimpleNamespace
import unittest
from unittest.mock import AsyncMock, Mock, call, patch

import discord

import cogs.automod as automod_module
import cogs.events as events_module
from cogs.automod import (
    IMAGE_FILTER_MAX_BYTES,
    IMAGE_FILTER_MAX_ENTRIES,
    IMAGE_FILTER_MAX_PIXELS,
    IMAGE_HASH_DISTANCE_THRESHOLD,
    ImageFilterRemoveSelect,
    ImageFilterResult,
    ImageFiltersView,
    ImageMatch,
    apply_image_filter_punishment,
    automod_cmd,
    ban_image_context,
    fingerprint_image_bytes,
    hash_distance,
    hash_image_bytes,
    image_match_allows_punishment,
    inspect_image_bytes,
    match_banned_image,
    normalize_image_filter_settings,
    run_image_filter,
)


def _entry(hash_hex: str, label: str = "test") -> dict:
    return {"hash": hash_hex, "label": label, "added_by": 1, "added_at": ""}


def _fingerprint_entry(fingerprint: dict, label: str = "test") -> dict:
    return {**fingerprint, "label": label, "added_by": 1, "added_at": ""}


def _png_bytes(color, size=(64, 64), *, structured=True, stripe=None):
    from PIL import Image, ImageDraw

    image = Image.new("RGB", size, color)
    draw = ImageDraw.Draw(image)
    if structured:
        # Scale-relative detail stays visually stable when independently rendered.
        for x in range(size[0]):
            red = int(x / size[0] * 255)
            draw.line([(x, 0), (x, size[1] // 4)], fill=(red, 0, 0))
    if stripe:
        draw.rectangle([0, size[1] - 8, size[0], size[1]], fill=stripe)
    buffer = io.BytesIO()
    image.save(buffer, format="PNG")
    return buffer.getvalue()


class ImageFilterSettingsTests(unittest.TestCase):
    def test_normalize_defaults(self):
        settings = normalize_image_filter_settings({})
        self.assertFalse(settings["enabled"])
        self.assertTrue(settings["delete_message"])
        self.assertTrue(settings["log_detections"])
        self.assertFalse(settings["punish"])
        self.assertEqual(settings["punishment_type"], "warn")
        self.assertEqual(settings["duration_minutes"], 60)
        self.assertEqual(settings["entries"], [])

    def test_normalize_strictly_rejects_malformed_values(self):
        malformed_hashes = [
            None,
            "",
            "0" * 15,
            "0" * 17,
            "not-hex-not-hex!",
        ]
        settings = normalize_image_filter_settings({
            "punishment_type": "explode",
            "duration_minutes": "not a number",
            "entries": [
                *({"hash": value} for value in malformed_hashes),
                "garbage",
                {"hash": "0123456789ABCDEF", "vhash": "bad", "color": "zzzzzz",
                 "sha256": "short", "aspect": object(), "detail": [], "added_by": {}},
            ],
        })

        self.assertEqual(settings["punishment_type"], "warn")
        self.assertEqual(settings["duration_minutes"], 60)
        self.assertEqual(len(settings["entries"]), 1)
        entry = settings["entries"][0]
        self.assertEqual(entry["hash"], "0123456789abcdef")
        self.assertEqual(entry["vhash"], "")
        self.assertEqual(entry["color"], "")
        self.assertEqual(entry["sha256"], "")
        self.assertEqual(entry["aspect"], 0)
        self.assertEqual(entry["detail"], 0)
        self.assertEqual(entry["added_by"], 0)

        self.assertEqual(
            normalize_image_filter_settings({"entries": {"hash": "0" * 16}})["entries"],
            [],
        )

    def test_normalize_clamps_duration_and_deduplicates_stable_ids(self):
        first = {"id": "caller-controlled", "hash": "0123456789abcdef", "label": "first"}
        duplicate = {"id": "different", "hash": "0123456789abcdef", "label": "duplicate"}
        distinct = {"id": "caller-controlled", "hash": "1123456789abcdef", "label": "distinct"}

        settings = normalize_image_filter_settings({
            "duration_minutes": 10**100,
            "entries": [first, duplicate, distinct],
        })

        self.assertEqual(settings["duration_minutes"], 40320)
        self.assertEqual([entry["label"] for entry in settings["entries"]], ["first", "distinct"])
        ids = [entry["id"] for entry in settings["entries"]]
        self.assertEqual(len(ids), len(set(ids)))
        self.assertTrue(all(len(entry_id) == 16 for entry_id in ids))
        self.assertEqual(
            ids,
            [entry["id"] for entry in normalize_image_filter_settings(settings)["entries"]],
        )
        self.assertEqual(normalize_image_filter_settings({"duration_minutes": -5})["duration_minutes"], 1)

    def test_normalize_caps_entries(self):
        entries = [{"hash": f"{index + 1:016x}"} for index in range(IMAGE_FILTER_MAX_ENTRIES + 10)]
        settings = normalize_image_filter_settings({"entries": entries})
        self.assertEqual(len(settings["entries"]), IMAGE_FILTER_MAX_ENTRIES)


class ImageFingerprintTests(unittest.TestCase):
    def test_hash_distance(self):
        self.assertEqual(hash_distance("0" * 16, "0" * 16), 0)
        self.assertEqual(hash_distance("0" * 16, "f" + "0" * 15), 4)

    def test_match_returns_image_match_for_legacy_entries(self):
        entries = [_entry("00ff00ff00ff00ff", "meme")]

        exact = match_banned_image("00ff00ff00ff00ff", entries)
        self.assertIsInstance(exact, ImageMatch)
        self.assertTrue(exact.matched)
        self.assertIs(exact.entry, entries[0])
        self.assertEqual(exact.distance, 0)
        self.assertEqual(exact.quality, "legacy")

        near = match_banned_image("00ff00ff00ff00f0", entries)
        self.assertTrue(near.matched)
        self.assertLessEqual(near.distance, IMAGE_HASH_DISTANCE_THRESHOLD)

    def test_no_match_beyond_threshold(self):
        match = match_banned_image("f" * 16, [_entry("0123456789abcdef")])
        self.assertFalse(match.matched)
        self.assertEqual(match.quality, "none")

    def test_structured_resized_image_is_a_strong_match(self):
        original = fingerprint_image_bytes(_png_bytes((30, 60, 90), size=(64, 64)))
        resized = fingerprint_image_bytes(_png_bytes((30, 60, 90), size=(128, 128)))

        self.assertIsNotNone(original)
        self.assertIsNotNone(resized)
        self.assertNotEqual(original["sha256"], resized["sha256"])
        match = match_banned_image(resized, [_fingerprint_entry(original, "structured")])
        self.assertTrue(match.matched)
        self.assertEqual(match.quality, "strong")
        self.assertTrue(image_match_allows_punishment("ban", match))

        from PIL import Image

        reencoded_buffer = io.BytesIO()
        with Image.open(io.BytesIO(_png_bytes((30, 60, 90), size=(64, 64)))) as image:
            image.convert("RGB").save(reencoded_buffer, format="JPEG", quality=75)
        reencoded_match = match_banned_image(
            fingerprint_image_bytes(reencoded_buffer.getvalue()),
            [_fingerprint_entry(original, "structured")],
        )
        self.assertEqual(reencoded_match.quality, "strong")

    def test_different_solid_colors_do_not_match_same_horizontal_dhash(self):
        red = fingerprint_image_bytes(_png_bytes((255, 0, 0), structured=False))
        green = fingerprint_image_bytes(_png_bytes((0, 255, 0), structured=False))

        self.assertEqual(red["hash"], green["hash"])
        self.assertFalse(match_banned_image(green, [_fingerprint_entry(red, "red")]).matched)

    def test_low_detail_near_match_cannot_auto_punish(self):
        original = fingerprint_image_bytes(_png_bytes((80, 100, 120), size=(64, 64), structured=False))
        resized = fingerprint_image_bytes(_png_bytes((80, 100, 120), size=(128, 128), structured=False))

        self.assertEqual(original["detail"], 0)
        self.assertNotEqual(original["sha256"], resized["sha256"])
        match = match_banned_image(resized, [_fingerprint_entry(original, "flat")])
        self.assertTrue(match.matched)
        self.assertEqual(match.quality, "fuzzy")
        for punishment_type in ("warn", "timeout", "kick", "ban"):
            with self.subTest(punishment_type=punishment_type):
                self.assertFalse(image_match_allows_punishment(punishment_type, match))

    def test_exact_low_detail_match_can_auto_punish(self):
        fingerprint = fingerprint_image_bytes(_png_bytes((80, 100, 120), structured=False))
        self.assertEqual(fingerprint["detail"], 0)

        match = match_banned_image(fingerprint, [_fingerprint_entry(fingerprint, "flat")])
        self.assertTrue(match.matched)
        self.assertEqual(match.quality, "exact")
        self.assertTrue(image_match_allows_punishment("ban", match))

    def test_legacy_degenerate_hash_does_not_match(self):
        for degenerate_hash in ("0" * 16, "f" * 16):
            with self.subTest(hash=degenerate_hash):
                match = match_banned_image(degenerate_hash, [_entry(degenerate_hash)])
                self.assertFalse(match.matched)

    def test_fingerprint_is_stable_and_hash_wrapper_is_compatible(self):
        data = _png_bytes((30, 60, 90))
        fingerprint = fingerprint_image_bytes(data)
        self.assertIsNotNone(fingerprint)
        self.assertEqual(hash_image_bytes(data), fingerprint["hash"])
        self.assertEqual(len(fingerprint["hash"]), 16)
        self.assertEqual(len(fingerprint["vhash"]), 16)
        self.assertEqual(len(fingerprint["color"]), 6)
        self.assertEqual(len(fingerprint["sha256"]), 64)

    def test_fingerprint_rejects_garbage_oversized_and_pixel_bomb_data(self):
        self.assertIsNone(fingerprint_image_bytes(b"this is not an image"))
        self.assertIsNone(fingerprint_image_bytes(b"x" * (IMAGE_FILTER_MAX_BYTES + 1)))

        from PIL import Image

        bomb_size = (4001, 4000)
        self.assertGreater(bomb_size[0] * bomb_size[1], IMAGE_FILTER_MAX_PIXELS)
        bomb = Image.new("1", bomb_size)
        buffer = io.BytesIO()
        bomb.save(buffer, format="PNG")
        self.assertIsNone(fingerprint_image_bytes(buffer.getvalue()))

    def test_fingerprint_rejects_unsupported_pillow_format(self):
        from PIL import Image

        buffer = io.BytesIO()
        Image.new("RGB", (32, 32), (10, 20, 30)).save(buffer, format="BMP")
        self.assertIsNone(fingerprint_image_bytes(buffer.getvalue()))

    def test_animation_is_explicitly_deferred_to_manual_review(self):
        from PIL import Image

        size = (64, 64)
        benign = Image.new("RGB", size, (0, 0, 255))
        banned = Image.new("RGB", size, (30, 60, 90))

        animation_buffer = io.BytesIO()
        benign.save(animation_buffer, format="GIF", save_all=True, append_images=[banned], duration=100, loop=0)
        inspection = inspect_image_bytes(animation_buffer.getvalue())

        self.assertTrue(inspection.is_image)
        self.assertFalse(inspection.complete)
        self.assertEqual(inspection.fingerprints, ())
        self.assertIn("animated", inspection.reason)


class ImageFilterRuntimeTests(unittest.IsolatedAsyncioTestCase):
    @staticmethod
    def _member(member_id=42):
        member = Mock(spec=discord.Member)
        member.id = member_id
        member.bot = False
        member.roles = []
        member.mention = f"<@{member_id}>"
        member.display_name = "Member"
        member.display_avatar = SimpleNamespace(url="https://example.invalid/avatar.png")
        return member

    async def test_four_non_images_before_valid_fifth_do_not_bypass_filter(self):
        valid_data = _png_bytes((30, 60, 90))
        fingerprint = fingerprint_image_bytes(valid_data)
        attachments = [
            SimpleNamespace(
                filename=f"not-image-{index}.txt",
                size=len(b"not an image"),
                read=AsyncMock(return_value=b"not an image"),
            )
            for index in range(4)
        ]
        attachments.append(SimpleNamespace(
            filename="banned.png",
            size=len(valid_data),
            read=AsyncMock(return_value=valid_data),
        ))
        data_manager = SimpleNamespace(config={
            "immunity_list": [],
            "image_filters": {
                "enabled": True,
                "delete_message": False,
                "log_detections": False,
                "entries": [_fingerprint_entry(fingerprint, "banned")],
            },
        })
        message = SimpleNamespace(
            guild=SimpleNamespace(),
            author=self._member(),
            channel=SimpleNamespace(id=123),
            attachments=attachments,
        )

        with patch.object(automod_module, "bot", SimpleNamespace(data_manager=data_manager)):
            result = await run_image_filter(message)

        self.assertTrue(result.matched)
        self.assertFalse(result.message_deleted)
        self.assertFalse(result.block_downstream)
        attachments[-1].read.assert_awaited_once()

    async def test_fuzzy_match_is_log_only(self):
        original = fingerprint_image_bytes(
            _png_bytes((80, 100, 120), size=(64, 64), structured=False)
        )
        resized_data = _png_bytes((80, 100, 120), size=(128, 128), structured=False)
        attachment = SimpleNamespace(
            filename="near-copy.png",
            size=len(resized_data),
            read=AsyncMock(return_value=resized_data),
        )
        data_manager = SimpleNamespace(config={
            "immunity_list": [],
            "image_filters": {
                "enabled": True,
                "delete_message": True,
                "log_detections": False,
                "punish": True,
                "punishment_type": "ban",
                "entries": [_fingerprint_entry(original, "flat image")],
            },
        })
        message = SimpleNamespace(
            id=99,
            guild=SimpleNamespace(),
            author=self._member(),
            channel=SimpleNamespace(id=123, mention="<#123>"),
            attachments=[attachment],
            delete=AsyncMock(),
        )
        log_embed = object()

        with patch.object(
            automod_module,
            "bot",
            SimpleNamespace(data_manager=data_manager),
        ), patch.object(
            automod_module,
            "make_action_log_embed",
            Mock(return_value=log_embed),
        ) as make_log, patch.object(
            automod_module,
            "send_automod_log",
            AsyncMock(),
        ) as send_log, patch.object(
            automod_module,
            "apply_image_filter_punishment",
            AsyncMock(),
        ) as punish:
            result = await run_image_filter(message)

        self.assertTrue(result.matched)
        self.assertFalse(result.message_deleted)
        self.assertFalse(result.block_downstream)
        message.delete.assert_not_awaited()
        punish.assert_not_awaited()
        self.assertIn("fuzzy match", make_log.call_args.kwargs["duration"].lower())
        send_log.assert_awaited_once_with(message.guild, log_embed, view=None)

    async def test_incomplete_inspection_blocks_relay_and_forces_log(self):
        attachment = SimpleNamespace(
            filename="too-large.png",
            content_type="image/png",
            size=IMAGE_FILTER_MAX_BYTES + 1,
            read=AsyncMock(),
        )
        data_manager = SimpleNamespace(config={
            "immunity_list": [],
            "image_filters": {
                "enabled": True,
                "delete_message": True,
                "log_detections": False,
                "entries": [_entry("0123456789abcdef", "banned")],
            },
        })
        message = SimpleNamespace(
            id=100,
            guild=SimpleNamespace(),
            author=self._member(),
            channel=SimpleNamespace(id=123, mention="<#123>"),
            attachments=[attachment],
        )

        with patch.object(
            automod_module,
            "bot",
            SimpleNamespace(data_manager=data_manager),
        ), patch.object(
            automod_module,
            "make_action_log_embed",
            Mock(return_value=object()),
        ), patch.object(
            automod_module,
            "send_automod_log",
            AsyncMock(),
        ) as send_log:
            result = await run_image_filter(message)

        self.assertFalse(result.matched)
        self.assertFalse(result.message_deleted)
        self.assertTrue(result.block_downstream)
        attachment.read.assert_not_awaited()
        send_log.assert_awaited_once()

    async def test_auto_punishment_rejects_protected_members_before_actions(self):
        cases = (
            ("owner", 42, 42, False, 1),
            ("administrator", 43, 42, True, 1),
            ("unmanageable", 44, 42, False, 10),
        )
        for label, member_id, owner_id, administrator, top_role in cases:
            with self.subTest(label=label):
                member = SimpleNamespace(
                    id=member_id,
                    guild_permissions=SimpleNamespace(administrator=administrator),
                    top_role=top_role,
                    timeout=AsyncMock(),
                )
                guild = SimpleNamespace(
                    owner_id=owner_id,
                    me=SimpleNamespace(top_role=10),
                    ban=AsyncMock(),
                    kick=AsyncMock(),
                )

                applied, summary, case_record = await apply_image_filter_punishment(
                    guild,
                    member,
                    entry_label="blocked",
                    punishment_type="ban",
                    duration_minutes=60,
                )

                self.assertFalse(applied)
                self.assertIn("Safety check", summary)
                self.assertIsNone(case_record)
                member.timeout.assert_not_awaited()
                guild.ban.assert_not_awaited()
                guild.kick.assert_not_awaited()


class ImageFilterUiTests(unittest.IsolatedAsyncioTestCase):
    async def test_ban_image_context_is_runtime_authorized_and_setup_registers_it(self):
        self.assertIsNone(ban_image_context.default_permissions)

        fake_bot = SimpleNamespace(
            add_cog=AsyncMock(),
            tree=SimpleNamespace(add_command=Mock()),
        )
        await automod_module.setup(fake_bot)

        fake_bot.add_cog.assert_awaited_once()
        fake_bot.tree.add_command.assert_has_calls([
            call(automod_cmd),
            call(ban_image_context),
        ])

    async def test_paginated_removal_reaches_entries_26_through_30(self):
        entries = [
            {"hash": f"{index:016x}", "label": f"Entry {index}"}
            for index in range(1, 31)
        ]
        data_manager = SimpleNamespace(
            config={"image_filters": {"entries": entries}},
            mark_config_dirty=Mock(),
            save_config=AsyncMock(),
        )
        fake_bot = SimpleNamespace(data_manager=data_manager)

        with patch.object(automod_module, "bot", fake_bot), patch.object(
            automod_module,
            "make_embed",
            side_effect=lambda title, description=None, **kwargs: discord.Embed(
                title=title,
                description=description,
            ),
        ):
            view = ImageFiltersView(page=1)
            remove_select = next(
                child for child in view.children
                if isinstance(child, ImageFilterRemoveSelect)
            )
            self.assertEqual(view.page, 1)
            self.assertEqual(
                [option.label for option in remove_select.options],
                [f"Entry {index}" for index in range(26, 31)],
            )
            self.assertIn("26-30 of 30", remove_select.placeholder)

            remove_select._values = [remove_select.options[-1].value]
            interaction = SimpleNamespace(
                guild=SimpleNamespace(icon=None),
                response=SimpleNamespace(edit_message=AsyncMock()),
            )
            await remove_select.callback(interaction)

        remaining = data_manager.config["image_filters"]["entries"]
        self.assertEqual(len(remaining), 29)
        self.assertNotIn("Entry 30", {entry["label"] for entry in remaining})
        data_manager.save_config.assert_awaited_once()
        interaction.response.edit_message.assert_awaited_once()


class ImageFilterEventFlowTests(unittest.IsolatedAsyncioTestCase):
    async def test_block_downstream_still_runs_mention_spam_enforcement(self):
        tracker = defaultdict(lambda: deque(maxlen=10))
        tracker[42].extend([980.0, 990.0])
        fake_bot = SimpleNamespace(data_manager=SimpleNamespace(config={
            "immunity_list": [],
            "mod_roles": [],
            "role_mention_spam_target": 123,
        }))
        author = SimpleNamespace(
            id=42,
            bot=False,
            roles=[],
            guild_permissions=SimpleNamespace(administrator=True),
            display_avatar=SimpleNamespace(url="https://example.invalid/avatar.png"),
            mention="<@42>",
        )
        message = SimpleNamespace(
            id=99,
            type=discord.MessageType.default,
            guild=SimpleNamespace(icon=None),
            author=author,
            attachments=[object()],
            mention_everyone=True,
            role_mentions=[],
            delete=AsyncMock(),
        )
        result = ImageFilterResult(matched=True, message_deleted=True, block_downstream=True)

        with patch.object(events_module, "bot", fake_bot), patch.object(
            events_module,
            "abuse_system",
            SimpleNamespace(mention_spam_tracker=tracker),
        ), patch.object(
            events_module,
            "run_image_filter",
            AsyncMock(return_value=result),
        ) as run_filter, patch.object(
            events_module,
            "punish_rogue_mod",
            AsyncMock(),
        ) as punish, patch.object(events_module.time, "time", return_value=1000.0):
            await events_module.on_message(message)

        run_filter.assert_awaited_once_with(message)
        punish.assert_awaited_once()
        self.assertEqual(punish.await_args.args[:3], (
            message.guild,
            author,
            "Mention Spam (Mass Pings)",
        ))
        message.delete.assert_awaited_once()
        self.assertEqual(list(tracker[42]), [])


if __name__ == "__main__":
    unittest.main()
