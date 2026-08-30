import time
import unittest
from pathlib import Path
from types import SimpleNamespace
from unittest.mock import AsyncMock

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
        self.assertIsNone(dashboard._unsign(token[:-1] + ("a" if token[-1] != "a" else "b")))
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
            self.assertNotIn("variables", payload)
            self.assertEqual(response.headers["X-Frame-Options"], "DENY")

            private = await client.get("/api/settings")
            self.assertEqual(private.status, 401)
        finally:
            await client.close()


class MinecraftDashboardAssetTests(unittest.TestCase):
    def test_public_page_has_every_surface_and_no_private_odds(self):
        root = Path(__file__).parents[1] / "minecraft_bot" / "dashboard_static"
        html = (root / "index.html").read_text()
        script = (root / "dashboard.js").read_text()
        self.assertIn("PLAYER LEADERBOARDS", html)
        self.assertIn("CLAN LEADERBOARDS", html)
        self.assertIn("CURRENT CLAN BATTLE", html)
        self.assertIn("Continue with Discord", html)
        self.assertIn("discord_username", script)
        self.assertNotIn("hidden-amethyst-one-in", html + script)

    def test_dashboard_script_is_static_data_not_an_embedded_secret(self):
        script = (Path(__file__).parents[1] / "minecraft_bot" / "dashboard_static" / "dashboard.js").read_text()
        self.assertNotIn("client_secret", script)
        self.assertNotIn("MINECRAFT_BRIDGE_SECRET", script)
        self.assertIn("X-MGX-CSRF", script)


if __name__ == "__main__":
    unittest.main()
