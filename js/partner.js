// Partner portal: agent and merchant read-only views.
// No /admin calls; every server string goes through esc().
import { api, requireRole, signOut } from "./api.js";
import { esc, money, fmtDate, skeletonRows, emptyState, statusChip, toast } from "./ui.js";

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

// ─── agent view ───────────────────────────────────────────────────────────────
async function renderAgent() {
  page().innerHTML = `<section class="wrap page-head"><h2>My profile</h2><div class="skeleton" style="height:260px;border-radius:18px"></div></section>`;
  try {
    const [profile, merchant, devices] = await Promise.all([
      api("/agents/me"),
      api("/agents/me/merchant").catch(() => null),
      api("/agents/me/devices").catch(() => []),
    ]);
    page().innerHTML = `
      <section class="wrap page-head">
        <h2>Agent dashboard</h2>
        <div class="card">
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
    const [merchant, agents, devices] = await Promise.all([
      api("/merchants/me"),
      api("/merchants/me/agents").catch(() => []),
      api("/merchants/me/devices").catch(() => []),
    ]);
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
