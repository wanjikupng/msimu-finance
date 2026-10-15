// Agent POS terminal app (cashless). One screen at a time, driven by the
// terminal keypad (or the keyboard). Every collection is an M-PESA payment
// request (STK push) to the customer's phone; nothing is ever taken in cash.
//
// The side panel plays the customer's phone receiving the M-PESA prompt. In
// production that prompt appears on the customer's real handset and the
// answer comes back on the M-PESA callback; here it is simulated.
import { api, session } from "./api.js";
import { esc, money, moneyExact, fmtDate, fmtDateTime } from "./ui.js";

const APP_VERSION = "msimu-pos 1.1.0";
const POLL_MS = 2000;
const params = new URLSearchParams(location.search);
let tid = (params.get("tid") || "").toUpperCase();

// Terminal state
const S = {
  screen: "boot",
  info: null,          // session: merchant, agent, ...
  input: "",
  error: "",
  customer: null,      // lookup result
  contract: null,      // selected contract
  amount: "",
  payer: "",           // optional other M-PESA number
  pending: null,       // transaction waiting for the customer
  result: null,        // final transaction
  lastRef: null,
  report: null,
  busy: false,
};
let pollTimer = null;
let heartbeatTimer = null;

const $ = (id) => document.getElementById(id);
const screenEl = () => $("pos-screen");

function call(path, opts = {}) {
  return api(`/pos/${encodeURIComponent(tid)}${path}`, { ...opts, redirectOn401: false, headers: { "X-Terminal-App-Version": APP_VERSION, ...(opts.headers || {}) } });
}

function go(screen, patch = {}) {
  Object.assign(S, { screen, error: "", input: "" }, patch);
  render();
}

// ─── screens ──────────────────────────────────────────────────────────────────
function header(title) {
  return `<div class="pos-head"><span>${esc(title)}</span><span>${esc(new Date().toLocaleTimeString("en-KE", { hour: "2-digit", minute: "2-digit" }))}</span></div>`;
}
function footer(left, right) {
  return `<div class="pos-foot"><span>${esc(left || "")}</span><span>${esc(right || "")}</span></div>`;
}
function errorLine() {
  return S.error ? `<div class="pos-error">${esc(S.error)}</div>` : "";
}

