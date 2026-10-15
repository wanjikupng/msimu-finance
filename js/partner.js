// Partner portal: agent and merchant read-only views.
// No /admin calls; every server string goes through esc().
import { api, requireRole, signOut } from "./api.js";
import { esc, money, moneyExact, fmtDate, fmtDateTime, skeletonRows, emptyState, statusChip, toast } from "./ui.js";

// ─── guards ───────────────────────────────────────────────────────────────────
const ALLOWED = ["AGENT", "MERCHANT", "TECHNICIAN"];
const me = requireRole(ALLOWED);
const role = me?.role;
if (me) {
  document.getElementById("user-name").textContent = `${me.fullName || me.customerCode || "Partner"} · ${esc(role)}`;
  document.getElementById("sign-out").addEventListener("click", signOut);
}

// ─── page helper ──────────────────────────────────────────────────────────────
const page = () => document.getElementById("page");

// Terminal row/card shared by agent and merchant views. Cashless: the figures
// are M-PESA collections sent from the terminal, never cash.
function onlineChip(t) {
  return t.online ? '<span class="chip chip-green">ONLINE</span>' : '<span class="chip chip-grey">OFFLINE</span>';
}
function terminalsTable(terminals, withAgent) {
  if (!terminals || terminals.length === 0) return emptyState("No terminals", "Terminals appear here once deployed.");
  return `<div class="table-scroll"><table class="data"><thead><tr><th>Terminal</th><th>Status</th><th>Online</th>${withAgent ? "<th>Agent</th>" : ""}<th>Today (M-PESA)</th><th>Last seen</th></tr></thead><tbody>${terminals.map((t) => `<tr><td class="mono">${esc(t.terminalCode)}</td><td>${statusChip(t.status)}</td><td>${onlineChip(t)}</td>${withAgent ? `<td>${esc(t.agentName || "Unassigned")}</td>` : ""}<td>${esc(String(t.todayApprovedCount))} · ${money(t.todayApprovedAmount)}</td><td>${esc(t.lastSeenAt ? fmtDateTime(t.lastSeenAt) : "Never")}</td></tr>`).join("")}</tbody></table></div>`;
}

// Commission card shared by agent and merchant: what is owed, what is on its
// way (in a batch awaiting approval), what has reached their M-PESA.
const COMMISSION_LABEL = { AGENT_COLLECTION: "Collection", MERCHANT_COLLECTION: "Terminal override", MERCHANT_SALE: "Phone sale fee", ROUNDING_CARRY: "Cents carried forward" };
function commissionCard(c, title, intro) {
  if (!c) return "";
  return `<div class="card mt"><h3>${esc(title)}</h3><p class="small">${esc(intro)}</p>
    <div class="stat-grid"><div class="stat"><span>Owed to you</span><strong>${moneyExact(c.owed)}</strong><em>paid in the next settlement</em></div><div class="stat"><span>On its way</span><strong>${moneyExact(c.inSettlement)}</strong><em>awaiting approval</em></div><div class="stat"><span>Paid to your M-PESA</span><strong>${moneyExact(c.paidToDate)}</strong></div><div class="stat"><span>Earned today</span><strong>${moneyExact(c.earnedToday)}</strong></div></div>
    ${c.payouts.length ? `<h3 class="mt">Payouts</h3><div class="table-scroll"><table class="data"><thead><tr><th>Batch</th><th>Amount</th><th>Status</th><th>M-PESA ref</th><th>Paid</th></tr></thead><tbody>${c.payouts.map((p) => `<tr><td class="mono">${esc(p.batchNumber)}</td><td>${money(p.netAmount)}</td><td>${statusChip(p.status)}</td><td class="mono">${esc(p.payoutReference || p.failureReason || "—")}</td><td>${esc(p.paidAt ? fmtDateTime(p.paidAt) : "—")}</td></tr>`).join("")}</tbody></table></div>` : ""}
    ${c.entries.length ? `<details class="mt"><summary class="small">Recent commission (${esc(String(c.entries.length))})</summary><div class="table-scroll"><table class="data"><thead><tr><th>Earned</th><th>For</th><th>Payment / contract</th><th>Amount</th><th>Status</th></tr></thead><tbody>${c.entries.map((e) => `<tr><td>${esc(fmtDateTime(e.earnedAt))}</td><td>${esc(COMMISSION_LABEL[e.commissionType] || e.commissionType)}${e.clawback ? " (clawback)" : ""}</td><td class="mono">${esc(e.transactionReference || e.contractNumber || "")}</td><td>${moneyExact(e.amount)}</td><td>${statusChip(e.status)}</td></tr>`).join("")}</tbody></table></div></details>` : ""}
  </div>`;
}

