"""Local-first web dashboard, Discord OAuth, live values, logs, and standings."""

from __future__ import annotations

import base64
import hashlib
import hmac
import json
import logging
import secrets
import time
from pathlib import Path
from typing import Any, Optional
from urllib.parse import urlencode

import aiohttp
import discord
from aiohttp import web

from .audit import (
    OUTCOME_FAILED,
    OUTCOME_SUCCESS,
    RISK_CONFIGURATION,
    SOURCE_COMMAND,
    CommandAuditRecord,
    deliver,
)
from .perks import RANK_ROLES
from .presentation import head_url, skin_url


logger = logging.getLogger("MinecraftAccessBot.dashboard")
OWNER_ROLE_ID = next(role_id for role_id, group, *_rest in RANK_ROLES if group == "owner")
SESSION_SECONDS = 12 * 60 * 60
SITE_ROOT = Path(__file__).resolve().parents[1] / "devblog" / "dist"


class DashboardServer:
    def __init__(self, bot: Any) -> None:
        self.bot = bot
        self.config = bot.config
        self._runner: Optional[web.AppRunner] = None
        self._site: Optional[web.TCPSite] = None
        self._app = web.Application(
            client_max_size=128 * 1024,
            middlewares=[self._security_headers],
        )
        self._app.router.add_get("/auth/login", self.login)
        self._app.router.add_get("/auth/callback", self.callback)
        self._app.router.add_post("/auth/logout", self.logout)
        self._app.router.add_get("/api/me", self.me)
        self._app.router.add_get("/api/leaderboards", self.leaderboards)
        self._app.router.add_get("/api/settings", self.settings)
        self._app.router.add_patch("/api/settings/{key:.+}", self.change_setting)
        self._app.router.add_get("/api/logs", self.logs)
        self._app.router.add_get("/api/stats", self.stats_overview)
        self._app.router.add_get("/api/stats/series", self.stats_series)
        # The dashboard is part of the existing dev-blog, not a second site.
        # API/auth routes are registered first so this static fallback cannot
        # shadow them.
        self._app.router.add_get("/{path:.*}", self.site_file)

    @property
    def redirect_uri(self) -> str:
        return f"{self.config.dashboard_public_url}/auth/callback"

    @web.middleware
    async def _security_headers(self, request: web.Request, handler):
        response = await handler(request)
        response.headers["X-Content-Type-Options"] = "nosniff"
        response.headers["X-Frame-Options"] = "DENY"
        response.headers["Referrer-Policy"] = "same-origin"
        response.headers["Permissions-Policy"] = "camera=(), microphone=(), geolocation=()"
        response.headers["Content-Security-Policy"] = (
            "default-src 'self'; img-src 'self' https://mc-heads.net https://api.mcheads.org "
            "https://cdn.discordapp.com "
            "https://*.discordapp.net data:; style-src 'self' 'unsafe-inline' "
            "https://fonts.googleapis.com; font-src https://fonts.gstatic.com; "
            "script-src 'self' 'unsafe-inline'; connect-src 'self'; "
            "frame-ancestors 'none'; base-uri 'none'; form-action 'self'"
        )
        return response

    async def start(self) -> None:
        if not self.config.dashboard_enabled:
            return
        if not (SITE_ROOT / "index.html").is_file():
            # A missing site build must not take the Minecraft bot down with it. The
            # dashboard is an accessory; verification, chat relay and rank sync are not.
            logger.error(
                "Dashboard disabled: %s has no build. Run "
                "`python devblog/build.py --include-private` and restart.",
                SITE_ROOT,
            )
            return
        self._runner = web.AppRunner(self._app, access_log=None)
        await self._runner.setup()
        self._site = web.TCPSite(
            self._runner,
            host=self.config.dashboard_host,
            port=self.config.dashboard_port,
        )
        await self._site.start()
        logger.info(
            "Local dev-blog preview with Minecraft controls listening at %s "
            "(Discord OAuth redirect: %s)",
            self.config.dashboard_public_url,
            self.redirect_uri,
        )

    async def close(self) -> None:
        if self._runner is not None:
            await self._runner.cleanup()
            self._runner = None
            self._site = None

    async def site_file(self, request: web.Request) -> web.FileResponse:
        root = SITE_ROOT.resolve()
        relative = request.match_info.get("path", "").lstrip("/")
        candidate = (root / relative).resolve()
        try:
            candidate.relative_to(root)
        except ValueError:
            raise web.HTTPNotFound()
        if candidate.is_dir() or not candidate.suffix:
            candidate = candidate / "index.html"
        if candidate.is_file():
            return web.FileResponse(candidate)
        not_found = root / "404.html"
        if not_found.is_file():
            return web.FileResponse(not_found, status=404)
        raise web.HTTPNotFound()

    async def login(self, _request: web.Request) -> web.StreamResponse:
        if not self.config.dashboard_client_secret or self.bot.user is None:
            raise web.HTTPServiceUnavailable(
                text=(
                    "Discord OAuth is not configured. Set MINECRAFT_DASHBOARD_CLIENT_SECRET "
                    f"and register {self.redirect_uri} as the application's redirect URI."
                )
            )
        state = secrets.token_urlsafe(24)
        response = web.HTTPFound(
            "https://discord.com/oauth2/authorize?" + urlencode(
                {
                    "client_id": str(self.bot.user.id),
                    "redirect_uri": self.redirect_uri,
                    "response_type": "code",
                    "scope": "identify",
                    "state": state,
                    "prompt": "consent",
                }
            )
        )
        response.set_cookie(
            "mgx_oauth_state",
            self._sign({"state": state, "exp": int(time.time()) + 600}),
            httponly=True,
            secure=self.redirect_uri.startswith("https://"),
            samesite="Lax",
            max_age=600,
        )
        return response

    async def callback(self, request: web.Request) -> web.StreamResponse:
        state_payload = self._unsign(request.cookies.get("mgx_oauth_state", ""))
        if not state_payload or not hmac.compare_digest(
            str(state_payload.get("state", "")), str(request.query.get("state", ""))
        ):
            raise web.HTTPForbidden(text="The Discord authorization state is invalid or expired.")
        code = str(request.query.get("code", ""))
        if not code:
            raise web.HTTPBadRequest(text="Discord did not return an authorization code.")
        async with aiohttp.ClientSession(timeout=aiohttp.ClientTimeout(total=15)) as session:
            async with session.post(
                "https://discord.com/api/v10/oauth2/token",
                data={
                    "client_id": str(self.bot.user.id),
                    "client_secret": self.config.dashboard_client_secret,
                    "grant_type": "authorization_code",
                    "code": code,
                    "redirect_uri": self.redirect_uri,
                },
            ) as token_response:
                token = await token_response.json(content_type=None)
                if token_response.status != 200 or not token.get("access_token"):
                    raise web.HTTPBadGateway(text="Discord rejected the OAuth code.")
            async with session.get(
                "https://discord.com/api/v10/users/@me",
                headers={"Authorization": f"Bearer {token['access_token']}"},
            ) as user_response:
                user = await user_response.json(content_type=None)
                if user_response.status != 200 or not user.get("id"):
                    raise web.HTTPBadGateway(text="Discord could not identify this account.")
        user_id = int(user["id"])
        member = await self._owner_member(user_id)
        if member is None:
            raise web.HTTPForbidden(text="Only the Discord OWNER role may use this dashboard.")
        csrf = secrets.token_urlsafe(24)
        response = web.HTTPFound("/control/")
        response.del_cookie("mgx_oauth_state")
        response.set_cookie(
            "mgx_dashboard_session",
            self._sign({
                "user_id": user_id,
                "username": str(user.get("username") or member.name),
                "csrf": csrf,
                "exp": int(time.time()) + SESSION_SECONDS,
            }),
            httponly=True,
            secure=self.redirect_uri.startswith("https://"),
            samesite="Strict",
            max_age=SESSION_SECONDS,
        )
        return response

    async def logout(self, _request: web.Request) -> web.Response:
        response = web.json_response({"ok": True})
        response.del_cookie("mgx_dashboard_session")
        return response

    async def me(self, request: web.Request) -> web.Response:
        session, member = await self._require_owner(request, optional=True)
        if session is None or member is None:
            return web.json_response({
                "authenticated": False,
                "oauth_configured": bool(self.config.dashboard_client_secret),
            })
        return web.json_response({
            "authenticated": True,
            "user_id": member.id,
            "username": member.name,
            "display_name": member.display_name,
            "avatar_url": str(member.display_avatar.url),
            "csrf": session["csrf"],
        })

    async def leaderboards(self, _request: web.Request) -> web.Response:
        snapshot = json.loads(json.dumps(self.bot.bridge.latest_leaderboard or {}))
        individual = snapshot.get("individual") or {}
        uuids = {
            str(row.get("minecraft_uuid") or "")
            for rows in individual.values() if isinstance(rows, list)
            for row in rows if row.get("minecraft_uuid")
        }
        links = await self.bot.data.owners_for_uuids(uuids)
        guild = self.bot.get_guild(self.config.guild_id)
        for rows in individual.values():
            if not isinstance(rows, list):
                continue
            for row in rows:
                uuid = str(row.get("minecraft_uuid") or "")
                username = str(row.get("username") or "")
                row["head_url"] = head_url(uuid, username) if uuid or username else ""
                row["skin_url"] = skin_url(uuid, username) if uuid or username else ""
                discord_id = links.get(uuid)
                member = guild.get_member(int(discord_id)) if guild and discord_id else None
                row["discord_user_id"] = discord_id
                row["discord_username"] = member.name if member is not None else None
        return web.json_response(snapshot, headers={"Cache-Control": "no-store"})

    async def stats_overview(self, request: web.Request) -> web.Response:
        """
        Every headline figure in one call.

        Owner-gated in full. The public leaderboards endpoint already publishes standings;
        this adds activity, AFK and the sampled history, which are operational numbers.
        """
        await self._require_owner(request)
        try:
            days = max(1, min(365, int(request.query.get("days", "30"))))
        except (TypeError, ValueError):
            days = 30

        activity = await self.bot.data.player_activity_metrics(days=days)
        access = await self.bot.data.access_status_counts()

        afk: dict[str, Any] = {}
        reader = getattr(self.bot.data, "afk_metrics", None)
        if reader is not None:
            afk = await reader(days=days)
            uuids = {str(p["minecraft_uuid"]) for p in afk.get("players", [])}
            links = await self.bot.data.owners_for_uuids(uuids)
            for player in afk.get("players", []):
                uuid = str(player["minecraft_uuid"])
                player["head_url"] = head_url(uuid, player.get("username", ""))
                player["discord_user_id"] = links.get(uuid)

        metrics: list[str] = []
        names = getattr(self.bot.data, "stat_metrics", None)
        if names is not None:
            metrics = await names()

        return web.json_response(
            {
                "days": days,
                "activity": activity,
                "access": {str(k): int(v) for k, v in access.items()},
                "afk": afk,
                "metrics": metrics,
                "leaderboards": json.loads(json.dumps(self.bot.bridge.latest_leaderboard or {})),
                "sampled_every_seconds": 900,
            },
            headers={"Cache-Control": "no-store"},
        )

    async def stats_series(self, request: web.Request) -> web.Response:
        """One or more sampled metrics as time series, for the charts."""
        await self._require_owner(request)
        try:
            days = max(1, min(365, int(request.query.get("days", "30"))))
        except (TypeError, ValueError):
            days = 30
        wanted = [m for m in request.query.get("metric", "").split(",") if m.strip()]
        reader = getattr(self.bot.data, "stat_series", None)
        if reader is None:
            return web.json_response({"series": {}}, headers={"Cache-Control": "no-store"})
        # Capped so a wide selection cannot turn one request into hundreds of queries.
        series = {name: await reader(name, days=days) for name in wanted[:24]}
        return web.json_response(
            {"days": days, "series": series}, headers={"Cache-Control": "no-store"}
        )

    async def settings(self, request: web.Request) -> web.Response:
        await self._require_owner(request)
        payload = self.bot.bridge.latest_game_variables or {}
        return web.json_response(payload, headers={"Cache-Control": "no-store"})

    async def change_setting(self, request: web.Request) -> web.Response:
        session, member = await self._require_owner(request)
        self._require_csrf(request, session)
        try:
            body = await request.json()
        except (json.JSONDecodeError, TypeError):
            raise web.HTTPBadRequest(text="A JSON request body is required.")
        operation = "reset" if body.get("reset") is True else "set"
        key = request.match_info["key"]
        accounts = await self.bot.data.list_accounts_for_user(member.id)
        actor_uuid = next(
            (str(row.get("minecraft_uuid") or "") for row in accounts if row.get("minecraft_uuid")),
            "",
        )
        if not actor_uuid:
            raise web.HTTPConflict(text="The Discord OWNER account has no linked Minecraft account.")
        started = time.monotonic()
        success, message = await self.bot.bridge.change_game_variable(
            actor_uuid=actor_uuid,
            operation=operation,
            key=key,
            value=body.get("value", ""),
        )
        record = CommandAuditRecord(
            source=SOURCE_COMMAND,
            command="dashboard game variable",
            user_id=member.id,
            user_label=member.name,
            guild_id=self.config.guild_id,
            options=(("operation", operation), ("key", key), ("value", str(body.get("value", "")))),
            outcome=OUTCOME_SUCCESS if success else OUTCOME_FAILED,
            risk=RISK_CONFIGURATION,
            duration_ms=int((time.monotonic() - started) * 1000),
            detail=message,
        )
        await deliver(self.bot, record)
        if not success:
            raise web.HTTPConflict(text=message)
        return web.json_response({"ok": True, "message": message})

    async def logs(self, request: web.Request) -> web.Response:
        await self._require_owner(request)
        rows = await self.bot.data.list_command_log(limit=50)
        safe_rows = [
            {
                key: row.get(key)
                for key in (
                    "id", "source", "command", "actor_discord_id", "actor_label",
                    "outcome", "risk", "duration_ms", "detail", "options", "created_at",
                )
            }
            for row in rows
        ]
        return web.json_response({"logs": safe_rows}, headers={"Cache-Control": "no-store"})

    async def _owner_member(self, user_id: int) -> Optional[discord.Member]:
        guild = self.bot.get_guild(self.config.guild_id)
        if guild is None:
            return None
        member = guild.get_member(int(user_id))
        if member is None:
            try:
                member = await guild.fetch_member(int(user_id))
            except (discord.NotFound, discord.Forbidden, discord.HTTPException):
                return None
        return member if any(role.id == OWNER_ROLE_ID for role in member.roles) else None

    async def _require_owner(
        self, request: web.Request, *, optional: bool = False
    ) -> tuple[Optional[dict[str, Any]], Optional[discord.Member]]:
        session = self._unsign(request.cookies.get("mgx_dashboard_session", ""))
        member = await self._owner_member(int(session.get("user_id", 0))) if session else None
        if session is None or member is None:
            if optional:
                return None, None
            raise web.HTTPUnauthorized(text="Sign in with the Discord OWNER role first.")
        return session, member

    @staticmethod
    def _require_csrf(request: web.Request, session: dict[str, Any]) -> None:
        supplied = request.headers.get("X-MGX-CSRF", "")
        if not supplied or not hmac.compare_digest(supplied, str(session.get("csrf", ""))):
            raise web.HTTPForbidden(text="The dashboard security token is missing or expired.")

    def _sign(self, payload: dict[str, Any]) -> str:
        raw = json.dumps(payload, separators=(",", ":"), sort_keys=True).encode()
        encoded = base64.urlsafe_b64encode(raw).decode().rstrip("=")
        signature = hmac.new(self.config.bridge_secret, encoded.encode(), hashlib.sha256).digest()
        return encoded + "." + base64.urlsafe_b64encode(signature).decode().rstrip("=")

    def _unsign(self, token: str) -> Optional[dict[str, Any]]:
        try:
            encoded, supplied = token.split(".", 1)
            expected = hmac.new(self.config.bridge_secret, encoded.encode(), hashlib.sha256).digest()
            signature = base64.urlsafe_b64decode(supplied + "=" * (-len(supplied) % 4))
            if not hmac.compare_digest(expected, signature):
                return None
            raw = base64.urlsafe_b64decode(encoded + "=" * (-len(encoded) % 4))
            payload = json.loads(raw)
            if int(payload.get("exp", 0)) <= int(time.time()):
                return None
            return payload
        except (ValueError, TypeError, json.JSONDecodeError):
            return None


__all__ = ["DashboardServer", "OWNER_ROLE_ID"]
