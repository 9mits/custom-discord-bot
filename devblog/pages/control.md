---
title: Owner Control Panel
nav: Control
nav_hidden: true
private: true
order: 5
layout: console
tagline: Balance crates, airdrops, rewards and events from one place. Nothing goes live until you publish it, and every change can be undone.
---

<div class="mgx-live-page" id="console-root">
  <div id="owner-account" class="live-owner-account"><a class="btn btn-discord" href="/auth/login">Authorize with Discord</a></div>

  <section id="control-lock" class="live-lock-card">
    <img src="../assets/icon.png" alt="" aria-hidden="true">
    <h2>Owner access only</h2>
    <p>Sign in with Discord. Access remains available only while your account holds the exact role mapped to the LuckPerms <strong>owner</strong> group.</p>
    <a class="btn btn-discord" href="/auth/login">Authorize with Discord</a>
  </section>

  <div id="owner-content" hidden>
    <div class="con-shell">
      <nav class="con-rail" id="con-nav" aria-label="Settings sections"></nav>

      <div class="con-main">
        <header class="con-head">
          <div>
            <p class="live-eyebrow">OWNER CONTROL PANEL</p>
            <h2 id="con-page-title">Overview</h2>
          </div>
          <input id="con-search" type="search" placeholder="Search settings, or paste a key" aria-label="Search settings">
        </header>

        <div id="con-page"></div>
      </div>
    </div>

    <div class="con-draftbar" id="con-draftbar" hidden></div>

    <div class="con-modal" id="con-preview" hidden>
      <div class="con-modal-card" role="dialog" aria-modal="true" aria-labelledby="con-preview-title">
        <header>
          <h3 id="con-preview-title">Review before publishing</h3>
          <button type="button" class="con-close" data-close aria-label="Close">&times;</button>
        </header>
        <div id="con-preview-body"></div>
        <footer>
          <button type="button" class="con-secondary" data-close>Keep editing</button>
          <button type="button" class="con-primary" id="con-preview-publish">Publish</button>
        </footer>
      </div>
    </div>
  </div>
</div>

<div id="live-toast" class="live-toast" role="status" aria-live="polite"></div>
