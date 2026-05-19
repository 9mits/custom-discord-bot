from __future__ import annotations

from modules import roles


async def setup(bot) -> None:
    bot.tree.add_command(roles.role_cmd)
    bot.tree.add_command(roles.role_manage)
    bot.tree.add_command(roles.role_settings)
    bot.tree.add_command(roles.help_cmd)
