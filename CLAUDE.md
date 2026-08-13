# CLAUDE.md

This project's instructions for all AI coding agents live in **AGENTS.md**, the
cross-tool source of truth. It is imported below so Claude Code loads it in full.

@AGENTS.md

## Claude Code notes

- Follow the **automatic merge and deploy** policy in AGENTS.md: after required
  CI is green, squash-merge, restart production, and verify it is running unless
  the user explicitly requested a hold or no deployment.
- Project memory is machine-local at `~/.claude/projects/<project>/memory/` — it
  is not committed and does not travel with the repo. A repo-level `.claude/memory/`
  folder is **not** auto-loaded, so don't create one expecting it to be read.
- Prefer plan mode before large, multi-file changes.
- For substantial Minecraft integrations, it is acceptable to study, import,
  and adapt suitably licensed open-source plugins such as TAB or DiscordSRV
  instead of recreating established behavior. Verify license compatibility,
  pin the upstream source, retain required attribution, and document material
  changes rather than copying incompatible or untracked code.