function render() {
  $("pos-tid").textContent = tid || "TRM-····";
  const el = screenEl();
  const r = {
    tid: () => `${header("SET UP")}<div class="pos-body"><p>Enter this terminal's ID</p><div class="pos-field">TRM-${esc(S.input)}<span class="pos-caret"></span></div><p class="pos-hint">e.g. 0001, then OK</p>${errorLine()}</div>${footer("", "OK")}`,
    login: () => `${header("AGENT SIGN IN")}<div class="pos-body"><form id="pos-login" class="pos-form"><label>Email<input id="pos-email" type="email" autocomplete="username" required /></label><label>Password<input id="pos-pass" type="password" autocomplete="current-password" required /></label><button class="btn btn-primary btn-sm" type="submit">Sign in</button></form>${errorLine()}</div>${footer(tid, "")}`,
    menu: () => `${header(S.info?.merchantName || "MENU")}<div class="pos-body"><p class="pos-hint">${esc(S.info?.agentName || "")} · ${esc(S.info?.agentCode || "")}</p><ol class="pos-menu"><li><b>1</b> Collect payment (M-PESA)</li><li><b>2</b> Reprint last receipt</li><li><b>3</b> End-of-day report</li><li><b>9</b> Sign out</li></ol>${errorLine()}</div>${footer("Select 1-9", "")}`,
    lookup: () => `${header("FIND CUSTOMER")}<div class="pos-body"><p>Customer phone number or contract number</p><div class="pos-field">${esc(S.input)}<span class="pos-caret"></span></div><p class="pos-hint">Phone: 07XX XXX XXX. Contract: type the digits, e.g. 3 for LN-…-000003</p>${errorLine()}</div>${footer("✕ Back", "OK Search")}`,
    contracts: () => `${header("SELECT CONTRACT")}<div class="pos-body"><p><b>${esc(S.customer.displayName)}</b> · ${esc(S.customer.maskedPhone || "")}</p>${S.customer.contracts.length ? `<ol class="pos-menu">${S.customer.contracts.map((c, i) => `<li><b>${i + 1}</b> ${esc(c.productName)} <span class="pos-hint">${esc(c.contractNumber)}</span><br><span class="pos-hint">Due now ${esc(money(c.amountDueNow))} · ${esc(c.status)}${c.lockState === "LOCKED" ? " · PHONE LOCKED" : ""}</span></li>`).join("")}</ol>` : `<p>No active contracts.</p>`}${errorLine()}</div>${footer("✕ Back", "Select")}`,
    amount: () => {
      const c = S.contract;
      return `${header("AMOUNT")}<div class="pos-body"><p>${esc(c.productName)} · ${esc(c.contractNumber)}</p><p class="pos-hint">Due now ${esc(money(c.amountDueNow))} · Daily ${esc(money(c.dailyAmount))} · Balance ${esc(money(c.amountRemaining))}</p><div class="pos-field pos-big">KES ${esc(S.input)}<span class="pos-caret"></span></div>${errorLine()}</div>${footer("✕ Back", "OK Next")}`;
    },
    payer: () => `${header("M-PESA NUMBER")}<div class="pos-body"><p>Send the request to the customer's registered M-PESA number <b>${esc(S.customer.maskedPhone || "—")}</b>?</p><p class="pos-hint">OK = yes. Or type another Safaricom number (someone paying for them).</p><div class="pos-field">${esc(S.input)}<span class="pos-caret"></span></div>${errorLine()}</div>${footer("✕ Back", "OK")}`,
    confirm: () => `${header("CONFIRM")}<div class="pos-body"><p>Send M-PESA request</p><div class="pos-big">${esc(money(S.amount))}</div><p>to ${esc(S.payer ? maskLocal(S.payer) : S.customer.maskedPhone || "")}</p><p class="pos-hint">for ${esc(S.contract.contractNumber)} · ${esc(S.contract.productName)}</p>${errorLine()}</div>${footer("✕ Cancel", "OK Send")}`,
    waiting: () => `${header("WAITING")}<div class="pos-body pos-center"><div class="pos-spinner" aria-hidden="true"></div><p><b>Request sent</b></p><p>Ask the customer to check their phone and enter their <b>M-PESA PIN</b>.</p><p class="pos-hint">${esc(S.pending?.transactionReference || "")}</p>${errorLine()}</div>${footer("", "Waiting…")}`,
    result: () => {
      const t = S.result;
      const ok = t.status === "POSTED";
      return `${header(ok ? "APPROVED" : "NOT PAID")}<div class="pos-body pos-center"><div class="pos-stamp ${ok ? "ok" : "bad"}">${ok ? "✓" : "✕"}</div><p><b>${esc(money(t.amount))}</b> ${ok ? "received via M-PESA" : ""}</p>${ok ? `<p class="pos-hint">M-PESA ref ${esc(t.providerReference || "")}<br>Receipt ${esc(t.receiptNumber || "")}</p><p>Receipt printed.</p>` : `<p>${esc(t.failureReason || "The customer did not approve the request.")}</p>`}</div>${footer("", "OK Menu")}`;
    },
    report: () => {
      const r = S.report;
      return `${header("END OF DAY")}<div class="pos-body"><p class="pos-hint">${esc(fmtDate(r.businessDate))} · ${esc(r.terminalCode)}</p><table class="pos-table"><tr><td>Approved</td><td>${esc(String(r.approvedCount))}</td><td>${esc(money(r.approvedAmount))}</td></tr><tr><td>Declined</td><td>${esc(String(r.declinedCount))}</td><td></td></tr><tr><td>Waiting</td><td>${esc(String(r.pendingCount))}</td><td></td></tr><tr><td>My commission</td><td></td><td>${esc(moneyExact(r.commissionEarnedToday))}</td></tr></table><p class="pos-hint">All collections are already in M-PESA: no cash to count or hand over. Report printed.</p></div>${footer("", "OK Menu")}`;
    },
    boot: () => `${header("M-SIMU POS")}<div class="pos-body pos-center"><div class="pos-spinner"></div><p>Starting…</p></div>${footer("", "")}`,
  }[S.screen];
  el.innerHTML = r ? r() : "";
  if (S.screen === "login") wireLogin();
  renderCustomerPhone();
}

