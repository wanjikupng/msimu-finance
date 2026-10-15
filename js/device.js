// Phone simulator: the lock agent of one financed device.
//
// Everything here runs on the "phone". It never calls the server: the
// provisioning data (device key, anchor date, grace days) was loaded by a
// technician from the staff console and lives in this browser's storage,
// standing in for the phone's secure element. Codes are verified locally with
// js/unlock-codec.js (the same scheme as the server's UnlockCodeCodec).
import { verifyCode, formatCode } from "./unlock-codec.js";
import { esc } from "./ui.js";

const deviceCode = new URLSearchParams(location.search).get("device") || "";
const STORE_KEY = `msimu.lockagent.${deviceCode}`;
const MAX_ATTEMPTS = 5;
const LOCKOUT_MS = 30 * 60 * 1000;

let agent = load();
let entry = "";
let message = null;

function load() {
  try {
    const raw = localStorage.getItem(STORE_KEY);
    return raw ? JSON.parse(raw) : null;
  } catch {
    return null;
  }
}
function save() {
  try { localStorage.setItem(STORE_KEY, JSON.stringify(agent)); } catch { /* storage unavailable */ }
}

// ---- local calendar arithmetic (the phone's own clock) ----
function parseDay(iso) {
  const [y, m, d] = iso.split("-").map(Number);
  return new Date(y, m - 1, d);
}
function isoDay(date) {
  const p = (n) => String(n).padStart(2, "0");
  return `${date.getFullYear()}-${p(date.getMonth() + 1)}-${p(date.getDate())}`;
}
function addDays(date, n) {
  const d = new Date(date);
  d.setDate(d.getDate() + n);
  return d;
}
function phoneNow() {
  return new Date(Date.now() + (agent?.clockOffsetDays || 0) * 86400000);
}
function phoneToday() {
  return isoDay(phoneNow());
}
function pretty(iso) {
  return parseDay(iso).toLocaleDateString("en-KE", { weekday: "short", day: "numeric", month: "short" });
}

// ---- lock decision: made entirely on the device ----
function lockState() {
  if (!agent) return "UNPROVISIONED";
  if (agent.permanent) return "OWNED";
  if (!agent.unlockedUntil) return "LOCKED";
  const today = phoneToday();
  if (today > agent.unlockedUntil) return "LOCKED";
  const dueDay = isoDay(addDays(parseDay(agent.unlockedUntil), -(agent.graceDays || 0)));
  return today > dueDay ? "GRACE" : "UNLOCKED";
}

async function applyCode(typed) {
  if (agent.lockoutUntil && Date.now() < agent.lockoutUntil) {
    const mins = Math.ceil((agent.lockoutUntil - Date.now()) / 60000);
    return { ok: false, text: `Too many wrong codes. Try again in ${mins} min.` };
  }
  const decoded = await verifyCode(agent.secretKey, agent.deviceCode, typed);
  if (!decoded) {
    agent.failedAttempts = (agent.failedAttempts || 0) + 1;
    if (agent.failedAttempts >= MAX_ATTEMPTS) {
      agent.lockoutUntil = Date.now() + LOCKOUT_MS;
      agent.failedAttempts = 0;
    }
    save();
    return { ok: false, text: "That code is not valid for this phone." };
  }
  if (decoded.sequence <= (agent.lastSequence || 0)) {
    return { ok: false, text: "That code has already been used. Enter your newest code." };
  }
  agent.lastSequence = decoded.sequence;
  agent.failedAttempts = 0;
  agent.lockoutUntil = null;
  if (decoded.permanent) {
    agent.permanent = true;
    agent.unlockedUntil = null;
  } else {
    agent.unlockedUntil = isoDay(addDays(parseDay(agent.anchorDate), decoded.days));
  }
  save();
  return {
    ok: true,
    text: decoded.permanent ? "Paid in full. This phone is now yours, unlocked for good." : `Unlocked until ${pretty(agent.unlockedUntil)}.`,
  };
}

// ---- rendering ----
const screen = () => document.getElementById("sim-screen");

function keypadHtml() {
  const keys = ["1", "2", "3", "4", "5", "6", "7", "8", "9", "⌫", "0", "OK"];
  return `<div class="sim-entry mono" aria-label="Code entered">${esc(formatCode(entry.padEnd(12, "·")))}</div>
    <div class="sim-keypad">${keys.map((k) => `<button type="button" data-key="${k}" aria-label="${k === "⌫" ? "Delete" : k === "OK" ? "Submit code" : k}">${k}</button>`).join("")}</div>`;
}

function messageHtml() {
  if (!message) return "";
  return `<div class="sim-msg ${message.ok ? "ok" : "bad"}" role="status">${esc(message.text)}</div>`;
}

