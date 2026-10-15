// Customer portal: shell, home, phones, pay, activity, account.
// No /admin calls are made anywhere; every server string goes through esc().
import { api, requireRole, signOut } from "./api.js";
import { fmtDate, fmtDateTime, esc, money, skeletonRows, emptyState, statusChip, toast, debounce, confirmDialog, openModal, dueLabel } from "./ui.js";
import { ownershipPct, renderPhone, phoneStateFor } from "./phone.js";

// ─── guards ───────────────────────────────────────────────────────────────────
const me = requireRole(["CUSTOMER"]);
if (me) {
  document.getElementById("user-name").textContent = me.fullName || me.customerCode || "Signed in";
  document.getElementById("sign-out").addEventListener("click", signOut);
}

// ─── router ───────────────────────────────────────────────────────────────────
const routes = {
  "#/home": renderHome,
  "#/phones": renderPhones,
  "#/pay": renderPay,
  "#/activity": renderActivity,
  "#/account": renderAccount,
};
const NAV = [
  { hash: "#/home", label: "Home" },
  { hash: "#/phones", label: "Phones" },
  { hash: "#/pay", label: "Pay" },
  { hash: "#/activity", label: "Activity" },
  { hash: "#/account", label: "Account" },
];
function renderNav() {
  const current = location.hash || "#/home";
  for (const id of ["tabbar", "rail"]) {
    const el = document.getElementById(id);
    if (!el) continue;
    el.innerHTML = NAV.map((n) => `<a href="${n.hash}" aria-current="${n.hash === current ? "page" : "false"}">${esc(n.label)}</a>`).join("");
  }
}
function route() {
  if (!me) return;
  (routes[location.hash || "#/home"] || routes["#/home"])();
  renderNav();
}
window.addEventListener("hashchange", route);

// ─── shared helpers ───────────────────────────────────────────────────────────
const page = () => document.getElementById("page");

function daysLeft(c) {
  const r = Number(c.amountRemaining ?? 0), d = Number(c.dailyAmount ?? 1);
  return Math.max(0, Math.ceil(r / Math.max(1, d)));
}
function finishDate(c) { const d = new Date(); d.setDate(d.getDate() + daysLeft(c)); return d; }
function paymentStreak(t = []) {
  const days = new Set();
  for (const e of t) { if (e.status !== "POSTED" || !e.date) continue; const d = new Date(e.date); if (Number.isNaN(d.getTime())) continue; days.add(new Date(d.getFullYear(), d.getMonth(), d.getDate()).toDateString()); }
  // Consecutive paying days ending today — or yesterday, since today's
  // payment may simply not have been made yet.
  const now = new Date();
  let c = new Date(now.getFullYear(), now.getMonth(), now.getDate());
  if (!days.has(c.toDateString())) c.setDate(c.getDate() - 1);
  let s = 0;
  while (days.has(c.toDateString())) { s++; c.setDate(c.getDate() - 1); }
  return s;
}
function contractSelectHtml(contracts, activeId) {
  if (contracts.length <= 1) return "";
  return `<div class="field"><label for="home-contract">Switch contract</label><select id="home-contract">${contracts.map((c) => `<option value="${esc(String(c.id))}" ${c.id === activeId ? "selected" : ""}>${esc(c.productName)} (${esc(c.contractNumber || "")})</option>`).join("")}</select></div>`;
}
function timelineHtml(tl) {
  if (!tl.length) return "";
  return `<div class="card mt"><h3>Recent activity</h3><ul class="timeline">${tl.slice(0, 5).map((e) => `<li><div class="t-main"><div>${esc(fmtDate(e.date))} · ${esc(e.channel ?? "—")} · ${statusChip(e.status)}</div><div class="t-sub">${esc(e.transactionReference || "")}${e.failureReason ? ` — ${esc(e.failureReason)}` : ""}</div></div><div class="t-amt">${money(e.amount)}${e.balanceAfter != null ? ` → ${money(e.balanceAfter)}` : ""}</div></li>`).join("")}</ul></div>`;
}

// ─── HOME ─────────────────────────────────────────────────────────────────────
let homeContractId = null;

