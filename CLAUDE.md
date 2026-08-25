# CLAUDE.md

This project's instructions for all AI coding agents live in **AGENTS.md**, the
cross-tool source of truth. It is imported below so Claude Code loads it in full.

@AGENTS.md

## Claude Code notes

- Follow the **automatic merge and deploy** policy in AGENTS.md: after required
  CI is green, squash-merge, restart the BisectHosting Discord production panel,
  and verify it is running unless the user explicitly requested a hold or no
  deployment. GravelHost Minecraft production is the confirmation-gated exception.
- **Minecraft is test-first, blog-gated, and production-confirmed.** Every merged
  `minecraft-bridge/` change must be built and installed automatically with
  `python scripts/testserver.py deploy`. Never upload a jar, config, or resource
  pack to GravelHost if it contains a Minecraft-affecting change after the
  `covers:` commit of the newest published dev-blog `category: Update` page;
  those changes are unreleased even if the user approved the test build.
  Confirmation cannot override this blog boundary. Once the build passes both
  gates, upload the approved `runtime/testserver/plugins/MGXAccessBridge.jar`
  without rebuilding it, verify it against `runtime/testserver/test-build.json`,
  swap it atomically, and state that the Minecraft restart is the user's step.
  If a post-blog build is found live, immediately restore and verify the exact
  latest blog-covered build. See non-negotiable 2 and **Hosting — GravelHost** in
  AGENTS.md.
- Project memory is machine-local at `~/.claude/projects/<project>/memory/` — it
  is not committed and does not travel with the repo. A repo-level `.claude/memory/`
  folder is **not** auto-loaded, so don't create one expecting it to be read.
- Prefer plan mode before large, multi-file changes.
- Treat **Testing policy — verification is automatic; live gameplay is explicit**
  in AGENTS.md as mandatory. Do not launch Minecraft/VibeCraft for every update.
  Installing every merged plugin jar into `runtime/testserver/` is mandatory but
  is not permission to launch Paper or a client.
  When the user explicitly asks to test a Minecraft update, use the real local
  client(s), exercise only the affected feature matrix, inspect actual state and
  logs, and never call a compile or unit-test-only run an in-game pass.
- The **dev blog workflow** in AGENTS.md is the whole procedure for posting a
  server update: `devblog/blog.py` decides the range, scaffolds, validates and
  publishes. Do not drive git by hand for a post.
- Before drafting any dev-blog post, give the user the exact, numbered
  feature-specific screenshot brief required by AGENTS.md — filenames, setup,
  action/menu, subjects, `/mgxadmin devblog` settings, HUD state and framing.
  Treat `cover:` as designed editorial artwork, never a raw gameplay capture;
  keep UI-heavy screenshots inside the relevant article section.
  Match the supplied reference pages' punchy reveal voice, not only their
  headings, emoji and bold formatting.
- For substantial Minecraft integrations, it is acceptable to study, import,
  and adapt suitably licensed open-source plugins such as TAB or DiscordSRV
  instead of recreating established behavior. Verify license compatibility,
  pin the upstream source, retain required attribution, and document material
  changes rather than copying incompatible or untracked code.
