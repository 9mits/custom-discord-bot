---
title: Owner Control Panel
nav: Control
nav_hidden: true
order: 5
layout: dashboard
tagline: Change live crate, key, event and airdrop values without restarting Paper. Exact odds and audit logs remain owner-only.
---

<div class="mgx-live-page" id="control-root">
  <div id="owner-account" class="live-owner-account"><a class="btn btn-discord" href="/auth/login">Authorize with Discord</a></div>

  <section id="control-lock" class="live-lock-card">
    <img src="../assets/icon.png" alt="" aria-hidden="true">
    <h2>Owner access only</h2>
    <p>Sign in with Discord. Access remains available only while your account holds the exact role mapped to the LuckPerms <strong>owner</strong> group.</p>
    <a class="btn btn-discord" href="/auth/login">Authorize with Discord</a>
  </section>

  <div id="owner-content" hidden>
    <section class="live-panel">
      <div class="live-panel-head">
        <div><p class="live-eyebrow">OWNER CONTROL PANEL</p><h2>Live game variables</h2></div>
        <div class="live-control-actions">
          <input id="setting-search" type="search" placeholder="Search everything" aria-label="Search settings">
          <button id="refresh-settings" class="btn live-secondary" type="button">Refresh</button>
        </div>
      </div>
      <div id="setting-categories" class="live-category-rail"></div>
      <div id="settings-grid" class="live-settings-grid"></div>
    </section>

    <section class="live-panel">
      <div class="live-panel-head"><div><p class="live-eyebrow">AUDIT TRAIL</p><h2>Recent control activity</h2></div></div>
      <div id="logs-content" class="live-log-list"><p class="live-empty">Loading control activity…</p></div>
    </section>
  </div>
</div>

<div id="live-toast" class="live-toast" role="status" aria-live="polite"></div>
