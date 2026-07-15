"""Native AutoMod follow-up engine, policy views, report flows, and /automod command."""

import asyncio
import base64
import hashlib
import io
import logging
import re
import struct
import threading
import time
import unicodedata
import warnings
from collections import OrderedDict
from dataclasses import dataclass
from datetime import datetime, timedelta, timezone
from pathlib import Path
from typing import Iterable, Optional, List, Union, Tuple

import discord
from discord import app_commands
from discord.ext import commands

from core.constants import (
    EMBED_PALETTE,
    SCOPE_MODERATION,
)
from core.services import (
    DEFAULT_NATIVE_AUTOMOD_SETTINGS,
    get_feature_flag,
    get_native_automod_settings,
)
from core.context import bot, tree
from core.utils import iso_to_dt, now_iso
from .shared import (
    logger,
    truncate_text,
    format_duration,
    format_log_quote,
    format_reason_value,
    make_action_log_embed,
    normalize_log_embed,
    make_embed,
    brand_embed,
    make_confirmation_embed,
    join_lines,
    upsert_embed_field,
    get_user_display_name,
    format_user_ref,
    get_primary_guild,
    has_permission_capability,
    respond_with_error,
    is_staff,
    is_staff_member,
    resolve_member,
    get_valid_duration,
    build_automod_dashboard_embed,
    get_punishment_log_channel_id,
    send_automod_log,
)

from .roles import build_appeal_view

AUTOMOD_PUNISHMENT_OPTIONS = [
    ("warn", "Warn Only"),
    ("timeout", "Timeout"),
    ("kick", "Kick"),
    ("ban", "Ban"),
]
AUTOMOD_THRESHOLD_PRESETS = [1, 2, 3, 4, 5, 6, 8, 10, 12]
AUTOMOD_WINDOW_PRESETS = [15, 60, 120, 360, 720, 1440, 2880, 4320, 10080]
AUTOMOD_TIMEOUT_PRESETS = [10, 30, 60, 120, 180, 720, 1440, 2880, 10080, 40320]
AUTOMOD_REPORT_RESPONSE_PRESETS = {
    "fixed": {
        "label": "We fixed the AutoMod",
        "description": "Tell the user the AutoMod setup was corrected.",
        "message": "We reviewed your report and fixed the AutoMod setup for that warning. Thanks for reporting it.",
        "status": "Resolved - AutoMod Updated",
        "kind": "success",
    },
    "justified": {
        "label": "Warn was justified",
        "description": "Tell the user the AutoMod warning will stand.",
        "message": "We reviewed your report and the AutoMod warning was justified, so it will remain as-is.",
        "status": "Reviewed - Warning Stands",
        "kind": "warning",
    },
    "removed": {
        "label": "Warn was removed",
        "description": "Tell the user the warning was treated as a false positive.",
        "message": "We reviewed your report and treated this as a false positive. The warning has been cleared on our side.",
        "status": "Resolved - False Positive",
        "kind": "success",
    },
    "custom": {
        "label": "Custom response",
        "description": "Write and send a custom staff response.",
        "message": "",
        "status": "Staff Replied",
        "kind": "info",
    },
}


def format_minutes_interval(minutes: int) -> str:
    minutes = max(1, int(minutes or 1))
    if minutes < 60:
        return f"{minutes} minute{'s' if minutes != 1 else ''}"
    if minutes < 1440:
        hours = minutes // 60
        return f"{hours} hour{'s' if hours != 1 else ''}"
    days = minutes // 1440
    return f"{days} day{'s' if days != 1 else ''}"


def format_seconds_interval(seconds: int) -> str:
    seconds = max(1, int(seconds or 1))
    if seconds < 60:
        return f"{seconds} second{'s' if seconds != 1 else ''}"
    minutes = seconds // 60
    return format_minutes_interval(minutes)


def format_compact_minutes_input(minutes: int) -> str:
    minutes = max(1, int(minutes or 1))
    if minutes % 10080 == 0:
        return f"{minutes // 10080}w"
    if minutes % 1440 == 0:
        return f"{minutes // 1440}d"
    if minutes % 60 == 0:
        return f"{minutes // 60}h"
    return f"{minutes}m"


def parse_positive_integer_input(raw_value: str, *, field_name: str, minimum: int = 1, maximum: int = 999) -> int:
    text = str(raw_value or "").strip()
    if not text:
        raise ValueError(f"{field_name} is required.")
    if not text.isdigit():
        raise ValueError(f"{field_name} must be a whole number.")
    value = int(text)
    if value < minimum or value > maximum:
        raise ValueError(f"{field_name} must be between {minimum} and {maximum}.")
    return value


def parse_minutes_input(raw_value: str, *, field_name: str, minimum: int = 1, maximum: int = 40320) -> int:
    text = str(raw_value or "").strip().lower()
    if not text:
        raise ValueError(f"{field_name} is required.")

    match = re.fullmatch(r"(\d+)\s*([a-z]+)?", text)
    if not match:
        raise ValueError(f"{field_name} must look like 30m, 12h, 2d, or 1w.")

    amount = int(match.group(1))
    unit = (match.group(2) or "m").lower()

    if unit in {"m", "min", "mins", "minute", "minutes"}:
        minutes = amount
    elif unit in {"h", "hr", "hrs", "hour", "hours"}:
        minutes = amount * 60
    elif unit in {"d", "day", "days"}:
        minutes = amount * 1440
    elif unit in {"w", "wk", "wks", "week", "weeks"}:
        minutes = amount * 10080
    else:
        raise ValueError(f"{field_name} must use m, h, d, or w.")

    if minutes < minimum or minutes > maximum:
        raise ValueError(f"{field_name} must be between {format_minutes_interval(minimum)} and {format_minutes_interval(maximum)}.")
    return minutes


def parse_automod_punishment_input(raw_value: str, *, field_name: str = "Action") -> str:
    text = str(raw_value or "").strip().lower()
    mapping = {
        "warn": "warn",
        "warning": "warn",
        "timeout": "timeout",
        "mute": "timeout",
        "kick": "kick",
        "ban": "ban",
    }
    punishment_type = mapping.get(text)
    if punishment_type is None:
        raise ValueError(f"{field_name} must be one of: warn, timeout, kick, or ban.")
    return punishment_type


def build_numeric_select_options(current: int, presets: List[int], formatter) -> List[discord.SelectOption]:
    values = []
    for value in presets:
        if value not in values:
            values.append(value)
    if current not in values:
        values.append(current)
    return [
        discord.SelectOption(label=truncate_text(formatter(value), 100), value=str(value), default=value == current)
        for value in values[:25]
    ]


def store_native_automod_settings(settings: dict) -> dict:
    normalized = get_native_automod_settings({"native_automod": settings})
    bot.data_manager.config["native_automod"] = normalized
    return normalized


def format_automod_punishment_label(policy: dict) -> str:
    punishment_type = str(policy.get("punishment_type", "warn") or "warn").lower()
    if punishment_type == "timeout":
        return f"Timeout ({format_duration(int(policy.get('duration_minutes', 60) or 60))})"
    if punishment_type == "ban":
        return "Ban"
    if punishment_type == "kick":
        return "Kick"
    return "Warn Only"


def get_automod_report_preset(key: str) -> dict:
    return AUTOMOD_REPORT_RESPONSE_PRESETS.get(key, AUTOMOD_REPORT_RESPONSE_PRESETS["custom"])


def build_default_native_automod_policy() -> dict:
    return {
        "enabled": False,
        "reason_template": str(DEFAULT_NATIVE_AUTOMOD_SETTINGS["default_escalation"]["reason_template"]),
        "steps": [],
    }


def get_native_automod_policy_steps(policy: Optional[dict]) -> List[dict]:
    if not isinstance(policy, dict):
        return []
    steps = []
    for payload in policy.get("steps", []):
        if not isinstance(payload, dict):
            continue
        punishment_type = str(payload.get("punishment_type", "warn") or "warn").lower()
        threshold = max(1, int(payload.get("threshold", 1) or 1))
        window_minutes = max(1, int(payload.get("window_minutes", 1440) or 1440))
        duration_minutes = int(payload.get("duration_minutes", 0) or 0)
        if punishment_type == "timeout":
            duration_minutes = max(1, min(40320, duration_minutes or 60))
        elif punishment_type == "ban":
            duration_minutes = -1
        else:
            duration_minutes = 0
        steps.append({
            "threshold": threshold,
            "window_minutes": window_minutes,
            "duration_minutes": duration_minutes,
            "punishment_type": punishment_type,
        })
    steps.sort(key=lambda step: (int(step.get("threshold", 1)), int(step.get("window_minutes", 1)), str(step.get("punishment_type", "warn"))))
    return steps[:5]


def build_default_native_automod_step(existing_steps: Optional[List[dict]] = None) -> dict:
    steps = get_native_automod_policy_steps({"steps": existing_steps or []})
    if steps:
        last_step = steps[-1]
        threshold = min(25, max(1, int(last_step.get("threshold", 3) or 3) + 1))
        window_minutes = int(last_step.get("window_minutes", 1440) or 1440)
    else:
        threshold = 3
        window_minutes = 1440
    return {
        "threshold": threshold,
        "window_minutes": window_minutes,
        "duration_minutes": 60,
        "punishment_type": "timeout",
    }


def format_native_automod_step_summary(step: dict) -> str:
    threshold = int(step.get("threshold", 1) or 1)
    return f"{threshold} warning{'s' if threshold != 1 else ''} in {format_minutes_interval(int(step.get('window_minutes', 1440) or 1440))} -> {format_automod_punishment_label(step)}"


def get_native_rule_override(settings: dict, rule: discord.AutoModRule) -> Tuple[str, dict, bool]:
    overrides = settings.get("rule_overrides", {})
    for candidate in (str(rule.id), rule.name):
        if candidate in overrides:
            return candidate, overrides[candidate], True
    return str(rule.id), build_default_native_automod_policy(), False


def render_id_mentions(ids: List[int], *, prefix: str, limit: int = 6) -> str:
    cleaned = [int(value) for value in ids if isinstance(value, int) or str(value).isdigit()]
    if not cleaned:
        return "None"
    rendered = [f"<{prefix}{value}>" for value in cleaned[:limit]]
    if len(cleaned) > limit:
        rendered.append(f"+{len(cleaned) - limit} more")
    return ", ".join(rendered)


def build_automod_bridge_embed(guild: discord.Guild) -> discord.Embed:
    settings = get_native_automod_settings(bot.data_manager.config)
    embed = make_embed(
        "AutoMod Bot Response",
        "> Control what the bot does after Discord AutoMod triggers.",
        kind="warning",
        scope=SCOPE_MODERATION,
        guild=guild,
    )
    embed.add_field(name="Bot Response", value="On" if settings.get("enabled", True) else "Off", inline=True)
    embed.add_field(name="User DMs", value="On" if settings.get("warning_dm_enabled", True) else "Off", inline=True)
    embed.add_field(name="False-Positive Report", value="On" if settings.get("report_button_enabled", True) else "Off", inline=True)
    return embed


def build_automod_policy_embed(
    guild: discord.Guild,
    policy: dict,
    *,
    title: str,
    description: str,
    rule: Optional[discord.AutoModRule] = None,
    using_override: bool = False,
    selected_step_index: Optional[int] = None,
) -> discord.Embed:
    steps = get_native_automod_policy_steps(policy)
    embed = make_embed(title, description, kind="warning", scope=SCOPE_MODERATION, guild=guild)
    if rule is not None:
        embed.add_field(name="Rule", value=rule.name, inline=True)
    enabled_label = "On" if policy.get("enabled") and steps else "Off"
    embed.add_field(name="Auto Punish", value=f"{enabled_label} • {len(steps)} step{'s' if len(steps) != 1 else ''}", inline=True)
    if steps:
        step_lines = [f"{index + 1}. {format_native_automod_step_summary(step)}" for index, step in enumerate(steps[:5])]
        embed.add_field(name="Escalation Ladder", value=join_lines(step_lines, fallback="No punishment steps set yet."), inline=False)
    else:
        embed.add_field(name="Escalation Ladder", value="No punishment steps set yet.", inline=False)
    if steps and selected_step_index is not None and 0 <= selected_step_index < len(steps):
        selected_step = steps[selected_step_index]
        selected_lines = [
            f"Step: {selected_step_index + 1}",
            f"Warnings: {selected_step.get('threshold', 1)}",
            f"Window: {format_minutes_interval(int(selected_step.get('window_minutes', 1440) or 1440))}",
            f"Action: {format_automod_punishment_label(selected_step)}",
        ]
        if str(selected_step.get("punishment_type", "warn")).lower() == "timeout":
            selected_lines.append(f"Timeout: {format_minutes_interval(int(selected_step.get('duration_minutes', 60) or 60))}")
        embed.add_field(name="Selected Step", value=join_lines(selected_lines), inline=False)
    embed.add_field(name="Reason Template", value=format_log_quote(policy.get("reason_template", "Repeated native AutoMod violations"), limit=500), inline=False)
    return embed


def build_automod_immunity_embed(guild: discord.Guild) -> discord.Embed:
    settings = get_native_automod_settings(bot.data_manager.config)
    embed = make_embed(
        "AutoMod Immunity",
        "> Choose who should be ignored by the native AutoMod bridge follow-up.",
        kind="info",
        scope=SCOPE_MODERATION,
        guild=guild,
    )
    embed.add_field(name="Users", value=render_id_mentions(settings.get("immunity_users", []), prefix="@"), inline=False)
    embed.add_field(name="Roles", value=render_id_mentions(settings.get("immunity_roles", []), prefix="@&"), inline=False)
    embed.add_field(name="Channels", value=render_id_mentions(settings.get("immunity_channels", []), prefix="#"), inline=False)
    return embed


def build_automod_routing_embed(guild: discord.Guild) -> discord.Embed:
    embed = make_embed(
        "AutoMod Log Channels",
        "> Use the selectors below to set or clear where the bot sends AutoMod logs and user reports.",
        kind="info",
        scope=SCOPE_MODERATION,
        guild=guild,
    )
    embed.add_field(
        name="Log Channel",
        value=f"<#{bot.data_manager.config.get('automod_log_channel_id', 0)}>" if bot.data_manager.config.get("automod_log_channel_id") else "Uses punishment logs or the native alert channel fallback",
        inline=False,
    )
    embed.add_field(
        name="Report Channel",
        value=f"<#{bot.data_manager.config.get('automod_report_channel_id', 0)}>" if bot.data_manager.config.get("automod_report_channel_id") else "Uses the appeal log channel or punishment logs",
        inline=False,
    )
    return embed


def build_automod_rule_browser_embed(guild: discord.Guild, rules: List[discord.AutoModRule]) -> discord.Embed:
    settings = get_native_automod_settings(bot.data_manager.config)
    configured_rules = sum(1 for payload in settings.get("rule_overrides", {}).values() if get_native_automod_policy_steps(payload))
    embed = make_embed(
        "Native AutoMod Rules",
        "> Pick one Discord AutoMod rule below to set up that rule's automatic punishment steps.",
        kind="warning",
        scope=SCOPE_MODERATION,
        guild=guild,
    )
    if not rules:
        embed.add_field(name="Rules", value="No native Discord AutoMod rules were found in this server.", inline=False)
        return embed
    embed.add_field(name="Native Rules", value=str(len(rules)), inline=True)
    embed.add_field(name="Rules Configured", value=str(configured_rules), inline=True)
    return embed


def describe_automod_rule_trigger(rule: discord.AutoModRule) -> str:
    trigger = rule.trigger
    if trigger.type == discord.AutoModRuleTriggerType.keyword:
        keywords = ", ".join(f"`{truncate_text(value, 20)}`" for value in trigger.keyword_filter[:4]) or "No keywords"
        regexes = ", ".join(f"`{truncate_text(value, 20)}`" for value in trigger.regex_patterns[:2])
        details = [f"Keywords: {keywords}"]
        if regexes:
            details.append(f"Regex: {regexes}")
        return join_lines(details)
    if trigger.type == discord.AutoModRuleTriggerType.keyword_preset:
        presets = []
        if trigger.presets.profanity:
            presets.append("Profanity")
        if trigger.presets.sexual_content:
            presets.append("Sexual Content")
        if trigger.presets.slurs:
            presets.append("Slurs")
        return ", ".join(presets) or "Preset Rule"
    if trigger.type == discord.AutoModRuleTriggerType.mention_spam:
        raid = "On" if trigger.mention_raid_protection else "Off"
        return f"Mention Limit: {trigger.mention_limit or 0} • Raid Protection: {raid}"
    if trigger.type == discord.AutoModRuleTriggerType.spam:
        return "Spam detection"
    return trigger.type.name.replace('_', ' ').title()


