"""The owner console's contract with the page and with the plugin.

Run from the repo root:
    python devblog/tests/test_owner_console.py

There is no JavaScript runtime on the build machine, so nothing here executes the
console. What it does instead is hold the two seams where the console breaks
*silently* rather than loudly:

  * an element id it reaches for that the page does not define — `getElementById`
    returns null and the panel simply does not appear, with nothing in the log;
  * a snapshot field it reads that the plugin never writes — every row renders as
    `undefined`, which looks like data rather than a bug.

Both are cross-file, so no single-file test can see them.
"""

from __future__ import annotations

import re
import sys
import unittest
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
sys.path.insert(0, str(ROOT / "devblog"))

import build  # noqa: E402
import theme  # noqa: E402

CONSOLE_JS = (ROOT / "devblog" / "static" / "owner-console.js").read_text(encoding="utf-8")
CONTROL_MD = (ROOT / "devblog" / "pages" / "control.md").read_text(encoding="utf-8")
STORE_JAVA = (
    ROOT / "minecraft-bridge" / "src" / "main" / "java" / "bot" / "mgx" / "accessbridge"
    / "GameVariableStore.java"
).read_text(encoding="utf-8")
# The snapshot has two producers: the variable registry, and the catalogue of what an
# owner added or removed. A field check that knows about only one reports the other's
# fields as missing.
CATALOG_JAVA = (
    ROOT / "minecraft-bridge" / "src" / "main" / "java" / "bot" / "mgx" / "accessbridge"
    / "CustomCatalogStore.java"
).read_text(encoding="utf-8")
# Four producers now: the variable registry, the catalogue of what an owner changed,
# the auction listings, and the activity feed. A check that knows about some of them
# reports the others' fields as missing.
AUCTION_JAVA = (
    ROOT / "minecraft-bridge" / "src" / "main" / "java" / "bot" / "mgx" / "accessbridge"
    / "AuctionStore.java"
).read_text(encoding="utf-8")
FEED_JAVA = (
    ROOT / "minecraft-bridge" / "src" / "main" / "java" / "bot" / "mgx" / "accessbridge"
    / "ActivityFeed.java"
).read_text(encoding="utf-8")
HISTORY_JAVA = (
    ROOT / "minecraft-bridge" / "src" / "main" / "java" / "bot" / "mgx" / "accessbridge"
    / "ConfigHistory.java"
).read_text(encoding="utf-8")


