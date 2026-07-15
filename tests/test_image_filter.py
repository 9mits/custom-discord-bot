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
    IMAGE_FILTER_MAX_FALSE_POSITIVES,
    IMAGE_FILTER_MAX_PIXELS,
    IMAGE_HASH_DISTANCE_THRESHOLD,
    IMAGE_OCR_MIN_LINE_CONFIDENCE,
    IMAGE_SCAM_CONFIDENCE_THRESHOLD,
    AutoModBridgeSettingsView,
    AutoModChannelSettingsView,
    AutoModDashboardView,
    AutoModImmunityView,
    AutoModPolicyEditorView,
    AutoModRuleBrowserView,
    AutoModSectionSelect,
    ImageFilterRemoveSelect,
    ImageFilterResult,
    ImageFalsePositiveButton,
    ImageInspection,
    ImageFiltersView,
    ImageMatch,
    ScamContentMatch,
    apply_image_filter_punishment,
    automod_cmd,
    ban_image_context,
    detect_mrbeast_crypto_scam,
    decode_image_feedback_fingerprint,
    encode_image_feedback_fingerprint,
    fingerprint_image_bytes,
    hash_distance,
    hash_image_bytes,
    image_match_allows_punishment,
    image_match_similarity,
    inspect_image_bytes,
    match_banned_image,
    normalize_image_filter_settings,
    resolve_image_filter_server_url,
    run_image_filter,
)
from cogs.roles import build_appeal_view


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


def _action_log_embed(title, description, **kwargs):
    embed = discord.Embed(title=title, description=description)
    for name, key in (("Actor", "actor"), ("Target", "target"), ("Reason", "reason")):
        if kwargs.get(key):
            embed.add_field(name=name, value=kwargs[key], inline=name != "Reason")
    if kwargs.get("thumbnail"):
        embed.set_thumbnail(url=kwargs["thumbnail"])
    return embed


class ImageFilterSettingsTests(unittest.TestCase):
    def test_normalize_defaults(self):
        settings = normalize_image_filter_settings({})
        self.assertFalse(settings["enabled"])
        self.assertTrue(settings["delete_message"])
        self.assertTrue(settings["log_detections"])
        self.assertTrue(settings["scan_scam_content"])
        self.assertFalse(settings["punish"])
        self.assertEqual(settings["punishment_type"], "warn")
        self.assertEqual(settings["duration_minutes"], 60)
        self.assertEqual(settings["entries"], [])
        self.assertEqual(settings["false_positives"], [])

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
        self.assertEqual(entry["url"], "")
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

    def test_normalize_caps_learned_false_positives(self):
        entries = [{"hash": f"{index + 1:016x}"} for index in range(IMAGE_FILTER_MAX_FALSE_POSITIVES + 10)]
        settings = normalize_image_filter_settings({"false_positives": entries})
        self.assertEqual(len(settings["false_positives"]), IMAGE_FILTER_MAX_FALSE_POSITIVES)


