import re
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
            self.assertIn("api.mcheads.org/ioshead", row["head_url"])
            self.assertIn("api.mcheads.org/iosbody", row["skin_url"])
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
        self.assertIn("ioshead/MHF_Steve/left", script)
        self.assertIn('data-fallback="', script)
        self.assertNotIn("live-avatar-fallback", script)
        self.assertIn("row.icon", script)
        self.assertNotIn("clanIcons", script)
        self.assertIn('active ? "Current Clan Battle" : "No active battle"', script)
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

    def test_leaderboard_heads_are_not_rounded_or_cropped(self):
        theme = (Path(__file__).parents[1] / "devblog" / "theme.py").read_text()

        self.assertIn("img.live-head-render", theme)
        self.assertIn("border-radius: 0; object-fit: contain", theme)
        self.assertIn("width: 9.5rem; max-width: 100%; height: 9.5rem; object-fit: contain", theme)
        self.assertIn("object-position: center bottom", theme)

    def test_statistics_heads_use_the_same_steve_fallback(self):
        script = (
            Path(__file__).parents[1] / "devblog" / "static" / "server-statistics.js"
        ).read_text()

        self.assertIn("ioshead/MHF_Steve/left", script)
        self.assertIn("wireHeadFallbacks", script)
        self.assertIn("data-head-fallback", script)

    def test_every_clan_icon_has_a_real_minecraft_texture(self):
        root = Path(__file__).parents[1]
        assets = root / "devblog" / "static" / "minecraft-items"
        catalog = root / "minecraft-bridge" / "src" / "main" / "java" / "bot" / "mgx" / "accessbridge" / "ClanIcon.java"
        source = catalog.read_text()
        icons = re.findall(r'^\s+[A-Z0-9_]+\("([a-z0-9_]+)"', source, re.MULTILINE)
        self.assertEqual(len(icons), 97)
        for icon in icons:
            with self.subTest(icon=icon):
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


class MinecraftStatisticsTests(unittest.IsolatedAsyncioTestCase):
    """The statistics surface: owner-gated API, and a page that cannot fake a chart."""

    def _dashboard(self, *, afk=None, series=None, metrics=None):
        config = SimpleNamespace(
            bridge_secret=b"x" * 32,
            dashboard_enabled=True,
            dashboard_host="127.0.0.1",
            dashboard_port=8090,
            dashboard_public_url="http://127.0.0.1:8090",
            dashboard_client_secret="secret",
            guild_id=10,
        )
        data = SimpleNamespace(
            player_activity_metrics=AsyncMock(return_value={"current": 2, "peak": 5}),
            access_status_counts=AsyncMock(return_value={"VERIFIED": 7}),
            afk_metrics=AsyncMock(return_value=afk if afk is not None else {"players": []}),
            owners_for_uuids=AsyncMock(return_value={}),
            stat_metrics=AsyncMock(return_value=metrics or []),
            stat_series=AsyncMock(return_value=series or []),
        )
        bot = SimpleNamespace(
            config=config,
            data=data,
            bridge=SimpleNamespace(latest_leaderboard={}),
            get_guild=lambda _id: None,
        )
        return DashboardServer(bot)

    async def _client(self, dashboard):
        client = TestClient(TestServer(dashboard._app))
        await client.start_server()
        self.addAsyncCleanup(client.close)
        return client

    async def test_statistics_require_the_owner_role(self):
        client = await self._client(self._dashboard())
        for path in ("/api/stats", "/api/stats/series?metric=players.online"):
            response = await client.get(path)
            self.assertEqual(response.status, 401, path)

    async def test_the_overview_carries_activity_access_and_afk(self):
        dashboard = self._dashboard(
            afk={
                "players": [{
                    "minecraft_uuid": "u", "username": "Someone",
                    "edition": "JAVA", "afk_seconds": 3600, "sessions": 2,
                }],
                "total_seconds": 3600,
                "by_edition": {"JAVA": 3600, "BEDROCK": 0},
                "peak_afk": 1,
            },
            metrics=["players.online"],
        )
        with patch.object(dashboard, "_require_owner", AsyncMock(return_value=({}, None))):
            client = await self._client(dashboard)
            body = await (await client.get("/api/stats?days=7")).json()

        self.assertEqual(body["days"], 7)
        self.assertEqual(body["activity"]["peak"], 5)
        self.assertEqual(body["access"]["VERIFIED"], 7)
        self.assertEqual(body["afk"]["by_edition"]["JAVA"], 3600)
        self.assertEqual(body["metrics"], ["players.online"])
        # A head URL is added so the table can draw a face without a second round trip.
        self.assertIn("head_url", body["afk"]["players"][0])

    async def test_the_series_endpoint_is_capped_so_one_request_is_not_hundreds(self):
        dashboard = self._dashboard(series=[{"at": 1, "value": 2.0}])
        with patch.object(dashboard, "_require_owner", AsyncMock(return_value=({}, None))):
            client = await self._client(dashboard)
            wanted = ",".join("metric%d" % i for i in range(60))
            body = await (await client.get("/api/stats/series?metric=" + wanted)).json()
        self.assertEqual(len(body["series"]), 24)

    async def test_an_out_of_range_window_falls_back_rather_than_erroring(self):
        dashboard = self._dashboard()
        with patch.object(dashboard, "_require_owner", AsyncMock(return_value=({}, None))):
            client = await self._client(dashboard)
            for query in ("days=abc", "days=-5", "days=99999"):
                response = await client.get("/api/stats?" + query)
                self.assertEqual(response.status, 200, query)
                self.assertTrue(1 <= (await response.json())["days"] <= 365)


