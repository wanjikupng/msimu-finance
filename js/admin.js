// Staff console: shell, overview, applications, customers, devices, catalog,
// payments, network, reports. Role-gated sidebar shows only permitted sections.
// Every string injected into HTML goes through esc(); no customer-only endpoints.
import { api, requireRole, signOut } from "./api.js";
import { esc, money, fmtDate, fmtDateTime, skeletonRows, emptyState, statusChip, toast, confirmDialog, openModal, openDrawer, setFieldErrors } from "./ui.js";

// ─── guards ───────────────────────────────────────────────────────────────────
const ALLOWED = ["ADMIN", "LOAN_OFFICER", "PAYMENTS_OPERATOR"];
const me = requireRole(ALLOWED);
const role = me?.role;
if (me) {
  document.getElementById("user-name").textContent = `${me.fullName || me.customerCode || "Staff"} · ${esc(role)}`;
  document.getElementById("sign-out").addEventListener("click", signOut);
}

// ─── sidebar sections by role ────────────────────────────────────────────────
const SECTIONS = [
  { id: "#/overview", label: "Overview", roles: ["ADMIN", "LOAN_OFFICER", "PAYMENTS_OPERATOR"] },
  { id: "#/applications", label: "Applications", roles: ["ADMIN", "LOAN_OFFICER"] },
  { id: "#/customers", label: "Customers", roles: ["ADMIN", "LOAN_OFFICER"] },
  { id: "#/devices", label: "Devices", roles: ["ADMIN"] },
  { id: "#/catalog", label: "Catalog", roles: ["ADMIN"] },
  { id: "#/payments", label: "Payments", roles: ["ADMIN", "PAYMENTS_OPERATOR"] },
  { id: "#/reconciliation", label: "Reconciliation", roles: ["ADMIN", "PAYMENTS_OPERATOR"] },
  { id: "#/network", label: "Network", roles: ["ADMIN"] },
  { id: "#/reports", label: "Reports", roles: ["ADMIN"] },
];

// ─── router ───────────────────────────────────────────────────────────────────
const routes = {
  "#/overview": renderOverview,
  "#/applications": renderApplications,
  "#/customers": renderCustomers,
  "#/devices": renderDevices,
  "#/catalog": renderCatalog,
  "#/payments": renderPayments,
  "#/reconciliation": renderReconciliation,
  "#/network": renderNetwork,
  "#/reports": renderReports,
};

function renderNav() {
  const current = (location.hash || "#/overview").split("?")[0];
  for (const id of ["tabbar", "rail"]) {
    const el = document.getElementById(id);
    if (!el) continue;
    if (id === "rail") {
      el.innerHTML = `<h3 style="font-size:0.85rem;color:var(--slate);margin:0 0 0.5rem;padding:0 0.5rem">Sections</h3>` +
        visibleSections().map((n) => `<a href="${n.id}" aria-current="${n.id === current ? "page" : "false"}">${esc(n.label)}</a>`).join("");
    } else {
      el.innerHTML = visibleSections().map((n) => `<a href="${n.id}" aria-current="${n.id === current ? "page" : "false"}">${esc(n.label)}</a>`).join("");
    }
  }
}

function visibleSections() {
  return SECTIONS.filter((s) => s.roles.includes(role));
}

// Deep-linkable routing: "#/payments?status=PENDING&open=PAY-1".
// Renderers accept { params } and honor `status`/`search` presets plus a
// one-shot `open` record id consumed on first render.
let pendingOpen = null;

function parseRoute() {
  const raw = location.hash || "#/overview";
  const [section, query] = raw.split("?");
  return { section, params: new URLSearchParams(query || "") };
}

function route() {
  if (!me) return;
  const { section, params } = parseRoute();
  const allowed = visibleSections().some((s) => s.id === section);
  const target = allowed ? section : "#/overview";
  if (!allowed && section !== "#/overview") {
    location.hash = "#/overview";
    return;
  }
  // Resolve a one-shot record id to auto-open: explicit ?open= wins,
  // otherwise keep an object set by a Needs-attention click for this tab.
  const fromParams = params.get("open");
  if (fromParams) {
    pendingOpen = fromParams;
  } else if (pendingOpen && typeof pendingOpen === "object") {
    pendingOpen = pendingOpen.section === target ? pendingOpen.open : null;
  }
  const fn = routes[target] || routes["#/overview"];
  fn({ status: params.get("status"), search: params.get("search") });
  renderNav();
}

// Renderers call this after data loads to auto-open a deep-linked record once.
function consumeOpen() {
  const open = pendingOpen;
  pendingOpen = null;
  return typeof open === "string" ? open : null;
}
window.addEventListener("hashchange", route);

const page = () => document.getElementById("page");

// ─── helpers ──────────────────────────────────────────────────────────────────
async function fetchWithFallback(path) {
  try { return await api(path); } catch { return null; }
}
function svgBars(data, height = 120) {
  const max = Math.max(1, ...data.map((d) => d.value));
  return `<div class="bars">${data.map((d) => `<div class="bar ${d.value === 0 ? "zero" : ""}" style="height:${(d.value / max) * height}px" title="${esc(d.label)}: ${esc(String(d.value))}"></div>`).join("")}</div><div class="chart-legend">${data.map((d) => `${esc(d.label)}: ${money(d.value)}`).join(" · ")}</div>`;
}

// ─── OVERVIEW ─────────────────────────────────────────────────────────────────
async function renderOverview() {
  page().innerHTML = `<section class="wrap page-head"><h2>Overview</h2><div class="kpis">${Array(6).fill('<div class="kpi"><div class="skeleton" style="height:2.5rem"></div></div>').join("")}</div><div class="grid-2 mt" style="gap:1rem"><div class="card"><div class="skeleton" style="height:200px"></div></div><div class="card"><div class="skeleton" style="height:200px"></div></div></div></section>`;
  try {
    const [apps, contracts, devices, payments, reconRecords] = await Promise.all([
      fetchWithFallback("/admin/financing/applications/summary"),
      fetchWithFallback("/loans"),
      fetchWithFallback("/admin/devices"),
      fetchWithFallback("/admin/payments"),
      fetchWithFallback("/admin/reconciliation/records"),
    ]);
    const activeContracts = (Array.isArray(contracts) ? contracts : []).filter((c) => c.loanStatus === "ACTIVE");
    const overdueContracts = (Array.isArray(contracts) ? contracts : []).filter((c) => c.loanStatus === "OVERDUE");
    const outstandingBalance = [...activeContracts, ...overdueContracts].reduce((s, c) => s + Number(c.amountRemaining || 0), 0);
    const overdueBalance = overdueContracts.reduce((s, c) => s + Number(c.amountRemaining || 0), 0);
    const riskPct = outstandingBalance > 0 ? Math.round((overdueBalance / outstandingBalance) * 100) : 0;

    // Payments: collected today and total from POSTED payments
    const allPayments = Array.isArray(payments) ? payments : [];
    const today = new Date().toDateString();
    const collectedToday = allPayments
      .filter((p) => p.status === "POSTED" && p.postedAt && new Date(p.postedAt).toDateString() === today)
      .reduce((s, p) => s + Number(p.amount || 0), 0);
    const collectedTotal = allPayments
      .filter((p) => p.status === "POSTED")
      .reduce((s, p) => s + Number(p.amount || 0), 0);

    // 14-day collections bar
    const days14 = [];
    for (let i = 13; i >= 0; i--) {
      const d = new Date(); d.setDate(d.getDate() - i);
      const ds = d.toDateString();
      const label = `${d.getDate()}/${d.getMonth() + 1}`;
      const val = allPayments
        .filter((p) => p.status === "POSTED" && p.postedAt && new Date(p.postedAt).toDateString() === ds)
        .reduce((s, p) => s + Number(p.amount || 0), 0);
      days14.push({ label, value: val });
    }

    // Needs attention — every item deep-links to the filtered tab and,
    // where it names a single record, auto-opens its detail drawer.
    const overdueItems = overdueContracts.map((c) => ({ text: `${esc(c.customerName || "")} · ${esc(c.productName || "")} ${esc(String(Math.ceil(Math.max(0, (new Date() - new Date(c.nextPaymentDueDate)) / 86400000))))} days late`, href: `#/customers`, open: c.customerId != null ? { section: "#/customers", open: String(c.customerId) } : null }));
    const pendingApps = Array.isArray(apps) ? [
      { text: `${apps.submitted + apps.underReview} applications pending review`, href: "#/applications?status=SUBMITTED", open: null },
    ] : [];
    const pendingPayments = allPayments
      .filter((p) => p.status === "PENDING")
      .slice(0, 3)
      .map((p) => ({ text: `${esc(p.transactionReference || "")} · ${money(p.amount)} pending`, href: "#/payments?status=PENDING", open: p.transactionReference ? { section: "#/payments", open: p.transactionReference } : null }));
    const exceptionItems = Array.isArray(reconRecords)
      ? reconRecords.filter((r) => r.status !== "MATCHED").slice(0, 3).map((r) => ({ text: `${esc(r.providerReference || r.internalReference || "")} · ${esc(String(r.status || "").replace(/_/g, " "))}`, href: `#/reconciliation${r.status ? `?status=${r.status}` : ""}`, open: r.id != null ? { section: "#/reconciliation", open: String(r.id) } : null }))
      : [];
    const repairItems = Array.isArray(devices)
      ? devices.filter((d) => d.status === "REPAIR").slice(0, 3).map((d) => ({ text: `${esc(d.deviceCode || "")} · ${esc(d.productName || "")} in repair`, href: "#/devices?status=REPAIR", open: d.id != null ? { section: "#/devices", open: String(d.id) } : null }))
      : [];

    page().innerHTML = `
      <section class="wrap page-head">
        <h2>Overview</h2>
        <div class="kpis">
          <div class="kpi"><div class="k-label">Active contracts</div><div class="k-value">${esc(String(activeContracts.length))}</div></div>
          <div class="kpi"><div class="k-label">Overdue contracts</div><div class="k-value">${esc(String(overdueContracts.length))}</div></div>
          <div class="kpi"><div class="k-label">Outstanding balance</div><div class="k-value">${money(outstandingBalance)}</div></div>
          <div class="kpi"><div class="k-label">Collected today</div><div class="k-value">${money(collectedToday)}</div></div>
          <div class="kpi"><div class="k-label">Total collected</div><div class="k-value">${money(collectedTotal)}</div></div>
          <div class="kpi"><div class="k-label">Portfolio at risk</div><div class="k-value">${esc(String(riskPct))}%</div></div>
        </div>
        <div class="grid-2 mt" style="gap:1rem">
          <div class="card">
            <h3>Applications funnel</h3>
            ${Array.isArray(apps) ? `<div class="funnel">
              <div class="funnel-step"><strong>${esc(String(apps.submitted))}</strong><span>Submitted</span></div>
              <div class="funnel-step"><strong>${esc(String(apps.underReview))}</strong><span>Review</span></div>
              <div class="funnel-step"><strong>${esc(String(apps.approved))}</strong><span>Approved</span></div>
              <div class="funnel-step"><strong>${esc(String(apps.rejected))}</strong><span>Rejected</span></div>
              <div class="funnel-step"><strong>${esc(String(apps.activeContracts))}</strong><span>Contracts</span></div>
            </div>` : '<p class="small">Could not load summary.</p>'}
          </div>
          <div class="card">
            <h3>Device fleet</h3>
            ${Array.isArray(devices) ? (() => {
              const counts = {};
              devices.forEach((d) => { const s = d.status || "UNKNOWN"; counts[s] = (counts[s] || 0) + 1; });
              const colors = { AVAILABLE: "#12B08A", ASSIGNED: "#4147D5", FINANCED: "#087A5D", OVERDUE: "#D2453D", PAID_OFF: "#4C6472", REPAIR: "#E39A0B", RESERVED: "#E39A0B", LOST: "#D2453D", RETIRED: "#4C6472" };
              const total = devices.length;
              const barHtml = `<div class="fleetbar">${Object.entries(counts).map(([s, c]) => `<span style="width:${(c / total) * 100}%;background:${colors[s] || '#4C6472'}" title="${esc(s)}: ${c}"></span>`).join("")}</div>`;
              const legend = Object.entries(counts).map(([s, c]) => `${esc(s)}: ${c}`).join(" · ");
              return `${barHtml}<div class="chart-legend mt">${esc(legend)}</div>`;
            })() : '<p class="small">Could not load fleet.</p>'}
          </div>
          <div class="card" style="grid-column:1/-1">
            <h3>Collections — last 14 days</h3>
            ${svgBars(days14)}
          </div>
          <div class="card" style="grid-column:1/-1">
            <h3>Needs attention</h3>
            <ul class="timeline">${[...overdueItems, ...pendingApps, ...pendingPayments, ...exceptionItems, ...repairItems].slice(0, 8).map((item, i) => `<li><a href="${esc(item.href)}" data-attn="${i}" style="color:var(--ink);font-weight:600">${esc(item.text)}</a></li>`).join("") || `<li>No items need attention right now.</li>`}</ul>
          </div>
        </div>
      </section>`;
      document.querySelectorAll("[data-attn]").forEach((el) => el.addEventListener("click", () => {
        const all = [...overdueItems, ...pendingApps, ...pendingPayments, ...exceptionItems, ...repairItems].slice(0, 8);
        const item = all[Number(el.dataset.attn)];
        if (item && item.open) {
          pendingOpen = item.open;
        }
      }));
  } catch (err) {
    page().innerHTML = `<section class="wrap page-head"><div class="form-error">${esc(err.message || "Unable to load overview.")}</div></section>`;
  }
}