def describe_automod_rule_actions(rule: discord.AutoModRule) -> str:
    parts = []
    for action in rule.actions:
        if action.type == discord.AutoModRuleActionType.block_message:
            parts.append(f"Block message{' + custom notice' if action.custom_message else ''}")
        elif action.type == discord.AutoModRuleActionType.send_alert_message:
            parts.append(f"Send alert to <#{action.channel_id}>")
        elif action.type == discord.AutoModRuleActionType.timeout:
            minutes = int(action.duration.total_seconds() // 60) if action.duration else 0
            parts.append(f"Timeout for {format_duration(minutes)}")
        elif action.type == discord.AutoModRuleActionType.block_member_interactions:
            parts.append("Block member interactions")
    return ", ".join(parts) or "No actions"


def serialize_automod_rule(rule: discord.AutoModRule) -> dict:
    trigger = rule.trigger
    presets = []
    if trigger.presets.profanity:
        presets.append("profanity")
    if trigger.presets.sexual_content:
        presets.append("sexual_content")
    if trigger.presets.slurs:
        presets.append("slurs")

    payload = {
        "name": rule.name,
        "enabled": rule.enabled,
        "trigger_type": rule.trigger.type.name,
        "keyword_filter": trigger.keyword_filter,
        "regex_patterns": trigger.regex_patterns,
        "allow_list": trigger.allow_list,
        "mention_limit": trigger.mention_limit,
        "mention_raid_protection": trigger.mention_raid_protection,
        "presets": presets,
        "actions": [],
        "exempt_roles": list(rule.exempt_role_ids),
        "exempt_channels": list(rule.exempt_channel_ids),
    }
    for action in rule.actions:
        action_payload = {"type": action.type.name}
        if action.custom_message:
            action_payload["custom_message"] = action.custom_message
        if action.channel_id:
            action_payload["channel_id"] = action.channel_id
        if action.duration:
            action_payload["duration_minutes"] = int(action.duration.total_seconds() // 60)
        payload["actions"].append(action_payload)
    return payload


def build_automod_trigger_from_payload(payload: dict, existing_type: Optional[discord.AutoModRuleTriggerType] = None) -> discord.AutoModTrigger:
    trigger_name = str(payload.get("trigger_type") or (existing_type.name if existing_type else "keyword")).lower()
    trigger_type = discord.AutoModRuleTriggerType[trigger_name]
    if trigger_type == discord.AutoModRuleTriggerType.keyword:
        return discord.AutoModTrigger(
            type=trigger_type,
            keyword_filter=[str(v) for v in payload.get("keyword_filter", []) if str(v).strip()],
            regex_patterns=[str(v) for v in payload.get("regex_patterns", []) if str(v).strip()],
            allow_list=[str(v) for v in payload.get("allow_list", []) if str(v).strip()],
        )
    if trigger_type == discord.AutoModRuleTriggerType.keyword_preset:
        presets = discord.AutoModPresets.none()
        for name in payload.get("presets", []):
            if name == "profanity":
                presets.profanity = True
            elif name == "sexual_content":
                presets.sexual_content = True
            elif name == "slurs":
                presets.slurs = True
        return discord.AutoModTrigger(type=trigger_type, presets=presets, allow_list=[str(v) for v in payload.get("allow_list", []) if str(v).strip()])
    if trigger_type == discord.AutoModRuleTriggerType.mention_spam:
        return discord.AutoModTrigger(
            type=trigger_type,
            mention_limit=max(1, min(50, int(payload.get("mention_limit", 5) or 5))),
            mention_raid_protection=bool(payload.get("mention_raid_protection", False)),
        )
    return discord.AutoModTrigger(type=trigger_type)


def build_automod_actions_from_payload(payload: dict, guild: discord.Guild) -> List[discord.AutoModRuleAction]:
    actions: List[discord.AutoModRuleAction] = []
    for action_payload in payload.get("actions", []):
        if not isinstance(action_payload, dict):
            continue
        action_type = str(action_payload.get("type", "block_message")).lower()
        if action_type == "send_alert_message":
            channel_id = action_payload.get("channel_id") or bot.data_manager.config.get("automod_log_channel_id") or get_punishment_log_channel_id()
            if channel_id:
                actions.append(discord.AutoModRuleAction(channel_id=int(channel_id)))
        elif action_type == "timeout":
            duration_minutes = max(1, min(40320, int(action_payload.get("duration_minutes", 60) or 60)))
            actions.append(discord.AutoModRuleAction(duration=timedelta(minutes=duration_minutes)))
        elif action_type == "block_member_interactions":
            actions.append(discord.AutoModRuleAction(type=discord.AutoModRuleActionType.block_member_interactions))
        else:
            actions.append(discord.AutoModRuleAction(custom_message=str(action_payload.get("custom_message") or "This message was blocked by server AutoMod.")))
    if not actions:
        actions.append(discord.AutoModRuleAction(custom_message="This message was blocked by server AutoMod."))
        alert_channel_id = bot.data_manager.config.get("automod_log_channel_id") or get_punishment_log_channel_id()
        if alert_channel_id:
            actions.append(discord.AutoModRuleAction(channel_id=int(alert_channel_id)))
    return actions


async def fetch_native_automod_rules(guild: discord.Guild) -> List[discord.AutoModRule]:
    return await guild.fetch_automod_rules()


def build_native_automod_rules_embed(guild: discord.Guild, rules: List[discord.AutoModRule]) -> discord.Embed:
    embed = make_embed(
        "Native AutoMod Rules",
        "> Discord's built-in AutoMod rules currently configured for this server.",
        kind="warning",
        scope=SCOPE_MODERATION,
        guild=guild,
    )
    if not rules:
        embed.add_field(name="Rules", value="No native AutoMod rules are configured yet.", inline=False)
        return embed
    embed.add_field(name="Total Rules", value=str(len(rules)), inline=True)
    embed.add_field(name="Enabled", value=str(sum(1 for rule in rules if rule.enabled)), inline=True)
    for rule in rules[:10]:
        embed.add_field(
            name=f"{'On' if rule.enabled else 'Off'} • {rule.name}",
            value=join_lines([
                f"Trigger: {describe_automod_rule_trigger(rule)}",
                f"Actions: {describe_automod_rule_actions(rule)}",
                f"Exempt Roles: {len(rule.exempt_role_ids)} • Exempt Channels: {len(rule.exempt_channel_ids)}",
            ]),
            inline=False,
        )
    return embed


def build_native_automod_rule_detail_embed(guild: discord.Guild, rule: discord.AutoModRule) -> discord.Embed:
    embed = make_embed(
        f"AutoMod Rule: {rule.name}",
        "> Detailed view of one Discord native AutoMod rule.",
        kind="warning",
        scope=SCOPE_MODERATION,
        guild=guild,
    )
    embed.add_field(name="Target", value=rule.name, inline=True)
    embed.add_field(name="Reason", value=format_reason_value(rule.trigger.type.name.replace('_', ' ').title(), limit=300), inline=False)
    embed.add_field(name="Trigger", value=describe_automod_rule_trigger(rule), inline=False)
    embed.add_field(name="Actions", value=describe_automod_rule_actions(rule), inline=False)
    embed.add_field(name="Enabled", value="Yes" if rule.enabled else "No", inline=True)
    embed.add_field(name="Rule ID", value=str(rule.id), inline=True)
    embed.add_field(name="Exempt Roles", value=", ".join(f"<@&{rid}>" for rid in rule.exempt_role_ids) or "None", inline=False)
    embed.add_field(name="Exempt Channels", value=", ".join(f"<#{cid}>" for cid in rule.exempt_channel_ids) or "None", inline=False)
    return embed


def get_native_automod_stats_bucket(user_id: int) -> dict:
    store = bot.data_manager.mod_stats.setdefault("native_automod", {})
    if not isinstance(store, dict):
        store = {}
        bot.data_manager.mod_stats["native_automod"] = store
    bucket = store.setdefault(str(user_id), {"events": [], "applied_steps": []})
    if not isinstance(bucket, dict):
        bucket = {"events": [], "applied_steps": []}
        store[str(user_id)] = bucket
    events = bucket.setdefault("events", [])
    if not isinstance(events, list):
        bucket["events"] = []
    applied_steps = bucket.setdefault("applied_steps", [])
    if not isinstance(applied_steps, list):
        bucket["applied_steps"] = []
    return bucket


def prune_native_automod_bucket(bucket: dict, *, now_value: Optional[datetime] = None) -> None:
    now_value = now_value or discord.utils.utcnow()

    fresh_events = []
    for event in bucket.get("events", []):
        dt = iso_to_dt(event.get("timestamp")) if isinstance(event, dict) else None
        if dt and now_value - dt <= timedelta(days=30):
            fresh_events.append(event)
    bucket["events"] = fresh_events[-100:]

    fresh_steps = []
    for record in bucket.get("applied_steps", []):
        dt = iso_to_dt(record.get("timestamp")) if isinstance(record, dict) else None
        if dt and now_value - dt <= timedelta(days=30):
            fresh_steps.append(record)
    bucket["applied_steps"] = fresh_steps[-100:]


def record_native_automod_event(*, user_id: int, rule_id: int, rule_name: str, content: str, matched_keyword: Optional[str]) -> None:
    bucket = get_native_automod_stats_bucket(user_id)
    now_value = discord.utils.utcnow()
    prune_native_automod_bucket(bucket, now_value=now_value)
    events = list(bucket.get("events", []))
    events.append({
        "timestamp": now_iso(),
        "rule_id": int(rule_id),
        "rule_name": rule_name,
        "content": truncate_text(content, 500),
        "matched_keyword": matched_keyword,
    })
    bucket["events"] = events[-100:]


def count_recent_native_automod_hits(*, user_id: int, rule_id: int, rule_name: str, window_minutes: int) -> int:
    bucket = get_native_automod_stats_bucket(user_id)
    prune_native_automod_bucket(bucket)
    cutoff = discord.utils.utcnow() - timedelta(minutes=max(1, window_minutes))
    count = 0
    for event in bucket.get("events", []):
        if not isinstance(event, dict):
            continue
        dt = iso_to_dt(event.get("timestamp"))
        if not dt or dt < cutoff:
            continue
        event_rule_id = event.get("rule_id")
        event_rule_name = str(event.get("rule_name", ""))
        if str(event_rule_id) == str(rule_id) or event_rule_name == rule_name:
            count += 1
    return count


def has_recent_native_automod_step_application(
    *,
    user_id: int,
    rule_id: int,
    rule_name: str,
    threshold: int,
    window_minutes: int,
) -> bool:
    bucket = get_native_automod_stats_bucket(user_id)
    prune_native_automod_bucket(bucket)
    cutoff = discord.utils.utcnow() - timedelta(minutes=max(1, window_minutes))
    for record in bucket.get("applied_steps", []):
        if not isinstance(record, dict):
            continue
        dt = iso_to_dt(record.get("timestamp"))
        if not dt or dt < cutoff:
            continue
        record_rule_id = record.get("rule_id")
        record_rule_name = str(record.get("rule_name", ""))
        if str(record_rule_id) != str(rule_id) and record_rule_name != rule_name:
            continue
        if int(record.get("threshold", 0) or 0) != int(threshold):
            continue
        if int(record.get("window_minutes", 0) or 0) != int(window_minutes):
            continue
        return True
    return False


def record_native_automod_step_application(
    *,
    user_id: int,
    rule_id: int,
    rule_name: str,
    step: dict,
) -> None:
    bucket = get_native_automod_stats_bucket(user_id)
    now_value = discord.utils.utcnow()
    prune_native_automod_bucket(bucket, now_value=now_value)
    applied_steps = list(bucket.get("applied_steps", []))
    applied_steps.append({
        "timestamp": now_iso(),
        "rule_id": int(rule_id),
        "rule_name": str(rule_name),
        "threshold": int(step.get("threshold", 1) or 1),
        "window_minutes": int(step.get("window_minutes", 1440) or 1440),
        "punishment_type": str(step.get("punishment_type", "warn") or "warn"),
        "duration_minutes": int(step.get("duration_minutes", 0) or 0),
    })
    bucket["applied_steps"] = applied_steps[-100:]


def get_triggered_native_automod_step(*, user_id: int, rule_id: int, rule_name: str, policy: dict) -> Tuple[Optional[dict], int]:
    if not bool(policy.get("enabled", False)):
        return None, 0

    for step in get_native_automod_policy_steps(policy):
        threshold = int(step.get("threshold", 1) or 1)
        window_minutes = int(step.get("window_minutes", 1440) or 1440)
        hit_count = count_recent_native_automod_hits(
            user_id=user_id,
            rule_id=rule_id,
            rule_name=rule_name,
            window_minutes=window_minutes,
        )
        if hit_count < threshold:
            continue
        if has_recent_native_automod_step_application(
            user_id=user_id,
            rule_id=rule_id,
            rule_name=rule_name,
            threshold=threshold,
            window_minutes=window_minutes,
        ):
            continue
        return step, hit_count
    return None, 0


def build_native_automod_dedupe_key(execution: discord.AutoModAction) -> Tuple[int, int, int, str, str]:
    return (
        int(execution.guild_id or 0),
        int(execution.user_id or 0),
        int(execution.rule_id or 0),
        str(execution.channel_id or 0),
        truncate_text(execution.matched_keyword or execution.matched_content or execution.content or "", 120),
    )


def claim_native_automod_execution(execution: discord.AutoModAction, *, ttl_seconds: int = 15) -> bool:
    now_ts = time.time()
    cache = bot.native_automod_event_cache
    for cache_key, seen_at in list(cache.items()):
        if now_ts - seen_at > ttl_seconds:
            cache.pop(cache_key, None)

    dedupe_key = build_native_automod_dedupe_key(execution)
    previous = cache.get(dedupe_key)
    if previous and now_ts - previous <= ttl_seconds:
        return False

    cache[dedupe_key] = now_ts
    return True


def get_native_automod_action_label(execution: discord.AutoModAction) -> str:
    return execution.action.type.name.replace("_", " ").title()


def native_automod_rule_has_enforcement(rule: Optional[discord.AutoModRule], execution: discord.AutoModAction) -> bool:
    enforcement_types = {
        discord.AutoModRuleActionType.block_message,
        discord.AutoModRuleActionType.timeout,
        discord.AutoModRuleActionType.block_member_interactions,
    }
    if execution.action.type in enforcement_types:
        return True
    if rule is None:
        return False
    return any(getattr(action, "type", None) in enforcement_types for action in getattr(rule, "actions", []))


def is_native_automod_exempt(member: discord.Member, channel_id: Optional[int], settings: dict) -> bool:
    if str(member.id) in bot.data_manager.config.get("immunity_list", []):
        return True

    immunity_users = {int(value) for value in settings.get("immunity_users", []) if isinstance(value, int) or str(value).isdigit()}
    immunity_roles = {int(value) for value in settings.get("immunity_roles", []) if isinstance(value, int) or str(value).isdigit()}
    immunity_channels = {int(value) for value in settings.get("immunity_channels", []) if isinstance(value, int) or str(value).isdigit()}

    if member.id in immunity_users:
        return True
    if channel_id and channel_id in immunity_channels:
        return True
    return any(role.id in immunity_roles for role in member.roles)


# ---------------- Image filter (banned-image detection) ----------------
# Multiple compact signatures distinguish visually different images that share
# a grayscale dHash. Expensive decoding runs off the gateway event loop and is
# bounded so a compressed image cannot consume unbounded memory.

IMAGE_FILTER_MAX_ENTRIES = 100
IMAGE_FILTER_MAX_FALSE_POSITIVES = 250
IMAGE_FILTER_MAX_BYTES = 8 * 1024 * 1024
IMAGE_FILTER_MAX_TOTAL_BYTES = 16 * 1024 * 1024
IMAGE_FILTER_MAX_ATTACHMENTS = 10
IMAGE_FILTER_MAX_PIXELS = 12_000_000
IMAGE_FILTER_ALLOWED_FORMATS = frozenset({"PNG", "JPEG", "WEBP", "GIF"})
IMAGE_FILTER_FILENAME_SUFFIXES = (".png", ".jpg", ".jpeg", ".webp", ".gif", ".apng", ".bmp", ".tif", ".tiff", ".ico", ".svg")
IMAGE_HASH_DISTANCE_THRESHOLD = 10
IMAGE_COLOR_DISTANCE_THRESHOLD = 72
IMAGE_ASPECT_DELTA_THRESHOLD = 100
IMAGE_STRONG_HASH_DISTANCE_THRESHOLD = 3
IMAGE_STRONG_COLOR_DISTANCE_THRESHOLD = 24
IMAGE_STRONG_ASPECT_DELTA_THRESHOLD = 30
IMAGE_STRONG_MIN_DETAIL = 10
IMAGE_SCAM_CONFIDENCE_THRESHOLD = 85
IMAGE_OCR_MAX_DIMENSION = 1600
IMAGE_OCR_MIN_LINE_CONFIDENCE = 0.45
IMAGE_CONTENT_CACHE_LIMIT = 256
IMAGE_MESSAGE_CLEANUP_CONCURRENCY = 4
IMAGE_MESSAGE_CLEANUP_HOURS = 24
IMAGE_FILTER_REASON = (
    "We believe your account may have been compromised and used to spread "
    "malicious scam images or links."
)
_image_filter_work_semaphore = asyncio.Semaphore(2)
_image_review_action_lock = asyncio.Lock()
_image_review_resolutions = set()
_image_ocr_lock = threading.Lock()
_image_ocr_engine = None
_image_ocr_unavailable = False
_image_content_cache = OrderedDict()
_image_content_cache_lock = threading.Lock()
_image_feedback_struct = struct.Struct(">32sQQ3sIB")

MRBEAST_ANCHOR = "mrbeast"
CRYPTO_SCAM_TERMS = (
    "bitcoin", "btc", "ethereum", "eth", "usdt", "crypto", "cryptocurrency",
    "solana", "dogecoin", "xrp",
)
SCAM_SOLICITATION_TERMS = (
    "send", "deposit", "transfer", "contribution", "wallet", "claim", "scan",
)
SCAM_RETURN_TERMS = (
    "send you back", "sent back", "get back", "receive back", "double", "multiply",
    "guaranteed return", "2x", "x2",
)
SCAM_GIVEAWAY_TERMS = (
    "giveaway", "give away", "airdrop", "promotion", "bonus", "reward", "prize",
)
SCAM_DESTINATION_TERMS = (
    "wallet address", "contribution address", "qr code", "scan code", "website",
    "visit", "http", "www", ".com", ".net", ".org",
)


@dataclass(frozen=True)
class ScamContentMatch:
    matched: bool = False
    confidence: int = 0
    category: str = ""
    signals: Tuple[str, ...] = ()
    matched_terms: Tuple[str, ...] = ()
    text: str = ""


@dataclass(frozen=True)
class ImageMatch:
    entry: Optional[dict] = None
    distance: int = 0
    vertical_distance: int = 0
    color_distance: int = 0
    quality: str = "none"
    confidence: int = 0

    @property
    def matched(self) -> bool:
        return self.entry is not None


@dataclass(frozen=True)
class ImageFilterResult:
    matched: bool = False
    message_deleted: bool = False
    block_downstream: bool = False
    cleanup_deleted: int = 0


@dataclass(frozen=True)
class ImageInspection:
    fingerprints: Tuple[dict, ...] = ()
    content_match: ScamContentMatch = ScamContentMatch()
    complete: bool = True
    is_image: bool = False
    reason: str = ""


def _normalize_hex(value, length: int) -> str:
    normalized = str(value or "").strip().lower()
    if len(normalized) != length:
        return ""
    try:
        int(normalized, 16)
    except ValueError:
        return ""
    return normalized


def _coerce_image_filter_int(value, default: int, *, minimum: int = 0, maximum: Optional[int] = None) -> int:
    try:
        normalized = int(value)
    except (TypeError, ValueError):
        normalized = default
    normalized = max(minimum, normalized)
    if maximum is not None:
        normalized = min(maximum, normalized)
    return normalized


def _normalize_image_fingerprint_entries(raw_entries, *, limit: int, default_label: str) -> List[dict]:
    if not isinstance(raw_entries, list):
        return []
    entries = []
    seen = set()
    for item in raw_entries:
        if not isinstance(item, dict):
            continue
        hash_hex = _normalize_hex(item.get("hash"), 16)
        if not hash_hex:
            continue
        sha256_hex = _normalize_hex(item.get("sha256"), 64)
        vertical_hash = _normalize_hex(item.get("vhash"), 16)
        color_hash = _normalize_hex(item.get("color"), 6)
        aspect = _coerce_image_filter_int(item.get("aspect"), 0, maximum=100_000)
        detail = _coerce_image_filter_int(item.get("detail"), 0, maximum=255)
        source_url = str(item.get("url", "") or "").strip()
        if not source_url.startswith(("http://", "https://")):
            source_url = ""
        identity = sha256_hex or f"{hash_hex}:{vertical_hash}:{color_hash}:{aspect}"
        if identity in seen:
            continue
        seen.add(identity)
        entries.append({
            "id": hashlib.sha256(identity.encode("utf-8")).hexdigest()[:16],
            "hash": hash_hex,
            "vhash": vertical_hash,
            "color": color_hash,
            "aspect": aspect,
            "detail": detail,
            "sha256": sha256_hex,
            "url": source_url,
            "label": truncate_text(str(item.get("label", default_label) or default_label), 80),
            "added_by": _coerce_image_filter_int(item.get("added_by"), 0),
            "added_at": str(item.get("added_at", "") or ""),
        })
    return entries[:limit]


def normalize_image_filter_settings(current: dict) -> dict:
    if not isinstance(current, dict):
        current = {}
    entries = _normalize_image_fingerprint_entries(
        current.get("entries", []),
        limit=IMAGE_FILTER_MAX_ENTRIES,
        default_label="Banned image",
    )
    false_positives = _normalize_image_fingerprint_entries(
        current.get("false_positives", []),
        limit=IMAGE_FILTER_MAX_FALSE_POSITIVES,
        default_label="Staff-confirmed false positive",
    )
    punishment_type = str(current.get("punishment_type", "warn") or "warn").lower()
    if punishment_type not in {"warn", "timeout", "kick", "ban"}:
        punishment_type = "warn"
    return {
        "enabled": bool(current.get("enabled", False)),
        "delete_message": bool(current.get("delete_message", True)),
        "log_detections": bool(current.get("log_detections", True)),
        "scan_scam_content": bool(current.get("scan_scam_content", True)),
        "punish": bool(current.get("punish", False)),
        "punishment_type": punishment_type,
        "duration_minutes": _coerce_image_filter_int(current.get("duration_minutes"), 60, minimum=1, maximum=40320),
        "entries": entries,
        "false_positives": false_positives,
    }


def get_image_filter_settings() -> dict:
    return normalize_image_filter_settings(bot.data_manager.config.get("image_filters", {}))


def store_image_filter_settings(settings: dict) -> dict:
    normalized = normalize_image_filter_settings(settings)
    bot.data_manager.config["image_filters"] = normalized
    bot.data_manager.mark_config_dirty()
    return normalized


async def _collect_image_cleanup_channels(guild: discord.Guild, after: datetime) -> List:
    channels = []
    seen = set()

    def add_channel(channel) -> None:
        channel_id = getattr(channel, "id", None)
        if channel_id is not None and channel_id not in seen:
            seen.add(channel_id)
            channels.append(channel)

    text_channels = list(getattr(guild, "text_channels", []) or [])
    message_channels = [
        *text_channels,
        *list(getattr(guild, "voice_channels", []) or []),
        *list(getattr(guild, "stage_channels", []) or []),
    ]
    for channel in message_channels:
        add_channel(channel)
    for thread in list(getattr(guild, "threads", []) or []):
        add_channel(thread)

    parents = [*text_channels, *list(getattr(guild, "forums", []) or [])]
    for parent in parents:
        variants = ({},)
        if isinstance(parent, discord.TextChannel):
            variants = ({}, {"private": True, "joined": True})
        for kwargs in variants:
            try:
                async for thread in parent.archived_threads(limit=None, **kwargs):
                    archived_at = getattr(thread, "archive_timestamp", None)
                    if archived_at and archived_at < after:
                        break
                    add_channel(thread)
            except (discord.Forbidden, discord.HTTPException, AttributeError):
                continue
    return channels


async def _delete_recent_user_messages_in_channel(
    channel,
    bot_member: discord.Member,
    user_id: int,
    after: datetime,
    exclude_message_id: Optional[int] = None,
) -> int:
    try:
        permissions = channel.permissions_for(bot_member)
        if not permissions.view_channel or not permissions.read_message_history or not permissions.manage_messages:
            return 0
    except AttributeError:
        return 0

    messages = []
    try:
        async for candidate in channel.history(limit=None, after=after, oldest_first=False):
            if candidate.author.id == user_id and candidate.id != exclude_message_id:
                messages.append(candidate)
    except (discord.Forbidden, discord.HTTPException, AttributeError):
        return 0

    deleted = 0
    for index in range(0, len(messages), 100):
        chunk = messages[index:index + 100]
        try:
            await channel.delete_messages(chunk, reason="24-hour cleanup after trusted image-filter detection")
            deleted += len(chunk)
        except (discord.Forbidden, discord.HTTPException, discord.NotFound):
            for candidate in chunk:
                try:
                    await candidate.delete()
                    deleted += 1
                except (discord.Forbidden, discord.HTTPException, discord.NotFound):
                    continue
    return deleted


async def delete_flagged_user_messages_for_24_hours(
    guild: discord.Guild,
    user_id: int,
    *,
    reference: Optional[datetime] = None,
    exclude_message_id: Optional[int] = None,
) -> int:
    reference = reference or discord.utils.utcnow()
    if reference.tzinfo is None:
        reference = reference.replace(tzinfo=timezone.utc)
    after = reference.astimezone(timezone.utc) - timedelta(hours=IMAGE_MESSAGE_CLEANUP_HOURS)
    channels = await _collect_image_cleanup_channels(guild, after)
    bot_member = getattr(guild, "me", None)
    if bot_member is None:
        return 0
    semaphore = asyncio.Semaphore(IMAGE_MESSAGE_CLEANUP_CONCURRENCY)

    async def guarded_cleanup(channel) -> int:
        async with semaphore:
            return await _delete_recent_user_messages_in_channel(
                channel,
                bot_member,
                user_id,
                after,
                exclude_message_id,
            )

    results = await asyncio.gather(*(guarded_cleanup(channel) for channel in channels), return_exceptions=True)
    return sum(result for result in results if isinstance(result, int))


def _difference_hash(image, *, vertical: bool = False) -> str:
    from PIL import Image

    size = (8, 9) if vertical else (9, 8)
    gray = image.convert("L").resize(size, Image.Resampling.LANCZOS)
    pixels = list(gray.getdata())
    bits = 0
    if vertical:
        for row in range(8):
            for col in range(8):
                bits = (bits << 1) | (pixels[row * 8 + col] > pixels[(row + 1) * 8 + col])
    else:
        for row in range(8):
            for col in range(8):
                bits = (bits << 1) | (pixels[row * 9 + col] > pixels[row * 9 + col + 1])
    return f"{bits:016x}"


def _thumbnail_with_orientation(image, orientation: int):
    from PIL import Image

    image.thumbnail((64, 64), Image.Resampling.LANCZOS)
    transpose = {
        2: Image.Transpose.FLIP_LEFT_RIGHT,
        3: Image.Transpose.ROTATE_180,
        4: Image.Transpose.FLIP_TOP_BOTTOM,
        5: Image.Transpose.TRANSPOSE,
        6: Image.Transpose.ROTATE_270,
        7: Image.Transpose.TRANSVERSE,
        8: Image.Transpose.ROTATE_90,
    }.get(orientation)
    return image.transpose(transpose) if transpose is not None else image


def _within_one_edit(candidate: str, target: str) -> bool:
    if abs(len(candidate) - len(target)) > 1:
        return False
    previous = list(range(len(target) + 1))
    for row, left in enumerate(candidate, 1):
        current = [row]
        for column, right in enumerate(target, 1):
            current.append(min(
                current[-1] + 1,
                previous[column] + 1,
                previous[column - 1] + (left != right),
            ))
        previous = current
    return previous[-1] <= 1


def _has_mrbeast_anchor(text: str) -> bool:
    anchor_text = text.translate(str.maketrans({
        "0": "o", "1": "i", "3": "e", "4": "a", "5": "s",
        "7": "t", "8": "b", "$": "s", "|": "i",
    }))
    compact = re.sub(r"[^a-z0-9]", "", anchor_text)
    if MRBEAST_ANCHOR in compact:
        return True
    for size in range(len(MRBEAST_ANCHOR) - 1, len(MRBEAST_ANCHOR) + 2):
        for start in range(0, max(0, len(compact) - size + 1)):
            if _within_one_edit(compact[start:start + size], MRBEAST_ANCHOR):
                return True
    return False


def _find_scam_term(text: str, terms: Tuple[str, ...]) -> Optional[str]:
    for term in terms:
        if term.startswith(".") or term in {"http", "www"}:
            if term in text:
                return term
            continue
        pattern = r"(?<![a-z0-9])" + re.escape(term).replace(r"\ ", r"\s+") + r"(?![a-z0-9])"
        if re.search(pattern, text):
            return term
    return None


def detect_mrbeast_crypto_scam(lines: Iterable[Union[str, Tuple[str, float]]]) -> ScamContentMatch:
    accepted = []
    confidences = []
    for item in lines:
        if isinstance(item, tuple):
            raw_text = str(item[0] or "")
            try:
                confidence = float(item[1])
            except (TypeError, ValueError):
                confidence = 0.0
        else:
            raw_text = str(item or "")
            confidence = 1.0
        if confidence < IMAGE_OCR_MIN_LINE_CONFIDENCE:
            continue
        normalized = unicodedata.normalize("NFKC", raw_text).lower().strip()
        if normalized:
            accepted.append(normalized[:300])
            confidences.append(min(1.0, max(0.0, confidence)))
        if sum(len(value) for value in accepted) >= 3000:
            break
    text = "\n".join(accepted)
    if not text:
        return ScamContentMatch()

    anchor = _has_mrbeast_anchor(text)
    crypto_term = _find_scam_term(text, CRYPTO_SCAM_TERMS)
    solicitation_term = _find_scam_term(text, SCAM_SOLICITATION_TERMS)
    return_term = _find_scam_term(text, SCAM_RETURN_TERMS)
    giveaway_term = _find_scam_term(text, SCAM_GIVEAWAY_TERMS)
    destination_term = _find_scam_term(text, SCAM_DESTINATION_TERMS)
    crypto = crypto_term is not None
    solicitation = solicitation_term is not None
    returns = return_term is not None
    giveaway = giveaway_term is not None
    destination = destination_term is not None
    structural_match = anchor and crypto and solicitation and (returns or (giveaway and destination))

    score = (
        (35 if anchor else 0)
        + (20 if crypto else 0)
        + (15 if solicitation else 0)
        + (20 if returns else 0)
        + (10 if giveaway else 0)
        + (10 if destination else 0)
    )
    average_confidence = sum(confidences) / len(confidences)
    confidence = min(100, round(score * (0.9 + 0.1 * average_confidence)))
    signals = tuple(
        label for label, present in (
            ("MrBeast", anchor),
            ("crypto", crypto),
            ("solicitation", solicitation),
            ("promised return", returns),
            ("giveaway", giveaway),
            ("payment destination", destination),
        )
        if present
    )
    return ScamContentMatch(
        matched=structural_match and confidence >= IMAGE_SCAM_CONFIDENCE_THRESHOLD,
        confidence=confidence,
        category="MrBeast crypto scam" if structural_match else "",
        signals=signals,
        matched_terms=tuple(dict.fromkeys(
            term for term in (
                "MrBeast" if anchor else None,
                crypto_term,
                solicitation_term,
                return_term,
                giveaway_term,
                destination_term,
            )
            if term
        )),
        text=truncate_text(text, 1000),
    )


def detect_image_content(lines: Iterable[Union[str, Tuple[str, float]]]) -> ScamContentMatch:
    captured_lines = tuple(lines)
    matches = (detect_mrbeast_crypto_scam(captured_lines),)
    return max(matches, key=lambda match: (match.matched, match.confidence))


def _run_local_image_ocr(image) -> Tuple[Tuple[str, float], ...]:
    global _image_ocr_engine, _image_ocr_unavailable
    with _image_ocr_lock:
        if _image_ocr_unavailable:
            return ()
        if _image_ocr_engine is None:
            try:
                import rapidocr
                from rapidocr import RapidOCR
                model_root = Path(rapidocr.__file__).resolve().parent / "models"
                model_paths = {
                    "Det.model_path": model_root / "PP-OCRv6_det_small.onnx",
                    "Cls.model_path": model_root / "ch_ppocr_mobile_v2.0_cls_mobile.onnx",
                    "Rec.model_path": model_root / "PP-OCRv6_rec_small.onnx",
                }
                if not all(path.is_file() for path in model_paths.values()):
                    raise RuntimeError("the bundled OCR model files are missing")
                logging.getLogger("RapidOCR").setLevel(logging.ERROR)
                _image_ocr_engine = RapidOCR(params={
                    "Global.log_level": "error",
                    "EngineConfig.onnxruntime.intra_op_num_threads": 1,
                    "EngineConfig.onnxruntime.inter_op_num_threads": 1,
                    **{key: str(path) for key, path in model_paths.items()},
                })
            except Exception as exc:
                _image_ocr_unavailable = True
                logger.error("Local image OCR could not initialize: %s", exc)
                return ()
        try:
            result = _image_ocr_engine(image)
        except Exception as exc:
            logger.warning("Local image OCR could not inspect an image: %s", exc)
            return ()

    texts = tuple(getattr(result, "txts", ()) or ())
    scores = tuple(getattr(result, "scores", ()) or ())
    return tuple(
        (str(value), float(scores[index]) if index < len(scores) else 0.0)
        for index, value in enumerate(texts)
    )


def _analyze_image_content(image, sha256_hex: str) -> ScamContentMatch:
    from PIL import Image

    with _image_content_cache_lock:
        cached = _image_content_cache.get(sha256_hex)
        if cached is not None:
            _image_content_cache.move_to_end(sha256_hex)
            return cached
    sample = image.convert("RGB")
    sample.thumbnail((IMAGE_OCR_MAX_DIMENSION, IMAGE_OCR_MAX_DIMENSION), Image.Resampling.LANCZOS)
    match = detect_image_content(_run_local_image_ocr(sample))
    with _image_content_cache_lock:
        _image_content_cache[sha256_hex] = match
        _image_content_cache.move_to_end(sha256_hex)
        while len(_image_content_cache) > IMAGE_CONTENT_CACHE_LIMIT:
            _image_content_cache.popitem(last=False)
    return match


def _fingerprint_sample(image, *, width: int, height: int, sha256_hex: str) -> dict:
    from PIL import Image

    sample = image.convert("RGB")
    rgb_pixels = list(sample.resize((16, 16), Image.Resampling.LANCZOS).getdata())
    gray_pixels = list(sample.convert("L").resize((16, 16), Image.Resampling.LANCZOS).getdata())
    average_color = tuple(round(sum(pixel[index] for pixel in rgb_pixels) / len(rgb_pixels)) for index in range(3))
    mean_luminance = sum(gray_pixels) / len(gray_pixels)
    detail = round((sum((pixel - mean_luminance) ** 2 for pixel in gray_pixels) / len(gray_pixels)) ** 0.5)
    return {
        "hash": _difference_hash(sample),
        "vhash": _difference_hash(sample, vertical=True),
        "color": "".join(f"{channel:02x}" for channel in average_color),
        "aspect": round(width * 1000 / height),
        "detail": detail,
        "sha256": sha256_hex,
    }


def inspect_image_bytes(data: bytes, *, analyze_content: bool = False) -> ImageInspection:
    try:
        from PIL import Image, ImageOps, UnidentifiedImageError
    except ImportError:
        return ImageInspection(complete=False, reason="Pillow is unavailable")
    if not data:
        return ImageInspection()
    if len(data) > IMAGE_FILTER_MAX_BYTES:
        return ImageInspection(complete=False, reason="attachment exceeds the byte limit")
    recognized_image = False
    try:
        with warnings.catch_warnings():
            warnings.simplefilter("error", Image.DecompressionBombWarning)
            with Image.open(io.BytesIO(data)) as source:
                recognized_image = True
                if source.format not in IMAGE_FILTER_ALLOWED_FORMATS:
                    return ImageInspection(complete=False, is_image=True, reason="unsupported image format")
                width, height = source.size
                if width <= 0 or height <= 0 or width * height > IMAGE_FILTER_MAX_PIXELS:
                    return ImageInspection(complete=False, is_image=True, reason="image exceeds the pixel limit")
                if max(1, int(getattr(source, "n_frames", 1) or 1)) > 1:
                    return ImageInspection(complete=False, is_image=True, reason="animated images require manual review")
                orientation = source.getexif().get(274, 1)
                if orientation in {5, 6, 7, 8}:
                    width, height = height, width
                sha256_hex = hashlib.sha256(data).hexdigest()
                content_match = ScamContentMatch()
                if analyze_content:
                    content_match = _analyze_image_content(
                        ImageOps.exif_transpose(source.copy()),
                        sha256_hex,
                    )
                source.draft("RGB", (64, 64))
                sample = _thumbnail_with_orientation(source, orientation)
                fingerprint = _fingerprint_sample(sample, width=width, height=height, sha256_hex=sha256_hex)
                return ImageInspection(
                    fingerprints=(fingerprint,),
                    content_match=content_match,
                    complete=True,
                    is_image=True,
                )
    except UnidentifiedImageError:
        return ImageInspection()
    except Exception:
        return ImageInspection(complete=False, is_image=recognized_image, reason="image decoding failed")


def fingerprint_image_bytes(data: bytes) -> Optional[dict]:
    inspection = inspect_image_bytes(data)
    return inspection.fingerprints[0] if inspection.fingerprints else None


def hash_image_bytes(data: bytes) -> Optional[str]:
    fingerprint = fingerprint_image_bytes(data)
    return fingerprint["hash"] if fingerprint else None


def hash_distance(first: str, second: str) -> int:
    return bin(int(first, 16) ^ int(second, 16)).count("1")


def _color_distance(first: str, second: str) -> int:
    first_rgb = tuple(int(first[index:index + 2], 16) for index in range(0, 6, 2))
    second_rgb = tuple(int(second[index:index + 2], 16) for index in range(0, 6, 2))
    return sum(abs(left - right) for left, right in zip(first_rgb, second_rgb))


def match_banned_image(fingerprint: Union[str, dict], entries: List[dict]) -> ImageMatch:
    if isinstance(fingerprint, str):
        fingerprint = {"hash": _normalize_hex(fingerprint, 16)}
    if not isinstance(fingerprint, dict):
        return ImageMatch()
    hash_hex = _normalize_hex(fingerprint.get("hash"), 16)
    if not hash_hex:
        return ImageMatch()

    best_match = ImageMatch()
    best_score = (99, 999, 999)
    for entry in entries:
        if not isinstance(entry, dict):
            continue
        entry_hash = _normalize_hex(entry.get("hash"), 16)
        if not entry_hash:
            continue
        distance = hash_distance(hash_hex, entry_hash)
        fingerprint_sha = _normalize_hex(fingerprint.get("sha256"), 64)
        entry_sha = _normalize_hex(entry.get("sha256"), 64)
        if fingerprint_sha and entry_sha and fingerprint_sha == entry_sha:
            candidate = ImageMatch(entry=entry, distance=distance, quality="exact")
            score = (0, distance, 0)
        else:
            vertical_hash = _normalize_hex(fingerprint.get("vhash"), 16)
            entry_vertical_hash = _normalize_hex(entry.get("vhash"), 16)
            color_hash = _normalize_hex(fingerprint.get("color"), 6)
            entry_color_hash = _normalize_hex(entry.get("color"), 6)
            aspect = _coerce_image_filter_int(fingerprint.get("aspect"), 0)
            entry_aspect = _coerce_image_filter_int(entry.get("aspect"), 0)
            if not vertical_hash or not entry_vertical_hash or not color_hash or not entry_color_hash or not aspect or not entry_aspect:
                if entry_hash in {"0" * 16, "f" * 16}:
                    continue
                if distance > IMAGE_HASH_DISTANCE_THRESHOLD:
                    continue
                candidate = ImageMatch(entry=entry, distance=distance, quality="legacy")
                score = (3, distance, 0)
            else:
                vertical_distance = hash_distance(vertical_hash, entry_vertical_hash)
                color_distance = _color_distance(color_hash, entry_color_hash)
                aspect_delta = abs(aspect - entry_aspect)
                if (
                    distance > IMAGE_HASH_DISTANCE_THRESHOLD
                    or vertical_distance > IMAGE_HASH_DISTANCE_THRESHOLD
                    or color_distance > IMAGE_COLOR_DISTANCE_THRESHOLD
                    or aspect_delta > IMAGE_ASPECT_DELTA_THRESHOLD
                ):
                    continue
                detail = _coerce_image_filter_int(fingerprint.get("detail"), 0)
                entry_detail = _coerce_image_filter_int(entry.get("detail"), 0)
                strong = (
                    distance <= IMAGE_STRONG_HASH_DISTANCE_THRESHOLD
                    and vertical_distance <= IMAGE_STRONG_HASH_DISTANCE_THRESHOLD
                    and color_distance <= IMAGE_STRONG_COLOR_DISTANCE_THRESHOLD
                    and aspect_delta <= IMAGE_STRONG_ASPECT_DELTA_THRESHOLD
                    and min(detail, entry_detail) >= IMAGE_STRONG_MIN_DETAIL
                )
                quality = "strong" if strong else "fuzzy"
                candidate = ImageMatch(
                    entry=entry,
                    distance=distance,
                    vertical_distance=vertical_distance,
                    color_distance=color_distance,
                    quality=quality,
                )
                score = (1 if strong else 2, distance + vertical_distance, color_distance)
        if score < best_score:
            best_match = candidate
            best_score = score
    return best_match


def image_match_allows_punishment(punishment_type: str, match: ImageMatch) -> bool:
    trusted_quality = match.quality in {"exact", "strong"}
    trusted_content = match.quality == "content" and match.confidence >= IMAGE_SCAM_CONFIDENCE_THRESHOLD
    return punishment_type in {"warn", "timeout", "kick", "ban"} and (trusted_quality or trusted_content)


def image_match_similarity(match: ImageMatch) -> int:
    if not match.matched:
        return 0
    if match.quality == "exact":
        return 100
    if match.quality == "content":
        return max(0, min(100, match.confidence))
    if match.quality == "reference":
        return max(0, min(100, match.confidence))
    if match.quality == "legacy":
        return max(0, min(100, round((1 - min(64, match.distance) / 64) * 100)))
    hash_similarity = 1 - min(128, match.distance + match.vertical_distance) / 128
    color_similarity = 1 - min(765, match.color_distance) / 765
    return max(0, min(100, round((hash_similarity * 0.8 + color_similarity * 0.2) * 100)))


def find_closest_image_reference(fingerprint: dict, entries: List[dict]) -> ImageMatch:
    hash_hex = _normalize_hex(fingerprint.get("hash"), 16) if isinstance(fingerprint, dict) else ""
    if not hash_hex:
        return ImageMatch()

    best = ImageMatch()
    best_similarity = -1
    for entry in entries:
        reference_url = str(entry.get("url", "") or "").strip() if isinstance(entry, dict) else ""
        entry_hash = _normalize_hex(entry.get("hash"), 16) if isinstance(entry, dict) else ""
        if not reference_url.startswith(("http://", "https://")) or not entry_hash:
            continue
        direct_match = match_banned_image(fingerprint, [entry])
        if direct_match.matched:
            candidate = direct_match
            similarity = image_match_similarity(candidate)
        else:
            distance = hash_distance(hash_hex, entry_hash)
            vertical_hash = _normalize_hex(fingerprint.get("vhash"), 16)
            entry_vertical_hash = _normalize_hex(entry.get("vhash"), 16)
            color_hash = _normalize_hex(fingerprint.get("color"), 6)
            entry_color_hash = _normalize_hex(entry.get("color"), 6)
            vertical_distance = (
                hash_distance(vertical_hash, entry_vertical_hash)
                if vertical_hash and entry_vertical_hash
                else 64
            )
            color_distance = (
                _color_distance(color_hash, entry_color_hash)
                if color_hash and entry_color_hash
                else 765
            )
            hash_similarity = 1 - min(128, distance + vertical_distance) / 128
            color_similarity = 1 - min(765, color_distance) / 765
            aspect = _coerce_image_filter_int(fingerprint.get("aspect"), 0)
            entry_aspect = _coerce_image_filter_int(entry.get("aspect"), 0)
            aspect_penalty = min(20, abs(aspect - entry_aspect) / 25) if aspect and entry_aspect else 10
            similarity = max(0, min(100, round(hash_similarity * 80 + color_similarity * 20 - aspect_penalty)))
            candidate = ImageMatch(
                entry=entry,
                distance=distance,
                vertical_distance=vertical_distance,
                color_distance=color_distance,
                quality="reference",
                confidence=similarity,
            )
        if similarity > best_similarity:
            best = candidate
            best_similarity = similarity
    return best


def encode_image_feedback_fingerprint(fingerprint: dict) -> str:
    if not isinstance(fingerprint, dict):
        return ""
    sha256_hex = _normalize_hex(fingerprint.get("sha256"), 64)
    hash_hex = _normalize_hex(fingerprint.get("hash"), 16)
    vertical_hash = _normalize_hex(fingerprint.get("vhash"), 16)
    color_hash = _normalize_hex(fingerprint.get("color"), 6)
    aspect = _coerce_image_filter_int(fingerprint.get("aspect"), 0, maximum=100_000)
    detail = _coerce_image_filter_int(fingerprint.get("detail"), 0, maximum=255)
    if not all((sha256_hex, hash_hex, vertical_hash, color_hash, aspect)):
        return ""
    packed = _image_feedback_struct.pack(
        bytes.fromhex(sha256_hex),
        int(hash_hex, 16),
        int(vertical_hash, 16),
        bytes.fromhex(color_hash),
        aspect,
        detail,
    )
    return base64.urlsafe_b64encode(packed).decode("ascii").rstrip("=")


def decode_image_feedback_fingerprint(payload: str) -> Optional[dict]:
    try:
        padding = "=" * (-len(payload) % 4)
        packed = base64.urlsafe_b64decode(payload + padding)
        sha256_bytes, hash_value, vertical_value, color_bytes, aspect, detail = _image_feedback_struct.unpack(packed)
    except (ValueError, TypeError, struct.error):
        return None
    if aspect <= 0 or aspect > 100_000:
        return None
    return {
        "sha256": sha256_bytes.hex(),
        "hash": f"{hash_value:016x}",
        "vhash": f"{vertical_value:016x}",
        "color": color_bytes.hex(),
        "aspect": aspect,
        "detail": detail,
    }


def _attachment_looks_like_image(attachment) -> bool:
    content_type = str(getattr(attachment, "content_type", "") or "").lower()
    filename = str(getattr(attachment, "filename", "") or "").lower()
    return content_type.startswith("image/") or filename.endswith(IMAGE_FILTER_FILENAME_SUFFIXES)


def _bounded_image_filter_attachments(attachments):
    def attachment_priority(attachment) -> int:
        content_type = str(getattr(attachment, "content_type", "") or "").lower()
        if _attachment_looks_like_image(attachment):
            return 0
        if not content_type or content_type == "application/octet-stream":
            return 1
        return 2

    total_bytes = 0
    budget_exceeded = False
    selected = []
    ordered = sorted(attachments, key=attachment_priority)
    for attachment in ordered:
        size = _coerce_image_filter_int(getattr(attachment, "size", 0), 0)
        if size <= 0:
            budget_exceeded = True
            continue
        if size > IMAGE_FILTER_MAX_BYTES:
            budget_exceeded = True
            continue
        if total_bytes + size > IMAGE_FILTER_MAX_TOTAL_BYTES:
            budget_exceeded = True
            continue
        total_bytes += size
        selected.append(attachment)
    return selected, budget_exceeded


async def inspect_image_attachment(attachment, *, analyze_content: bool = False) -> ImageInspection:
    async with _image_filter_work_semaphore:
        try:
            data = await attachment.read()
        except Exception:
            return ImageInspection(complete=False, reason="attachment download failed")
        if len(data) > IMAGE_FILTER_MAX_BYTES:
            return ImageInspection(complete=False, reason="attachment exceeds the byte limit")
        return await asyncio.to_thread(inspect_image_bytes, data, analyze_content=analyze_content)


class ImageFalsePositiveButton(
    discord.ui.DynamicItem[discord.ui.Button],
    template=r"imagefp:false_positive:(?P<payload>[A-Za-z0-9_-]{75})",
):
    def __init__(self, fingerprint: dict) -> None:
        payload = encode_image_feedback_fingerprint(fingerprint)
        if not payload:
            raise ValueError("A complete image fingerprint is required")
        super().__init__(
            discord.ui.Button(
                label="Mark False Positive",
                style=discord.ButtonStyle.secondary,
                custom_id=f"imagefp:false_positive:{payload}",
            )
        )
        self.fingerprint = fingerprint

    @classmethod
    async def from_custom_id(
        cls,
        interaction: discord.Interaction,
        item: discord.ui.Button,
        match: "re.Match[str]",
        /,
    ) -> "ImageFalsePositiveButton":
        fingerprint = decode_image_feedback_fingerprint(match["payload"])
        if fingerprint is None:
            raise ValueError("Invalid image feedback fingerprint")
        return cls(fingerprint)

    async def callback(self, interaction: discord.Interaction) -> None:
        if not is_staff(interaction):
            await respond_with_error(
                interaction,
                "You do not have permission to review image-filter detections.",
                scope=SCOPE_MODERATION,
            )
            return
        if interaction.guild is None:
            await respond_with_error(interaction, "This review action must be used in a server.", scope=SCOPE_MODERATION)
            return

        await interaction.response.defer(ephemeral=True)

        settings = get_image_filter_settings()
        learned_match = match_banned_image(self.fingerprint, settings["false_positives"])
        if learned_match.quality in {"exact", "strong"}:
            await interaction.followup.send(
                embed=make_confirmation_embed(
                    "Already Learned",
                    "> Matching copies are already excluded from automatic image enforcement.",
                    scope=SCOPE_MODERATION,
                    guild=interaction.guild,
                ),
                ephemeral=True,
            )
            return

        source_message = interaction.message
        review_key = None
        if source_message is not None and getattr(source_message, "id", None):
            review_key = (interaction.guild.id, source_message.id)
            async with _image_review_action_lock:
                if review_key in _image_review_resolutions:
                    await interaction.followup.send(
                        embed=make_confirmation_embed(
                            "Review Already Resolved",
                            "> Another moderator already resolved this image review.",
                            scope=SCOPE_MODERATION,
                            guild=interaction.guild,
                        ),
                        ephemeral=True,
                    )
                    return
                _image_review_resolutions.add(review_key)

        try:
            settings["false_positives"] = settings["false_positives"][-(IMAGE_FILTER_MAX_FALSE_POSITIVES - 1):]
            settings["false_positives"].append({
                **self.fingerprint,
                "label": "Staff-confirmed false positive",
                "added_by": interaction.user.id,
                "added_at": now_iso(),
            })
            store_image_filter_settings(settings)
            await bot.data_manager.save_config()
        except Exception as exc:
            if review_key is not None:
                _image_review_resolutions.discard(review_key)
            logger.error("Could not save image-filter false-positive feedback: %s", exc)
            await interaction.followup.send(
                embed=make_embed(
                    "Review Failed",
                    "> The false-positive decision could not be saved. Try again.",
                    kind="error",
                    scope=SCOPE_MODERATION,
                    guild=interaction.guild,
                ),
                ephemeral=True,
            )
            return

        if source_message is None or not source_message.embeds:
            await interaction.followup.send(
                embed=make_confirmation_embed(
                    "False Positive Learned",
                    "> Matching copies will now bypass automatic image enforcement. Any current punishment must still be reviewed from its case.",
                    scope=SCOPE_MODERATION,
                    guild=interaction.guild,
                ),
                ephemeral=True,
            )
            return

        embed = discord.Embed.from_dict(source_message.embeds[0].to_dict())
        upsert_embed_field(
            embed,
            "Review",
            "False positive learned locally • matching copies will bypass future enforcement. Review the current case separately if a punishment was issued.",
            inline=False,
        )
        view = discord.ui.View.from_message(source_message, timeout=None)
        for child in view.children:
            custom_id = getattr(child, "custom_id", "") or ""
            if custom_id == self.item.custom_id:
                child.disabled = True
                child.label = "False Positive Learned"
            elif custom_id.startswith("imagefilter:punish:"):
                child.disabled = True
                child.label = "No Punishment"
        try:
            await source_message.edit(content=None, embed=embed, view=view)
        except Exception as exc:
            logger.warning(
                "Image filter could not update false-positive review message %s: %s",
                getattr(source_message, "id", "unknown"),
                exc,
            )
        await interaction.followup.send(
            embed=make_confirmation_embed(
                "False Positive Learned",
                "> Matching copies will now bypass automatic image enforcement.",
                scope=SCOPE_MODERATION,
                guild=interaction.guild,
            ),
            ephemeral=True,
        )


class ImageReviewPunishButton(
    discord.ui.DynamicItem[discord.ui.Button],
    template=(
        r"imagefilter:punish:(?P<user_id>[0-9]+):"
        r"(?P<channel_id>[0-9]+):(?P<message_id>[0-9]+)"
    ),
):
    def __init__(self, user_id: int, channel_id: int, message_id: int) -> None:
        super().__init__(
            discord.ui.Button(
                label="Apply Configured Punishment",
                style=discord.ButtonStyle.danger,
                custom_id=f"imagefilter:punish:{user_id}:{channel_id}:{message_id}",
            )
        )
        self.user_id = user_id
        self.channel_id = channel_id
        self.message_id = message_id

    @classmethod
    async def from_custom_id(
        cls,
        interaction: discord.Interaction,
        item: discord.ui.Button,
        match: "re.Match[str]",
        /,
    ) -> "ImageReviewPunishButton":
        return cls(
            int(match["user_id"]),
            int(match["channel_id"]),
            int(match["message_id"]),
        )

    async def callback(self, interaction: discord.Interaction) -> None:
        if not is_staff(interaction):
            await respond_with_error(
                interaction,
                "You do not have permission to review image-filter detections.",
                scope=SCOPE_MODERATION,
            )
            return
        guild = interaction.guild
        if guild is None:
            await respond_with_error(interaction, "This review action must be used in a server.", scope=SCOPE_MODERATION)
            return

        await interaction.response.defer(ephemeral=True)
        log_message = interaction.message
        log_message_id = getattr(log_message, "id", 0) or self.message_id
        review_key = (guild.id, log_message_id)
        async with _image_review_action_lock:
            if review_key in _image_review_resolutions:
                await interaction.followup.send(
                    embed=make_confirmation_embed(
                        "Review Already Resolved",
                        "> Another moderator already resolved this image review.",
                        scope=SCOPE_MODERATION,
                        guild=guild,
                    ),
                    ephemeral=True,
                )
                return

            existing_case = find_image_review_punishment(self.message_id)
            if existing_case is not None:
                _image_review_resolutions.add(review_key)
                await interaction.followup.send(
                    embed=make_confirmation_embed(
                        "Review Already Resolved",
                        f"> This image review was already resolved as Case #{existing_case.get('case_id', 'Unknown')}.",
                        scope=SCOPE_MODERATION,
                        guild=guild,
                    ),
                    ephemeral=True,
                )
                return

            member = await resolve_member(guild, self.user_id)
            if member is None:
                await interaction.followup.send(
                    embed=make_embed(
                        "Member Not Found",
                        "> The detected account is no longer in the server, so the configured punishment could not be applied.",
                        kind="error",
                        scope=SCOPE_MODERATION,
                        guild=guild,
                    ),
                    ephemeral=True,
                )
                return

            settings = get_image_filter_settings()
            try:
                applied, summary, case_record, dm_sent = await apply_image_filter_punishment(
                    guild,
                    member,
                    entry_label="Staff-confirmed image detection",
                    punishment_type=settings["punishment_type"],
                    duration_minutes=settings["duration_minutes"],
                )
            except Exception as exc:
                logger.error("Image review punishment failed unexpectedly for user %s: %s", member.id, exc)
                await interaction.followup.send(
                    embed=make_embed(
                        "Punishment Not Applied",
                        "> The configured punishment failed unexpectedly. Try again or punish the member manually.",
                        kind="error",
                        scope=SCOPE_MODERATION,
                        guild=guild,
                    ),
                    ephemeral=True,
                )
                return
            if not applied or not case_record:
                await interaction.followup.send(
                    embed=make_embed(
                        "Punishment Not Applied",
                        f"> {summary}",
                        kind="error",
                        scope=SCOPE_MODERATION,
                        guild=guild,
                    ),
                    ephemeral=True,
                )
                return

            cleanup_deleted = 0
            try:
                cleanup_deleted = await delete_flagged_user_messages_for_24_hours(
                    guild,
                    member.id,
                    exclude_message_id=self.message_id,
                )
            except Exception as exc:
                logger.warning("Image review could not complete cleanup for user %s: %s", member.id, exc)

            source_deleted = False
            if settings["delete_message"]:
                get_channel_or_thread = getattr(guild, "get_channel_or_thread", None)
                source_channel = get_channel_or_thread(self.channel_id) if callable(get_channel_or_thread) else None
                if source_channel is None:
                    source_channel = guild.get_channel(self.channel_id)
                fetch_message = getattr(source_channel, "fetch_message", None)
                if callable(fetch_message):
                    try:
                        source_message = await fetch_message(self.message_id)
                        await source_message.delete()
                        source_deleted = True
                    except discord.NotFound:
                        source_deleted = True
                    except Exception as exc:
                        logger.warning("Image review could not delete source message %s: %s", self.message_id, exc)

            note_lines = [
                line
                for line in str(case_record.get("note") or "").splitlines()
                if not line.startswith("24-Hour Cleanup:")
            ]
            note_lines.append(f"24-Hour Cleanup: {cleanup_deleted} earlier message(s) removed")
            case_record["note"] = truncate_text("\n".join(note_lines), 1000)
            case_record["image_review_source_message_id"] = self.message_id
            case_record["image_review_source_channel_id"] = self.channel_id
            try:
                await bot.data_manager.save_punishments()
            except Exception as exc:
                logger.warning("Image review could not update cleanup audit for case %s: %s", case_record.get("case_id"), exc)

            _image_review_resolutions.add(review_key)
            if log_message is not None and log_message.embeds:
                try:
                    from .cases import add_punishment_record_log_fields, get_case_label
                    from .case_panel import OpenCaseButton

                    embed = discord.Embed.from_dict(log_message.embeds[0].to_dict())
                    embed.title = f"[{get_case_label(case_record)}] Image Review Punished"
                    upsert_embed_field(embed, "Result", summary, inline=True)
                    upsert_embed_field(embed, "DM", "Delivered" if dm_sent else "Failed or closed", inline=True)
                    upsert_embed_field(
                        embed,
                        "Review",
                        "Configured punishment applied after moderator review.",
                        inline=False,
                    )
                    if cleanup_deleted:
                        upsert_embed_field(
                            embed,
                            "24-Hour Cleanup",
                            f"{cleanup_deleted} earlier message{'s' if cleanup_deleted != 1 else ''} removed",
                            inline=True,
                        )
                    if settings["delete_message"]:
                        upsert_embed_field(
                            embed,
                            "Source Message",
                            "Deleted" if source_deleted else "Could not be deleted",
                            inline=True,
                        )
                    add_punishment_record_log_fields(embed, case_record)
                    brand_embed(embed, guild=guild, scope=SCOPE_MODERATION)
                    view = discord.ui.View.from_message(log_message, timeout=None)
                    has_case_button = False
                    for child in view.children:
                        custom_id = getattr(child, "custom_id", "") or ""
                        if custom_id.startswith("imagefilter:punish:"):
                            child.disabled = True
                            child.label = "Punishment Applied"
                        elif custom_id.startswith("case:open:"):
                            has_case_button = True
                    if not has_case_button:
                        view.add_item(OpenCaseButton(case_record["case_id"]))
                    await log_message.edit(content=None, embed=embed, view=view)
                except Exception as exc:
                    logger.warning("Image review could not update log message %s: %s", log_message_id, exc)

            await interaction.followup.send(
                embed=make_confirmation_embed(
                    "Punishment Applied",
                    f"> {summary}",
                    scope=SCOPE_MODERATION,
                    guild=guild,
                ),
                ephemeral=True,
            )


def build_image_filter_log_view(
    case_id: Optional[int],
    fingerprint: Optional[dict],
    *,
    review_target: Optional[Tuple[int, int, int]] = None,
) -> Optional[discord.ui.View]:
    view = discord.ui.View(timeout=None)
    if case_id:
        from .case_panel import OpenCaseButton
        view.add_item(OpenCaseButton(case_id))
    if review_target:
        view.add_item(ImageReviewPunishButton(*review_target))
    if fingerprint and encode_image_feedback_fingerprint(fingerprint):
        view.add_item(ImageFalsePositiveButton(fingerprint))
    return view if view.children else None


def get_image_filter_review_ping(guild: discord.Guild) -> Optional[str]:
    config = bot.data_manager.config
    configured_mod_roles = config.get("mod_roles", [])
    if not isinstance(configured_mod_roles, list):
        configured_mod_roles = []
    candidates = [config.get("role_mod"), *configured_mod_roles]
    get_role = getattr(guild, "get_role", None)
    if not callable(get_role):
        return None
    for raw_role_id in candidates:
        try:
            role_id = int(raw_role_id)
        except (TypeError, ValueError):
            continue
        role = get_role(role_id)
        if role is not None:
            return role.mention
    return None


def find_image_review_punishment(source_message_id: int) -> Optional[dict]:
    punishments = getattr(bot.data_manager, "punishments", {})
    if not isinstance(punishments, dict):
        return None
    for records in punishments.values():
        if not isinstance(records, list):
            continue
        for record in records:
            if (
                isinstance(record, dict)
                and record.get("image_review_source_message_id") == source_message_id
            ):
                return record
    return None


async def log_image_filter_inspection_failure(message: discord.Message) -> None:
    try:
        embed = make_embed(
            "Image Filter Inspection Incomplete",
            f"> {format_user_ref(message.author)} posted an attachment the image filter could not inspect. Review it manually in {message.channel.mention}.",
            kind="warning",
            scope=SCOPE_MODERATION,
            guild=message.guild,
        )
        embed.add_field(name="Action", value="No automatic action — manual review required", inline=False)
        jump_url = str(getattr(message, "jump_url", "") or "")
        message_value = f"[{message.id}]({jump_url})" if jump_url.startswith(("http://", "https://")) else f"`{message.id}`"
        embed.add_field(name="Message ID", value=message_value, inline=True)
        image_attachment = next(
            (
                attachment for attachment in message.attachments
                if str(getattr(attachment, "content_type", "") or "").startswith("image/")
            ),
            None,
        )
        if image_attachment is not None:
            image_url = str(getattr(image_attachment, "url", "") or "")
            if image_url.startswith(("http://", "https://")):
                embed.set_image(url=image_url)
        await send_automod_log(message.guild, embed)
    except Exception as exc:
        logger.warning("Image filter could not report incomplete inspection for message %s: %s", message.id, exc)


async def resolve_image_filter_server_url(guild: discord.Guild, punishment_type: str) -> Optional[str]:
    if punishment_type == "ban":
        return None
    if punishment_type in {"warn", "timeout"}:
        return f"https://discord.com/channels/{guild.id}"

    try:
        vanity_invite = await guild.vanity_invite()
        vanity_url = str(getattr(vanity_invite, "url", "") or "")
        if vanity_url.startswith(("http://", "https://")):
            return vanity_url
    except (discord.Forbidden, discord.HTTPException, AttributeError):
        pass

    try:
        invites = await guild.invites()
    except (discord.Forbidden, discord.HTTPException, AttributeError):
        invites = []
    for invite in invites:
        max_uses = int(getattr(invite, "max_uses", 0) or 0)
        uses = int(getattr(invite, "uses", 0) or 0)
        if int(getattr(invite, "max_age", 0) or 0) != 0 or (max_uses and uses >= max_uses):
            continue
        invite_url = str(getattr(invite, "url", "") or "")
        if invite_url.startswith(("http://", "https://")):
            return invite_url

    bot_member = guild.me
    if bot_member is None:
        return None
    channels = []
    if guild.system_channel is not None:
        channels.append(guild.system_channel)
    channels.extend(channel for channel in guild.text_channels if channel not in channels)
    for channel in channels:
        try:
            permissions = channel.permissions_for(bot_member)
            if not permissions.view_channel or not permissions.create_instant_invite:
                continue
            invite = await channel.create_invite(
                max_age=0,
                max_uses=0,
                unique=False,
                reason="Return link for an automated image-filter punishment",
            )
            invite_url = str(getattr(invite, "url", "") or "")
            if invite_url.startswith(("http://", "https://")):
                return invite_url
        except (discord.Forbidden, discord.HTTPException, AttributeError):
            continue
    return None


async def prepare_image_filter_dm_destination(member: discord.Member):
    try:
        return await member.create_dm()
    except Exception:
        return member if callable(getattr(member, "send", None)) else None


def build_image_filter_return_view(server_url: Optional[str]) -> Optional[discord.ui.View]:
    if not server_url:
        return None
    view = discord.ui.View()
    view.add_item(discord.ui.Button(label="Return to Server", style=discord.ButtonStyle.link, url=server_url))
    return view


async def send_image_filter_user_dm(
    guild: discord.Guild,
    member: discord.Member,
    *,
    entry_label: str,
    action_label: str,
    punishment_type: Optional[str] = None,
    duration_minutes: int = 0,
    case_record: Optional[dict] = None,
    server_url: Optional[str] = None,
    destination=None,
) -> bool:
    destination = destination or await prepare_image_filter_dm_destination(member)
    if destination is None:
        return False
    server_name = str(getattr(guild, "name", "this server") or "this server")
    if punishment_type:
        description = f"You have been **{action_label}** in **{server_name}**."
    elif action_label == "Image Removed":
        description = f"An image you posted in **{server_name}** was automatically detected and removed."
    else:
        description = f"An image you posted in **{server_name}** was automatically flagged."
    try:
        icon = getattr(guild, "icon", None)
        dm_embed = make_embed(
            "Moderation Action Issued",
            f"> {description}",
            kind="danger",
            scope=SCOPE_MODERATION,
            guild=guild,
            thumbnail=icon.url if icon else None,
        )
        dm_embed.add_field(name="Reason", value=format_reason_value(IMAGE_FILTER_REASON, limit=1000), inline=False)
        if punishment_type == "timeout" and duration_minutes > 0:
            dm_embed.add_field(name="Duration", value=format_duration(duration_minutes), inline=True)
            expires = discord.utils.format_dt(discord.utils.utcnow() + get_valid_duration(duration_minutes), "R")
            dm_embed.add_field(name="Expires", value=expires, inline=True)
        elif punishment_type == "ban":
            dm_embed.add_field(name="Duration", value="Ban", inline=True)
        review_text = "This action was handled automatically by a image detection system. False positives are possible."
        if case_record:
            review_text += " If you believe this was an error, press **Appeal Punishment** below."
        else:
            review_text += " If you believe this was an error, contact the moderation team."
        dm_embed.add_field(name="Automated Detection Notice", value=f"> {review_text}", inline=False)
        if case_record:
            view = build_appeal_view(guild.id, case_record["case_id"], server_url=server_url)
        else:
            view = build_image_filter_return_view(server_url)
        await destination.send(embed=dm_embed, view=view)
        return True
    except Exception as exc:
        logger.info("Image-filter DM could not be delivered to user %s: %s", member.id, exc)
        return False


async def send_image_filter_enforcement_correction(
    guild: discord.Guild,
    member: discord.Member,
    *,
    destination=None,
) -> None:
    if destination is None:
        return
    try:
        embed = make_embed(
            "Moderation Action Update",
            "> The automatic punishment described in the previous message could not be applied. The detection was logged for staff review.",
            kind="warning",
            scope=SCOPE_MODERATION,
            guild=guild,
        )
        await destination.send(embed=embed)
    except Exception as exc:
        logger.info("Image-filter correction DM could not be delivered to user %s: %s", member.id, exc)


async def apply_image_filter_punishment(
    guild: discord.Guild,
    member: discord.Member,
    *,
    entry_label: str,
    punishment_type: str,
    duration_minutes: int,
    cleanup_deleted: int = 0,
) -> Tuple[bool, str, Optional[dict], bool]:
    if member.id == guild.owner_id or member.guild_permissions.administrator:
        return False, "Safety check skipped auto-punishment for the server owner or an administrator.", None, False
    if punishment_type in {"timeout", "kick", "ban"}:
        bot_member = guild.me
        if bot_member is None or member.top_role >= bot_member.top_role:
            return False, "Safety check skipped auto-punishment because the bot cannot manage this member.", None, False
    if is_staff_member(member, bot.data_manager.config):
        return False, "Safety check skipped auto-punishment for a moderator.", None, False
    try:
        server_url = await resolve_image_filter_server_url(guild, punishment_type)
    except Exception as exc:
        logger.warning("Could not build an image-filter return link for guild %s: %s", guild.id, exc)
        server_url = None
    dm_destination = await prepare_image_filter_dm_destination(member)

    reason = IMAGE_FILTER_REASON
    if punishment_type == "ban":
        action_label = "Banned"
    elif punishment_type == "timeout":
        action_label = "Timed Out"
    elif punishment_type == "kick":
        action_label = "Kicked"
    else:
        action_label = "Warned"
    user_message_text = f"You have been **{action_label}** in **{guild.name}**."
    note = truncate_text(
        "\n".join([
            "Image recognition automod triggered.",
            f"Matched Entry: {entry_label}",
            f"24-Hour Cleanup: {cleanup_deleted} earlier message(s) removed",
        ]),
        1000,
    )

    if punishment_type == "timeout" and duration_minutes <= 0:
        duration_minutes = 60
    if punishment_type == "ban":
        duration_minutes = -1

    record = {
        "reason": reason,
        "moderator": bot.user.id,
        "duration_minutes": duration_minutes if punishment_type != "kick" else 0,
        "timestamp": now_iso(),
        "escalated": False,
        "note": note,
        "user_msg": user_message_text,
        "target_name": get_user_display_name(member),
        "type": punishment_type if punishment_type in {"warn", "timeout", "ban", "kick"} else "warn",
        "active": punishment_type == "ban",
    }
    removal_action = punishment_type in {"kick", "ban"}
    case_record = None
    if removal_action:
        case_record = await bot.data_manager.add_punishment(str(member.id), record, persist=False)

    dm_sent = False
    if removal_action:
        dm_sent = await send_image_filter_user_dm(
            guild,
            member,
            entry_label=entry_label,
            action_label=action_label,
            punishment_type=punishment_type,
            duration_minutes=duration_minutes,
            case_record=case_record,
            server_url=server_url,
            destination=dm_destination,
        )

    try:
        if punishment_type == "timeout":
            await member.timeout(get_valid_duration(duration_minutes), reason=f"{reason} (By {bot.user})")
        elif punishment_type == "ban":
            await guild.ban(member, reason=f"{reason} (By {bot.user})", delete_message_days=0)
        elif punishment_type == "kick":
            await guild.kick(member, reason=f"{reason} (By {bot.user})")
    except discord.Forbidden:
        if case_record:
            try:
                await bot.data_manager.discard_pending_punishment(str(member.id), case_record["case_id"])
            except Exception as exc:
                logger.error("Could not discard failed image-filter case %s: %s", case_record.get("case_id"), exc)
        if dm_sent:
            await send_image_filter_enforcement_correction(guild, member, destination=dm_destination)
        return False, "The bot does not have permission to apply the configured punishment.", None, dm_sent
    except Exception as exc:
        if case_record:
            try:
                await bot.data_manager.discard_pending_punishment(str(member.id), case_record["case_id"])
            except Exception as discard_exc:
                logger.error("Could not discard failed image-filter case %s: %s", case_record.get("case_id"), discard_exc)
        if dm_sent:
            await send_image_filter_enforcement_correction(guild, member, destination=dm_destination)
        return False, f"Failed to apply punishment: {exc}", None, dm_sent

    if not removal_action:
        case_record = await bot.data_manager.add_punishment(str(member.id), record)
    bot.data_manager.config.setdefault("stats", {})["total_issued"] = bot.data_manager.config.get("stats", {}).get("total_issued", 0) + 1
    await bot.data_manager.save_config()

    if not dm_sent:
        dm_sent = await send_image_filter_user_dm(
            guild,
            member,
            entry_label=entry_label,
            action_label=action_label,
            punishment_type=punishment_type,
            duration_minutes=duration_minutes,
            case_record=case_record,
            server_url=server_url,
            destination=dm_destination,
        )

    status = punishment_type.title()
    if punishment_type == "warn":
        status = "Warning"
    elif punishment_type == "timeout":
        status = f"Timeout ({format_duration(duration_minutes)})"
    return True, f"Applied {status} automatically", case_record, dm_sent


async def run_image_filter(message: discord.Message) -> ImageFilterResult:
    if not message.guild or message.author.bot or not message.attachments:
        return ImageFilterResult()
    if not isinstance(message.author, discord.Member):
        return ImageFilterResult()
    settings = get_image_filter_settings()
    if not settings["enabled"] or (not settings["entries"] and not settings["scan_scam_content"]):
        return ImageFilterResult()
    if is_staff_member(message.author, bot.data_manager.config):
        return ImageFilterResult()
    native_settings = get_native_automod_settings(bot.data_manager.config)
    if is_native_automod_exempt(message.author, message.channel.id, native_settings):
        return ImageFilterResult()

    matched_attachment = None
    matched_fingerprint = None
    matched = ImageMatch()
    quality_rank = {"exact": 0, "content": 1, "strong": 2, "fuzzy": 3, "legacy": 4, "none": 5}
    decoded_images = 0
    attachments, inspection_incomplete = _bounded_image_filter_attachments(message.attachments)
    for attachment in attachments:
        inspection = await inspect_image_attachment(
            attachment,
            analyze_content=settings["scan_scam_content"],
        )
        fingerprints = list(inspection.fingerprints)
        inspection_incomplete = inspection_incomplete or not inspection.complete
        if inspection.is_image:
            decoded_images += 1

        learned_exception = next(
            (
                exception_match
                for fingerprint in fingerprints
                if (exception_match := match_banned_image(fingerprint, settings["false_positives"])).quality
                in {"exact", "strong"}
            ),
            None,
        )
        if learned_exception is not None:
            continue

        if inspection.content_match.matched:
            content_label = inspection.content_match.category
            if not content_label and {"MrBeast", "crypto"}.issubset(inspection.content_match.signals):
                content_label = "MrBeast crypto scam"
            candidate = ImageMatch(
                entry={
                    "label": content_label or "Image content policy match",
                    "signals": inspection.content_match.signals,
                    "matched_terms": inspection.content_match.matched_terms,
                    "ocr_text": inspection.content_match.text,
                },
                quality="content",
                confidence=inspection.content_match.confidence,
            )
            if (
                quality_rank[candidate.quality] < quality_rank[matched.quality]
                or (
                    matched.quality == "content"
                    and candidate.confidence > matched.confidence
                )
            ):
                matched = candidate
                matched_attachment = attachment
                matched_fingerprint = fingerprints[0] if fingerprints else None

        if not fingerprints:
            if matched.quality == "content" or decoded_images >= IMAGE_FILTER_MAX_ATTACHMENTS:
                break
            continue
        for fingerprint in fingerprints:
            candidate = match_banned_image(fingerprint, settings["entries"])
            if candidate.matched and quality_rank[candidate.quality] < quality_rank[matched.quality]:
                matched = candidate
                matched_attachment = attachment
                matched_fingerprint = fingerprint
            if matched.quality == "exact":
                break
        if matched.quality in {"exact", "content"}:
            break
        if decoded_images >= IMAGE_FILTER_MAX_ATTACHMENTS:
            break
    if not matched.matched:
        if inspection_incomplete:
            await log_image_filter_inspection_failure(message)
            return ImageFilterResult(block_downstream=bool(settings["delete_message"]))
        return ImageFilterResult()

    trusted_match = (
        matched.quality in {"exact", "strong"}
        or (
            matched.quality == "content"
            and matched.confidence >= IMAGE_SCAM_CONFIDENCE_THRESHOLD
        )
    )
    review_required = not trusted_match
    deleted = False
    if settings["delete_message"] and trusted_match:
        try:
            await message.delete()
            deleted = True
        except Exception as exc:
            logger.warning("Image filter could not delete matched message %s: %s", message.id, exc)

    cleanup_deleted = 0
    if trusted_match:
        try:
            cleanup_deleted = await delete_flagged_user_messages_for_24_hours(
                message.guild,
                message.author.id,
                exclude_message_id=message.id,
            )
        except Exception as exc:
            logger.warning("Image filter could not complete the 24-hour cleanup for user %s: %s", message.author.id, exc)

    result = ImageFilterResult(
        matched=True,
        message_deleted=deleted,
        block_downstream=bool(settings["delete_message"] and (trusted_match or inspection_incomplete)),
        cleanup_deleted=cleanup_deleted,
    )

    case_record = None
    dm_sent = False
    action_summary = "Message deleted" if deleted else "Detection logged"
    if trusted_match and not settings["punish"]:
        action_summary += " • automatic punishment is disabled in Image Filter settings"
    failure_requires_log = not trusted_match or inspection_incomplete
    if inspection_incomplete and not trusted_match:
        action_summary = "Staff review required: inspection incomplete"
    elif not trusted_match:
        action_summary = f"Staff review required: {matched.quality} match"
    elif settings["delete_message"] and not deleted:
        action_summary = "Message deletion failed"
        failure_requires_log = True
    if settings["punish"]:
        if not image_match_allows_punishment(settings["punishment_type"], matched):
            action_summary = f"Auto-punish skipped: {matched.quality} match requires staff review"
            failure_requires_log = True
        else:
            try:
                applied, punish_summary, case_record, dm_sent = await apply_image_filter_punishment(
                    message.guild,
                    message.author,
                    entry_label=matched.entry["label"],
                    punishment_type=settings["punishment_type"],
                    duration_minutes=settings["duration_minutes"],
                    cleanup_deleted=cleanup_deleted,
                )
            except Exception as exc:
                applied = False
                punish_summary = f"Auto-punish failed unexpectedly: {exc}"
                case_record = None
                dm_sent = False
            action_summary = punish_summary
            if not applied:
                failure_requires_log = True

    if trusted_match and not dm_sent:
        try:
            server_url = await resolve_image_filter_server_url(message.guild, "warn")
        except Exception:
            server_url = None
        dm_sent = await send_image_filter_user_dm(
            message.guild,
            message.author,
            entry_label=matched.entry["label"],
            action_label="Image Removed" if deleted else "Image Flagged",
            server_url=server_url,
        )

    if settings["log_detections"] or failure_requires_log:
        try:
            flagged_url = str(getattr(matched_attachment, "url", "") or "").strip()
            reference_match = ImageMatch()
            direct_reference_url = str(matched.entry.get("url") or "").strip()
            if direct_reference_url.startswith(("http://", "https://")):
                reference_match = matched
            elif matched_fingerprint:
                reference_match = find_closest_image_reference(matched_fingerprint, settings["entries"])
            reference_entry = reference_match.entry or {}
            reference_url = str(reference_entry.get("url") or "").strip()
            reference_label = discord.utils.escape_markdown(str(reference_entry.get("label") or matched.entry["label"]))
            reference_similarity = image_match_similarity(reference_match)

            case_id = int(case_record.get("case_id", 0) or 0) if case_record else 0
            case_label = ""
            if case_record:
                from .cases import add_punishment_record_log_fields, get_case_label
                case_label = get_case_label(case_record)
            if case_label:
                title = f"[{case_label}] Banned Image Detected"
            elif review_required:
                title = "Image Match Needs Review"
            else:
                title = "Banned Image Detected"
            system_user = getattr(bot, "user", None)
            actor = format_user_ref(system_user) if getattr(system_user, "mention", None) else "Automated Image Detection"
            embed = make_action_log_embed(
                title,
                (
                    "A lower-confidence image match needs a moderator decision. No automatic punishment was applied."
                    if review_required
                    else "An automated image moderation action has been applied and logged."
                ),
                guild=message.guild,
                kind="danger" if case_record else "warning",
                scope=SCOPE_MODERATION,
                actor=actor,
                target=format_user_ref(message.author),
                reason=case_record.get("reason") if case_record else IMAGE_FILTER_REASON,
                thumbnail=reference_url if reference_url.startswith(("http://", "https://")) else None,
            )
            if case_record:
                add_punishment_record_log_fields(embed, case_record)
            detector = (
                "Local OCR + structural scam classifier"
                if matched.quality == "content"
                else "Perceptual image fingerprint"
            )
            detection_value = "\n".join([
                f"Method: {detector}",
                f"Confidence: {image_match_similarity(matched)}%",
                f"Quality: {matched.quality.title()}",
            ])
            embed.add_field(name="Detection", value=detection_value, inline=False)
            matched_terms = tuple(matched.entry.get("matched_terms", ()) or ())
            if matched_terms:
                term_value = ", ".join(
                    f"`{discord.utils.escape_markdown(str(term)).replace('`', '')}`"
                    for term in matched_terms
                )
                embed.add_field(name="Matched Words", value=truncate_text(term_value, 1000), inline=False)
            if reference_url.startswith(("http://", "https://")):
                reference_value = f"[{reference_label}]({reference_url}) • {reference_similarity}% visual similarity"
            else:
                reference_value = "No stored reference image • matched by the local content model"
            embed.add_field(name="Matched Reference", value=reference_value, inline=False)
            if flagged_url.startswith(("http://", "https://")):
                embed.add_field(name="Submitted Image", value=f"[Open image]({flagged_url})", inline=True)
            embed.add_field(name="Result", value=action_summary, inline=True)
            dm_status = "Not sent — awaiting review" if review_required else ("Delivered" if dm_sent else "Failed or closed")
            embed.add_field(name="DM", value=dm_status, inline=True)
            if trusted_match:
                embed.add_field(
                    name="24-Hour Cleanup",
                    value=f"{cleanup_deleted} earlier message{'s' if cleanup_deleted != 1 else ''} removed",
                    inline=True,
                )
            jump_url = str(getattr(message, "jump_url", "") or "")
            message_value = f"[{message.id}]({jump_url})" if jump_url.startswith(("http://", "https://")) else f"`{message.id}`"
            embed.add_field(name="Message ID", value=message_value, inline=True)
            if flagged_url.startswith(("http://", "https://")):
                embed.set_image(url=flagged_url)
            review_target = None
            if review_required:
                review_target = (message.author.id, message.channel.id, message.id)
            view = build_image_filter_log_view(
                case_id or None,
                matched_fingerprint,
                review_target=review_target,
            )
            review_ping = get_image_filter_review_ping(message.guild) if review_required else None
            content = f"{review_ping} Lower-confidence image match needs review." if review_ping else None
            await send_automod_log(message.guild, embed, content=content, view=view)
        except Exception as exc:
            logger.warning("Image filter could not send the detection log for message %s: %s", message.id, exc)
    return result


async def apply_native_automod_escalation(
    guild: discord.Guild,
    member: discord.Member,
    *,
    rule_id: int,
    rule_name: str,
    content: str,
    matched_keyword: Optional[str],
    warning_count: int,
    policy: dict,
    step: dict,
) -> Tuple[bool, str, Optional[dict]]:
    punishment_type = str(step.get("punishment_type", "warn") or "warn").lower()
    duration_minutes = int(step.get("duration_minutes", 0) or 0)
    threshold = int(step.get("threshold", 1) or 1)
    window_minutes = int(step.get("window_minutes", 1440) or 1440)
    reason_template = str(policy.get("reason_template", "Repeated native AutoMod violations") or "Repeated native AutoMod violations")
    reason = f"{reason_template} [{rule_name}]"
    if punishment_type == "ban":
        action_label = "Banned"
    elif punishment_type == "timeout":
        action_label = "Timed Out"
    elif punishment_type == "kick":
        action_label = "Kicked"
    else:
        action_label = "Warned"
    user_message_text = f"You have been **{action_label}** in **{guild.name}**."
    note = truncate_text(
        "\n".join([
            "Discord AutoMod escalation triggered.",
            f"Rule: {rule_name}",
            f"Hit Count: {warning_count} warning(s) in {format_minutes_interval(window_minutes)}",
            f"Triggered Step: {threshold} warning(s)",
            f"Matched Keyword: {matched_keyword or 'Unknown'}",
            f"Blocked Message: {content or '[Unavailable]'}",
        ]),
        1000,
    )
    timestamp_iso = now_iso()
    case_record = None

    if punishment_type == "timeout" and duration_minutes <= 0:
        duration_minutes = 60
    if punishment_type == "ban":
        duration_minutes = -1

    try:
        if punishment_type == "timeout":
            await member.timeout(get_valid_duration(duration_minutes), reason=f"{reason} (By {bot.user})")
        elif punishment_type == "ban":
            await guild.ban(member, reason=f"{reason} (By {bot.user})", delete_message_days=0)
        elif punishment_type == "kick":
            await guild.kick(member, reason=f"{reason} (By {bot.user})")
    except discord.Forbidden:
        return False, "The bot does not have permission to apply the configured escalation.", None
    except Exception as exc:
        return False, f"Failed to apply escalation: {exc}", None

    record = {
        "reason": reason,
        "moderator": bot.user.id,
        "duration_minutes": duration_minutes if punishment_type != "kick" else 0,
        "timestamp": timestamp_iso,
        "escalated": True,
        "note": note,
        "user_msg": user_message_text,
        "target_name": get_user_display_name(member),
        "type": punishment_type if punishment_type in {"warn", "timeout", "ban", "kick"} else "warn",
        "active": punishment_type in {"ban", "timeout"},
    }
    case_record = await bot.data_manager.add_punishment(str(member.id), record, persist=False)
    bot.data_manager.config.setdefault("stats", {})["total_issued"] = bot.data_manager.config.get("stats", {}).get("total_issued", 0) + 1
    bot.data_manager.mark_config_dirty()
    await bot.data_manager.save_all()

    try:
        dm_embed = make_embed(
            "Moderation Action Issued",
            f"> {user_message_text}",
            kind="danger",
            scope=SCOPE_MODERATION,
            guild=guild,
            thumbnail=guild.icon.url if guild.icon else None,
        )
        dm_embed.add_field(name="Reason", value=format_reason_value(reason, limit=1000), inline=False)
        if punishment_type == "timeout" and duration_minutes > 0:
            dm_embed.add_field(name="Duration", value=format_duration(duration_minutes), inline=True)
            expires = discord.utils.format_dt(discord.utils.utcnow() + get_valid_duration(duration_minutes), "R")
            dm_embed.add_field(name="Expires", value=expires, inline=True)
        elif punishment_type == "ban":
            dm_embed.add_field(name="Duration", value="Ban" if duration_minutes == -1 else format_duration(duration_minutes), inline=True)
            if duration_minutes > 0:
                expires = discord.utils.format_dt(discord.utils.utcnow() + get_valid_duration(duration_minutes), "R")
                dm_embed.add_field(name="Expires", value=expires, inline=True)
        await member.send(embed=dm_embed, view=build_appeal_view(guild.id, case_record["case_id"]))
    except Exception:
        pass

    status = punishment_type.title()
    if punishment_type == "warn":
        status = "Warning"
    elif punishment_type == "timeout":
        status = f"Timeout ({format_duration(duration_minutes)})"
    elif punishment_type == "ban":
        status = "Ban"

    return True, f"Applied {status} automatically at {warning_count} warnings in {format_minutes_interval(window_minutes)}.", case_record


def ensure_native_rule_override_policy(settings: dict, rule: discord.AutoModRule) -> Tuple[str, dict]:
    override_key, current_policy, _ = get_native_rule_override(settings, rule)
    policy = {
        "enabled": bool(current_policy.get("enabled", False)),
        "reason_template": str(current_policy.get("reason_template", DEFAULT_NATIVE_AUTOMOD_SETTINGS["default_escalation"]["reason_template"]) or DEFAULT_NATIVE_AUTOMOD_SETTINGS["default_escalation"]["reason_template"])[:200],
        "steps": get_native_automod_policy_steps(current_policy),
    }
    settings.setdefault("rule_overrides", {})[override_key] = policy
    return override_key, policy


class AutoModPolicyReasonModal(discord.ui.Modal, title="Edit AutoMod Reason Template"):
    reason_template = discord.ui.TextInput(
        label="Reason Template",
        style=discord.TextStyle.paragraph,
        max_length=200,
        placeholder="Repeated native AutoMod violations",
    )

    def __init__(self, *, rule: Optional[discord.AutoModRule] = None, rules: Optional[List[discord.AutoModRule]] = None):
        super().__init__()
        self.rule = rule
        self.rules = rules or []
        settings = get_native_automod_settings(bot.data_manager.config)
        if rule is None:
            policy = build_default_native_automod_policy()
        else:
            _, policy, _ = get_native_rule_override(settings, rule)
        self.reason_template.default = str(policy.get("reason_template", DEFAULT_NATIVE_AUTOMOD_SETTINGS["default_escalation"]["reason_template"]))

    async def on_submit(self, interaction: discord.Interaction) -> None:
        settings = get_native_automod_settings(bot.data_manager.config)
        if self.rule is None:
            await interaction.response.edit_message(embed=build_automod_dashboard_embed(interaction.guild), view=AutoModDashboardView())
            return
        _, policy = ensure_native_rule_override_policy(settings, self.rule)
        policy["reason_template"] = self.reason_template.value.strip()[:200] or DEFAULT_NATIVE_AUTOMOD_SETTINGS["default_escalation"]["reason_template"]
        store_native_automod_settings(settings)
        await bot.data_manager.save_config()

        view = AutoModPolicyEditorView(rule=self.rule, rules=self.rules)
        await interaction.response.send_message(embed=view.build_embed(interaction.guild), view=view, ephemeral=True)


class AutoModStepValuesModal(discord.ui.Modal, title="Edit AutoMod Step"):
    punishment_type = discord.ui.TextInput(
        label="Action",
        placeholder="warn, timeout, kick, or ban",
        max_length=10,
    )
    warning_count = discord.ui.TextInput(
        label="Warnings",
        placeholder="3",
        max_length=4,
    )
    warning_window = discord.ui.TextInput(
        label="Window",
        placeholder="6h, 2d, or 1w",
        max_length=12,
    )
    timeout_length = discord.ui.TextInput(
        label="Timeout Length",
        placeholder="1h or 12h",
        required=False,
        max_length=12,
    )

    def __init__(self, *, parent_view):
        super().__init__()
        self.parent_view = parent_view
        current_step = parent_view.get_current_step()
        self.punishment_type.default = str(current_step.get("punishment_type", "warn")).lower()
        self.warning_count.default = str(current_step.get("threshold", 1))
        self.warning_window.default = format_compact_minutes_input(int(current_step.get("window_minutes", 1440) or 1440))
        if str(current_step.get("punishment_type", "warn")).lower() == "timeout":
            self.timeout_length.default = format_compact_minutes_input(int(current_step.get("duration_minutes", 60) or 60))
        else:
            self.timeout_length.default = ""

    async def on_submit(self, interaction: discord.Interaction) -> None:
        policy = self.parent_view.get_current_policy()
        steps = self.parent_view.get_current_steps()
        if not steps:
            overview = AutoModPolicyEditorView(rule=self.parent_view.rule, rules=self.parent_view.rules)
            await interaction.response.send_message(embed=overview.build_embed(interaction.guild), view=overview, ephemeral=True)
            return

        current_step = dict(steps[self.parent_view.step_index])

        try:
            punishment_type = parse_automod_punishment_input(self.punishment_type.value, field_name="Action")
            current_step["punishment_type"] = punishment_type
            current_step["threshold"] = parse_positive_integer_input(self.warning_count.value, field_name="Warning count")
            current_step["window_minutes"] = parse_minutes_input(self.warning_window.value, field_name="Warning window", maximum=43200)
            if punishment_type == "timeout":
                timeout_raw = self.timeout_length.value.strip() or format_compact_minutes_input(int(current_step.get("duration_minutes", 60) or 60))
                current_step["duration_minutes"] = parse_minutes_input(timeout_raw, field_name="Timeout length", maximum=40320)
            elif punishment_type == "ban":
                current_step["duration_minutes"] = -1
            else:
                current_step["duration_minutes"] = 0
        except ValueError as exc:
            await respond_with_error(interaction, str(exc), scope=SCOPE_MODERATION)
            return

        steps[self.parent_view.step_index] = current_step
        policy["steps"] = steps
        await self.parent_view.persist_policy(policy)

        view = AutoModPolicyEditorView(rule=self.parent_view.rule, rules=self.parent_view.rules, step_index=self.parent_view.step_index)
        if getattr(interaction, "message", None) is not None:
            await interaction.response.edit_message(embed=view.build_embed(interaction.guild), view=view)
            return
        await interaction.response.send_message(embed=view.build_embed(interaction.guild), view=view, ephemeral=True)


class AutoModStepSelect(discord.ui.Select):
    def __init__(self, parent_view):
        self.parent_view = parent_view
        options = []
        for index, step in enumerate(self.parent_view.get_current_steps()):
            options.append(
                discord.SelectOption(
                    label=f"Step {index + 1}",
                    value=str(index),
                    description=truncate_text(format_native_automod_step_summary(step), 100),
                    default=index == getattr(self.parent_view, "step_index", 0),
                )
            )
        super().__init__(placeholder="Choose which step to edit...", min_values=1, max_values=1, options=options[:25], row=0)

    async def callback(self, interaction: discord.Interaction) -> None:
        step_index = int(self.values[0])
        view = AutoModPolicyEditorView(rule=self.parent_view.rule, rules=self.parent_view.rules, step_index=step_index)
        await interaction.response.edit_message(embed=view.build_embed(interaction.guild), view=view)


class AutoModRuleSelect(discord.ui.Select):
    def __init__(self, parent_view, rules: List[discord.AutoModRule]):
        self.parent_view = parent_view
        self.rules = rules[:25]
        options = []
        settings = get_native_automod_settings(bot.data_manager.config)
        for rule in self.rules:
            _, policy, using_override = get_native_rule_override(settings, rule)
            steps = get_native_automod_policy_steps(policy)
            summary_label = f"{len(steps)} step{'s' if len(steps) != 1 else ''}" if steps else "No steps"
            options.append(
                discord.SelectOption(
                    label=truncate_text(rule.name, 100),
                    value=str(rule.id),
                    description=truncate_text(
                        f"{'On' if policy.get('enabled') and steps else 'Off'} • {summary_label}",
                        100,
                    ),
                )
            )
        super().__init__(placeholder="Choose a native AutoMod rule...", min_values=1, max_values=1, options=options, row=0)

    async def callback(self, interaction: discord.Interaction) -> None:
        selected = next((rule for rule in self.rules if str(rule.id) == self.values[0]), None)
        if selected is None:
            await respond_with_error(interaction, "That AutoMod rule could not be found anymore.", scope=SCOPE_MODERATION)
            return
        view = AutoModPolicyEditorView(rule=selected, rules=self.parent_view.rules)
        await interaction.response.edit_message(embed=view.build_embed(interaction.guild), view=view)


class AutoModSectionSelect(discord.ui.Select):
    def __init__(self, current: str = "overview", *, row: int = 4):
        options = [
            discord.SelectOption(label="Overview", value="overview", default=current == "overview"),
            discord.SelectOption(label="Rule Punishments", value="rules", default=current == "rules"),
            discord.SelectOption(label="Response Settings", value="responses", default=current == "responses"),
            discord.SelectOption(label="Image Filters", value="images", default=current == "images"),
            discord.SelectOption(label="Immunity", value="immunity", default=current == "immunity"),
            discord.SelectOption(label="Log Channels", value="logs", default=current == "logs"),
        ]
        super().__init__(placeholder="Choose an AutoMod section...", min_values=1, max_values=1, options=options, row=row)

    async def callback(self, interaction: discord.Interaction) -> None:
        section = self.values[0]
        if section == "rules":
            await interaction.response.defer()
            rules = await fetch_native_automod_rules(interaction.guild)
            await interaction.edit_original_response(
                embed=build_automod_rule_browser_embed(interaction.guild, rules),
                view=AutoModRuleBrowserView(rules),
            )
            return

        destinations = {
            "overview": (build_automod_dashboard_embed, AutoModDashboardView),
            "responses": (build_automod_bridge_embed, AutoModBridgeSettingsView),
            "images": (build_image_filters_embed, ImageFiltersView),
            "immunity": (build_automod_immunity_embed, AutoModImmunityView),
            "logs": (build_automod_routing_embed, AutoModChannelSettingsView),
        }
        embed_builder, view_type = destinations[section]
        await interaction.response.edit_message(embed=embed_builder(interaction.guild), view=view_type())


class AutoModBridgeSettingsView(discord.ui.View):
    def __init__(self):
        super().__init__(timeout=180)
        self.sync_buttons()
        self.add_item(AutoModSectionSelect("responses"))

    def sync_buttons(self) -> None:
        settings = get_native_automod_settings(bot.data_manager.config)
        self.toggle_bridge.label = f"Bot Response: {'On' if settings.get('enabled', True) else 'Off'}"
        self.toggle_bridge.style = discord.ButtonStyle.success if settings.get("enabled", True) else discord.ButtonStyle.secondary
        self.toggle_dm.label = f"User DMs: {'On' if settings.get('warning_dm_enabled', True) else 'Off'}"
        self.toggle_dm.style = discord.ButtonStyle.success if settings.get("warning_dm_enabled", True) else discord.ButtonStyle.secondary
        self.toggle_report.label = f"Report Button: {'On' if settings.get('report_button_enabled', True) else 'Off'}"
        self.toggle_report.style = discord.ButtonStyle.success if settings.get("report_button_enabled", True) else discord.ButtonStyle.secondary

    async def _save_and_refresh(self, interaction: discord.Interaction, settings: dict) -> None:
        store_native_automod_settings(settings)
        await bot.data_manager.save_config()
        self.sync_buttons()
        await interaction.response.edit_message(embed=build_automod_bridge_embed(interaction.guild), view=self)

    @discord.ui.button(label="Bot Response", style=discord.ButtonStyle.secondary, row=0)
    async def toggle_bridge(self, interaction: discord.Interaction, button: discord.ui.Button) -> None:
        settings = get_native_automod_settings(bot.data_manager.config)
        settings["enabled"] = not settings.get("enabled", True)
        await self._save_and_refresh(interaction, settings)

    @discord.ui.button(label="User DMs", style=discord.ButtonStyle.secondary, row=0)
    async def toggle_dm(self, interaction: discord.Interaction, button: discord.ui.Button) -> None:
        settings = get_native_automod_settings(bot.data_manager.config)
        settings["warning_dm_enabled"] = not settings.get("warning_dm_enabled", True)
        await self._save_and_refresh(interaction, settings)

    @discord.ui.button(label="Report Button", style=discord.ButtonStyle.secondary, row=0)
    async def toggle_report(self, interaction: discord.Interaction, button: discord.ui.Button) -> None:
        settings = get_native_automod_settings(bot.data_manager.config)
        settings["report_button_enabled"] = not settings.get("report_button_enabled", True)
        await self._save_and_refresh(interaction, settings)

class AutoModRuleBrowserView(discord.ui.View):
    def __init__(self, rules: List[discord.AutoModRule]):
        super().__init__(timeout=180)
        self.rules = rules[:25]
        if self.rules:
            self.add_item(AutoModRuleSelect(self, self.rules))
        self.add_item(AutoModSectionSelect("rules"))


class AutoModPolicyEditorView(discord.ui.View):
    def __init__(self, *, rule: Optional[discord.AutoModRule] = None, rules: Optional[List[discord.AutoModRule]] = None, step_index: int = 0):
        super().__init__(timeout=180)
        self.rule = rule
        self.rules = rules or []
        self.step_index = step_index
        steps = self.get_current_steps() if self.rule is not None else []
        if steps:
            self.step_index = max(0, min(step_index, len(steps) - 1))
            self.add_item(AutoModStepSelect(self))
        self.sync_buttons()
        self.add_item(AutoModSectionSelect("rules"))

    def get_current_policy(self) -> dict:
        settings = get_native_automod_settings(bot.data_manager.config)
        if self.rule is None:
            return build_default_native_automod_policy()
        _, policy, _ = get_native_rule_override(settings, self.rule)
        return {
            "enabled": bool(policy.get("enabled", False)),
            "reason_template": str(policy.get("reason_template", DEFAULT_NATIVE_AUTOMOD_SETTINGS["default_escalation"]["reason_template"]) or DEFAULT_NATIVE_AUTOMOD_SETTINGS["default_escalation"]["reason_template"])[:200],
            "steps": get_native_automod_policy_steps(policy),
        }

    def get_current_steps(self) -> List[dict]:
        return get_native_automod_policy_steps(self.get_current_policy())

    def get_current_step(self) -> dict:
        steps = self.get_current_steps()
        if not steps:
            self.step_index = 0
            return build_default_native_automod_step()
        self.step_index = max(0, min(self.step_index, len(steps) - 1))
        return dict(steps[self.step_index])

    def build_embed(self, guild: discord.Guild) -> discord.Embed:
        if self.rule is None:
            return build_automod_policy_embed(
                guild,
                build_default_native_automod_policy(),
                title="AutoMod Rule Punishment",
                description="> Pick a Discord AutoMod rule first, then edit that rule's punishment settings.",
            )
        settings = get_native_automod_settings(bot.data_manager.config)
        _, policy, using_override = get_native_rule_override(settings, self.rule)
        return build_automod_policy_embed(
            guild,
            policy,
            title=f"Rule Punishment: {self.rule.name}",
            description="> Pick a step from the dropdown, then use the buttons below to edit that step or the rule.",
            rule=self.rule,
            using_override=using_override,
            selected_step_index=self.step_index if self.get_current_steps() else None,
        )

    def sync_buttons(self) -> None:
        settings = get_native_automod_settings(bot.data_manager.config)
        enabled = False
        using_override = False
        steps = self.get_current_steps() if self.rule is not None else []
        if self.rule is not None:
            _, policy, using_override = get_native_rule_override(settings, self.rule)
            enabled = bool(policy.get("enabled", False) and steps)
        self.toggle_enabled.label = f"Auto Punish: {'On' if enabled else 'Off'}"
        self.toggle_enabled.style = discord.ButtonStyle.success if enabled else discord.ButtonStyle.secondary
        self.toggle_enabled.disabled = not bool(steps)
        self.add_step.disabled = self.rule is None or len(steps) >= 5
        self.custom_amounts.disabled = not bool(steps)
        self.remove_step.disabled = not bool(steps)
        self.remove_step.style = discord.ButtonStyle.secondary if self.remove_step.disabled else discord.ButtonStyle.danger
        self.clear_override.disabled = self.rule is None or not using_override
        self.clear_override.style = discord.ButtonStyle.secondary if self.clear_override.disabled else discord.ButtonStyle.danger

    async def persist_policy(self, policy: dict) -> None:
        settings = get_native_automod_settings(bot.data_manager.config)
        if self.rule is None:
            return
        override_key, _ = ensure_native_rule_override_policy(settings, self.rule)
        policy["steps"] = get_native_automod_policy_steps(policy)
        if not policy["steps"]:
            policy["enabled"] = False
            self.step_index = 0
        else:
            self.step_index = max(0, min(self.step_index, len(policy["steps"]) - 1))
        settings.setdefault("rule_overrides", {})[override_key] = policy
        store_native_automod_settings(settings)
        await bot.data_manager.save_config()

    async def save_policy(self, interaction: discord.Interaction, policy: dict) -> None:
        if self.rule is None:
            await interaction.response.edit_message(embed=build_automod_dashboard_embed(interaction.guild), view=AutoModDashboardView())
            return
        await self.persist_policy(policy)

    @discord.ui.button(label="Auto Punish", style=discord.ButtonStyle.secondary, row=1)
    async def toggle_enabled(self, interaction: discord.Interaction, button: discord.ui.Button) -> None:
        settings = get_native_automod_settings(bot.data_manager.config)
        if self.rule is None:
            await interaction.response.edit_message(embed=build_automod_dashboard_embed(interaction.guild), view=AutoModDashboardView())
            return
        _, policy = ensure_native_rule_override_policy(settings, self.rule)
        if not policy.get("steps"):
            view = AutoModPolicyEditorView(rule=self.rule, rules=self.rules, step_index=self.step_index)
            await interaction.response.edit_message(embed=view.build_embed(interaction.guild), view=view)
            return
        policy["enabled"] = not bool(policy.get("enabled", False))
        await self.save_policy(interaction, policy)
        view = AutoModPolicyEditorView(rule=self.rule, rules=self.rules, step_index=self.step_index)
        await interaction.response.edit_message(embed=view.build_embed(interaction.guild), view=view)

    @discord.ui.button(label="Add Step", style=discord.ButtonStyle.primary, row=1)
    async def add_step(self, interaction: discord.Interaction, button: discord.ui.Button) -> None:
        settings = get_native_automod_settings(bot.data_manager.config)
        if self.rule is None:
            await interaction.response.edit_message(embed=build_automod_dashboard_embed(interaction.guild), view=AutoModDashboardView())
            return
        _, policy = ensure_native_rule_override_policy(settings, self.rule)
        steps = get_native_automod_policy_steps(policy)
        if len(steps) >= 5:
            await interaction.response.edit_message(embed=self.build_embed(interaction.guild), view=self)
            return
        steps.append(build_default_native_automod_step(steps))
        policy["steps"] = steps
        policy["enabled"] = True
        await self.save_policy(interaction, policy)
        view = AutoModPolicyEditorView(rule=self.rule, rules=self.rules, step_index=len(steps) - 1)
        await interaction.response.edit_message(embed=view.build_embed(interaction.guild), view=view)

    @discord.ui.button(label="Edit Step", style=discord.ButtonStyle.primary, row=1)
    async def custom_amounts(self, interaction: discord.Interaction, button: discord.ui.Button) -> None:
        await interaction.response.send_modal(AutoModStepValuesModal(parent_view=self))

    @discord.ui.button(label="Edit Reason", style=discord.ButtonStyle.secondary, row=2)
    async def edit_reason(self, interaction: discord.Interaction, button: discord.ui.Button) -> None:
        await interaction.response.send_modal(AutoModPolicyReasonModal(rule=self.rule, rules=self.rules))

    @discord.ui.button(label="Remove Step", style=discord.ButtonStyle.danger, row=2)
    async def remove_step(self, interaction: discord.Interaction, button: discord.ui.Button) -> None:
        policy = self.get_current_policy()
        steps = self.get_current_steps()
        if not steps:
            view = AutoModPolicyEditorView(rule=self.rule, rules=self.rules, step_index=self.step_index)
            await interaction.response.edit_message(embed=view.build_embed(interaction.guild), view=view)
            return
        steps.pop(self.step_index)
        policy["steps"] = steps
        if not steps:
            policy["enabled"] = False
        await self.save_policy(interaction, policy)
        next_index = min(self.step_index, max(0, len(steps) - 1))
        view = AutoModPolicyEditorView(rule=self.rule, rules=self.rules, step_index=next_index)
        await interaction.response.edit_message(embed=view.build_embed(interaction.guild), view=view)

    @discord.ui.button(label="Reset Rule", style=discord.ButtonStyle.danger, row=2)
    async def clear_override(self, interaction: discord.Interaction, button: discord.ui.Button) -> None:
        if self.rule is None:
            await interaction.response.defer()
            return
        settings = get_native_automod_settings(bot.data_manager.config)
        override_key, _, using_override = get_native_rule_override(settings, self.rule)
        if using_override:
            settings.setdefault("rule_overrides", {}).pop(override_key, None)
            settings.setdefault("rule_overrides", {}).pop(self.rule.name, None)
            settings.setdefault("rule_overrides", {}).pop(str(self.rule.id), None)
            store_native_automod_settings(settings)
            await bot.data_manager.save_config()
        view = AutoModPolicyEditorView(rule=self.rule, rules=self.rules, step_index=0)
        await interaction.response.edit_message(embed=view.build_embed(interaction.guild), view=view)

class AutoModChannelSelect(discord.ui.ChannelSelect):
    def __init__(self, config_key: str, label: str):
        super().__init__(
            placeholder=f"Select {label}...",
            min_values=1,
            max_values=1,
            channel_types=[discord.ChannelType.text],
        )
        self.config_key = config_key
        self.label = label

    async def callback(self, interaction: discord.Interaction) -> None:
        selected = self.values[0]
        channel = interaction.guild.get_channel(selected.id) or await interaction.guild.fetch_channel(selected.id)
        bot.data_manager.config[self.config_key] = channel.id
        await bot.data_manager.save_config()
        view = AutoModChannelSettingsView()
        await interaction.response.edit_message(embed=build_automod_routing_embed(interaction.guild), view=view)


class AutoModChannelSettingsView(discord.ui.View):
    def __init__(self):
        super().__init__(timeout=180)
        self.add_item(AutoModChannelSelect("automod_log_channel_id", "AutoMod Log Channel"))
        self.add_item(AutoModChannelSelect("automod_report_channel_id", "AutoMod Report Channel"))
        self.add_item(AutoModChannelActionSelect())
        self.add_item(AutoModSectionSelect("logs"))


class AutoModChannelActionSelect(discord.ui.Select):
    def __init__(self):
        options = [
            discord.SelectOption(label="Clear Log Channel", value="clear_log", description="Clear the dedicated AutoMod log channel."),
            discord.SelectOption(label="Clear Report Channel", value="clear_report", description="Clear the dedicated AutoMod report channel."),
        ]
        super().__init__(
            placeholder="More log channel actions...",
            min_values=1,
            max_values=1,
            options=options,
            row=2,
        )

    async def callback(self, interaction: discord.Interaction) -> None:
        action = self.values[0]
        if action == "clear_log":
            bot.data_manager.config["automod_log_channel_id"] = 0
            await bot.data_manager.save_config()
            await interaction.response.edit_message(embed=build_automod_routing_embed(interaction.guild), view=AutoModChannelSettingsView())
            return
        if action == "clear_report":
            bot.data_manager.config["automod_report_channel_id"] = 0
            await bot.data_manager.save_config()
            await interaction.response.edit_message(embed=build_automod_routing_embed(interaction.guild), view=AutoModChannelSettingsView())


class AutoModStoredValueRemoveSelect(discord.ui.Select):
    def __init__(self, *, label: str, config_key: str, options: List[discord.SelectOption]):
        self.config_key = config_key
        super().__init__(
            placeholder=f"Remove {label}...",
            min_values=1,
            max_values=min(len(options), 10),
            options=options[:25],
        )

    async def callback(self, interaction: discord.Interaction) -> None:
        selected_ids = {int(value) for value in self.values}
        settings = get_native_automod_settings(bot.data_manager.config)
        settings[self.config_key] = [value for value in settings.get(self.config_key, []) if int(value) not in selected_ids]
        store_native_automod_settings(settings)
        await bot.data_manager.save_config()
        await interaction.response.edit_message(embed=make_embed("Entries Removed", "> The selected entries have been removed.", kind="success", scope=SCOPE_MODERATION, guild=interaction.guild), view=None)


class AutoModStoredValueRemoveView(discord.ui.View):
    def __init__(self, *, label: str, config_key: str, options: List[discord.SelectOption]):
        super().__init__(timeout=180)
        self.add_item(AutoModStoredValueRemoveSelect(label=label, config_key=config_key, options=options))


class AutoModImmunityUserSelect(discord.ui.UserSelect):
    def __init__(self):
        super().__init__(placeholder="Add immune users...", min_values=1, max_values=10, row=0)

    async def callback(self, interaction: discord.Interaction) -> None:
        settings = get_native_automod_settings(bot.data_manager.config)
        current = {int(value) for value in settings.get("immunity_users", [])}
        current.update(int(user.id) for user in self.values)
        settings["immunity_users"] = sorted(current)
        store_native_automod_settings(settings)
        await bot.data_manager.save_config()
        await interaction.response.edit_message(embed=build_automod_immunity_embed(interaction.guild), view=AutoModImmunityView())


class AutoModImmunityRoleSelect(discord.ui.RoleSelect):
    def __init__(self):
        super().__init__(placeholder="Add immune roles...", min_values=1, max_values=10, row=1)

    async def callback(self, interaction: discord.Interaction) -> None:
        settings = get_native_automod_settings(bot.data_manager.config)
        current = {int(value) for value in settings.get("immunity_roles", [])}
        current.update(int(role.id) for role in self.values)
        settings["immunity_roles"] = sorted(current)
        store_native_automod_settings(settings)
        await bot.data_manager.save_config()
        await interaction.response.edit_message(embed=build_automod_immunity_embed(interaction.guild), view=AutoModImmunityView())


class AutoModImmunityChannelSelect(discord.ui.ChannelSelect):
    def __init__(self):
        super().__init__(placeholder="Add immune channels...", min_values=1, max_values=10, channel_types=[discord.ChannelType.text], row=2)

    async def callback(self, interaction: discord.Interaction) -> None:
        settings = get_native_automod_settings(bot.data_manager.config)
        current = {int(value) for value in settings.get("immunity_channels", [])}
        current.update(int(channel.id) for channel in self.values)
        settings["immunity_channels"] = sorted(current)
        store_native_automod_settings(settings)
        await bot.data_manager.save_config()
        await interaction.response.edit_message(embed=build_automod_immunity_embed(interaction.guild), view=AutoModImmunityView())


class AutoModImmunityView(discord.ui.View):
    def __init__(self):
        super().__init__(timeout=180)
        self.add_item(AutoModImmunityUserSelect())
        self.add_item(AutoModImmunityRoleSelect())
        self.add_item(AutoModImmunityChannelSelect())
        self.add_item(AutoModSectionSelect("immunity"))

    async def _send_remove_picker(self, interaction: discord.Interaction, *, label: str, config_key: str) -> None:
        settings = get_native_automod_settings(bot.data_manager.config)
        values = settings.get(config_key, [])
        if not values:
            await interaction.response.send_message(embed=make_embed("Nothing Configured", f"> No {label.lower()} are configured.", kind="info", scope=SCOPE_MODERATION, guild=interaction.guild), ephemeral=True)
            return
        options = []
        for value in values[:25]:
            if config_key == "immunity_users":
                member = interaction.guild.get_member(int(value))
                option_label = member.display_name if member else f"User {value}"
            elif config_key == "immunity_roles":
                role = interaction.guild.get_role(int(value))
                option_label = role.name if role else f"Role {value}"
            else:
                channel = interaction.guild.get_channel(int(value)) or interaction.guild.get_channel_or_thread(int(value))
                option_label = f"#{channel.name}" if channel else f"Channel {value}"
            options.append(discord.SelectOption(label=truncate_text(option_label, 100), value=str(value)))
        await interaction.response.send_message(
            f"Choose which {label.lower()} to remove:",
            view=AutoModStoredValueRemoveView(label=label, config_key=config_key, options=options),
            ephemeral=True,
        )

    @discord.ui.button(label="Remove Users", style=discord.ButtonStyle.secondary, row=3)
    async def remove_users(self, interaction: discord.Interaction, button: discord.ui.Button) -> None:
        await self._send_remove_picker(interaction, label="Users", config_key="immunity_users")

    @discord.ui.button(label="Remove Roles", style=discord.ButtonStyle.secondary, row=3)
    async def remove_roles(self, interaction: discord.Interaction, button: discord.ui.Button) -> None:
        await self._send_remove_picker(interaction, label="Roles", config_key="immunity_roles")

    @discord.ui.button(label="Remove Channels", style=discord.ButtonStyle.secondary, row=3)
    async def remove_channels(self, interaction: discord.Interaction, button: discord.ui.Button) -> None:
        await self._send_remove_picker(interaction, label="Channels", config_key="immunity_channels")

def build_image_filters_embed(guild: discord.Guild) -> discord.Embed:
    settings = get_image_filter_settings()
    if settings["punish"]:
        punishment_label = settings["punishment_type"].title()
        if settings["punishment_type"] == "timeout":
            punishment_label = f"Timeout ({format_duration(settings['duration_minutes'])})"
    else:
        punishment_label = "Off"
    embed = make_embed(
        "Image Filters",
        "> Detects banned images and high-confidence MrBeast crypto scams locally. Trusted detections also remove the sender's messages from the preceding 24 hours. No external recognition API is used.",
        kind="warning",
        scope=SCOPE_MODERATION,
        guild=guild,
    )
    filter_status = "Enabled"
    if settings["enabled"] and settings["scan_scam_content"]:
        filter_status = "Enabled • Scam scan on"
    elif not settings["enabled"]:
        filter_status = "Disabled"
    embed.add_field(name="Filters", value=filter_status, inline=True)
    embed.add_field(name="Delete Message", value="On" if settings["delete_message"] else "Off", inline=True)
    embed.add_field(name="Log Detections", value="On" if settings["log_detections"] else "Off", inline=True)
    embed.add_field(name="Auto Punish", value=punishment_label, inline=True)
    embed.add_field(name="Banned Images", value=f"{len(settings['entries'])}/{IMAGE_FILTER_MAX_ENTRIES}", inline=True)
    embed.add_field(name="Learned Exceptions", value=str(len(settings["false_positives"])), inline=True)
    if settings["entries"]:
        preview = [f"- {entry['label']}" for entry in settings["entries"][:10]]
        if len(settings["entries"]) > 10:
            preview.append(f"- …and {len(settings['entries']) - 10} more")
        embed.add_field(name="Entries", value="\n".join(preview), inline=False)
    return embed


class ImageFilterPunishmentSelect(discord.ui.Select):
    def __init__(self, *, page: int = 0):
        self.page = page
        settings = get_image_filter_settings()
        options = [
            discord.SelectOption(label=label, value=value, default=settings["punish"] and value == settings["punishment_type"])
            for value, label in AUTOMOD_PUNISHMENT_OPTIONS
        ]
        super().__init__(placeholder="Auto-punishment type (when Auto Punish is on)...", min_values=1, max_values=1, options=options, row=0)

    async def callback(self, interaction: discord.Interaction) -> None:
        settings = get_image_filter_settings()
        settings["punishment_type"] = self.values[0]
        store_image_filter_settings(settings)
        await bot.data_manager.save_config()
        await interaction.response.edit_message(embed=build_image_filters_embed(interaction.guild), view=ImageFiltersView(page=self.page))


class ImageFilterRemoveSelect(discord.ui.Select):
    def __init__(self, *, page: int = 0):
        self.page = page
        settings = get_image_filter_settings()
        start = page * 25
        page_entries = settings["entries"][start:start + 25]
        options = [
            discord.SelectOption(label=entry["label"], value=entry["id"])
            for entry in page_entries
        ]
        if not options:
            options = [discord.SelectOption(label="No banned images", value="none")]
        end = min(start + 25, len(settings["entries"]))
        placeholder = f"Remove a banned image ({start + 1}-{end} of {len(settings['entries'])})..." if page_entries else "Remove a banned image..."
        super().__init__(placeholder=placeholder, min_values=1, max_values=1, options=options, row=1, disabled=not page_entries)

    async def callback(self, interaction: discord.Interaction) -> None:
        if self.values[0] == "none":
            await interaction.response.defer()
            return
        settings = get_image_filter_settings()
        settings["entries"] = [entry for entry in settings["entries"] if entry["id"] != self.values[0]]
        store_image_filter_settings(settings)
        await bot.data_manager.save_config()
        max_page = max(0, (len(settings["entries"]) - 1) // 25)
        await interaction.response.edit_message(
            embed=build_image_filters_embed(interaction.guild),
            view=ImageFiltersView(page=min(self.page, max_page)),
        )


class ImageFilterDurationModal(discord.ui.Modal, title="Timeout Duration"):
    duration_input = discord.ui.TextInput(label="Timeout minutes", placeholder="60", max_length=6)

    def __init__(self, *, page: int = 0):
        super().__init__()
        self.page = page
        self.duration_input.default = str(get_image_filter_settings()["duration_minutes"])

    async def on_submit(self, interaction: discord.Interaction) -> None:
        digits = "".join(ch for ch in self.duration_input.value if ch.isdigit())
        if not digits:
            await respond_with_error(interaction, "The duration must be a number of minutes.", scope=SCOPE_MODERATION)
            return
        settings = get_image_filter_settings()
        settings["duration_minutes"] = max(1, min(40320, int(digits)))
        store_image_filter_settings(settings)
        await bot.data_manager.save_config()
        await interaction.response.edit_message(embed=build_image_filters_embed(interaction.guild), view=ImageFiltersView(page=self.page))


class ImageFiltersView(discord.ui.View):
    def __init__(self, *, page: int = 0):
        super().__init__(timeout=180)
        settings = get_image_filter_settings()
        max_page = max(0, (len(settings["entries"]) - 1) // 25)
        self.page = max(0, min(page, max_page))
        self.add_item(ImageFilterPunishmentSelect(page=self.page))
        self.add_item(ImageFilterRemoveSelect(page=self.page))
        self.toggle_enabled.label = f"Filters: {'On' if settings['enabled'] else 'Off'}"
        self.toggle_enabled.style = discord.ButtonStyle.success if settings["enabled"] else discord.ButtonStyle.secondary
        self.toggle_delete.label = f"Delete: {'On' if settings['delete_message'] else 'Off'}"
        self.toggle_log.label = f"Log: {'On' if settings['log_detections'] else 'Off'}"
        self.toggle_punish.label = f"Auto Punish: {'On' if settings['punish'] else 'Off'}"
        self.toggle_punish.style = discord.ButtonStyle.danger if settings["punish"] else discord.ButtonStyle.secondary
        self.previous_page.disabled = self.page == 0
        self.next_page.disabled = self.page >= max_page
        if max_page == 0:
            self.remove_item(self.previous_page)
            self.remove_item(self.next_page)
        if settings["punishment_type"] != "timeout":
            self.remove_item(self.set_duration)
        self.add_item(AutoModSectionSelect("images"))

    async def _toggle(self, interaction: discord.Interaction, key: str) -> None:
        settings = get_image_filter_settings()
        settings[key] = not settings[key]
        store_image_filter_settings(settings)
        await bot.data_manager.save_config()
        await interaction.response.edit_message(embed=build_image_filters_embed(interaction.guild), view=ImageFiltersView(page=self.page))

    @discord.ui.button(label="Filters", style=discord.ButtonStyle.secondary, row=2)
    async def toggle_enabled(self, interaction: discord.Interaction, button: discord.ui.Button) -> None:
        await self._toggle(interaction, "enabled")

    @discord.ui.button(label="Delete", style=discord.ButtonStyle.secondary, row=2)
    async def toggle_delete(self, interaction: discord.Interaction, button: discord.ui.Button) -> None:
        await self._toggle(interaction, "delete_message")

    @discord.ui.button(label="Log", style=discord.ButtonStyle.secondary, row=2)
    async def toggle_log(self, interaction: discord.Interaction, button: discord.ui.Button) -> None:
        await self._toggle(interaction, "log_detections")

    @discord.ui.button(label="Auto Punish", style=discord.ButtonStyle.secondary, row=2)
    async def toggle_punish(self, interaction: discord.Interaction, button: discord.ui.Button) -> None:
        await self._toggle(interaction, "punish")

    @discord.ui.button(label="Timeout Duration", style=discord.ButtonStyle.primary, row=3)
    async def set_duration(self, interaction: discord.Interaction, button: discord.ui.Button) -> None:
        await interaction.response.send_modal(ImageFilterDurationModal(page=self.page))

    @discord.ui.button(label="Previous", style=discord.ButtonStyle.secondary, row=3)
    async def previous_page(self, interaction: discord.Interaction, button: discord.ui.Button) -> None:
        await interaction.response.edit_message(
            embed=build_image_filters_embed(interaction.guild),
            view=ImageFiltersView(page=self.page - 1),
        )

    @discord.ui.button(label="Next", style=discord.ButtonStyle.secondary, row=3)
    async def next_page(self, interaction: discord.Interaction, button: discord.ui.Button) -> None:
        await interaction.response.edit_message(
            embed=build_image_filters_embed(interaction.guild),
            view=ImageFiltersView(page=self.page + 1),
        )

class AutoModDashboardView(discord.ui.View):
    def __init__(self):
        super().__init__(timeout=180)
        self.add_item(AutoModSectionSelect("overview", row=0))


async def resolve_user_for_automod_report(guild: Optional[discord.Guild], user_id: int) -> Optional[Union[discord.Member, discord.User]]:
    if guild is not None:
        member = guild.get_member(user_id)
        if member is not None:
            return member
    cached = bot.get_user(user_id)
    if cached is not None:
        return cached
    try:
        return await bot.fetch_user(user_id)
    except Exception:
        return None


async def apply_automod_report_response(
    interaction: discord.Interaction,
    *,
    guild_id: int,
    reporter_id: int,
    warning_id: str,
    rule_name: str,
    response_key: str,
    response_text: str,
    source_message: Optional[discord.Message],
) -> bool:
    if not is_staff(interaction):
        await respond_with_error(interaction, "Access denied.", scope=SCOPE_MODERATION)
        return False

    guild = bot.get_guild(guild_id) or interaction.guild or get_primary_guild()
    if guild is None:
        await respond_with_error(interaction, "The server for this AutoMod report could not be resolved.", scope=SCOPE_MODERATION)
        return False

    if source_message is not None and source_message.embeds:
        for field in source_message.embeds[0].fields:
            if str(field.name).strip().lower() == "report status":
                await respond_with_error(interaction, "This AutoMod report already has a staff response.", scope=SCOPE_MODERATION)
                return False

    target_user = await resolve_user_for_automod_report(guild, reporter_id)
    if target_user is None:
        await respond_with_error(interaction, "The user for this AutoMod report could not be found.", scope=SCOPE_MODERATION)
        return False

    preset = get_automod_report_preset(response_key)
    dm_embed = make_embed(
        "AutoMod Report Update",
        f"> {response_text}",
        kind=preset.get("kind", "info"),
        scope=SCOPE_MODERATION,
        guild=guild,
        thumbnail=guild.icon.url if guild and guild.icon else None,
    )
    dm_embed.add_field(name="Regarding", value=f"Your reported AutoMod warning for **{truncate_text(rule_name, 200)}**", inline=False)

    try:
        await target_user.send(embed=dm_embed)
    except discord.Forbidden:
        await respond_with_error(interaction, "The user has DMs closed, so the response could not be delivered.", scope=SCOPE_MODERATION)
        return False
    except Exception as exc:
        await respond_with_error(interaction, f"Failed to send the AutoMod report response: {exc}", scope=SCOPE_MODERATION)
        return False

    report_message = source_message
    if report_message is None:
        report_channel_id = (
            bot.data_manager.config.get("automod_report_channel_id")
            or bot.data_manager.config.get("appeal_channel_id")
            or get_punishment_log_channel_id()
        )
        report_channel = guild.get_channel_or_thread(int(report_channel_id)) if report_channel_id else None
        if report_channel is not None and interaction.message is not None:
            report_message = interaction.message

    if report_message is not None and report_message.embeds:
        updated_embed = discord.Embed.from_dict(report_message.embeds[0].to_dict())
        updated_embed.color = EMBED_PALETTE.get(preset.get("kind", "info"), EMBED_PALETTE["info"])
        upsert_embed_field(updated_embed, "Report Status", preset.get("status", "Staff Replied"), inline=True)
        upsert_embed_field(updated_embed, "Responded", discord.utils.format_dt(discord.utils.utcnow(), "F"), inline=True)
        upsert_embed_field(updated_embed, "Staff Response", format_log_quote(response_text, limit=800), inline=False)
        brand_embed(updated_embed, guild=guild, scope=SCOPE_MODERATION)
        try:
            await report_message.edit(embed=updated_embed, view=None)
        except Exception:
            pass
    return True


class AutoModCustomReportResponseModal(discord.ui.Modal, title="Custom AutoMod Report Response"):
    response_text = discord.ui.TextInput(
        label="Response",
        style=discord.TextStyle.paragraph,
        max_length=1000,
        placeholder="Write the response that should be sent to the user.",
    )

    def __init__(self, *, guild_id: int, reporter_id: int, warning_id: str, rule_name: str, source_message: Optional[discord.Message]):
        super().__init__()
        self.guild_id = guild_id
        self.reporter_id = reporter_id
        self.warning_id = warning_id
        self.rule_name = rule_name
        self.source_message = source_message

    async def on_submit(self, interaction: discord.Interaction) -> None:
        success = await apply_automod_report_response(
            interaction,
            guild_id=self.guild_id,
            reporter_id=self.reporter_id,
            warning_id=self.warning_id,
            rule_name=self.rule_name,
            response_key="custom",
            response_text=self.response_text.value.strip()[:1000],
            source_message=self.source_message,
        )
        if success and not interaction.response.is_done():
            await interaction.response.send_message(embed=make_confirmation_embed("Response Sent", "> The response was sent to the user.", scope=SCOPE_MODERATION, guild=interaction.guild), ephemeral=True)
        elif success:
            await interaction.followup.send(embed=make_confirmation_embed("Response Sent", "> The response was sent to the user.", scope=SCOPE_MODERATION, guild=interaction.guild), ephemeral=True)


class AutoModReportResponseSelect(discord.ui.Select):
    def __init__(self, parent_view):
        self.parent_view = parent_view
        options = [
            discord.SelectOption(
                label=preset["label"],
                value=key,
                description=truncate_text(preset["description"], 100),
            )
            for key, preset in AUTOMOD_REPORT_RESPONSE_PRESETS.items()
        ]
        super().__init__(
            placeholder="Respond to this report...",
            min_values=1,
            max_values=1,
            options=options,
        )

    async def callback(self, interaction: discord.Interaction) -> None:
        selected = self.values[0]
        if selected == "custom":
            await interaction.response.send_modal(
                AutoModCustomReportResponseModal(
                    guild_id=self.parent_view.guild_id,
                    reporter_id=self.parent_view.reporter_id,
                    warning_id=self.parent_view.warning_id,
                    rule_name=self.parent_view.rule_name,
                    source_message=interaction.message,
                )
            )
            return

        preset = get_automod_report_preset(selected)
        await interaction.response.defer(ephemeral=True)
        success = await apply_automod_report_response(
            interaction,
            guild_id=self.parent_view.guild_id,
            reporter_id=self.parent_view.reporter_id,
            warning_id=self.parent_view.warning_id,
            rule_name=self.parent_view.rule_name,
            response_key=selected,
            response_text=preset["message"],
            source_message=interaction.message,
        )
        if success:
            await interaction.followup.send(
                embed=make_confirmation_embed(
                    "Report Response Sent",
                    f"> {preset['label']} was sent to the user.",
                    scope=SCOPE_MODERATION,
                    guild=interaction.guild,
                ),
                ephemeral=True,
            )


class AutoModReportResponseView(discord.ui.View):
    def __init__(self, *, guild_id: int, reporter_id: int, warning_id: str, rule_name: str):
        super().__init__(timeout=604800)
        self.guild_id = guild_id
        self.reporter_id = reporter_id
        self.warning_id = warning_id
        self.rule_name = rule_name
        self.add_item(AutoModReportResponseSelect(self))


class AutoModReportModal(discord.ui.Modal, title="Report AutoMod Warning"):
    why_incorrect = discord.ui.TextInput(
        label="What was wrong?",
        style=discord.TextStyle.paragraph,
        max_length=600,
        placeholder="Explain why you think the filter was wrong.",
    )
    extra_context = discord.ui.TextInput(
        label="Anything else staff should know?",
        style=discord.TextStyle.paragraph,
        required=False,
        max_length=600,
        placeholder="Context, screenshots, or what you were trying to say.",
    )

    def __init__(self, *, guild_id: int, warning_id: str, rule_id: int, rule_name: str, content: str, matched_keyword: Optional[str], source_message: Optional[discord.Message] = None):
        super().__init__()
        self.guild_id = guild_id
        self.warning_id = warning_id
        self.rule_id = rule_id
        self.rule_name = rule_name
        self.content = content
        self.matched_keyword = matched_keyword
        self.source_message = source_message

    async def on_submit(self, interaction: discord.Interaction) -> None:
        guild = bot.get_guild(self.guild_id) or get_primary_guild()
        if guild is None:
            await interaction.response.send_message(embed=make_embed("Server Not Found", "> The server for this report could not be resolved.", kind="error", scope=SCOPE_MODERATION, guild=interaction.guild), ephemeral=True)
            return

        channel_id = (
            bot.data_manager.config.get("automod_report_channel_id")
            or bot.data_manager.config.get("appeal_channel_id")
            or get_punishment_log_channel_id()
        )
        channel = guild.get_channel(int(channel_id)) if channel_id else None
        if channel is None:
            await interaction.response.send_message(embed=make_embed("Not Configured", "> No AutoMod report channel is configured yet. Please contact staff directly.", kind="error", scope=SCOPE_MODERATION, guild=interaction.guild), ephemeral=True)
            return

        embed = make_action_log_embed(
            "AutoMod Report Submitted",
            "A user reported that a native AutoMod warning may have been incorrect.",
            guild=guild,
            kind="warning",
            scope=SCOPE_MODERATION,
            actor=format_user_ref(interaction.user),
            target=self.rule_name,
            reason="User reported a possible false positive.",
            message=self.content or '[Unavailable]',
            notes=[
                f"Rule ID: {self.rule_id}",
                f"Matched Keyword: {self.matched_keyword or 'Unknown'}",
                f"User Report: {truncate_text(self.why_incorrect.value, 500)}",
                f"Extra Context: {truncate_text(self.extra_context.value, 500) if self.extra_context.value else 'None'}",
            ],
            thumbnail=interaction.user.display_avatar.url,
            author_name=f"{interaction.user.display_name} ({interaction.user.id})",
            author_icon=interaction.user.display_avatar.url,
        )
        await channel.send(
            embed=normalize_log_embed(embed, guild=guild),
            view=AutoModReportResponseView(
                guild_id=guild.id,
                reporter_id=interaction.user.id,
                warning_id=self.warning_id,
                rule_name=self.rule_name,
            ),
        )

        if self.source_message is not None:
            try:
                if self.source_message.embeds:
                    updated_embed = discord.Embed.from_dict(self.source_message.embeds[0].to_dict())
                else:
                    updated_embed = discord.Embed()
                updated_embed.color = EMBED_PALETTE.get("success", EMBED_PALETTE["info"])
                upsert_embed_field(updated_embed, "Report Status", "Reported to staff — under review", inline=False)
                reported_view = discord.ui.View(timeout=None)
                reported_view.add_item(discord.ui.Button(label="Reported", style=discord.ButtonStyle.success, disabled=True))
                await self.source_message.edit(embed=updated_embed, view=reported_view)
            except Exception:
                pass

        await interaction.response.send_message(
            embed=make_confirmation_embed(
                "Report Sent",
                "> Your AutoMod report was sent to the staff team for review.",
                scope=SCOPE_MODERATION,
                guild=guild,
            ),
            ephemeral=True,
        )


class AutoModWarningView(discord.ui.View):
    def __init__(self, *, guild_id: int, warning_id: str, rule_id: int, rule_name: str, content: str, matched_keyword: Optional[str]):
        super().__init__(timeout=86400)
        self.guild_id = guild_id
        self.warning_id = warning_id
        self.rule_id = rule_id
        self.rule_name = rule_name
        self.content = truncate_text(content or "", 1000)
        self.matched_keyword = matched_keyword

    @discord.ui.button(label="Report to Moderator", style=discord.ButtonStyle.secondary)
    async def report(self, interaction: discord.Interaction, button: discord.ui.Button) -> None:
        await interaction.response.send_modal(
            AutoModReportModal(
                guild_id=self.guild_id,
                warning_id=self.warning_id,
                rule_id=self.rule_id,
                rule_name=self.rule_name,
                content=self.content,
                matched_keyword=self.matched_keyword,
                source_message=interaction.message,
            )
        )


@tree.command(name="automod", description="Manage AutoMod follow-up rules.")
@app_commands.default_permissions(administrator=True)
@app_commands.check(lambda i: has_permission_capability(i, "setup_panel"))
async def automod_cmd(interaction: discord.Interaction):
    if not get_feature_flag(bot.data_manager.config, "automod_panel", True):
        await respond_with_error(interaction, "The AutoMod panel is currently turned off in feature settings.", scope=SCOPE_MODERATION)
        return
    await interaction.response.send_message(embed=build_automod_dashboard_embed(interaction.guild), view=AutoModDashboardView(), ephemeral=True)


@tree.context_menu(name="Ban Image")
async def ban_image_context(interaction: discord.Interaction, message: discord.Message):
    if not is_staff(interaction):
        await respond_with_error(interaction, "You do not have permission to ban images.", scope=SCOPE_MODERATION)
        return

    await interaction.response.defer(ephemeral=True)
    settings = get_image_filter_settings()
    added = []
    duplicates = 0
    decoded_images = 0
    attachments, inspection_incomplete = _bounded_image_filter_attachments(message.attachments)
    inspection_reasons = {"attachment budget exceeded"} if inspection_incomplete else set()
    for attachment in attachments:
        if len(settings["entries"]) >= IMAGE_FILTER_MAX_ENTRIES:
            break
        inspection = await inspect_image_attachment(attachment)
        fingerprint = inspection.fingerprints[0] if inspection.fingerprints else None
        inspection_incomplete = inspection_incomplete or not inspection.complete
        if not inspection.complete and inspection.reason:
            inspection_reasons.add(inspection.reason)
        if not fingerprint:
            continue
        decoded_images += 1
        existing = match_banned_image(fingerprint, settings["entries"])
        if existing.quality in {"exact", "strong"}:
            duplicates += 1
            if decoded_images >= IMAGE_FILTER_MAX_ATTACHMENTS:
                break
            continue
        settings["entries"].append({
            "id": fingerprint["sha256"],
            **fingerprint,
            "label": truncate_text(attachment.filename, 80),
            "url": str(getattr(attachment, "url", "") or ""),
            "added_by": interaction.user.id,
            "added_at": now_iso(),
        })
        added.append(truncate_text(attachment.filename, 100))
        if decoded_images >= IMAGE_FILTER_MAX_ATTACHMENTS:
            break

    if not added:
        detail = "That message has no image attachments the filter can fingerprint."
        if duplicates:
            detail = "Every image on that message is already on the banned list."
        elif inspection_incomplete:
            reason_text = ", ".join(sorted(inspection_reasons)) or "inspection could not complete"
            detail = f"One or more attachments could not be added: {reason_text}."
        await interaction.followup.send(embed=make_embed("Nothing Added", f"> {detail}", kind="muted", scope=SCOPE_MODERATION, guild=interaction.guild), ephemeral=True)
        return

    store_image_filter_settings(settings)
    await bot.data_manager.save_config()

    lines = [f"- {name}" for name in added]
    if duplicates:
        lines.append(f"- {duplicates} image(s) skipped (already banned)")
    if inspection_incomplete:
        reason_text = ", ".join(sorted(inspection_reasons)) or "inspection could not complete"
        lines.append(f"- Some attachments were skipped: {reason_text}")
    if not settings["enabled"]:
        lines.append("")
        lines.append("The image filter is currently **disabled** — enable it under `/automod` → Image Filters.")
    await interaction.followup.send(
        embed=make_embed(
            "Images Banned",
            "> Added to the banned image list. Re-uploads and near-copies will now be detected.\n" + "\n".join(lines),
            kind="success",
            scope=SCOPE_MODERATION,
            guild=interaction.guild,
        ),
        ephemeral=True,
    )


class AutoModCog(commands.Cog):
    def __init__(self, bot):
        self.bot = bot



async def setup(bot):
    await bot.add_cog(AutoModCog(bot))
    bot.tree.add_command(automod_cmd)
    bot.tree.add_command(ban_image_context)