class MinecraftStatisticsAssetTests(unittest.TestCase):
    def _files(self):
        root = Path(__file__).parents[1] / "devblog"
        return (
            (root / "pages" / "statistics.md").read_text(),
            (root / "static" / "server-statistics.js").read_text(),
        )

    def test_the_page_declares_its_own_layout_and_stays_out_of_public_nav(self):
        page, _ = self._files()
        self.assertIn("layout: statistics", page)
        self.assertIn("nav_hidden: true", page)
        self.assertIn("Owner access only", page)

    def test_charts_are_hand_built_rather_than_a_blocked_cdn(self):
        _, script = self._files()
        self.assertIn("http://www.w3.org/2000/svg", script)
        for banned in ("cdn.", "chart.js", "d3.", "unpkg", "jsdelivr", "<script"):
            self.assertNotIn(banned, script.lower())

    def test_a_metric_with_no_history_says_so_rather_than_drawing_a_flat_line(self):
        _, script = self._files()
        # "No data yet" and "the value was zero" are different facts.
        self.assertIn("No samples in this window yet", script)
        self.assertIn("A line needs two", script)
        self.assertIn("points.length < 2", script)

    def test_every_chart_is_labelled_with_a_scale(self):
        _, script = self._files()
        self.assertIn("stat-grid", script)
        self.assertIn("stat-axis", script)
        self.assertIn("toLocaleDateString", script)

    def test_owner_only_values_are_never_rendered_by_this_page(self):
        page, script = self._files()
        self.assertNotIn("hidden-amethyst-one-in", page + script)


