from __future__ import annotations

from modules import system


async def setup(bot) -> None:
    bot.tree.add_command(system.list_commands)
    bot.tree.add_command(system.stats)
    bot.tree.add_command(system.directory)
    bot.tree.add_command(system.setup)
    bot.tree.add_command(system.config_cmd)
    bot.tree.add_command(system.publicexecution)
    bot.tree.add_command(system.internals)
    bot.tree.add_command(system.archive)
    bot.tree.add_command(system.unarchive)
    bot.tree.add_command(system.clone)
    bot.tree.add_command(system.rules)
    bot.tree.add_command(system.safety_panel)
    bot.tree.add_command(system.access)
    bot.tree.add_command(system.lockdown)
    bot.tree.add_command(system.unlockdown)
    bot.tree.add_command(system.status_cmd)
    bot.add_command(system.sync)
    bot.add_listener(system.on_guild_role_update)
    bot.add_listener(system.on_member_update)
    bot.add_listener(system.on_message)
    bot.add_listener(system.on_ready)
    bot.tree.on_error = system.on_app_command_error
