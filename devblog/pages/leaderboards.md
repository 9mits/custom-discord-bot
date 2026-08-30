---
title: Server Leaderboards
nav: Leaderboards
order: 1
layout: dashboard
tagline: Every published player and clan leaderboard, directly from the same Paper snapshot used in game and on Discord.
---

<div class="mgx-live-page" id="leaderboard-root">
  <section class="live-status-card" aria-live="polite">
    <span class="live-pulse" aria-hidden="true"></span>
    <div><small>LAST SERVER SNAPSHOT</small><strong id="generated-at">Waiting for Paper</strong></div>
  </section>

  <section class="live-panel">
    <div class="live-panel-head">
      <div><p class="live-eyebrow">PLAYER LEADERBOARDS</p><h2>The individual race</h2></div>
      <div id="player-tabs" class="live-tabs" role="tablist" aria-label="Player leaderboard"></div>
    </div>
    <div id="player-board" class="live-rank-grid live-loading"></div>
  </section>

  <section class="live-panel live-clan-panel">
    <div class="live-panel-head">
      <div><p class="live-eyebrow">CLAN LEADERBOARDS</p><h2>Teams moving the server</h2></div>
      <div id="clan-tabs" class="live-tabs" role="tablist" aria-label="Clan leaderboard"></div>
    </div>
    <div id="clan-board" class="live-rank-grid"></div>
  </section>

  <section class="live-battle" id="clan-battle">
    <div class="live-battle-copy">
      <p class="live-eyebrow">CURRENT CLAN BATTLE</p>
      <h2 id="battle-title">No active battle</h2>
      <p id="battle-objective">When the next clan battle starts, its objective and live standings will appear here.</p>
      <div id="battle-deadline" class="live-deadline"></div>
    </div>
    <div id="battle-board" class="live-battle-board"></div>
  </section>
</div>

<div id="live-toast" class="live-toast" role="status" aria-live="polite"></div>