// ─── agent view ───────────────────────────────────────────────────────────────
async function renderAgent() {
  page().innerHTML = `<section class="wrap page-head"><h2>My profile</h2><div class="skeleton" style="height:260px;border-radius:18px"></div></section>`;
  try {
    const [profile, merchant, devices, terminals] = await Promise.all([
      api("/agents/me"),
      api("/agents/me/merchant").catch(() => null),
      api("/agents/me/devices").catch(() => []),
      api("/agents/me/terminals").catch(() => []),
    ]);
    const commission = await api("/agents/me/commissions").catch(() => null);
    const rate = commission ? `${(Number(commission.plan.agentCollectionRate) * 100).toFixed(0)}%` : "";
    const active = (terminals || []).find((t) => t.status === "ACTIVE");
    page().innerHTML = `
      <section class="wrap page-head">
        <h2>Agent dashboard</h2>
        <div class="card lock-card">
          <div class="head-row"><h3>My terminal</h3>${active ? onlineChip(active) : ""}</div>
          ${active ? `<p class="small">Collect repayments by sending an M-PESA request to the customer's phone. The customer approves with their own PIN. You never handle cash.</p>
            <div class="stat-grid"><div class="stat"><span>Terminal</span><strong class="mono">${esc(active.terminalCode)}</strong><em>${esc(active.model)}</em></div><div class="stat"><span>Collected today</span><strong>${money(active.todayApprovedAmount)}</strong><em>${esc(String(active.todayApprovedCount))} M-PESA payments</em></div><div class="stat"><span>Declined today</span><strong>${esc(String(active.todayDeclinedCount))}</strong></div></div>
            <div class="btn-row mt"><a class="btn btn-primary" href="pos.html?tid=${encodeURIComponent(active.terminalCode)}" target="_blank" rel="noopener">Open terminal</a></div>`
            : `<p class="small">${terminals && terminals.length ? `Your terminal ${esc(terminals[0].terminalCode)} is ${esc(terminals[0].status)}. Contact your merchant.` : "No terminal is assigned to you yet."}</p>`}
        </div>
        ${commissionCard(commission, "My commission", `You earn ${rate} of every M-PESA payment you collect on your terminal (up to ${commission ? money(commission.plan.agentCollectionCap) : ""} per payment). It is paid straight to your M-PESA when a settlement is approved.`)}
        <div class="card mt">
          <h3>Profile</h3>
          <dl class="kv"><dt>Name</dt><dd>${esc(profile.fullName || "")}</dd></dl>
          <dl class="kv"><dt>Agent code</dt><dd class="mono">${esc(profile.agentCode || "")}</dd></dl>
          <dl class="kv"><dt>Status</dt><dd>${statusChip(profile.status)}</dd></dl>
          <dl class="kv"><dt>Merchant</dt><dd>${esc(merchant?.businessName || profile.merchantName || "—")}</dd></dl>
          <dl class="kv"><dt>Email</dt><dd>${esc(profile.email || "")}</dd></dl>
          <dl class="kv"><dt>Phone</dt><dd>${esc(profile.phoneNumber || "—")}</dd></dl>
        </div>
        <div class="card mt">
          <h3>My devices</h3>
          ${(!devices || devices.length === 0) ? emptyState("No devices assigned", "Devices will appear here once assigned.") : `<div class="table-scroll"><table class="data"><thead><tr><th>Code</th><th>Product</th><th>IMEI</th><th>Status</th><th>Customer</th></tr></thead><tbody>${devices.map((d) => `<tr><td class="mono">${esc(d.deviceCode || "")}</td><td>${esc(d.productName || "")}</td><td class="mono">${esc(d.imei || "—")}</td><td>${statusChip(d.status)}</td><td>${esc(d.customerName || "—")}</td></tr>`).join("")}</tbody></table></div>`}
        </div>
      </section>`;
  } catch (err) {
    page().innerHTML = `<section class="wrap page-head"><div class="form-error">${esc(err.message || "Unable to load agent data.")}</div></section>`;
  }
}