class ImageContentDetectionTests(unittest.TestCase):
    def test_detects_changed_mrbeast_crypto_scam_copy(self):
        variants = (
            "MR BEA5T CRYPTO GIVEAWAY SEND 0.1 BTC TO THIS WALLET ADDRESS AND RECEIVE DOUBLE BACK",
            "MrBeast Ethereum promotion. Claim your reward now and scan the QR code.",
            "MRBEAST BITCOIN AIRDROP - deposit BTC at the contribution address to get 2x back",
        )
        for text in variants:
            with self.subTest(text=text):
                match = detect_mrbeast_crypto_scam([(text, 0.9)])
                self.assertTrue(match.matched)
                self.assertGreaterEqual(match.confidence, IMAGE_SCAM_CONFIDENCE_THRESHOLD)
                self.assertIn("MrBeast", match.signals)
                self.assertIn("crypto", match.signals)
                self.assertIn("solicitation", match.signals)
                self.assertIn("MrBeast", match.matched_terms)
                self.assertGreaterEqual(len(match.matched_terms), 4)

    def test_legitimate_or_unrelated_images_do_not_match(self):
        safe_examples = (
            "MrBeast explains Bitcoin in a new video",
            "MrBeast gave away Bitcoin to subscribers",
            "MrBeast giveaway claim your prize",
            "Crypto giveaway: send BTC to this wallet and receive double back",
            "MrBeast official channel announcement",
        )
        for text in safe_examples:
            with self.subTest(text=text):
                self.assertFalse(detect_mrbeast_crypto_scam([text]).matched)

    def test_low_confidence_ocr_text_cannot_trigger(self):
        match = detect_mrbeast_crypto_scam([(
            "MRBEAST CRYPTO GIVEAWAY SEND BTC TO WALLET AND RECEIVE DOUBLE BACK",
            IMAGE_OCR_MIN_LINE_CONFIDENCE - 0.01,
        )])
        self.assertFalse(match.matched)
        self.assertEqual(match.confidence, 0)

    def test_image_inspection_runs_local_ocr_only_when_requested(self):
        data = _png_bytes((30, 60, 90))
        ocr_lines = ((
            "MRBEAST CRYPTO GIVEAWAY SEND BTC TO WALLET AND RECEIVE DOUBLE BACK",
            0.95,
        ),)
        with patch.object(automod_module, "_run_local_image_ocr", return_value=ocr_lines) as run_ocr:
            normal = inspect_image_bytes(data)
            analyzed = inspect_image_bytes(data, analyze_content=True)

        self.assertFalse(normal.content_match.matched)
        self.assertTrue(analyzed.content_match.matched)
        run_ocr.assert_called_once()

    def test_high_confidence_content_match_can_auto_punish(self):
        match = ImageMatch(
            entry={"label": "MrBeast crypto scam"},
            quality="content",
            confidence=IMAGE_SCAM_CONFIDENCE_THRESHOLD,
        )
        self.assertTrue(image_match_allows_punishment("kick", match))
        self.assertEqual(image_match_similarity(match), IMAGE_SCAM_CONFIDENCE_THRESHOLD)


