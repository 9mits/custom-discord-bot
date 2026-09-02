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

  /* Sections, in the order the sidebar shows them. A flat list of twenty-nine was a
     wall to read, and two entries were both called "Auction House" — the settings that
     govern the auction, and the live listings. They are named apart now. */
  var PAGES = [
    {id: "overview", label: "Overview", group: ""},

    {id: "actions", label: "Do something", group: "Operate"},
    {id: "statistics", label: "Statistics", group: "Operate"},
    {id: "player", label: "Look up a player", group: "Operate"},
    {id: "announce", label: "Update notice", group: "Operate"},
    {id: "auction", label: "Live listings", group: "Operate"},
    {id: "history", label: "Change history", group: "Operate"},
    {id: "schedule_actions", label: "Scheduled", group: "Operate"},
    {id: "presets", label: "Presets & backup", group: "Operate"},

    {id: "crates", label: "Crates", group: "Rewards"},
    {id: "crate_balance", label: "Crate balance", group: "Rewards"},
    {id: "airdrops", label: "Airdrops", group: "Rewards"},
    {id: "online_rewards", label: "Online rewards", group: "Rewards"},
    {id: "shop", label: "Shop", group: "Rewards"},
    {id: "amethyst_shop", label: "Amethyst shop", group: "Rewards"},
    {id: "economy", label: "Economy", group: "Rewards"},
    {id: "auction_house", label: "Auction rules", group: "Rewards"},

    {id: "huge_amethyst", label: "Huge Amethyst", group: "Events"},
    {id: "amethyst_mobs", label: "Amethyst mobs", group: "Events"},
    {id: "admin_events", label: "Admin events", group: "Events"},
    {id: "event_multipliers", label: "Multipliers", group: "Events"},
    {id: "event_schedule", label: "Schedule", group: "Events"},
    {id: "clan_battles", label: "Clan battles", group: "Events"},

    {id: "world", label: "World", group: "World & players"},
    {id: "players", label: "Players", group: "World & players"},
    {id: "clans", label: "Clans", group: "World & players"},
    {id: "launch", label: "Launch", group: "World & players"},

    {id: "potions", label: "Potions", group: "Items & effects"},
    {id: "enchantments", label: "Enchantments", group: "Items & effects"},
    {id: "cosmetics", label: "Cosmetics", group: "Items & effects"},
    {id: "perks", label: "Perks", group: "Items & effects"},

    {id: "messages", label: "Messages", group: "Presentation"},
    {id: "boss_bars", label: "Boss bars", group: "Presentation"},
    {id: "presentation", label: "Presentation", group: "Presentation"}
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

  /** What each page is for, in the owner's terms rather than the code's. */
  var PAGE_INTROS = {
    crates: "What each crate can give and how often. The tables below are the whole reward pool — every row is something a player can open and receive.",
    airdrops: "How often Airdrops arrive, how far out they land, and what is inside. Rarity is rolled first, then the contents for that rarity.",
    online_rewards: "What players get for staying connected. Six tiers, reached by lifetime hours played, each with its own rewards and chances.",
    huge_amethyst: "The cooperative block event: how tough it is, when it pays out, and what those payouts are.",
    admin_events: "What the events you trigger by hand are worth. These do not happen on their own.",
    amethyst_mobs: "How often an ordinary monster spawns as an Amethyst one, and what killing it drops.",
    event_multipliers: "How much each server-wide event multiplies by. Players are told the figure, and the announcement follows whatever you set.",
    event_schedule: "The gap between world events. Airdrops and Huge Amethyst Blocks share this timer and take turns.",
    players: "Rules that apply to everyone: going AFK, teleporting, verifying, combat.",
    world: "Spawn, the border, and how much of the world is kept loaded. The distance caps are the main lever on server load.",
    clans: "How large a clan can get and how long its invitations stay open.",
    auction_house: "Limits on player-to-player selling.",
    economy: "Bounties and automatic payments.",
    shop: "Prices, as percentages of what the catalogue set. 100 leaves a price alone, 50 halves it, 200 doubles it. The shop-wide figure and a shelf's own figure multiply together.",
    amethyst_shop: "The limited Amethyst shelf and the items it sells.",
    potions: "How strong and how long each custom potion is.",
    enchantments: "The highest level a crate book may carry for each mark.",
    cosmetics: "How auras, trails and reveals move and how far away they are visible. Mostly a matter of taste and a little of server load.",
    crate_balance: "The system that quietly nudges an unlucky player's odds back toward the advertised rate. Leave it alone unless you know you want to.",
    boss_bars: "The colour of each boss bar.",
    perks: "What Elite and Booster are worth in play.",
    clan_battles: "What each placement pays when a Clan Battle ends.",
    launch: "The opening countdown and how long PvP stays off afterwards.",
    messages: "The exact words players see. Formatting is MiniMessage — <bold>text</bold> and <#b57edc>colour</#b57edc> work — and anything in angle brackets like <keys> is filled in by the server. Empty a message to switch it off entirely.",
    presentation: "Small pieces of what players see."
  };

  /** Words an owner might search for, mapped to what the setting is actually called. */
  var SEARCH_TERMS = {
    "rare": "weight chance odds", "common": "weight chance odds",
    "money": "economy shop price money balance", "cash": "money",
    "loot": "reward weight loot", "drop": "airdrop loot drop",
    "speed": "speed rate", "lag": "view simulation distance swarm",
    "performance": "view simulation distance swarm active",
    "colour": "colour color bar", "color": "colour color bar",
    "reward": "reward keys shards prize", "keys": "key crate",
    "time": "minutes seconds hours duration lifetime",
    "how often": "one in chance weight delay interval",
    "chance": "one in weight chance percent"
  };

  /**
   * What an owner is actually trying to do, and the settings that do it.
   *
   * The catalogue is organised by what each value configures, which is right for finding
   * something you already know the name of and useless for "crates feel stingy". A task
   * starts from the intent, gathers the handful of settings that bear on it, and offers a
   * change you can look at before publishing — so the panel does the part that needs
   * knowing where things live.
   *
   * `apply` returns the edits for a strength, or null to just show the settings.
   */
  var TASKS = [
    {
      id: "crates-generous",
      title: "Crates feel stingy",
      blurb: "Players earn keys too slowly, or open too little of worth.",
      keys: ["crate.keys-per-hour", "crate.booster-keys-per-hour",
             "crate.default.key-cost", "crates.luck.minimum-percent"],
      strengths: [
        {label: "A little more generous", factor: 1.25},
        {label: "Noticeably more", factor: 1.5},
        {label: "Double the earn rate", factor: 2}
      ],
      apply: function (factor) {
        return scaleEdits(["crate.keys-per-hour", "crate.booster-keys-per-hour"], factor);
      }
    },
    {
      id: "crates-tight",
      title: "Crates are too generous",
      blurb: "Keys are piling up, or rare rewards are showing too often.",
      keys: ["crate.keys-per-hour", "crate.booster-keys-per-hour",
             "crates.luck.maximum-percent", "crate.hidden-amethyst-one-in"],
      strengths: [
        {label: "Slightly tighter", factor: 0.8},
        {label: "Noticeably tighter", factor: 0.6},
        {label: "Half the earn rate", factor: 0.5}
      ],
      apply: function (factor) {
        return scaleEdits(["crate.keys-per-hour", "crate.booster-keys-per-hour"], factor);
      }
    },
    {
      id: "airdrops-often",
      title: "Airdrops should come round more often",
      blurb: "The wait between world events is too long.",
      keys: ["amethyst-events.minimum-delay-minutes", "amethyst-events.maximum-delay-minutes",
             "airdrop.enabled", "airdrop.lifetime-minutes", "airdrop.maximum-active"],
      strengths: [
        {label: "A bit more often", factor: 0.75},
        {label: "Twice as often", factor: 0.5},
        {label: "Nearly constant", factor: 0.25}
      ],
      apply: function (factor) {
        return scaleEdits(["amethyst-events.minimum-delay-minutes",
                           "amethyst-events.maximum-delay-minutes"], factor);
      }
    },
    {
      id: "economy-hot",
      title: "The economy is inflated",
      blurb: "Too much money about. Raise what things cost, lower what selling pays.",
      keys: ["shop.buy-percent", "shop.sell-percent", "auction.maximum-price",
             "bounty.minimum"],
      strengths: [
        {label: "Gentle correction", buy: 125, sell: 85},
        {label: "Firm correction", buy: 150, sell: 70},
        {label: "Hard reset of prices", buy: 200, sell: 50}
      ],
      apply: function (strength) {
        return [{key: "shop.buy-percent", value: String(strength.buy)},
                {key: "shop.sell-percent", value: String(strength.sell)}];
      }
    },
    {
      id: "weekend-event",
      title: "Run a weekend event",
      blurb: "Turn up the multipliers, then start one from Do something.",
      keys: ["events.key.multiplier", "events.money.multiplier",
             "events.crateluck.multiplier", "events.maximum-seconds"],
      strengths: null
    },
    {
      id: "new-player-friendly",
      title: "Make the first hours kinder",
      blurb: "New players see the early reward tiers and the shop before anything else.",
      keys: ["online-rewards.tier.1.minimum-hours", "online-rewards.tier.1.bonus-keys",
             "online-rewards.tier.2.bonus-keys", "online-rewards.interval-minutes",
             "shop.buy-percent"],
      strengths: null
    },
    {
      id: "quieter-server",
      title: "Make the server calmer",
      blurb: "Fewer world events, gentler cosmetics, less going on at once.",
      keys: ["amethyst-events.minimum-delay-minutes", "airdrop.maximum-active",
             "chaos.maximum-swarm", "cosmetics.view-distance", "cosmetics.aura.sound-volume"],
      strengths: null
    },
    {
      id: "performance",
      title: "The server is struggling",
      blurb: "The settings that cost the most to run.",
      keys: ["world.max-view-distance", "world.max-simulation-distance",
             "airdrop.maximum-active", "chaos.maximum-swarm", "cosmetics.view-distance",
             "airdrop.guard.hunt-radius"],
      strengths: null
    }
  ];

  /** Edits that multiply the current value, clamped to what each setting allows. */
  function scaleEdits(keys, factor) {
    var edits = [];
    keys.forEach(function (key) {
      var row = state.byKey[key];
      if (!row) return;
      var current = Number(draftedValue(key));
      var next = Math.round(current * factor);
      // A 20% raise on a value of 2 rounds straight back to 2, so the setting an owner
      // pointed at silently does not move. Nudge by one in the direction asked for:
      // "a bit more" on a small number means the next number up, not nothing.
      if (next === current && factor !== 1) {
        next = current + (factor > 1 ? 1 : -1);
      }
      next = Math.max(row.minimum === undefined ? next : row.minimum,
              Math.min(row.maximum === undefined ? next : row.maximum, next));
      if (next !== Number(row.value)) edits.push({key: key, value: String(next)});
    });
    return edits;
  }

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
    task: null,
    activity: null,
    auction: null,
    logFilter: "all",
    stalePlugin: false,
    stats: null,
    statDays: 30,
    statMetrics: [],
    statSeries: {},
    shopShelf: null,
    presets: null,
    player: null,
    playerQuery: "",
    schedule: null,
    announce: null,
    announceResult: null,
    announceDraft: {title: "", description: "", colour: "f06000", footer: "", image: ""},
    actions: [],
    online: [],
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

  /* ---------- what a value actually means ---------- */

  /**
   * Plain language for what a setting does at its current value.
   *
   * A number on its own is not information. "Keys per online hour: 2" tells an owner
   * nothing about whether that is generous; "a player online three hours a day earns
   * about 6 keys" does. Every line here is computed from the value being shown, so it
   * moves as the value moves and cannot go stale.
   */
  function meaning(row) {
    var value = draftedValue(row.key);
    var number = Number(value);
    switch (row.control) {
      case "weight_row": {
        var share = chanceOf(row, true);
        var rank = tableRows(row.table)
          .filter(function (other) { return chanceOf(other, true) > share; }).length + 1;
        var total = tableRows(row.table).length;
        if (share <= 0) return "Never given while this is zero.";
        return "About " + oneInFrom(share).replace("1 in ", "1 in every ") +
          " — the " + ordinal(rank) + " most likely of " + total + ".";
      }
      case "odds":
        return number > 0
          ? "Happens about " + oneInFrom(100 / number).replace("1 in ", "1 time in every ") + "."
          : "Never happens.";
      case "multiplier":
        return "Players see this advertised as " + number + "x.";
      case "duration": {
        var unit = row.unit === "hours" ? "hour" : (row.unit === "seconds" ? "second"
          : (row.unit === "milliseconds" ? "millisecond" : (row.unit === "frames" ? "frame" : "minute")));
        return humanTime(number, unit) + ".";
      }
      case "distance": {
        var border = state.byKey["world.border-radius"];
        if (border && row.key.indexOf("radius") >= 0) {
          var pct = (number / Number(border.value)) * 100;
          return number.toLocaleString() + " blocks — " + pct.toFixed(1) +
            "% of the way to the world border.";
        }
        return number.toLocaleString() + " blocks. A chunk is 16.";
      }
      case "percent": {
        // "past the halfway point" said nothing on any of the twenty-eight rows that
        // reach here — a price multiplier has no halfway point. What a percentage means
        // depends on what it is a percentage of, so each family answers for itself.
        if (/health-percent$/.test(row.key)) {
          return "Fires once the block is down to " + number + "% of its health.";
        }
        if (/\.(buy|sell)-percent$/.test(row.key)) {
          var paying = /sell-percent$/.test(row.key) ? "Selling pays" : "Buying costs";
          if (number === 100) return "Exactly what the catalogue lists.";
          return number < 100
            ? paying + " " + (100 - number) + "% less than the listed price."
            : paying + " " + (number - 100) + "% more than the listed price.";
        }
        if (row.key.indexOf("crates.") === 0) {
          return number === 100
            ? "The ordinary rare-reward rate."
            : number + "% of the ordinary rare-reward rate.";
        }
        return number + "%.";
      }
      case "rate":
        if (row.unit === "per 10,000") {
          return "About " + oneInFrom(number / 100).replace("1 in ", "1 in every ") + ".";
        }
        return "A fraction. 1 is unchanged; " + number + " is " +
          (number < 1 ? Math.round((1 - number) * 100) + "% less" :
            Math.round((number - 1) * 100) + "% more") + ".";
      case "toggle":
        return value ? "On. Turning this off stops it entirely." : "Off. Nothing is running.";
      case "level":
        return "Level " + number + (number > 1 ? " — the stronger variant." : " — the ordinary effect.");
      case "quantity":
        return quantityMeaning(row, number);
      default:
        return "";
    }
  }

  /** Quantities get the most context, because a count means least on its own. */
  function quantityMeaning(row, number) {
    if (row.key === "crate.keys-per-hour" || row.key === "crate.booster-keys-per-hour") {
      return "A player online three hours a day earns about " + (number * 3) +
        " keys a day, " + (number * 21) + " a week.";
    }
    if (row.key.indexOf("online-rewards.population") === 0) {
      return "Applies to the stay-online ladder as more players come on.";
    }
    if (row.unit === "money") {
      return "$" + number.toLocaleString() + ".";
    }
    if (row.unit === "keys" || row.unit === "shards" || row.unit === "items"
        || row.unit === "diamonds" || row.unit === "emeralds" || row.unit === "gold") {
      // The unit is stored plural, so one of anything needs it back in the singular.
      // "gold" is uncountable and must not become "gol".
      return number === 0 ? "Nothing is given." :
        number + " " +
        (number === 1 ? row.unit.replace(/(?!^)s$/, "") : row.unit) +
        " each time.";
    }
    return "";
  }

  function ordinal(n) {
    var suffix = ["th", "st", "nd", "rd"][(n % 100 - n % 10 !== 10) * 1 && n % 10 < 4 ? n % 10 : 0];
    return n + (suffix || "th");
  }

  function humanTime(value, unit) {
    if (unit === "minute" && value >= 60) {
      var hours = value / 60;
      return value + " minutes — " + (hours === 1 ? "an hour" : hours.toFixed(1) + " hours");
    }
    if (unit === "second" && value >= 60) {
      return value + " seconds — about " + Math.round(value / 60) + " minutes";
    }
    if (unit === "hour" && value >= 24) {
      return value + " hours — " + (value / 24).toFixed(1) + " days";
    }
    if (unit === "millisecond") {
      return (value / 1000).toFixed(1) + " seconds";
    }
    if (unit === "frame") {
      return value + " frames — about " + (value / 20).toFixed(1) + " seconds";
    }
    return value + " " + unit + (value === 1 ? "" : "s");
  }

  /** What else moves when this does. */
  function relatedTo(row) {
    var notes = [];
    if (row.table) {
      var others = tableRows(row.table).length - 1;
      notes.push("Shares a table with " + others + " other entr" + (others === 1 ? "y" : "ies") +
        " — raising this lowers all of their chances.");
    }
    if (row.partner && state.byKey[row.partner]) {
      notes.push("Paired with " + state.byKey[row.partner].label + "; one cannot pass the other.");
    }
    if (row.reload === "next_event") {
      notes.push("Anything already standing in the world keeps the value it spawned with.");
    }
    return notes;
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
    if (row.control === "choice") {
      return '<div class="con-odds"><select class="con-choice" data-key="' +
        escapeHtml(row.key) + '">' + (row.choices || []).map(function (option) {
          return '<option value="' + escapeHtml(option) + '"' +
            (option === value ? " selected" : "") + ">" +
            escapeHtml(titleCase(option)) + "</option>";
        }).join("") + "</select>" +
        (/colour|color/.test(row.key)
          ? '<span class="con-swatch tone-' + escapeHtml(String(value).toLowerCase()) +
            '" aria-hidden="true"></span>'
          : "") + "</div>";
    }
    if (row.control === "text") {
      return '<div class="con-odds"><input class="con-search-field" type="text" data-key="' +
        escapeHtml(row.key) + '" value="' + escapeHtml(value) + '" maxlength="' +
        escapeHtml(row.maximum) + '"><span class="con-odds-read">up to ' +
        escapeHtml(row.maximum) + " characters</span></div>";
    }
    if (row.control === "level") {
      return '<div class="con-odds">' + numberField(row, value) +
        '<span class="con-odds-read">level <strong>' + escapeHtml(value) +
        "</strong>" + (Number(value) > 1 ? " &middot; the II variant" : "") + "</span></div>";
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
      // Only four of the twenty-eight percentages are about health; the rest are shop
      // prices and luck bounds, and every one of them used to read "of full health".
      var of = /health-percent$/.test(row.key) ? " of full health"
        : /\.buy-percent$/.test(row.key) ? " of the listed price"
        : /\.sell-percent$/.test(row.key) ? " of the listed price"
        : "";
      return '<div class="con-odds">' + numberField(row, value) +
        '<span class="con-odds-read">' + escapeHtml(value) + "%" + of + "</span></div>";
    }
    if (row.control === "duration" || row.control === "distance") {
      return '<div class="con-odds">' + numberField(row, value) +
        '<span class="con-odds-read">' + escapeHtml(unitLabel(row)) + "</span></div>";
    }
    return '<div class="con-odds">' + numberField(row, value) +
      '<span class="con-odds-read">' + escapeHtml(unitLabel(row)) + "</span></div>";
  }

  /**
   * The default and the allowed range, spelled out.
   *
   * Both were already enforced — min/max landed on the input and the server refused
   * anything outside them — but neither was ever shown, so the only way to learn a
   * bound was to hit it. Knowing that view distance stops at 32 before typing 64 is
   * the difference between a control and a guess.
   */
  function limitsOf(row) {
    var parts = [];
    if (row["default"] !== undefined && row["default"] !== "") {
      parts.push("default " + escapeHtml(row["default"]));
    }
    var low = row.minimum;
    var high = row.maximum;
    if (row.control === "toggle" || row.control === "choice") {
      low = undefined;
      high = undefined;
    }
    if (row.control === "text" && high !== undefined) {
      parts.push("up to " + escapeHtml(high) + " characters");
    } else if (low !== undefined && high !== undefined) {
      parts.push(Number(low).toLocaleString() + "\u2013" + Number(high).toLocaleString() +
        (unitLabel(row) ? " " + escapeHtml(unitLabel(row)) : ""));
    } else if (high !== undefined) {
      parts.push("up to " + Number(high).toLocaleString());
    } else if (low !== undefined) {
      parts.push("at least " + Number(low).toLocaleString());
    }
    return parts.length
      ? '<span class="con-limits">' + parts.join(" &middot; ") + "</span>"
      : "";
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
      (meaning(row) ? '<p class="con-meaning">' + escapeHtml(meaning(row)) + "</p>" : "") +
      relatedTo(row).map(function (note) {
        return '<p class="con-related">' + escapeHtml(note) + "</p>";
      }).join("") +
      (finding ? '<p class="con-finding">' + escapeHtml(finding) + "</p>" : "") +
      '<div class="con-setting-foot">' +
      (row.reload === "next_event"
        ? '<span class="con-lag" title="Anything already standing in the world keeps the value it spawned with">applies to the next one</span>'
        : '<span class="con-live">live</span>') +
      limitsOf(row) +
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
          '<label class="con-field"><span>Group</span>' +
          '<select class="con-choice" id="con-add-category">' +
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
    if (!material) {
      toast(context.cosmetic ? "Give it an identifier first." : "Pick an item first.", true);
      return;
    }
    if (context.cosmetic) {
      var effect = byId("con-add-category").value;
      var effects = (state.catalog || {}).cosmetic_effects || [];
      var worn = effects.filter(function (e) { return e.id === effect; })[0];
      byId("con-add").hidden = true;
      state.adding = null;
      catalogCall("add_cosmetic", {
        id: String(byId("con-add-material").value || "").trim().toLowerCase()
          .replace(/ /g, "_"),
        display_name: String(byId("con-add-name").value || "").trim(),
        // The category follows the effect it wears: a trail cannot be worn as an aura.
        category: worn ? worn.category : "AURA",
        weight: Number(byId("con-add-amount").value || 100),
        wears_effect: effect,
        description: ""
      });
      return;
    }
    if (context.shop) {
      // The shop dialog has no reward name or weight; it is an item, a shelf and a price.
      byId("con-add").hidden = true;
      state.adding = null;
      catalogCall("set_shop_offer", {
        material: material,
        category: byId("con-add-category").value,
        amount: Number(byId("con-add-amount").value || 1),
        price: Number(byId("con-add-min").value || 1)
      });
      return;
    }
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

  async function removeRow(table, key) {
    var crate = CRATE_OF_TABLE[table];
    var row = state.byKey[key];
    var label = row ? rowName(row) : key;
    if (!await confirmThat(
      "Remove " + label + "?",
      "Built-in entries can be put back afterwards; ones you added cannot.",
      "Remove it"
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

  /**
   * Matches what an owner types, not only what the setting is called.
   *
   * Someone looking for "lag" will not type "simulation distance", and someone after
   * "how often" will not type "one in". The map above widens the query rather than the
   * haystack, so a search still lands on the same rows it always would.
   */
  function matchesSearch(row) {
    if (!state.search) return true;
    var haystack = (row.key + " " + row.label + " " + row.description + " " +
      (row.group_label || "") + " " + (row.unit || "") + " " + meaning(row)).toLowerCase();
    if (haystack.indexOf(state.search) >= 0) return true;
    var widened = SEARCH_TERMS[state.search];
    if (!widened) return false;
    return widened.split(" ").some(function (term) {
      return haystack.indexOf(term) >= 0;
    });
  }

  function groupRows(group) {
    return state.rows.filter(function (row) {
      return row.group === group && matchesSearch(row);
    });
  }

  /**
   * The shop, item by item.
   *
   * <p>The 251 offers stay compiled into the plugin because they are the shop's shape.
   * What an owner changes about it — a price, an amount, an item added or taken off the
   * shelf — is an overlay, so this shows the whole shelf as a player would see it and
   * marks which rows have been touched.
   */
  function shopEditor() {
    var shelves = (state.catalog || {}).shop_shelves || [];
    if (!shelves.length) return "";
    var removed = (state.catalog || {}).shop_removed || [];
    var open = state.shopShelf || shelves[0].id;
    var rail = shelves.map(function (shelf) {
      return '<button type="button" data-shelf="' + escapeHtml(shelf.id) + '" aria-pressed="' +
        (shelf.id === open ? "true" : "false") + '">' + escapeHtml(shelf.label) +
        '<span class="con-count">' + (shelf.offers || []).length + "</span></button>";
    }).join("");
    var shelf = shelves.filter(function (row) { return row.id === open; })[0] || shelves[0];
    var body = (shelf.offers || []).map(function (offer) {
      return '<tr class="' + (offer.edited ? "dirty" : "") + '">' +
        '<td><div class="con-entry">' + itemIcon(offer.material) + "<span>" +
        escapeHtml(titleCase(offer.material)) +
        (offer.edited ? '<em class="con-tag con-added">edited</em>' : "") +
        (offer.built_in ? "" : '<em class="con-tag con-added">added</em>') +
        "</span></div></td>" +
        '<td class="con-num"><input class="con-number tight" type="number" min="1" max="64" ' +
        'data-shop-amount="' + escapeHtml(offer.material) + '" value="' +
        escapeHtml(offer.amount) + '"></td>' +
        '<td class="con-num"><input class="con-number" type="number" min="1" ' +
        'data-shop-price="' + escapeHtml(offer.material) + '" value="' +
        escapeHtml(offer.price) + '"></td>' +
        '<td class="con-num"><button type="button" class="con-remove" data-shop-remove="' +
        escapeHtml(offer.material) + '" title="Take off the shelf">&times;</button>' +
        (offer.edited || !offer.built_in
          ? '<button type="button" class="con-move shown" data-shop-restore="' +
            escapeHtml(offer.material) + '">reset</button>'
          : "") + "</td></tr>";
    }).join("");
    var back = removed.length
      ? '<div class="con-removed">Off the shelf: ' + removed.map(function (material) {
          return '<button type="button" class="con-chip" data-shop-restore="' +
            escapeHtml(material) + '">' + escapeHtml(titleCase(material)) +
            " &#8635;</button>";
        }).join("") + "</div>"
      : "";
    return '<section class="con-table"><header><div><h3>Every item on sale</h3>' +
      "<p>Prices here are what a player pays, after the percentages above. Changing one " +
      "overrides the catalogue for that item only.</p></div>" +
      '<div class="con-table-actions">' +
      '<button type="button" class="con-secondary" id="con-shop-add">Add an item</button>' +
      "</div></header>" +
      '<div class="con-category-rail">' + rail + "</div>" + back +
      '<div class="con-table-scroll"><table><thead><tr><th>Item</th>' +
      '<th class="con-num">Amount</th><th class="con-num">Price</th>' +
      '<th class="con-num"></th></tr></thead><tbody>' + body + "</tbody></table></div>" +
      '<p class="con-table-note">An edit applies to the next player who opens the shop. ' +
      "Reset puts a built-in item back at its catalogue price.</p></section>";
  }

  function itemIcon(material) {
    return '<img class="con-icon" src="/assets/minecraft-items/' +
      escapeHtml(String(material).toLowerCase()) + '.png" alt="" loading="lazy">';
  }

  /**
   * Cosmetics an owner has added, and the effect each one wears.
   *
   * <p>A cosmetic's visual is dispatched by its id, so an invented one would be a name
   * with nothing behind it. An added cosmetic therefore borrows an effect that already
   * ships: it gets its own name, category, rarity and description, and it can go in a
   * crate, but what a player sees is one of the existing effects. Genuinely new artwork
   * still needs a build, which is stated rather than pretended away.
   */
  function cosmeticEditor() {
    var catalog = state.catalog || {};
    var effects = catalog.cosmetic_effects || [];
    if (!effects.length) return "";
    var added = catalog.cosmetics_added || [];
    var rows = added.map(function (row) {
      var worn = effects.filter(function (e) { return e.id === row.wears_effect; })[0];
      return "<tr><td><strong>" + escapeHtml(row.display_name) + "</strong>" +
        '<div class="con-muted">' + escapeHtml(row.id) + "</div></td>" +
        "<td>" + escapeHtml(titleCase(row.category)) + "</td>" +
        "<td>" + escapeHtml(worn ? worn.label : row.wears_effect) + "</td>" +
        '<td class="con-num">' + escapeHtml(row.weight) + "</td>" +
        '<td class="con-num"><button type="button" class="con-remove" ' +
        'data-cosmetic-remove="' + escapeHtml(row.id) + '">&times;</button></td></tr>';
    }).join("");
    return '<section class="con-table"><header><div><h3>Cosmetics you added</h3>' +
      "<p>An added cosmetic wears an effect that already ships. Its name, category, " +
      "rarity and description are yours; the visual is borrowed. New artwork needs a " +
      "build.</p></div><div class=\"con-table-actions\">" +
      '<button type="button" class="con-secondary" id="con-cosmetic-add">Add a cosmetic</button>' +
      "</div></header>" +
      (rows
        ? '<div class="con-table-scroll"><table><thead><tr><th>Cosmetic</th>' +
          "<th>Category</th><th>Wears</th><th class=\"con-num\">Weight</th>" +
          "<th class=\"con-num\"></th></tr></thead><tbody>" + rows + "</tbody></table></div>"
        : '<p class="con-empty">You have not added any cosmetics.</p>') +
      "</section>";
  }

  function openCosmeticDialog() {
    var effects = (state.catalog || {}).cosmetic_effects || [];
    byId("con-add-title").textContent = "Add a cosmetic";
    byId("con-add-body").innerHTML =
      '<div class="con-field-row">' +
      '<label class="con-field"><span>Name players see</span>' +
      '<input class="con-search-field" id="con-add-name" placeholder="Midnight Orbit"></label>' +
      '<label class="con-field"><span>Identifier</span>' +
      '<input class="con-search-field" id="con-add-material" placeholder="midnight_orbit"></label>' +
      "</div>" +
      '<label class="con-field"><span>Wears this effect</span>' +
      '<select class="con-choice" id="con-add-category">' + effects.map(function (effect) {
        return '<option value="' + escapeHtml(effect.id) + '">' +
          escapeHtml(effect.label) + " (" + escapeHtml(titleCase(effect.category)) +
          ")</option>";
      }).join("") + "</select>" +
      "<em>The visual is borrowed from this one; the category follows it.</em></label>" +
      '<label class="con-field"><span>Rarity weight</span>' +
      '<input class="con-number" id="con-add-amount" type="number" min="1" value="100">' +
      "<em>Higher is more common, the same scale as a crate reward.</em></label>";
    state.adding = {cosmetic: true};
    byId("con-add").hidden = false;
    window.setTimeout(function () { byId("con-add-name").focus(); }, 30);
  }

  function renderGroupPage(group) {
    var rows = groupRows(group);
    var intro = PAGE_INTROS[group]
      ? '<p class="con-intro">' + escapeHtml(PAGE_INTROS[group]) + "</p>" : "";
    if (!rows.length) {
      if (state.stalePlugin) return "";
      return state.search
        ? '<p class="con-empty">Nothing on this page matches &ldquo;' +
          escapeHtml(state.search) + "&rdquo;.</p>"
        : '<p class="con-empty">This page has no settings to show.</p>';
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

    var singleHtml = Object.keys(sections).sort(function (a, b) {
      // Bigger groups first: a page opening with a two-card section reads as an
      // afterthought before you reach what it is actually about.
      return sections[b].length - sections[a].length;
    }).map(function (heading) {
      return '<section class="con-section"><h3>' + escapeHtml(heading) +
        '<span class="con-section-count">' + sections[heading].length + "</span></h3>" +
        '<div class="con-grid">' + sections[heading].join("") + "</div></section>";
    }).join("");

    return intro + editors +
      (group === "shop" ? shopEditor() : "") +
      (group === "cosmetics" ? cosmeticEditor() : "") +
      singleHtml;
  }

  /** A task, opened. Shows only what bears on it, each explained. */
  function renderTask(task) {
    var rows = task.keys.map(function (key) { return state.byKey[key]; })
      .filter(function (row) { return !!row; });
    var strengths = task.strengths
      ? '<div class="con-strengths">' + task.strengths.map(function (option, index) {
          return '<button type="button" class="con-strength" data-strength="' +
            escapeHtml(task.id) + ":" + index + '"><strong>' +
            escapeHtml(option.label) + "</strong><em>preview the change</em></button>";
        }).join("") + "</div>"
      : '<p class="con-help wide">No single lever does this one — the settings below are ' +
        "the ones that matter. Change what you like and publish when you are happy.</p>";
    return '<article class="con-task-open"><header><button type="button" class="con-link" ' +
      'data-task="">&larr; All tasks</button><h2>' + escapeHtml(task.title) + "</h2>" +
      "<p>" + escapeHtml(task.blurb) + "</p></header>" + strengths +
      '<h3>What this touches</h3><div class="con-grid">' +
      rows.map(settingCard).join("") + "</div></article>";
  }

  function taskCard(task) {
    return '<button type="button" class="con-task" data-task="' + escapeHtml(task.id) + '">' +
      "<strong>" + escapeHtml(task.title) + "</strong>" +
      "<span>" + escapeHtml(task.blurb) + "</span>" +
      '<em>' + task.keys.length + " setting" + (task.keys.length === 1 ? "" : "s") +
      "</em></button>";
  }

  /**
   * What moved, without being asked.
   *
   * <p>Spotting that the economy doubled or that airdrop claims stopped is the actual
   * job, and until now it meant going and looking. These are computed from the same
   * series the charts use: a large move over the window, or a counter that has stopped
   * moving at all. Deliberately few and deliberately dull — a noticeboard that cries
   * wolf gets ignored, which is worse than not having one.
   */
  function noticeboard() {
    var series = state.statSeries || {};
    var names = Object.keys(series);
    if (!names.length) return "";
    var notes = [];
    names.forEach(function (name) {
      var points = series[name] || [];
      if (points.length < 4) return;
      var first = Number(points[0].value) || 0;
      var last = Number(points[points.length - 1].value) || 0;
      var label = metricLabel(name);
      if (first > 0) {
        var move = ((last - first) / first) * 100;
        if (Math.abs(move) >= 25) {
          notes.push({
            tone: move > 0 ? "up" : "down",
            text: label + " " + (move > 0 ? "rose" : "fell") + " " +
              Math.abs(Math.round(move)) + "% over this window, " +
              compactNumber(first) + " to " + compactNumber(last) + "."
          });
        }
      }
      // A counter that stopped is the quiet failure: nothing errors, the thing just
      // stops happening. Only meaningful for counters, which never decrease.
      var half = points.slice(Math.floor(points.length / 2));
      var moved = half.some(function (point, index) {
        return index > 0 && Number(point.value) !== Number(half[index - 1].value);
      });
      if (!moved && /opened|claimed|spawned|sales|completed/.test(name) && last > 0) {
        notes.push({
          tone: "down",
          text: label + " has not moved in the second half of this window."
        });
      }
    });
    var concentration = economyConcentration();
    if (concentration) notes.push(concentration);
    if (!notes.length) return "";
    return '<section class="con-section"><h3>Worth a look' +
      '<span class="con-section-count">' + notes.length + "</span></h3>" +
      '<div class="con-notices">' + notes.slice(0, 6).map(function (note) {
        return '<p class="con-notice ' + note.tone + '">' + escapeHtml(note.text) + "</p>";
      }).join("") + "</div></section>";
  }

  /** One player holding most of the money is worth saying out loud. */
  function economyConcentration() {
    var series = state.statSeries || {};
    var total = (series["economy.total_balance"] || []).slice(-1)[0];
    var richest = (series["economy.richest_balance"] || []).slice(-1)[0];
    if (!total || !richest || !Number(total.value)) return null;
    var share = (Number(richest.value) / Number(total.value)) * 100;
    // One player cannot hold more than all of it. Above 100 means the two samples were
    // taken at different moments or a counter reset, and reporting it as a finding is
    // how a noticeboard stops being believed.
    if (share < 30 || share > 100) return null;
    return {
      tone: "down",
      text: "One player holds " + Math.round(share) + "% of all the money on the server."
    };
  }

  function renderOverview() {
    if (state.task) {
      var open = TASKS.filter(function (entry) { return entry.id === state.task; })[0];
      if (open) return renderTask(open);
    }
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

    var tasks = '<section class="con-section"><h3>What do you want to do?</h3>' +
      '<p class="con-help wide">Pick the thing you are trying to change. Each one gathers ' +
      "the settings that bear on it and explains what they do, so you do not have to know " +
      "where anything lives. Or use the pages on the left to go straight to a value.</p>" +
      '<div class="con-tasks">' + TASKS.map(taskCard).join("") + "</div></section>";
    return noticeboard() + tasks + '<div class="con-stats">' + cards + "</div>" +
      changed + lag + history;
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

  /**
   * The things an owner can do, as forms rather than commands.
   *
   * Each action declares its own arguments, so this draws whatever the plugin offers
   * instead of hard-coding a list the two sides then have to keep in step.
   */
  function renderActions() {
    if (!state.actions.length) {
      return '<p class="con-empty">The connected server has not offered any actions. It ' +
        "may be running a plugin older than the panel.</p>";
    }
    var groups = {};
    state.actions.forEach(function (action) {
      (groups[action.group] = groups[action.group] || []).push(action);
    });
    return Object.keys(groups).map(function (group) {
      // Actions are small forms, not settings rows, so they tile instead of stacking
      // full-width — a one-field card the width of the screen reads as a form to fill in.
      return '<section class="con-section"><h3>' + escapeHtml(group) + "</h3>" +
        '<div class="con-grid con-cards">' + groups[group].map(actionCard).join("") +
        "</div></section>";
    }).join("");
  }

  function actionField(action, param) {
    var id = "act-" + action.id.replace(/\./g, "-") + "-" + param.name;
    var control;
    if (param.type === "choice") {
      control = '<select class="con-choice" id="' + escapeHtml(id) + '">' +
        (param.choices || []).map(function (option) {
          return '<option value="' + escapeHtml(option) + '">' +
            escapeHtml(titleCase(option)) + "</option>";
        }).join("") + "</select>";
    } else if (param.type === "player") {
      control = '<input class="con-search-field" id="' + escapeHtml(id) +
        '" list="con-online" placeholder="' +
        (state.online.length ? escapeHtml(state.online[0]) : "Nobody is online") + '">';
    } else {
      control = '<input class="con-number" type="number" id="' + escapeHtml(id) + '"' +
        (param.required ? "" : ' placeholder="optional"') + ">";
    }
    return '<label class="con-field"><span>' + escapeHtml(param.label) + "</span>" + control +
      (param.help ? "<em>" + escapeHtml(param.help) + "</em>" : "") + "</label>";
  }

  function actionCard(action) {
    return '<article class="con-setting con-action" data-action="' + escapeHtml(action.id) + '">' +
      '<div class="con-setting-head"><h4>' + escapeHtml(action.label) + "</h4></div>" +
      '<p class="con-help">' + escapeHtml(action.description) + "</p>" +
      (action.params || []).map(function (param) {
        return actionField(action, param);
      }).join("") +
      (action.confirm
        ? '<p class="con-warn">' + escapeHtml(action.confirm) + "</p>"
        : "") +
      // Colour carries consequence, not just clickability: an action that declares a
      // confirmation is one that reaches every player, so it is the one that looks it.
      // Eleven orange buttons on one page told an owner nothing about which to be careful with.
      '<div class="con-setting-foot"><button type="button" class="' +
      (action.confirm ? "con-danger" : "con-secondary") + '" data-run="' +
      escapeHtml(action.id) + '">' + escapeHtml(action.label) + "</button></div></article>";
  }

  /**
   * Stages a task's suggested change without publishing it.
   *
   * Deliberately a draft, not an action. The whole point is that you see exactly what it
   * would do — in the cards below and in the review dialog — before anything reaches the
   * server.
   */
  function applyStrength(token) {
    var parts = token.split(":");
    var task = TASKS.filter(function (entry) { return entry.id === parts[0]; })[0];
    if (!task || !task.strengths) return;
    var option = task.strengths[Number(parts[1])];
    var edits = task.apply(option.factor !== undefined ? option.factor : option);
    if (!edits.length) {
      toast("That would not change anything from where the values are now.", false);
      return;
    }
    edits.forEach(function (edit) { setDraft(edit.key, Number(edit.value)); });
    toast(option.label + " staged — " + edits.length +
      " change(s) below. Review before publishing.", false);
  }

  async function runAction(id) {
    var action = state.actions.filter(function (entry) { return entry.id === id; })[0];
    if (!action) return;
    if (action.confirm &&
        !await confirmThat(action.label, action.confirm, action.label)) return;
    var args = {};
    var missing = null;
    (action.params || []).forEach(function (param) {
      var field = byId("act-" + id.replace(/\./g, "-") + "-" + param.name);
      var value = field ? String(field.value || "").trim() : "";
      if (!value && param.required) missing = param.label;
      if (value) args[param.name] = value;
    });
    if (missing) { toast(missing + " is required.", true); return; }
    try {
      var result = await post("/api/action", {id: id, arguments: args});
      toast((result && result.message) || "Done.", false);
      await loadSettings();
      render();
    } catch (error) {
      toast(error.message, true);
    }
  }

  /** What has happened in game lately, by category. */
  /* ---------- statistics ---------- */

  var STEVE_HEAD =
    "https://minotar.net/helm/8667ba71-b85a-4004-af54-457a9734eed7/32.png";

  /** Metric keys are stored machine-side; nobody wants to read snake_case in a chart. */
  function metricLabel(name) {
    return titleCase(String(name).replace(/[._]/g, " "));
  }

  function compactNumber(value) {
    var n = Number(value) || 0;
    if (Math.abs(n) >= 1e9) return (n / 1e9).toFixed(1).replace(/\.0$/, "") + "B";
    if (Math.abs(n) >= 1e6) return (n / 1e6).toFixed(1).replace(/\.0$/, "") + "M";
    if (Math.abs(n) >= 1e3) return (n / 1e3).toFixed(1).replace(/\.0$/, "") + "K";
    return String(Math.round(n * 100) / 100);
  }

  function duration(seconds) {
    var total = Math.max(0, Math.round(Number(seconds) || 0));
    var hours = Math.floor(total / 3600);
    var minutes = Math.round((total % 3600) / 60);
    if (hours >= 24) {
      var days = Math.floor(hours / 24);
      return days + "d " + (hours % 24) + "h";
    }
    return hours ? hours + "h " + minutes + "m" : minutes + "m";
  }

  /**
   * One metric drawn as an area chart, in SVG.
   *
   * Hand-drawn rather than pulled from a charting library: the whole series is a few
   * hundred points, the shapes needed are a filled area and a line, and a library would
   * be a large dependency for something the page can express exactly. SVG also stays
   * sharp at any size and needs no canvas sizing dance on a hidden tab.
   */
  /**
   * When settings were published, as marks on a chart.
   *
   * <p>The panel already knew both halves and never joined them: publish history has
   * timestamps, the series has values over time. Drawing one against the other is the
   * difference between "the economy grew" and "the economy grew after you did that".
   */
  function publishMarks(first, span) {
    return ((state.snapshot || {}).history || []).map(function (publish) {
      var at = Math.floor(Number(publish.at) / 1000);
      if (!at || at < first || at > first + span) return null;
      return {
        at: at,
        count: publish.change_count || (publish.changes || []).length,
        actor: publish.actor || "",
        labels: (publish.changes || []).slice(0, 4).map(function (change) {
          var row = state.byKey[change.key];
          return (row ? row.label : change.key) + " " + change.before + " \u2192 " + change.after;
        })
      };
    }).filter(Boolean);
  }

  function areaChart(points) {
    var width = 560;
    var height = 130;
    var pad = {top: 10, right: 6, bottom: 16, left: 6};
    if (!points || points.length < 2) {
      return '<p class="con-chart-empty">Not enough samples yet — a chart needs at ' +
        "least two.</p>";
    }
    var values = points.map(function (p) { return Number(p.value) || 0; });
    var low = Math.min.apply(null, values);
    var high = Math.max.apply(null, values);
    // A flat series would divide by zero and draw on the baseline; give it headroom so
    // "steady at 12" still reads as a line rather than as nothing.
    if (high === low) { high = low + 1; }
    var innerW = width - pad.left - pad.right;
    var innerH = height - pad.top - pad.bottom;
    var first = points[0].at;
    var span = Math.max(1, points[points.length - 1].at - first);

    var coords = points.map(function (p) {
      var x = pad.left + ((p.at - first) / span) * innerW;
      var y = pad.top + innerH - (((Number(p.value) || 0) - low) / (high - low)) * innerH;
      return [x, y];
    });
    var line = coords.map(function (c, i) {
      return (i ? "L" : "M") + c[0].toFixed(1) + " " + c[1].toFixed(1);
    }).join(" ");
    var area = line + " L" + coords[coords.length - 1][0].toFixed(1) + " " +
      (pad.top + innerH) + " L" + coords[0][0].toFixed(1) + " " + (pad.top + innerH) + " Z";
    var last = coords[coords.length - 1];

    var gridlines = [0, 0.5, 1].map(function (fraction) {
      var y = (pad.top + innerH - fraction * innerH).toFixed(1);
      return '<line x1="' + pad.left + '" x2="' + (width - pad.right) + '" y1="' + y +
        '" y2="' + y + '" class="con-chart-grid"/>';
    }).join("");

    var marks = publishMarks(first, span).map(function (mark) {
      var x = (pad.left + ((mark.at - first) / span) * innerW).toFixed(1);
      return '<line x1="' + x + '" x2="' + x + '" y1="' + pad.top + '" y2="' +
        (pad.top + innerH) + '" class="con-chart-mark"><title>' +
        escapeHtml(new Date(mark.at * 1000).toLocaleString() + " \u2014 " + mark.count +
          " setting(s) published" + (mark.actor ? " by " + mark.actor : "") +
          (mark.labels.length ? "\n" + mark.labels.join("\n") : "")) +
        "</title></line>";
    }).join("");

    return '<div class="con-chart-wrap"><svg class="con-chart" viewBox="0 0 ' + width + " " + height +
      '" preserveAspectRatio="none" role="img" aria-label="' +
      escapeHtml(compactNumber(values[values.length - 1]) + " now, " +
        compactNumber(low) + " to " + compactNumber(high) + " over the window") + '">' +
      gridlines + marks +
      '<path d="' + area + '" class="con-chart-area"/>' +
      '<path d="' + line + '" class="con-chart-line"/>' +
      '<circle cx="' + last[0].toFixed(1) + '" cy="' + last[1].toFixed(1) +
      '" r="3" class="con-chart-point"/>' +
      "</svg>" +
      '<div class="con-chart-scale"><span>' + compactNumber(high) + "</span><span>" +
      compactNumber(low) + "</span></div></div>";
  }

  function chartCard(name, points) {
    var values = (points || []).map(function (p) { return Number(p.value) || 0; });
    var now = values.length ? values[values.length - 1] : 0;
    var previous = values.length > 1 ? values[0] : now;
    var delta = now - previous;
    var direction = delta > 0 ? "up" : delta < 0 ? "down" : "flat";
    return '<article class="con-chart-card"><header><h4>' +
      escapeHtml(metricLabel(name)) + "</h4>" +
      '<div><strong>' + compactNumber(now) + "</strong>" +
      '<span class="con-delta ' + direction + '">' +
      (direction === "flat" ? "no change"
        : (delta > 0 ? "+" : "") + compactNumber(delta) + " over the window") +
      "</span></div></header>" + areaChart(points) + "</article>";
  }

  function seriesSection() {
    var stats = state.stats || {};
    var metrics = stats.metrics || [];
    if (!metrics.length) {
      return '<section class="con-section"><h3>Over time</h3>' +
        '<p class="con-empty">Nothing has been sampled yet. The first charts appear ' +
        "once two samples exist.</p></section>";
    }
    var chosen = state.statMetrics.length ? state.statMetrics : metrics.slice(0, 4);
    var rail = metrics.map(function (name) {
      return '<button type="button" data-metric="' + escapeHtml(name) + '" aria-pressed="' +
        (chosen.indexOf(name) >= 0 ? "true" : "false") + '">' +
        escapeHtml(metricLabel(name)) + "</button>";
    }).join("");
    var charts = chosen.map(function (name) {
      var points = (state.statSeries || {})[name];
      return points === undefined
        ? '<article class="con-chart-card"><header><h4>' + escapeHtml(metricLabel(name)) +
          "</h4></header><p class=\"con-chart-empty\">Loading&hellip;</p></article>"
        : chartCard(name, points);
    }).join("");
    return '<section class="con-section"><h3>Over time' +
      '<span class="con-section-count">' + chosen.length + " of " + metrics.length +
      "</span></h3>" +
      '<p class="con-help wide">Sampled every ' +
      Math.round((stats.sampled_every_seconds || 900) / 60) +
      " minutes. History starts when sampling was switched on &mdash; earlier periods " +
      "cannot be reconstructed. Pick what to chart:</p>" +
      '<div class="con-category-rail">' + rail + "</div>" +
      '<div class="con-chart-grid">' + charts + "</div></section>";
  }

  /** Where the players actually come from, as one bar rather than a caption. */
  function editionSplit(activity) {
    var java = Number(activity.java_joins) || 0;
    var bedrock = Number(activity.bedrock_joins) || 0;
    var total = java + bedrock;
    if (!total) return "";
    var javaShare = (java / total) * 100;
    return '<section class="con-section"><h3>Where players join from</h3>' +
      '<div class="con-dist" role="img" aria-label="' + java + ' Java, ' + bedrock +
      ' Bedrock">' +
      '<span class="con-dist-slice tone-1" style="width:' + javaShare.toFixed(2) +
      '%" title="Java ' + java + '"></span>' +
      '<span class="con-dist-slice tone-2" style="width:' + (100 - javaShare).toFixed(2) +
      '%" title="Bedrock ' + bedrock + '"></span></div>' +
      '<p class="con-table-note"><strong>' + java.toLocaleString() + "</strong> Java (" +
      javaShare.toFixed(1) + "%) &middot; <strong>" + bedrock.toLocaleString() +
      "</strong> Bedrock (" + (100 - javaShare).toFixed(1) + "%)</p></section>";
  }

  function statTiles(stats) {
    var activity = stats.activity || {};
    var access = stats.access || {};
    var afk = stats.afk || {};
    var records = Object.keys(access).reduce(function (sum, key) {
      return sum + (Number(access[key]) || 0);
    }, 0);
    return '<div class="con-stats">' + [
      ["Online now", Number(activity.current || 0).toLocaleString(), "players connected"],
      ["Busiest it got", Number(activity.peak || 0).toLocaleString(),
        activity.peak_at
          ? "on " + new Date(activity.peak_at * 1000).toLocaleString()
          : "no peak recorded yet"],
      ["Joins", Number(activity.joins || 0).toLocaleString(),
        (activity.java_joins || 0) + " Java, " + (activity.bedrock_joins || 0) + " Bedrock"],
      ["Verified accounts", Number(access.VERIFIED || 0).toLocaleString(),
        records.toLocaleString() + " access records in total"],
      ["Time spent idle", duration(afk.total_seconds || 0),
        "peak " + (afk.peak_afk || 0) + " AFK at once"]
    ].map(function (tile) {
      return '<div class="con-stat"><span>' + escapeHtml(tile[0]) + "</span><strong>" +
        escapeHtml(tile[1]) + "</strong><em>" + escapeHtml(tile[2]) + "</em></div>";
    }).join("") + "</div>";
  }

  /** When people play, as a weekday-by-hour grid. */
  function busiestHours(busiest) {
    if (!busiest.length) {
      return '<section class="con-section"><h3>When people play</h3>' +
        '<p class="con-empty">Not enough samples yet.</p></section>';
    }
    var peak = busiest.reduce(function (top, row) {
      return Math.max(top, Number(row.average) || 0);
    }, 0) || 1;
    var days = ["Mon", "Tue", "Wed", "Thu", "Fri", "Sat", "Sun"];
    var grid = {};
    busiest.forEach(function (row) {
      grid[row.weekday + ":" + row.hour] = Number(row.average) || 0;
    });
    var head = "<tr><th></th>";
    for (var hour = 0; hour < 24; hour++) {
      // Not con-num: that sets width 1% for loot-table figures, which here would give
      // every hour a sliver and hand the rest to the weekday label.
      head += "<th>" + hour + "</th>";
    }
    head += "</tr>";
    var body = days.map(function (name, index) {
      var cells = "";
      for (var h = 0; h < 24; h++) {
        var value = grid[index + ":" + h];
        var share = value === undefined ? 0 : value / peak;
        cells += '<td class="con-heat" style="--heat:' + share.toFixed(3) + '" title="' +
          name + " " + h + ":00 — " + (value === undefined ? "no samples"
            : value.toFixed(1) + " players") + '"></td>';
      }
      return "<tr><th>" + name + "</th>" + cells + "</tr>";
    }).join("");
    return '<section class="con-section"><h3>When people play' +
      '<span class="con-section-count">peak ' + peak.toFixed(1) + "</span></h3>" +
      '<div class="con-table-scroll"><table class="con-heatmap"><thead>' + head +
      "</thead><tbody>" + body + "</tbody></table></div>" +
      '<p class="con-table-note">Average players online, by weekday and hour, in server ' +
      "time. Darker is busier.</p></section>";
  }

  function head(url) {
    return '<img class="con-avatar" src="' + escapeHtml(url || STEVE_HEAD) +
      '" alt="" loading="lazy">';
  }

  function afkSection(afk) {
    var players = afk.players || [];
    var byEdition = afk.by_edition || {};
    if (!players.length && !afk.total_seconds) {
      return "";
    }
    var summary = '<div class="con-stats">' + [
      ["Idle on Java", duration(byEdition.JAVA || 0), "across the window"],
      ["Idle on Bedrock", duration(byEdition.BEDROCK || 0), "across the window"],
      ["Players who went idle", String(players.length), "at least once"]
    ].map(function (tile) {
      return '<div class="con-stat"><span>' + escapeHtml(tile[0]) + "</span><strong>" +
        escapeHtml(tile[1]) + "</strong><em>" + escapeHtml(tile[2]) + "</em></div>";
    }).join("") + "</div>";
    var rows = players.slice(0, 25).map(function (row, index) {
      return "<tr><td class=\"con-num con-muted\">" + (index + 1) + "</td>" +
        '<td><div class="con-entry">' + head(row.head_url) + "<span>" +
        escapeHtml(row.username || "unknown") + "</span></div></td>" +
        "<td>" + (row.edition === "BEDROCK" ? "Bedrock" : "Java") + "</td>" +
        '<td class="con-num">' + escapeHtml(duration(row.afk_seconds)) + "</td>" +
        '<td class="con-num con-muted">' + escapeHtml(String(row.sessions || 0)) +
        "</td></tr>";
    }).join("");
    return '<section class="con-section"><h3>Idle time' +
      '<span class="con-section-count">' + players.length + "</span></h3>" + summary +
      (rows
        ? '<div class="con-table-scroll"><table><thead><tr><th class="con-num">#</th>' +
          "<th>Player</th><th>Edition</th><th class=\"con-num\">Idle</th>" +
          "<th class=\"con-num\">Stretches</th></tr></thead><tbody>" + rows +
          "</tbody></table></div>"
        : "") + "</section>";
  }

  /** The standings the server pushes, individual and clan. */
  function boardsSection(snapshot) {
    var html = "";
    ["individual", "clan"].forEach(function (group) {
      var boards = (snapshot || {})[group] || {};
      Object.keys(boards).forEach(function (key) {
        var rows = boards[key];
        if (!Array.isArray(rows) || !rows.length) return;
        html += '<article class="con-board"><h4>' +
          escapeHtml(titleCase(key.replace(/_/g, " "))) + "</h4><ol>" +
          rows.slice(0, 10).map(function (row) {
            return "<li><span>" + head(row.head_url) +
              escapeHtml(row.username || row.name || "") + "</span><b>" +
              escapeHtml(compactNumber(row.value != null ? row.value : row.score || 0)) +
              "</b></li>";
          }).join("") + "</ol></article>";
      });
    });
    if (!html) return "";
    return '<section class="con-section"><h3>Standings</h3>' +
      '<div class="con-boards">' + html + "</div></section>";
  }

  /**
   * Server statistics, drawn with the console's own components.
   *
   * These lived on a separate owner page with a separate stylesheet and a separate
   * sign-in, which meant checking whether a change had worked involved leaving the place
   * you made it. Same data, same shell — and all of it, including the sampled history
   * and the standings, not just the headline figures.
   */
  function renderStatistics() {
    var stats = state.stats;
    if (!stats) {
      return '<p class="con-empty">Loading statistics&hellip;</p>';
    }
    if (stats.error) {
      return '<p class="con-empty">Statistics are unavailable: ' +
        escapeHtml(stats.error) + "</p>";
    }
    var windows = [1, 7, 30, 90, 365].map(function (days) {
      return '<button type="button" data-stat-days="' + days + '" aria-pressed="' +
        (state.statDays === days ? "true" : "false") + '">' +
        (days === 1 ? "24 hours" : days === 365 ? "1 year" : days + " days") + "</button>";
    }).join("");
    return '<p class="con-intro">How the server has actually been used over the window ' +
      "you pick. These are observations, not settings &mdash; nothing here is editable.</p>" +
      '<div class="con-category-rail">' + windows + "</div>" +
      statTiles(stats) +
      seriesSection() +
      busiestHours((stats.activity || {}).busiest || []) +
      editionSplit(stats.activity || {}) +
      afkSection(stats.afk || {}) +
      boardsSection(stats.leaderboards || {});
  }

  /**
   * Named setting sets, and a file you can keep.
   *
   * <p>Publish history undoes one mistake; it does not put everything back the way it
   * was on Tuesday. A preset is that: the values that differ from default, saved under a
   * name. Applying one *stages* it as a draft rather than writing it, so a preset goes
   * through the same validation, review and audit trail as anything typed by hand — and
   * so you see exactly what it would change before it changes.
   */
  function renderPresets() {
    var presets = state.presets;
    if (!presets) return '<p class="con-empty">Loading&hellip;</p>';
    if (presets.error) {
      return '<p class="con-empty">Presets are unavailable: ' +
        escapeHtml(presets.error) + "</p>";
    }
    var changed = state.rows.filter(function (row) { return row.overridden; });
    var list = (presets.list || []).map(function (preset) {
      return '<article class="con-preset"><div><h4>' + escapeHtml(preset.name) + "</h4>" +
        "<span>" + preset.count + " setting" + (preset.count === 1 ? "" : "s") +
        (preset.saved_at
          ? " &middot; saved " + new Date(preset.saved_at * 1000).toLocaleDateString()
          : "") + "</span></div>" +
        '<div class="con-table-actions">' +
        '<button type="button" class="con-secondary" data-preset-apply="' +
        escapeHtml(preset.name) + '">Stage it</button>' +
        '<button type="button" class="con-remove" data-preset-delete="' +
        escapeHtml(preset.name) + '">Delete</button></div></article>';
    }).join("");

    return '<p class="con-intro">A preset is the values that differ from default, saved ' +
      "under a name. Applying one stages it as a draft, so you review and publish it like " +
      "any other change &mdash; nothing is written behind your back.</p>" +
      '<section class="con-section"><h3>Save what is set now' +
      '<span class="con-section-count">' + changed.length + " changed</span></h3>" +
      '<div class="con-field-row">' +
      '<label class="con-field"><span>Name it</span>' +
      '<input class="con-search-field" id="con-preset-name" placeholder="Weekend event" ' +
      'maxlength="60"></label>' +
      '<div class="con-table-actions">' +
      '<button type="button" class="con-primary" id="con-preset-save"' +
      (changed.length ? "" : " disabled") + ">Save preset</button>" +
      '<button type="button" class="con-secondary" id="con-preset-export">Export a file</button>' +
      '<button type="button" class="con-secondary" id="con-preset-import">Import a file</button>' +
      '<input type="file" id="con-preset-file" accept="application/json" hidden>' +
      "</div></div>" +
      (changed.length
        ? ""
        : '<p class="con-table-note">Everything is at its default, so there is nothing ' +
          "to save yet.</p>") + "</section>" +
      '<section class="con-section"><h3>Saved presets' +
      '<span class="con-section-count">' + (presets.list || []).length + "</span></h3>" +
      (list ? '<div class="con-presets">' + list + "</div>"
            : '<p class="con-empty">No presets saved yet.</p>') + "</section>";
  }

  /**
   * Actions booked ahead of time.
   *
   * <p>"Do something" runs an event now; this runs one on Saturday at six. The bot keeps
   * the clock because it survives a Minecraft restart, and nothing fires while the plugin
   * is away — an event announced to a server that cannot hear it is worse than one that
   * did not run.
   */
  function renderSchedule() {
    var data = state.schedule;
    if (!data) return '<p class="con-empty">Loading&hellip;</p>';
    if (data.error) {
      return '<p class="con-empty">The schedule is unavailable: ' +
        escapeHtml(data.error) + "</p>";
    }
    var actions = data.actions || [];
    if (!actions.length) {
      return '<p class="con-empty">The connected server has not offered any actions to ' +
        "schedule.</p>";
    }
    var rows = (data.entries || []).slice().sort(function (a, b) {
      return a.run_at - b.run_at;
    }).map(function (entry) {
      var action = actions.filter(function (row) { return row.id === entry.action; })[0];
      var when = new Date(entry.run_at * 1000);
      var overdue = entry.run_at < (data.now || 0);
      return '<tr class="' + (entry.enabled ? "" : "con-muted") + '">' +
        "<td><strong>" + escapeHtml(action ? action.label : entry.action) + "</strong>" +
        (entry.label ? '<div class="con-muted">' + escapeHtml(entry.label) + "</div>" : "") +
        "</td>" +
        "<td>" + escapeHtml(when.toLocaleString()) +
        (overdue && entry.enabled ? '<div class="con-warn">due</div>' : "") + "</td>" +
        "<td>" + (entry.repeat_days
          ? "every " + entry.repeat_days + " day" + (entry.repeat_days === 1 ? "" : "s")
          : '<span class="con-muted">once</span>') + "</td>" +
        "<td>" + (entry.last_run_at
          ? '<span class="con-muted">' + escapeHtml(entry.last_result || "ran") + "</span>"
          : '<span class="con-muted">not yet</span>') + "</td>" +
        '<td class="con-num"><button type="button" class="con-remove" ' +
        'data-schedule-delete="' + escapeHtml(entry.id) + '">&times;</button></td></tr>';
    }).join("");

    var options = actions.map(function (action) {
      return '<option value="' + escapeHtml(action.id) + '">' +
        escapeHtml(action.label) + "</option>";
    }).join("");

    return '<p class="con-intro">Book an action for later. It runs once at the time you ' +
      "pick, or every so many days from then. Anything more than fifteen minutes overdue " +
      "is skipped rather than run late &mdash; nobody wants Saturday\u2019s event starting " +
      "on Sunday morning.</p>" +
      '<section class="con-section"><h3>Book something</h3>' +
      '<div class="con-field-row">' +
      '<label class="con-field"><span>What</span>' +
      '<select class="con-choice" id="con-sched-action">' + options + "</select></label>" +
      '<label class="con-field"><span>When</span>' +
      '<input class="con-search-field" id="con-sched-at" type="datetime-local"></label>' +
      '<label class="con-field"><span>Repeat every (days, 0 = once)</span>' +
      '<input class="con-number" id="con-sched-repeat" type="number" min="0" max="365" value="0">' +
      "</label>" +
      '<div class="con-table-actions">' +
      '<button type="button" class="con-primary" id="con-sched-add">Book it</button>' +
      "</div></div>" +
      '<p class="con-table-note">Times are read in this browser\u2019s timezone.</p>' +
      "</section>" +
      '<section class="con-section"><h3>Booked' +
      '<span class="con-section-count">' + (data.entries || []).length + "</span></h3>" +
      (rows
        ? '<div class="con-table-scroll"><table><thead><tr><th>Action</th><th>When</th>' +
          "<th>Repeat</th><th>Last run</th><th class=\"con-num\"></th></tr></thead>" +
          "<tbody>" + rows + "</tbody></table></div>"
        : '<p class="con-empty">Nothing is booked.</p>') + "</section>";
  }

  /**
   * Everything the panel knows about one player, in one place.
   *
   * <p>There was no per-player view anywhere, which made the most common thing an owner
   * does — someone reports a problem, you go and look at them — the one thing the panel
   * could not help with. Assembled from what the bot holds and the plugin's last
   * snapshot, so it still answers when the server is disconnected, with what was true
   * when it last spoke.
   */
  function renderPlayer() {
    var found = state.player;
    var box = '<section class="con-section"><h3>Look up a player</h3>' +
      '<div class="con-field-row">' +
      '<label class="con-field"><span>Name</span>' +
      '<input class="con-search-field" id="con-player-name" placeholder="Fatcat1440" value="' +
      escapeHtml(state.playerQuery || "") + '"></label>' +
      '<div class="con-table-actions">' +
      '<button type="button" class="con-primary" id="con-player-find">Look up</button>' +
      "</div></div></section>";
    if (!found) {
      return '<p class="con-intro">Balances, standings, linked accounts and idle time ' +
        "for one player. Partial names work.</p>" + box;
    }
    if (found.error) {
      return box + '<p class="con-empty">' + escapeHtml(found.error) + "</p>";
    }
    if (!found.found) {
      return box + '<p class="con-empty">Nobody matching &ldquo;' +
        escapeHtml(found.query) + "&rdquo; has a linked account.</p>";
    }
    var account = found.account || {};
    var others = (found.matches || []).filter(function (name) {
      return name && name !== account.username;
    });
    var tiles = [
      ["Edition", account.edition === "BEDROCK" ? "Bedrock" : "Java", "how they connect"],
      ["Last seen", account.last_seen_at
        ? new Date(account.last_seen_at * 1000).toLocaleDateString() : "unknown",
        "most recent session"],
      ["Idle time", duration((found.afk || {}).seconds || 0),
        ((found.afk || {}).sessions || 0) + " stretch(es), 30 days"],
      ["Linked accounts", String((found.linked_accounts || []).length),
        "on the same Discord"]
    ].map(function (tile) {
      return '<div class="con-stat"><span>' + escapeHtml(tile[0]) + "</span><strong>" +
        escapeHtml(tile[1]) + "</strong><em>" + escapeHtml(tile[2]) + "</em></div>";
    }).join("");

    var standings = (found.standings || []).map(function (row) {
      return "<tr><td>" + escapeHtml(titleCase(row.board.replace(/_/g, " "))) + "</td>" +
        '<td class="con-num">#' + escapeHtml(row.rank) + "</td>" +
        '<td class="con-num">' + escapeHtml(compactNumber(row.value)) + "</td></tr>";
    }).join("");

    var linked = (found.linked_accounts || []).map(function (row) {
      return '<span class="con-chip">' + escapeHtml(row.username || "?") + " &middot; " +
        (row.edition === "BEDROCK" ? "Bedrock" : "Java") + "</span>";
    }).join("");

    var listings = (found.listings || []).map(function (row) {
      return "<tr><td>" + escapeHtml(row.display_name || titleCase(row.material || "")) +
        '</td><td class="con-num">' + escapeHtml(row.amount || 1) +
        '</td><td class="con-num">' + escapeHtml(compactNumber(row.price || 0)) +
        "</td></tr>";
    }).join("");

    return box +
      '<section class="con-section"><h3>' + escapeHtml(account.username || "") +
      (found.discord_user_id
        ? '<span class="con-section-count">Discord ' +
          escapeHtml(found.discord_user_id) + "</span>"
        : '<span class="con-section-count">no Discord link</span>') + "</h3>" +
      '<div class="con-stats">' + tiles + "</div>" +
      (linked ? '<div class="con-removed">Also plays as: ' + linked + "</div>" : "") +
      (others.length
        ? '<p class="con-table-note">Other names matching that search: ' +
          others.map(escapeHtml).join(", ") + "</p>"
        : "") + "</section>" +
      (standings
        ? '<section class="con-section"><h3>Standings</h3>' +
          '<div class="con-table-scroll"><table><thead><tr><th>Board</th>' +
          '<th class="con-num">Rank</th><th class="con-num">Value</th></tr></thead>' +
          "<tbody>" + standings + "</tbody></table></div></section>"
        : "") +
      (listings
        ? '<section class="con-section"><h3>Selling right now' +
          '<span class="con-section-count">' + (found.listings || []).length +
          "</span></h3>" +
          '<div class="con-table-scroll"><table><thead><tr><th>Item</th>' +
          '<th class="con-num">Amount</th><th class="con-num">Price</th></tr></thead>' +
          "<tbody>" + listings + "</tbody></table></div></section>"
        : "");
  }

  function renderActivity() {
    var feed = state.activity || {};
    var entries = feed.entries || [];
    if (!entries.length) {
      return '<p class="con-empty">Nothing has been recorded yet. The last ' +
        escapeHtml(feed.retained || 300) + " in-game actions show here as they happen.</p>";
    }
    var categories = ["all"].concat(feed.categories || []);
    var shown = entries.filter(function (entry) {
      return state.logFilter === "all" || entry.category === state.logFilter;
    });
    return '<div class="con-category-rail">' + categories.map(function (name) {
        var count = name === "all" ? entries.length : entries.filter(function (entry) {
          return entry.category === name;
        }).length;
        return '<button type="button" data-log="' + escapeHtml(name) + '" aria-pressed="' +
          (state.logFilter === name ? "true" : "false") + '">' + escapeHtml(titleCase(name)) +
          '<span class="con-count">' + count + "</span></button>";
      }).join("") + "</div>" +
      '<div class="con-log">' + shown.map(function (entry) {
        return '<div class="con-log-row"><span class="con-log-cat">' +
          escapeHtml(entry.category || "other") + "</span>" +
          '<div><strong>' + escapeHtml(entry.summary) + "</strong>" +
          (entry.actor ? '<span class="con-log-actor">' + escapeHtml(entry.actor) + "</span>" : "") +
          "</div><time>" + escapeHtml(new Date(Number(entry.at)).toLocaleTimeString()) +
          "</time></div>";
      }).join("") + "</div>" +
      '<p class="con-table-note">The last ' + escapeHtml(feed.retained || 300) +
      " actions, newest first. The durable record is still the Discord log.</p>";
  }

  /** What is on sale right now. */
  function renderAuction() {
    var house = state.auction || {};
    var listings = house.listings || [];
    if (!listings.length) {
      return '<p class="con-empty">Nothing is listed for sale.</p>';
    }
    return '<div class="con-stats">' +
      '<div class="con-stat"><span>Listings</span><strong>' + escapeHtml(house.count || 0) +
      "</strong><em>on sale now</em></div>" +
      '<div class="con-stat"><span>Combined asking price</span><strong>' +
      escapeHtml(Number(house.total_value || 0).toLocaleString()) +
      "</strong><em>if everything sold</em></div></div>" +
      '<section class="con-table"><div class="con-table-scroll"><table><thead><tr>' +
      "<th>Item</th><th>Seller</th><th class=\"con-num\">Amount</th>" +
      "<th class=\"con-num\">Price</th><th class=\"con-num\">Expires</th>" +
      "</tr></thead><tbody>" + listings.map(function (row) {
        return "<tr><td><div class=\"con-entry\">" +
          '<img class="con-icon" src="/assets/minecraft-items/' +
          escapeHtml(String(row.material).toLowerCase()) +
          '.png" alt="" loading="lazy" onerror="this.replaceWith(Object.assign(' +
          "document.createElement('span'),{className:'con-icon-gap'}))\">" +
          "<span>" + escapeHtml(row.display_name || titleCase(row.material)) +
          "</span></div></td><td>" + escapeHtml(row.seller) +
          '</td><td class="con-num">' + escapeHtml(row.amount) +
          '</td><td class="con-num">' + escapeHtml(Number(row.price).toLocaleString()) +
          '</td><td class="con-num con-muted">' +
          escapeHtml(new Date(Number(row.expires_at)).toLocaleDateString()) +
          "</td></tr>";
      }).join("") + "</tbody></table></div></section>";
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

  function pageCount(page) {
    if (page.id === "actions") return state.actions.length;
    if (page.id === "statistics" || page.id === "announce" || page.id === "player") return 0;
    if (page.id === "presets") return ((state.presets || {}).list || []).length;
    if (page.id === "schedule_actions") return ((state.schedule || {}).entries || []).length;
    if (page.id === "activity") return ((state.activity || {}).entries || []).length;
    if (page.id === "auction") return ((state.auction || {}).listings || []).length;
    if (page.id === "overview" || page.id === "history") return 0;
    return groupRows(page.id).length;
  }

  /**
   * What the search actually found, across every page.
   *
   * The sidebar already counts matches per page, so it is the map; this is the list.
   * Rows keep their own page as a heading so a result is never shown without saying
   * where it lives — the point of searching is usually to find out exactly that.
   */
  /**
   * The search box, read as an instruction rather than a filter.
   *
   * <p>The panel's whole premise was "say what you want and it happens", and the way in
   * was still a filter over 527 keys. This parses a small, honest grammar — a direction,
   * an amount, and something to point them at — and answers with a *proposal*: the exact
   * settings, their before and after. Nothing is applied; it stages a draft, so the
   * review and publish path is the same as for anything typed by hand.
   *
   * <p>Deliberately not a natural-language pretence. It understands what it understands
   * and says nothing when it does not, which is better than confidently changing the
   * wrong thing.
   */
  var COMMAND_VERBS = [
    {match: /\b(double|twice)\b/, factor: 2},
    {match: /\b(halve|half)\b/, factor: 0.5},
    {match: /\b(triple)\b/, factor: 3},
    {match: /\b(raise|increase|up|more|boost|generous)\b/, factor: 1.25, soft: true},
    {match: /\b(lower|reduce|down|less|cut|cheaper|stingy)\b/, factor: 0.8, soft: true}
  ];

  function parseCommand(text) {
    var lowered = String(text || "").toLowerCase();
    var percent = /(-?\d+(?:\.\d+)?)\s*%/.exec(lowered);
    var times = /\bx\s*(\d+(?:\.\d+)?)|\b(\d+(?:\.\d+)?)\s*x\b/.exec(lowered);
    var factor = null;
    var direction = 1;

    for (var i = 0; i < COMMAND_VERBS.length; i++) {
      if (COMMAND_VERBS[i].match.test(lowered)) {
        factor = COMMAND_VERBS[i].factor;
        direction = factor >= 1 ? 1 : -1;
        break;
      }
    }
    if (percent) {
      var size = Math.abs(Number(percent[1])) / 100;
      // "20% more" and "20% less" differ only by the verb, so the verb decides the sign.
      factor = direction < 0 ? 1 - size : 1 + size;
    } else if (times) {
      factor = Number(times[1] || times[2]);
    }
    if (!factor || !isFinite(factor) || factor <= 0 || factor === 1) return null;

    // What to point it at: the words that are not the instruction.
    var subject = lowered
      .replace(/(-?\d+(?:\.\d+)?)\s*%/g, " ")
      .replace(/\bx\s*\d+(?:\.\d+)?|\b\d+(?:\.\d+)?\s*x\b/g, " ")
      .replace(/\b(double|twice|halve|half|triple|raise|increase|up|more|boost|generous|lower|reduce|down|less|cut|cheaper|stingy|make|the|by|a|to|all|and|please)\b/g, " ")
      .replace(/\s+/g, " ")
      .trim();
    if (subject.length < 3) return null;

    var words = subject.split(" ");
    var targets = state.rows.filter(function (row) {
      if (row.control === "toggle" || row.control === "choice" || row.control === "text") {
        return false;
      }
      var hay = (row.key + " " + row.label + " " + row.group_label).toLowerCase();
      return words.every(function (word) { return hay.indexOf(word) >= 0; });
    });
    if (!targets.length || targets.length > 40) return null;
    var edits = scaleEdits(targets.map(function (row) { return row.key; }), factor);
    if (!edits.length) return null;
    return {factor: factor, subject: subject, edits: edits};
  }

  function commandCard() {
    var parsed = parseCommand(state.search);
    if (!parsed) return "";
    var percent = Math.round((parsed.factor - 1) * 100);
    var rows = parsed.edits.slice(0, 8).map(function (edit) {
      var row = state.byKey[edit.key];
      return "<li><div><strong>" + escapeHtml(row.label) + "</strong><span>" +
        escapeHtml(row.group_label) + "</span></div><code>" +
        escapeHtml(row.value) + " &rarr; " + escapeHtml(edit.value) + "</code></li>";
    }).join("");
    return '<section class="con-command"><header><h3>' +
      escapeHtml(percent > 0 ? "Raise " + parsed.subject + " by " + percent + "%"
                             : "Lower " + parsed.subject + " by " + Math.abs(percent) + "%") +
      "</h3><p>" + parsed.edits.length + " setting" +
      (parsed.edits.length === 1 ? "" : "s") +
      " would change. Nothing is applied until you publish.</p></header>" +
      '<ul class="con-preview-list">' + rows +
      (parsed.edits.length > 8
        ? "<li><div><span>and " + (parsed.edits.length - 8) +
          " more</span></div></li>" : "") + "</ul>" +
      '<div class="con-table-actions">' +
      '<button type="button" class="con-primary" id="con-command-stage">Stage these changes</button>' +
      "</div></section>";
  }

  function renderSearch() {
    var sections = [];
    var total = 0;
    PAGES.forEach(function (page) {
      if (page.id === "overview" || page.id === "actions" || page.id === "activity"
          || page.id === "auction" || page.id === "history") {
        return;
      }
      var rows = groupRows(page.id);
      if (!rows.length) return;
      total += rows.length;
      sections.push(
        '<section class="con-section"><h3>' + escapeHtml(page.label) +
        '<span class="con-section-count">' + rows.length + "</span>" +
        '<button type="button" class="con-link" data-page="' + escapeHtml(page.id) +
        '">Open page</button></h3><div class="con-grid">' +
        rows.map(settingCard).join("") + "</div></section>"
      );
    });
    if (!total) {
      var command = commandCard();
      // An understood instruction is an answer; following it with "nothing matches"
      // reads as a failure when the panel just told you exactly what it would do.
      return command || '<p class="con-empty">Nothing matches &ldquo;' +
        escapeHtml(state.search) +
        "&rdquo;. Try the name a player would use, or paste a setting key.</p>";
    }
    return commandCard() +
      '<p class="con-intro"><strong>' + total + "</strong> setting" +
      (total === 1 ? "" : "s") + " match &ldquo;" + escapeHtml(state.search) +
      "&rdquo;, across " + sections.length + " page" + (sections.length === 1 ? "" : "s") +
      ". Edit them here; publishing works the same as anywhere else.</p>" +
      sections.join("");
  }

  /**
   * Whether what is on screen is what the server currently has.
   *
   * The panel is served the last snapshot the plugin sent, which outlives the
   * connection that produced it. A stale copy that looks live is the one way this
   * screen can mislead: every number would read as fact while the server had moved on.
   */
  function offline() {
    var link = state.snapshot ? (state.snapshot.connection || {}) : {};
    // Absent means an older backend that does not report it; assume connected rather
    // than blocking every edit on a field it never sends.
    return link.connected === false;
  }

  function pluginBanner() {
    if (!state.stalePlugin) return "";
    return '<p class="con-offline"><strong>The Minecraft server is running an older ' +
      "plugin.</strong> It sent " + (state.snapshot.variables || []).length +
      " values with no page grouping, which this panel needs, so every page reads as " +
      "empty. The newer build is already on the server &mdash; restart Minecraft to " +
      "load it, and this page fills in.</p>";
  }

  function staleBanner() {
    if (!offline()) return "";
    var link = state.snapshot.connection || {};
    var age = link.captured_at && link.now
      ? Math.max(1, Math.round((link.now - link.captured_at) / 60))
      : null;
    return '<p class="con-offline"><strong>The Minecraft server is not connected.</strong> ' +
      "Everything below is the last snapshot it sent" +
      (age === null ? "" : ", from about " + age + " minute" + (age === 1 ? "" : "s") + " ago") +
      ". You can still edit and your draft is kept, but nothing can be published until " +
      "the server is back.</p>";
  }

  function renderStatus() {
    var pill = byId("con-status");
    if (!pill) return;
    var link = state.snapshot ? (state.snapshot.connection || {}) : {};
    if (link.connected) {
      pill.className = "cx-status";
      pill.textContent = "Connected";
      pill.title = "The plugin is connected; these are its live values.";
      return;
    }
    pill.className = "cx-status off";
    var age = link.captured_at && link.now
      ? Math.max(0, Math.round((link.now - link.captured_at) / 60))
      : null;
    pill.textContent = "Server offline";
    pill.title = age === null
      ? "The plugin is not connected. These values are the last it sent."
      : "The plugin is not connected. These values are " + age +
        " minute(s) old and changes cannot reach it until it returns.";
  }

  function renderNav() {
    var group = null;
    byId("con-nav").innerHTML = PAGES.map(function (page) {
      var heading = "";
      if (page.group !== group) {
        group = page.group;
        if (group) heading = '<p class="cx-group">' + escapeHtml(group) + "</p>";
      }
      var count = pageCount(page);
      var dirty = dirtyKeys().filter(function (key) {
        return state.byKey[key].group === page.id;
      }).length;
      return heading +
        '<button type="button" data-page="' + escapeHtml(page.id) + '" aria-current="' +
        // Searching leaves every page: marking one as current would point at a page
        // the work area is not showing.
        (!state.search && state.page === page.id ? "page" : "false") + '">' +
        '<span class="cx-label">' + escapeHtml(page.label) + "</span>" +
        (dirty
          ? '<span class="con-dot" title="' + dirty + ' unpublished">' + dirty + "</span>"
          : count ? '<span class="con-count">' + count + "</span>" : "") +
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
    var unreachable = offline();
    bar.hidden = false;
    bar.innerHTML =
      '<div class="con-draft-count"><strong>' + pending.length + "</strong> unpublished change" +
      (pending.length === 1 ? "" : "s") +
      (unreachable
        ? '<span class="bad">the server is not connected</span>'
        : state.validating
          ? "<span>checking&hellip;</span>"
          : blocking
            ? '<span class="bad">' + blocking + " problem" + (blocking === 1 ? "" : "s") + " to fix</span>"
            : "<span>ready to publish</span>") +
      "</div>" +
      '<div class="con-draft-actions">' +
      '<button type="button" class="con-secondary" id="con-discard">Discard</button>' +
      '<button type="button" class="con-secondary" id="con-review">Review</button>' +
      '<button type="button" class="con-primary" id="con-publish"' +
      (unreachable || blocking || state.validating || state.publishing ? " disabled" : "") + ">" +
      (state.publishing ? "Publishing&hellip;" : "Publish") + "</button></div>";
  }

  /**
   * What a setting will mean once published, in the same words it means now.
   *
   * <p>meaning() already turns a value into a sentence — "a player online three hours a
   * day earns about 6 keys". Running it against the live value and the drafted one turns
   * the review from a list of numbers into a list of consequences, which is the question
   * being asked at that moment: not what the number becomes, but what it does.
   */
  function meaningShift(row) {
    var after = meaning(row);
    var live = state.draft[row.key];
    delete state.draft[row.key];
    var before = meaning(row);
    state.draft[row.key] = live;
    if (!before || !after || before === after) {
      return after ? '<em class="con-shift">' + escapeHtml(after) + "</em>" : "";
    }
    return '<em class="con-shift"><s>' + escapeHtml(before) + "</s> " +
      escapeHtml(after) + "</em>";
  }

  function renderPreview() {
    var pending = dirtyKeys();
    var body = pending.map(function (key) {
      var row = state.byKey[key];
      return "<li><div><strong>" + escapeHtml(row.label) + "</strong><span>" +
        escapeHtml(row.group_label) + "</span>" + meaningShift(row) +
        "</div><code>" +
        escapeHtml(row.value) + " &rarr; " + escapeHtml(draftedValue(key)) + "</code></li>";
    }).join("");
    byId("con-preview-body").innerHTML =
      "<p>Publishing applies all of these together, or none of them if the server "
      + "refuses any one.</p><ul class=\"con-preview-list\">" + body + "</ul>";
    byId("con-preview").hidden = false;
  }

  function render() {
    dropSettledEdits();
    renderStatus();
    renderNav();
    renderDraftBar();
    var main = byId("con-page");
    var banner = pluginBanner() + staleBanner();
    if (state.search) {
      main.innerHTML = banner + renderSearch();
      byId("con-page-title").textContent = "Search";
      return;
    }
    if (state.page === "overview") {
      main.innerHTML = banner + renderOverview();
      // The noticeboard reads the same series the charts do; fetch them once so the
      // overview can say what moved without being opened twice.
      if (state.stats === null) loadStatistics();
    } else if (state.page === "actions") {
      main.innerHTML = banner + renderActions() +
        '<datalist id="con-online">' + state.online.map(function (name) {
          return '<option value="' + escapeHtml(name) + '">';
        }).join("") + "</datalist>";
    } else if (state.page === "player") {
      main.innerHTML = banner + renderPlayer();
    } else if (state.page === "schedule_actions") {
      main.innerHTML = banner + renderSchedule();
      if (state.schedule === null) loadSchedule();
    } else if (state.page === "presets") {
      main.innerHTML = banner + renderPresets();
      if (state.presets === null) loadPresets();
    } else if (state.page === "announce") {
      main.innerHTML = banner + renderAnnounce();
      if (state.announce === null) loadAnnounce();
    } else if (state.page === "statistics") {
      main.innerHTML = banner + renderStatistics();
      if (state.stats === null) loadStatistics();
    } else if (state.page === "activity") {
      main.innerHTML = banner + renderActivity();
    } else if (state.page === "auction") {
      main.innerHTML = banner + renderAuction();
    } else if (state.page === "history") {
      main.innerHTML = banner + renderHistory();
    } else {
      main.innerHTML = banner + renderGroupPage(state.page);
    }
    var page = PAGES.filter(function (entry) { return entry.id === state.page; })[0];
    byId("con-page-title").textContent = page ? page.label : "";
  }

  /**
   * Ask before something that cannot simply be undone.
   *
   * Resolves true if the owner goes ahead. Replaces window.confirm, which renders as
   * the browser's own dialog — stamped with the page URL, unable to name the action on
   * its button, and unable to show which choice is the destructive one.
   */
  function confirmThat(title, body, label) {
    return new Promise(function (resolve) {
      var modal = byId("con-confirm");
      var go = byId("con-confirm-go");
      byId("con-confirm-title").textContent = title;
      byId("con-confirm-body").innerHTML = "<p>" + escapeHtml(body) + "</p>";
      go.textContent = label || "Confirm";
      modal.hidden = false;

      function finish(answer) {
        modal.hidden = true;
        go.removeEventListener("click", yes);
        modal.removeEventListener("click", no);
        resolve(answer);
      }
      function yes() { finish(true); }
      function no(event) {
        // The backdrop and every [data-close] dismiss it; nothing else does, so a
        // stray click inside the card cannot cancel the thing being confirmed.
        if (event.target === modal || event.target.closest("[data-close]")) finish(false);
      }
      go.addEventListener("click", yes);
      modal.addEventListener("click", no);
      go.focus();
    });
  }

  function clearSearch() {
    state.search = "";
    var box = byId("con-search");
    if (box) box.value = "";
  }

  /* ---------- routing ---------- */

  /** The open page lives in the address bar, so a refresh or a shared link keeps it. */
  function pageFromHash() {
    var id = (window.location.hash || "").replace(/^#\/?/, "");
    return PAGES.some(function (page) { return page.id === id; }) ? id : "overview";
  }

  function goTo(id) {
    if (window.location.hash.replace(/^#\/?/, "") === id) {
      applyRoute();
      return;
    }
    window.location.hash = id;
  }

  function applyRoute() {
    var id = pageFromHash();
    if (state.page !== id) state.task = null;
    state.page = id;
    render();
    var main = document.querySelector(".con-main");
    if (main) main.scrollTop = 0;
  }

  /* ---------- events ---------- */

  function wire() {
    byId("con-nav").addEventListener("click", function (event) {
      var button = event.target.closest("[data-page]");
      if (!button) return;
      goTo(button.dataset.page);
    });

    // The announcement editor updates its preview as you type, so it listens for input
    // rather than change; a preview that only appears when a field loses focus is not a
    // preview of what you are writing.
    byId("con-page").addEventListener("keydown", function (event) {
      if (event.key === "Enter" && event.target.id === "con-player-name") {
        event.preventDefault();
        lookUpPlayer();
      }
    });

    byId("con-page").addEventListener("input", function (event) {
      var field = event.target.dataset ? event.target.dataset.announce : null;
      if (!field) return;
      state.announceDraft[field] = event.target.value;
      var preview = document.querySelector(".con-announce-preview");
      if (preview) {
        preview.outerHTML = announcePreview(state.announceDraft);
      }
    });

    byId("con-page").addEventListener("change", function (event) {
      var target = event.target;
      if (target.id === "con-preset-file") {
        if (target.files && target.files[0]) importPreset(target.files[0]);
        target.value = "";
        return;
      }
      if (target.dataset.shopPrice !== undefined || target.dataset.shopAmount !== undefined) {
        saveShopOffer(target.dataset.shopPrice || target.dataset.shopAmount);
        return;
      }
      if (target.dataset.announceToggle !== undefined) {
        post("/api/announce", {enabled: target.checked})
          .then(loadAnnounce)
          .catch(function (error) { toast(error.message, true); });
        return;
      }
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
        var edited = state.byKey[target.dataset.key];
        if (edited && (edited.control === "choice" || edited.control === "text")) {
          setDraft(edited.key, target.value);
          return;
        }
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
      if (restore) { restoreRow(restore.dataset.table, restore.dataset.restore); return; }
      var run = event.target.closest("[data-run]");
      if (run) { runAction(run.dataset.run); return; }
      if (event.target.id === "con-announce-send") { sendAnnouncement(); return; }
      var shelf = event.target.closest("[data-shelf]");
      if (shelf) { state.shopShelf = shelf.dataset.shelf; render(); return; }
      var shopOut = event.target.closest("[data-shop-remove]");
      if (shopOut) { removeShopOffer(shopOut.dataset.shopRemove); return; }
      var shopBack = event.target.closest("[data-shop-restore]");
      if (shopBack) { catalogCall("restore_shop_offer", {material: shopBack.dataset.shopRestore}); return; }
      if (event.target.id === "con-shop-add") { openShopDialog(); return; }
      if (event.target.id === "con-cosmetic-add") { openCosmeticDialog(); return; }
      var dropCosmetic = event.target.closest("[data-cosmetic-remove]");
      if (dropCosmetic) {
        catalogCall("remove_cosmetic", {id: dropCosmetic.dataset.cosmeticRemove});
        return;
      }
      if (event.target.id === "con-command-stage") {
        var parsed = parseCommand(state.search);
        if (!parsed) return;
        var values = {};
        parsed.edits.forEach(function (edit) { values[edit.key] = edit.value; });
        clearSearch();
        stageValues(values, "That instruction");
        return;
      }
      if (event.target.id === "con-player-find") { lookUpPlayer(); return; }
      if (event.target.id === "con-sched-add") { bookAction(); return; }
      var unbook = event.target.closest("[data-schedule-delete]");
      if (unbook) { cancelBooking(unbook.dataset.scheduleDelete); return; }
      if (event.target.id === "con-preset-save") { savePreset(); return; }
      if (event.target.id === "con-preset-export") { exportPreset(); return; }
      if (event.target.id === "con-preset-import") { byId("con-preset-file").click(); return; }
      var applyPreset = event.target.closest("[data-preset-apply]");
      if (applyPreset) {
        var wanted = (state.presets.list || []).filter(function (row) {
          return row.name === applyPreset.dataset.presetApply;
        })[0];
        if (wanted) stageValues(wanted.values || {}, wanted.name);
        return;
      }
      var dropPreset = event.target.closest("[data-preset-delete]");
      if (dropPreset) { deletePreset(dropPreset.dataset.presetDelete); return; }
      var metric = event.target.closest("[data-metric]");
      if (metric) {
        var name = metric.dataset.metric;
        var at = state.statMetrics.indexOf(name);
        if (at >= 0) state.statMetrics.splice(at, 1);
        else state.statMetrics.push(name);
        render();
        loadSeries();
        return;
      }
      var window_ = event.target.closest("[data-stat-days]");
      if (window_) {
        state.statDays = Number(window_.dataset.statDays);
        state.stats = null;
        // A different window means different samples; keeping the old ones would chart
        // thirty days of history under a "24 hours" heading.
        state.statSeries = {};
        render();
        loadStatistics();
        return;
      }
      var logged = event.target.closest("[data-log]");
      if (logged) { state.logFilter = logged.dataset.log; render(); return; }
      var jump = event.target.closest("[data-page]");
      if (jump) {
        clearSearch();
        goTo(jump.dataset.page);
        return;
      }
      var task = event.target.closest("[data-task]");
      if (task) {
        state.task = task.dataset.task || null;
        render();
        // The work area scrolls, not the window: the shell is fixed to the viewport.
        byId("con-page").parentElement.scrollTo({top: 0, behavior: "smooth"});
        return;
      }
      var strength = event.target.closest("[data-strength]");
      if (strength) { applyStrength(strength.dataset.strength); }
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

    byId("con-page").addEventListener("error", function (event) {
      var image = event.target;
      if (!image || !image.classList || !image.classList.contains("con-avatar")) return;
      if (image.src !== STEVE_HEAD) {
        image.src = STEVE_HEAD;
        return;
      }
      // The fallback failed too, so stop trying and leave a quiet square.
      image.classList.add("missing");
    }, true);

    var search = byId("con-search");
    search.addEventListener("input", function () {
      state.search = search.value.trim().toLowerCase();
      render();
    });

    document.addEventListener("keydown", function (event) {
      // Focus the search from anywhere. In a panel this size the search is the way in,
      // so it gets the shortcut every tool that has one uses for it.
      var typing = /^(input|select|textarea)$/i.test((event.target.tagName || ""));
      if ((event.key === "k" && (event.metaKey || event.ctrlKey)) ||
          (event.key === "/" && !typing)) {
        event.preventDefault();
        search.focus();
        search.select();
        return;
      }
      if (event.key !== "Escape") return;
      // The confirmation owns its own dismissal: closing it here would leave the promise
      // it handed out unresolved, and whatever was waiting on it would never run again.
      var confirming = byId("con-confirm");
      if (confirming && !confirming.hidden) {
        confirming.querySelector("[data-close]").click();
        return;
      }
      if (!byId("con-preview").hidden || !byId("con-add").hidden) {
        byId("con-preview").hidden = true;
        byId("con-add").hidden = true;
        state.adding = null;
        return;
      }
      // Nothing is open, so Escape means "drop the query" — the one other thing it
      // could plausibly do, and the only way back without reaching for the mouse.
      if (state.search) {
        clearSearch();
        render();
      }
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
    if (!await confirmThat(
      "Roll this change back?",
      "Every value in it goes back the way it was. The rollback is itself recorded as a "
      + "new change, so it can be undone too.",
      "Roll it back"
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

  /** One catalogue operation, then a reload so the page shows what the server did. */
  async function catalogCall(operation, payload) {
    try {
      var answer = await post("/api/catalog",
        Object.assign({operation: operation}, payload));
      toast(answer.message || "Done.", false);
      await loadSettings();
      render();
    } catch (error) {
      toast(error.message, true);
    }
  }

  function shopRowOf(material) {
    var shelves = (state.catalog || {}).shop_shelves || [];
    for (var i = 0; i < shelves.length; i++) {
      var found = (shelves[i].offers || []).filter(function (offer) {
        return offer.material === material;
      })[0];
      if (found) return {offer: found, shelf: shelves[i].id};
    }
    return null;
  }

  function saveShopOffer(material) {
    var found = shopRowOf(material);
    if (!found) return;
    var page = byId("con-page");
    var price = page.querySelector('[data-shop-price="' + material + '"]');
    var amount = page.querySelector('[data-shop-amount="' + material + '"]');
    catalogCall("set_shop_offer", {
      material: material,
      category: found.shelf,
      amount: Number(amount ? amount.value : found.offer.amount),
      price: Number(price ? price.value : found.offer.price)
    });
  }

  async function removeShopOffer(material) {
    if (!await confirmThat(
      "Take " + titleCase(material) + " off the shelf?",
      "Players will no longer be able to buy it. A built-in item can be put back at its "
      + "catalogue price afterwards.",
      "Take it off"
    )) return;
    catalogCall("remove_shop_offer", {material: material});
  }

  function openShopDialog() {
    var shelves = (state.catalog || {}).shop_shelves || [];
    var open = state.shopShelf || (shelves[0] || {}).id;
    byId("con-add-title").textContent = "Add an item to the shop";
    byId("con-add-body").innerHTML =
      '<label class="con-field"><span>Item</span>' + materialPicker() +
      "<em>Anything the server can hand a player.</em></label>" +
      '<div class="con-field-row">' +
      '<label class="con-field"><span>Shelf</span>' +
      '<select class="con-choice" id="con-add-category">' + shelves.map(function (shelf) {
        return '<option value="' + escapeHtml(shelf.id) + '"' +
          (shelf.id === open ? " selected" : "") + ">" + escapeHtml(shelf.label) + "</option>";
      }).join("") + "</select></label>" +
      '<label class="con-field"><span>How many</span>' +
      '<input class="con-number" id="con-add-amount" type="number" min="1" max="64" value="1">' +
      "</label>" +
      '<label class="con-field"><span>Price</span>' +
      '<input class="con-number" id="con-add-min" type="number" min="1" value="100">' +
      "</label></div>" +
      '<p class="con-help">The price is what a player pays before the shelf percentage ' +
      "above is applied.</p>";
    state.adding = {shop: true};
    byId("con-add").hidden = false;
    window.setTimeout(function () { byId("con-add-material").focus(); }, 30);
  }

  async function lookUpPlayer() {
    var name = String(byId("con-player-name").value || "").trim();
    if (!name) { toast("Type a player name first.", true); return; }
    state.playerQuery = name;
    try {
      state.player = await api("/api/player?name=" + encodeURIComponent(name));
    } catch (error) {
      state.player = {error: error.message};
    }
    render();
  }

  async function loadSchedule() {
    try {
      state.schedule = await api("/api/schedule");
    } catch (error) {
      state.schedule = {error: error.message};
    }
    if (state.page === "schedule_actions") render();
  }

  async function bookAction() {
    var when = byId("con-sched-at").value;
    if (!when) { toast("Pick a date and time first.", true); return; }
    // datetime-local has no timezone, so it is read in the browser's own — which is what
    // an owner means when they type six o'clock.
    var at = Math.floor(new Date(when).getTime() / 1000);
    if (!at || at <= Date.now() / 1000) {
      toast("Pick a time in the future.", true);
      return;
    }
    try {
      await post("/api/schedule", {
        action: byId("con-sched-action").value,
        arguments: {},
        run_at: at,
        repeat_days: Number(byId("con-sched-repeat").value || 0)
      });
      toast("Booked.", false);
      state.schedule = null;
      await loadSchedule();
    } catch (error) {
      toast(error.message, true);
    }
  }

  async function cancelBooking(id) {
    if (!await confirmThat("Cancel this booking?",
      "It will not run. Nothing that has already happened is undone.", "Cancel it")) return;
    try {
      await post("/api/schedule", {id: id, delete: true});
      state.schedule = null;
      await loadSchedule();
    } catch (error) {
      toast(error.message, true);
    }
  }

  async function loadPresets() {
    try {
      var answer = await api("/api/presets");
      state.presets = {list: answer.presets || []};
    } catch (error) {
      state.presets = {error: error.message};
    }
    if (state.page === "presets") render();
  }

  /** The values that differ from default — the only part of a snapshot worth keeping. */
  function changedValues() {
    var values = {};
    state.rows.forEach(function (row) {
      if (row.overridden) values[row.key] = row.value;
    });
    return values;
  }

  async function savePreset() {
    var name = String(byId("con-preset-name").value || "").trim();
    if (!name) { toast("Give the preset a name first.", true); return; }
    try {
      await post("/api/presets", {name: name, values: changedValues()});
      toast("Saved \u201c" + name + "\u201d.", false);
      state.presets = null;
      await loadPresets();
    } catch (error) {
      toast(error.message, true);
    }
  }

  async function deletePreset(name) {
    if (!await confirmThat("Delete \u201c" + name + "\u201d?",
      "The preset is removed. Nothing on the server changes.", "Delete it")) return;
    try {
      await post("/api/presets", {name: name, delete: true});
      state.presets = null;
      await loadPresets();
    } catch (error) {
      toast(error.message, true);
    }
  }

  /**
   * Stages a preset as a draft.
   *
   * <p>Deliberately not applied. Everything it would change appears in the review dialog
   * and the save bar first, which is the difference between restoring a known-good tune
   * and finding out afterwards what it moved.
   */
  function stageValues(values, label) {
    var staged = 0;
    var unknown = 0;
    Object.keys(values).forEach(function (key) {
      var row = state.byKey[key];
      if (!row) { unknown += 1; return; }
      if (String(values[key]) === String(row.value)) return;
      state.draft[key] = {value: values[key]};
      staged += 1;
    });
    saveDraft();
    scheduleValidate();
    render();
    toast(staged
      ? label + ": " + staged + " change(s) staged for review" +
        (unknown ? ", " + unknown + " unknown setting(s) ignored" : "") + "."
      : label + " matches what is already live; nothing staged.", false);
  }

  function exportPreset() {
    var payload = {
      exported_at: new Date().toISOString(),
      settings: changedValues()
    };
    var blob = new Blob([JSON.stringify(payload, null, 2)], {type: "application/json"});
    var link = document.createElement("a");
    link.href = URL.createObjectURL(blob);
    link.download = "mysterious-smp-x-settings.json";
    document.body.appendChild(link);
    link.click();
    document.body.removeChild(link);
    URL.revokeObjectURL(link.href);
  }

  function importPreset(file) {
    var reader = new FileReader();
    reader.onload = function () {
      try {
        var parsed = JSON.parse(String(reader.result));
        var values = parsed.settings || parsed.values || parsed;
        if (!values || typeof values !== "object") {
          throw new Error("That file has no settings in it.");
        }
        stageValues(values, "Imported file");
      } catch (error) {
        toast("Could not read that file: " + error.message, true);
      }
    };
    reader.readAsText(file);
  }

  async function loadAnnounce() {
    try {
      state.announce = await api("/api/announce");
    } catch (error) {
      state.announce = {error: error.message};
    }
    if (state.page === "announce") render();
  }

  async function sendAnnouncement() {
    var draft = state.announceDraft;
    if (!draft.title.trim() && !draft.description.trim()) {
      toast("Write a title or a message first.", true);
      return;
    }
    if (!await confirmThat(
      "Send this to " + (state.announce.recipients || 0) + " members?",
      "Each one gets a direct message. Sending is paced, so it takes about " +
      Math.max(1, Math.round((state.announce.recipients || 0) * 1.2 / 60)) +
      " minutes and cannot be recalled once it starts.",
      "Send it"
    )) return;
    state.announce.sending = true;
    render();
    try {
      state.announceResult = await post("/api/announce", draft);
      toast("Delivered to " + state.announceResult.delivered + " member(s).",
            state.announceResult.stopped_early);
    } catch (error) {
      toast(error.message, true);
    }
    await loadAnnounce();
  }

  async function loadStatistics() {
    try {
      state.stats = await api("/api/stats?days=" + state.statDays);
    } catch (error) {
      state.stats = {error: error.message};
    }
    // Overview reads the same series for its noticeboard, so both pages care.
    if (state.page === "statistics" || state.page === "overview") render();
    // The charts are a second request: the overview is what the page needs to appear,
    // and a wide metric selection can be several queries behind it.
    if (state.stats && !state.stats.error) {
      if (!state.statMetrics.length) {
        state.statMetrics = (state.stats.metrics || []).slice(0, 4);
      }
      await loadSeries();
    }
  }

  async function loadSeries() {
    var wanted = state.statMetrics.filter(function (name) {
      return state.statSeries[name] === undefined;
    });
    if (!wanted.length) return;
    try {
      var answer = await api("/api/stats/series?days=" + state.statDays +
        "&metric=" + wanted.map(encodeURIComponent).join(","));
      Object.keys(answer.series || {}).forEach(function (name) {
        state.statSeries[name] = answer.series[name] || [];
      });
      // Anything the server did not answer for still has to stop saying "loading".
      wanted.forEach(function (name) {
        if (state.statSeries[name] === undefined) state.statSeries[name] = [];
      });
    } catch (error) {
      wanted.forEach(function (name) { state.statSeries[name] = []; });
    }
    // Overview reads the same series for its noticeboard, so both pages care.
    if (state.page === "statistics" || state.page === "overview") render();
  }

  async function loadSettings() {
    var snapshot = await api("/api/settings");
    state.snapshot = snapshot || {};
    var sent = state.snapshot.variables || [];
    // Every page filters on row.group, which only plugins from 6.74.0 onward send. An
    // older one answers with values that match no page, so all twenty-nine render empty
    // and the search-empty message fires with an empty query — the panel looks broken
    // when what is actually wrong is that the server has not loaded the new jar yet.
    state.stalePlugin = sent.length > 0 && !sent.some(function (row) {
      return typeof row.group === "string" && row.group !== "";
    });
    state.rows = sent.filter(function (row) {
      return row.group !== "unclassified";
    });
    state.byKey = {};
    state.rows.forEach(function (row) { state.byKey[row.key] = row; });
    state.catalog = state.snapshot.catalog || null;
    state.actions = (state.snapshot.action_catalogue || {}).actions || [];
    state.activity = state.snapshot.activity || null;
    state.auction = state.snapshot.auction || null;
    state.online = (state.snapshot.action_catalogue || {}).online || [];
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
    window.addEventListener("hashchange", applyRoute);
    // The count comes from the server, so the prompt cannot drift from what is there.
    byId("con-search").placeholder =
      "Search " + state.rows.length + " settings, or paste a key";
    state.page = pageFromHash();
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
