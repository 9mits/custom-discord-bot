"""Native AutoMod follow-up engine, policy views, report flows, and /automod command."""

import asyncio
import hashlib
import io
import re
import time
import warnings
from dataclasses import dataclass
from datetime import datetime, timedelta
from typing import Optional, List, Union, Tuple

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
_image_filter_work_semaphore = asyncio.Semaphore(2)


@dataclass(frozen=True)
class ImageMatch:
    entry: Optional[dict] = None
    distance: int = 0
    vertical_distance: int = 0
    color_distance: int = 0
    quality: str = "none"

    @property
    def matched(self) -> bool:
        return self.entry is not None


@dataclass(frozen=True)
class ImageFilterResult:
    matched: bool = False
    message_deleted: bool = False
    block_downstream: bool = False


@dataclass(frozen=True)
class ImageInspection:
    fingerprints: Tuple[dict, ...] = ()
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


def normalize_image_filter_settings(current: dict) -> dict:
    if not isinstance(current, dict):
        current = {}
    raw_entries = current.get("entries", [])
    if not isinstance(raw_entries, list):
        raw_entries = []
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
            "label": truncate_text(str(item.get("label", "Banned image") or "Banned image"), 80),
            "added_by": _coerce_image_filter_int(item.get("added_by"), 0),
            "added_at": str(item.get("added_at", "") or ""),
        })
    punishment_type = str(current.get("punishment_type", "warn") or "warn").lower()
    if punishment_type not in {"warn", "timeout", "kick", "ban"}:
        punishment_type = "warn"
    return {
        "enabled": bool(current.get("enabled", False)),
        "delete_message": bool(current.get("delete_message", True)),
        "log_detections": bool(current.get("log_detections", True)),
        "punish": bool(current.get("punish", False)),
        "punishment_type": punishment_type,
        "duration_minutes": _coerce_image_filter_int(current.get("duration_minutes"), 60, minimum=1, maximum=40320),
        "entries": entries[:IMAGE_FILTER_MAX_ENTRIES],
    }


def get_image_filter_settings() -> dict:
    return normalize_image_filter_settings(bot.data_manager.config.get("image_filters", {}))


def store_image_filter_settings(settings: dict) -> dict:
    normalized = normalize_image_filter_settings(settings)
    bot.data_manager.config["image_filters"] = normalized
    bot.data_manager.mark_config_dirty()
    return normalized


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


def inspect_image_bytes(data: bytes) -> ImageInspection:
    try:
        from PIL import Image, UnidentifiedImageError
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
                source.draft("RGB", (64, 64))
                sample = _thumbnail_with_orientation(source, orientation)
                fingerprint = _fingerprint_sample(sample, width=width, height=height, sha256_hex=sha256_hex)
                return ImageInspection(fingerprints=(fingerprint,), complete=True, is_image=True)
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
    return punishment_type in {"warn", "timeout", "kick", "ban"} and match.quality in {"exact", "strong"}


def image_match_similarity(match: ImageMatch) -> int:
    if not match.matched:
        return 0
    if match.quality == "exact":
        return 100
    if match.quality == "legacy":
        return max(0, min(100, round((1 - min(64, match.distance) / 64) * 100)))
    hash_similarity = 1 - min(128, match.distance + match.vertical_distance) / 128
    color_similarity = 1 - min(765, match.color_distance) / 765
    return max(0, min(100, round((hash_similarity * 0.8 + color_similarity * 0.2) * 100)))


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


async def inspect_image_attachment(attachment) -> ImageInspection:
    async with _image_filter_work_semaphore:
        try:
            data = await attachment.read()
        except Exception:
            return ImageInspection(complete=False, reason="attachment download failed")
        if len(data) > IMAGE_FILTER_MAX_BYTES:
            return ImageInspection(complete=False, reason="attachment exceeds the byte limit")
        return await asyncio.to_thread(inspect_image_bytes, data)


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


async def apply_image_filter_punishment(
    guild: discord.Guild,
    member: discord.Member,
    *,
    entry_label: str,
    punishment_type: str,
    duration_minutes: int,
) -> Tuple[bool, str, Optional[dict]]:
    if member.id == guild.owner_id or member.guild_permissions.administrator:
        return False, "Safety check skipped auto-punishment for the server owner or an administrator.", None
    if punishment_type in {"timeout", "kick", "ban"}:
        bot_member = guild.me
        if bot_member is None or member.top_role >= bot_member.top_role:
            return False, "Safety check skipped auto-punishment because the bot cannot manage this member.", None

    reason = f"Banned image posted [{entry_label}]"
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
        ]),
        1000,
    )

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
        return False, "The bot does not have permission to apply the configured punishment.", None
    except Exception as exc:
        return False, f"Failed to apply punishment: {exc}", None

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
    case_record = await bot.data_manager.add_punishment(str(member.id), record)
    bot.data_manager.config.setdefault("stats", {})["total_issued"] = bot.data_manager.config.get("stats", {}).get("total_issued", 0) + 1
    await bot.data_manager.save_config()

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
            dm_embed.add_field(name="Duration", value="Ban", inline=True)
        dm_embed.add_field(
            name="Automated Detection Notice",
            value="> This action was taken by automated image recognition, which can make mistakes. If this was an error, press **Appeal Punishment** below and staff will review it.",
            inline=False,
        )
        await member.send(embed=dm_embed, view=build_appeal_view(guild.id, case_record["case_id"]))
    except Exception:
        pass

    status = punishment_type.title()
    if punishment_type == "warn":
        status = "Warning"
    elif punishment_type == "timeout":
        status = f"Timeout ({format_duration(duration_minutes)})"
    return True, f"Applied {status} automatically", case_record


