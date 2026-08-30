import time
import unittest
from tempfile import TemporaryDirectory
from pathlib import Path
from types import SimpleNamespace
from unittest.mock import AsyncMock
from unittest.mock import patch

from aiohttp.test_utils import TestClient, TestServer

from minecraft_bot.dashboard import DashboardServer, OWNER_ROLE_ID
from minecraft_bot.perks import RANK_ROLES


class MinecraftDashboardSecurityTests(unittest.IsolatedAsyncioTestCase):
    def _dashboard(self):
        config = SimpleNamespace(
            bridge_secret=b"x" * 32,
            dashboard_enabled=True,
            dashboard_host="127.0.0.1",
            dashboard_port=8090,
            dashboard_public_url="http://127.0.0.1:8090",
            dashboard_client_secret="secret",
            guild_id=10,
        )
        bot = SimpleNamespace(config=config)
        return DashboardServer(bot)

    def test_owner_role_is_the_role_mapped_to_luckperms_owner(self):
        mapped = next(role_id for role_id, group, *_ in RANK_ROLES if group == "owner")
        self.assertEqual(OWNER_ROLE_ID, mapped)

    def test_signed_session_rejects_tampering_and_expiration(self):
        dashboard = self._dashboard()
        token = dashboard._sign({"user_id": 1, "csrf": "safe", "exp": int(time.time()) + 60})
        self.assertEqual(dashboard._unsign(token)["csrf"], "safe")
        payload, signature = token.split(".", 1)
        # Not the last character of the signature. A SHA-256 digest is 32 bytes, which
        # base64 encodes in 43 characters carrying 258 bits, so the final character has
        # two bits that decode to nothing: four of the 64 values it could hold produce
        # the same 32 bytes and the tamper goes undetected. Editing it made this test
        # fail on about one run in sixteen, depending only on the timestamp inside the
        # payload. Every earlier character is fully significant.
        self.assertIsNone(dashboard._unsign(
            payload + "." + ("a" if signature[0] != "a" else "b") + signature[1:]
        ))
        self.assertIsNone(dashboard._unsign(
            ("a" if payload[0] != "a" else "b") + payload[1:] + "." + signature
        ))
        expired = dashboard._sign({"user_id": 1, "exp": int(time.time()) - 1})
        self.assertIsNone(dashboard._unsign(expired))

    async def test_role_is_rechecked_in_the_guild(self):
        dashboard = self._dashboard()
        owner = SimpleNamespace(id=1, roles=[SimpleNamespace(id=OWNER_ROLE_ID)])
        outsider = SimpleNamespace(id=2, roles=[SimpleNamespace(id=123)])
        guild = SimpleNamespace(
            get_member=lambda user_id: owner if user_id == 1 else outsider,
            fetch_member=AsyncMock(),
        )
        dashboard.bot.get_guild = lambda _guild_id: guild
        self.assertIs(await dashboard._owner_member(1), owner)
        self.assertIsNone(await dashboard._owner_member(2))

    async def test_public_leaderboard_is_enriched_but_settings_stay_private(self):
        dashboard = self._dashboard()
        uuid = "11111111-1111-1111-1111-111111111111"
        dashboard.bot.bridge = SimpleNamespace(
            latest_leaderboard={
                "generated_at": 1,
                "individual": {"wealth": [{"minecraft_uuid": uuid, "username": "Kai"}]},
                "clan": {"wealth": []},
            },
            latest_game_variables={"variables": [{"key": "secret", "value": 1}]},
        )
        dashboard.bot.data = SimpleNamespace(
            owners_for_uuids=AsyncMock(return_value={uuid: "9"})
        )
        member = SimpleNamespace(name="nine")
        guild = SimpleNamespace(get_member=lambda user_id: member if user_id == 9 else None)
        dashboard.bot.get_guild = lambda _guild_id: guild
        client = TestClient(TestServer(dashboard._app))
        await client.start_server()
        try:
            response = await client.get("/api/leaderboards")
            payload = await response.json()
            row = payload["individual"]["wealth"][0]
            self.assertEqual(row["discord_username"], "nine")
            self.assertIn("mc-heads.net", row["head_url"])
            self.assertIn("mc-heads.net/body", row["skin_url"])
            self.assertNotIn("variables", payload)
            self.assertEqual(response.headers["X-Frame-Options"], "DENY")

            private = await client.get("/api/settings")
            self.assertEqual(private.status, 401)
        finally:
            await client.close()

    async def test_existing_devblog_is_the_site_served_by_the_backend(self):
        dashboard = self._dashboard()
        with TemporaryDirectory() as directory:
            root = Path(directory)
            (root / "leaderboards").mkdir()
            (root / "assets").mkdir()
            (root / "index.html").write_text("existing blog home")
            (root / "leaderboards" / "index.html").write_text("integrated standings")
            (root / "assets" / "style.css").write_text("body{}")
            with patch("minecraft_bot.dashboard.SITE_ROOT", root):
                client = TestClient(TestServer(dashboard._app))
                await client.start_server()
                try:
                    home = await client.get("/")
                    standings = await client.get("/leaderboards/")
                    asset = await client.get("/assets/style.css")
                    self.assertEqual(await home.text(), "existing blog home")
                    self.assertEqual(await standings.text(), "integrated standings")
                    self.assertEqual(asset.status, 200)
                finally:
                    await client.close()