// ─── APPLICATIONS ─────────────────────────────────────────────────────────────
async function renderApplications(opts = {}) {
  page().innerHTML = `<section class="wrap page-head"><h2>Applications</h2><div class="skeleton" style="height:300px;border-radius:18px"></div></section>`;
  try {
    const apps = await api("/admin/financing/applications");
    let filter = opts.status || "";
    function render() {
      const filtered = filter ? apps.filter((a) => a.status === filter) : apps;
      page().innerHTML = `
        <section class="wrap page-head">
          <h2>Applications</h2>
          <div class="toolbar">
            <div class="field"><label for="app-filter">Status</label><select id="app-filter"><option value="">All</option><option value="SUBMITTED" ${filter === "SUBMITTED" ? "selected" : ""}>Submitted</option><option value="UNDER_REVIEW" ${filter === "UNDER_REVIEW" ? "selected" : ""}>Under review</option><option value="APPROVED" ${filter === "APPROVED" ? "selected" : ""}>Approved</option><option value="REJECTED" ${filter === "REJECTED" ? "selected" : ""}>Rejected</option><option value="CANCELLED" ${filter === "CANCELLED" ? "selected" : ""}>Cancelled</option></select></div>
          </div>
          ${filtered.length === 0 ? emptyState("No applications", filter ? "Try a different filter." : "Applications will appear here once customers apply.") : `<div class="table-scroll"><table class="data"><thead><tr><th>Application</th><th>Customer</th><th>Device</th><th>Deposit</th><th>Financed</th><th>Status</th><th></th></tr></thead><tbody>${filtered.map((a) => `<tr class="clickable" data-view="${esc(String(a.id))}"><td>${esc(a.applicationNumber || "")}</td><td>${esc(a.customerName || "")}</td><td>${esc(a.productName || "")}</td><td>${money(a.depositAmount)}</td><td>${money(a.requestedPrincipal)}</td><td>${statusChip(a.status)}</td><td><button class="btn btn-ghost btn-sm" data-view="${esc(String(a.id))}">Review</button></td></tr>`).join("")}</tbody></table></div>`}
        </section>`;
      document.getElementById("app-filter").addEventListener("change", (e) => { filter = e.target.value; render(); });
      document.querySelectorAll("[data-view]").forEach((el) => el.addEventListener("click", () => viewApplication(apps.find((a) => String(a.id) === el.dataset.view))));
      const open = consumeOpen();
      if (open) viewApplication(apps.find((a) => String(a.id) === open));
    }
    function viewApplication(app) {
      if (!app) return;
      const canReview = app.status === "SUBMITTED";
      const canDecide = app.status === "UNDER_REVIEW";
      openDrawer(`Application ${esc(app.applicationNumber || app.id)}`, `
        <div class="kv"><dt>Customer</dt><dd>${esc(app.customerName || "")} (${esc(app.customerCode || "")})</dd></div>
        <div class="kv"><dt>Device</dt><dd>${esc(app.productName || "")} · ${esc(app.deviceCode || "")}</dd></div>
        <div class="kv"><dt>IMEI</dt><dd class="mono">${esc(app.imeiMasked || "—")}</dd></div>
        <div class="kv"><dt>Deposit</dt><dd>${money(app.depositAmount)}</dd></div>
        <div class="kv"><dt>Requested</dt><dd>${money(app.requestedPrincipal)}</dd></div>
        <div class="kv"><dt>Plan</dt><dd>${esc(app.paymentPlanName || "")}</dd></div>
        <div class="kv"><dt>Status</dt><dd>${statusChip(app.status)}</dd></div>
        ${app.reviewedByName ? `<div class="kv"><dt>Reviewer</dt><dd>${esc(app.reviewedByName)}</dd></div>` : ""}
        ${app.decisionReason ? `<div class="kv"><dt>Reason</dt><dd>${esc(app.decisionReason)}</dd></div>` : ""}
        <div class="btn-row" style="margin-top:1rem">
          ${canReview ? `<button class="btn btn-primary btn-sm" data-action="review">Start review</button>` : ""}
          ${canDecide ? `<button class="btn btn-primary btn-sm" data-action="approve">Approve</button>` : ""}
          ${canDecide ? `<button class="btn btn-danger btn-sm" data-action="reject">Reject</button>` : ""}
        </div>`);
      document.querySelector("[data-action='review']")?.addEventListener("click", async () => {
        const ok = await confirmDialog({ title: "Start review?", message: "This will move the application to UNDER_REVIEW status." });
        if (!ok) return;
        try { await api(`/admin/financing/applications/${app.id}/review`, { method: "POST" }); toast("Review started.", "success"); document.querySelector(".modal-close")?.click(); renderApplications(); } catch (e) { toast(e.message || "Failed.", "error"); }
      });
      document.querySelector("[data-action='approve']")?.addEventListener("click", async () => {
        const ok = await confirmDialog({ title: "Approve application?", message: "A financing contract will be created and the device will be handed to the customer." });
        if (!ok) return;
        try { await api(`/admin/financing/applications/${app.id}/approve`, { method: "POST" }); toast("Application approved.", "success"); document.querySelector(".modal-close")?.click(); renderApplications(); } catch (e) { toast(e.message || "Failed.", "error"); }
      });
      document.querySelector("[data-action='reject']")?.addEventListener("click", async () => {
        openModal({ title: "Reject application", bodyHTML: `
          <div class="field"><label for="reject-reason">Decision reason</label><textarea id="reject-reason" rows="3" placeholder="Required deposit not met…"></textarea></div>
          <div class="btn-row"><button class="btn btn-danger btn-sm" id="reject-confirm">Reject</button><button class="btn btn-secondary btn-sm" data-close>Cancel</button></div>` });
        document.getElementById("reject-confirm")?.addEventListener("click", async () => {
          const reason = document.getElementById("reject-reason")?.value.trim();
          if (!reason) { toast("A reason is required.", "error"); return; }
          try { await api(`/admin/financing/applications/${app.id}/reject`, { method: "POST", body: { decisionReason: reason } }); toast("Application rejected.", "success"); document.querySelector(".modal-close")?.click(); renderApplications(); } catch (e) { toast(e.message || "Failed.", "error"); }
        });
      });
    }
    render();
  } catch (err) {
    page().innerHTML = `<section class="wrap page-head"><div class="form-error">${esc(err.message || "Unable to load applications.")}</div></section>`;
  }
}

