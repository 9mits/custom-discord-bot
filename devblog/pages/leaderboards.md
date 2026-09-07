---
title: Server Leaderboards
nav: Leaderboards
order: 1
layout: dashboard
tagline: Every published player and clan leaderboard, directly from the same Paper snapshot used in game and on Discord.
---

<div class="mgx-live-page" id="leaderboard-root">
  <div class="live-leaderboard-toolbar">
    <div class="live-view-tabs" role="tablist" aria-label="Leaderboard sections">
      <button type="button" role="tab" data-view="leaderboards" aria-selected="true">Leaderboards</button>
      <button type="button" role="tab" data-view="pvp-ranks" aria-selected="false">PvP Ranks</button>
      <button type="button" role="tab" data-view="events" aria-selected="false">Events</button>
      <button type="button" role="tab" data-view="clan-battle" aria-selected="false">Clan Battle</button>
    </div>
    <section class="live-status-card" aria-live="polite">
      <span class="live-pulse" aria-hidden="true"></span>
      <div><small>LAST SERVER SNAPSHOT</small><strong id="generated-at">Waiting for Paper</strong></div>
    </section>
  </div>

  <div class="live-view-panel" data-view-panel="leaderboards">
    <section class="live-panel">
      <div class="live-panel-head">
        <div><p class="live-eyebrow">INDIVIDUAL STANDINGS</p><h2>Player Leaderboards</h2></div>
        <div id="player-tabs" class="live-tabs" role="tablist" aria-label="Player leaderboard"></div>
      </div>
      <div id="player-board" class="live-board live-loading"></div>
    </section>

    <section class="live-panel live-clan-panel">
      <div class="live-panel-head">
        <div><p class="live-eyebrow">TEAM STANDINGS</p><h2>Clan Leaderboards</h2></div>
        <div id="clan-tabs" class="live-tabs" role="tablist" aria-label="Clan leaderboard"></div>
      </div>
      <div id="clan-board" class="live-board"></div>
    </section>
  </div>

  <div class="live-view-panel" data-view-panel="pvp-ranks" hidden>
    <section class="live-panel">
      <div class="live-panel-head">
        <div class="live-event-heading">
          <span class="live-board-icon" aria-hidden="true"><img class="live-minecraft-icon" src="/assets/minecraft-items/nether_star.png" alt="Nether Star"></span>
          <div><p class="live-eyebrow">THE DUELLING LADDER</p><h2>PvP Rank Leaderboard</h2><p class="live-panel-description">Every <code>/pvp</code> fight is rated against the player you beat, so the climb from Bronze to Unreal is won against real opposition.</p></div>
        </div>
      </div>
      <div id="pvp-board" class="live-board live-loading"></div>
    </section>
  </div>

  <div class="live-view-panel" data-view-panel="events" hidden>
    <section class="live-panel live-event-panel">
      <div class="live-panel-head">
        <div class="live-event-heading">
          <span id="event-icon" class="live-board-icon" aria-hidden="true"></span>
          <div><p class="live-eyebrow">LIMITED-TIME COMPETITIONS</p><h2>Event Leaderboards</h2><p id="event-description" class="live-panel-description">Race for exclusive event records while they are live.</p></div>
        </div>
        <div id="event-tabs" class="live-tabs live-event-tabs" role="tablist" aria-label="Event leaderboard"></div>
      </div>
      <div id="event-board" class="live-board"></div>
    </section>
  </div>

  <div class="live-view-panel" data-view-panel="clan-battle" hidden>
    <section class="live-battle" id="clan-battle">
      <div class="live-battle-copy">
        <span class="live-board-icon live-battle-icon"><img class="live-minecraft-icon" src="/assets/minecraft-items/crate_key.png" alt="Crate Key"></span>
        <p class="live-eyebrow">CURRENT CLAN BATTLE</p>
        <h2 id="battle-title">No active battle</h2>
        <p id="battle-objective">When the next clan battle starts, its objective and live standings will appear here.</p>
        <div id="battle-deadline" class="live-deadline"></div>
      </div>
      <div id="battle-board" class="live-battle-board"></div>
    </section>
  </div>
</div>

<div id="live-toast" class="live-toast" role="status" aria-live="polite"></div>