class MinecraftDashboardAssetTests(unittest.TestCase):
    def test_public_page_has_every_surface_and_no_private_odds(self):
        root = Path(__file__).parents[1] / "devblog"
        html = (root / "pages" / "leaderboards.md").read_text()
        html += (root / "pages" / "control.md").read_text()
        script = (root / "static" / "server-dashboard.js").read_text()
        self.assertIn("Player Leaderboards", html)
        self.assertIn("Clan Leaderboards", html)
        self.assertIn("Event Leaderboards", html)
        self.assertIn("CURRENT CLAN BATTLE", html)
        self.assertIn("Authorize with Discord", html)
        self.assertIn("discord_username", script)
        self.assertIn('defaultBoards = ["wealth", "kills"]', script)
        self.assertIn('eventBoards = ["amethyst_airdrops", "amethyst_crates"]', script)
        self.assertIn("slice(0, 10)", script)
        self.assertIn("live-podium", script)
        self.assertIn("skin_url", script)
        self.assertIn("row.icon", script)
        self.assertNotIn("live-tab-icon", script)
        self.assertNotIn("iconSvg", script)
        self.assertNotIn("The individual race", html)
        self.assertNotIn("Teams moving the server", html)
        self.assertNotIn("hidden-amethyst-one-in", html + script)

    def test_top_three_have_distinct_podium_treatments(self):
        root = Path(__file__).parents[1] / "devblog"
        script = (root / "static" / "server-dashboard.js").read_text()
        theme = (root / "theme.py").read_text()
        for tier in ("rank-gold", "rank-silver", "rank-bronze"):
            self.assertIn(tier, script)
            self.assertIn(tier, theme)

    def test_every_clan_icon_has_a_real_minecraft_texture(self):
        root = Path(__file__).parents[1]
        assets = root / "devblog" / "static" / "minecraft-items"
        catalog = root / "minecraft-bridge" / "src" / "main" / "java" / "bot" / "mgx" / "accessbridge" / "ClanIcon.java"
        source = catalog.read_text()
        for icon in (
            "amethyst_shard", "diamond", "emerald", "gold_ingot", "netherite_ingot", "nether_star",
            "ender_pearl", "heart_of_the_sea", "blaze_powder", "echo_shard", "totem_of_undying", "golden_apple",
        ):
            self.assertIn('"' + icon + '"', source)
            self.assertGreater((assets / (icon + ".png")).stat().st_size, 0)
        self.assertGreater((assets / "crate_key.png").stat().st_size, 0)

    def test_control_page_is_secret_but_still_has_a_direct_route(self):
        control = (Path(__file__).parents[1] / "devblog" / "pages" / "control.md").read_text()
        self.assertIn("nav_hidden: true", control)
        self.assertIn('id="control-root"', control)

    def test_dashboard_script_is_static_data_not_an_embedded_secret(self):
        script = (Path(__file__).parents[1] / "devblog" / "static" / "server-dashboard.js").read_text()
        self.assertNotIn("client_secret", script)
        self.assertNotIn("MINECRAFT_BRIDGE_SECRET", script)
        self.assertIn("X-MGX-CSRF", script)


if __name__ == "__main__":
    unittest.main()