// Phone lock card: state in words, the latest unlock code, and a link to the
// phone simulator. The code is what the customer would also receive by SMS.
function lockCardHtml(lock) {
  if (!lock) return "";
  const until = lock.unlockedUntil ? fmtDate(lock.unlockedUntil) : "";
  const text = {
    UNLOCKED: `Your phone is unlocked until <strong>${esc(until)}</strong>. Each payment extends it.`,
    GRACE: `Your payment is late. Your phone locks after <strong>${esc(until)}</strong>. Pay now to keep it unlocked.`,
    LOCKED: "Your phone is locked because payments are behind. Pay to catch up and you'll get a new unlock code straight away.",
    OWNED: "Paid in full. Your phone is unlocked for good.",
  }[lock.lockState] || "";
  const code = lock.latestCode;
  return `<div class="card mt lock-card">
    <div class="head-row"><h3>Phone lock</h3>${statusChip(lock.lockState)}</div>
    <p class="small">${text}</p>
    ${code ? `<p class="small" style="margin-bottom:0.3rem">Your latest unlock code${code.permanent ? " (permanent)" : ""}:</p>
      <div class="btn-row" style="align-items:center"><span class="unlock-code" id="unlock-code">${esc(code.code)}</span><button class="btn btn-ghost btn-sm" type="button" id="copy-code">Copy</button></div>
      <p class="small">Code #${esc(String(code.sequence))}, also sent by SMS. Enter it on your phone if it is locked; codes work without internet.</p>` : ""}
    <div class="btn-row"><a class="btn btn-secondary btn-sm" href="device.html?device=${encodeURIComponent(lock.deviceCode)}" target="_blank" rel="noopener">Open my phone (simulator)</a>${lock.lockState === "LOCKED" || lock.lockState === "GRACE" ? `<a class="btn btn-primary btn-sm" href="#/pay">Pay now</a>` : ""}</div>
  </div>`;
}

async function renderHome() {
  page().innerHTML = `<section class="wrap page-head"><h2>Home</h2><div class="skeleton" style="height:260px;border-radius:18px"></div></section>`;
  try {
    const [dashboard, apps] = await Promise.all([api("/users/me/dashboard"), api("/financing/applications/me")]);
    const contracts = dashboard.deviceLoans || [];
    const tl = dashboard.paymentTimeline || [];
    const active = contracts.find((c) => c.id === homeContractId)
      || contracts.find((c) => c.loanStatus === "ACTIVE" || c.loanStatus === "OVERDUE") || contracts[0] || null;
    const lock = active ? await api(`/loans/${active.id}/device-lock`).catch(() => null) : null;
    const lockVisual = lock ? { LOCKED: "overdue", GRACE: "due", OWNED: "owned" }[lock.lockState] : null;
    const pendingApp = Array.isArray(apps) ? apps.find((a) => a.status === "SUBMITTED" || a.status === "UNDER_REVIEW") : null;
    const due = active ? dueLabel(active.nextPaymentDueDate) : "";
    let html = `<section class="wrap page-head"><div class="grid-2 uneven">`;
    if (!active) {
      html += `<div><h1>Track your phone.</h1><p>${esc(pendingApp ? `Application ${pendingApp.applicationNumber ?? ""} is ${pendingApp.status ?? ""}.` : "Choose your first phone to get started.")}</p><div class="btn-row"><a class="btn btn-primary" href="#/phones">${esc(pendingApp ? "Check status" : "Choose a phone")}</a></div></div><div class="card empty"><p class="small">No active contract yet.</p></div>`;
    } else {
      const pct = ownershipPct(active), finish = finishDate(active), state = lockVisual || phoneStateFor(active, due.includes("late") ? -1 : 0), streak = paymentStreak(tl);
      html += `<div><h1>${state === "owned" ? "You own it." : "Track your phone."}</h1><div class="btn-row"><a class="btn btn-primary" href="#/pay">${money(active.dailyAmount)} due</a><a class="btn btn-secondary btn-sm" href="#/activity">See payments</a></div><p class="small" style="margin-top:0.75rem">${esc(active.productName || "Phone")} · <span class="mono">${esc(active.contractNumber || "")}</span> · ${statusChip(active.loanStatus)}</p><div class="stat-grid"><div class="stat"><span>Ownership</span><strong>${Math.round(pct)}%</strong></div><div class="stat"><span>Remaining</span><strong>${money(active.amountRemaining)}</strong></div><div class="stat"><span>Next due</span><strong>${esc(due)}</strong></div><div class="stat"><span>Own it by</span><strong>${esc(fmtDate(finish))}</strong><em>~${esc(String(daysLeft(active)))} days</em></div><div class="stat"><span>Paid so far</span><strong>${money(active.amountPaid)}</strong></div><div class="stat"><span>Streak</span><strong>${esc(String(streak))} day${streak === 1 ? "" : "s"}</strong></div></div>${lockCardHtml(lock)}</div>`;
      html += `<div class="card"><div class="phone-stage"><div id="home-phone" class="phone-figure"></div><div><div class="bignum">${Math.round(pct)}% paid</div><p class="small">${money(active.dailyAmount)}/day · ${esc(active.planName || "")}</p></div></div><div class="mt">${contractSelectHtml(contracts, active.id)}</div><div class="card mt" style="background:var(--paper)"><h3 class="bignum" style="font-size:1.1rem">Payoff planner</h3><p class="small">What if you pay a little more each day?</p><div class="grid-2" style="gap:0.6rem">${[10, 20, 50].map((x) => { const nd = daysLeft({ amountRemaining: active.amountRemaining, dailyAmount: active.dailyAmount + x }); return `<div class="card"><div class="kv"><dt>+KES ${x}/day</dt><dd>${esc(String(nd))} days</dd></div><div class="small">Save ${esc(String(Math.max(0, daysLeft(active) - nd)))} days</div></div>`; }).join("")}</div></div>${timelineHtml(tl)}</div>`;
    }
    html += `</div></section>`;
    page().innerHTML = html;
    const phoneEl = document.getElementById("home-phone");
    if (phoneEl && active) renderPhone(phoneEl, { pct: ownershipPct(active), state: lockVisual || phoneStateFor(active, due.includes("late") ? -1 : 0), label: "Your phone ownership" });
    const sel = document.getElementById("home-contract");
    if (sel) sel.addEventListener("change", (e) => { homeContractId = Number(e.target.value); renderHome(); });
    document.getElementById("copy-code")?.addEventListener("click", async () => {
      try { await navigator.clipboard.writeText(document.getElementById("unlock-code").textContent.replace(/\s/g, "")); toast("Code copied.", "success"); }
      catch { toast("Copy not available. Please type the code.", "error"); }
    });
  } catch (err) {
    page().innerHTML = `<section class="wrap page-head"><div class="form-error">${esc(err.message || "Unable to load dashboard.")}</div></section>`;
  }
}

// ─── PHONES (stepped) ────────────────────────────────────────────────────────
let phonesData = { step: 1, products: [], devices: [], plans: [], modes: [], selectedDevice: null, selectedPlan: null, deposit: 0, quote: null };

async function loadPhonesData() {
  const [devices, products, plans, modes] = await Promise.all([
    api("/financing/eligible-devices"), api("/products"),
    api("/financing/payment-plans"), api("/financing/payment-modes"),
  ]);
  const productMap = new Map(products.map((p) => [p.id, p]));
  // group devices by product, count stock
  const grouped = [];
  const byProduct = new Map();
  for (const d of devices) {
    const pid = d.productId;
    if (!byProduct.has(pid)) byProduct.set(pid, { product: productMap.get(pid) || { id: pid, name: d.productName, fullPrice: 0, downPayment: 0, dailyAmount: 0 }, devices: [] });
    byProduct.get(pid).devices.push(d);
  }
  for (const [, g] of byProduct) grouped.push(g);
  phonesData.products = grouped;
  phonesData.plans = plans;
  phonesData.modes = modes;
}

async function renderPhones() {
  page().innerHTML = `<section class="wrap page-head"><h2>Choose a phone</h2><div class="skeleton" style="height:300px;border-radius:18px"></div></section>`;
  try {
    await loadPhonesData();
    phonesData.step = 1;
    renderPhoneStep();
  } catch (err) {
    page().innerHTML = `<section class="wrap page-head"><div class="form-error">${esc(err.message || "Could not load phones.")}</div></section>`;
  }
}

function renderPhoneStep() {
  const s = phonesData.step;
  const steps = [
    { n: 1, label: "Choose" },
    { n: 2, label: "Plan" },
    { n: 3, label: "Review" },
    { n: 4, label: "Sign" },
  ];
  let html = `<section class="wrap page-head">
    <h2>Choose a phone</h2>
    <div class="btn-row" style="margin-bottom:1rem">${steps.map((st) => `<span class="chip ${st.n <= s ? "chip-green" : "chip-grey"}">${st.n}. ${esc(st.label)}</span>`).join("")}</div>
    <div id="phone-step"></div>
  </section>`;
  page().innerHTML = html;
  const slot = document.getElementById("phone-step");
  if (s === 1) renderPhoneCatalog(slot);
  else if (s === 2) renderPhonePlan(slot);
  else if (s === 3) renderPhoneReview(slot);
  else if (s === 4) renderPhoneSign(slot);
}

function renderPhoneCatalog(slot) {
  if (!phonesData.products.length) { slot.innerHTML = emptyState("No phones available right now", "Check back later.", "Go home", "#/home"); return; }
  slot.innerHTML = `<div class="grid-2">${phonesData.products.map((g) => {
    const p = g.product, c = g.devices.length;
    return `<div class="card" style="cursor:pointer" data-pick="${esc(String(p.id))}">
      <h3>${esc(p.name)}</h3><p class="small">${esc(p.description || "")}</p>
      <div class="kv"><dt>Total</dt><dd>${money(p.fullPrice)}</dd></div>
      <div class="kv"><dt>Deposit from</dt><dd>${money(p.downPayment)}</dd></div>
      <div class="kv"><dt>Units in stock</dt><dd>${esc(String(c))}</dd></div>
      <div class="chip chip-green" style="margin-top:0.5rem">${esc(String(c))} available</div>
    </div>`;
  }).join("")}</div>`;
  slot.querySelectorAll("[data-pick]").forEach((el) => el.addEventListener("click", () => {
    const gid = Number(el.dataset.pick);
    const group = phonesData.products.find((g) => g.product.id === gid);
    if (!group) return;
    phonesData.selectedDevice = group.devices[0];
    phonesData.selectedPlan = null;
    phonesData.deposit = group.product.downPayment || 0;
    phonesData.quote = null;
    phonesData.step = 2;
    renderPhoneStep();
  }));
}

async function renderPhonePlan(slot) {
  const d = phonesData.selectedDevice;
  if (!d) { phonesData.step = 1; renderPhoneStep(); return; }
  const product = phonesData.products.find((g) => g.devices.some((dd) => dd.id === d.id))?.product || {};
  const maxDeposit = Math.max(0, (Number(product.fullPrice) || 0) - 1);
  const plans = phonesData.plans || [];
  const modes = phonesData.modes || [];

  slot.innerHTML = `
    <div class="grid-2 uneven">
      <div class="card">
        <div class="phone-stage"><div id="plan-phone" class="phone-figure"></div><div><h3>${esc(product.name || d.productName)}</h3><p class="small">${esc(product.description || "")}</p><p class="small">Device ${esc(d.deviceCode || "")} · IMEI ${esc(d.imei || "—")}</p></div></div>
      </div>
      <div class="card">
        <div class="field"><label for="phone-deposit">Deposit (KES)</label><input id="phone-deposit" type="number" min="${esc(String(product.downPayment || 0))}" max="${esc(String(maxDeposit))}" step="50" value="${esc(String(phonesData.deposit))}" /></div>
        <div class="field"><label for="phone-plan">Payment plan</label><select id="phone-plan"><option value="">Choose a plan…</option>${plans.map((pl) => `<option value="${esc(String(pl.id))}" ${phonesData.selectedPlan?.id === pl.id ? "selected" : ""}>${esc(pl.planName)} — own it in ${esc(String(pl.durationDays))} days</option>`).join("")}</select></div>
        <div id="phone-quote" class="card mt" style="background:var(--paper)"></div>
        <div class="btn-row" style="margin-top:1rem"><a class="btn btn-secondary" href="#/phones" data-back>Back to catalog</a><button class="btn btn-primary" id="phone-next" disabled>Continue</button></div>
      </div>
    </div>`;
  const depositEl = document.getElementById("phone-deposit");
  const planEl = document.getElementById("phone-plan");
  const nextBtn = document.getElementById("phone-next");
  const quoteSlot = document.getElementById("phone-quote");

  const fetchQuote = debounce(async () => {
    if (!depositEl.value || !planEl.value) { quoteSlot.innerHTML = '<p class="small">Choose a plan and deposit to see your quote.</p>'; nextBtn.disabled = true; return; }
    try {
      const q = await api(`/financing/quote?deviceId=${d.id}&paymentPlanId=${planEl.value}&deposit=${depositEl.value}`);
      phonesData.quote = q;
      phonesData.deposit = Number(depositEl.value);
      phonesData.selectedPlan = phonesData.plans.find((pl) => String(pl.id) === planEl.value) || null;
      const extra = Number(q.depositAmount || 0) + Number(q.totalPayable || 0) - Number(q.devicePrice || 0);
      quoteSlot.innerHTML = `
        <h3 class="bignum" style="font-size:1.1rem">Your quote</h3>
        <dl class="kv"><dt>Device price</dt><dd>${money(q.devicePrice)}</dd></dl>
        <dl class="kv"><dt>Deposit today</dt><dd>${money(q.depositAmount)}</dd></dl>
        <dl class="kv"><dt>Financed amount</dt><dd>${money(q.requestedPrincipal)}</dd></dl>
        <dl class="kv"><dt>Daily installment</dt><dd>${money(q.installmentAmount)}</dd></dl>
        <dl class="kv"><dt>Plan</dt><dd>${esc(q.paymentPlanName || "")}</dd></dl>
        <dl class="kv"><dt>Total cost</dt><dd>${money(q.totalPayable)}</dd></dl>
        <dl class="kv"><dt>Extra over cash price</dt><dd>${money(extra)} ${extra === 0 ? '<span class="chip chip-green">No interest</span>' : ""}</dd></dl>`;
      nextBtn.disabled = false;
    } catch (err) {
      quoteSlot.innerHTML = `<div class="form-error">${esc(err.message || "Could not get quote.")}</div>`;
      nextBtn.disabled = true;
    }
  }, 400);
  depositEl.addEventListener("input", fetchQuote);
  planEl.addEventListener("change", fetchQuote);
  document.querySelector("[data-back]")?.addEventListener("click", (e) => { e.preventDefault(); phonesData.step = 1; renderPhoneStep(); });
  nextBtn.addEventListener("click", () => { phonesData.step = 3; renderPhoneStep(); });
  // render small phone
  renderPhone(document.getElementById("plan-phone"), { pct: 0, state: "active", label: "Phone preview" });
  fetchQuote();
}

function renderPhoneReview(slot) {
  const d = phonesData.selectedDevice, q = phonesData.quote, p = phonesData.products.find((g) => g.devices.some((dd) => dd.id === d?.id))?.product || {};
  if (!d || !q) { phonesData.step = 2; renderPhoneStep(); return; }
  slot.innerHTML = `
    <div class="grid-2 uneven">
      <div class="card"><div class="phone-stage"><div id="review-phone" class="phone-figure"></div><div><h3>${esc(p.name || d.productName)}</h3><p class="small">${esc(p.description || "")}</p></div></div></div>
      <div class="card">
        <h3>Review your application</h3>
        <dl class="kv"><dt>Device</dt><dd>${esc(p.name || "")} · ${esc(d.deviceCode || "")}</dd></dl>
        <dl class="kv"><dt>Deposit</dt><dd>${money(q.depositAmount)}</dd></dl>
        <dl class="kv"><dt>Financed</dt><dd>${money(q.requestedPrincipal)}</dd></dl>
        <dl class="kv"><dt>Daily</dt><dd>${money(q.installmentAmount)}</dd></dl>
        <dl class="kv"><dt>Plan</dt><dd>${esc(q.paymentPlanName || "")}</dd></dl>
        <dl class="kv"><dt>Extra over cash</dt><dd>${money(Number(q.depositAmount || 0) + Number(q.totalPayable || 0) - Number(q.devicePrice || 0))}</dd></dl>
        <div class="btn-row"><a class="btn btn-secondary" href="#/phones" data-back>Back</a><button class="btn btn-primary" id="phone-next">Review & sign</button></div>
      </div>
    </div>`;
  renderPhone(document.getElementById("review-phone"), { pct: 0, state: "active", label: "Phone preview" });
  document.querySelector("[data-back]")?.addEventListener("click", (e) => { e.preventDefault(); phonesData.step = 2; renderPhoneStep(); });
  document.getElementById("phone-next").addEventListener("click", () => { phonesData.step = 4; renderPhoneStep(); });
}

// Signature pad: draws on mouse + touch, clears with a button.
// NOTE: the signature is UI-only — the backend does not store it.
function initSigPad(canvas) {
  if (!canvas) return;
  const ctx = canvas.getContext("2d");
  let drawing = false, hasDrawn = false;
  const getPos = (e) => {
    const rect = canvas.getBoundingClientRect();
    const t = e.touches?.[0] || e;
    return { x: (t.clientX - rect.left) * (canvas.width / rect.width), y: (t.clientY - rect.top) * (canvas.height / rect.height) };
  };
  const start = (e) => { drawing = true; const p = getPos(e); ctx.beginPath(); ctx.moveTo(p.x, p.y); };
  const draw = (e) => { if (!drawing) return; hasDrawn = true; const p = getPos(e); ctx.lineTo(p.x, p.y); ctx.stroke(); };
  const stop = () => { drawing = false; canvas.dataset.signed = hasDrawn ? "1" : ""; };
  canvas.addEventListener("mousedown", start);
  canvas.addEventListener("mousemove", draw);
  canvas.addEventListener("mouseup", stop);
  canvas.addEventListener("mouseleave", stop);
  canvas.addEventListener("touchstart", start, { passive: true });
  canvas.addEventListener("touchmove", draw, { passive: true });
  canvas.addEventListener("touchend", stop, { passive: true });
}

async function renderPhoneSign(slot) {
  const d = phonesData.selectedDevice, q = phonesData.quote, p = phonesData.products.find((g) => g.devices.some((dd) => dd.id === d?.id))?.product || {}, planId = phonesData.selectedPlan?.id;
  if (!d || !q || !planId) { phonesData.step = 2; renderPhoneStep(); return; }
  slot.innerHTML = `
    <div class="grid-2 uneven">
      <div class="card"><div class="phone-stage"><div id="sign-phone" class="phone-figure"></div><div><h3>${esc(p.name || "")}</h3><p class="small">Deposit ${money(q.depositAmount)} · Daily ${money(q.installmentAmount)}</p></div></div>
        <div class="card mt" style="background:var(--paper)"><h3 class="bignum" style="font-size:1.1rem">Summary</h3><dl class="kv"><dt>Device</dt><dd>${esc(p.name || "")} · ${esc(d.deviceCode || "")}</dd></dl><dl class="kv"><dt>Deposit</dt><dd>${money(q.depositAmount)}</dd></dl><dl class="kv"><dt>Financed</dt><dd>${money(q.requestedPrincipal)}</dd></dl><dl class="kv"><dt>Daily</dt><dd>${money(q.installmentAmount)}</dd></dl></div>
      </div>
      <div class="card">
        <h3>E-sign and apply</h3>
        <p class="small">Draw your signature below. This is captured on your device for now; the backend does not store it yet.</p>
        <canvas id="sig-canvas" class="sigpad" width="500" height="160"></canvas>
        <div class="btn-row"><button type="button" class="btn btn-secondary btn-sm" id="sig-clear">Clear signature</button></div>
        <div class="checkrow"><input type="checkbox" id="consent-data" /><label for="consent-data">I consent to my data being used for this financing application.</label></div>
        <div class="checkrow"><input type="checkbox" id="consent-lock" /><label for="consent-lock">I understand my phone can be locked if I miss payments.</label></div>
        <div id="sign-error" class="form-error hidden" role="alert"></div>
        <div class="btn-row"><a class="btn btn-secondary" href="#/phones" data-back>Back</a><button class="btn btn-primary" id="apply-btn" disabled>Submit application</button></div>
      </div>
    </div>`;
  renderPhone(document.getElementById("sign-phone"), { pct: 0, state: "active", label: "Phone preview" });
  const canvas = document.getElementById("sig-canvas");
  initSigPad(canvas);
  document.getElementById("sig-clear").addEventListener("click", () => { const ctx = canvas.getContext("2d"); ctx.clearRect(0, 0, canvas.width, canvas.height); canvas.dataset.signed = ""; updateApply(); });
  const updateApply = () => { document.getElementById("apply-btn").disabled = !(canvas.dataset.signed && document.getElementById("consent-data").checked && document.getElementById("consent-lock").checked); };
  document.getElementById("consent-data").addEventListener("change", updateApply);
  document.getElementById("consent-lock").addEventListener("change", updateApply);
  updateApply();
  document.querySelector("[data-back]")?.addEventListener("click", (e) => { e.preventDefault(); phonesData.step = 3; renderPhoneStep(); });
  document.getElementById("apply-btn").addEventListener("click", async () => {
    const errEl = document.getElementById("sign-error");
    errEl.classList.add("hidden");
    document.getElementById("apply-btn").disabled = true;
    try {
      await api("/financing/applications", { method: "POST", body: { deviceId: d.id, depositAmount: Number(phonesData.deposit), paymentPlanId: planId, paymentModeId: null } });
      phonesData.step = 1;
      phonesData.selectedDevice = null;
      phonesData.quote = null;
      toast("Application submitted!", "success");
      location.hash = "#/home";
      renderHome();
    } catch (err) {
      errEl.classList.remove("hidden");
      errEl.textContent = err.message || "Could not submit. The device may have been reserved by someone else.";
      document.getElementById("apply-btn").disabled = false;
      if (err.status === 400 || err.status === 409) { phonesData.step = 1; renderPhoneStep(); }
    }
  });
}

// ─── PAY ──────────────────────────────────────────────────────────────────────
async function renderPay() {
  page().innerHTML = `<section class="wrap page-head"><h2>Make a payment</h2><div class="skeleton" style="height:300px;border-radius:18px"></div></section>`;
  try {
    const dashboard = await api("/users/me/dashboard");
    const contracts = (dashboard.deviceLoans || []).filter((c) => c.loanStatus === "ACTIVE" || c.loanStatus === "OVERDUE");
    if (!contracts.length) { page().innerHTML = `<section class="wrap page-head">${emptyState("No active contract", "Choose a phone first.", "Choose a phone", "#/phones")}</section>`; return; }
    let activeId = contracts[0].id;
    let amount = Number(contracts[0].dailyAmount) || 0;
    let channel = "MPESA";
    let idemKey = crypto.randomUUID();
    let submitting = false;
    let pollTimer = null;

    function render() {
      const c = contracts.find((x) => x.id === activeId) || contracts[0];
      const max = Number(c.amountRemaining) || 0;
      if (amount > max) amount = max;
      page().innerHTML = `
        <section class="wrap page-head">
          <h2>Make a payment</h2>
          <div class="card">
            <div class="field"><label for="pay-contract">Contract</label><select id="pay-contract">${contracts.map((ct) => `<option value="${esc(String(ct.id))}" ${ct.id === activeId ? "selected" : ""}>${esc(ct.productName || "")} · ${esc(ct.contractNumber || "")} · ${money(ct.amountRemaining)} left</option>`).join("")}</select></div>
            <div class="field"><label for="pay-amount">Amount (KES, max ${money(max)})</label><input id="pay-amount" type="number" min="1" max="${esc(String(max))}" step="1" value="${esc(String(amount))}" /></div>
            <div class="btn-row" style="flex-wrap:wrap">${[{ l: "Daily " + money(c.dailyAmount), v: c.dailyAmount }, { l: "3 days", v: c.dailyAmount * 3 }, { l: "7 days", v: c.dailyAmount * 7 }, { l: "Clear balance", v: max }].map((b) => `<button type="button" class="btn btn-secondary btn-sm chip-amount" data-amt="${b.v}">${esc(b.l)}</button>`).join("")}</div>
            <div class="field" style="margin-top:0.9rem"><label for="pay-channel">Channel</label><select id="pay-channel"><option value="MPESA">M-PESA (simulated)</option><option value="CARD" disabled>Card — Coming soon</option><option value="BANK" disabled>Bank — Coming soon</option><option value="AGENT" disabled>Agent — Coming soon</option><option value="POS" disabled>POS — Coming soon</option><option value="USSD" disabled>USSD — Coming soon</option></select></div>
            <details class="demo-box"><summary>Demo controls</summary><div class="field"><label for="pay-scenario">Mock scenario</label><select id="pay-scenario"><option value="AUTO">Provider decides (success)</option><option value="SUCCESS">Force success</option><option value="PENDING">Force pending</option><option value="FAILED">Force failure</option></select></div></details>
            <div id="pay-result" class="mt"></div>
            <div class="btn-row"><button class="btn btn-primary" id="pay-btn">Pay ${money(amount)}</button></div>
          </div>
        </section>`;
      document.getElementById("pay-contract").addEventListener("change", (e) => { activeId = Number(e.target.value); amount = Number((contracts.find((x) => x.id === activeId) || c).dailyAmount); render(); });
      document.getElementById("pay-amount").addEventListener("input", (e) => { amount = Math.max(0, Math.min(max, Number(e.target.value) || 0)); document.getElementById("pay-btn").textContent = `Pay ${money(amount)}`; });
      for (const el of document.querySelectorAll(".chip-amount")) el.addEventListener("click", () => { amount = Math.min(max, Number(el.dataset.amt)); render(); });
      document.getElementById("pay-btn").addEventListener("click", doPay);
    }

    async function doPay() {
      if (submitting) return;
      const c = contracts.find((x) => x.id === activeId) || contracts[0];
      const max = Number(c.amountRemaining) || 0;
      if (amount <= 0 || amount > max) { toast("Amount must be between 1 and " + max, "error"); return; }
      submitting = true;
      try {
        const tx = await api("/payments", { method: "POST", headers: { "Idempotency-Key": idemKey }, body: { contractId: activeId, amount, channel: "MPESA", mockScenario: document.getElementById("pay-scenario")?.value || "AUTO" } });
        renderPayResult(tx);
        if (tx.status === "PENDING") startPoll(tx.transactionReference);
      } catch (err) {
        const resEl = document.getElementById("pay-result");
        if (resEl) resEl.innerHTML = `<div class="form-error">${esc(err.message || "Payment failed.")}</div>`;
        // Keep idemKey for retry on network error; fresh key after FAILED.
      }
      submitting = false;
    }

    async function renderPayResult(tx) {
      const resEl = document.getElementById("pay-result");
      if (!resEl) return;
      const note = { POSTED: "Posted. Balance reduced and receipt issued.", SUCCESS: "Confirmed by provider. Awaiting posting.", PENDING: "Waiting for M-PESA confirmation…", FAILED: `Failed: ${tx.failureReason || "provider declined"}.` }[tx.status] || tx.status;
      resEl.innerHTML = `<div id="pay-phone" class="phone-figure" style="margin:1rem 0"></div><div class="card" style="background:var(--paper)"><div class="kv"><dt>Reference</dt><dd>${esc(tx.transactionReference || "")}</dd></div><div class="kv"><dt>Status</dt><dd>${statusChip(tx.status)}</dd></div><div class="kv"><dt>Provider ref</dt><dd>${esc(tx.providerReference || "—")}</dd></div><div class="kv"><dt>Receipt</dt><dd>${esc(tx.receiptNumber || "—")}</dd></div><div class="kv"><dt>Balance</dt><dd>${money(tx.remainingBalance)}</dd></div><p class="small">${esc(note)}</p></div>`;
      if (tx.status === "POSTED" || tx.status === "SUCCESS") {
        idemKey = crypto.randomUUID();
        // Render updated phone ownership from the new remaining balance.
        const dashboard = await api("/users/me/dashboard");
        const contract = (dashboard.deviceLoans || []).find((c) => c.id === activeId);
        if (contract) renderPhone(document.getElementById("pay-phone"), { pct: ownershipPct(contract), state: "active", label: "Updated ownership" });
        // A payment that moves the paid-through date also issues a new unlock code.
        const lock = await api(`/loans/${activeId}/device-lock`).catch(() => null);
        const code = lock?.latestCode;
        if (code && code.paymentReference === tx.transactionReference && document.getElementById("pay-result")) {
          document.getElementById("pay-result").insertAdjacentHTML("beforeend", `<div class="card mt lock-card"><h3>New unlock code</h3><span class="unlock-code">${esc(code.code)}</span><p class="small mt">${code.permanent ? "Paid in full: this code unlocks your phone for good." : `Keeps your phone unlocked until ${esc(fmtDate(code.validUntil))}.`} Also sent by SMS.</p></div>`);
        }
      }
      if (tx.status === "FAILED") { idemKey = crypto.randomUUID(); }
    }

    function startPoll(ref) {
      if (pollTimer) clearInterval(pollTimer);
      let attempts = 0;
      pollTimer = setInterval(async () => {
        attempts++;
        if (attempts > 40) { clearInterval(pollTimer); const resEl = document.getElementById("pay-result"); if (resEl) resEl.innerHTML += `<p class="small mt">Still waiting for provider. You can check activity later.</p>`; return; }
        try {
          const tx = await api(`/payments/${ref}`);
          if (tx.status === "POSTED" || tx.status === "SUCCESS" || tx.status === "FAILED") {
            clearInterval(pollTimer);
            renderPayResult(tx);
          }
        } catch { /* keep polling */ }
      }, 3000);
    }

    render();
  } catch (err) {
    page().innerHTML = `<section class="wrap page-head"><div class="form-error">${esc(err.message || "Unable to load payment screen.")}</div></section>`;
  }
}

// ─── ACTIVITY ─────────────────────────────────────────────────────────────────
async function renderActivity() {
  page().innerHTML = `<section class="wrap page-head"><h2>Activity</h2><div class="skeleton" style="height:300px;border-radius:18px"></div></section>`;
  try {
    const [txns, apps] = await Promise.all([api("/payments/me"), api("/financing/applications/me")]);
    let tab = "payments";
    function render() {
      page().innerHTML = `<section class="wrap page-head"><h2>Activity</h2>
        <div class="tabs"><button aria-selected="${tab === "payments"}" data-tab="payments">Payments</button><button aria-selected="${tab === "apps"}" data-tab="apps">Applications</button></div>
        <div id="activity-content" class="card"></div></section>`;
      const slot = document.getElementById("activity-content");
      if (tab === "payments") renderPayments(slot, txns);
      else renderApps(slot, apps);
      document.querySelectorAll("[data-tab]").forEach((btn) => btn.addEventListener("click", () => { tab = btn.dataset.tab; render(); }));
    }
    function renderPayments(slot, txns) {
      if (!txns.length) { slot.innerHTML = emptyState("No payments yet", "Make a payment to get started.", "Pay now", "#/pay"); return; }
      slot.innerHTML = `<div class="table-scroll"><table class="data"><thead><tr><th>Date</th><th>Ref</th><th>Amount</th><th>Channel</th><th>Status</th><th></th></tr></thead><tbody>${txns.map((tx) => `<tr><td>${esc(fmtDateTime(tx.requestedAt))}</td><td class="mono">${esc(tx.transactionReference || "")}</td><td>${money(tx.amount)}</td><td>${esc(tx.channel || "")}</td><td>${statusChip(tx.status)}</td><td>${tx.status === "POSTED" ? `<button class="btn btn-ghost btn-sm" data-receipt="${esc(tx.transactionReference || "")}">Receipt</button>` : ""}</td></tr>`).join("")}</tbody></table></div>`;
      slot.querySelectorAll("[data-receipt]").forEach((btn) => btn.addEventListener("click", async () => {
        try { const r = await api(`/payments/${btn.dataset.receipt}/receipt`); showReceipt(r); } catch (e) { toast(e.message || "Could not load receipt.", "error"); }
      }));
    }
    function showReceipt(r) {
      openModal({ title: "Payment receipt", bodyHTML: `<div class="receipt"><h3 class="bignum" style="font-size:1.1rem">Receipt ${esc(r.receiptNumber || "")}</h3><dl class="kv"><dt>Customer</dt><dd>${esc(r.customerName || "")} (${esc(r.customerCode || "")})</dd></dl><dl class="kv"><dt>Contract</dt><dd>${esc(r.contractNumber || "")}</dd></dl><dl class="kv"><dt>Device</dt><dd>${esc(r.productName || "")} · ${esc(r.deviceCode || "")}</dd></dl><dl class="kv"><dt>Amount</dt><dd>${money(r.amount)}</dd></dl><dl class="kv"><dt>Channel</dt><dd>${esc(r.channel || "")}</dd></dl><dl class="kv"><dt>Provider ref</dt><dd>${esc(r.providerReference || "—")}</dd></dl><dl class="kv"><dt>Status</dt><dd>${statusChip(r.status)}</dd></dl><dl class="kv"><dt>Balance</dt><dd>${money(r.remainingBalance)}</dd></dl><dl class="kv"><dt>Date</dt><dd>${esc(fmtDateTime(r.postedAt))}</dd></dl><div class="btn-row"><button class="btn btn-secondary btn-sm" onclick="window.print()">Print</button></div></div>` });
    }
    function renderApps(slot, apps) {
      if (!apps.length) { slot.innerHTML = emptyState("No applications yet", "Apply for a phone to get started.", "Choose a phone", "#/phones"); return; }
      slot.innerHTML = `<div class="table-scroll"><table class="data"><thead><tr><th>Application</th><th>Device</th><th>Deposit</th><th>Financed</th><th>Status</th><th></th></tr></thead><tbody>${apps.map((a) => `<tr><td>${esc(a.applicationNumber || "")}</td><td>${esc(a.productName || "")}</td><td>${money(a.depositAmount)}</td><td>${money(a.requestedPrincipal)}</td><td>${statusChip(a.status)}</td><td>${["SUBMITTED", "UNDER_REVIEW"].includes(a.status) ? `<button class="btn btn-ghost btn-sm" data-cancel="${esc(String(a.id))}">Cancel</button>` : ""}${a.status === "REJECTED" ? `<span class="small"> ${esc(a.decisionReason || "")}</span>` : ""}</td></tr>`).join("")}</tbody></table></div>`;
      slot.querySelectorAll("[data-cancel]").forEach((btn) => btn.addEventListener("click", async () => {
        const ok = await confirmDialog({ title: "Cancel application?", message: "This cannot be undone.", confirmLabel: "Cancel application", danger: true });
        if (!ok) return;
        try { await api(`/financing/applications/me/${btn.dataset.cancel}/cancel`, { method: "POST" }); toast("Application cancelled.", "success"); renderActivity(); } catch (e) { toast(e.message || "Could not cancel.", "error"); }
      }));
    }
    render();
  } catch (err) {
    page().innerHTML = `<section class="wrap page-head"><div class="form-error">${esc(err.message || "Unable to load activity.")}</div></section>`;
  }
}

// ─── ACCOUNT ──────────────────────────────────────────────────────────────────
async function renderAccount() {
  page().innerHTML = `<section class="wrap page-head"><h2>Account</h2><div class="skeleton" style="height:300px;border-radius:18px"></div></section>`;
  try {
    const [profile, dashboard] = await Promise.all([api("/users/me"), api("/users/me/dashboard")]);
    const cashLoans = dashboard.cashLoans || [];
    let ussdOpen = false, ussdLines = [], ussdInput = "";
    function render() {
      page().innerHTML = `
        <section class="wrap page-head">
          <h2>Account</h2>
          <div class="card"><h3>Profile</h3><dl class="kv"><dt>Name</dt><dd>${esc(profile.fullName || "")}</dd></dl><dl class="kv"><dt>Code</dt><dd>${esc(profile.customerCode || "")}</dd></dl><dl class="kv"><dt>Email</dt><dd>${esc(profile.email || "")}</dd></dl><dl class="kv"><dt>Phone</dt><dd>${esc(profile.phoneNumber || "—")}</dd></dl></div>
          <div class="card mt"><h3>Cash loan</h3><p class="small">Request a cash advance (demo).</p><form id="cash-form"><div class="grid-2"><div class="field"><label for="cl-amount">Amount (KES)</label><input id="cl-amount" type="number" min="1000" step="100" value="10000" /></div><div class="field"><label for="cl-rate">Monthly rate (%)</label><input id="cl-rate" type="number" min="0" step="0.5" value="4" /></div></div><div class="field"><label for="cl-purpose">Purpose</label><input id="cl-purpose" type="text" value="School fees" /></div><div class="btn-row"><button class="btn btn-primary" type="submit">Request cash loan</button></div></form></div>
          ${cashLoans.length ? `<div class="card mt"><h3>Cash loans</h3><ul class="timeline">${cashLoans.map((cl) => `<li><div class="t-main"><div>${esc(cl.purpose || "")} · ${statusChip(cl.status)}</div><div class="t-sub">${esc(cl.customerName || "")} · ${esc(fmtDate(cl.createdAt))}</div></div><div class="t-amt">${money(cl.balanceRemaining)}</div></li>`).join("")}</ul></div>` : ""}
          <div class="btn-row" style="margin-top:1rem"><button class="btn btn-secondary" id="signout-btn">Sign out</button><button class="btn btn-secondary" id="ussd-btn">USSD simulator</button></div>
          ${ussdOpen ? `<div class="card mt"><h3>USSD simulator <span class="chip chip-indigo rail-tag">Tracom: alternative banking channels</span></h3><div class="ussd-screen" aria-live="polite">${ussdLines.map((l) => esc(l)).join("\n")}</div><div class="ussd-input"><input id="ussd-input" type="text" placeholder="${esc(ussdInput)}" /><button class="btn btn-primary btn-sm" id="ussd-send">Send</button></div></div>` : ""}
        </section>`;
      document.getElementById("signout-btn").addEventListener("click", signOut);
      document.getElementById("ussd-btn").addEventListener("click", async () => {
        ussdOpen = true;
        ussdLines = ["*456# M-Simu Finance", "", "1 My balance", "2 Pay now", "3 Mini statement", "4 Next due date", "0 Exit", "", "Reply:"];
        ussdInput = "1";
        render();
      });
      document.getElementById("cash-form").addEventListener("submit", async (e) => {
        e.preventDefault();
        try {
          await api("/cash-loans", { method: "POST", body: { amountRequested: Number(document.getElementById("cl-amount").value), monthlyRate: Number(document.getElementById("cl-rate").value), purpose: document.getElementById("cl-purpose").value.trim() } });
          toast("Cash loan request submitted.", "success");
          renderAccount();
        } catch (err) { toast(err.message || "Could not request loan.", "error"); }
      });
      document.getElementById("ussd-send")?.addEventListener("click", async () => {
        const input = document.getElementById("ussd-input");
        const cmd = (input?.value || "").trim();
        if (!cmd) return;
        ussdLines.push(`> ${cmd}`);
        try {
          if (cmd === "1" || cmd === "4") {
            const d = await api("/users/me/dashboard");
            const summary = d.paymentSummary || {};
            if (cmd === "1") { ussdLines.push(`Balance: ${money(summary.outstandingDevices ?? summary.totalBalance ?? 0)} remaining`); }
            else {
              const c = (d.deviceLoans || []).find((x) => x.loanStatus === "ACTIVE" || x.loanStatus === "OVERDUE");
              ussdLines.push(c ? `Next due: ${dueLabel(c.nextPaymentDueDate)} — ${money(c.dailyAmount)}` : "No active contract.");
            }
          } else if (cmd === "2") {
            const d = await api("/users/me/dashboard");
            const c = (d.deviceLoans || []).find((x) => x.loanStatus === "ACTIVE" || x.loanStatus === "OVERDUE");
            if (!c) { ussdLines.push("No active contract."); }
            else {
              const tx = await api("/payments", { method: "POST", headers: { "Idempotency-Key": crypto.randomUUID() }, body: { contractId: c.id, amount: c.dailyAmount, channel: "MPESA", mockScenario: "AUTO" } });
              ussdLines.push(`Paid ${money(tx.amount)}. Status: ${tx.status}.${tx.receiptNumber ? ` Receipt: ${tx.receiptNumber}.` : ""}`);
            }
          } else if (cmd === "3") {
            const txns = await api("/payments/me");
            if (!txns.length) ussdLines.push("No transactions yet.");
            else txns.slice(0, 5).forEach((tx) => ussdLines.push(`${fmtDateTime(tx.requestedAt)} — ${money(tx.amount)} — ${tx.status}`));
          } else if (cmd === "0") {
            ussdLines.push("Goodbye.");
            setTimeout(() => { ussdOpen = false; ussdLines = []; render(); }, 800);
          } else {
            ussdLines.push("Invalid option. Reply 1–4 or 0.");
          }
        } catch (err) {
          ussdLines.push(err.message || "Request failed.");
        }
        ussdInput = "";
        render();
      });
    }
    render();
  } catch (err) {
    page().innerHTML = `<section class="wrap page-head"><div class="form-error">${esc(err.message || "Unable to load account.")}</div></section>`;
  }
}

if (me) route();
