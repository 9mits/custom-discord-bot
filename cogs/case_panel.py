"""History and case-panel UI views — split from cases.py."""
from __future__ import annotations

import html
import io
import json
import re
from typing import List, Optional, Union

import discord

from core.constants import (
    DEFAULT_RULES,
    SCOPE_MODERATION,
    SCOPE_SYSTEM,
)
from core.context import bot
from core.models import CaseNote
from core.services import (
    export_case_payload,
    normalize_case_record,
    sanitize_evidence_links,
    sanitize_linked_cases,
    sanitize_tags,
)
from core.utils import now_iso, parse_duration_str
from .shared import (
    format_duration,
    is_staff,
    panel_container,
    format_user_ref,
    make_action_log_embed,
    make_confirmation_embed,
    make_embed,
    make_empty_state_embed,
    make_error_embed,
    resolve_member,
    respond_with_error,
    send_log,
    send_punishment_log,
    truncate_text,
    UNDO_REASON_PRESETS,
)
from .cases import (
    build_all_cases_embed,
    build_case_detail_embed,
    build_undo_confirm_embed,
    describe_punishment_record,
    get_case_label,
    get_undo_reason_details,
)
from .history import FinalConfirmClear, UndoReasonModal, UndoReasonSelect, execute_undo_and_log

async def log_case_management_action(
    guild: discord.Guild,
    actor: discord.Member,
    target_user_id: str,
    record: dict,
    action: str,
    details: str,
):
    detail_lines = [line.strip() for line in str(details or "").splitlines() if line.strip()]
    embed = make_action_log_embed(
        f"{get_case_label(record)} Updated",
        "A case-management action modified the record metadata.",
        guild=guild,
        kind="info",
        scope=SCOPE_MODERATION,
        actor=format_user_ref(actor),
        target=f"<@{target_user_id}> (`{target_user_id}`)",
        reason=action,
        notes=detail_lines or [f"Result: {truncate_text(details, 500)}"],
    )
    if record.get("action_id"):
        embed.add_field(name="Action ID", value=f"`{record['action_id']}`", inline=True)
    await send_punishment_log(guild, embed)


def _split_case_input(value: str) -> List[str]:
    return [part.strip() for part in re.split(r"[\n,]+", value or "") if part.strip()]


class CaseNoteModal(discord.ui.Modal, title="Add Internal Case Note"):
    note = discord.ui.TextInput(
        label="Internal Note",
        style=discord.TextStyle.paragraph,
        placeholder="Staff-only note for future context.",
        max_length=1000,
    )

    def __init__(self, panel: "CasePanelView"):
        super().__init__()
        self.panel = panel

    async def on_submit(self, interaction: discord.Interaction) -> None:
        target_user_id, record = bot.data_manager.get_case(self.panel.case_id)
        if not record or not target_user_id:
            await respond_with_error(interaction, "The selected case no longer exists.", scope=SCOPE_MODERATION)
            return

        notes = record.setdefault("internal_notes", [])
        notes.append(CaseNote(interaction.user.id, self.note.value.strip(), now_iso()).to_dict())
        normalize_case_record(record)
        await bot.data_manager.save_punishments()
        await log_case_management_action(interaction.guild, interaction.user, target_user_id, record, "Internal note added", self.note.value)
        await self.panel.refresh_panel()
        await interaction.response.send_message(
            embed=make_confirmation_embed(
                f"{get_case_label(record)} Saved",
                "> Internal note added to the case record.",
                scope=SCOPE_MODERATION,
                guild=interaction.guild,
            ),
            ephemeral=True,
        )


