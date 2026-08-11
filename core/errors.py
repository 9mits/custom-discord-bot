"""Typed operational errors with safe user-facing messages."""

from __future__ import annotations

import uuid


class BotOperationError(RuntimeError):
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