function maskLocal(msisdn) {
  const d = String(msisdn).replace(/\D/g, "");
  return d.length < 7 ? d : `${d.slice(0, 4)}${"*".repeat(d.length - 7)}${d.slice(-3)}`;
}

// ─── simulated customer phone ─────────────────────────────────────────────────
let pin = "";
function renderCustomerPhone() {
  const card = $("customer-phone-card");
  if (!card) return;
  if (S.screen !== "waiting" || !S.pending) {
    card.innerHTML = `<h3>Customer's phone (simulated)</h3><p class="small">When the agent sends a request, the M-PESA prompt appears here, as it would on the customer's own phone.</p>`;
    return;
  }
  const t = S.pending;
  card.innerHTML = `<h3>Customer's phone (simulated)</h3>
    <div class="stk-phone">
      <div class="stk-dialog" role="dialog" aria-label="M-PESA payment prompt">
        <p class="stk-title">M-PESA</p>
        <p>Do you want to pay <b>${esc(money(t.amount))}</b> to <b>M-SIMU FINANCE</b> for account <b>${esc(t.contractNumber)}</b>?</p>
        <label class="stk-pin">Enter M-PESA PIN<input id="stk-pin" type="password" inputmode="numeric" maxlength="4" value="${esc(pin)}" autocomplete="off" /></label>
        <div class="btn-row"><button class="btn btn-secondary btn-sm" type="button" id="stk-cancel">Cancel</button><button class="btn btn-primary btn-sm" type="button" id="stk-ok">Send</button></div>
      </div>
    </div>
    <p class="small">Any 4 digits. In real life the PIN is checked by Safaricom on the customer's phone; it never reaches the agent or this system.</p>`;
  $("stk-pin").addEventListener("input", (e) => { pin = e.target.value.replace(/\D/g, "").slice(0, 4); e.target.value = pin; });
  $("stk-ok").addEventListener("click", () => {
    if (pin.length !== 4) { $("stk-pin").focus(); return; }
    customerAnswers(true);
  });
  $("stk-cancel").addEventListener("click", () => customerAnswers(false));
}

async function customerAnswers(approved) {
  if (!S.pending) return;
  const ref = S.pending.transactionReference;
  pin = "";
  try {
    await call(`/collections/${encodeURIComponent(ref)}/customer-response`, { method: "POST", body: { approved } });
  } catch (e) {
    S.error = e.message;
  }
  // The terminal learns the outcome the same way it would in production: by polling.
  await pollOnce();
}

// ─── actions ─────────────────────────────────────────────────────────────────
async function startSession() {
  if (!tid) { go("tid"); return; }
  if (!session.valid() || session.load()?.role !== "AGENT") { go("login"); return; }
  try {
    S.info = await call("/session");
    go("menu");
    clearInterval(heartbeatTimer);
    heartbeatTimer = setInterval(() => call("/heartbeat", { method: "POST" }).catch(() => {}), 60000);
  } catch (e) {
    if (e.status === 401) { session.clear(); go("login", { error: "Session expired. Sign in again." }); return; }
    session.clear();
    go("login", { error: e.message || "This terminal cannot be used." });
  }
}

function wireLogin() {
  $("pos-login")?.addEventListener("submit", async (ev) => {
    ev.preventDefault();
    try {
      const auth = await api("/auth/login", { method: "POST", body: { email: $("pos-email").value.trim(), password: $("pos-pass").value }, redirectOn401: false });
      if (auth.role !== "AGENT") { go("login", { error: "Only agents can sign in on a terminal." }); return; }
      session.save(auth);
      await startSession();
    } catch (e) {
      go("login", { error: e.status === 401 ? "Wrong email or password." : e.message });
    }
  });
  setTimeout(() => $("pos-email")?.focus(), 0);
}

async function lookup() {
  const q = S.input.trim();
  const query = /^\d{1,6}$/.test(q) ? `LN-${new Date().getFullYear()}-${q.padStart(6, "0")}` : q;
  try {
    S.customer = await call(`/customers?query=${encodeURIComponent(query)}`);
    go("contracts");
  } catch (e) { S.error = e.message || "Not found."; render(); }
}