// ─── CUSTOMERS (d2 placeholder) ──────────────────────────────────────────────
async function renderCustomers(opts = {}) {
  page().innerHTML = `<section class="wrap page-head"><h2>Customers</h2><div class="skeleton" style="height:300px;border-radius:18px"></div></section>`;
  try {
    const users = await api("/admin/users?role=CUSTOMER");
    let search = opts.search || "";
    function render() {
      const filtered = search ? users.filter((u) => (u.fullName || "").toLowerCase().includes(search) || (u.email || "").toLowerCase().includes(search) || (u.customerCode || "").toLowerCase().includes(search)) : users;
      page().innerHTML = `
        <section class="wrap page-head">
          <h2>Customers</h2>
          <div class="toolbar">
            <div class="field"><label for="cust-search">Search</label><input id="cust-search" type="text" placeholder="Name, email or code" value="${esc(search)}" /></div>
            <button class="btn btn-primary btn-sm" id="add-customer-btn">Register customer</button>
          </div>
          ${filtered.length === 0 ? emptyState("No customers found", search ? "Try a different search." : "Register the first customer.") : `<div class="table-scroll"><table class="data"><thead><tr><th>Code</th><th>Name</th><th>Email</th><th>Phone</th><th>Joined</th><th></th></tr></thead><tbody>${filtered.map((u) => `<tr class="clickable" data-view="${esc(String(u.id))}"><td class="mono">${esc(u.customerCode || "")}</td><td>${esc(u.fullName || "")}</td><td>${esc(u.email || "")}</td><td>${esc(u.phoneNumber || "—")}</td><td>${esc(fmtDate(u.createdAt))}</td><td><button class="btn btn-ghost btn-sm" data-view="${esc(String(u.id))}">View</button></td></tr>`).join("")}</tbody></table></div>`}
        </section>`;
      document.getElementById("cust-search").addEventListener("input", (e) => { search = e.target.value.toLowerCase().trim(); render(); });
      document.getElementById("add-customer-btn").addEventListener("click", () => openRegisterCustomerModal());
      document.querySelectorAll("[data-view]").forEach((el) => el.addEventListener("click", () => viewCustomer(users.find((u) => String(u.id) === el.dataset.view))));
      const open = consumeOpen();
      if (open) viewCustomer(users.find((u) => String(u.id) === open));
    }
    function openRegisterCustomerModal() {
      openModal({ title: "Register customer", bodyHTML: `
        <form id="cust-form" novalidate>
          <div class="field"><label for="c-name">Full name</label><input id="c-name" data-field="fullName" type="text" required /></div>
          <div class="field"><label for="c-email">Email</label><input id="c-email" data-field="email" type="email" required /></div>
          <div class="field"><label for="c-phone">Phone</label><input id="c-phone" data-field="phoneNumber" type="tel" placeholder="0712 345 678" /></div>
          <div class="field"><label for="c-pass">Password</label><input id="c-pass" data-field="password" type="password" required />
            <ul class="checklist" id="c-checklist"><li data-rule="length">8–100 chars</li><li data-rule="letter">One letter</li><li data-rule="digit">One digit</li></ul>
          </div>
          <div class="btn-row"><button class="btn btn-primary" type="submit">Create account</button></div>
        </form>` });
      const form = document.getElementById("cust-form");
      const passInput = document.getElementById("c-pass");
      const checklist = document.getElementById("c-checklist");
      const updateChecklist = () => { const p = passInput.value; checklist.querySelectorAll("[data-rule]").forEach((li) => { const rule = li.dataset.rule; const ok = rule === "length" ? p.length >= 8 && p.length <= 100 : rule === "letter" ? /[A-Za-z]/.test(p) : /\d/.test(p); li.classList.toggle("ok", ok); }); };
      passInput.addEventListener("input", updateChecklist);
      form.addEventListener("submit", async (e) => {
        e.preventDefault();
        try {
          await api("/admin/users", { method: "POST", body: { fullName: document.getElementById("c-name").value.trim(), email: document.getElementById("c-email").value.trim(), phoneNumber: document.getElementById("c-phone").value.trim(), password: passInput.value, role: "CUSTOMER" } });
          toast("Customer created.", "success");
          document.querySelector(".modal-close")?.click();
          renderCustomers();
        } catch (err) { setFieldErrors(form, err.fieldErrors); toast(err.message || "Failed.", "error"); }
      });
    }
    async function viewCustomer(user) {
      if (!user) return;
      openDrawer(`Customer ${esc(user.fullName || "")}`, '<div class="skeleton" style="height:200px"></div>');
      const setBody = (html) => { const b = document.querySelector(".drawer-body"); if (b) b.innerHTML = html; };
      try {
        const [dash, apps, devices, payments] = await Promise.all([
          api(`/users/${user.id}/dashboard`),
          fetchWithFallback("/admin/financing/applications"),
          role === "ADMIN" ? fetchWithFallback("/admin/devices") : Promise.resolve(null),
          (role === "ADMIN" || role === "PAYMENTS_OPERATOR") ? fetchWithFallback(`/admin/payments?customerId=${user.id}`) : Promise.resolve(null),
        ]);
        const myApps = Array.isArray(apps) ? apps.filter((a) => a.customerCode && user.customerCode && a.customerCode === user.customerCode) : [];
        const myDevices = Array.isArray(devices) ? devices.filter((d) => d.customerCode && user.customerCode && d.customerCode === user.customerCode) : null;
        const myPayments = Array.isArray(payments) ? payments : null;
        let tab = "profile";
        function render() {
          const tabs = [
            ["profile", "Profile"],
            ["contracts", `Contracts (${dash.deviceLoans?.length ?? 0})`],
            ...(myDevices !== null ? [["devices", `Devices (${myDevices.length})`]] : []),
            ["applications", `Applications (${myApps.length})`],
            ...(myPayments !== null ? [["payments", `Payments (${myPayments.length})`]] : []),
          ];
          let body = `<div class="tabs" role="tablist">${tabs.map(([id, label]) => `<button role="tab" aria-selected="${tab === id}" data-ctab="${id}">${esc(label)}</button>`).join("")}</div><div id="ctab-body"></div>`;
          setBody(body);
          const slot = document.getElementById("ctab-body");
          if (tab === "profile") {
            slot.innerHTML = `
              <div class="kv"><dt>Code</dt><dd class="mono">${esc(user.customerCode || "")}</dd></div>
              <div class="kv"><dt>Email</dt><dd>${esc(user.email || "")}</dd></div>
              <div class="kv"><dt>Phone</dt><dd>${esc(user.phoneNumber || "—")}</dd></div>
              <div class="kv"><dt>Joined</dt><dd>${esc(fmtDate(user.createdAt))}</dd></div>
              <div class="kv"><dt>Balance</dt><dd>${money(dash.totalBalance || 0)}</dd></div>
              <div class="kv"><dt>Paid</dt><dd>${money(dash.totalPaid || 0)}</dd></div>
              ${dash.paymentTimeline?.length ? `<h3 class="mt">Recent activity</h3><ul class="timeline">${dash.paymentTimeline.slice(0, 5).map((e) => `<li><div class="t-main">${esc(fmtDate(e.date))} · ${statusChip(e.status)}</div><div class="t-amt">${money(e.amount)}</div></li>`).join("")}</ul>` : ""}`;
          } else if (tab === "contracts") {
            slot.innerHTML = dash.deviceLoans?.length ? `<div class="table-scroll"><table class="data"><thead><tr><th>Contract</th><th>Device</th><th>Remaining</th><th>Status</th></tr></thead><tbody>${dash.deviceLoans.map((l) => `<tr><td class="mono">${esc(l.contractNumber || "")}</td><td>${esc(l.productName || "")}</td><td>${money(l.amountRemaining)}</td><td>${statusChip(l.loanStatus)}</td></tr>`).join("")}</tbody></table></div>` : `<p class="small">No contracts.</p>`;
          } else if (tab === "devices") {
            slot.innerHTML = myDevices.length ? `<div class="table-scroll"><table class="data"><thead><tr><th>Code</th><th>Product</th><th>IMEI</th><th>Status</th></tr></thead><tbody>${myDevices.map((d) => `<tr><td class="mono">${esc(d.deviceCode || "")}</td><td>${esc(d.productName || "")}</td><td class="mono">${esc(d.imei || "—")}</td><td>${statusChip(d.status)}</td></tr>`).join("")}</tbody></table></div>` : `<p class="small">No devices assigned.</p>`;
          } else if (tab === "applications") {
            slot.innerHTML = myApps.length ? `<div class="table-scroll"><table class="data"><thead><tr><th>Application</th><th>Device</th><th>Financed</th><th>Status</th></tr></thead><tbody>${myApps.map((a) => `<tr><td class="mono">${esc(a.applicationNumber || "")}</td><td>${esc(a.productName || "")}</td><td>${money(a.requestedPrincipal)}</td><td>${statusChip(a.status)}</td></tr>`).join("")}</tbody></table></div>` : `<p class="small">No applications.</p>`;
          } else if (tab === "payments") {
            slot.innerHTML = myPayments.length ? `<ul class="timeline">${myPayments.slice(0, 10).map((p) => `<li><div class="t-main"><div class="mono">${esc(p.transactionReference || "")} · ${statusChip(p.status)}</div><div class="t-sub">${esc(fmtDateTime(p.requestedAt))}</div></div><div class="t-amt">${money(p.amount)}</div></li>`).join("")}</ul>` : `<p class="small">No payments.</p>`;
          }
          slot.parentElement.querySelectorAll("[data-ctab]").forEach((btn) => btn.addEventListener("click", () => { tab = btn.dataset.ctab; render(); }));
        }
        render();
      } catch { setBody('<p class="small">Could not load dashboard.</p>'); }
    }
    render();
  } catch (err) {
    page().innerHTML = `<section class="wrap page-head"><div class="form-error">${esc(err.message || "Unable to load customers.")}</div></section>`;
  }
}