class CaseLinksModal(discord.ui.Modal, title="Update Evidence and Tags"):
    evidence_links = discord.ui.TextInput(
        label="Evidence Links",
        style=discord.TextStyle.paragraph,
        placeholder="Paste URLs separated by commas or new lines.",
        required=False,
        max_length=1000,
    )
    linked_cases = discord.ui.TextInput(
        label="Related Case IDs",
        placeholder="Example: 101, 118, 204",
        required=False,
        max_length=200,
    )
    tags = discord.ui.TextInput(
        label="Tags",
        placeholder="Example: scam, repeat-offender, escalated",
        required=False,
        max_length=200,
    )

    def __init__(self, panel: "CasePanelView"):
        super().__init__()
        self.panel = panel
        _, record = bot.data_manager.get_case(panel.case_id)
        if record:
            self.evidence_links.default = "\n".join(record.get("evidence_links", []))
            self.linked_cases.default = ", ".join(str(case_id) for case_id in record.get("linked_cases", []))
            self.tags.default = ", ".join(record.get("tags", []))

    async def on_submit(self, interaction: discord.Interaction) -> None:
        target_user_id, record = bot.data_manager.get_case(self.panel.case_id)
        if not record or not target_user_id:
            await respond_with_error(interaction, "The selected case no longer exists.", scope=SCOPE_MODERATION)
            return

        record["evidence_links"] = sanitize_evidence_links(_split_case_input(self.evidence_links.value))
        record["linked_cases"] = sanitize_linked_cases(_split_case_input(self.linked_cases.value), current_case_id=record.get("case_id"))
        record["tags"] = sanitize_tags(_split_case_input(self.tags.value))
        normalize_case_record(record)
        await bot.data_manager.save_punishments()
        await log_case_management_action(
            interaction.guild,
            interaction.user,
            target_user_id,
            record,
            "Links and tags updated",
            f"Tags: {', '.join(record['tags']) or 'None'} | Linked: {', '.join(str(case_id) for case_id in record['linked_cases']) or 'None'}",
        )
        await self.panel.refresh_panel()
        await interaction.response.send_message(
            embed=make_confirmation_embed(
                f"{get_case_label(record)} Saved",
                "> Evidence links, linked cases, and tags were updated.",
                scope=SCOPE_MODERATION,
                guild=interaction.guild,
            ),
            ephemeral=True,
        )


class CaseSwitchSelect(discord.ui.Select):
    def __init__(self, panel: "CasePanelView"):
        self.panel = panel
        options = []
        for case_id in panel.case_ids[:25]:
            _, record = bot.data_manager.get_case(case_id)
            if not record:
                continue
            label = truncate_text(f"{get_case_label(record)} • {record.get('reason', 'Unknown')}", 100)
            description = truncate_text(describe_punishment_record(record), 100)
            options.append(
                discord.SelectOption(
                    label=label,
                    description=description,
                    value=str(case_id),
                    default=case_id == panel.case_id,
                )
            )
        if not options:
            options.append(discord.SelectOption(label="No cases found", value="0"))
        super().__init__(placeholder="Open another case...", min_values=1, max_values=1, options=options, row=3)

    async def callback(self, interaction: discord.Interaction) -> None:
        if self.values[0] == "0":
            await respond_with_error(interaction, "No valid cases are available.", scope=SCOPE_MODERATION)
            return
        self.panel.case_id = int(self.values[0])
        await interaction.response.edit_message(embed=self.panel.build_embed(), view=self.panel)