class ConsolePageContractTests(unittest.TestCase):
    def test_every_element_the_console_reaches_for_exists_on_the_page(self):
        wanted = set(re.findall(r'byId\("([a-z0-9-]+)"\)', CONSOLE_JS))
        # Guards the scrape itself, without pinning to an id a rename may legitimately
        # move — a broken pattern yields nothing and would otherwise pass silently.
        self.assertGreater(len(wanted), 5, "the id scrape stopped matching")
        defined = set(re.findall(r'id="([a-z0-9-]+)"', CONTROL_MD))
        # Elements the console builds itself — the sign-out button, the fields inside a
        # dialog body. Scraped rather than listed, so an id it both writes and reads
        # stays self-consistent without this test needing to know about each one.
        creates = set(re.findall(r'id=\\?"([a-z0-9-]+)\\?"', CONSOLE_JS))
        creates |= set(re.findall(r"""id='([a-z0-9-]+)'""", CONSOLE_JS))
        missing = wanted - defined - creates
        self.assertEqual(
            set(), missing,
            "owner-console.js reaches for element(s) control.md never defines: %s" % sorted(missing),
        )

    def _control_page(self):
        return next(
            page for page in build.load_pages(include_private=True) if page.slug == "control"
        )

    def test_the_control_page_loads_the_console_and_not_the_leaderboard_script(self):
        page = self._control_page()
        self.assertEqual(page.layout, "console")
        html = theme.render_page(page, "<div></div>", "../", "https://example.test")
        self.assertIn("assets/owner-console.js", html)
        self.assertNotIn("assets/server-dashboard.js", html)

    def test_the_console_page_never_reaches_the_public_build(self):
        # It is a control surface for one person. A public build must not carry it at
        # all — not merely gate it behind a sign-in button that 404s.
        self.assertTrue(self._control_page().private)
        self.assertNotIn("control", [page.slug for page in build.load_pages()])

    def test_the_hidden_attribute_actually_hides(self):
        """A flex or grid component toggled with `el.hidden` needs the global reset.

        Without it the author `display` wins, so the element renders empty and on top
        of the page from load — which is how both the review dialog and the draft bar
        shipped visible and blocking. The theme used to patch this per element, which
        is precisely why a new one was missed.
        """
        # assertTrue, not assertIn: a failing assertIn prints the whole stylesheet.
        self.assertTrue(
            "[hidden] { display: none !important; }" in theme.STYLESHEET,
            "the global [hidden] reset is gone, so anything toggled with el.hidden that "
            "sets its own display will render visible and empty",
        )

    def test_no_component_patches_hidden_by_itself(self):
        # A per-element patch means someone worked around the missing reset instead of
        # relying on it, and the next component added will not be covered.
        patches = re.findall(r"^[^\n{]*\[hidden\][^\n{]*\{", theme.STYLESHEET, re.M)
        stray = [p.strip() for p in patches if not p.strip().startswith("[hidden]")]
        self.assertEqual([], stray, "these should rely on the global reset: %s" % stray)

    def test_every_element_toggled_by_script_is_hidden_in_the_markup(self):
        # `el.hidden = false` on something the page never hid is a no-op that reads as
        # working, so the element is simply always on screen.
        shown = set(re.findall(r'byId\("([a-z0-9-]+)"\)\.hidden = false', CONSOLE_JS))
        for element in shown:
            self.assertRegex(
                CONTROL_MD, r'id="%s"[^>]*hidden' % re.escape(element),
                "%s is revealed by the console but never hidden in control.md" % element,
            )

    def test_every_guided_task_points_at_settings_that_exist(self):
        """A task naming a key the plugin does not define renders an empty panel.

        The failure is silent — the task opens, says it touches five settings, and shows
        none of them — so it has to be caught here rather than by looking.
        """
        task_block = CONSOLE_JS.split("var TASKS = [", 1)[1].split("\n  ];", 1)[0]
        named = set(re.findall(r'"([a-z][a-z0-9\-]*(?:\.[a-z0-9\-]+)+)"', task_block))
        self.assertGreater(len(named), 20, "the task key scrape stopped matching")

        defined = set(re.findall(r'\b(?:integer|bool|choice|text|decimal)\(\s*\n?\s*"([^"]+)"',
                                 STORE_JAVA))
        # Keys built in loops carry a prefix rather than a literal; keep the literals only.
        missing = {key for key in named if key not in defined
                   and not any(key.startswith(p) for p in
                               ("crate.", "airdrop.", "online-rewards.", "huge-amethyst.",
                                "events.", "shop.", "chaos.", "cosmetics.", "world.",
                                "amethyst-events.", "crates.", "auction.", "bounty."))}
        self.assertEqual(
            set(), missing,
            "guided tasks name setting(s) nothing defines: %s" % sorted(missing))

    def test_every_settings_page_has_an_introduction(self):
        # A page that opens straight into a grid of controls is the thing this rebuild
        # exists to stop.
        # Scoped to the PAGES array: the frequency presets share its literal shape, so a
        # loose scrape reports "very_common" as an undocumented page.
        page_block = CONSOLE_JS.split("var PAGES = [", 1)[1].split("\n  ];", 1)[0]
        pages = set(re.findall(r'\{id: "([a-z_]+)"', page_block))
        pages -= {"overview", "history", "actions", "activity", "auction"}
        self.assertGreater(len(pages), 15, "the page scrape stopped matching")
        intro_block = CONSOLE_JS.split("var PAGE_INTROS = {", 1)[1].split("\n  };", 1)[0]
        described = set(re.findall(r'^\s*([a-z_]+):', intro_block, re.M))
        self.assertEqual(
            set(), pages - described,
            "settings page(s) with no introduction: %s" % sorted(pages - described))

    def test_every_class_the_console_emits_is_styled(self):
        # A class with no rule renders as unstyled text in the middle of a panel, which
        # reads as a broken page rather than a missing stylesheet.
        emitted = set(re.findall(r'class="(con-[a-z0-9 -]+)"', CONSOLE_JS))
        names = {name for value in emitted for name in value.split() if name.startswith("con-")}
        # Applied conditionally alongside a base class that is styled.
        state_only = {"con-", "con-icon"}
        unstyled = {
            name for name in names - state_only
            if ("." + name) not in theme.STYLESHEET
        }
        self.assertEqual(
            set(), unstyled,
            "owner-console.js emits unstyled class(es): %s" % sorted(unstyled),
        )