// ─── DEVICES (d2) ─────────────────────────────────────────────────────────────
async function renderDevices(opts = {}) {
  page().innerHTML = `<section class="wrap page-head"><h2>Devices</h2><div class="skeleton" style="height:300px;border-radius:18px"></div></section>`;
  try {
    const [devices, products] = await Promise.all([api("/admin/devices"), api("/products")]);
    let statusF = opts.status || "", productF = "";
    function render() {
      const filtered = devices.filter((d) => (!statusF || d.status === statusF) && (!productF || String(d.productId) === productF));
      page().innerHTML = `
        <section class="wrap page-head">
          <h2>Devices</h2>
          <div class="toolbar">
            <div class="field"><label for="dev-status">Status</label><select id="dev-status"><option value="">All</option>${["AVAILABLE","RESERVED","ASSIGNED","FINANCED","OVERDUE","PAID_OFF","REPAIR","LOST","RETIRED"].map((s) => `<option value="${s}" ${statusF === s ? "selected" : ""}>${s}</option>`).join("")}</select></div>
            <div class="field"><label for="dev-product">Product</label><select id="dev-product"><option value="">All</option>${products.map((p) => `<option value="${p.id}" ${productF === String(p.id) ? "selected" : ""}>${esc(p.name)}</option>`).join("")}</select></div>
            <button class="btn btn-primary btn-sm" id="add-device-btn">Add device</button>
          </div>
          ${filtered.length === 0 ? emptyState("No devices match", "Try different filters.") : `<div class="table-scroll"><table class="data"><thead><tr><th>Code</th><th>Product</th><th>IMEI</th><th>Status</th><th>Merchant</th><th>Customer</th><th></th></tr></thead><tbody>${filtered.map((d) => `<tr class="clickable" data-view="${esc(String(d.id))}"><td class="mono">${esc(d.deviceCode || "")}</td><td>${esc(d.productName || "")}</td><td class="mono">${esc(d.imei || "—")}</td><td>${statusChip(d.status)}</td><td>${esc(d.merchantName || "—")}</td><td>${esc(d.customerName || "—")}</td><td><button class="btn btn-ghost btn-sm" data-view="${esc(String(d.id))}">View</button></td></tr>`).join("")}</tbody></table></div>`}
        </section>`;
      document.getElementById("dev-status").addEventListener("change", (e) => { statusF = e.target.value; render(); });
      document.getElementById("dev-product").addEventListener("change", (e) => { productF = e.target.value; render(); });
      document.getElementById("add-device-btn").addEventListener("click", () => openAddDeviceModal());
      document.querySelectorAll("[data-view]").forEach((el) => el.addEventListener("click", () => viewDevice(devices.find((d) => String(d.id) === el.dataset.view))));
      const open = consumeOpen();
      if (open) viewDevice(devices.find((d) => String(d.id) === open));
    }
    function openAddDeviceModal() {
      openModal({ title: "Add device", bodyHTML: `
        <form id="dev-form" novalidate>
          <div class="field"><label for="d-product">Product</label><select id="d-product">${products.map((p) => `<option value="${p.id}">${esc(p.name)}</option>`).join("")}</select></div>
          <div class="field"><label for="d-imei">IMEI</label><input id="d-imei" data-field="imei" type="text" pattern="\\d{10,16}" placeholder="10–16 digits" /></div>
          <div class="field"><label for="d-serial">Serial number</label><input id="d-serial" data-field="serialNumber" type="text" pattern="[A-Za-z0-9\\-]+" placeholder="Letters, digits, dashes" /></div>
          <div class="btn-row"><button class="btn btn-primary" type="submit">Add</button></div>
        </form>` });
      document.getElementById("dev-form").addEventListener("submit", async (e) => {
        e.preventDefault();
        try {
          await api("/admin/devices", { method: "POST", body: { productId: Number(document.getElementById("d-product").value), imei: document.getElementById("d-imei").value.trim() || undefined, serialNumber: document.getElementById("d-serial").value.trim() || undefined } });
          toast("Device added.", "success");
          document.querySelector(".modal-close")?.click();
          renderDevices();
        } catch (err) { setFieldErrors(document.getElementById("dev-form"), err.fieldErrors); toast(err.message || "Failed.", "error"); }
      });
    }
    function viewDevice(device) {
      if (!device) return;
      openDrawer(`Device ${esc(device.deviceCode || "")}`, `
        <div class="kv"><dt>Product</dt><dd>${esc(device.productName || "")}</dd></div>
        <div class="kv"><dt>IMEI</dt><dd class="mono">${esc(device.imei || "—")}</dd></div>
        <div class="kv"><dt>Serial</dt><dd class="mono">${esc(device.serialNumber || "—")}</dd></div>
        <div class="kv"><dt>Status</dt><dd>${statusChip(device.status)}</dd></div>
        <div class="kv"><dt>Merchant</dt><dd>${esc(device.merchantName || "—")}</dd></div>
        <div class="kv"><dt>Agent</dt><dd>${esc(device.agentName || "—")}</dd></div>
        <div class="kv"><dt>Customer</dt><dd>${esc(device.customerName || "—")}</dd></div>
        <div class="btn-row mt">
          ${device.status === "AVAILABLE" || device.status === "RESERVED" || device.status === "REPAIR" ? `<button class="btn btn-primary btn-sm" data-action="release">Release to warehouse</button>` : ""}
          <button class="btn btn-secondary btn-sm" data-action="status">Change status</button>
        </div>`);
      document.querySelector("[data-action='release']")?.addEventListener("click", async () => {
        const ok = await confirmDialog({ title: "Release device?", message: "This will return the device to AVAILABLE inventory." });
        if (!ok) return;
        try { await api(`/admin/devices/${device.id}/release`, { method: "POST" }); toast("Device released.", "success"); document.querySelector(".modal-close")?.click(); renderDevices(); } catch (e) { toast(e.message || e.detail || "Failed.", "error"); }
      });
      document.querySelector("[data-action='status']")?.addEventListener("click", () => {
        openModal({ title: "Change status", bodyHTML: `
          <div class="field"><label for="new-status">New status</label><select id="new-status">${["AVAILABLE","RESERVED","ASSIGNED","FINANCED","OVERDUE","PAID_OFF","REPAIR","LOST","RETIRED"].map((s) => `<option value="${s}" ${s === device.status ? "selected" : ""}>${s}</option>`).join("")}</select></div>
          <div class="btn-row"><button class="btn btn-primary btn-sm" id="status-confirm">Apply</button></div>` });
        document.getElementById("status-confirm")?.addEventListener("click", async () => {
          try { await api(`/admin/devices/${device.id}/status`, { method: "PATCH", body: { status: document.getElementById("new-status").value } }); toast("Status updated.", "success"); document.querySelector(".modal-close")?.click(); renderDevices(); } catch (e) { toast(e.message || "Transition refused.", "error"); }
        });
      });
    }
    render();
  } catch (err) {
    page().innerHTML = `<section class="wrap page-head"><div class="form-error">${esc(err.message || "Unable to load devices.")}</div></section>`;
  }
}