class CasePanelView(discord.ui.View):
    def __init__(self, target_user_id: str, case_ids: List[int], target_user: Optional[Union[discord.Member, discord.User]] = None):
        super().__init__(timeout=300)
        self.target_user_id = target_user_id
        self.case_ids = case_ids
        self.case_id = case_ids[0]
        self.target_user = target_user
        self.message: Optional[discord.Message] = None
        self.switch_select: Optional[CaseSwitchSelect] = None
        if len(self.case_ids) > 1:
            self.switch_select = CaseSwitchSelect(self)
            self.add_item(self.switch_select)

    async def resolve_target(self, guild: Optional[discord.Guild]) -> Optional[Union[discord.Member, discord.User]]:
        """Resolve the case target, falling back to a global user lookup so
        panel actions still work for members who left the server."""
        if isinstance(self.target_user, discord.Member):
            return self.target_user
        if guild:
            member = await resolve_member(guild, int(self.target_user_id))
            if member:
                self.target_user = member
                return member
        if self.target_user:
            return self.target_user
        try:
            user = await bot.fetch_user(int(self.target_user_id))
        except discord.HTTPException:
            return None
        self.target_user = user
        return user

    async def handle_case_removed(self) -> None:
        """Called after this panel's current case was undone."""
        if self.case_id in self.case_ids:
            self.case_ids.remove(self.case_id)
        if self.switch_select:
            self.remove_item(self.switch_select)
            self.switch_select = None
        if not self.case_ids:
            self.stop()
            if self.message:
                await self.message.edit(
                    embed=make_empty_state_embed(
                        "No Cases Remaining",
                        "> Every case for this member has been resolved or removed.",
                        scope=SCOPE_MODERATION,
                        guild=self.message.guild,
                    ),
                    view=None,
                )
            return
        self.case_id = self.case_ids[0]
        if len(self.case_ids) > 1:
            self.switch_select = CaseSwitchSelect(self)
            self.add_item(self.switch_select)
        if self.message:
            await self.message.edit(embed=self.build_embed(), view=self)

    def current_record(self) -> Optional[dict]:
        _, record = bot.data_manager.get_case(self.case_id)
        return record

    def build_embed(self) -> discord.Embed:
        record = self.current_record()
        if not record:
            return make_empty_state_embed(
                "Case Not Found",
                "> The selected case could not be loaded.",
                scope=SCOPE_MODERATION,
                guild=self.target_user.guild if isinstance(self.target_user, discord.Member) else None,
            )
        guild = self.target_user.guild if isinstance(self.target_user, discord.Member) else (self.message.guild if self.message else None)
        return build_case_detail_embed(guild, self.target_user_id, record, target_user=self.target_user)

    async def refresh_panel(self) -> None:
        if self.message:
            await self.message.edit(embed=self.build_embed(), view=self)

    @discord.ui.button(label="Add Note", style=discord.ButtonStyle.primary, row=0)
    async def add_note(self, interaction: discord.Interaction, button: discord.ui.Button) -> None:
        self.message = interaction.message
        await interaction.response.send_modal(CaseNoteModal(self))

    @discord.ui.button(label="Evidence & Tags", style=discord.ButtonStyle.primary, row=1)
    async def links_and_tags(self, interaction: discord.Interaction, button: discord.ui.Button) -> None:
        self.message = interaction.message
        await interaction.response.send_modal(CaseLinksModal(self))

    @discord.ui.button(label="Download Case", style=discord.ButtonStyle.secondary, row=1)
    async def export_case(self, interaction: discord.Interaction, button: discord.ui.Button) -> None:
        record = self.current_record()
        if not record:
            await respond_with_error(interaction, "The selected case could not be loaded.", scope=SCOPE_MODERATION)
            return

        payload = export_case_payload(self.target_user_id, record)
        buffer = io.BytesIO(json.dumps(payload, indent=2, ensure_ascii=False).encode("utf-8"))
        file = discord.File(buffer, filename=f"case_{record.get('case_id', 'unknown')}.json")
        await interaction.response.send_message(
            embed=make_confirmation_embed(
                f"{get_case_label(record)} Download Ready",
                "> A case file was generated for this case.",
                scope=SCOPE_MODERATION,
                guild=interaction.guild,
            ),
            file=file,
            ephemeral=True,
        )

    @discord.ui.button(label="Punish Target", style=discord.ButtonStyle.danger, row=2)
    async def punish_target(self, interaction: discord.Interaction, button: discord.ui.Button) -> None:
        target = await self.resolve_target(interaction.guild)
        if target is None:
            await respond_with_error(interaction, "The case target could not be resolved.", scope=SCOPE_MODERATION)
            return
        from .moderation import show_punish_menu
        await show_punish_menu(interaction, target)

    @discord.ui.button(label="Undo This Case", style=discord.ButtonStyle.danger, row=2)
    async def undo_case(self, interaction: discord.Interaction, button: discord.ui.Button) -> None:
        self.message = interaction.message
        record = self.current_record()
        if not record:
            await respond_with_error(interaction, "The selected case could not be loaded.", scope=SCOPE_MODERATION)
            return
        target = await self.resolve_target(interaction.guild)
        if target is None:
            await respond_with_error(interaction, "The case target could not be resolved.", scope=SCOPE_MODERATION)
            return
        prompt = CaseUndoPromptView(self, target, self.case_id)
        await interaction.response.send_message(
            embed=prompt.build_embed(interaction.guild, record),
            view=prompt,
            ephemeral=True,
        )


class CaseUndoPromptView(discord.ui.View):
    """Single-prompt undo flow for the case panel: reason preset select,
    optional custom reason, and confirm — all on one ephemeral message."""

    def __init__(self, panel: CasePanelView, target: Union[discord.Member, discord.User], case_id: int):
        super().__init__(timeout=120)
        self.panel = panel
        self.target = target
        self.case_id = case_id
        self.undo_reason_value = UNDO_REASON_PRESETS[0]["value"]
        self.custom_undo_reason: Optional[str] = None
        self.add_item(UndoReasonSelect(self, row=0))

    def get_reason_text(self) -> str:
        return get_undo_reason_details(self.undo_reason_value, self.custom_undo_reason)[1]

    def build_embed(self, guild: Optional[discord.Guild], record: Optional[dict] = None) -> discord.Embed:
        record = record or self.panel.current_record() or {}
        return build_undo_confirm_embed(self.target, record, self.get_reason_text(), guild=guild)

    async def _refresh_prompt(self, interaction: discord.Interaction) -> None:
        # Rebuild the reason select so the chosen preset shows as default.
        for item in list(self.children):
            if isinstance(item, UndoReasonSelect):
                self.remove_item(item)
        self.add_item(UndoReasonSelect(self, row=0))
        await interaction.response.edit_message(embed=self.build_embed(interaction.guild), view=self)

    async def on_reason_change(self, interaction: discord.Interaction) -> None:
        await self._refresh_prompt(interaction)

    async def on_custom_reason_set(self, interaction: discord.Interaction) -> None:
        await self._refresh_prompt(interaction)

    @discord.ui.button(label="Custom Reason", style=discord.ButtonStyle.primary, row=1)
    async def custom_reason(self, interaction: discord.Interaction, button: discord.ui.Button) -> None:
        await interaction.response.send_modal(UndoReasonModal(self))

    @discord.ui.button(label="Confirm Undo", style=discord.ButtonStyle.danger, row=1)
    async def confirm(self, interaction: discord.Interaction, button: discord.ui.Button) -> None:
        await interaction.response.edit_message(content="Processing undo...", embed=None, view=None)
        success, removed_record, action_result = await execute_undo_and_log(interaction, self.target, self.case_id, self.get_reason_text())
        if not success or not removed_record:
            await interaction.edit_original_response(content=action_result, embed=None, view=None)
            return
        await self.panel.handle_case_removed()
        await interaction.edit_original_response(
            content=f"**{get_case_label(removed_record)}** was undone.\n{action_result}",
            embed=None,
            view=None,
        )
        self.stop()

    @discord.ui.button(label="Cancel", style=discord.ButtonStyle.secondary, row=1)
    async def cancel(self, interaction: discord.Interaction, button: discord.ui.Button) -> None:
        await interaction.response.edit_message(content="Undo canceled.", embed=None, view=None)
        self.stop()


