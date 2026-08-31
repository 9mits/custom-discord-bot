/*
 * Server Statistics.
 *
 * Charts are hand-built SVG. The site ships no charting library and should not start:
 * a CDN is blocked outright, and bundling one to draw line charts and a heatmap costs
 * more bytes than the whole page. Everything here is one file with no dependencies.
 *
 * Nothing is invented. A metric with no samples draws an empty state saying when
 * sampling began rather than a flat line at zero, because "no data yet" and "the value
 * was zero" are different facts and a chart that conflates them lies for a fortnight.
 */
(function () {
  "use strict";

  var steveHead = "https://api.mcheads.org/ioshead/MHF_Steve/left";

  var root = document.getElementById("stats-root");
  if (!root) { return; }

  var $ = function (id) { return document.getElementById(id); };
  var esc = function (value) {
    return String(value == null ? "" : value).replace(/[&<>"']/g, function (c) {
      return { "&": "&amp;", "<": "&lt;", ">": "&gt;", '"': "&quot;", "'": "&#39;" }[c];
    });
  };

  var state = { days: 30, selected: [], overview: null };

  /* ---------------------------------------------------------------- formatting */

  function compactNumber(value) {
    var n = Number(value) || 0;
    var abs = Math.abs(n);
    if (abs >= 1e9) { return trim(n / 1e9) + "B"; }
    if (abs >= 1e6) { return trim(n / 1e6) + "M"; }
    if (abs >= 1e3) { return trim(n / 1e3) + "K"; }
    return String(Math.round(n * 100) / 100);
  }
  function trim(v) { return (Math.round(v * 10) / 10).toString(); }

  function duration(seconds) {
    var s = Math.max(0, Math.round(Number(seconds) || 0));
    var h = Math.floor(s / 3600);
    var m = Math.floor((s % 3600) / 60);
    if (h >= 24) { return Math.floor(h / 24) + "d " + (h % 24) + "h"; }
    if (h > 0) { return h + "h " + m + "m"; }
    if (m > 0) { return m + "m"; }
    return s + "s";
  }

  /* Metric names are machine-shaped; these are what a person calls them. */
  var LABELS = {
    "players.online": "Players online",
    "players.joins_24h": "Joins (24h)",
    "players.java_joins_24h": "Java joins (24h)",
    "players.bedrock_joins_24h": "Bedrock joins (24h)",
    "access.verified": "Verified accounts",
    "access.total": "Access records",
    "afk.seconds_24h": "AFK time (24h)",
    "afk.peak_concurrent": "Peak concurrent AFK",
    "afk.java_seconds_24h": "AFK time — Java (24h)",
    "afk.bedrock_seconds_24h": "AFK time — Bedrock (24h)"
  };
  function label(metric) { return LABELS[metric] || metric; }
  function isDuration(metric) { return metric.indexOf("seconds") !== -1; }

  /* -------------------------------------------------------------------- charts */

  var NS = "http://www.w3.org/2000/svg";
  function svgEl(name, attrs) {
    var node = document.createElementNS(NS, name);
    Object.keys(attrs || {}).forEach(function (k) { node.setAttribute(k, attrs[k]); });
    return node;
  }

  /**
   * One line chart. Width is 100% via viewBox so it scales without a resize listener.
   */
  function lineChart(points, metric) {
    var W = 640, H = 200, padL = 52, padR = 12, padT = 14, padB = 26;
    var svg = svgEl("svg", {
      viewBox: "0 0 " + W + " " + H, class: "stat-chart",
      preserveAspectRatio: "none", role: "img",
      "aria-label": label(metric) + " over time"
    });

    var values = points.map(function (p) { return Number(p.value) || 0; });
    var max = Math.max.apply(null, values.concat([1]));
    var min = Math.min.apply(null, values.concat([0]));
    if (max === min) { max = min + 1; }
    var t0 = points[0].at, t1 = points[points.length - 1].at;
    var span = Math.max(1, t1 - t0);

    var x = function (at) { return padL + ((at - t0) / span) * (W - padL - padR); };
    var y = function (v) { return padT + (1 - (v - min) / (max - min)) * (H - padT - padB); };

    // Four gridlines, labelled. A chart without a scale is a decoration.
    for (var i = 0; i <= 3; i++) {
      var value = min + ((max - min) * i) / 3;
      var gy = y(value);
      svg.appendChild(svgEl("line", {
        x1: padL, x2: W - padR, y1: gy, y2: gy, class: "stat-grid"
      }));
      var text = svgEl("text", { x: padL - 8, y: gy + 4, class: "stat-axis", "text-anchor": "end" });
      text.textContent = isDuration(metric) ? duration(value) : compactNumber(value);
      svg.appendChild(text);
    }

    var line = points.map(function (p, idx) {
      return (idx ? "L" : "M") + x(p.at).toFixed(1) + " " + y(p.value).toFixed(1);
    }).join(" ");
    var area = line + " L" + x(t1).toFixed(1) + " " + y(min).toFixed(1)
             + " L" + x(t0).toFixed(1) + " " + y(min).toFixed(1) + " Z";

    svg.appendChild(svgEl("path", { d: area, class: "stat-area" }));
    svg.appendChild(svgEl("path", { d: line, class: "stat-line" }));

    // The endpoint is the number people actually want; give it a dot.
    svg.appendChild(svgEl("circle", {
      cx: x(t1), cy: y(values[values.length - 1]), r: 3.5, class: "stat-endpoint"
    }));

    var start = svgEl("text", { x: padL, y: H - 8, class: "stat-axis" });
    start.textContent = new Date(t0 * 1000).toLocaleDateString();
    svg.appendChild(start);
    var end = svgEl("text", { x: W - padR, y: H - 8, class: "stat-axis", "text-anchor": "end" });
    end.textContent = new Date(t1 * 1000).toLocaleDateString();
    svg.appendChild(end);
    return svg;
  }

  function chartCard(metric, points) {
    var card = document.createElement("figure");
    card.className = "stat-chart-card";
    var latest = points.length ? points[points.length - 1].value : 0;
    var first = points.length ? points[0].value : 0;
    var delta = latest - first;
    var deltaClass = delta > 0 ? "up" : (delta < 0 ? "down" : "flat");
    var shown = isDuration(metric) ? duration(latest) : compactNumber(latest);

    card.innerHTML =
      '<figcaption class="stat-chart-head">' +
        '<span class="stat-chart-name">' + esc(label(metric)) + "</span>" +
        '<span class="stat-chart-value">' + esc(shown) +
          (points.length > 1
            ? ' <span class="stat-delta ' + deltaClass + '">' +
              (delta > 0 ? "+" : "") +
              esc(isDuration(metric) ? duration(Math.abs(delta)) : compactNumber(delta)) +
              "</span>"
            : "") +
        "</span>" +
      "</figcaption>";

    if (points.length < 2) {
      var empty = document.createElement("p");
      empty.className = "stat-empty";
      empty.textContent = points.length === 1
        ? "Only one sample so far. A line needs two — the next one lands within 15 minutes."
        : "No samples in this window yet. Sampling runs every 15 minutes.";
      card.appendChild(empty);
    } else {
      card.appendChild(lineChart(points, metric));
    }
    return card;
  }

  /* ------------------------------------------------------------------- heatmap */

  var WEEKDAYS = ["Mon", "Tue", "Wed", "Thu", "Fri", "Sat", "Sun"];

  function heatmap(busiest) {
    if (!busiest || !busiest.length) {
      return '<p class="stat-empty">No activity recorded in this window yet.</p>';
    }
    var grid = {};
    var peak = 0;
    busiest.forEach(function (item) {
      var key = item.weekday + ":" + item.hour;
      grid[key] = item.average;
      if (item.average > peak) { peak = item.average; }
    });
    var html = '<table class="stat-heatmap"><thead><tr><th></th>';
    for (var h = 0; h < 24; h++) {
      html += "<th>" + (h % 3 === 0 ? h : "") + "</th>";
    }
    html += "</tr></thead><tbody>";
    for (var d = 0; d < 7; d++) {
      html += "<tr><th>" + WEEKDAYS[d] + "</th>";
      for (var hour = 0; hour < 24; hour++) {
        var value = grid[d + ":" + hour] || 0;
        var intensity = peak > 0 ? value / peak : 0;
        html += '<td style="--heat:' + intensity.toFixed(3) + '" title="' +
                esc(WEEKDAYS[d] + " " + hour + ":00 JST — " +
                    (Math.round(value * 10) / 10) + " players") + '"></td>';
      }
      html += "</tr>";
    }
    return html + "</tbody></table><p class=\"stat-note\">Average observed players, JST. " +
           "Darker is busier; peak is " + (Math.round(peak * 10) / 10) + ".</p>";
  }

  /* --------------------------------------------------------------------- fetch */

  function api(path) {
    return fetch(path, { credentials: "same-origin" }).then(function (r) {
      if (r.status === 401 || r.status === 403) { return null; }
      if (!r.ok) { throw new Error("Request failed: " + r.status); }
      return r.json();
    });
  }

  function tile(name, value, note) {
    return '<div class="stat-tile"><span class="stat-tile-name">' + esc(name) +
           '</span><span class="stat-tile-value">' + esc(value) + "</span>" +
           (note ? '<span class="stat-tile-note">' + esc(note) + "</span>" : "") +
           "</div>";
  }

  function renderOverview(data) {
    state.overview = data;
    var a = data.activity || {};
    var access = data.access || {};
    var afk = data.afk || {};

    $("stat-tiles").innerHTML = [
      tile("Online now", a.current == null ? "—" : a.current),
      tile("Peak", a.peak == null ? "—" : a.peak,
           a.peak_at ? new Date(a.peak_at * 1000).toLocaleString() : ""),
      tile("Joins", a.joins == null ? "—" : a.joins,
           (a.java_joins || 0) + " Java · " + (a.bedrock_joins || 0) + " Bedrock"),
      tile("Verified accounts", access.VERIFIED == null ? "—" : access.VERIFIED,
           Object.keys(access).reduce(function (sum, k) { return sum + access[k]; }, 0) +
           " records total"),
      tile("AFK recorded", duration(afk.total_seconds || 0),
           "peak " + (afk.peak_afk || 0) + " at once")
    ].join("");

    var byEdition = afk.by_edition || {};
    $("afk-summary").innerHTML = [
      tile("AFK — Java", duration(byEdition.JAVA || 0)),
      tile("AFK — Bedrock", duration(byEdition.BEDROCK || 0)),
      tile("Players who went AFK", (afk.players || []).length)
    ].join("");

    renderAfkTable(afk.players || []);
    $("stat-heatmap").innerHTML = heatmap(a.busiest);
    renderBoards(data.leaderboards || {});
    renderMetricToggles(data.metrics || []);

    $("sampling-note").textContent = (data.metrics || []).length
      ? "Sampled every " + Math.round((data.sampled_every_seconds || 900) / 60) +
        " minutes. History begins when sampling was switched on — earlier periods cannot be reconstructed."
      : "Sampling has just been switched on. The first charts appear once two samples exist.";
  }

  function renderAfkTable(players) {
    if (!players.length) {
      $("afk-table").innerHTML =
        '<p class="stat-empty">No completed AFK stretches recorded in this window yet.</p>';
      return;
    }
    var rows = players.slice(0, 50).map(function (p, i) {
      return "<tr><td>" + (i + 1) + "</td>" +
        '<td class="stat-player">' +
          '<img src="' + esc(p.head_url || steveHead) + '" data-head-fallback alt="" width="20" height="20">' +
          esc(p.username || "") + "</td>" +
        "<td>" + esc(p.edition === "BEDROCK" ? "Bedrock" : "Java") + "</td>" +
        '<td class="num">' + esc(duration(p.afk_seconds)) + "</td>" +
        '<td class="num">' + esc(p.sessions) + "</td></tr>";
    }).join("");
    $("afk-table").innerHTML =
      '<table class="stat-table"><thead><tr><th>#</th><th>Player</th><th>Edition</th>' +
      '<th class="num">AFK time</th><th class="num">Stretches</th></tr></thead><tbody>' +
      rows + "</tbody></table>";
    wireHeadFallbacks($("afk-table"));
  }

  function wireHeadFallbacks(target) {
    target.querySelectorAll("img[data-head-fallback]").forEach(function (image) {
      image.addEventListener("error", function () {
        if (image.src !== steveHead) image.src = steveHead;
      });
    });
  }

  function renderBoards(snapshot) {
    var host = $("stat-boards");
    var groups = ["individual", "clan"];
    var html = "";
    groups.forEach(function (group) {
      var boards = snapshot[group] || {};
      Object.keys(boards).forEach(function (key) {
        var rows = boards[key];
        if (!Array.isArray(rows) || !rows.length) { return; }
        html += '<div class="stat-board"><h3>' + esc(key.replace(/_/g, " ")) + "</h3><ol>";
        rows.slice(0, 10).forEach(function (row) {
          html += "<li><span>" +
            '<img src="' + esc(row.head_url || steveHead) + '" data-head-fallback alt="" width="18" height="18">' +
            esc(row.username || row.name || "") + "</span><b>" +
            esc(compactNumber(row.value != null ? row.value : row.score || 0)) + "</b></li>";
        });
        html += "</ol></div>";
      });
    });
    host.innerHTML = html || '<p class="stat-empty">No standings have been pushed by the server yet.</p>';
    wireHeadFallbacks(host);
  }

  function renderMetricToggles(metrics) {
    var host = $("metric-toggles");
    if (!metrics.length) { host.innerHTML = ""; return; }
    if (!state.selected.length) { state.selected = metrics.slice(0, 4); }
    var filter = ($("metric-search").value || "").toLowerCase();
    host.innerHTML = metrics.filter(function (m) {
      return !filter || label(m).toLowerCase().indexOf(filter) !== -1
             || m.toLowerCase().indexOf(filter) !== -1;
    }).map(function (m) {
      var on = state.selected.indexOf(m) !== -1;
      return '<button type="button" class="stat-toggle' + (on ? " on" : "") +
             '" data-metric="' + esc(m) + '" aria-pressed="' + on + '">' +
             esc(label(m)) + "</button>";
    }).join("");
    Array.prototype.forEach.call(host.querySelectorAll(".stat-toggle"), function (button) {
      button.addEventListener("click", function () {
        var metric = button.getAttribute("data-metric");
        var at = state.selected.indexOf(metric);
        if (at === -1) { state.selected.push(metric); } else { state.selected.splice(at, 1); }
        renderMetricToggles(metrics);
        loadSeries();
      });
    });
  }

  function loadSeries() {
    var host = $("stat-charts");
    if (!state.selected.length) {
      host.innerHTML = '<p class="stat-empty">Pick a metric above to chart it.</p>';
      return;
    }
    api("/api/stats/series?days=" + state.days + "&metric=" +
        encodeURIComponent(state.selected.join(","))).then(function (data) {
      if (!data) { return; }
      host.innerHTML = "";
      state.selected.forEach(function (metric) {
        host.appendChild(chartCard(metric, data.series[metric] || []));
      });
    }).catch(function (error) {
      host.innerHTML = '<p class="stat-empty">' + esc(error.message) + "</p>";
    });
  }

  function load() {
    api("/api/stats?days=" + state.days).then(function (data) {
      if (!data) {
        $("stats-lock").hidden = false;
        $("stats-content").hidden = true;
        return;
      }
      $("stats-lock").hidden = true;
      $("stats-content").hidden = false;
      renderOverview(data);
      loadSeries();
    }).catch(function (error) {
      $("stat-tiles").innerHTML = '<p class="stat-empty">' + esc(error.message) + "</p>";
    });
  }

  $("stats-range").addEventListener("change", function (event) {
    state.days = parseInt(event.target.value, 10) || 30;
    load();
  });
  $("stats-refresh").addEventListener("click", load);
  $("metric-search").addEventListener("input", function () {
    renderMetricToggles((state.overview && state.overview.metrics) || []);
  });

  load();
})();