// ─── CATALOG (d2) ────────────────────────────────────────────────────────────
async function renderCatalog() {
  page().innerHTML = `<section class="wrap page-head"><h2>Catalog</h2><div class="skeleton" style="height:300px;border-radius:18px"></div></section>`;
  try {
    const [products, plans, modes] = await Promise.all([api("/products"), api("/payment-plans"), api("/payment-modes")]);
    page().innerHTML = `
      <section class="wrap page-head">
        <h2>Catalog</h2>
        <div class="grid-2">
          <div class="card"><h3>Products</h3><button class="btn btn-primary btn-sm mt" id="add-product-btn">Add product</button><div class="table-scroll mt"><table class="data"><thead><tr><th>Name</th><th>Price</th><th>Deposit</th><th>Daily</th></tr></thead><tbody>${products.map((p) => `<tr><td>${esc(p.name)}</td><td>${money(p.fullPrice)}</td><td>${money(p.downPayment)}</td><td>${money(p.dailyAmount)}</td></tr>`).join("")}</tbody></table></div></div>
          <div class="card"><h3>Payment plans</h3><button class="btn btn-primary btn-sm mt" id="add-plan-btn">Add plan</button><div class="table-scroll mt"><table class="data"><thead><tr><th>Name</th><th>Days</th><th>Daily</th></tr></thead><tbody>${plans.map((p) => `<tr><td>${esc(p.planName)}</td><td>${esc(String(p.durationDays))}</td><td>${money(p.dailyAmount)}</td></tr>`).join("")}</tbody></table></div></div>
          <div class="card"><h3>Payment modes</h3><button class="btn btn-primary btn-sm mt" id="add-mode-btn">Add mode</button><div class="table-scroll mt"><table class="data"><thead><tr><th>Name</th><th>Description</th></tr></thead><tbody>${modes.map((m) => `<tr><td>${esc(m.modeName)}</td><td>${esc(m.description || "—")}</td></tr>`).join("")}</tbody></table></div></div>
        </div>
      </section>`;
    document.getElementById("add-product-btn").addEventListener("click", () => {
      openModal({ title: "Add product", bodyHTML: `<form id="prod-form" novalidate>
        <div class="field"><label>Name</label><input id="p-name" type="text" required /></div>
        <div class="field"><label>Description</label><input id="p-desc" type="text" /></div>
        <div class="grid-2"><div class="field"><label>Price (KES)</label><input id="p-price" type="number" min="1" required /></div><div class="field"><label>Deposit</label><input id="p-deposit" type="number" min="0" required /></div></div>
        <div class="field"><label>Daily</label><input id="p-daily" type="number" min="1" required /></div>
        <div class="btn-row"><button class="btn btn-primary" type="submit">Add</button></div></form>` });
      document.getElementById("prod-form").addEventListener("submit", async (e) => {
        e.preventDefault();
        try { await api("/products", { method: "POST", body: { name: document.getElementById("p-name").value.trim(), description: document.getElementById("p-desc").value.trim(), fullPrice: Number(document.getElementById("p-price").value), downPayment: Number(document.getElementById("p-deposit").value), dailyAmount: Number(document.getElementById("p-daily").value), paymentPlanName: null, paymentMode: null } }); toast("Product added.", "success"); document.querySelector(".modal-close")?.click(); renderCatalog(); } catch (err) { toast(err.message || "Failed.", "error"); }
      });
    });
    document.getElementById("add-plan-btn").addEventListener("click", () => {
      openModal({ title: "Add payment plan", bodyHTML: `<form id="plan-form" novalidate>
        <div class="field"><label>Name</label><input id="pl-name" type="text" required /></div>
        <div class="grid-2"><div class="field"><label>Days</label><input id="pl-days" type="number" min="1" required /></div><div class="field"><label>Daily (KES)</label><input id="pl-daily" type="number" min="1" required /></div></div>
        <div class="field"><label>Deposit</label><input id="pl-deposit" type="number" min="0" required /></div>
        <div class="btn-row"><button class="btn btn-primary" type="submit">Add</button></div></form>` });
      document.getElementById("plan-form").addEventListener("submit", async (e) => {
        e.preventDefault();
        try { await api("/payment-plans", { method: "POST", body: { planName: document.getElementById("pl-name").value.trim(), durationDays: Number(document.getElementById("pl-days").value), dailyAmount: Number(document.getElementById("pl-daily").value), depositAmount: Number(document.getElementById("pl-deposit").value) } }); toast("Plan added.", "success"); document.querySelector(".modal-close")?.click(); renderCatalog(); } catch (err) { toast(err.message || "Failed.", "error"); }
      });
    });
    document.getElementById("add-mode-btn").addEventListener("click", () => {
      openModal({ title: "Add payment mode", bodyHTML: `<form id="mode-form" novalidate>
        <div class="field"><label>Name</label><input id="m-name" type="text" required /></div>
        <div class="field"><label>Description</label><input id="m-desc" type="text" /></div>
        <div class="btn-row"><button class="btn btn-primary" type="submit">Add</button></div></form>` });
      document.getElementById("mode-form").addEventListener("submit", async (e) => {
        e.preventDefault();
        try { await api("/payment-modes", { method: "POST", body: { modeName: document.getElementById("m-name").value.trim(), description: document.getElementById("m-desc").value.trim() } }); toast("Mode added.", "success"); document.querySelector(".modal-close")?.click(); renderCatalog(); } catch (err) { toast(err.message || "Failed.", "error"); }
      });
    });
  } catch (err) {
    page().innerHTML = `<section class="wrap page-head"><div class="form-error">${esc(err.message || "Unable to load catalog.")}</div></section>`;
  }
}