class OpenCaseButton(
    discord.ui.DynamicItem[discord.ui.Button],
    template=r"case:open:(?P<case_id>[0-9]+)",
):
    """Restart-surviving 'Open Case #N' button attached to log messages.
    Registered via bot.add_dynamic_items in core/bot.py."""

    def __init__(self, case_id: int) -> None:
        super().__init__(
            discord.ui.Button(
                label=f"Open Case #{case_id}",
                style=discord.ButtonStyle.secondary,
                custom_id=f"case:open:{case_id}",
            )
        )
        self.case_id = case_id

    @classmethod
    async def from_custom_id(cls, interaction: discord.Interaction, item: discord.ui.Button, match: "re.Match[str]", /) -> "OpenCaseButton":
        return cls(int(match["case_id"]))

    async def callback(self, interaction: discord.Interaction) -> None:
        if not is_staff(interaction):
            await respond_with_error(interaction, "You do not have permission to open case panels.", scope=SCOPE_MODERATION)
            return
        await show_case_panel(interaction, case_id=self.case_id)


def build_case_link_view(case_id: int) -> discord.ui.View:
    view = discord.ui.View(timeout=None)
    view.add_item(OpenCaseButton(case_id))
    return view


async def show_case_panel(
    interaction: discord.Interaction,
    *,
    case_id: Optional[int] = None,
    user: Optional[discord.Member] = None,
):
    await interaction.response.defer(ephemeral=True)

    target_user_id: Optional[str] = None
    target_user: Optional[Union[discord.Member, discord.User]] = user
    case_ids: List[int] = []

    if case_id:
        target_user_id, record = bot.data_manager.get_case(case_id)
        if not record or not target_user_id:
            await interaction.followup.send(
                embed=make_empty_state_embed(
                    "Case Not Found",
                    f"> No case with ID `{case_id}` was found.",
                    scope=SCOPE_MODERATION,
                    guild=interaction.guild,
                ),
                ephemeral=True,
            )
            return
        case_ids = [case_id]
        if not target_user:
            target_user = interaction.guild.get_member(int(target_user_id))

    elif user:
        target_user_id = str(user.id)
        case_ids = [record.get("case_id") for record in bot.data_manager.get_user_cases(user.id) if record.get("case_id")]
        if not case_ids:
            await interaction.followup.send(
                embed=make_empty_state_embed(
                    "No Cases Found",
                    f"> **{user.display_name}** has no recorded cases to manage.\n> Use `/punish` to open the first case for this member.",
                    scope=SCOPE_MODERATION,
                    guild=interaction.guild,
                    thumbnail=user.display_avatar.url,
                ),
                ephemeral=True,
            )
            return
    else:
        await interaction.followup.send(
            embed=make_error_embed(
                "Case Panel Requires Context",
                "> Choose a `case_id` or a `user` so the bot knows which case to open.",
                scope=SCOPE_MODERATION,
                guild=interaction.guild,
            ),
            ephemeral=True,
        )
        return

    view = CasePanelView(target_user_id, case_ids, target_user=target_user)
    message = await interaction.followup.send(embed=view.build_embed(), view=view, ephemeral=True, wait=True)
    view.message = message


