from __future__ import annotations

from modules import moderation


async def setup(bot) -> None:
    bot.tree.add_command(moderation.ModGroup())
    bot.tree.add_command(moderation.punish_context)
    bot.tree.add_command(moderation.history_context)
    bot.add_listener(moderation.on_raw_reaction_add)
