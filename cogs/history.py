"""History and case-panel UI views — split from cases.py."""
from __future__ import annotations

from typing import Awaitable, Callable, List, Optional, Tuple, Union

import discord

from core.constants import SCOPE_MODERATION
from core.context import bot
from core.utils import iso_to_dt
from .shared import (
    UNDO_REASON_PRESETS,
    make_confirmation_embed,
    make_embed,
    respond_with_error,
    send_punishment_log,
    truncate_text,
)
from .roles import build_punish_embed
from .cases import (
    build_history_archive_attachment,
    build_history_cleared_log_embed,
    build_history_overview_embed,
    build_no_history_embed,
    build_punishment_undo_log_embed,
    build_undo_confirm_embed,
    build_undo_panel_embed,
    clear_user_history_records,
    describe_punishment_record,
    get_case_label,
    get_case_id,
    get_undo_reason_details,
    undo_case_record,
)


async def execute_undo_and_log(
    interaction: discord.Interaction,
    target: Union[discord.Member, discord.User],
    case_id: int,
    undo_reason: str,
) -> Tuple[bool, Optional[dict], str]:
    """Undo a case and post the undo log. The single undo->log path shared by
    /undo, the history panel, and the case panel."""
    success, removed_record, action_result = await undo_case_record(
        interaction.guild,
        interaction.user,
        target,
        case_id,
        undo_reason,
    )
    if not success or not removed_record:
        return success, removed_record, action_result

    attachment = build_history_archive_attachment(
        "undo_case",
        target_user_id=str(target.id),
        actor_id=interaction.user.id,
        payload={
            "action": "undo_case",
            "undo_reason": undo_reason,
            "record": removed_record,
        },
    )
    log_embed = build_punishment_undo_log_embed(interaction.guild, interaction.user, target, removed_record, undo_reason, action_result)
    from .moderation import build_revoke_undo_view, stash_undone_case
    stash_undone_case(target.id, removed_record)
    await bot.data_manager.save_config()
    view = build_revoke_undo_view(removed_record.get("case_id") or 0)
    await send_punishment_log(interaction.guild, log_embed, view=view, attachments=[attachment])
    return success, removed_record, action_result

class FinalConfirmClear(discord.ui.View):
    def __init__(self, target, moderator, origin_message=None):
        super().__init__(timeout=60)
        self.target = target
        self.moderator = moderator
        self.origin_message = origin_message

    @discord.ui.button(label="YES, WIPE EVERYTHING", style=discord.ButtonStyle.danger)
    async def confirm(self, interaction: discord.Interaction, button: discord.ui.Button) -> None:
        removed_records = await clear_user_history_records(self.target)
        if removed_records:
            attachment = build_history_archive_attachment(
                "history_clear",
                target_user_id=str(self.target.id),
                actor_id=self.moderator.id,
                payload={"action": "history_clear", "records": removed_records},
            )
            log_embed = build_history_cleared_log_embed(interaction.guild, self.moderator, self.target, removed_records)
            await send_punishment_log(interaction.guild, log_embed, attachments=[attachment])

            await interaction.response.edit_message(embed=make_embed("History Cleared", "> The user's moderation history has been completely wiped.", kind="success", scope=SCOPE_MODERATION, guild=interaction.guild), view=None)

            if self.origin_message:
                try:
                    await self.origin_message.edit(embed=build_punish_embed(self.target))
                except Exception:
                    pass
        else:
            await interaction.response.edit_message(embed=make_embed("Nothing to Clear", "> This user has no history to clear.", kind="muted", scope=SCOPE_MODERATION, guild=interaction.guild), view=None)

    @discord.ui.button(label="No, Stop", style=discord.ButtonStyle.secondary)
    async def cancel(self, interaction: discord.Interaction, button: discord.ui.Button) -> None:
        await interaction.response.edit_message(embed=make_embed("Cancelled", "> The history was not cleared.", kind="muted", scope=SCOPE_MODERATION, guild=interaction.guild), view=None)