// ─── PAYMENTS (d3) ────────────────────────────────────────────────────────────
async function renderPayments(opts = {}) {
  page().innerHTML = `<section class="wrap page-head"><h2>Payments</h2><div class="skeleton" style="height:300px;border-radius:18px"></div></section>`;
  try {
    let statusF = opts.status || "";
    async function load() { return api(`/admin/payments${statusF ? `?status=${statusF}` : ""}`); }
    let txns = await load();
    function render() {
      page().innerHTML = `
        <section class="wrap page-head">
          <h2>Payments</h2>
          <p class="small">Only POSTED payments change contract balances. PENDING payments need a provider result first, then posting. POSTED payments can be reversed with a reason.</p>
          <div class="toolbar"><div class="field"><label for="pay-filter">Status</label><select id="pay-filter"><option value="">All</option><option value="PENDING" ${statusF === "PENDING" ? "selected" : ""}>Pending</option><option value="SUCCESS" ${statusF === "SUCCESS" ? "selected" : ""}>Success</option><option value="POSTED" ${statusF === "POSTED" ? "selected" : ""}>Posted</option><option value="FAILED" ${statusF === "FAILED" ? "selected" : ""}>Failed</option><option value="REVERSED" ${statusF === "REVERSED" ? "selected" : ""}>Reversed</option></select></div></div>
          ${txns.length === 0 ? emptyState("No payments", "Transactions will appear here.") : `<div class="table-scroll"><table class="data"><thead><tr><th>Reference</th><th>Customer</th><th>Amount</th><th>Channel</th><th>Status</th><th>Provider ref</th><th></th></tr></thead><tbody>${txns.map((tx) => `<tr class="clickable" data-tx="${esc(tx.transactionReference || "")}"><td class="mono">${esc(tx.transactionReference || "")}</td><td>${esc(tx.customerName || "")}</td><td>${money(tx.amount)}</td><td>${esc(tx.channel || "")}</td><td>${statusChip(tx.status)}</td><td class="mono">${esc(tx.providerReference || "—")}</td><td class="mono">${tx.status === "POSTED" ? esc(tx.receiptNumber || "") : `<span class="btn-row" style="margin:0">${tx.status === "PENDING" && role === "ADMIN" ? `<button class="btn btn-ghost btn-sm" data-resolve="${esc(tx.transactionReference || "")}">Resolve</button>` : ""}${(tx.status === "PENDING" || tx.status === "SUCCESS") && (role === "ADMIN" || role === "PAYMENTS_OPERATOR") ? `<button class="btn btn-ghost btn-sm" data-post="${esc(tx.transactionReference || "")}">Post</button>` : ""}${tx.status === "POSTED" && (role === "ADMIN" || role === "PAYMENTS_OPERATOR") ? `<button class="btn btn-ghost btn-sm" data-reverse="${esc(tx.transactionReference || "")}">Reverse</button>` : ""}</span>`}</td></tr>`).join("")}</tbody></table></div>`}
        </section>`;
      document.getElementById("pay-filter").addEventListener("change", async (e) => { statusF = e.target.value; txns = await load(); render(); });
      document.querySelectorAll("tr[data-tx]").forEach((row) => row.addEventListener("click", (e) => {
        if (e.target.closest("button")) return;
        viewTransaction(row.dataset.tx);
      }));
      document.querySelectorAll("[data-post]").forEach((btn) => btn.addEventListener("click", () => postTx(btn.dataset.post)));
      document.querySelectorAll("[data-resolve]").forEach((btn) => btn.addEventListener("click", () => resolveTx(btn.dataset.resolve)));
      document.querySelectorAll("[data-reverse]").forEach((btn) => btn.addEventListener("click", () => reverseTx(btn.dataset.reverse)));
      const open = consumeOpen();
      if (open) viewTransaction(open);
    }
    async function postTx(reference) {
      if (!reference) return;
      const ok = await confirmDialog({ title: "Post payment?", message: "This applies the amount to the contract balance exactly once." });
      if (!ok) return;
      try { await api(`/payments/${reference}/post`, { method: "POST" }); toast("Payment posted.", "success"); document.querySelector(".modal-close")?.click(); txns = await load(); render(); } catch (e) { toast(e.message || "Failed.", "error"); }
    }
    async function resolveTx(reference) {
      if (!reference) return;
      openModal({ title: "Resolve pending payment", bodyHTML: `
        <p class="small">Record what the provider reported for <span class="mono">${esc(reference)}</span>. This changes the transaction state only — posting stays a separate step.</p>
        <div class="field"><label for="resolve-outcome">Provider outcome</label><select id="resolve-outcome"><option value="SUCCESS">SUCCESS</option><option value="FAILED">FAILED</option></select></div>
        <div class="field"><label for="resolve-reason">Failure reason (required for FAILED)</label><input id="resolve-reason" type="text" placeholder="e.g. provider timeout" /></div>
        <div class="btn-row"><button class="btn btn-primary btn-sm" id="resolve-confirm">Apply outcome</button></div>` });
      document.getElementById("resolve-confirm")?.addEventListener("click", async () => {
        const outcome = document.getElementById("resolve-outcome")?.value;
        const failureReason = document.getElementById("resolve-reason")?.value.trim() || undefined;
        try { await api(`/payments/${reference}/provider-result`, { method: "POST", body: { outcome, failureReason } }); toast("Provider outcome recorded.", "success"); document.querySelector(".modal-close")?.click(); txns = await load(); render(); } catch (e) { toast(e.message || "Failed.", "error"); }
      });
    }
    async function reverseTx(reference) {
      if (!reference) return;
      openModal({ title: "Reverse posted payment", bodyHTML: `
        <p class="small">Reversing <span class="mono">${esc(reference)}</span> rolls its allocations back and restores the contract schedule. The receipt stays as history. This cannot be undone.</p>
        <div class="field"><label for="reverse-reason">Reversal reason</label><input id="reverse-reason" type="text" placeholder="e.g. duplicate counter entry" /></div>
        <div class="btn-row"><button class="btn btn-danger btn-sm" id="reverse-confirm">Reverse payment</button></div>` });
      document.getElementById("reverse-confirm")?.addEventListener("click", async () => {
        const reason = document.getElementById("reverse-reason")?.value.trim();
        if (!reason) { toast("A reversal reason is required.", "error"); return; }
        const ok = await confirmDialog({ title: "Reverse this payment?", message: "The contract balance and schedule will be restored.", danger: true });
        if (!ok) return;
        try { await api(`/payments/${reference}/reverse`, { method: "POST", body: { reason } }); toast("Payment reversed.", "success"); document.querySelector(".modal-close")?.click(); txns = await load(); render(); } catch (e) { toast(e.message || "Failed.", "error"); }
      });
    }
    async function viewTransaction(reference) {
      if (!reference) return;
      openDrawer(`Payment ${reference}`, '<div class="skeleton" style="height:220px"></div>');
      const setBody = (html) => { const b = document.querySelector(".drawer-body"); if (b) b.innerHTML = html; };
      try {
        const [tx, allocations, recon] = await Promise.all([
          api(`/payments/${encodeURIComponent(reference)}`),
          fetchWithFallback(`/payments/${encodeURIComponent(reference)}/allocations`),
          fetchWithFallback("/admin/reconciliation/records"),
        ]);
        const receipt = (tx.status === "POSTED" || tx.status === "REVERSED")
          ? await fetchWithFallback(`/payments/${encodeURIComponent(reference)}/receipt`)
          : null;
        const reconMatch = Array.isArray(recon)
          ? recon.find((r) => r.internalReference === tx.transactionReference) || null
          : null;
        const lifecycle = [
          ["Requested", tx.requestedAt, true],
          ["Provider confirmed", tx.completedAt, !!tx.completedAt],
          ["Posted", tx.postedAt, !!tx.postedAt],
        ];
        setBody(`
          <div style="margin-bottom:0.75rem">${statusChip(tx.status)}</div>
          <div class="kv"><dt>Amount</dt><dd>${money(tx.amount)}</dd></div>
          <div class="kv"><dt>Customer</dt><dd>${esc(tx.customerName || "")} <span class="mono">${esc(tx.customerCode || "")}</span></dd></div>
          <div class="kv"><dt>Contract</dt><dd class="mono">${esc(tx.contractNumber || "")}</dd></div>
          <div class="kv"><dt>Channel</dt><dd>${esc(tx.channel || "")}</dd></div>
          <div class="kv"><dt>Provider ref</dt><dd class="mono">${esc(tx.providerReference || "—")}</dd></div>
          ${tx.failureReason ? `<div class="kv"><dt>Failure reason</dt><dd>${esc(tx.failureReason)}</dd></div>` : ""}
          ${tx.status === "REVERSED" ? `<div class="kv"><dt>Reversal</dt><dd>${esc(tx.reversalReason || "reversed")}</dd></div>` : ""}
          <h3 class="mt">Lifecycle</h3>
          <ul class="timeline">${lifecycle.map(([label, when, done]) => `<li><div class="t-main">${esc(label)}</div><div class="t-amt">${done ? esc(fmtDateTime(when)) : "—"}</div></li>`).join("")}</ul>
          <h3 class="mt">Allocations</h3>
          ${Array.isArray(allocations) && allocations.length ? `<div class="table-scroll"><table class="data"><thead><tr><th>Day</th><th>Amount</th><th>Status</th></tr></thead><tbody>${allocations.map((a) => `<tr><td>${esc(fmtDate(a.allocationDate))}</td><td>${money(a.amount)}</td><td>${statusChip(a.status)}</td></tr>`).join("")}</tbody></table></div>` : `<p class="small">No allocations yet — nothing has been posted.</p>`}
          <h3 class="mt">Reconciliation</h3>
          ${reconMatch ? `<div class="kv"><dt>Status</dt><dd>${statusChip(reconMatch.status)}</dd></div>${reconMatch.differenceAmount != null ? `<div class="kv"><dt>Difference</dt><dd>${money(reconMatch.differenceAmount)}</dd></div>` : ""}${reconMatch.reason ? `<div class="kv"><dt>Reason</dt><dd>${esc(reconMatch.reason)}</dd></div>` : ""}` : `<p class="small">Not yet reconciled against the provider feed.</p>`}
          ${receipt ? `<h3 class="mt">Receipt</h3><div class="kv"><dt>Receipt</dt><dd class="mono">${esc(receipt.receiptNumber || "")}</dd></div><div class="kv"><dt>Balance after</dt><dd>${money(receipt.remainingBalance)}</dd></div>` : ""}
          <div class="btn-row" style="margin-top:1rem">
            ${(tx.status === "PENDING" || tx.status === "SUCCESS") && (role === "ADMIN" || role === "PAYMENTS_OPERATOR") ? `<button class="btn btn-primary btn-sm" data-drawer-post>Post</button>` : ""}
            ${tx.status === "PENDING" && role === "ADMIN" ? `<button class="btn btn-secondary btn-sm" data-drawer-resolve>Resolve</button>` : ""}
            ${tx.status === "POSTED" && (role === "ADMIN" || role === "PAYMENTS_OPERATOR") ? `<button class="btn btn-danger btn-sm" data-drawer-reverse>Reverse</button>` : ""}
            <button class="btn btn-secondary btn-sm" data-drawer-customer>View customer</button>
          </div>`);
        document.querySelector("[data-drawer-post]")?.addEventListener("click", () => { document.querySelector(".modal-close")?.click(); postTx(tx.transactionReference); });
        document.querySelector("[data-drawer-resolve]")?.addEventListener("click", () => { document.querySelector(".modal-close")?.click(); resolveTx(tx.transactionReference); });
        document.querySelector("[data-drawer-reverse]")?.addEventListener("click", () => { document.querySelector(".modal-close")?.click(); reverseTx(tx.transactionReference); });
        document.querySelector("[data-drawer-customer]")?.addEventListener("click", async () => {
          document.querySelector(".modal-close")?.click();
          try {
            const users = await api("/admin/users?role=CUSTOMER");
            const user = users.find((u) => u.customerCode && tx.customerCode && u.customerCode === tx.customerCode);
            if (user) { location.hash = "#/customers"; pendingOpen = String(user.id); route(); }
            else toast("Customer record not found.", "error");
          } catch (e) { toast(e.message || "Failed.", "error"); }
        });
      } catch (e) {
        setBody(`<div class="form-error">${esc(e.message || "Unable to load payment.")}</div>`);
      }
    }
    render();
  } catch (err) {
    page().innerHTML = `<section class="wrap page-head"><div class="form-error">${esc(err.message || "Unable to load payments.")}</div></section>`;
  }
}