// ─── merchant view ────────────────────────────────────────────────────────────
async function renderMerchant() {
  page().innerHTML = `<section class="wrap page-head"><h2>My merchant</h2><div class="skeleton" style="height:260px;border-radius:18px"></div></section>`;
  try {
    const [merchant, agents, devices, terminals] = await Promise.all([
      api("/merchants/me"),
      api("/merchants/me/agents").catch(() => []),
      api("/merchants/me/devices").catch(() => []),
      api("/merchants/me/terminals").catch(() => []),
    ]);
    const commission = await api("/merchants/me/commissions").catch(() => null);
    const todayTotal = (terminals || []).reduce((s, t) => s + Number(t.todayApprovedAmount || 0), 0);
    page().innerHTML = `
      <section class="wrap page-head">
        <h2>Merchant dashboard</h2>
        <div class="card">
          <h3>Business</h3>
          <dl class="kv"><dt>Business name</dt><dd>${esc(merchant.businessName || "")}</dd></dl>
          <dl class="kv"><dt>Merchant code</dt><dd class="mono">${esc(merchant.merchantCode || "")}</dd></dl>
          <dl class="kv"><dt>Status</dt><dd>${statusChip(merchant.status)}</dd></dl>
          <dl class="kv"><dt>Email</dt><dd>${esc(merchant.email || "")}</dd></dl>
          <dl class="kv"><dt>Phone</dt><dd>${esc(merchant.phone || "—")}</dd></dl>
          <dl class="kv"><dt>Address</dt><dd>${esc(merchant.address || "—")}</dd></dl>
        </div>
        ${commissionCard(commission, "Commission & settlement", commission ? `Your business earns ${(Number(commission.plan.merchantCollectionRate) * 100).toFixed(1)}% of M-PESA collections at your terminals, plus ${money(commission.plan.merchantSaleFee)} for every phone financed from your stock. Paid to your M-PESA when a settlement is approved.` : "")}
        ${commission && commission.agents.length ? `<div class="card mt"><h3>Your agents' commission</h3><div class="table-scroll"><table class="data"><thead><tr><th>Agent</th><th>Owed</th><th>On its way</th><th>Paid to date</th></tr></thead><tbody>${commission.agents.map((a) => `<tr><td>${esc(a.name)}</td><td>${moneyExact(a.accrued)}</td><td>${moneyExact(a.inSettlement)}</td><td>${moneyExact(a.settled)}</td></tr>`).join("")}</tbody></table></div></div>` : ""}
        <div class="card mt">
          <div class="head-row"><h3>Terminals</h3><span class="small">Today: ${money(todayTotal)} collected via M-PESA</span></div>
          ${terminalsTable(terminals, true)}
        </div>
        <div class="grid-2 mt">
          <div class="card">
            <h3>Agents</h3>
            ${(!agents || agents.length === 0) ? emptyState("No agents", "Agents will appear here once registered.") : `<div class="table-scroll"><table class="data"><thead><tr><th>Code</th><th>Name</th><th>Status</th></tr></thead><tbody>${agents.map((a) => `<tr><td class="mono">${esc(a.agentCode || "")}</td><td>${esc(a.fullName || "")}</td><td>${statusChip(a.status)}</td></tr>`).join("")}</tbody></table></div>`}
          </div>
          <div class="card">
            <h3>Devices</h3>
            ${(!devices || devices.length === 0) ? emptyState("No devices", "Devices will appear here once assigned.") : `<div class="table-scroll"><table class="data"><thead><tr><th>Code</th><th>Product</th><th>Status</th><th>Agent</th><th>Customer</th></tr></thead><tbody>${devices.map((d) => `<tr><td class="mono">${esc(d.deviceCode || "")}</td><td>${esc(d.productName || "")}</td><td>${statusChip(d.status)}</td><td>${esc(d.agentName || "—")}</td><td>${esc(d.customerName || "—")}</td></tr>`).join("")}</tbody></table></div>`}
          </div>
        </div>
      </section>`;
  } catch (err) {
    page().innerHTML = `<section class="wrap page-head"><div class="form-error">${esc(err.message || "Unable to load merchant data.")}</div></section>`;
  }
}

// ─── technician placeholder ───────────────────────────────────────────────────
function renderTechnician() {
  page().innerHTML = `<section class="wrap page-head"><h2>Technician</h2><div class="card empty"><h3>Coming soon</h3><p class="small">Repair and terminal management will be available in a future phase.</p><a class="btn btn-secondary" href="#/home">Back to home</a></div></section>`;
}

// ─── route ────────────────────────────────────────────────────────────────────
function route() {
  if (!me) return;
  if (role === "AGENT") renderAgent();
  else if (role === "MERCHANT") renderMerchant();
  else renderTechnician();
}

if (me) route();