class ConfigChangeSetEndpointTests(unittest.IsolatedAsyncioTestCase):
    """Draft, validate, publish, roll back — and what each one writes to the trail."""

    def _dashboard(self, *, changeset=None, snapshot=None):
        config = SimpleNamespace(
            bridge_secret=b"x" * 32,
            dashboard_enabled=True,
            dashboard_host="127.0.0.1",
            dashboard_port=8090,
            dashboard_public_url="http://127.0.0.1:8090",
            dashboard_client_secret="secret",
            guild_id=10,
        )
        data = SimpleNamespace(
            list_accounts_for_user=AsyncMock(return_value=[{"minecraft_uuid": "uuid-1"}]),
        )
        bot = SimpleNamespace(
            config=config,
            data=data,
            bridge=SimpleNamespace(
                latest_game_variables=snapshot if snapshot is not None else {
                    "variables": [{"key": "crate.default.key-cost", "label": "Default crate key cost"}],
                    "history": [{"id": "abc", "changes": [], "change_count": 0}],
                },
                run_config_changeset=AsyncMock(
                    return_value=changeset
                    if changeset is not None
                    else (True, "Published 1 change(s).", {
                        "publish_id": "abc",
                        "changes": [
                            {"key": "crate.default.key-cost", "before": 1, "after": 3}
                        ],
                    })
                ),
            ),
            get_guild=lambda _id: None,
        )
        return DashboardServer(bot)

    async def _owner_client(self, dashboard):
        member = SimpleNamespace(id=9, name="mits")
        patcher = patch.object(
            dashboard, "_require_owner", AsyncMock(return_value=({"csrf": "t"}, member))
        )
        patcher.start()
        self.addCleanup(patcher.stop)
        csrf = patch.object(dashboard, "_require_csrf", lambda *_args, **_kwargs: None)
        csrf.start()
        self.addCleanup(csrf.stop)
        client = TestClient(TestServer(dashboard._app))
        await client.start_server()
        self.addAsyncCleanup(client.close)
        return client

    async def test_change_set_endpoints_require_the_owner_role(self):
        dashboard = self._dashboard()
        client = TestClient(TestServer(dashboard._app))
        await client.start_server()
        self.addAsyncCleanup(client.close)
        for path in ("/api/settings/validate", "/api/settings/publish", "/api/settings/rollback"):
            response = await client.post(path, json={"edits": [{"key": "a", "value": "1"}]})
            self.assertEqual(response.status, 401, path)
        self.assertEqual((await client.get("/api/settings/history")).status, 401)

    async def test_each_fixed_settings_path_reaches_its_own_handler(self):
        # /api/settings/{key} is a greedy wildcard sitting over the same prefix. aiohttp
        # prefers plain paths, so this holds today; the point of asserting it is that the
        # panel breaks quietly rather than loudly if that ever stops being true.
        dashboard = self._dashboard()
        client = await self._owner_client(dashboard)
        body = await (await client.get("/api/settings/history")).json()
        self.assertEqual(body["history"][0]["id"], "abc")
        # /api/settings returns the whole snapshot and would also carry "history", so
        # the absence of "variables" is what proves which handler answered.
        self.assertNotIn("variables", body)

    async def test_publish_records_one_audit_row_per_value_with_both_numbers(self):
        dashboard = self._dashboard()
        client = await self._owner_client(dashboard)
        with patch("minecraft_bot.dashboard.deliver", AsyncMock()) as deliver:
            response = await client.post(
                "/api/settings/publish",
                json={"edits": [{"key": "crate.default.key-cost", "value": "3"}]},
            )
            body = await response.json()

        self.assertTrue(body["ok"])
        self.assertEqual(body["publish_id"], "abc")
        record = deliver.await_args_list[0].args[1]
        options = dict(record.options)
        self.assertEqual(options["key"], "crate.default.key-cost")
        self.assertEqual(options["previous"], "1")
        self.assertEqual(options["value"], "3")
        self.assertEqual(options["publish"], "abc")
        # The label makes the trail readable without looking the key up.
        self.assertEqual(options["label"], "Default crate key cost")

    async def test_a_rejected_publish_returns_findings_and_records_the_refusal(self):
        dashboard = self._dashboard(changeset=(
            False,
            "Every weight in airdrop.rarity would be zero.",
            {"findings": [{"key": "airdrop.rarity.common.weight", "message": "Leave one above zero."}]},
        ))
        client = await self._owner_client(dashboard)
        with patch("minecraft_bot.dashboard.deliver", AsyncMock()) as deliver:
            response = await client.post(
                "/api/settings/publish",
                json={"edits": [{"key": "airdrop.rarity.common.weight", "value": "0"}]},
            )
            body = await response.json()

        self.assertEqual(response.status, 409)
        self.assertFalse(body["ok"])
        self.assertEqual(body["findings"][0]["key"], "airdrop.rarity.common.weight")
        self.assertEqual(dict(deliver.await_args_list[0].args[1].options)["outcome"], "rejected")

    async def test_validate_writes_nothing_to_the_trail(self):
        dashboard = self._dashboard(changeset=(True, "The change set is valid.", {"findings": []}))
        client = await self._owner_client(dashboard)
        with patch("minecraft_bot.dashboard.deliver", AsyncMock()) as deliver:
            body = await (await client.post(
                "/api/settings/validate",
                json={"edits": [{"key": "crate.default.key-cost", "value": "3"}]},
            )).json()

        self.assertTrue(body["valid"])
        deliver.assert_not_awaited()

    async def test_an_empty_change_set_is_refused_before_it_reaches_the_bridge(self):
        dashboard = self._dashboard()
        client = await self._owner_client(dashboard)
        for payload in ({}, {"edits": []}, {"edits": [{"value": "3"}]}):
            response = await client.post("/api/settings/publish", json=payload)
            self.assertEqual(response.status, 400, payload)
        dashboard.bot.bridge.run_config_changeset.assert_not_awaited()

    async def test_rollback_needs_the_publish_it_undoes(self):
        dashboard = self._dashboard()
        client = await self._owner_client(dashboard)
        self.assertEqual(
            (await client.post("/api/settings/rollback", json={})).status, 400
        )

    async def test_an_owner_with_no_linked_minecraft_account_is_told_why(self):
        dashboard = self._dashboard()
        dashboard.bot.data.list_accounts_for_user = AsyncMock(return_value=[])
        client = await self._owner_client(dashboard)
        response = await client.post(
            "/api/settings/publish",
            json={"edits": [{"key": "crate.default.key-cost", "value": "3"}]},
        )
        self.assertEqual(response.status, 409)
        self.assertIn("linked Minecraft account", await response.text())