// ─── RECONCILIATION ─────────────────────────────────────────────────────────
async function renderReconciliation(opts = {}) {
  page().innerHTML = `<section class="wrap page-head"><h2>Reconciliation</h2><div class="skeleton" style="height:300px;border-radius:18px"></div></section>`;
  try {
    let statusF = opts.status || "";
    async function load() {
      const [batches, records] = await Promise.all([
        api("/admin/reconciliation/batches"),
        api("/admin/reconciliation/records" + (statusF ? "?status=" + statusF : "")),
      ]);
      return { batches, records };
    }
    let loaded = await load();
    let batches = loaded.batches;
    let records = loaded.records;
    function batchRowHtml(b) {
      return "<tr><td class=\"mono\">" + esc(b.batchNumber || "") + "</td>"
        + "<td>" + esc(b.provider || "All") + "</td>"
        + "<td>" + statusChip(b.status) + "</td>"
        + "<td>" + esc(String(b.totalRecords)) + "</td>"
        + "<td>" + esc(String(b.matchedCount)) + "</td>"
        + "<td>" + esc(String(b.exceptionCount)) + "</td>"
        + "<td>" + esc(fmtDateTime(b.completedAt)) + "</td></tr>";
    }
    function reconRowHtml(r) {
      const provAmt = r.providerAmount == null ? "—" : money(r.providerAmount);
      const intAmt = r.internalAmount == null ? "—" : money(r.internalAmount);
      const diffAmt = r.differenceAmount == null ? "—" : money(r.differenceAmount);
      return "<tr class=\"clickable\" data-recon=\"" + esc(String(r.id)) + "\">"
        + "<td class=\"mono\">" + esc(r.providerReference || "—") + "</td>"
        + "<td>" + statusChip(r.status) + "</td>"
        + "<td class=\"mono\">" + esc(r.internalReference || "—") + "</td>"
        + "<td>" + provAmt + "</td><td>" + intAmt + "</td><td>" + diffAmt + "</td>"
        + "<td>" + esc(r.reason || "—") + "</td></tr>";
    }
    function summary() {
      const matched = records.filter((r) => r.status === "MATCHED").length;
      const exceptions = records.filter((r) => r.status !== "MATCHED").length;
      const rate = records.length === 0 ? 100 : Math.round((matched / records.length) * 100);
      return { matched, exceptions, rate, total: records.length };
    }
    function viewReconRecord(rec) {
      if (!rec) return;
      openDrawer("Reconciliation " + (rec.providerReference || rec.internalReference || ""), `
        <div style="margin-bottom:0.75rem">${statusChip(rec.status)}</div>
        <h3 class="mt">Provider evidence</h3>
        <div class="kv"><dt>Reference</dt><dd class="mono">${esc(rec.providerReference || "—")}</dd></div>
        <div class="kv"><dt>Amount</dt><dd>${rec.providerAmount == null ? "—" : money(rec.providerAmount)}</dd></div>
        <div class="kv"><dt>Status</dt><dd>${esc(rec.providerStatus || "—")}</dd></div>
        <h3 class="mt">Internal payment</h3>
        <div class="kv"><dt>Reference</dt><dd class="mono">${esc(rec.internalReference || "—")}</dd></div>
        <div class="kv"><dt>Amount</dt><dd>${rec.internalAmount == null ? "—" : money(rec.internalAmount)}</dd></div>
        <div class="kv"><dt>Status</dt><dd>${esc(rec.internalStatus || "—")}</dd></div>
        <div class="kv"><dt>Batch</dt><dd class="mono">${esc(rec.batchNumber || "")}</dd></div>
        <div class="kv"><dt>Reconciled</dt><dd>${esc(fmtDateTime(rec.reconciledAt))}</dd></div>
        ${rec.internalReference ? `<div class="btn-row" style="margin-top:1rem"><button class="btn btn-primary btn-sm" data-recon-tx>View transaction</button></div>` : ""}
        <p class="small" style="margin-top:1rem">Read-only: mismatches never change balances. Resolve via the Payments tab.</p>`);
      document.querySelector("[data-recon-tx]")?.addEventListener("click", () => {
        document.querySelector(".modal-close")?.click();
        location.hash = "#/payments";
        pendingOpen = rec.internalReference;
        route();
      });
    }
    function render() {
      const s = summary();
      page().innerHTML = `
        <section class="wrap page-head">
          <h2>Reconciliation</h2>
          <p class="small">Compares internal payments against the provider feed. Read-only: mismatches never change balances.</p>
          <div class="kpis">
            <div class="kpi"><div class="k-label">Provider records</div><div class="k-value">${esc(String(s.total))}</div></div>
            <div class="kpi"><div class="k-label">Matched</div><div class="k-value">${esc(String(s.matched))}</div></div>
            <div class="kpi"><div class="k-label">Exceptions</div><div class="k-value">${esc(String(s.exceptions))}</div></div>
            <div class="kpi"><div class="k-label">Match rate</div><div class="k-value">${esc(String(s.rate))}%</div></div>
          </div>
          <div class="btn-row mt">
            <button class="btn btn-primary btn-sm" id="recon-run-btn">Run reconciliation</button>
            <button class="btn btn-secondary btn-sm" id="recon-import-btn">Import provider feed</button>
          </div>
          <div class="card mt"><h3>Runs</h3>
            <div id="recon-batches"></div>
          </div>
          <div class="card mt"><h3>Exceptions</h3>
            <div class="toolbar"><div class="field"><label for="recon-filter">Status</label><select id="recon-filter"></select></div></div>
            <div id="recon-table"></div>
          </div>
        </section>`;
      document.getElementById("recon-batches").innerHTML =
        batches.length === 0
          ? emptyState("No runs yet", "Run reconciliation to compare the latest feed.")
          : `<div class="table-scroll"><table class="data"><thead><tr><th>Batch</th><th>Provider</th><th>Status</th><th>Records</th><th>Matched</th><th>Exceptions</th><th>Completed</th></tr></thead><tbody>${batches.map(batchRowHtml).join("")}</tbody></table></div>`;
      const filterSel = document.getElementById("recon-filter");
      const statuses = ["", "AMOUNT_MISMATCH", "STATUS_MISMATCH", "MISSING_INTERNAL", "MISSING_PROVIDER", "DUPLICATE_PROVIDER", "MATCHED"];
      const labels = { "": "All non-matched", AMOUNT_MISMATCH: "Amount mismatch", STATUS_MISMATCH: "Status mismatch", MISSING_INTERNAL: "Missing internal", MISSING_PROVIDER: "Missing provider", DUPLICATE_PROVIDER: "Duplicate provider", MATCHED: "Matched" };
      filterSel.innerHTML = statuses.map((st) => `<option value="${st}"${statusF === st ? " selected" : ""}>${labels[st]}</option>`).join("");
      const tableSlot = document.getElementById("recon-table");
      if (records.length === 0) {
        tableSlot.innerHTML = emptyState("No exceptions", "Everything currently reconciles.");
      } else {
        tableSlot.innerHTML = `<div class="table-scroll"><table class="data"><thead><tr><th>Provider ref</th><th>Status</th><th>Internal</th><th>Provider amt</th><th>Internal amt</th><th>Difference</th><th>Reason</th></tr></thead><tbody>${records.map(reconRowHtml).join("")}</tbody></table></div>`;
      }
      filterSel.addEventListener("change", async (e) => {
        statusF = e.target.value;
        const loaded2 = await load();
        batches = loaded2.batches;
        records = loaded2.records;
        render();
      });
      tableSlot.querySelectorAll("tr[data-recon]").forEach((row) => row.addEventListener("click", () => {
        const rec = records.find((r) => String(r.id) === row.dataset.recon);
        if (rec) viewReconRecord(rec);
      }));
      const open = consumeOpen();
      if (open) {
        const byId = records.find((r) => String(r.id) === open);
        if (byId) viewReconRecord(byId);
      }
      document.getElementById("recon-run-btn").addEventListener("click", runReconNow);
      document.getElementById("recon-import-btn").addEventListener("click", openFeedImport);
    }
    async function runReconNow() {
      const ok = await confirmDialog({ title: "Run reconciliation?", message: "Compares internal payments against the imported provider feed. Read-only." });
      if (!ok) return;
      try {
        const batch = await api("/admin/reconciliation/runs", { method: "POST" });
        toast("Run " + batch.batchNumber + ": " + batch.matchedCount + " matched, " + batch.exceptionCount + " exceptions.", batch.exceptionCount === 0 ? "success" : undefined);
        const loaded3 = await load();
        batches = loaded3.batches;
        records = loaded3.records;
        render();
      } catch (e) { toast(e.message || "Run failed.", "error"); }
    }
    function openFeedImport() {
      openModal({ title: "Import provider feed (simulated)", bodyHTML: `
        <p class="small">Paste a JSON array of provider transactions.</p>
        <div class="field"><label for="feed-json">Feed JSON</label><textarea id="feed-json" rows="8"></textarea></div>
        <div class="btn-row"><button class="btn btn-primary btn-sm" id="feed-confirm">Import</button></div>` });
      document.getElementById("feed-confirm")?.addEventListener("click", async () => {
        let items;
        try {
          items = JSON.parse(document.getElementById("feed-json")?.value || "");
          if (!Array.isArray(items)) throw new Error("not an array");
        } catch {
          toast("Feed must be a JSON array of transactions.", "error");
          return;
        }
        try {
          const result = await api("/admin/reconciliation/feed", { method: "POST", body: { transactions: items } });
          toast("Imported " + result.inserted + " new, skipped " + result.skippedDuplicates + " duplicates.", "success");
          document.querySelector(".modal-close")?.click();
          const loaded4 = await load();
          batches = loaded4.batches;
          records = loaded4.records;
          render();
        } catch (e) { toast(e.message || "Import failed.", "error"); }
      });
    }
    render();
  } catch (err) {
    page().innerHTML = `<section class="wrap page-head"><div class="form-error">${esc(err.message || "Unable to load reconciliation.")}</div></section>`;
  }
}
async function renderNetwork() {
  page().innerHTML = `<section class="wrap page-head"><h2>Network</h2><div class="skeleton" style="height:300px;border-radius:18px"></div></section>`;
  try {
    const [merchants, agents] = await Promise.all([api("/admin/merchants"), api("/admin/agents")]);
    let tab = "merchants";
    function render() {
      page().innerHTML = `
        <section class="wrap page-head">
          <h2>Network</h2>
          <div class="tabs"><button aria-selected="${tab === "merchants"}" data-tab="merchants">Merchants</button><button aria-selected="${tab === "agents"}" data-tab="agents">Agents</button></div>
          <div class="btn-row"><button class="btn btn-primary btn-sm" id="add-entity-btn">${tab === "merchants" ? "Add merchant" : "Add agent"}</button></div>
          <div id="net-content" class="card mt"></div>
        </section>`;
      const slot = document.getElementById("net-content");
      if (tab === "merchants") {
        slot.innerHTML = merchants.length === 0 ? emptyState("No merchants", "Register the first merchant.") : `<div class="table-scroll"><table class="data"><thead><tr><th>Code</th><th>Business</th><th>Status</th><th>Agents</th><th>Devices</th><th></th></tr></thead><tbody>${merchants.map((m) => `<tr><td class="mono">${esc(m.merchantCode || "")}</td><td>${esc(m.businessName || "")}</td><td>${statusChip(m.status)}</td><td>${esc(String(m.agentCount ?? 0))}</td><td>${esc(String(m.deviceCount ?? 0))}</td><td>${m.status === "SUSPENDED" ? `<button class="btn btn-ghost btn-sm" data-merchant-activate="${esc(String(m.id))}">Activate</button>` : `<button class="btn btn-ghost btn-sm" data-merchant-suspend="${esc(String(m.id))}">Suspend</button>`}</td></tr>`).join("")}</tbody></table></div>`;
      } else {
        slot.innerHTML = agents.length === 0 ? emptyState("No agents", "Register the first agent.") : `<div class="table-scroll"><table class="data"><thead><tr><th>Code</th><th>Name</th><th>Merchant</th><th>Status</th><th></th></tr></thead><tbody>${agents.map((a) => `<tr><td class="mono">${esc(a.agentCode || "")}</td><td>${esc(a.fullName || "")}</td><td>${esc(a.merchantName || "")}</td><td>${statusChip(a.status)}</td><td>${a.status === "SUSPENDED" ? `<button class="btn btn-ghost btn-sm" data-agent-activate="${esc(String(a.id))}">Activate</button>` : `<button class="btn btn-ghost btn-sm" data-agent-suspend="${esc(String(a.id))}">Suspend</button>`}</td></tr>`).join("")}</tbody></table></div>`;
      }
      document.querySelectorAll("[data-tab]").forEach((btn) => btn.addEventListener("click", () => { tab = btn.dataset.tab; render(); }));
      document.getElementById("add-entity-btn").addEventListener("click", tab === "merchants" ? openAddMerchantModal : openAddAgentModal);
      const patchStatus = async (path, status, label) => {
        const action = status === "SUSPENDED" ? "suspend" : "activate";
        const ok = await confirmDialog({ title: `${label} ${action}?`, message: `This will set the ${label.toLowerCase()} status to ${status}.`, danger: status === "SUSPENDED" });
        if (!ok) return;
        try { await api(path, { method: "PATCH", body: { status } }); toast(`${label} ${status === "SUSPENDED" ? "suspended" : "activated"}.`, "success"); renderNetwork(); } catch (e) { toast(e.message || "Failed.", "error"); }
      };
      document.querySelectorAll("[data-merchant-suspend]").forEach((btn) => btn.addEventListener("click", () => patchStatus(`/admin/merchants/${btn.dataset.merchantSuspend}`, "SUSPENDED", "Merchant")));
      document.querySelectorAll("[data-merchant-activate]").forEach((btn) => btn.addEventListener("click", () => patchStatus(`/admin/merchants/${btn.dataset.merchantActivate}`, "ACTIVE", "Merchant")));
      document.querySelectorAll("[data-agent-suspend]").forEach((btn) => btn.addEventListener("click", () => patchStatus(`/admin/agents/${btn.dataset.agentSuspend}`, "SUSPENDED", "Agent")));
      document.querySelectorAll("[data-agent-activate]").forEach((btn) => btn.addEventListener("click", () => patchStatus(`/admin/agents/${btn.dataset.agentActivate}`, "ACTIVE", "Agent")));
    }
    function openAddMerchantModal() {
      openModal({ title: "Add merchant", bodyHTML: `<form id="merch-form" novalidate>
        <div class="field"><label>Business name</label><input id="m-biz" type="text" required /></div>
        <div class="grid-2"><div class="field"><label>Phone</label><input id="m-phone" type="tel" required /></div><div class="field"><label>Email</label><input id="m-email" type="email" required /></div></div>
        <div class="field"><label>Address</label><input id="m-addr" type="text" /></div>
        <div class="btn-row"><button class="btn btn-primary" type="submit">Add</button></div></form>` });
      document.getElementById("merch-form").addEventListener("submit", async (e) => {
        e.preventDefault();
        try { await api("/admin/merchants", { method: "POST", body: { businessName: document.getElementById("m-biz").value.trim(), phone: document.getElementById("m-phone").value.trim(), email: document.getElementById("m-email").value.trim(), address: document.getElementById("m-addr").value.trim(), registrationNumber: null } }); toast("Merchant created.", "success"); document.querySelector(".modal-close")?.click(); renderNetwork(); } catch (err) { toast(err.message || "Failed.", "error"); }
      });
    }
    function openAddAgentModal() {
      openModal({ title: "Add agent", bodyHTML: `<form id="agent-form" novalidate>
        <div class="field"><label>Full name</label><input id="a-name" type="text" required /></div>
        <div class="grid-2"><div class="field"><label>Email</label><input id="a-email" type="email" required /></div><div class="field"><label>Phone</label><input id="a-phone" type="tel" /></div></div>
        <div class="field"><label>Password</label><input id="a-pass" type="password" required />
          <ul class="checklist" id="a-checklist"><li data-rule="length">8–100 chars</li><li data-rule="letter">One letter</li><li data-rule="digit">One digit</li></ul></div>
        <div class="field"><label>Merchant</label><select id="a-merchant"><option value="">Choose…</option>${merchants.map((m) => `<option value="${esc(String(m.id))}">${esc(m.businessName)}</option>`).join("")}</select></div>
        <div class="btn-row"><button class="btn btn-primary" type="submit">Create agent</button></div></form>` });
      const passInput = document.getElementById("a-pass");
      const checklist = document.getElementById("a-checklist");
      passInput.addEventListener("input", () => { const p = passInput.value; checklist.querySelectorAll("[data-rule]").forEach((li) => { const rule = li.dataset.rule; li.classList.toggle("ok", rule === "length" ? p.length >= 8 && p.length <= 100 : rule === "letter" ? /[A-Za-z]/.test(p) : /\d/.test(p)); }); });
      document.getElementById("agent-form").addEventListener("submit", async (e) => {
        e.preventDefault();
        try {
          const user = await api("/admin/users", { method: "POST", body: { fullName: document.getElementById("a-name").value.trim(), email: document.getElementById("a-email").value.trim(), phoneNumber: document.getElementById("a-phone").value.trim(), password: passInput.value, role: "AGENT" } });
          await api("/admin/agents", { method: "POST", body: { userAccountId: user.id, merchantId: Number(document.getElementById("a-merchant").value) } });
          toast("Agent created.", "success");
          document.querySelector(".modal-close")?.click();
          renderNetwork();
        } catch (err) { toast(err.message || "Failed.", "error"); }
      });
    }
    render();
  } catch (err) {
    page().innerHTML = `<section class="wrap page-head"><div class="form-error">${esc(err.message || "Unable to load network.")}</div></section>`;
  }
}