class FirstConfirmClear(discord.ui.View):
    def __init__(self, target, moderator, origin_message=None):
        super().__init__(timeout=60)
        self.target = target
        self.moderator = moderator
        self.origin_message = origin_message

    @discord.ui.button(label="Yes, Clear History", style=discord.ButtonStyle.danger)
    async def confirm(self, interaction: discord.Interaction, button: discord.ui.Button) -> None:
        await interaction.response.edit_message(
            content=f"**WAIT!** Are you **REALLY** sure?\nThis will wipe ALL past violations for {self.target.mention}.\nThey will be treated as a new user for future punishments.",
            view=FinalConfirmClear(self.target, self.moderator, self.origin_message)
        )

    @discord.ui.button(label="Cancel", style=discord.ButtonStyle.secondary)
    async def cancel(self, interaction: discord.Interaction, button: discord.ui.Button) -> None:
        await interaction.response.edit_message(embed=make_embed("Cancelled", "> The history was not cleared.", kind="muted", scope=SCOPE_MODERATION, guild=interaction.guild), view=None)

class RuleEditModal(discord.ui.Modal, title="Add/Edit Punishment Rule"):
    rule_name = discord.ui.TextInput(label="Rule Name", placeholder="e.g. Spamming", max_length=50)
    base_dur = discord.ui.TextInput(label="Base Duration (mins)", placeholder="0=Warn, -1=Ban", max_length=10)
    esc_dur = discord.ui.TextInput(label="Escalated Duration (mins)", placeholder="Repeat offense duration", max_length=10)

    async def on_submit(self, interaction: discord.Interaction) -> None:
        name = self.rule_name.value.strip()
        if not name:
            await interaction.response.send_message(embed=make_embed("Invalid Input", "> Rule name cannot be empty.", kind="error", scope=SCOPE_SYSTEM, guild=interaction.guild), ephemeral=True)
            return
            
        # Use parse_duration_str to allow "ban", "1d", "30m" etc.
        base = parse_duration_str(self.base_dur.value.strip())
        esc = parse_duration_str(self.esc_dur.value.strip())
            
        rules = bot.data_manager.config.get("punishment_rules", DEFAULT_RULES)
        rules[name] = {"base": base, "escalated": esc}
        bot.data_manager.config["punishment_rules"] = rules
        await bot.data_manager.save_config()
        
        # Log
        log_embed = make_embed(
            "Punishment Rule Updated",
            "> An escalation rule was created or overwritten from the rules dashboard.",
            kind="info",
            scope=SCOPE_SYSTEM,
            guild=interaction.guild,
        )
        log_embed.add_field(name="Actor", value=format_user_ref(interaction.user), inline=True)
        log_embed.add_field(name="Rule", value=name, inline=True)
        log_embed.add_field(name="Values", value=f"> Base: {base}m\n> Escalated: {esc}m", inline=True)
        await send_log(interaction.guild, log_embed)
        
        await interaction.response.send_message(embed=make_embed("Rule Saved", f"> Rule **{name}** saved successfully.", kind="success", scope=SCOPE_SYSTEM, guild=interaction.guild), ephemeral=True)

class AllCasesSelect(discord.ui.Select):
    def __init__(self, page_items):
        self.page_items = page_items
        options = []
        for user_id, record in page_items[:25]:
            case_id = record.get("case_id")
            if not isinstance(case_id, int):
                continue
            name = str(record.get("target_name") or user_id)
            label = f"{get_case_label(record)} • {name}"
            if len(label) > 100:
                label = label[:100]
            desc = truncate_text(f"{describe_punishment_record(record)} • {record.get('reason', 'Unknown')}", 100)
            options.append(discord.SelectOption(label=label, description=desc, value=str(case_id)))
        if not options:
            options = [discord.SelectOption(label="No cases", value="none")]
        super().__init__(placeholder="Select a case to open its panel...", min_values=1, max_values=1, options=options)

    async def callback(self, interaction: discord.Interaction) -> None:
        if self.values[0] == "none":
            await interaction.response.defer()
            return
        await show_case_panel(interaction, case_id=int(self.values[0]))


class AllCasesNavButton(discord.ui.Button):
    def __init__(self, label: str, delta: int, *, disabled: bool = False):
        super().__init__(label=label, style=discord.ButtonStyle.primary, disabled=disabled, row=1)
        self.delta = delta

    async def callback(self, interaction: discord.Interaction) -> None:
        view: "AllCasesView" = self.view
        view.page = max(0, min(view.page + self.delta, view.max_pages - 1))
        view.update_components()
        await interaction.response.edit_message(embed=view.build_embed(), view=view)


