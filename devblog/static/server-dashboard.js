(function () {
  "use strict";

  var state = {
    snapshot: null,
    me: null,
    settings: [],
    category: "All",
    leaderboardView: "leaderboards",
    playerBoard: "wealth",
    clanBoard: "wealth",
    eventBoard: "amethyst_airdrops"
  };
  var defaultBoards = ["wealth", "kills"];
  var eventBoards = ["amethyst_airdrops", "amethyst_crates"];
  var labels = {
    wealth: "Richest",
    kills: "Most Kills",
    amethyst_crates: "Amethyst Crates",
    amethyst_airdrops: "Airdrops Claimed",
    clan_battle: "Clan Battle"
  };
  var descriptions = {
    amethyst_airdrops: "Claim the most Amethyst Airdrops before the event closes.",
    amethyst_crates: "Open the most Amethyst Crates and take the event crown."
  };
  var eventIcons = {amethyst_airdrops: "amethyst_shard", amethyst_crates: "crate_key"};
  var steveHead = "https://api.mcheads.org/ioshead/MHF_Steve/left";
  var steveBody = "https://api.mcheads.org/iosbody/MHF_Steve/left";

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
    toast.timer = window.setTimeout(function () { node.className = "live-toast"; }, 4000);
  }
  async function api(url, options) {
    var response = await window.fetch(url, Object.assign({cache: "no-store"}, options || {}));
    if (!response.ok) {
      var message = await response.text();
      throw new Error(message && message.length < 240 ? message : "Request failed (" + response.status + ")");
    }
    return response.json();
  }
  function relativeTime(timestamp) {
    timestamp = Number(timestamp);
    if (!Number.isFinite(timestamp) || timestamp <= 0) return "Waiting for Paper";
    // The bridge publishes milliseconds. Accept legacy Unix-second snapshots too,
    // but always compare milliseconds with JavaScript's millisecond clock.
    if (timestamp < 1000000000000) timestamp *= 1000;
    var seconds = Math.round((timestamp - Date.now()) / 1000);
    var formatter = new Intl.RelativeTimeFormat(undefined, {numeric: "auto"});
    if (Math.abs(seconds) >= 3600) return formatter.format(Math.round(seconds / 3600), "hour");
    if (Math.abs(seconds) >= 60) return formatter.format(Math.round(seconds / 60), "minute");
    return formatter.format(seconds, "second");
  }
  function itemIcon(item, className, label) {
    return '<img class="live-minecraft-icon ' + escapeHtml(className || "") + '" src="/assets/minecraft-items/' +
      escapeHtml(item) + '.png" alt="' + escapeHtml(label || "") + '" loading="lazy">';
  }
  function tabs(target, keys, active, onSelect) {
    if (!target) return;
    target.innerHTML = keys.map(function (key) {
      return '<button type="button" role="tab" data-key="' + escapeHtml(key) + '" aria-selected="' +
        (key === active ? "true" : "false") + '">' +
        escapeHtml(labels[key] || key.replaceAll("_", " ")) + "</button>";
    }).join("");
    target.querySelectorAll("button").forEach(function (button) {
      button.addEventListener("click", function () { onSelect(button.dataset.key); });
    });
  }
  function tierFor(rank) {
    return rank === 1 ? "rank-gold" : (rank === 2 ? "rank-silver" : "rank-bronze");
  }
  function playerArt(row, full) {
    var fallback = full ? steveBody : steveHead;
    var source = (full ? row.skin_url : row.head_url) || fallback;
    var label = escapeHtml(row.username || "Player");
    return '<div class="live-player-art ' + (full ? "full" : "head") + '">' +
      '<img class="' + (full ? "live-skin-render" : "live-head-render") + '" src="' +
        escapeHtml(source) + '" data-fallback="' + fallback + '" alt="' + label +
        (full ? ' Minecraft skin"' : ' Minecraft head"') + ' loading="lazy"></div>';
  }
  function clanArt(row) {
    var supplied = String(row.icon || "");
    var icon = /^[a-z0-9_]+$/.test(supplied) ? supplied : "amethyst_shard";
    return '<div class="live-clan-crest">' + itemIcon(icon, "", (row.clan || "Clan") + " clan icon") + "</div>";
  }
  function memberCount(row) {
    var count = Number(row.members || 0);
    return escapeHtml(count) + " " + (count === 1 ? "member" : "members");
  }
  function podiumCard(row, index, clan) {
    var rank = Number(row.rank || index + 1);
    var accolade = rank === 1 ? "Champion" : (rank === 2 ? "Runner-up" : "Third place");
    var name = clan ? row.clan : row.username;
    var detail = clan
      ? memberCount(row) + " · Level " + escapeHtml(row.level || 0)
      : (row.discord_username ? "@" + escapeHtml(row.discord_username) : "Discord not linked");
    return '<article class="live-podium-card ' + tierFor(rank) + " " + (clan ? "clan-card" : "player-card") + '">' +
      '<header><span class="live-medal">#' + rank + '</span><span class="live-accolade">' + accolade + "</span></header>" +
      (clan ? clanArt(row) : playerArt(row, true)) +
      '<div class="live-podium-copy"><h3>' + escapeHtml(name || "?") + "</h3>" +
      '<div class="live-discord-name">' + detail + "</div>" +
      '<strong class="live-value">' + escapeHtml(row.display != null ? row.display : (row.value || 0)) + "</strong></div>" +
      '<div class="live-podium-step" aria-hidden="true"></div></article>';
  }
  function rankRow(row, index, clan) {
    var rank = Number(row.rank || index + 1);
    var name = clan ? row.clan : row.username;
    var detail = clan
      ? memberCount(row) + " · Level " + escapeHtml(row.level || 0)
      : (row.discord_username ? "@" + escapeHtml(row.discord_username) : "Discord not linked");
    return '<li class="live-rank-row"><span class="live-row-place">#' + rank + "</span>" +
      (clan ? clanArt(row) : playerArt(row, false)) +
      '<div class="live-row-player"><strong>' + escapeHtml(name || "?") + "</strong><span>" + detail + "</span></div>" +
      '<strong class="live-row-value">' + escapeHtml(row.display != null ? row.display : (row.value || 0)) + "</strong></li>";
  }
  function wireImageFallbacks(target) {
    target.querySelectorAll(".live-player-art img").forEach(function (image) {
      image.addEventListener("error", function () {
        if (image.src !== image.dataset.fallback) image.src = image.dataset.fallback;
      });
    });
    target.querySelectorAll(".live-clan-crest img").forEach(function (image) {
      image.addEventListener("error", function () {
        if (!image.src.endsWith("/amethyst_shard.png")) image.src = "/assets/minecraft-items/amethyst_shard.png";
      });
    });
  }
  function renderBoard(scope, board, targetId) {
    var rows = (state.snapshot && state.snapshot[scope] && state.snapshot[scope][board]) || [];
    var target = byId(targetId || (scope === "individual" ? "player-board" : "clan-board"));
    if (!target) return;
    target.classList.remove("live-loading");
    var ranked = rows.slice(0, 10);
    var clan = scope === "clan";
    target.innerHTML = ranked.length
      ? '<div class="live-podium">' + ranked.slice(0, 3).map(function (row, index) {
          return podiumCard(row, index, clan);
        }).join("") + '</div><ol class="live-rank-list" start="4">' + ranked.slice(3).map(function (row, index) {
          return rankRow(row, index + 3, clan);
        }).join("") + "</ol>"
      : '<p class="live-empty">No standings yet.</p>';
    wireImageFallbacks(target);
  }
  function renderBattle() {
    var event = (state.snapshot && state.snapshot.clan_battle) || {};
    var rows = ((state.snapshot && state.snapshot.clan && state.snapshot.clan.clan_battle) || []).slice(0, 10);
    var active = Boolean(event.name || rows.length);
    byId("battle-title").textContent = event.name || (active ? "Current Clan Battle" : "No active battle");
    byId("battle-objective").textContent = event.objective || (active
      ? "Live standings for the current clan battle."
      : "When the next clan battle starts, its objective and live standings will appear here.");
    byId("battle-deadline").textContent = event.ends_at ? "Ends " + new Date(event.ends_at).toLocaleString() : "";
    byId("battle-board").innerHTML = rows.length
      ? rows.map(function (row, index) {
          return '<div class="live-battle-row"><span>#' + escapeHtml(row.rank || index + 1) + "</span>" + clanArt(row) + "<strong>" +
            escapeHtml(row.clan) + "</strong><span>" + escapeHtml(row.display || row.value) + "</span></div>";
        }).join("")
      : '<p class="live-empty">No active clan battle standings.</p>';
    wireImageFallbacks(byId("battle-board"));
  }
  function renderLeaderboards() {
    renderBoard("individual", state.playerBoard);
    renderBoard("clan", state.clanBoard);
    renderBoard("individual", state.eventBoard, "event-board");
    if (byId("event-icon")) byId("event-icon").innerHTML = itemIcon(
      eventIcons[state.eventBoard] || "amethyst_shard", "", labels[state.eventBoard] || "Event icon"
    );
    if (byId("event-description")) byId("event-description").textContent = descriptions[state.eventBoard] || "Limited-time event standings.";
    document.querySelectorAll("#player-tabs button").forEach(function (button) {
      button.setAttribute("aria-selected", button.dataset.key === state.playerBoard ? "true" : "false");
    });
    document.querySelectorAll("#clan-tabs button").forEach(function (button) {
      button.setAttribute("aria-selected", button.dataset.key === state.clanBoard ? "true" : "false");
    });
    document.querySelectorAll("#event-tabs button").forEach(function (button) {
      button.setAttribute("aria-selected", button.dataset.key === state.eventBoard ? "true" : "false");
    });
  }
  function selectLeaderboardView(view) {
    state.leaderboardView = view;
    document.querySelectorAll("[data-view-panel]").forEach(function (panel) {
      panel.hidden = panel.dataset.viewPanel !== view;
    });
    document.querySelectorAll("[data-view]").forEach(function (button) {
      button.setAttribute("aria-selected", button.dataset.view === view ? "true" : "false");
    });
  }
  /**
   * Standings from the backend when it is serving this page, and from the snapshot
   * CI published beside it otherwise.
   *
   * The public site is HTTPS and the dashboard is plain HTTP, so the page cannot call
   * that API from a browser at all -- mixed content, before CORS even applies. So the
   * build publishes assets/leaderboards.json as a same-origin file, and this reads it
   * when /api/ is not there. Same page either way: live where it can be, and at worst
   * as old as the last rebuild.
   */
  async function liveOrPublishedStandings() {
    try {
      var live = await api("/api/leaderboards");
      if (live && (live.individual || live.clan)) { return live; }
    } catch (ignored) {
      // No backend on this origin. Expected on the published site.
    }
    var response = await fetch("../assets/leaderboards.json", { credentials: "omit" });
    if (!response.ok) { throw new Error("No standings are published yet."); }
    var document_ = await response.json();
    var boards = document_.boards || {};
    return {
      individual: boards.individual || {},
      clan: boards.clan || {},
      clan_battle: boards.clan_battle || {},
      generated_at: Date.parse(document_.checked_at || "") || 0
    };
  }

  async function loadLeaderboards() {
    try {
      state.snapshot = await liveOrPublishedStandings();
      byId("generated-at").textContent = relativeTime(Number(state.snapshot.generated_at || 0));
      var playerKeys = defaultBoards.filter(function (key) { return key in (state.snapshot.individual || {}); });
      var clanKeys = defaultBoards.filter(function (key) { return key in (state.snapshot.clan || {}); });
      var eventKeys = eventBoards.filter(function (key) { return key in (state.snapshot.individual || {}); });
      if (!playerKeys.includes(state.playerBoard)) state.playerBoard = playerKeys[0];
      if (!clanKeys.includes(state.clanBoard)) state.clanBoard = clanKeys[0];
      if (!eventKeys.includes(state.eventBoard)) state.eventBoard = eventKeys[0];
      tabs(byId("player-tabs"), playerKeys, state.playerBoard, function (key) { state.playerBoard = key; renderLeaderboards(); });
      tabs(byId("clan-tabs"), clanKeys, state.clanBoard, function (key) { state.clanBoard = key; renderLeaderboards(); });
      tabs(byId("event-tabs"), eventKeys, state.eventBoard, function (key) { state.eventBoard = key; renderLeaderboards(); });
      renderLeaderboards();
      renderBattle();
      selectLeaderboardView(state.leaderboardView);
    } catch (error) {
      toast(error.message, true);
    }
  }

  async function loadMe() {
    state.me = await api("/api/me");
    if (!state.me.authenticated) return;
    byId("owner-account").innerHTML = '<div class="live-user-pill"><img src="' + escapeHtml(state.me.avatar_url) +
      '" alt=""><span><strong>' + escapeHtml(state.me.display_name) +
      '</strong><button id="owner-logout" type="button">Sign out</button></span></div>';
    byId("owner-logout").addEventListener("click", async function () {
      await api("/auth/logout", {method: "POST"});
      window.location.reload();
    });
    byId("control-lock").hidden = true;
    byId("owner-content").hidden = false;
    await Promise.all([loadSettings(), loadLogs()]);
  }
  async function loadSettings() {
    try {
      var payload = await api("/api/settings");
      state.settings = payload.variables || [];
      var categories = ["All"].concat(Array.from(new Set(state.settings.map(function (item) { return item.category; }))));
      if (!categories.includes(state.category)) state.category = "All";
      byId("setting-categories").innerHTML = categories.map(function (category) {
        return '<button type="button" data-category="' + escapeHtml(category) + '" aria-pressed="' +
          (category === state.category ? "true" : "false") + '">' + escapeHtml(category) + "</button>";
      }).join("");
      byId("setting-categories").querySelectorAll("button").forEach(function (button) {
        button.addEventListener("click", function () { state.category = button.dataset.category; renderSettings(); });
      });
      renderSettings();
    } catch (error) {
      toast(error.message, true);
    }
  }
  function renderSettings() {
    var search = byId("setting-search");
    var query = search ? search.value.trim().toLowerCase() : "";
    var filtered = state.settings.filter(function (item) {
      return (state.category === "All" || item.category === state.category) &&
        (!query || (item.key + " " + item.label + " " + item.description).toLowerCase().includes(query));
    });
    byId("settings-grid").innerHTML = filtered.map(function (item) {
      var input = item.type === "boolean"
        ? '<select data-input><option value="true" ' + (item.value === true ? "selected" : "") +
          '>Enabled</option><option value="false" ' + (item.value === false ? "selected" : "") + ">Disabled</option></select>"
        : '<input data-input type="number" value="' + escapeHtml(item.value) + '" min="' +
          escapeHtml(item.minimum) + '" max="' + escapeHtml(item.maximum) + '">';
      var chance = item.chance_percent !== undefined
        ? '<span class="live-chance">' + Number(item.chance_percent).toFixed(6) + "% current chance</span>"
        : "";
      return '<article class="live-setting-card" data-key="' + escapeHtml(item.key) + '"><header><h3>' +
        escapeHtml(item.label) + "</h3>" + (item.overridden ? '<span class="live-overridden">OVERRIDE</span>' : "") +
        "</header><code>" + escapeHtml(item.key) + "</code><p>" + escapeHtml(item.description) + "</p>" + chance +
        '<div class="live-setting-input">' + input + '<button data-save type="button">Apply</button>' +
        (item.overridden ? '<button data-reset class="reset" type="button">Reset</button>' : "") + "</div></article>";
    }).join("") || '<p class="live-empty">No variables match this view.</p>';
    byId("settings-grid").querySelectorAll(".live-setting-card").forEach(function (card) {
      card.querySelector("[data-save]").addEventListener("click", function () {
        changeSetting(card.dataset.key, card.querySelector("[data-input]").value, false);
      });
      var reset = card.querySelector("[data-reset]");
      if (reset) reset.addEventListener("click", function () { changeSetting(card.dataset.key, "", true); });
    });
    byId("setting-categories").querySelectorAll("button").forEach(function (button) {
      button.setAttribute("aria-pressed", button.dataset.category === state.category ? "true" : "false");
    });
  }
  async function changeSetting(key, value, reset) {
    try {
      var result = await api("/api/settings/" + encodeURIComponent(key), {
        method: "PATCH",
        headers: {"Content-Type": "application/json", "X-MGX-CSRF": state.me.csrf},
        body: JSON.stringify(reset ? {reset: true} : {value: value})
      });
      toast(result.message + ". No restart required.", false);
      await Promise.all([loadSettings(), loadLogs()]);
    } catch (error) {
      toast(error.message, true);
    }
  }
  async function loadLogs() {
    try {
      var data = await api("/api/logs");
      byId("logs-content").innerHTML = data.logs.length ? data.logs.map(function (row) {
        return '<div class="live-log-row"><span class="' + (row.outcome === "success" ? "ok" : "failed") + '">' +
          escapeHtml(row.outcome) + "</span><span><strong>" + escapeHtml(row.command) + "</strong><br>" +
          escapeHtml(row.actor_label) + (row.detail ? " · " + escapeHtml(row.detail) : "") + "</span><time>" +
          new Date(Number(row.created_at) * 1000).toLocaleString() + "</time></div>";
      }).join("") : '<p class="live-empty">No control activity has been recorded.</p>';
    } catch (error) {
      byId("logs-content").innerHTML = '<p class="live-empty">' + escapeHtml(error.message) + "</p>";
    }
  }

  if (byId("leaderboard-root")) {
    document.querySelectorAll("[data-view]").forEach(function (button) {
      button.addEventListener("click", function () { selectLeaderboardView(button.dataset.view); });
    });
    loadLeaderboards();
    window.setInterval(loadLeaderboards, 60000);
  }
  if (byId("control-root")) {
    var search = byId("setting-search");
    if (search) search.addEventListener("input", renderSettings);
    var refresh = byId("refresh-settings");
    if (refresh) refresh.addEventListener("click", loadSettings);
    loadMe().catch(function (error) { toast(error.message, true); });
  }
}());