class ImageFingerprintTests(unittest.TestCase):
    def test_hash_distance(self):
        self.assertEqual(hash_distance("0" * 16, "0" * 16), 0)
        self.assertEqual(hash_distance("0" * 16, "f" + "0" * 15), 4)

    def test_similarity_hides_internal_metrics_behind_one_percentage(self):
        exact = ImageMatch(entry={"label": "exact"}, quality="exact")
        strong = ImageMatch(
            entry={"label": "strong"},
            distance=2,
            vertical_distance=3,
            color_distance=20,
            quality="strong",
        )
        legacy = ImageMatch(entry={"label": "legacy"}, distance=8, quality="legacy")

        self.assertEqual(image_match_similarity(exact), 100)
        self.assertGreaterEqual(image_match_similarity(strong), 90)
        self.assertEqual(image_match_similarity(legacy), 88)
        self.assertEqual(image_match_similarity(ImageMatch()), 0)

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

    def test_feedback_fingerprint_round_trip_fits_a_discord_custom_id(self):
        fingerprint = fingerprint_image_bytes(_png_bytes((30, 60, 90)))
        payload = encode_image_feedback_fingerprint(fingerprint)

        self.assertEqual(len(payload), 75)
        self.assertEqual(decode_image_feedback_fingerprint(payload), fingerprint)
        self.assertLessEqual(len(f"imagefp:false_positive:{payload}"), 100)
        self.assertIsNone(decode_image_feedback_fingerprint("invalid"))

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
        member.guild_permissions = SimpleNamespace(administrator=False)
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

    async def test_moderators_are_immune_before_image_inspection(self):
        member = self._member()
        member.roles = [SimpleNamespace(id=900)]
        data_manager = SimpleNamespace(config={
            "role_mod": 900,
            "immunity_list": [],
            "image_filters": {
                "enabled": True,
                "scan_scam_content": True,
                "entries": [],
            },
        })
        message = SimpleNamespace(
            guild=SimpleNamespace(),
            author=member,
            channel=SimpleNamespace(id=123),
            attachments=[SimpleNamespace(size=100, filename="image.png")],
        )

        with patch.object(automod_module, "bot", SimpleNamespace(data_manager=data_manager)), patch.object(
            automod_module,
            "inspect_image_attachment",
            AsyncMock(),
        ) as inspect_attachment:
            result = await run_image_filter(message)

        self.assertFalse(result.matched)
        inspect_attachment.assert_not_awaited()

    async def test_trusted_detection_dms_user_when_auto_punish_is_off(self):
        fingerprint = fingerprint_image_bytes(_png_bytes((30, 60, 90)))
        attachment = SimpleNamespace(
            filename="blocked.png",
            content_type="image/png",
            url="https://example.invalid/blocked.png",
            size=100,
        )
        data_manager = SimpleNamespace(config={
            "immunity_list": [],
            "image_filters": {
                "enabled": True,
                "delete_message": True,
                "log_detections": False,
                "scan_scam_content": False,
                "punish": False,
                "entries": [_fingerprint_entry(fingerprint, "blocked image")],
            },
        })
        message = SimpleNamespace(
            id=102,
            guild=SimpleNamespace(),
            author=self._member(),
            channel=SimpleNamespace(id=123),
            attachments=[attachment],
            delete=AsyncMock(),
        )
        inspection = ImageInspection(fingerprints=(fingerprint,), is_image=True)

        with patch.object(automod_module, "bot", SimpleNamespace(data_manager=data_manager)), patch.object(
            automod_module,
            "inspect_image_attachment",
            AsyncMock(return_value=inspection),
        ), patch.object(
            automod_module,
            "resolve_image_filter_server_url",
            AsyncMock(return_value="https://discord.com/channels/1"),
        ), patch.object(
            automod_module,
            "send_image_filter_user_dm",
            AsyncMock(return_value=True),
        ) as send_dm:
            result = await run_image_filter(message)

        self.assertTrue(result.matched)
        self.assertTrue(result.message_deleted)
        message.delete.assert_awaited_once()
        send_dm.assert_awaited_once()
        self.assertEqual(send_dm.await_args.kwargs["action_label"], "Image Removed")
        self.assertEqual(send_dm.await_args.kwargs["entry_label"], "blocked image")

    async def test_staff_learned_false_positive_bypasses_future_enforcement(self):
        fingerprint = fingerprint_image_bytes(_png_bytes((30, 60, 90)))
        attachment = SimpleNamespace(filename="safe.png", content_type="image/png", size=100)
        data_manager = SimpleNamespace(config={
            "immunity_list": [],
            "image_filters": {
                "enabled": True,
                "delete_message": True,
                "log_detections": True,
                "scan_scam_content": True,
                "punish": True,
                "entries": [_fingerprint_entry(fingerprint, "blocked")],
                "false_positives": [_fingerprint_entry(fingerprint, "reviewed safe")],
            },
        })
        message = SimpleNamespace(
            guild=SimpleNamespace(),
            author=self._member(),
            channel=SimpleNamespace(id=123),
            attachments=[attachment],
            delete=AsyncMock(),
        )
        inspection = ImageInspection(
            fingerprints=(fingerprint,),
            content_match=ScamContentMatch(matched=True, confidence=100, category="MrBeast crypto scam"),
            is_image=True,
        )

        with patch.object(automod_module, "bot", SimpleNamespace(data_manager=data_manager)), patch.object(
            automod_module,
            "inspect_image_attachment",
            AsyncMock(return_value=inspection),
        ), patch.object(automod_module, "send_image_filter_user_dm", AsyncMock()) as send_dm, patch.object(
            automod_module,
            "apply_image_filter_punishment",
            AsyncMock(),
        ) as punish, patch.object(automod_module, "send_automod_log", AsyncMock()) as send_log:
            result = await run_image_filter(message)

        self.assertFalse(result.matched)
        message.delete.assert_not_awaited()
        send_dm.assert_not_awaited()
        punish.assert_not_awaited()
        send_log.assert_not_awaited()

    async def test_content_detection_logs_punishment_format_and_dual_images(self):
        submitted_fingerprint = fingerprint_image_bytes(_png_bytes((30, 60, 90), stripe=(10, 200, 30)))
        reference_fingerprint = fingerprint_image_bytes(_png_bytes((180, 40, 20), stripe=(220, 220, 20)))
        attachment = SimpleNamespace(
            filename="daily-scam.png",
            content_type="image/png",
            url="https://example.invalid/daily-scam.png",
            size=100,
        )
        data_manager = SimpleNamespace(config={
            "immunity_list": [],
            "image_filters": {
                "enabled": True,
                "delete_message": True,
                "log_detections": True,
                "scan_scam_content": True,
                "punish": True,
                "punishment_type": "kick",
                "entries": [{
                    **_fingerprint_entry(reference_fingerprint, "known scam reference"),
                    "url": "https://example.invalid/reference.png",
                }],
            },
        })
        message = SimpleNamespace(
            id=101,
            jump_url="https://discord.com/channels/1/123/101",
            guild=SimpleNamespace(icon=None),
            author=self._member(),
            channel=SimpleNamespace(id=123, mention="<#123>"),
            attachments=[attachment],
            delete=AsyncMock(),
        )
        inspection = ImageInspection(
            content_match=ScamContentMatch(
                matched=True,
                confidence=96,
                category="MrBeast crypto scam",
                signals=("MrBeast", "crypto", "solicitation", "promised return"),
                matched_terms=("MrBeast", "crypto", "send", "double"),
                text="mrbeast crypto send btc receive double back",
            ),
            fingerprints=(submitted_fingerprint,),
            is_image=True,
        )

        with patch.object(
            automod_module,
            "bot",
            SimpleNamespace(data_manager=data_manager),
        ), patch.object(
            automod_module,
            "inspect_image_attachment",
            AsyncMock(return_value=inspection),
        ) as inspect_attachment, patch.object(
            automod_module,
            "make_action_log_embed",
            side_effect=_action_log_embed,
        ), patch.object(
            automod_module,
            "send_automod_log",
            AsyncMock(),
        ) as send_log, patch.object(
            automod_module,
            "apply_image_filter_punishment",
            AsyncMock(return_value=(
                True,
                "Applied Kick automatically",
                {
                    "case_id": 55,
                    "type": "kick",
                    "duration_minutes": 0,
                    "timestamp": "2026-07-15T00:00:00+00:00",
                    "reason": "Banned image posted [MrBeast crypto scam]",
                },
                True,
            )),
        ) as punish:
            result = await run_image_filter(message)

        self.assertTrue(result.matched)
        self.assertTrue(result.message_deleted)
        self.assertTrue(result.block_downstream)
        inspect_attachment.assert_awaited_once_with(attachment, analyze_content=True)
        message.delete.assert_awaited_once()
        punish.assert_awaited_once()
        self.assertEqual(punish.await_args.kwargs["entry_label"], "MrBeast crypto scam")
        logged_embed = send_log.await_args.args[1]
        fields = {field.name: field.value for field in logged_embed.fields}
        self.assertEqual(logged_embed.title, "[Case #55] Banned Image Detected")
        self.assertIn("Automated Image Detection", fields["Actor"])
        self.assertIn("<@42>", fields["Target"])
        self.assertIn("Local OCR", fields["Detection"])
        self.assertIn("Confidence: 96%", fields["Detection"])
        self.assertEqual(fields["Matched Words"], "`MrBeast`, `crypto`, `send`, `double`")
        self.assertIn("[known scam reference](https://example.invalid/reference.png)", fields["Matched Reference"])
        self.assertRegex(fields["Matched Reference"], r"• \d+% visual similarity$")
        self.assertEqual(fields["Submitted Image"], "[Open image](https://example.invalid/daily-scam.png)")
        self.assertEqual(fields["DM"], "Delivered")
        self.assertEqual(logged_embed.thumbnail.url, "https://example.invalid/reference.png")
        self.assertEqual(logged_embed.image.url, "https://example.invalid/daily-scam.png")
        self.assertEqual(len(send_log.await_args.kwargs["view"].children), 2)

    async def test_fuzzy_match_is_log_only(self):
        original = fingerprint_image_bytes(
            _png_bytes((80, 100, 120), size=(64, 64), structured=False)
        )
        resized_data = _png_bytes((80, 100, 120), size=(128, 128), structured=False)
        attachment = SimpleNamespace(
            filename="near-copy.png",
            content_type="image/png",
            url="https://example.invalid/flagged.png",
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
                "entries": [{
                    **_fingerprint_entry(original, "flat image"),
                    "url": "https://example.invalid/original.png",
                }],
            },
        })
        message = SimpleNamespace(
            id=99,
            jump_url="https://discord.com/channels/1/123/99",
            guild=SimpleNamespace(icon=None),
            author=self._member(),
            channel=SimpleNamespace(id=123, mention="<#123>"),
            attachments=[attachment],
            delete=AsyncMock(),
        )
        with patch.object(
            automod_module,
            "bot",
            SimpleNamespace(data_manager=data_manager),
        ), patch.object(
            automod_module,
            "make_action_log_embed",
            side_effect=_action_log_embed,
        ), patch.object(
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
        send_log.assert_awaited_once()
        logged_embed = send_log.await_args.args[1]
        fields = {field.name: field.value for field in logged_embed.fields}
        self.assertEqual(
            set(fields),
            {"Actor", "Target", "Reason", "Detection", "Matched Reference", "Submitted Image", "Result", "DM", "Message ID"},
        )
        self.assertIn("[flat image](https://example.invalid/original.png)", fields["Matched Reference"])
        self.assertRegex(fields["Detection"], r"Confidence: \d+%")
        self.assertIn("fuzzy match", fields["Result"].lower())
        self.assertNotIn("Message Deleted", fields)
        self.assertNotIn("Inspection Complete", fields)
        self.assertEqual(logged_embed.image.url, "https://example.invalid/flagged.png")
        self.assertEqual(len(send_log.await_args.kwargs["view"].children), 1)
        self.assertIsInstance(send_log.await_args.kwargs["view"].children[0], ImageFalsePositiveButton)

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
            jump_url="https://discord.com/channels/1/123/100",
            guild=SimpleNamespace(icon=None),
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
            "make_embed",
            side_effect=lambda title, description=None, **kwargs: discord.Embed(
                title=title,
                description=description,
            ),
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
        logged_embed = send_log.await_args.args[1]
        self.assertEqual(
            {field.name for field in logged_embed.fields},
            {"Action", "Message ID"},
        )

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

                applied, summary, case_record, dm_sent = await apply_image_filter_punishment(
                    guild,
                    member,
                    entry_label="blocked",
                    punishment_type="ban",
                    duration_minutes=60,
                )

                self.assertFalse(applied)
                self.assertIn("Safety check", summary)
                self.assertIsNone(case_record)
                self.assertFalse(dm_sent)
                member.timeout.assert_not_awaited()
                guild.ban.assert_not_awaited()
                guild.kick.assert_not_awaited()

    async def test_automatic_kick_sends_dm_with_disclaimer_and_return_link(self):
        dm_channel = SimpleNamespace(send=AsyncMock())
        member = SimpleNamespace(
            id=42,
            display_name="Member",
            guild_permissions=SimpleNamespace(administrator=False),
            roles=[],
            top_role=1,
            create_dm=AsyncMock(return_value=dm_channel),
            timeout=AsyncMock(),
        )
        guild = SimpleNamespace(
            id=123,
            name="Test Server",
            owner_id=999,
            me=SimpleNamespace(top_role=10),
            icon=None,
            kick=AsyncMock(),
            ban=AsyncMock(),
        )
        data_manager = SimpleNamespace(
            config={"stats": {}},
            add_punishment=AsyncMock(return_value={"case_id": 55}),
            save_config=AsyncMock(),
        )
        return_view = object()

        with patch.object(
            automod_module,
            "bot",
            SimpleNamespace(user=SimpleNamespace(id=777), data_manager=data_manager),
        ), patch.object(
            automod_module,
            "is_staff_member",
            return_value=False,
        ), patch.object(
            automod_module,
            "resolve_image_filter_server_url",
            AsyncMock(return_value="https://discord.gg/return"),
        ), patch.object(
            automod_module,
            "build_appeal_view",
            return_value=return_view,
        ) as build_view, patch.object(
            automod_module,
            "make_embed",
            side_effect=lambda title, description=None, **kwargs: discord.Embed(
                title=title,
                description=description,
            ),
        ):
            applied, summary, case_record, dm_sent = await apply_image_filter_punishment(
                guild,
                member,
                entry_label="MrBeast crypto scam",
                punishment_type="kick",
                duration_minutes=60,
            )

        self.assertTrue(applied)
        self.assertEqual(summary, "Applied Kick automatically")
        self.assertEqual(case_record["case_id"], 55)
        self.assertTrue(dm_sent)
        member.create_dm.assert_awaited_once()
        guild.kick.assert_awaited_once()
        dm_channel.send.assert_awaited_once()
        sent_embed = dm_channel.send.await_args.kwargs["embed"]
        notice = next(field.value for field in sent_embed.fields if field.name == "Automated Detection Notice")
        self.assertIn("handled automatically", notice)
        self.assertIn("False positives are possible", notice)
        build_view.assert_called_once_with(123, 55, server_url="https://discord.gg/return")
        self.assertIs(dm_channel.send.await_args.kwargs["view"], return_view)


class ImageFilterUiTests(unittest.IsolatedAsyncioTestCase):
    async def test_false_positive_button_persists_learned_exception(self):
        fingerprint = fingerprint_image_bytes(_png_bytes((30, 60, 90)))
        button = ImageFalsePositiveButton(fingerprint)
        match = ImageFalsePositiveButton.__discord_ui_compiled_template__.fullmatch(button.item.custom_id)
        restored = await ImageFalsePositiveButton.from_custom_id(SimpleNamespace(), button.item, match)
        self.assertEqual(restored.fingerprint, fingerprint)

        data_manager = SimpleNamespace(
            config={"image_filters": {}},
            mark_config_dirty=Mock(),
            save_config=AsyncMock(),
        )
        interaction = SimpleNamespace(
            guild=SimpleNamespace(icon=None),
            user=SimpleNamespace(id=900),
            message=None,
            response=SimpleNamespace(send_message=AsyncMock()),
        )
        with patch.object(
            automod_module,
            "bot",
            SimpleNamespace(data_manager=data_manager),
        ), patch.object(automod_module, "is_staff", return_value=True), patch.object(
            automod_module,
            "make_confirmation_embed",
            side_effect=lambda title, description=None, **kwargs: discord.Embed(title=title, description=description),
        ):
            await button.callback(interaction)

        learned = data_manager.config["image_filters"]["false_positives"]
        self.assertEqual(len(learned), 1)
        self.assertEqual(learned[0]["sha256"], fingerprint["sha256"])
        self.assertEqual(learned[0]["added_by"], 900)
        data_manager.mark_config_dirty.assert_called()
        data_manager.save_config.assert_awaited_once()
        interaction.response.send_message.assert_awaited_once()

    async def test_punishment_dm_view_adds_return_link_only_when_available(self):
        return_view = build_appeal_view(123, 55, server_url="https://discord.gg/return")
        self.assertEqual([
            getattr(getattr(item, "item", item), "label", None)
            for item in return_view.children
        ], [
            "Appeal Punishment",
            "Return to Server",
        ])
        return_button = return_view.children[1]
        self.assertEqual(return_button.style, discord.ButtonStyle.link)
        self.assertEqual(return_button.url, "https://discord.gg/return")

        ban_view = build_appeal_view(123, 56)
        self.assertEqual([
            getattr(getattr(item, "item", item), "label", None)
            for item in ban_view.children
        ], ["Appeal Punishment"])

    async def test_kick_return_url_reuses_permanent_invite(self):
        invite = SimpleNamespace(
            max_age=0,
            max_uses=0,
            uses=12,
            url="https://discord.gg/existing",
        )
        guild = SimpleNamespace(
            id=123,
            vanity_invite=AsyncMock(return_value=None),
            invites=AsyncMock(return_value=[invite]),
        )

        self.assertEqual(
            await resolve_image_filter_server_url(guild, "kick"),
            "https://discord.gg/existing",
        )
        self.assertIsNone(await resolve_image_filter_server_url(guild, "ban"))

    async def test_automod_dashboard_uses_one_section_menu(self):
        view = AutoModDashboardView()
        self.assertEqual(len(view.children), 1)
        self.assertIsInstance(view.children[0], AutoModSectionSelect)
        self.assertFalse(any(getattr(child, "label", "") == "Back" for child in view.children))

    async def test_automod_subpanels_use_section_menu_without_back_buttons(self):
        data_manager = SimpleNamespace(config={"native_automod": {}})
        with patch.object(automod_module, "bot", SimpleNamespace(data_manager=data_manager)):
            views = [
                AutoModBridgeSettingsView(),
                AutoModRuleBrowserView([]),
                AutoModPolicyEditorView(),
                AutoModChannelSettingsView(),
                AutoModImmunityView(),
            ]

        for view in views:
            with self.subTest(view=type(view).__name__):
                self.assertEqual(
                    sum(isinstance(child, AutoModSectionSelect) for child in view.children),
                    1,
                )
                self.assertFalse(any(getattr(child, "label", "") == "Back" for child in view.children))

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
