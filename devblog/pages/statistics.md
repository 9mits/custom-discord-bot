---
title: Server Statistics
nav: Statistics
nav_hidden: true
private: true
order: 6
layout: statistics
tagline: Every figure the server records, charted over time. Activity, AFK, access and standings, owner-only.
---

<div class="mgx-live-page" id="stats-root">
  <div id="owner-account" class="live-owner-account"><a class="btn btn-discord" href="/auth/login">Authorize with Discord</a></div>

  <section id="stats-lock" class="live-lock-card">
    <img src="../assets/icon.png" alt="" aria-hidden="true">
    <h2>Owner access only</h2>
    <p>Sign in with Discord. Statistics remain available only while your account holds the exact role mapped to the LuckPerms <strong>owner</strong> group.</p>
    <a class="btn btn-discord" href="/auth/login">Authorize with Discord</a>
  </section>

  <div id="stats-content" hidden>

    <section class="live-panel">
      <div class="live-panel-head">
        <div><p class="live-eyebrow">AT A GLANCE</p><h2>Headline figures</h2></div>
        <div class="live-control-actions">
          <label class="stat-range-label" for="stats-range">Window</label>
          <select id="stats-range" class="stat-select" aria-label="Time window">
            <option value="1">24 hours</option>
            <option value="7">7 days</option>
            <option value="30" selected>30 days</option>
            <option value="90">90 days</option>
            <option value="365">1 year</option>
          </select>
          <button id="stats-refresh" class="btn live-secondary" type="button">Refresh</button>
        </div>
      </div>
      <div id="stat-tiles" class="stat-tile-grid"></div>
    </section>

    <section class="live-panel">
      <div class="live-panel-head">
        <div><p class="live-eyebrow">OVER TIME</p><h2>Sampled history</h2></div>
        <div class="live-control-actions">
          <input id="metric-search" type="search" placeholder="Filter metrics" aria-label="Filter metrics">
        </div>
      </div>
      <p class="stat-note" id="sampling-note"></p>
      <div id="metric-toggles" class="stat-toggle-rail"></div>
      <div id="stat-charts" class="stat-chart-grid"></div>
    </section>

    <section class="live-panel">
      <div class="live-panel-head"><div><p class="live-eyebrow">WHEN PEOPLE PLAY</p><h2>Busiest hours</h2></div></div>
      <div id="stat-heatmap" class="stat-heatmap-wrap"></div>
    </section>

    <section class="live-panel">
      <div class="live-panel-head"><div><p class="live-eyebrow">IDLE TIME</p><h2>AFK</h2></div></div>
      <div id="afk-summary" class="stat-tile-grid"></div>
      <div id="afk-table" class="stat-table-wrap"></div>
    </section>

    <section class="live-panel">
      <div class="live-panel-head"><div><p class="live-eyebrow">STANDINGS</p><h2>Every leaderboard</h2></div></div>
      <div id="stat-boards" class="stat-board-grid"></div>
    </section>

  </div>
</div>

<div id="live-toast" class="live-toast" role="status" aria-live="polite"></div>