class HistorySelect(discord.ui.Select):
    def __init__(self, page_items: List[dict], panel: "HistoryView"):
        self.panel = panel
        options = []
        for record in page_items:
            case_id = get_case_id(record)
            if case_id is None:
                continue
            reason = record.get("reason", "Unknown")
            dt = iso_to_dt(record.get("timestamp"))
            date_str = dt.strftime("%Y-%m-%d") if dt else "Unknown"
            label = f"{get_case_label(record)}: {truncate_text(reason, 70)}"
            desc = f"{date_str} • {describe_punishment_record(record)}"
            options.append(discord.SelectOption(label=label, description=desc, value=str(case_id)))

        if not options:
            options.append(discord.SelectOption(label="No cases found", value="0", description="There are no valid cases on this page."))

        super().__init__(placeholder="Select a case to open its control panel...", min_values=1, max_values=1, options=options)

    async def callback(self, interaction: discord.Interaction) -> None:
        if self.values[0] == "0":
            await respond_with_error(interaction, "There are no valid cases to open on this page.", scope=SCOPE_MODERATION)
            return

        self.panel.message = interaction.message
        from .case_panel import show_case_panel
        await show_case_panel(interaction, case_id=int(self.values[0]))


class UndoCaseSelect(discord.ui.Select):
    def __init__(self, page_items: List[dict], panel: "HistoryView"):
        self.panel = panel
        options = []
        for record in page_items:
            case_id = get_case_id(record)
            if case_id is None:
                continue
            dt = iso_to_dt(record.get("timestamp"))
            date_str = dt.strftime("%Y-%m-%d") if dt else "Unknown"
            label = f"{get_case_label(record)} ({date_str})"
            desc = truncate_text(f"{describe_punishment_record(record)} • {record.get('reason', 'Unknown')}", 100)
            options.append(
                discord.SelectOption(
                    label=label,
                    description=desc,
                    value=str(case_id),
                    default=case_id == panel.selected_case_id,
                )
            )

        if not options:
            options.append(discord.SelectOption(label="No cases found", value="0", description="There are no valid cases on this page."))

        super().__init__(placeholder="Select punishment to undo...", min_values=1, max_values=1, options=options, row=0)

    async def callback(self, interaction: discord.Interaction) -> None:
        if self.values[0] == "0":
            await respond_with_error(interaction, "There are no valid cases to undo on this page.", scope=SCOPE_MODERATION)
            return

        self.panel.message = interaction.message
        self.panel.selected_case_id = int(self.values[0])
        self.panel.update_components()
        await interaction.response.edit_message(embed=self.panel.build_embed(), view=self.panel)


class UndoReasonSelect(discord.ui.Select):
    """Reason preset picker. The host must expose undo_reason_value,
    custom_undo_reason, and an async on_reason_change(interaction) hook."""

    def __init__(self, host, *, row: int = 1):
        self.host = host
        options = [
            discord.SelectOption(
                label=preset["label"],
                value=preset["value"],
                description=truncate_text(preset["description"], 100),
                default=(not host.custom_undo_reason and preset["value"] == host.undo_reason_value),
            )
            for preset in UNDO_REASON_PRESETS
        ]
        super().__init__(placeholder="Select an undo reason preset...", min_values=1, max_values=1, options=options, row=row)

    async def callback(self, interaction: discord.Interaction) -> None:
        self.host.undo_reason_value = self.values[0]
        self.host.custom_undo_reason = None
        await self.host.on_reason_change(interaction)


class HistoryActionButton(discord.ui.Button):
    def __init__(self, label: str, style: discord.ButtonStyle, action: str, *, row: int, disabled: bool = False):
        super().__init__(label=label, style=style, row=row, disabled=disabled)
        self.action = action

    async def callback(self, interaction: discord.Interaction) -> None:
        view: HistoryView = self.view
        await view.handle_action(interaction, self.action)