class AllCasesView(discord.ui.View):
    def __init__(self, guild: discord.Guild):
        super().__init__(timeout=300)
        self.guild = guild
        self.page = 0
        self.items_per_page = 20
        self.message: Optional[discord.Message] = None
        self.cases: List = []
        self.counts: dict = {}
        self.max_pages = 1
        self.reload()

    def reload(self) -> None:
        self.cases = bot.data_manager.get_all_cases()
        self.counts = {}
        for _, record in self.cases:
            t = record.get("type", "unknown")
            self.counts[t] = self.counts.get(t, 0) + 1
        self.max_pages = max(1, (len(self.cases) + self.items_per_page - 1) // self.items_per_page)
        self.page = max(0, min(self.page, self.max_pages - 1))
        self.update_components()

    def get_page_items(self) -> List:
        start = self.page * self.items_per_page
        return self.cases[start:start + self.items_per_page]

    def build_embed(self) -> discord.Embed:
        return build_all_cases_embed(
            self.guild,
            self.get_page_items(),
            page=self.page,
            max_pages=self.max_pages,
            total=len(self.cases),
            counts=self.counts,
        )

    def update_components(self) -> None:
        self.clear_items()
        page_items = self.get_page_items()
        if page_items:
            self.add_item(AllCasesSelect(page_items))
        if self.max_pages > 1:
            self.add_item(AllCasesNavButton("Previous", -1, disabled=(self.page == 0)))
            self.add_item(discord.ui.Button(label=f"Page {self.page + 1}/{self.max_pages}", disabled=True, style=discord.ButtonStyle.secondary, row=1))
            self.add_item(AllCasesNavButton("Next", 1, disabled=(self.page >= self.max_pages - 1)))

class AccessView(discord.ui.View):
    def __init__(self):
        super().__init__(timeout=None)

    @discord.ui.select(cls=discord.ui.RoleSelect, placeholder="Select a role to toggle access...", min_values=1, max_values=1)
    async def select_role(self, interaction: discord.Interaction, select: discord.ui.RoleSelect) -> None:
        role = select.values[0]
        rid = role.id
        mod_roles = bot.data_manager.config.get("mod_roles", [])
        
        if rid in mod_roles:
            mod_roles.remove(rid)
            action = "removed from"
        else:
            mod_roles.append(rid)
            action = "added to"
            
        bot.data_manager.config["mod_roles"] = mod_roles
        await bot.data_manager.save_config()
        
        # Log
        log_embed = make_embed(
            "Moderator Access Updated",
            "> The list of roles with moderation access was changed.",
            kind="info",
            scope=SCOPE_SYSTEM,
            guild=interaction.guild,
        )
        log_embed.add_field(name="Actor", value=format_user_ref(interaction.user), inline=True)
        log_embed.add_field(name="Role", value=f"{role.mention} (`{role.id}`)", inline=True)
        log_embed.add_field(name="Action", value=action.capitalize(), inline=True)
        await send_log(interaction.guild, log_embed)
        
        role_lines = "\n".join(f"- <@&{rid}>" for rid in mod_roles) if mod_roles else "None configured — members with the Admin or Mod role can moderate."

        if interaction.message and interaction.message.embeds:
            embed = interaction.message.embeds[0]
            embed.clear_fields()
            embed.add_field(name="Current Access Roles", value=role_lines, inline=False)
            await interaction.response.edit_message(embed=embed, view=self)
        else:
            await interaction.response.edit_message(view=self)

class RuleDeleteSelect(discord.ui.Select):
    def __init__(self):
        rules = bot.data_manager.config.get("punishment_rules", DEFAULT_RULES)
        options = [discord.SelectOption(label=r) for r in list(rules.keys())[:25]]
        if not options:
            options = [discord.SelectOption(label="No rules found", value="none")]
        super().__init__(placeholder="Select rule to delete...", min_values=1, max_values=1, options=options)
    
    async def callback(self, interaction: discord.Interaction) -> None:
        if self.values[0] == "none":
            await interaction.response.send_message(embed=make_embed("No Rules", "> No rules to delete.", kind="info", scope=SCOPE_SYSTEM, guild=interaction.guild), ephemeral=True)
            return

        name = self.values[0]
        rules = bot.data_manager.config.get("punishment_rules", DEFAULT_RULES)
        if name in rules:
            del rules[name]
            bot.data_manager.config["punishment_rules"] = rules
            await bot.data_manager.save_config()

            # Log
            log_embed = make_embed(
                "Punishment Rule Deleted",
                "> A punishment escalation rule was removed from the dashboard.",
                kind="danger",
                scope=SCOPE_SYSTEM,
                guild=interaction.guild,
            )
            log_embed.add_field(name="Actor", value=format_user_ref(interaction.user), inline=True)
            log_embed.add_field(name="Rule", value=name, inline=True)
            await send_log(interaction.guild, log_embed)

            await interaction.response.send_message(embed=make_embed("Rule Deleted", f"> Rule **{name}** deleted.", kind="success", scope=SCOPE_SYSTEM, guild=interaction.guild), ephemeral=True)
        else:
            await interaction.response.send_message(embed=make_embed("Not Found", "> Rule not found.", kind="error", scope=SCOPE_SYSTEM, guild=interaction.guild), ephemeral=True)

class RuleDeleteView(discord.ui.View):
    def __init__(self):
        super().__init__(timeout=60)
        self.add_item(RuleDeleteSelect())

class RuleSelectForEdit(discord.ui.Select):
    def __init__(self):
        rules = bot.data_manager.config.get("punishment_rules", DEFAULT_RULES)
        options = []
        for name in list(rules.keys())[:25]:
            data = rules[name]
            desc = f"{format_duration(data['base'])} -> {format_duration(data['escalated'])}"
            options.append(discord.SelectOption(label=name, value=name, description=desc))
        
        if not options:
            options = [discord.SelectOption(label="No rules found", value="none")]
            
        super().__init__(placeholder="Select rule to edit...", min_values=1, max_values=1, options=options)

    async def callback(self, interaction: discord.Interaction) -> None:
        if self.values[0] == "none":
            await interaction.response.send_message(embed=make_embed("No Rules", "> No rules to edit.", kind="info", scope=SCOPE_SYSTEM, guild=interaction.guild), ephemeral=True)
            return

        name = self.values[0]
        rules = bot.data_manager.config.get("punishment_rules", DEFAULT_RULES)
        if name in rules:
            data = rules[name]
            modal = RuleEditModal()
            modal.rule_name.default = name
            # Fix: Display "Ban" instead of -1
            modal.base_dur.default = "Ban" if data['base'] == -1 else str(data['base'])
            modal.esc_dur.default = "Ban" if data['escalated'] == -1 else str(data['escalated'])

            modal.title = f"Edit Rule: {name}"[:45]
            await interaction.response.send_modal(modal)
        else:
            await interaction.response.send_message(embed=make_embed("Not Found", "> Rule not found.", kind="error", scope=SCOPE_SYSTEM, guild=interaction.guild), ephemeral=True)

class RuleSelectView(discord.ui.View):
    def __init__(self):
        super().__init__(timeout=60)
        self.add_item(RuleSelectForEdit())

def generate_transcript_html(messages, user):
    style = """
    body { background-color: #313338; color: #dbdee1; font-family: "gg sans", "Helvetica Neue", Helvetica, Arial, sans-serif; margin: 0; padding: 20px; }
    .chat-container { max-width: 100%; display: flex; flex-direction: column; }
    .message { display: flex; margin-top: 1rem; padding: 5px; }
    .message:hover { background-color: #2e3035; }
    .message.deleted { background-color: rgba(242, 63, 66, 0.1); border-left: 3px solid #f23f42; }
    .avatar { width: 40px; height: 40px; border-radius: 50%; margin-right: 16px; margin-top: 2px; }
    .content { display: flex; flex-direction: column; width: 100%; }
    .header { display: flex; align-items: center; margin-bottom: 2px; }
    .username { font-weight: 500; color: #f2f3f5; margin-right: 0.25rem; font-size: 1rem; }
    .timestamp { font-size: 0.75rem; color: #949ba4; margin-left: 0.25rem; }
    .msg-content { font-size: 1rem; line-height: 1.375rem; white-space: pre-wrap; color: #dbdee1; }
    .attachment-container { margin-top: 5px; }
    .attachment-img { max-width: 400px; max-height: 300px; border-radius: 8px; cursor: pointer; }
    .deleted-tag { font-size: 0.625rem; color: #f23f42; margin-left: 4px; border: 1px solid #f23f42; border-radius: 3px; padding: 0 4px; vertical-align: middle; }
    .edited-tag { font-size: 0.625rem; color: #949ba4; margin-left: 4px; vertical-align: middle; }
    .channel-ref { font-size: 0.75rem; color: #949ba4; font-weight: bold; margin-bottom: 2px; }
    a { color: #00a8fc; text-decoration: none; }
    a:hover { text-decoration: underline; }
    """
    
    safe_display_name = html.escape(user.display_name)
    html_parts = [
        f'<!DOCTYPE html><html lang="en"><head><meta charset="UTF-8"><title>History - {safe_display_name}</title><style>{style}</style></head><body>',
        f'<div class="chat-container"><h2 style="color:white; border-bottom: 1px solid #4e5058; padding-bottom: 10px;">Chat History: {safe_display_name} ({user.id})</h2>'
    ]

    # messages is Newest -> Oldest. Reverse to show Oldest -> Newest in HTML.
    for m in reversed(messages):
        ts = m["created_at"].strftime("%Y-%m-%d %H:%M:%S")
        content = html.escape(m.get("content", ""))
        if not content: content = "<em>[No Text Content]</em>"
        author_name = html.escape(m.get("author_name", user.display_name))
        author_avatar_url = html.escape(m.get("author_avatar_url", user.display_avatar.url if getattr(user, "display_avatar", None) else ""))

        # Status tags
        tags = ""
        if m.get("deleted"): tags += '<span class="deleted-tag">DELETED</span>'
        if m.get("edited"): tags += '<span class="edited-tag">(edited)</span>'

        # Attachments
        att_html = ""
        if m.get("attachments"):
            att_html += '<div class="attachment-container">'
            for a in m["attachments"]:
                safe_url = html.escape(a["url"])
                safe_filename = html.escape(a["filename"])
                ext = a["filename"].split('.')[-1].lower()
                if ext in ['png', 'jpg', 'jpeg', 'gif', 'webp']:
                    att_html += f'<a href="{safe_url}" target="_blank"><img src="{safe_url}" class="attachment-img" alt="{safe_filename}"></a><br>'
                else:
                    att_html += f'<a href="{safe_url}" target="_blank">{safe_filename}</a><br>'
            att_html += '</div>'

        # Stickers
        if m.get("stickers"):
            att_html += f'<div style="color:#949ba4; font-size:0.8rem;">Stickers: {html.escape(", ".join(m["stickers"]))}</div>'

        div_class = "message deleted" if m.get("deleted") else "message"
        row = f"""
        <div class="{div_class}">
            <img class="avatar" src="{author_avatar_url}" alt="Avatar">
            <div class="content">
                <div class="channel-ref">#{html.escape(str(m['channel_id']))}</div>
                <div class="header">
                    <span class="username">{author_name}</span>
                    <span class="timestamp">{ts}</span>
                    {tags}
                </div>
                <div class="msg-content">{content}</div>
                {att_html}
            </div>
        </div>
        """
        html_parts.append(row)
        
    html_parts.append('</div></body></html>')
    return "\n".join(html_parts)

class RulesDashboardButtons(discord.ui.ActionRow):
    @discord.ui.button(label="List Rules", style=discord.ButtonStyle.primary)
    async def list_rules(self, interaction: discord.Interaction, button: discord.ui.Button) -> None:
        rules = bot.data_manager.config.get("punishment_rules", DEFAULT_RULES)
        embed = make_embed(
            "Punishment Rules",
            "> Per-reason durations used by the moderation console. First offense, then repeat offense.",
            kind="info",
            scope=SCOPE_MODERATION,
            guild=interaction.guild,
        )
        if not rules:
            embed.add_field(name="Rules", value="No rules configured.", inline=False)
        for name, data in list(rules.items())[:24]:
            embed.add_field(name=name, value=f"{format_duration(data['base'])} → {format_duration(data['escalated'])}", inline=True)
        if len(rules) > 24:
            embed.add_field(name="…", value=f"{len(rules) - 24} more rules not shown.", inline=True)
        await interaction.response.send_message(embed=embed, ephemeral=True)

    @discord.ui.button(label="Add Rule", style=discord.ButtonStyle.success)
    async def add_rule(self, interaction: discord.Interaction, button: discord.ui.Button) -> None:
        modal = RuleEditModal()
        modal.title = "Add New Rule"
        await interaction.response.send_modal(modal)

    @discord.ui.button(label="Edit Rule", style=discord.ButtonStyle.secondary)
    async def edit_rule(self, interaction: discord.Interaction, button: discord.ui.Button) -> None:
        await interaction.response.send_message(embed=make_embed("Edit Rule", "> Select the rule you want to edit below.", kind="info", scope=SCOPE_SYSTEM, guild=interaction.guild), view=RuleSelectView(), ephemeral=True)

    @discord.ui.button(label="Delete Rule", style=discord.ButtonStyle.danger)
    async def delete_rule(self, interaction: discord.Interaction, button: discord.ui.Button) -> None:
        await interaction.response.send_message(embed=make_embed("Delete Rule", "> Select the rule you want to delete below.", kind="warning", scope=SCOPE_SYSTEM, guild=interaction.guild), view=RuleDeleteView(), ephemeral=True)


class RulesDashboardView(discord.ui.LayoutView):
    def __init__(self, guild: "discord.Guild | None" = None) -> None:
        super().__init__(timeout=None)
        rules = bot.data_manager.config.get("punishment_rules", DEFAULT_RULES)
        container = panel_container(
            "Punishment Scaling Rules",
            "> Preset rule baselines used by the punishment console. "
            "Base = first offence, Escalated = repeat offence.",
            guild=guild,
        )
        container.add_item(discord.ui.TextDisplay(f"**Configured rules** · {len(rules)}"))
        container.add_item(discord.ui.Separator())
        container.add_item(RulesDashboardButtons())
        self.add_item(container)


async def setup(bot) -> None:
    pass  # views are registered by importing this module
