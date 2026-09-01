---
title: Owner Control Panel
nav: Control
nav_hidden: true
private: true
order: 5
layout: console
tagline: Balance crates, airdrops, rewards and events from one place. Nothing goes live until you publish it, and every change can be undone.
---

<div id="console-root">

  <section id="control-lock">
    <img src="../assets/icon.png" alt="" aria-hidden="true">
    <h2>Owner access only</h2>
    <p>Sign in with Discord. Access remains available only while your account holds the exact role mapped to the LuckPerms <strong>owner</strong> group.</p>
    <a class="con-primary" href="/auth/login">Authorize with Discord</a>
  </section>

  <div id="owner-content" hidden>
    <div class="con-shell">

      <aside class="con-rail">
        <div class="cx-brand">
          <img src="../assets/icon.png" alt="">
          <b>Control</b>
          <small>Live</small>
        </div>
        <nav class="cx-nav" id="con-nav" aria-label="Sections"></nav>
        <div class="cx-side-foot">
          <div id="owner-account" class="live-owner-account"></div>
          <!--theme-switch-->
        </div>
      </aside>

      <div class="con-main">
        <header class="con-head">
          <div class="con-head-title">
            <span class="cx-crumb">Mysterious SMP X</span>
            <h2 id="con-page-title">Overview</h2>
          </div>
          <div class="cx-top-right">
            <div class="cx-search">
              <input id="con-search" type="search" placeholder="Search settings, or paste a key" aria-label="Search settings">
              <kbd aria-hidden="true">/</kbd>
            </div>
            <span class="cx-status off" id="con-status">Checking</span>
          </div>
        </header>

        <div id="con-page"></div>

        <div class="con-draftbar" id="con-draftbar" hidden></div>
      </div>
    </div>

    <div class="con-modal" id="con-add" hidden>
      <div class="con-modal-card" role="dialog" aria-modal="true" aria-labelledby="con-add-title">
        <header>
          <h3 id="con-add-title">Add an item</h3>
          <button type="button" class="con-close" data-close aria-label="Close">&times;</button>
        </header>
        <div id="con-add-body"></div>
        <footer>
          <button type="button" class="con-secondary" data-close>Cancel</button>
          <button type="button" class="con-primary" id="con-add-confirm">Add it</button>
        </footer>
      </div>
    </div>

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