class HistoryNavButton(discord.ui.Button):
    def __init__(self, label: str, style: discord.ButtonStyle, direction: int, *, row: int, disabled: bool = False):
        super().__init__(label=label, style=style, row=row, disabled=disabled)
        self.direction = direction

    async def callback(self, interaction: discord.Interaction) -> None:
        view: HistoryView = self.view
        view.message = interaction.message
        view.page = max(0, min(view.max_pages - 1, view.page + self.direction))
        if view.mode == "undo":
            page_items = view.get_page_items()
            if page_items:
                view.selected_case_id = get_case_id(page_items[0])
        view.update_components()
        await interaction.response.edit_message(embed=view.build_embed(), view=view)


class UndoReasonModal(discord.ui.Modal, title="Custom Undo Reason"):
    """Custom reason input. The host must expose custom_undo_reason and an
    async on_custom_reason_set(interaction) hook."""

    reason = discord.ui.TextInput(
        label="Undo Reason",
        style=discord.TextStyle.paragraph,
        placeholder="Explain why this punishment is being undone.",
        max_length=500,
    )

    def __init__(self, host):
        super().__init__()
        self.host = host
        if host.custom_undo_reason:
            self.reason.default = host.custom_undo_reason

    async def on_submit(self, interaction: discord.Interaction) -> None:
        custom_reason = self.reason.value.strip()
        if not custom_reason:
            await respond_with_error(interaction, "The undo reason cannot be empty.", scope=SCOPE_MODERATION)
            return

        self.host.custom_undo_reason = custom_reason
        await self.host.on_custom_reason_set(interaction)


class UndoConfirmView(discord.ui.View):
    def __init__(
        self,
        target: Union[discord.Member, discord.User],
        case_id: int,
        undo_reason: str,
        *,
        on_undone: Optional[Callable[[], Awaitable[None]]] = None,
    ):
        super().__init__(timeout=120)
        self.target = target
        self.case_id = case_id
        self.undo_reason = undo_reason
        self.on_undone = on_undone

    @discord.ui.button(label="Confirm Undo", style=discord.ButtonStyle.danger)
    async def confirm(self, interaction: discord.Interaction, button: discord.ui.Button) -> None:
        await interaction.response.edit_message(content="Processing undo...", embed=None, view=None)
        success, removed_record, action_result = await execute_undo_and_log(interaction, self.target, self.case_id, self.undo_reason)
        if not success or not removed_record:
            await interaction.edit_original_response(content=action_result, embed=None, view=None)
            return

        if self.on_undone:
            await self.on_undone()
        await interaction.edit_original_response(
            content=f"**{get_case_label(removed_record)}** was undone.\n{action_result}",
            embed=None,
            view=None,
        )

    @discord.ui.button(label="Cancel", style=discord.ButtonStyle.secondary)
    async def cancel(self, interaction: discord.Interaction, button: discord.ui.Button) -> None:
        await interaction.response.edit_message(content="Undo canceled.", embed=None, view=None)


class HistoryClearConfirmView(discord.ui.View):
    def __init__(self, panel: "HistoryView"):
        super().__init__(timeout=120)
        self.panel = panel

    @discord.ui.button(label="Yes, Clear History", style=discord.ButtonStyle.danger)
    async def confirm(self, interaction: discord.Interaction, button: discord.ui.Button) -> None:
        await interaction.response.edit_message(content="Clearing history...", embed=None, view=None)
        removed_records = await clear_user_history_records(self.panel.user)
        if not removed_records:
            await self.panel.refresh_panel_message()
            await interaction.edit_original_response(content="User has no history to clear.", embed=None, view=None)
            return

        attachment = build_history_archive_attachment(
            "history_clear",
            target_user_id=str(self.panel.user.id),
            actor_id=interaction.user.id,
            payload={"action": "history_clear", "records": removed_records},
        )
        log_embed = build_history_cleared_log_embed(interaction.guild, interaction.user, self.panel.user, removed_records)
        await send_punishment_log(interaction.guild, log_embed, attachments=[attachment])

        await self.panel.refresh_panel_message()
        await interaction.edit_original_response(content="**History has been completely wiped.**", embed=None, view=None)

    @discord.ui.button(label="Cancel", style=discord.ButtonStyle.secondary)
    async def cancel(self, interaction: discord.Interaction, button: discord.ui.Button) -> None:
        await interaction.response.edit_message(content="Clear history canceled.", embed=None, view=None)