async def run_image_filter(message: discord.Message) -> ImageFilterResult:
    if not message.guild or message.author.bot or not message.attachments:
        return ImageFilterResult()
    if not isinstance(message.author, discord.Member):
        return ImageFilterResult()
    settings = get_image_filter_settings()
    if not settings["enabled"] or not settings["entries"]:
        return ImageFilterResult()
    native_settings = get_native_automod_settings(bot.data_manager.config)
    if is_native_automod_exempt(message.author, message.channel.id, native_settings):
        return ImageFilterResult()

    matched_attachment = None
    matched = ImageMatch()
    quality_rank = {"exact": 0, "strong": 1, "fuzzy": 2, "legacy": 3, "none": 4}
    decoded_images = 0
    attachments, inspection_incomplete = _bounded_image_filter_attachments(message.attachments)
    for attachment in attachments:
        inspection = await inspect_image_attachment(attachment)
        fingerprints = list(inspection.fingerprints)
        inspection_incomplete = inspection_incomplete or not inspection.complete
        if not fingerprints:
            continue
        decoded_images += 1
        for fingerprint in fingerprints:
            candidate = match_banned_image(fingerprint, settings["entries"])
            if candidate.matched and quality_rank[candidate.quality] < quality_rank[matched.quality]:
                matched = candidate
                matched_attachment = attachment
            if matched.quality == "exact":
                break
        if matched.quality == "exact":
            break
        if decoded_images >= IMAGE_FILTER_MAX_ATTACHMENTS:
            break
    if not matched.matched:
        if inspection_incomplete:
            await log_image_filter_inspection_failure(message)
            return ImageFilterResult(block_downstream=bool(settings["delete_message"]))
        return ImageFilterResult()

    trusted_match = matched.quality in {"exact", "strong"}
    deleted = False
    if settings["delete_message"] and trusted_match:
        try:
            await message.delete()
            deleted = True
        except Exception as exc:
            logger.warning("Image filter could not delete matched message %s: %s", message.id, exc)

    result = ImageFilterResult(
        matched=True,
        message_deleted=deleted,
        block_downstream=bool(settings["delete_message"] and (trusted_match or inspection_incomplete)),
    )

    case_record = None
    action_summary = "Message deleted" if deleted else "Detection logged"
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
                applied, punish_summary, case_record = await apply_image_filter_punishment(
                    message.guild,
                    message.author,
                    entry_label=matched.entry["label"],
                    punishment_type=settings["punishment_type"],
                    duration_minutes=settings["duration_minutes"],
                )
            except Exception as exc:
                applied = False
                punish_summary = f"Auto-punish failed unexpectedly: {exc}"
                case_record = None
            action_summary = punish_summary
            if not applied:
                failure_requires_log = True

    if settings["log_detections"] or failure_requires_log:
        try:
            flagged_url = str(getattr(matched_attachment, "url", "") or "").strip()
            matched_url = str(matched.entry.get("url") or "").strip() or flagged_url
            matched_label = discord.utils.escape_markdown(str(matched.entry["label"]))
            matched_value = f"[{matched_label}]({matched_url})" if matched_url.startswith(("http://", "https://")) else matched_label
            embed = make_embed(
                "Banned Image Detected",
                f"> {format_user_ref(message.author)} posted a banned image in {message.channel.mention}.",
                kind="warning",
                scope=SCOPE_MODERATION,
                guild=message.guild,
            )
            embed.add_field(name="Matched", value=matched_value, inline=True)
            embed.add_field(name="Similarity", value=f"{image_match_similarity(matched)}%", inline=True)
            embed.add_field(name="Action", value=action_summary, inline=False)
            jump_url = str(getattr(message, "jump_url", "") or "")
            message_value = f"[{message.id}]({jump_url})" if jump_url.startswith(("http://", "https://")) else f"`{message.id}`"
            embed.add_field(name="Message ID", value=message_value, inline=True)
            if flagged_url.startswith(("http://", "https://")):
                embed.set_image(url=flagged_url)
            view = None
            if case_record:
                from .case_panel import build_case_link_view
                view = build_case_link_view(case_record["case_id"])
            await send_automod_log(message.guild, embed, view=view)
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
        "> Add images with the **Ban Image** right-click action. High-confidence matches can be deleted or punished; uncertain matches are sent for manual review.",
        kind="warning",
        scope=SCOPE_MODERATION,
        guild=guild,
    )
    embed.add_field(name="Filters", value="Enabled" if settings["enabled"] else "Disabled", inline=True)
    embed.add_field(name="Delete Message", value="On" if settings["delete_message"] else "Off", inline=True)
    embed.add_field(name="Log Detections", value="On" if settings["log_detections"] else "Off", inline=True)
    embed.add_field(name="Auto Punish", value=punishment_label, inline=True)
    embed.add_field(name="Banned Images", value=f"{len(settings['entries'])}/{IMAGE_FILTER_MAX_ENTRIES}", inline=True)
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