// ─── REPORTS (d3) ─────────────────────────────────────────────────────────────
async function renderReports() {
  page().innerHTML = `<section class="wrap page-head"><h2>Reports</h2><div class="skeleton" style="height:300px;border-radius:18px"></div></section>`;
  try {
    const [payments, contracts] = await Promise.all([api("/admin/payments"), api("/loans")]);
    const allPayments = Array.isArray(payments) ? payments : [];
    const allContracts = Array.isArray(contracts) ? contracts : [];
    // Collections by day (last 14 days)
    const days14 = [];
    for (let i = 13; i >= 0; i--) {
      const d = new Date(); d.setDate(d.getDate() - i);
      const ds = d.toDateString();
      const label = `${d.getDate()}/${d.getMonth() + 1}`;
      const val = allPayments.filter((p) => p.status === "POSTED" && p.postedAt && new Date(p.postedAt).toDateString() === ds).reduce((s, p) => s + Number(p.amount || 0), 0);
      days14.push({ label, value: val });
    }
    // Portfolio at risk buckets
    const live = allContracts.filter((c) => c.loanStatus === "ACTIVE" || c.loanStatus === "OVERDUE");
    const buckets = { "0 days": 0, "1–7 days": 0, "8–30 days": 0, "31+ days": 0 };
    live.forEach((c) => {
      if (!c.nextPaymentDueDate) return;
      const overdue = Math.max(0, Math.ceil((Date.now() - new Date(c.nextPaymentDueDate).getTime()) / 86400000));
      if (overdue <= 0) buckets["0 days"]++;
      else if (overdue <= 7) buckets["1–7 days"]++;
      else if (overdue <= 30) buckets["8–30 days"]++;
      else buckets["31+ days"]++;
    });
    function csvEscape(v) { return `"${String(v ?? "").replace(/"/g, '""')}"`; }
    function downloadCsv(filename, rows) {
      if (!rows.length) { toast("No data to export.", "error"); return; }
      const headers = Object.keys(rows[0]);
      const csv = [headers.join(","), ...rows.map((r) => headers.map((h) => csvEscape(r[h])).join(","))].join("\n");
      const blob = new Blob([csv], { type: "text/csv" });
      const a = document.createElement("a");
      a.href = URL.createObjectURL(blob);
      a.download = filename;
      a.click();
      URL.revokeObjectURL(a.href);
    }
    page().innerHTML = `
      <section class="wrap page-head">
        <h2>Reports</h2>
        <div class="card"><h3>Collections by day</h3>${svgBars(days14)}</div>
        <div class="card mt"><h3>Portfolio at risk (days overdue)</h3><div class="grid-2" style="gap:0.5rem">${Object.entries(buckets).map(([k, v]) => `<div class="card"><div class="kv"><dt>${esc(k)}</dt><dd>${esc(String(v))} contracts</dd></div></div>`).join("")}</div></div>
        <div class="btn-row mt">
          <button class="btn btn-secondary btn-sm" id="csv-contracts">Export contracts CSV</button>
          <button class="btn btn-secondary btn-sm" id="csv-payments">Export payments CSV</button>
        </div>
      </section>`;
    document.getElementById("csv-contracts").addEventListener("click", () => {
      downloadCsv("contracts.csv", allContracts.map((c) => ({ contractNumber: c.contractNumber, customerName: c.customerName, productName: c.productName, amountRemaining: c.amountRemaining, dailyAmount: c.dailyAmount, status: c.loanStatus, nextPaymentDueDate: c.nextPaymentDueDate })));
    });
    document.getElementById("csv-payments").addEventListener("click", () => {
      downloadCsv("payments.csv", allPayments.map((p) => ({ reference: p.transactionReference, customer: p.customerName, amount: p.amount, channel: p.channel, status: p.status, providerRef: p.providerReference, postedAt: p.postedAt })));
    });
  } catch (err) {
    page().innerHTML = `<section class="wrap page-head"><div class="form-error">${esc(err.message || "Unable to load reports.")}</div></section>`;
  }
}

if (me) route();