async function send() {
  if (S.busy) return;
  S.busy = true;
  try {
    const body = { contractId: S.contract.contractId, amount: Number(S.amount) };
    if (S.payer) body.payerMsisdn = S.payer;
    const tx = await call("/collections", { method: "POST", body, headers: { "Idempotency-Key": crypto.randomUUID() } });
    S.lastRef = tx.transactionReference;
    go("waiting", { pending: tx });
    clearInterval(pollTimer);
    pollTimer = setInterval(pollOnce, POLL_MS);
  } catch (e) {
    S.error = e.message || "Request failed.";
    render();
  } finally {
    S.busy = false;
  }
}

async function pollOnce() {
  if (!S.pending) return;
  try {
    const tx = await call(`/collections/${encodeURIComponent(S.pending.transactionReference)}`);
    if (tx.status !== "PENDING") {
      clearInterval(pollTimer);
      S.pending = null;
      go("result", { result: tx });
      if (tx.status === "POSTED") printReceipt(tx.transactionReference);
    }
  } catch { /* keep waiting */ }
}

async function printReceipt(ref) {
  const slot = $("slip-slot");
  try {
    const r = await call(`/collections/${encodeURIComponent(ref)}/receipt`);
    slot.innerHTML = `<div class="pos-slip" aria-label="Printed receipt">
      <div class="slip-c"><b>${esc(r.merchantName)}</b><br>M-SIMU FINANCE · AGENT</div>
      <div class="slip-rule"></div>
      <div class="slip-row"><span>TERMINAL</span><span>${esc(r.terminalCode)}</span></div>
      <div class="slip-row"><span>AGENT</span><span>${esc(r.agentName || "")}</span></div>
      <div class="slip-row"><span>DATE</span><span>${esc(fmtDateTime(r.completedAt || r.requestedAt))}</span></div>
      <div class="slip-rule"></div>
      <div class="slip-row"><span>CUSTOMER</span><span>${esc(r.customerName)}</span></div>
      <div class="slip-row"><span>M-PESA NO.</span><span>${esc(r.payerMsisdn || "")}</span></div>
      <div class="slip-row"><span>CONTRACT</span><span>${esc(r.contractNumber)}</span></div>
      <div class="slip-row"><span>PHONE</span><span>${esc(r.productName)}</span></div>
      <div class="slip-rule"></div>
      <div class="slip-row slip-big"><span>PAID (M-PESA)</span><span>${esc(money(r.amount))}</span></div>
      <div class="slip-row"><span>M-PESA REF</span><span>${esc(r.mpesaReference || "")}</span></div>
      <div class="slip-row"><span>RECEIPT</span><span>${esc(r.receiptNumber || "")}</span></div>
      <div class="slip-row"><span>BALANCE</span><span>${esc(money(r.balanceAfter))}</span></div>
      <div class="slip-row"><span>NEXT DUE</span><span>${esc(fmtDate(r.nextPaymentDueDate))}</span></div>
      ${r.unlockCode ? `<div class="slip-rule"></div><div class="slip-c">UNLOCK CODE${r.unlockPermanent ? " (PAID IN FULL)" : ""}<br><span class="slip-code">${esc(r.unlockCode)}</span><br>${r.unlockPermanent ? "Phone unlocked for good" : `Valid until ${esc(fmtDate(r.unlockedUntil))}`}</div>` : ""}
      <div class="slip-rule"></div>
      <div class="slip-c">No cash was taken. Paid by M-PESA.<br>Thank you</div>
    </div>`;
  } catch (e) {
    slot.innerHTML = `<div class="form-error">${esc(e.message || "Could not print receipt.")}</div>`;
  }
}

