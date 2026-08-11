"""Single-response interaction helper with acknowledgement metrics."""

from __future__ import annotations

from typing import Any, Optional


class InteractionResponder:
    def __init__(self, interaction, *, metrics=None) -> None:
        self.interaction = interaction
        self.metrics = metrics or getattr(getattr(interaction, "client", None), "metrics", None)

    def _record_acknowledgement(self) -> None:
        if self.metrics is not None:
            self.metrics.record_acknowledgement(self.interaction.id)

    async def defer(self, *, ephemeral: bool = True, thinking: bool = False) -> bool:
        if self.interaction.response.is_done():
            return False
        await self.interaction.response.defer(ephemeral=ephemeral, thinking=thinking)
        self._record_acknowledgement()
        return True

    async def send(
        self,
        *,
        content: Optional[str] = None,
        embed=None,
        view=None,
        ephemeral: bool = True,
        **kwargs: Any,
    ) -> bool:
        if self.interaction.response.is_done():
            await self.interaction.followup.send(
                content=content,
                embed=embed,
                view=view,
                ephemeral=ephemeral,
                **kwargs,
            )
            return False
        await self.interaction.response.send_message(
            content=content,
            embed=embed,
            view=view,
            ephemeral=ephemeral,
            **kwargs,
        )
        self._record_acknowledgement()
        return True

    async def edit(self, *, content=None, embed=None, view=None, **kwargs: Any) -> bool:
        if self.interaction.response.is_done():
            await self.interaction.edit_original_response(
                content=content,
                embed=embed,
                view=view,
                **kwargs,
            )
            return False
        await self.interaction.response.edit_message(
            content=content,
            embed=embed,
            view=view,
            **kwargs,
        )
        self._record_acknowledgement()
        return True

