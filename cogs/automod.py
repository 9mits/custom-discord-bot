from __future__ import annotations

from modules import automod


async def setup(bot) -> None:
    bot.tree.add_command(automod.automod_cmd)
    bot.add_listener(automod.on_automod_action)
    bot.add_listener(automod.on_socket_raw_receive)