async function showReport() {
  try {
    S.report = await call("/day-summary");
    go("report");
    const slot = $("slip-slot");
    slot.innerHTML = `<div class="pos-slip"><div class="slip-c"><b>${esc(S.report.merchantName)}</b><br>END OF DAY · ${esc(S.report.terminalCode)}</div><div class="slip-rule"></div>
      ${S.report.transactions.map((t) => `<div class="slip-row"><span>${esc(new Date(t.requestedAt).toLocaleTimeString("en-KE", { hour: "2-digit", minute: "2-digit" }))} ${esc(t.contractNumber)}</span><span>${esc(money(t.amount))} ${t.status === "POSTED" ? "OK" : esc(t.status)}</span></div>`).join("") || `<div class="slip-c">No collections today</div>`}
      <div class="slip-rule"></div><div class="slip-row slip-big"><span>APPROVED (${esc(String(S.report.approvedCount))})</span><span>${esc(money(S.report.approvedAmount))}</span></div>
      <div class="slip-row"><span>COMMISSION TODAY</span><span>${esc(moneyExact(S.report.commissionEarnedToday))}</span></div>
      <div class="slip-c">Settled to M-PESA. No cash held.</div></div>`;
  } catch (e) { S.error = e.message; render(); }
}

// ─── keypad ──────────────────────────────────────────────────────────────────
async function key(k) {
  const digit = /^\d$/.test(k);
  switch (S.screen) {
    case "tid":
      if (digit && S.input.length < 4) S.input += k;
      else if (k === "clear") S.input = S.input.slice(0, -1);
      else if (k === "enter" && S.input) { tid = `TRM-${S.input.padStart(4, "0")}`; history.replaceState(null, "", `?tid=${tid}`); return startSession(); }
      break;
    case "menu":
      if (k === "1") return go("lookup");
      if (k === "2") { if (S.lastRef) { await printReceipt(S.lastRef); S.error = "Receipt reprinted."; } else S.error = "No receipt to reprint yet."; break; }
      if (k === "3") return showReport();
      if (k === "9") { session.clear(); clearInterval(heartbeatTimer); return go("login"); }
      break;
    case "lookup":
      if (digit && S.input.length < 15) S.input += k;
      else if (k === "clear") S.input = S.input.slice(0, -1);
      else if (k === "cancel") return go("menu");
      else if (k === "enter" && S.input) return lookup();
      break;
    case "contracts":
      if (k === "cancel") return go("lookup");
      if (digit) {
        const c = S.customer.contracts[Number(k) - 1];
        if (c) return go("amount", { contract: c, input: String(Math.round(Number(c.amountDueNow) || Number(c.dailyAmount))) });
      }
      break;
    case "amount":
      if (digit && S.input.length < 7) S.input = S.input === "0" ? k : S.input + k;
      else if (k === "clear") S.input = S.input.slice(0, -1);
      else if (k === "cancel") return go("contracts");
      else if (k === "enter") {
        const n = Number(S.input);
        if (!n || n < 1) { S.error = "Enter an amount."; break; }
        if (n > Number(S.contract.amountRemaining)) { S.error = `Max ${money(S.contract.amountRemaining)}.`; break; }
        return go("payer", { amount: String(n), payer: "" });
      }
      break;
    case "payer":
      if (digit && S.input.length < 12) S.input += k;
      else if (k === "clear") S.input = S.input.slice(0, -1);
      else if (k === "cancel") return go("amount", { input: S.amount });
      else if (k === "enter") {
        if (S.input && !/^(0[17]\d{8}|254[17]\d{8})$/.test(S.input)) { S.error = "Enter a valid number, e.g. 0712345678."; break; }
        return go("confirm", { payer: S.input });
      }
      break;
    case "confirm":
      if (k === "cancel") return go("payer");
      if (k === "enter") return send();
      break;
    case "result":
    case "report":
      if (k === "enter" || k === "cancel") return go("menu");
      break;
    default:
  }
  render();
}

$("pos-keypad").addEventListener("click", (e) => {
  const b = e.target.closest("[data-k]");
  if (b) key(b.dataset.k);
});
document.addEventListener("keydown", (e) => {
  if (e.target.closest("input, textarea")) return;
  const map = { Enter: "enter", Escape: "cancel", Backspace: "clear" };
  const k = map[e.key] || (/^\d$/.test(e.key) ? e.key : null);
  if (k) { e.preventDefault(); key(k); }
});
setInterval(() => { const h = document.querySelector(".pos-head span:last-child"); if (h) h.textContent = new Date().toLocaleTimeString("en-KE", { hour: "2-digit", minute: "2-digit" }); }, 30000);

render();
startSession();