class HistoryView(discord.ui.View):
    def __init__(self, user: discord.Member, *, mode: str = "history", selected_case_id: Optional[int] = None, initial_undo_reason: Optional[str] = None):
        super().__init__(timeout=300)
        self.user = user
        self.mode = mode if mode in {"history", "undo"} else "history"
        self.selected_case_id = selected_case_id
        self.custom_undo_reason = str(initial_undo_reason or "").strip() or None
        self.undo_reason_value = UNDO_REASON_PRESETS[0]["value"]
        self.message: Optional[discord.Message] = None
        self.page = 0
        self.items_per_page = 25
        self.history: List[dict] = []
        self.sorted_history: List[dict] = []
        self.max_pages = 1
        self.reload_history()
        if self.mode == "undo" and not self.selected_case_id and self.sorted_history:
            self.selected_case_id = get_case_id(self.sorted_history[0])
        self.ensure_page_for_selected_case()
        self.update_components()

    def reload_history(self) -> None:
        self.history = [record for record in bot.data_manager.punishments.get(str(self.user.id), []) if isinstance(record, dict)]
        self.sorted_history = sorted(
            self.history,
            key=lambda record: (get_case_id(record) or 0, record.get("timestamp", "")),
            reverse=True,
        )
        self.max_pages = max(1, (len(self.sorted_history) + self.items_per_page - 1) // self.items_per_page)
        self.page = max(0, min(self.page, self.max_pages - 1))
        if self.selected_case_id and not any(get_case_id(record) == self.selected_case_id for record in self.sorted_history):
            self.selected_case_id = get_case_id(self.sorted_history[0]) if self.mode == "undo" and self.sorted_history else None

    def ensure_page_for_selected_case(self) -> None:
        if not self.selected_case_id:
            self.page = max(0, min(self.page, self.max_pages - 1))
            return
        for index, record in enumerate(self.sorted_history):
            if get_case_id(record) == self.selected_case_id:
                self.page = index // self.items_per_page
                return
        self.page = max(0, min(self.page, self.max_pages - 1))

    def get_page_items(self) -> List[dict]:
        start = self.page * self.items_per_page
        end = start + self.items_per_page
        return self.sorted_history[start:end]

    def get_selected_record(self) -> Optional[dict]:
        if not self.selected_case_id:
            return None
        for record in self.sorted_history:
            if get_case_id(record) == self.selected_case_id:
                return record
        return None

    def get_current_undo_reason_mode(self) -> str:
        return get_undo_reason_details(self.undo_reason_value, self.custom_undo_reason)[0]

    def get_current_undo_reason_text(self) -> str:
        return get_undo_reason_details(self.undo_reason_value, self.custom_undo_reason)[1]

    async def on_reason_change(self, interaction: discord.Interaction) -> None:
        self.message = interaction.message
        self.update_components()
        await interaction.response.edit_message(embed=self.build_embed(), view=self)

    async def on_custom_reason_set(self, interaction: discord.Interaction) -> None:
        await self.refresh_panel_message()
        await interaction.response.send_message(
            embed=make_confirmation_embed(
                "Undo Reason Saved",
                "> The custom undo reason was saved to the panel.",
                scope=SCOPE_MODERATION,
                guild=interaction.guild,
            ),
            ephemeral=True,
        )

    def build_embed(self) -> discord.Embed:
        if not self.sorted_history:
            return build_no_history_embed(self.user, self.user.guild)
        if self.mode == "undo":
            return build_undo_panel_embed(
                self.user,
                self.history,
                self.get_selected_record(),
                reason_mode=self.get_current_undo_reason_mode(),
                undo_reason=self.get_current_undo_reason_text(),
            )
        return build_history_overview_embed(self.user, self.history)

    async def refresh_panel_message(self) -> None:
        self.reload_history()
        if self.mode == "undo" and not self.selected_case_id and self.sorted_history:
            self.selected_case_id = get_case_id(self.sorted_history[0])
        self.ensure_page_for_selected_case()
        if not self.sorted_history:
            self.stop()
            if self.message:
                await self.message.edit(embed=build_no_history_embed(self.user, self.user.guild), view=None)
            return
        self.update_components()
        if self.message:
            await self.message.edit(embed=self.build_embed(), view=self)

    def update_components(self) -> None:
        self.clear_items()
        if not self.sorted_history:
            return

        if self.mode == "undo":
            self.add_item(UndoCaseSelect(self.get_page_items(), self))
            self.add_item(UndoReasonSelect(self))
            if self.max_pages > 1:
                self.add_item(HistoryNavButton("Previous", discord.ButtonStyle.primary, -1, row=2, disabled=(self.page == 0)))
                self.add_item(discord.ui.Button(label=f"Page {self.page + 1}/{self.max_pages}", disabled=True, style=discord.ButtonStyle.secondary, row=2))
                self.add_item(HistoryNavButton("Next", discord.ButtonStyle.primary, 1, row=2, disabled=(self.page >= self.max_pages - 1)))
            self.add_item(HistoryActionButton("Back to History", discord.ButtonStyle.secondary, "back_to_history", row=3))
            self.add_item(HistoryActionButton("Custom Reason", discord.ButtonStyle.primary, "custom_reason", row=3))
            self.add_item(HistoryActionButton("Undo Selected", discord.ButtonStyle.danger, "undo_selected", row=3, disabled=(self.get_selected_record() is None)))
            return

        self.add_item(HistorySelect(self.get_page_items(), self))
        if self.max_pages > 1:
            self.add_item(HistoryNavButton("Previous", discord.ButtonStyle.primary, -1, row=1, disabled=(self.page == 0)))
            self.add_item(discord.ui.Button(label=f"Page {self.page + 1}/{self.max_pages}", disabled=True, style=discord.ButtonStyle.secondary, row=1))
            self.add_item(HistoryNavButton("Next", discord.ButtonStyle.primary, 1, row=1, disabled=(self.page >= self.max_pages - 1)))
        self.add_item(HistoryActionButton("Undo Punishment", discord.ButtonStyle.danger, "open_undo", row=2))
        self.add_item(HistoryActionButton("Clear History", discord.ButtonStyle.danger, "clear_history", row=2))

    async def handle_action(self, interaction: discord.Interaction, action: str) -> None:
        self.message = interaction.message
        if action == "back_to_history":
            self.mode = "history"
            self.ensure_page_for_selected_case()
            self.update_components()
            await interaction.response.edit_message(embed=self.build_embed(), view=self)
            return

        if action == "open_undo":
            self.mode = "undo"
            if not self.selected_case_id:
                page_items = self.get_page_items()
                if page_items:
                    self.selected_case_id = get_case_id(page_items[0])
                elif self.sorted_history:
                    self.selected_case_id = get_case_id(self.sorted_history[0])
            self.ensure_page_for_selected_case()
            self.update_components()
            await interaction.response.edit_message(embed=self.build_embed(), view=self)
            return

        if action == "custom_reason":
            await interaction.response.send_modal(UndoReasonModal(self))
            return

        if action == "undo_selected":
            record = self.get_selected_record()
            if not record:
                await respond_with_error(interaction, "Select a case to undo first.", scope=SCOPE_MODERATION)
                return

            confirm_embed = build_undo_confirm_embed(self.user, record, self.get_current_undo_reason_text(), guild=interaction.guild)
            confirm_view = UndoConfirmView(
                self.user,
                get_case_id(record) or 0,
                self.get_current_undo_reason_text(),
                on_undone=self.refresh_panel_message,
            )
            await interaction.response.send_message(embed=confirm_embed, view=confirm_view, ephemeral=True)
            return

        if action == "clear_history":
            await interaction.response.send_message(
                embed=make_embed("Confirm Clear", "> Are you sure you want to clear this user's punishment history?", kind="warning", scope=SCOPE_MODERATION, guild=interaction.guild),
                view=HistoryClearConfirmView(self),
                ephemeral=True,
            )
            return


async def setup(bot) -> None:
    pass  # views are registered by importing this module