function render() {
  document.getElementById("sim-clock").textContent = phoneNow().toLocaleTimeString("en-KE", { hour: "2-digit", minute: "2-digit" });
  document.getElementById("sim-date").textContent = phoneNow().toLocaleDateString("en-KE", { weekday: "long", day: "numeric", month: "long" });
  const state = lockState();
  const s = screen();
  if (state === "UNPROVISIONED") {
    s.className = "sim-screen sim-setup";
    s.innerHTML = `<div class="sim-center"><div class="sim-icon" aria-hidden="true">⚙</div><h3>Not provisioned</h3>
      <p>${deviceCode ? `No lock key for <span class="mono">${esc(deviceCode)}</span> on this phone.` : "No device selected."}</p>
      <p class="small">A technician loads the key from the staff console: <strong>Devices → open a financed device → Load key into phone simulator</strong>.</p></div>`;
  } else if (state === "LOCKED") {
    s.className = "sim-screen sim-locked";
    s.innerHTML = `<div class="sim-lockhead"><div class="sim-icon" aria-hidden="true">🔒</div><h3>Phone locked</h3>
      <p>Payment for <span class="mono">${esc(agent.contractNumber)}</span> is overdue${agent.unlockedUntil ? ` (unlocked until ${esc(pretty(agent.unlockedUntil))})` : ""}.</p>
      <p class="small">Pay with M-PESA, then enter the unlock code you receive by SMS.</p></div>
      ${messageHtml()}${keypadHtml()}
      <div class="sim-emergency">Emergency calls (112 / 999) are always available</div>`;
  } else {
    s.className = `sim-screen sim-home${state === "OWNED" ? " sim-owned" : ""}`;
    const banner = state === "OWNED"
      ? `<div class="sim-banner ok">You own this phone. Paid in full.</div>`
      : state === "GRACE"
        ? `<div class="sim-banner warn">Payment overdue. The phone locks after ${esc(pretty(agent.unlockedUntil))}.</div>`
        : `<div class="sim-banner">Unlocked until ${esc(pretty(agent.unlockedUntil))}</div>`;
    s.innerHTML = `${banner}
      <div class="sim-apps">${["M-PESA", "Phone", "Messages", "Camera", "WhatsApp", "Maps", "Music", "Settings"].map((a) => `<div class="sim-app"><span aria-hidden="true">${a[0]}</span>${esc(a)}</div>`).join("")}</div>
      ${state === "OWNED" ? "" : `<details class="sim-codebox"${message ? " open" : ""}><summary>Enter unlock code</summary>${messageHtml()}${keypadHtml()}</details>`}`;
  }
  bindKeypad();
  renderInfo(state);
}

function bindKeypad() {
  screen().querySelectorAll("[data-key]").forEach((btn) => btn.addEventListener("click", async () => {
    const k = btn.dataset.key;
    if (k === "⌫") entry = entry.slice(0, -1);
    else if (k === "OK") {
      if (entry.length !== 12) { message = { ok: false, text: "Codes are 12 digits." }; render(); return; }
      message = await applyCode(entry);
      entry = "";
    } else if (entry.length < 12) entry += k;
    render();
  }));
}

function renderInfo(state) {
  const el = document.getElementById("sim-info");
  if (!agent) {
    el.innerHTML = `<h3>Lock agent</h3><p class="small">Empty: no key loaded.</p>`;
    return;
  }
  const labels = { UNLOCKED: "chip-green", GRACE: "chip-amber", LOCKED: "chip-red", OWNED: "chip-green" };
  el.innerHTML = `<h3>Lock agent</h3>
    <dl class="kv"><dt>Device</dt><dd class="mono">${esc(agent.deviceCode)}</dd></dl>
    <dl class="kv"><dt>Phone</dt><dd>${esc(agent.productName || "")}</dd></dl>
    <dl class="kv"><dt>Contract</dt><dd class="mono">${esc(agent.contractNumber)}</dd></dl>
    <dl class="kv"><dt>State</dt><dd><span class="chip ${labels[state] || "chip-grey"}">${esc(state)}</span></dd></dl>
    <dl class="kv"><dt>Unlocked until</dt><dd>${agent.permanent ? "Forever" : agent.unlockedUntil ? esc(pretty(agent.unlockedUntil)) : "—"}</dd></dl>
    <dl class="kv"><dt>Last code #</dt><dd>${esc(String(agent.lastSequence || 0))}</dd></dl>
    <dl class="kv"><dt>Grace days</dt><dd>${esc(String(agent.graceDays ?? 0))}</dd></dl>
    ${agent.clockOffsetDays ? `<p class="small">Phone clock is ${esc(String(agent.clockOffsetDays))} day(s) ahead.</p>` : ""}`;
}

function shiftClock(days) {
  if (!agent) return;
  agent.clockOffsetDays = days === 0 ? 0 : (agent.clockOffsetDays || 0) + days;
  message = null;
  save();
  render();
}

document.getElementById("sim-plus1").addEventListener("click", () => shiftClock(1));
document.getElementById("sim-plus3").addEventListener("click", () => shiftClock(3));
document.getElementById("sim-reset-clock").addEventListener("click", () => shiftClock(0));
document.getElementById("sim-wipe").addEventListener("click", () => {
  try { localStorage.removeItem(STORE_KEY); } catch { /* ignore */ }
  agent = null;
  message = null;
  render();
});
// Another tab (the staff console) may load a key or the customer may pay.
window.addEventListener("storage", (e) => { if (e.key === STORE_KEY) { agent = load(); render(); } });
setInterval(() => { document.getElementById("sim-clock").textContent = phoneNow().toLocaleTimeString("en-KE", { hour: "2-digit", minute: "2-digit" }); }, 30000);

// A technician who just loaded the key also enters the current code, so the
// phone starts in the right state.
(async () => {
  if (agent?.bootCode) {
    const code = agent.bootCode;
    delete agent.bootCode;
    await applyCode(code);
    message = null;
  }
  render();
})();
