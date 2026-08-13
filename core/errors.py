"""Typed operational errors with safe user-facing messages."""

from __future__ import annotations

import uuid

import discord
from discord import app_commands

from .constants import BRAND_NAME, EMBED_PALETTE


class BotOperationError(app_commands.AppCommandError):
    title = "Action Failed"
    public_message = "The action could not be completed."

    def __init__(self, message: str = "", *, correlation_id: str | None = None) -> None:
        super().__init__(message or self.public_message)
        self.correlation_id = correlation_id or uuid.uuid4().hex[:12]


class CallerPermissionError(BotOperationError):
    title = "Access Denied"
    public_message = "You do not have permission to use this action."


class BotPermissionError(BotOperationError):
    title = "Bot Permission Missing"
    public_message = "I do not have the Discord permissions required for this action."


class HierarchyError(BotOperationError):
    title = "Role Hierarchy Blocked"
    public_message = "The role hierarchy prevents this action."


class InvalidConfigurationError(BotOperationError):
    title = "Configuration Needed"
    public_message = "This feature is not configured correctly yet."


class StaleStateError(BotOperationError):
    title = "State Changed"
    public_message = "This control is out of date. Reopen it and try again."


class OverloadError(BotOperationError):
    title = "System Busy"
    public_message = "The bot is handling other heavy work. Try again shortly."


class RateLimitError(BotOperationError):
    title = "Rate Limited"
    public_message = "This action is temporarily rate limited. Try again shortly."


class InternalFailure(BotOperationError):
    title = "Internal Error"
    public_message = "The bot hit an unexpected error while processing this action."


_CLASSIFICATION_ATTR = "_mgx_operation_error"


def classify_operation_error(error: BaseException) -> BotOperationError:
    original = error.original if isinstance(error, app_commands.CommandInvokeError) else error

    # Cache the result on the exception so repeated classification of the same
    # failure keeps one correlation id: the reference shown to the user, the one in
    # the application log, and the one in the command audit row all match.
    for candidate in (error, original):
        cached = getattr(candidate, _CLASSIFICATION_ATTR, None)
        if isinstance(cached, BotOperationError):
            return cached

    if isinstance(original, BotOperationError):
        classified = original
    elif isinstance(error, app_commands.CheckFailure):
        classified = CallerPermissionError(str(error))
    elif isinstance(original, discord.Forbidden):
        classified = BotPermissionError(str(original))
    elif isinstance(original, discord.HTTPException) and original.status == 429:
        classified = RateLimitError(str(original))
    else:
        classified = InternalFailure(str(original))

    for candidate in (error, original):
        try:
            setattr(candidate, _CLASSIFICATION_ATTR, classified)
        except (AttributeError, TypeError):
            pass
    return classified


async def respond_operation_error(interaction: discord.Interaction, error: BotOperationError) -> None:
    message = error.public_message
    if isinstance(error, InternalFailure):
        message += f" Reference: `{error.correlation_id}`."
    embed = discord.Embed(
        title=error.title,
        description=f"> {message}",
        color=EMBED_PALETTE["danger"],
    )
    embed.set_footer(text=BRAND_NAME)
    if interaction.response.is_done():
        await interaction.followup.send(embed=embed, ephemeral=True)
    else:
        await interaction.response.send_message(embed=embed, ephemeral=True)
