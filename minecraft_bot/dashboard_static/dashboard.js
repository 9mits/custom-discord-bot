const state = { snapshot: null, me: null, settings: [], category: "All", playerBoard: "wealth", clanBoard: "wealth" };
const labels = {
  wealth: "Richest", kills: "Most Kills", amethyst_crates: "Amethyst Crates",
  amethyst_airdrops: "Airdrops Claimed", clan_battle: "Clan Battle"
};
const $ = id => document.getElementById(id);
const esc = value => String(value ?? "").replace(/[&<>'"]/g, c => ({"&":"&amp;","<":"&lt;",">":"&gt;","'":"&#39;",'"':"&quot;"}[c]));

function toast(message, error=false) {
  const node = $("toast"); node.textContent = message; node.className = `toast show${error ? " error" : ""}`;
  clearTimeout(toast.timer); toast.timer = setTimeout(() => node.className = "toast", 3500);
}
async function api(url, options={}) {
  const response = await fetch(url, {cache:"no-store", ...options});
  if (!response.ok) throw new Error((await response.text()) || `Request failed (${response.status})`);
  return response.json();
}
function relativeTime(timestamp) {
  if (!timestamp) return "Waiting for Paper";
  const seconds = Math.round((timestamp - Date.now()) / 1000);
  const formatter = new Intl.RelativeTimeFormat(undefined, {numeric:"auto"});
  const [amount, unit] = Math.abs(seconds) >= 3600 ? [Math.round(seconds/3600), "hour"] : Math.abs(seconds) >= 60 ? [Math.round(seconds/60), "minute"] : [seconds, "second"];
  return formatter.format(amount, unit);
}
function tabs(target, keys, active, onSelect) {
  target.innerHTML = keys.map(key => `<button type="button" data-key="${esc(key)}" class="${key===active?'active':''}">${esc(labels[key] || key.replaceAll('_',' '))}</button>`).join("");
  target.querySelectorAll("button").forEach(button => button.onclick = () => onSelect(button.dataset.key));
}
function rankCard(row, index, clan=false) {
  const rank = Number(row.rank || index + 1);
  const head = !clan && row.head_url ? `<img src="${esc(row.head_url)}" alt="${esc(row.username)} Minecraft head" loading="lazy">` : "";
  const name = clan ? row.clan : row.username;
  const discord = !clan ? `<div class="discord-name">${row.discord_username ? '@'+esc(row.discord_username) : 'No linked Discord name'}</div>` : `<div class="discord-name">${esc(row.members || 0)} members · Level ${esc(row.level || 0)}</div>`;
  return `<article class="rank-card ${rank<=3?'top-three':''} ${clan?'clan-card':''}"><div class="place">#${rank}</div>${head}<h3>${esc(name || '?')}</h3>${discord}<div class="value">${esc(row.display ?? row.value ?? 0)}</div></article>`;
}
function renderBoard(scope, board) {
  const rows = state.snapshot?.[scope]?.[board] || [];
  const target = scope === "individual" ? $("player-board") : $("clan-board");
  target.classList.remove("skeleton-board");
  target.innerHTML = rows.length ? rows.map((row,index) => rankCard(row,index,scope==="clan")).join("") : '<p class="empty">No standings yet.</p>';
}
function renderBattle() {
  const event = state.snapshot?.clan_battle || {};
  const rows = state.snapshot?.clan?.clan_battle || [];
  $("battle-title").textContent = event.name || "No active battle";
  $("battle-objective").textContent = event.objective || "When the next clan battle starts, its objective and live standings will appear here.";
  $("battle-deadline").textContent = event.ends_at ? `Ends ${new Date(event.ends_at).toLocaleString()}` : "";
  $("battle-board").innerHTML = rows.length ? rows.map((row,index) => `<div class="battle-row"><span class="battle-place">#${esc(row.rank || index+1)}</span><strong>${esc(row.clan)}</strong><span>${esc(row.display || row.value)}</span></div>`).join("") : '<p class="empty">No active clan battle standings.</p>';
}
async function loadLeaderboards() {
  try {
    state.snapshot = await api("/api/leaderboards");
    $("generated-at").textContent = relativeTime(Number(state.snapshot.generated_at || 0));
    const playerKeys = Object.keys(state.snapshot.individual || {});
    const clanKeys = Object.keys(state.snapshot.clan || {}).filter(key => key !== "clan_battle");
    if (!playerKeys.includes(state.playerBoard)) state.playerBoard = playerKeys[0];
    if (!clanKeys.includes(state.clanBoard)) state.clanBoard = clanKeys[0];
    tabs($("player-tabs"), playerKeys, state.playerBoard, key => {state.playerBoard=key; renderLeaderboards();});
    tabs($("clan-tabs"), clanKeys, state.clanBoard, key => {state.clanBoard=key; renderLeaderboards();});
    renderLeaderboards(); renderBattle();
  } catch (error) { toast(error.message, true); }
}
function renderLeaderboards() {
  renderBoard("individual", state.playerBoard);
  renderBoard("clan", state.clanBoard);
  $("player-tabs").querySelectorAll("button").forEach(b => b.classList.toggle("active", b.dataset.key===state.playerBoard));
  $("clan-tabs").querySelectorAll("button").forEach(b => b.classList.toggle("active", b.dataset.key===state.clanBoard));
}
async function loadMe() {
  state.me = await api("/api/me");
  if (!state.me.authenticated) return;
  $("account").innerHTML = `<div class="user-pill"><img src="${esc(state.me.avatar_url)}" alt=""><span><b>${esc(state.me.display_name)}</b><button id="logout" type="button">Sign out</button></span></div>`;
  $("logout").onclick = async () => { await api("/auth/logout", {method:"POST"}); location.reload(); };
  $("control").classList.remove("locked"); $("control-lock").hidden = true; $("settings-content").hidden = false;
  await Promise.all([loadSettings(), loadLogs()]);
}
async function loadSettings() {
  try {
    const payload = await api("/api/settings"); state.settings = payload.variables || [];
    const categories = ["All", ...new Set(state.settings.map(item => item.category))];
    if (!categories.includes(state.category)) state.category = "All";
    $("setting-categories").innerHTML = categories.map(category => `<button type="button" data-category="${esc(category)}" class="${category===state.category?'active':''}">${esc(category)}</button>`).join("");
    $("setting-categories").querySelectorAll("button").forEach(button => button.onclick = () => {state.category=button.dataset.category; renderSettings();});
    renderSettings();
  } catch (error) { toast(error.message, true); }
}
function renderSettings() {
  const query = $("setting-search").value.trim().toLowerCase();
  const filtered = state.settings.filter(item => (state.category === "All" || item.category === state.category) && (!query || `${item.key} ${item.label} ${item.description}`.toLowerCase().includes(query)));
  $("settings-grid").innerHTML = filtered.map(item => {
    const input = item.type === "boolean" ? `<select data-input><option value="true" ${item.value===true?'selected':''}>Enabled</option><option value="false" ${item.value===false?'selected':''}>Disabled</option></select>` : `<input data-input type="number" value="${esc(item.value)}" min="${esc(item.minimum)}" max="${esc(item.maximum)}">`;
    const chance = item.chance_percent !== undefined ? `<span class="chance">${Number(item.chance_percent).toFixed(6)}% current chance</span>` : "";
    return `<article class="setting-card" data-key="${esc(item.key)}"><header><h3>${esc(item.label)}</h3>${item.overridden?'<span class="overridden">OVERRIDE</span>':''}</header><code>${esc(item.key)}</code><p>${esc(item.description)}</p>${chance}<div class="setting-input">${input}<button data-save type="button">Apply</button>${item.overridden?'<button data-reset class="reset" type="button">Reset</button>':''}</div></article>`;
  }).join("") || '<p class="empty">No variables match this view.</p>';
  $("settings-grid").querySelectorAll(".setting-card").forEach(card => {
    card.querySelector("[data-save]").onclick = () => changeSetting(card.dataset.key, card.querySelector("[data-input]").value, false);
    const reset = card.querySelector("[data-reset]"); if (reset) reset.onclick = () => changeSetting(card.dataset.key, "", true);
  });
  $("setting-categories").querySelectorAll("button").forEach(b => b.classList.toggle("active", b.dataset.category===state.category));
}
async function changeSetting(key, value, reset) {
  try {
    const result = await api(`/api/settings/${encodeURIComponent(key)}`, {method:"PATCH", headers:{"Content-Type":"application/json","X-MGX-CSRF":state.me.csrf}, body:JSON.stringify(reset?{reset:true}:{value})});
    toast(`${result.message}. No restart required.`); await Promise.all([loadSettings(), loadLogs()]);
  } catch (error) { toast(error.message, true); }
}
async function loadLogs() {
  try {
    const data = await api("/api/logs");
    $("logs-content").innerHTML = data.logs.length ? data.logs.map(row => `<div class="log-row"><span class="${row.outcome==='success'?'ok':'failed'}">${esc(row.outcome)}</span><span><b>${esc(row.command)}</b><br>${esc(row.actor_label)}${row.detail?' · '+esc(row.detail):''}</span><time>${new Date(Number(row.created_at)*1000).toLocaleString()}</time></div>`).join("") : '<p class="empty">No control activity has been recorded.</p>';
  } catch (error) { $("logs-content").innerHTML = `<p class="empty">${esc(error.message)}</p>`; }
}
$("setting-search").addEventListener("input", renderSettings);
$("refresh-settings").addEventListener("click", loadSettings);
loadLeaderboards(); loadMe().catch(error => toast(error.message, true));
setInterval(loadLeaderboards, 60000);
