/**
 * The owner console.
 *
 * Replaces a grid of 380 number boxes labelled with their own database keys. The
 * organising idea is that a value is shown as the thing it controls: a weight is a row
 * of a distribution and is edited as a share of it, a "one in N" is shown as odds and a
 * percentage together, a duration is a duration. Nothing is published until it is
 * reviewed, and the server is asked whether a change set is legal before it is offered.
 */
(function () {
  "use strict";

  var PAGES = [
    {id: "overview", label: "Overview"},
    {id: "crates", label: "Crates"},
    {id: "airdrops", label: "Airdrops"},
    {id: "online_rewards", label: "Online Rewards"},
    {id: "huge_amethyst", label: "Huge Amethyst"},
    {id: "admin_events", label: "Admin Events"},
    {id: "amethyst_mobs", label: "Amethyst Mobs"},
    {id: "event_multipliers", label: "Event Multipliers"},
    {id: "event_schedule", label: "Event Schedule"},
    {id: "history", label: "History"}
  ];

  var TABLE_TITLES = {
    "crate.default": "Default Crate",
    "crate.amethyst": "Limited Amethyst Crate",
    "crate.shard": "Shard Crate",
    "airdrop.rarity": "Which rarity an Airdrop is",
    "airdrop.loot.common": "Common Airdrop contents",
    "airdrop.loot.rare": "Rare Airdrop contents",
    "airdrop.loot.legendary": "Legendary Airdrop contents",
    "airdrop.loot.mythic": "Mythic Airdrop contents"
  };

  var TABLE_BLURBS = {
    "crate.default": "Every reward the Default Crate can give, and how often.",
    "crate.amethyst": "The limited crate's reward pool.",
    "crate.shard": "What Shards buy.",
    "airdrop.rarity": "Each Airdrop rolls one of these before its contents.",
    "airdrop.loot.common": "Each material roll in a Common Airdrop picks one of these.",
    "airdrop.loot.rare": "Each material roll in a Rare Airdrop picks one of these.",
    "airdrop.loot.legendary": "Each material roll in a Legendary Airdrop picks one of these.",
    "airdrop.loot.mythic": "Each material roll in a Mythic Airdrop picks one of these."
  };

  var DRAFT_STORAGE = "mgx-console-draft";

  /**
   * How often something should show up, in words.
   *
   * A weight means nothing without the rest of its table, so adding an entry asks for a
   * frequency and works the weight out from whatever the table currently totals. The
   * percentages are the share the new entry takes once it is in.
   */
  var FREQUENCIES = [
    {id: "very_common", label: "Very common", share: 25, blurb: "about 1 in 4"},
    {id: "common", label: "Common", share: 12, blurb: "about 1 in 8"},
    {id: "uncommon", label: "Uncommon", share: 5, blurb: "about 1 in 20"},
    {id: "rare", label: "Rare", share: 1.5, blurb: "about 1 in 65"},
    {id: "very_rare", label: "Very rare", share: 0.4, blurb: "about 1 in 250"},
    {id: "ultra_rare", label: "Ultra rare", share: 0.05, blurb: "about 1 in 2,000"}
  ];

  var CATEGORIES = [
    {id: "RESOURCE", label: "Resources"},
    {id: "TREASURE", label: "Treasure"},
    {id: "TRIAL", label: "Trial Chamber"},
    {id: "POTION", label: "Potions"},
    {id: "ENCHANTMENT", label: "Enchantments"},
    {id: "COSMETIC", label: "Cosmetics"}
  ];

  var CRATE_OF_TABLE = {
    "crate.default": "default",
    "crate.amethyst": "amethyst",
    "crate.shard": "shard"
  };

  var state = {
    snapshot: null,
    rows: [],
    byKey: {},
    page: "overview",
    draft: {},
    findings: {},
    search: "",
    validating: false,
    publishing: false,
    catalog: null,
    materials: [],
    adding: null
  };

  function byId(id) { return document.getElementById(id); }

  function escapeHtml(value) {
    return String(value == null ? "" : value).replace(/[&<>'"]/g, function (character) {
      return {"&": "&amp;", "<": "&lt;", ">": "&gt;", "'": "&#39;", '"': "&quot;"}[character];
    });
  }

  function toast(message, failed) {
    var node = byId("live-toast");
    if (!node) return;
    node.textContent = message;
    node.className = "live-toast show" + (failed ? " error" : "");
    window.clearTimeout(toast.timer);
    toast.timer = window.setTimeout(function () { node.className = "live-toast"; }, 5000);
  }

  async function api(url, options) {
    var response = await window.fetch(url, Object.assign({cache: "no-store"}, options || {}));
    var body = null;
    try { body = await response.json(); } catch (notJson) { body = null; }
    if (!response.ok && !(body && body.findings)) {
      throw new Error((body && body.message) || "Request failed (" + response.status + ")");
    }
    return body;
  }

  function post(url, payload) {
    return api(url, {
      method: "POST",
      headers: {"Content-Type": "application/json", "X-MGX-CSRF": state.csrf || ""},
      body: JSON.stringify(payload)
    });
  }

  /* ---------- draft state ---------- */

  function draftedValue(key) {
    var row = state.byKey[key];
    if (!row) return 0;
    var edit = state.draft[key];
    if (!edit) return row.value;
    return edit.reset ? row["default"] : edit.value;
  }

  function isDirty(key) {
    var edit = state.draft[key];
    if (!edit) return false;
    return draftedValue(key) !== state.byKey[key].value;
  }

  function dirtyKeys() {
    return Object.keys(state.draft).filter(isDirty);
  }

  function setDraft(key, value) {
    var row = state.byKey[key];
    if (!row) return;
    if (value === row.value) {
      delete state.draft[key];
    } else {
      state.draft[key] = {value: value};
    }
    saveDraft();
    scheduleValidate();
    render();
  }

  function resetToDefault(key) {
    var row = state.byKey[key];
    if (!row) return;
    if (row["default"] === row.value) {
      delete state.draft[key];
    } else {
      state.draft[key] = {reset: true, value: row["default"]};
    }
    saveDraft();
    scheduleValidate();
    render();
  }

  function discardDraft() {
    state.draft = {};
    state.findings = {};
    saveDraft();
    render();
  }

  function saveDraft() {
    try {
      window.localStorage.setItem(DRAFT_STORAGE, JSON.stringify(state.draft));
    } catch (unavailable) {
      // A private window or blocked site data. The draft simply does not survive a
      // reload; everything else keeps working.
    }
  }

  function loadDraft() {
    try {
      var stored = JSON.parse(window.localStorage.getItem(DRAFT_STORAGE) || "{}");
      if (stored && typeof stored === "object") {
        // Drop anything the server no longer knows, or that already matches live —
        // a stale draft must never resurrect a setting that moved underneath it.
        Object.keys(stored).forEach(function (key) {
          if (state.byKey[key]) state.draft[key] = stored[key];
        });
        dropSettledEdits();
      }
    } catch (unreadable) {
      state.draft = {};
    }
  }

  function dropSettledEdits() {
    Object.keys(state.draft).forEach(function (key) {
      if (!isDirty(key)) delete state.draft[key];
    });
  }

  function editsPayload() {
    return dirtyKeys().map(function (key) {
      var edit = state.draft[key];
      return edit.reset
        ? {key: key, reset: true}
        : {key: key, value: String(edit.value)};
    });
  }

  /* ---------- server validation ---------- */

  function scheduleValidate() {
    window.clearTimeout(scheduleValidate.timer);
    scheduleValidate.timer = window.setTimeout(validateDraft, 400);
  }

  async function validateDraft() {
    var edits = editsPayload();
    if (!edits.length) {
      state.findings = {};
      renderDraftBar();
      return;
    }
    state.validating = true;
    renderDraftBar();
    try {
      var result = await post("/api/settings/validate", {edits: edits});
      var found = {};
      (result.findings || []).forEach(function (finding) {
        found[finding.key] = finding.message;
      });
      state.findings = found;
    } catch (error) {
      // A validation that cannot reach the server is not a rejection. Publishing is
      // still gated server-side, so the honest state is "unknown", not "invalid".
      state.findings = {};
    }
    state.validating = false;
    render();
  }

  /* ---------- distributions ---------- */

  var JACKPOT_KEY = "crate.hidden-amethyst-one-in";
  // The hidden jackpot is rolled before the ordinary table, and only for these two.
  var JACKPOT_TABLES = ["crate.amethyst", "crate.shard"];

  /**
   * The jackpot as a row of the table it actually competes with.
   *
   * It is not a weight — it is a separate "one in N" roll that happens first — so it
   * cannot sit in the weight column. It still belongs in the list: it is one of the
   * things a player can open the crate and receive, and showing it in a card of its own
   * left the table claiming chances that did not account for it.
   */
  function jackpotRow(table) {
    if (JACKPOT_TABLES.indexOf(table) < 0) return null;
    var row = state.byKey[JACKPOT_KEY];
    if (!row) return null;
    // One variable shown in two tables. The odds are identical in both; recording which
    // table drew it keeps chanceOf from having to guess.
    row.jackpotTable = table;
    return row;
  }

  function isJackpot(row) {
    return !!row && row.key === JACKPOT_KEY;
  }

  /** The share the jackpot takes before the weighted table is consulted at all. */
  function jackpotShare(table, useDraft) {
    var row = jackpotRow(table);
    if (!row) return 0;
    var oneIn = useDraft ? draftedValue(row.key) : row.value;
    return oneIn > 0 ? 100 / oneIn : 0;
  }

  function weightedRows(table) {
    return state.rows.filter(function (row) { return row.table === table; });
  }

  /** Every row shown in a table's editor, jackpot included where there is one. */
  function tableRows(table) {
    var rows = weightedRows(table);
    var jackpot = jackpotRow(table);
    return jackpot ? rows.concat([jackpot]) : rows;
  }

  function tableTotal(table, useDraft) {
    return weightedRows(table).reduce(function (sum, row) {
      return sum + (useDraft ? draftedValue(row.key) : row.value);
    }, 0);
  }

  function chanceOf(row, useDraft) {
    if (isJackpot(row)) {
      return jackpotShare(row.jackpotTable || "crate.amethyst", useDraft);
    }
    var total = tableTotal(row.table, useDraft);
    if (total <= 0) return 0;
    // The weighted roll only happens when the jackpot roll misses, so every ordinary
    // chance is scaled by the share the jackpot took first. Without this the column
    // summed past 100% and quietly overstated every reward.
    var remaining = 1 - jackpotShare(row.table, useDraft) / 100;
    return ((useDraft ? draftedValue(row.key) : row.value) / total) * 100 * remaining;
  }

  /**
   * The weight that gives this row the requested share.
   *
   * Only the edited row moves. Solving w / (w + others) = q keeps every other weight
   * exactly as the owner left it, so one edit never silently rewrites the rest of the
   * table — their percentages shift because the total did, which is the truth.
   */
  function weightForChance(row, percent) {
    var others = tableTotal(row.table, true) - draftedValue(row.key);
    var share = Math.min(Math.max(Number(percent), 0), 99.999) / 100;
    if (others <= 0) return Math.max(row.minimum || 1, draftedValue(row.key));
    var weight = Math.round((share * others) / (1 - share));
    return Math.min(Math.max(weight, row.minimum || 0), row.maximum || 10000000);
  }

  /** The jackpot's own name, with the crate-facing framing stripped. */
  function jackpotName(row) {
    return String(row.label || "").replace(/\s*chance$/i, "");
  }

  /** Enough decimals to show a one-in-500,000 without rounding it to zero. */
  function trimChance(percent) {
    if (percent >= 1) return percent.toFixed(3);
    if (percent >= 0.001) return percent.toFixed(5);
    return percent.toFixed(8);
  }

  function formatChance(percent) {
    if (percent >= 10) return percent.toFixed(1) + "%";
    if (percent >= 1) return percent.toFixed(2) + "%";
    if (percent >= 0.01) return percent.toFixed(3) + "%";
    if (percent <= 0) return "0%";
    return percent.toFixed(5) + "%";
  }

  function oneInFrom(percent) {
    if (percent <= 0) return "never";
    return "1 in " + Math.round(100 / percent).toLocaleString();
  }

  /* ---------- naming ---------- */

  var MATERIAL = /^airdrop\.loot\.([a-z0-9_]+)\.[a-z]+-weight$/;
  var RARITY = /^airdrop\.rarity\.([a-z]+)\.weight$/;

  function titleCase(text) {
    return String(text).replace(/_/g, " ").toLowerCase().replace(/\b[a-z]/g, function (c) {
      return c.toUpperCase();
    });
  }

  /** What to call a row inside its table, without repeating the table's own name. */
  function rowName(row) {
    var material = MATERIAL.exec(row.key);
    if (material) return titleCase(material[1]);
    var rarity = RARITY.exec(row.key);
    if (rarity) return titleCase(rarity[1]);
    return row.label;
  }

  function rowIcon(row) {
    var material = MATERIAL.exec(row.key);
    if (!material) return '<span class="con-icon-gap" aria-hidden="true"></span>';
    // A missing sprite becomes an empty box of the same size rather than nothing, so
    // one row without art does not shift its name out of the column.
    return '<img class="con-icon" src="/assets/minecraft-items/' + escapeHtml(material[1]) +
      '.png" alt="" loading="lazy" ' +
      'onerror="this.replaceWith(Object.assign(document.createElement(\'span\'),' +
      '{className:\'con-icon-gap\'}))">';
  }

  /* ---------- controls ---------- */

  function unitLabel(row) {
    if (row.unit === "one in") return "";
    return row.unit || "";
  }

  function numberField(row, value, extra) {
    return '<input class="con-number" type="number" data-key="' + escapeHtml(row.key) + '"' +
      (extra || "") +
      ' value="' + escapeHtml(value) + '"' +
      (row.minimum !== undefined ? ' min="' + escapeHtml(row.minimum) + '"' : "") +
      (row.maximum !== undefined ? ' max="' + escapeHtml(row.maximum) + '"' : "") +
      ' inputmode="numeric">';
  }

  function controlFor(row) {
    var value = draftedValue(row.key);
    if (row.control === "toggle") {
      return '<label class="con-switch"><input type="checkbox" data-key="' +
        escapeHtml(row.key) + '" data-toggle' + (value ? " checked" : "") +
        '><span class="con-track" aria-hidden="true"></span><span class="con-switch-text">' +
        (value ? "Enabled" : "Disabled") + "</span></label>";
    }
    if (row.control === "odds") {
      var percent = value > 0 ? 100 / value : 0;
      return '<div class="con-odds">' + numberField(row, value) +
        '<span class="con-odds-read">1 in <strong>' + Number(value).toLocaleString() +
        "</strong> &middot; " + formatChance(percent) + "</span></div>";
    }
    if (row.control === "rate") {
      return '<div class="con-odds">' + numberField(row, value) +
        '<span class="con-odds-read">' + formatChance(value / 100) +
        " &middot; " + escapeHtml(value) + " per 10,000</span></div>";
    }
    if (row.control === "multiplier") {
      return '<div class="con-odds">' + numberField(row, value) +
        '<span class="con-odds-read"><strong>' + escapeHtml(value) +
        "&times;</strong> &middot; players are told this figure</span></div>";
    }
    if (row.control === "percent") {
      return '<div class="con-odds">' + numberField(row, value) +
        '<span class="con-odds-read">' + escapeHtml(value) + "% of full health</span></div>";
    }
    if (row.control === "duration" || row.control === "distance") {
      return '<div class="con-odds">' + numberField(row, value) +
        '<span class="con-odds-read">' + escapeHtml(unitLabel(row)) + "</span></div>";
    }
    return '<div class="con-odds">' + numberField(row, value) +
      '<span class="con-odds-read">' + escapeHtml(unitLabel(row)) + "</span></div>";
  }

  function settingCard(row) {
    var dirty = isDirty(row.key);
    var finding = state.findings[row.key];
    var overridden = row.overridden || dirty;
    return '<article class="con-setting' + (dirty ? " dirty" : "") +
      (finding ? " invalid" : "") + '" data-setting="' + escapeHtml(row.key) + '">' +
      '<div class="con-setting-head"><h4>' + escapeHtml(row.label) + "</h4>" +
      (dirty ? '<span class="con-flag">edited</span>' : "") + "</div>" +
      '<p class="con-help">' + escapeHtml(row.description) + "</p>" +
      controlFor(row) +
      (finding ? '<p class="con-finding">' + escapeHtml(finding) + "</p>" : "") +
      '<div class="con-setting-foot">' +
      (row.reload === "next_event"
        ? '<span class="con-lag" title="Anything already standing in the world keeps the value it spawned with">applies to the next one</span>'
        : '<span class="con-live">live</span>') +
      (overridden
        ? '<button type="button" class="con-link" data-default="' + escapeHtml(row.key) +
          '">Reset to ' + escapeHtml(row["default"]) + "</button>"
        : "") +
      '<code class="con-key">' + escapeHtml(row.key) + "</code>" +
      "</div></article>";
  }

  /** A minimum and its maximum, drawn as one control so they cannot be read apart. */
  function rangeCard(low, high) {
    var dirty = isDirty(low.key) || isDirty(high.key);
    var finding = state.findings[low.key] || state.findings[high.key];
    var name = low.label.replace(/\s*minimum\s*/i, "").trim() || low.label;
    return '<article class="con-setting con-range' + (dirty ? " dirty" : "") +
      (finding ? " invalid" : "") + '">' +
      '<div class="con-setting-head"><h4>' + escapeHtml(titleCase(name)) + "</h4>" +
      (dirty ? '<span class="con-flag">edited</span>' : "") + "</div>" +
      '<p class="con-help">' + escapeHtml(low.description) + "</p>" +
      '<div class="con-range-fields">' +
      '<label>Lowest' + numberField(low, draftedValue(low.key)) + "</label>" +
      '<span class="con-range-dash" aria-hidden="true">to</span>' +
      '<label>Highest' + numberField(high, draftedValue(high.key)) + "</label>" +
      '<span class="con-unit">' + escapeHtml(unitLabel(low)) + "</span>" +
      "</div>" +
      (finding ? '<p class="con-finding">' + escapeHtml(finding) + "</p>" : "") +
      '<div class="con-setting-foot"><span class="con-live">live</span>' +
      '<code class="con-key">' + escapeHtml(low.key) + "</code></div></article>";
  }

  /* ---------- loot table editor ---------- */

  function tableEditor(table) {
    var rows = tableRows(table).slice().sort(function (a, b) {
      return chanceOf(b, true) - chanceOf(a, true);
    });
    if (!rows.length) return "";
    var liveTotal = tableTotal(table, false);
    var draftTotal = tableTotal(table, true);
    var moved = rows.some(function (row) { return isDirty(row.key); });
    var invalid = draftTotal <= 0;

    var bar = '<div class="con-dist" role="img" aria-label="Share of this table">' +
      rows.map(function (row, index) {
        var share = chanceOf(row, true);
        if (share <= 0) return "";
        return '<span class="con-dist-slice tone-' + (index % 6) + '" style="width:' +
          share.toFixed(4) + '%" title="' + escapeHtml(rowName(row)) + " " +
          formatChance(share) + '"></span>';
      }).join("") + "</div>";

    var body = rows.map(function (row) {
      var jackpot = isJackpot(row);
      var live = jackpot ? jackpotShare(table, false) : chanceOf(row, false);
      var now = jackpot ? jackpotShare(table, true) : chanceOf(row, true);
      var changed = Math.abs(live - now) > 0.0000001;
      var finding = state.findings[row.key];
      return '<tr class="' + (isDirty(row.key) ? "dirty" : "") + (finding ? " invalid" : "") +
        (jackpot ? " jackpot" : "") + '">' +
        '<td><div class="con-entry">' + rowIcon(row) + "<span>" +
        escapeHtml(jackpot ? jackpotName(row) : rowName(row)) +
        (jackpot ? '<em class="con-tag">rolled first</em>' : "") + "</span></div></td>" +
        '<td class="con-num"><input class="con-pct" type="number" step="0.000001" min="0" max="99.9" ' +
        'data-chance="' + escapeHtml(row.key) + '" data-table="' + escapeHtml(table) +
        '" value="' + escapeHtml(trimChance(now)) + '"></td>' +
        '<td class="con-num"><span class="con-move' + (changed ? " shown" : "") + '">' +
        (changed ? formatChance(live) + " &rarr; " + formatChance(now) : "") + "</span></td>" +
        '<td class="con-num">' + (jackpot
          ? '<span class="con-muted">1 in ' +
            escapeHtml(Number(draftedValue(row.key)).toLocaleString()) + "</span>"
          : '<input class="con-number tight" type="number" data-key="' +
            escapeHtml(row.key) + '" value="' + escapeHtml(draftedValue(row.key)) +
            '" min="' + escapeHtml(row.minimum) + '" max="' + escapeHtml(row.maximum) + '">') +
        "</td>" +
        '<td class="con-num con-muted">' + oneInFrom(now) + "</td>" +
        '<td class="con-num">' + (jackpot
          ? '<span class="con-muted" title="Built into how the crate rolls">&nbsp;</span>'
          : '<button type="button" class="con-remove" data-remove="' +
            escapeHtml(row.key) + '" data-table="' + escapeHtml(table) +
            '" title="Remove from this table" aria-label="Remove ' + escapeHtml(rowName(row)) +
            '">&times;</button>' +
            (isAddedRow(table, row.key)
              ? '<span class="con-added" title="You added this">+</span>' : "")) +
        "</td></tr>";
    }).join("");

    return '<section class="con-table' + (invalid ? " invalid" : "") + '">' +
      '<header><div><h3>' + escapeHtml(TABLE_TITLES[table] || table) + "</h3>" +
      "<p>" + escapeHtml(TABLE_BLURBS[table] || "") + "</p></div>" +
      '<div class="con-table-actions">' +
      '<button type="button" class="con-secondary" data-add="' + escapeHtml(table) +
      '">Add an item</button>' +
      '<div class="con-table-total"><strong>' + rows.length + "</strong> entries" +
      "<span>total weight " + draftTotal.toLocaleString() +
      (moved && draftTotal !== liveTotal
        ? " (was " + liveTotal.toLocaleString() + ")" : "") + "</span></div></div></header>" +
      bar + removedStrip(table) +
      (invalid
        ? '<p class="con-finding">Every weight here is zero, so nothing could be drawn ' +
          "from this table. Leave at least one above zero.</p>"
        : "") +
      '<div class="con-table-scroll"><table><thead><tr>' +
      "<th>Entry</th><th>Chance</th><th>Change</th><th>Weight</th><th>Roughly</th><th></th>" +
      "</tr></thead><tbody>" + body + "</tbody></table></div>" +
      '<p class="con-table-note">Type a percentage and the rest of the table re-reads ' +
      "against the new total. Weights stay exactly where you left them.</p></section>";
  }

  /* ---------- catalogue editing ---------- */

  function catalogFor(table) {
    var kind = CRATE_OF_TABLE[table];
    if (!kind || !state.catalog) return null;
    return (state.catalog.crates || []).filter(function (entry) {
      return entry.kind === kind;
    })[0] || null;
  }

  function isAddedRow(table, key) {
    var entry = catalogFor(table);
    if (!entry) {
      if (table.indexOf("airdrop.loot.") !== 0 || !state.catalog) return false;
      var material = MATERIAL.exec(key);
      return !!material && (state.catalog.airdrop_loot_added || []).some(function (row) {
        return row.material.toLowerCase() === material[1];
      });
    }
    var id = (/\.reward\.([a-z0-9_]+)\.weight$/.exec(key) || [])[1];
    return !!id && (entry.added || []).some(function (row) { return row.id === id; });
  }

  /** Weight that gives a new entry the requested share of a table it is not yet in. */
  function weightForShare(table, share) {
    var others = tableTotal(table, true);
    if (others <= 0) return 100;
    var fraction = Math.min(Math.max(share, 0.001), 90) / 100;
    return Math.max(1, Math.round((fraction * others) / (1 - fraction)));
  }

  function frequencyChooser(table) {
    return '<div class="con-freq" role="radiogroup" aria-label="How often">' +
      FREQUENCIES.map(function (frequency, index) {
        return '<label class="con-freq-option"><input type="radio" name="con-frequency" value="' +
          escapeHtml(frequency.share) + '"' + (index === 2 ? " checked" : "") +
          '><span><strong>' + escapeHtml(frequency.label) + "</strong><em>" +
          escapeHtml(frequency.blurb) + "</em></span></label>";
      }).join("") +
      '<label class="con-freq-option custom"><input type="radio" name="con-frequency" value="custom">' +
      '<span><strong>Exactly</strong><em><input id="con-freq-custom" type="number" step="0.01" ' +
      'min="0.001" max="90" value="2"> % of this table</em></span></label></div>';
  }

  function materialPicker() {
    // The id is written out rather than passed in so it can be found by reading the
    // file, which is how the page-contract test checks the console only reaches for
    // elements that exist.
    return '<input class="con-search-field" id="con-add-material" list="con-materials" ' +
      'placeholder="Start typing an item, e.g. copper ingot" autocomplete="off">' +
      '<datalist id="con-materials">' +
      state.materials.slice(0, 1200).map(function (material) {
        return '<option value="' + escapeHtml(material) + '">' +
          escapeHtml(titleCase(material)) + "</option>";
      }).join("") + "</datalist>";
  }

  function openAddDialog(table) {
    state.adding = {table: table, crate: CRATE_OF_TABLE[table] || null};
    var isCrate = !!state.adding.crate;
    var rarity = isCrate ? null : table.replace("airdrop.loot.", "");
    byId("con-add-title").textContent = isCrate
      ? "Add a reward to the " + (TABLE_TITLES[table] || table)
      : "Add a material to " + (TABLE_TITLES[table] || table);
    byId("con-add-body").innerHTML =
      '<label class="con-field"><span>Item</span>' + materialPicker() +
      '<em>Anything the server can hand a player.</em></label>' +
      '<label class="con-field"><span>Name players see</span>' +
      '<input class="con-search-field" id="con-add-name" placeholder="Filled in from the item">' +
      "</label>" +
      (isCrate
        ? '<div class="con-field-row">' +
          '<label class="con-field"><span>How many</span>' +
          '<input class="con-number" id="con-add-amount" type="number" min="1" max="64" value="1">' +
          "</label>" +
          '<label class="con-field"><span>Group</span><select id="con-add-category">' +
          CATEGORIES.map(function (category) {
            return '<option value="' + escapeHtml(category.id) + '">' +
              escapeHtml(category.label) + "</option>";
          }).join("") + "</select></label></div>"
        : '<div class="con-field-row">' +
          '<label class="con-field"><span>Smallest amount</span>' +
          '<input class="con-number" id="con-add-min" type="number" min="1" max="1000" value="4">' +
          "</label>" +
          '<label class="con-field"><span>Largest amount</span>' +
          '<input class="con-number" id="con-add-max" type="number" min="1" max="1000" value="12">' +
          "</label></div>" +
          '<p class="con-help">Airdrops multiply these by rarity: doubled in Rare, tripled in ' +
          "Legendary, quadrupled in Mythic.</p>") +
      '<div class="con-field"><span>How often should it show up?</span>' +
      frequencyChooser(table) +
      (isCrate ? "" : '<p class="con-help">Applies to the ' + escapeHtml(rarity) +
        " table. You can tune the other rarities afterwards.</p>") +
      "</div>";
    byId("con-add").hidden = false;
    window.setTimeout(function () { byId("con-add-material").focus(); }, 30);
  }

  function chosenShare() {
    var picked = document.querySelector('input[name="con-frequency"]:checked');
    if (!picked) return 5;
    if (picked.value === "custom") {
      return Number(byId("con-freq-custom").value) || 1;
    }
    return Number(picked.value);
  }

  async function submitAdd() {
    var context = state.adding;
    if (!context) return;
    var material = String(byId("con-add-material").value || "").trim().toUpperCase()
      .replace(/ /g, "_");
    if (!material) { toast("Pick an item first.", true); return; }
    var name = String(byId("con-add-name").value || "").trim() || titleCase(material);
    var weight = weightForShare(context.table, chosenShare());
    var payload;
    if (context.crate) {
      payload = {
        operation: "add_reward",
        crate: context.crate,
        id: material.toLowerCase(),
        display_name: name,
        category: byId("con-add-category").value,
        material: material,
        amount: Number(byId("con-add-amount").value) || 1,
        weight: weight
      };
    } else {
      var rarity = context.table.replace("airdrop.loot.", "");
      var weights = {};
      ["common", "rare", "legendary", "mythic"].forEach(function (name_) {
        weights[name_] = name_ === rarity ? weight : 0;
      });
      payload = {
        operation: "add_loot",
        material: material,
        minimum_amount: Number(byId("con-add-min").value) || 1,
        maximum_amount: Number(byId("con-add-max").value) || 1,
        weights: weights
      };
    }
    await sendCatalog(payload, "Added " + name + ".");
    byId("con-add").hidden = true;
    state.adding = null;
  }

  async function sendCatalog(payload, success) {
    try {
      var result = await post("/api/catalog", payload);
      toast((result && result.message) || success, false);
      await loadSettings();
      render();
    } catch (error) {
      toast(error.message, true);
    }
  }

  function removeRow(table, key) {
    var crate = CRATE_OF_TABLE[table];
    var row = state.byKey[key];
    var label = row ? rowName(row) : key;
    if (!window.confirm(
      "Remove " + label + "? Built-in entries can be put back afterwards; ones you added"
      + " cannot."
    )) return;
    if (crate) {
      var id = (/\.reward\.([a-z0-9_]+)\.weight$/.exec(key) || [])[1];
      sendCatalog({operation: "remove_reward", crate: crate, id: id}, "Removed " + label + ".");
      return;
    }
    var material = MATERIAL.exec(key);
    if (material) {
      sendCatalog(
        {operation: "remove_loot", material: material[1].toUpperCase()},
        "Removed " + label + "."
      );
    }
  }

  function restoreRow(table, id) {
    var crate = CRATE_OF_TABLE[table];
    sendCatalog(
      crate
        ? {operation: "restore_reward", crate: crate, id: id}
        : {operation: "restore_loot", material: id},
      "Restored."
    );
  }

  /** Built-ins an owner removed, offered back rather than simply gone. */
  function removedStrip(table) {
    var removed = [];
    var entry = catalogFor(table);
    if (entry) {
      removed = (entry.removed || []).map(function (row) {
        return {id: row.id, label: row.display_name || row.id};
      });
    } else if (table.indexOf("airdrop.loot.") === 0 && state.catalog) {
      removed = (state.catalog.airdrop_loot_removed || []).map(function (material) {
        return {id: material, label: titleCase(material)};
      });
    }
    if (!removed.length) return "";
    return '<div class="con-removed"><span>Removed from this table:</span>' +
      removed.map(function (row) {
        return '<button type="button" class="con-chip" data-restore="' + escapeHtml(row.id) +
          '" data-table="' + escapeHtml(table) + '" title="Put this back">' +
          escapeHtml(row.label) + " <em>put back</em></button>";
      }).join("") + "</div>";
  }

  /* ---------- pages ---------- */

  function matchesSearch(row) {
    if (!state.search) return true;
    var haystack = (row.key + " " + row.label + " " + row.description + " " +
      (row.group_label || "") + " " + (row.unit || "")).toLowerCase();
    return haystack.indexOf(state.search) >= 0;
  }

  function groupRows(group) {
    return state.rows.filter(function (row) {
      return row.group === group && matchesSearch(row);
    });
  }

  function renderGroupPage(group) {
    var rows = groupRows(group);
    if (!rows.length) {
      return '<p class="con-empty">Nothing on this page matches &ldquo;' +
        escapeHtml(state.search) + "&rdquo;.</p>";
    }
    var tables = [];
    rows.forEach(function (row) {
      if (row.table && tables.indexOf(row.table) < 0) tables.push(row.table);
    });
    var editors = tables.map(tableEditor).join("");

    // Everything that is not a distribution row, paired up where two keys are really
    // one range, and grouped under the heading the catalogue already gives them.
    // Drawn inside the crate tables it competes with, so it must not also appear as a
    // card of its own further down the page.
    var singles = rows.filter(function (row) {
      return !row.table && row.key !== JACKPOT_KEY;
    });
    var used = {};
    var sections = {};
    singles.forEach(function (row) {
      if (used[row.key]) return;
      var card;
      var partner = row.partner ? state.byKey[row.partner] : null;
      if (partner && !used[partner.key] && /minimum/i.test(row.key)) {
        used[partner.key] = true;
        card = rangeCard(row, partner);
      } else if (partner && !used[partner.key] && /maximum/i.test(row.key)) {
        used[partner.key] = true;
        card = rangeCard(partner, row);
      } else {
        card = settingCard(row);
      }
      used[row.key] = true;
      var heading = row.category || "Settings";
      (sections[heading] = sections[heading] || []).push(card);
    });

    var singleHtml = Object.keys(sections).map(function (heading) {
      return '<section class="con-section"><h3>' + escapeHtml(heading) + "</h3>" +
        '<div class="con-grid">' + sections[heading].join("") + "</div></section>";
    }).join("");

    return editors + singleHtml;
  }

  function renderOverview() {
    var overridden = state.rows.filter(function (row) { return row.overridden; });
    var lagging = state.rows.filter(function (row) { return row.reload === "next_event"; });
    var pending = dirtyKeys();
    var recent = (state.snapshot.history || []).slice(0, 5);

    var cards = [
      ["Values you can change", state.rows.length, "across " + (PAGES.length - 2) + " pages"],
      ["Changed from default", overridden.length,
        overridden.length ? "everything else is stock" : "everything is stock"],
      ["Unpublished edits", pending.length,
        pending.length ? "review them before they go live" : "nothing waiting"],
      ["Need a restart", 0, "every value here applies without one"]
    ].map(function (card) {
      return '<div class="con-stat"><span>' + escapeHtml(card[0]) + "</span><strong>" +
        escapeHtml(card[1]) + "</strong><em>" + escapeHtml(card[2]) + "</em></div>";
    }).join("");

    var changed = overridden.length
      ? '<section class="con-section"><h3>Changed from default</h3><div class="con-grid">' +
        overridden.slice(0, 12).map(settingCard).join("") + "</div>" +
        (overridden.length > 12
          ? '<p class="con-table-note">' + (overridden.length - 12) +
            " more, on their own pages.</p>"
          : "") + "</section>"
      : "";

    var lag = '<section class="con-section"><h3>What a change reaches</h3>' +
      '<p class="con-help wide">Every value here takes effect without restarting the ' +
      "server. " + lagging.length + " of them are copied into an event when it spawns, so " +
      "anything already standing in the world keeps the number it was born with: " +
      lagging.map(function (row) { return escapeHtml(row.label); }).join(", ") +
      ".</p></section>";

    var history = recent.length
      ? '<section class="con-section"><h3>Recent publishes</h3>' +
        '<div class="con-history">' + recent.map(publishRow).join("") + "</div></section>"
      : "";

    return '<div class="con-stats">' + cards + "</div>" + changed + lag + history;
  }

  function publishRow(publish) {
    var when = new Date(Number(publish.at)).toLocaleString();
    var rows = (publish.changes || []).map(function (change) {
      var row = state.byKey[change.key];
      return '<li><span>' + escapeHtml(row ? row.label : change.key) + "</span>" +
        '<code>' + escapeHtml(change.before) + " &rarr; " + escapeHtml(change.after) +
        "</code></li>";
    }).join("");
    return '<article class="con-publish"><header><div><strong>' +
      escapeHtml(publish.change_count) + " value" + (publish.change_count === 1 ? "" : "s") +
      " changed</strong><span>" + escapeHtml(when) +
      (publish.actor ? " &middot; " + escapeHtml(publish.actor) : "") + "</span></div>" +
      '<button type="button" class="con-secondary" data-rollback="' +
      escapeHtml(publish.id) + '">Roll back</button></header>' +
      "<ul>" + rows + "</ul></article>";
  }

  function renderHistory() {
    var history = state.snapshot.history || [];
    if (!history.length) {
      return '<p class="con-empty">Nothing has been published yet. Once it has, every ' +
        "change shows here with what it was before, and can be undone.</p>";
    }
    return '<div class="con-history">' + history.map(publishRow).join("") + "</div>";
  }

  /* ---------- chrome ---------- */

  function renderNav() {
    byId("con-nav").innerHTML = PAGES.map(function (page) {
      var count = page.id === "overview" || page.id === "history"
        ? 0
        : groupRows(page.id).length;
      var dirty = dirtyKeys().filter(function (key) {
        return state.byKey[key].group === page.id;
      }).length;
      return '<button type="button" data-page="' + escapeHtml(page.id) + '" aria-current="' +
        (state.page === page.id ? "page" : "false") + '">' + escapeHtml(page.label) +
        (count ? '<span class="con-count">' + count + "</span>" : "") +
        (dirty ? '<span class="con-dot" title="' + dirty + ' unpublished">' + dirty + "</span>" : "") +
        "</button>";
    }).join("");
  }

  function renderDraftBar() {
    var pending = dirtyKeys();
    var bar = byId("con-draftbar");
    if (!pending.length) {
      bar.hidden = true;
      bar.innerHTML = "";
      return;
    }
    var blocking = Object.keys(state.findings).length;
    bar.hidden = false;
    bar.innerHTML =
      '<div class="con-draft-count"><strong>' + pending.length + "</strong> unpublished change" +
      (pending.length === 1 ? "" : "s") +
      (state.validating
        ? "<span>checking&hellip;</span>"
        : blocking
          ? '<span class="bad">' + blocking + " problem" + (blocking === 1 ? "" : "s") + " to fix</span>"
          : "<span>ready to publish</span>") +
      "</div>" +
      '<div class="con-draft-actions">' +
      '<button type="button" class="con-secondary" id="con-discard">Discard</button>' +
      '<button type="button" class="con-secondary" id="con-review">Review</button>' +
      '<button type="button" class="con-primary" id="con-publish"' +
      (blocking || state.validating || state.publishing ? " disabled" : "") + ">" +
      (state.publishing ? "Publishing&hellip;" : "Publish") + "</button></div>";
  }

  function renderPreview() {
    var pending = dirtyKeys();
    var body = pending.map(function (key) {
      var row = state.byKey[key];
      return "<li><div><strong>" + escapeHtml(row.label) + "</strong><span>" +
        escapeHtml(row.group_label) + "</span></div><code>" +
        escapeHtml(row.value) + " &rarr; " + escapeHtml(draftedValue(key)) + "</code></li>";
    }).join("");
    byId("con-preview-body").innerHTML =
      "<p>Publishing applies all of these together, or none of them if the server "
      + "refuses any one.</p><ul class=\"con-preview-list\">" + body + "</ul>";
    byId("con-preview").hidden = false;
  }

  function render() {
    dropSettledEdits();
    renderNav();
    renderDraftBar();
    var main = byId("con-page");
    if (state.page === "overview") {
      main.innerHTML = renderOverview();
    } else if (state.page === "history") {
      main.innerHTML = renderHistory();
    } else {
      main.innerHTML = renderGroupPage(state.page);
    }
    var page = PAGES.filter(function (entry) { return entry.id === state.page; })[0];
    byId("con-page-title").textContent = page ? page.label : "";
  }

  /* ---------- events ---------- */

  function wire() {
    byId("con-nav").addEventListener("click", function (event) {
      var button = event.target.closest("[data-page]");
      if (!button) return;
      state.page = button.dataset.page;
      render();
      window.scrollTo({top: 0, behavior: "smooth"});
    });

    byId("con-page").addEventListener("change", function (event) {
      var target = event.target;
      if (target.dataset.toggle !== undefined) {
        setDraft(target.dataset.key, target.checked);
        return;
      }
      if (target.dataset.chance !== undefined) {
        var row = state.byKey[target.dataset.chance];
        if (isJackpot(row)) {
          // This row has no weight. A percentage here is the odds themselves, so it
          // converts back to the "one in N" the roll actually uses.
          var share = Math.min(Math.max(Number(target.value), 0.0000001), 90);
          setDraft(row.key, Math.min(Math.max(Math.round(100 / share), 1), row.maximum));
          return;
        }
        setDraft(row.key, weightForChance(row, target.value));
        return;
      }
      if (target.dataset.key !== undefined) {
        var parsed = parseInt(target.value, 10);
        if (!isNaN(parsed)) setDraft(target.dataset.key, parsed);
      }
    });

    byId("con-page").addEventListener("click", function (event) {
      var reset = event.target.closest("[data-default]");
      if (reset) { resetToDefault(reset.dataset.default); return; }
      var rollback = event.target.closest("[data-rollback]");
      if (rollback) { rollBack(rollback.dataset.rollback); return; }
      var add = event.target.closest("[data-add]");
      if (add) { openAddDialog(add.dataset.add); return; }
      var remove = event.target.closest("[data-remove]");
      if (remove) { removeRow(remove.dataset.table, remove.dataset.remove); return; }
      var restore = event.target.closest("[data-restore]");
      if (restore) { restoreRow(restore.dataset.table, restore.dataset.restore); }
    });

    byId("con-add").addEventListener("click", function (event) {
      if (event.target.dataset.close !== undefined || event.target.id === "con-add") {
        byId("con-add").hidden = true;
        state.adding = null;
      }
      if (event.target.id === "con-add-confirm") submitAdd();
    });

    // Typing an item fills the player-facing name, until the owner writes their own.
    byId("con-add").addEventListener("input", function (event) {
      if (event.target.id !== "con-add-material") return;
      var name = byId("con-add-name");
      if (name && !name.dataset.touched) {
        name.value = titleCase(String(event.target.value || "").replace(/_/g, " "));
      }
      if (event.target.id === "con-add-name") name.dataset.touched = "1";
    });
    byId("con-add").addEventListener("change", function (event) {
      if (event.target.id === "con-add-name") event.target.dataset.touched = "1";
      if (event.target.id === "con-freq-custom") {
        var custom = document.querySelector('input[name="con-frequency"][value="custom"]');
        if (custom) custom.checked = true;
      }
    });

    byId("con-draftbar").addEventListener("click", function (event) {
      if (event.target.id === "con-discard") discardDraft();
      if (event.target.id === "con-review") renderPreview();
      if (event.target.id === "con-publish") publish();
    });

    byId("con-preview").addEventListener("click", function (event) {
      if (event.target.dataset.close !== undefined || event.target.id === "con-preview") {
        byId("con-preview").hidden = true;
      }
      if (event.target.id === "con-preview-publish") {
        byId("con-preview").hidden = true;
        publish();
      }
    });

    var search = byId("con-search");
    search.addEventListener("input", function () {
      state.search = search.value.trim().toLowerCase();
      render();
    });

    document.addEventListener("keydown", function (event) {
      if (event.key !== "Escape") return;
      byId("con-preview").hidden = true;
      byId("con-add").hidden = true;
      state.adding = null;
    });

    window.addEventListener("beforeunload", function (event) {
      if (dirtyKeys().length) {
        event.preventDefault();
        event.returnValue = "";
      }
    });
  }

  async function publish() {
    var edits = editsPayload();
    if (!edits.length || state.publishing) return;
    state.publishing = true;
    renderDraftBar();
    try {
      var result = await post("/api/settings/publish", {edits: edits});
      if (result && result.ok === false) {
        var found = {};
        (result.findings || []).forEach(function (finding) {
          found[finding.key] = finding.message;
        });
        state.findings = found;
        toast(result.message || "The server refused that change set.", true);
      } else {
        state.draft = {};
        state.findings = {};
        saveDraft();
        toast(result.message || "Published.", false);
        await loadSettings();
      }
    } catch (error) {
      toast(error.message, true);
    }
    state.publishing = false;
    render();
  }

  async function rollBack(publishId) {
    if (!window.confirm(
      "Put every value in this change back the way it was? This is recorded as a new change."
    )) return;
    try {
      var result = await post("/api/settings/rollback", {publish_id: publishId});
      toast(result.message || "Restored.", false);
      await loadSettings();
      render();
    } catch (error) {
      toast(error.message, true);
    }
  }

  /* ---------- boot ---------- */

  async function loadSettings() {
    var snapshot = await api("/api/settings");
    state.snapshot = snapshot || {};
    state.rows = (state.snapshot.variables || []).filter(function (row) {
      return row.group !== "unclassified";
    });
    state.byKey = {};
    state.rows.forEach(function (row) { state.byKey[row.key] = row; });
    state.catalog = state.snapshot.catalog || null;
    state.materials = state.snapshot.materials || [];
  }

  async function boot() {
    var me = await api("/api/me");
    if (!me.authenticated) return;
    state.csrf = me.csrf;
    byId("owner-account").innerHTML = '<div class="live-user-pill"><img src="' +
      escapeHtml(me.avatar_url) + '" alt=""><span><strong>' +
      escapeHtml(me.display_name) + '</strong><button id="owner-logout" type="button">' +
      "Sign out</button></span></div>";
    byId("owner-logout").addEventListener("click", async function () {
      await api("/auth/logout", {method: "POST"});
      window.location.reload();
    });
    byId("control-lock").hidden = true;
    byId("owner-content").hidden = false;

    await loadSettings();
    loadDraft();
    wire();
    render();
    if (dirtyKeys().length) {
      toast("Picked up " + dirtyKeys().length + " unpublished change(s) from last time.", false);
      scheduleValidate();
    }
  }

  if (byId("console-root")) {
    boot().catch(function (error) { toast(error.message, true); });
  }
}());