class ConsoleSnapshotContractTests(unittest.TestCase):
    """What the console reads off a snapshot must be what the plugin writes into one."""

    def _written_fields(self, source: str) -> set[str]:
        return set(
            re.findall(r'(?:addProperty|add)\(\s*"([a-z_]+)"', source)
            + re.findall(r'addProperty\(\s*[A-Za-z]+,\s*"([a-z_]+)"', source)
            + re.findall(r'addValue\([a-z]+,\s*"([a-z_]+)"', source)
            + re.findall(r'row\.addProperty\("([a-z_]+)"', source)
        )

    def test_every_row_field_the_console_reads_is_written_by_the_plugin(self):
        # row.foo and row["foo"], minus the JavaScript built-ins and locals.
        read = set(re.findall(r'\brow(?:\.([a-z_]+)\b|\["([a-z_]+)"\])', CONSOLE_JS))
        wanted = {dotted or quoted for dotted, quoted in read}
        wanted -= {"filter", "map", "forEach", "some", "reduce", "indexOf", "slice", "push"}
        self.assertIn("control", wanted, "the field scrape stopped matching")

        written = (
            self._written_fields(STORE_JAVA)
            | self._written_fields(CATALOG_JAVA)
            | self._written_fields(AUCTION_JAVA)
            | self._written_fields(FEED_JAVA)
        )
        missing = wanted - written
        self.assertEqual(
            set(), missing,
            "the console reads snapshot field(s) no store ever writes: %s"
            % sorted(missing),
        )

    def test_the_snapshot_carries_the_three_collections_the_console_expects(self):
        for collection in ("variables", "tables", "history"):
            self.assertIn(
                'root.add("%s"' % collection, STORE_JAVA,
                "the snapshot no longer carries '%s', which the console reads" % collection,
            )

    def test_table_summaries_carry_what_a_distribution_editor_needs(self):
        for field in ("table", "total_weight", "entries"):
            self.assertIn('addProperty("%s"' % field, STORE_JAVA)

    def test_history_entries_carry_what_the_rollback_view_needs(self):
        for field in ("id", "at", "actor", "change_count"):
            self.assertIn('addProperty("%s"' % field, HISTORY_JAVA)
        self.assertIn('entry.add("changes"', HISTORY_JAVA)
        for field in ("before", "after"):
            self.assertIn('addValue(row, "%s"' % field, HISTORY_JAVA)

    def test_the_console_filters_out_values_the_plugin_could_not_classify(self):
        # SettingMetadata falls back to an "unclassified" group rather than throwing.
        # The console must not render that bucket as if it were a real page.
        self.assertIn('row.group !== "unclassified"', CONSOLE_JS)
        self.assertIn("UNCLASSIFIED", (
            ROOT / "minecraft-bridge" / "src" / "main" / "java" / "bot" / "mgx"
            / "accessbridge" / "SettingMetadata.java"
        ).read_text(encoding="utf-8"))

    def test_every_group_the_plugin_can_emit_has_a_page(self):
        metadata = (
            ROOT / "minecraft-bridge" / "src" / "main" / "java" / "bot" / "mgx"
            / "accessbridge" / "SettingMetadata.java"
        ).read_text(encoding="utf-8")
        block = metadata.split("enum Group {", 1)[1].split("}", 1)[0]
        groups = {name.lower() for name in re.findall(r"^\s*([A-Z_]+)\(", block, re.M)}
        groups.discard("unclassified")
        pages = set(re.findall(r'\{id: "([a-z_]+)"', CONSOLE_JS))
        missing = groups - pages
        self.assertEqual(
            set(), missing,
            "the plugin can group values under page(s) the console does not draw: %s"
            % sorted(missing),
        )


if __name__ == "__main__":
    unittest.main(verbosity=2)
