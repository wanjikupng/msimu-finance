// Shared UI primitives: escaping, formatting, toasts, accessible
// modals/drawers/confirms, skeletons, empty states, status chips.
const _escMap = { "&": "&amp;", "<": "&lt;", ">": "&gt;", '"': "&quot;", "'": "&#39;" };

// Escape ALL user/server text before injecting into HTML (XSS prevention).
export function esc(value) {
  return String(value ?? "").replace(/[&<>"']/g, (c) => _escMap[c]);
}

// "KES 14,000" — whole shillings, Kenyan grouping.
export function money(value) {
  const n = Number(value ?? 0);
  if (!Number.isFinite(n)) return "KES 0";
  return "KES " + Math.round(n).toLocaleString("en-KE");
}

const _dateFmt = new Intl.DateTimeFormat("en-KE", {
  timeZone: "Africa/Nairobi",
  day: "numeric",
  month: "short",
  year: "numeric",
});
const _dateTimeFmt = new Intl.DateTimeFormat("en-KE", {
  timeZone: "Africa/Nairobi",
  day: "numeric",
  month: "short",
  year: "numeric",
  hour: "numeric",
  minute: "2-digit",
});

export function fmtDate(value) {
  if (!value) return "—";
  const d = new Date(value);
  return Number.isNaN(d.getTime()) ? "—" : _dateFmt.format(d);
}

export function fmtDateTime(value) {
  if (!value) return "—";
  const d = new Date(value);
  return Number.isNaN(d.getTime()) ? "—" : _dateTimeFmt.format(d);
}

// "due today" / "in 3 days" / "2 days late" from a nextPaymentDueDate.
// nowISO lets tests/stories pin "today"; defaults to the current day.
export function dueLabel(nextPaymentDueDate, nowISO) {
  if (!nextPaymentDueDate) return "No due date set";
  const day = 24 * 60 * 60 * 1000;
  const startOf = (d) => new Date(d.getFullYear(), d.getMonth(), d.getDate()).getTime();
  const due = new Date(nextPaymentDueDate);
  if (Number.isNaN(due.getTime())) return "No due date set";
  const diff = Math.round((startOf(due) - startOf(nowISO ? new Date(nowISO) : new Date())) / day);
  if (diff === 0) return "due today";
  if (diff === 1) return "due tomorrow";
  if (diff > 1) return `in ${diff} days`;
  if (diff === -1) return "1 day late";
  return `${Math.abs(diff)} days late`;
}

// Status chip palettes. Green = on track/paid, amber = due soon,
// red = overdue/locked, grey = neutral/closed, indigo = in-between/system.
const CHIP_CLASS = {
  ACTIVE: "chip-green",
  PAID_OFF: "chip-green",
  APPROVED: "chip-green",
  POSTED: "chip-green",
  AVAILABLE: "chip-green",
  FINANCED: "chip-green",
  UNDER_REVIEW: "chip-amber",
  SUBMITTED: "chip-amber",
  PENDING: "chip-amber",
  RESERVED: "chip-amber",
  REPAIR: "chip-amber",
  OVERDUE: "chip-red",
  REJECTED: "chip-red",
  FAILED: "chip-red",
  LOST: "chip-red",
  SUCCESS: "chip-indigo",
  INITIATED: "chip-grey",
  CANCELLED: "chip-grey",
  REVERSED: "chip-grey",
  RETIRED: "chip-grey",
  CLOSED: "chip-grey",
  SUSPENDED: "chip-red",
  ASSIGNED: "chip-grey",
};

export function statusChip(status) {
  const s = String(status ?? "—");
  const cls = CHIP_CLASS[s] || "chip-grey";
  return `<span class="chip ${cls}">${esc(s.replace(/_/g, " "))}</span>`;
}

export function railTag(text) {
  return `<span class="chip chip-indigo rail-tag">Tracom: ${esc(text)}</span>`;
}

// ---- toasts ----
let _toastRegion = null;
export function toast(message, kind = "") {
  if (!_toastRegion) {
    _toastRegion = document.createElement("div");
    _toastRegion.className = "toast-region";
    _toastRegion.setAttribute("aria-live", "polite");
    document.body.appendChild(_toastRegion);
  }
  const el = document.createElement("div");
  el.className = `toast${kind === "error" ? " toast-error" : kind === "success" ? " toast-success" : ""}`;
  el.textContent = message;
  _toastRegion.appendChild(el);
  setTimeout(() => el.remove(), 4200);
}

// ---- accessible modal ----
let _lastFocus = null;
export function openModal({ title, bodyHTML, wide = false }) {
  _lastFocus = document.activeElement;
  const overlay = document.createElement("div");
  overlay.className = "modal-overlay";
  overlay.innerHTML = `
    <div class="modal" role="dialog" aria-modal="true" aria-label="${esc(title)}"${wide ? ' style="max-width:44rem"' : ""}>
      <div class="modal-head">
        <h2>${esc(title)}</h2>
        <button type="button" class="modal-close" data-close aria-label="Close dialog">✕</button>
      </div>
      <div class="modal-body"></div>
    </div>`;
  overlay.querySelector(".modal-body").innerHTML = bodyHTML;
  document.body.appendChild(overlay);
  document.body.style.overflow = "hidden";

  const close = () => {
    overlay.remove();
    document.body.style.overflow = "";
    document.removeEventListener("keydown", onKey, true);
    if (_lastFocus && _lastFocus.focus) _lastFocus.focus();
  };
  const onKey = (e) => {
    if (e.key === "Escape") close();
  };
  document.addEventListener("keydown", onKey, true);
  overlay.addEventListener("mousedown", (e) => {
    if (e.target === overlay) close();
  });
  overlay.querySelector("[data-close]").addEventListener("click", close);

  const focusable = overlay.querySelector("input, select, textarea, button:not([data-close])");
  (focusable || overlay.querySelector("[data-close]")).focus();
  return { overlay, close };
}

export function openDrawer(title, bodyHTML) {
  _lastFocus = document.activeElement;
  const overlay = document.createElement("div");
  overlay.className = "modal-overlay";
  overlay.style.justifyContent = "flex-end";
  overlay.style.alignItems = "stretch";
  overlay.innerHTML = `
    <div class="drawer" role="dialog" aria-modal="true" aria-label="${esc(title)}">
      <div class="modal-head">
        <h2>${esc(title)}</h2>
        <button type="button" class="modal-close" data-close aria-label="Close panel">✕</button>
      </div>
      <div class="drawer-body"></div>
    </div>`;
  overlay.querySelector(".drawer-body").innerHTML = bodyHTML;
  document.body.appendChild(overlay);
  document.body.style.overflow = "hidden";
  const close = () => {
    overlay.remove();
    document.body.style.overflow = "";
    document.removeEventListener("keydown", onKey, true);
    if (_lastFocus && _lastFocus.focus) _lastFocus.focus();
  };
  const onKey = (e) => {
    if (e.key === "Escape") close();
  };
  document.addEventListener("keydown", onKey, true);
  overlay.addEventListener("mousedown", (e) => {
    if (e.target === overlay) close();
  });
  overlay.querySelector("[data-close]").addEventListener("click", close);
  overlay.querySelector("[data-close]").focus();
  return { overlay, close };
}

// Confirm dialog for destructive actions. Resolves true/false.
export function confirmDialog({ title, message, confirmLabel = "Confirm", danger = false }) {
  return new Promise((resolve) => {
    const { overlay, close } = openModal({
      title,
      bodyHTML: `<p>${esc(message)}</p>
        <div class="btn-row">
          <button type="button" class="btn ${danger ? "btn-danger" : "btn-primary"}" data-yes>${esc(confirmLabel)}</button>
          <button type="button" class="btn btn-secondary" data-no>Cancel</button>
        </div>`,
    });
    const done = (value) => {
      close();
      resolve(value);
    };
    overlay.querySelector("[data-yes]").addEventListener("click", () => done(true));
    overlay.querySelector("[data-no]").addEventListener("click", () => done(false));
  });
}

// ---- loading + empty states ----
export function skeletonRows(count = 4, cols = 4) {
  return Array.from({ length: count }, () => {
    const cells = Array.from({ length: cols }, () => `<td><div class="skeleton">&nbsp;</div></td>`).join("");
    return `<tr>${cells}</tr>`;
  }).join("");
}

export function emptyState(title, hint, actionLabel, actionHref) {
  return `<div class="empty">
    <h3>${esc(title)}</h3>
    <p>${esc(hint)}</p>
    ${actionLabel ? `<a class="btn btn-primary" href="${esc(actionHref || "#")}">${esc(actionLabel)}</a>` : ""}
  </div>`;
}

// Map backend 400 fieldErrors {field: message} onto [data-field] inputs.
export function setFieldErrors(root, fieldErrors = {}) {
  root.querySelectorAll("[data-field]").forEach((input) => {
    const msg = fieldErrors[input.dataset.field];
    input.setAttribute("aria-invalid", msg ? "true" : "false");
    let err = input.parentElement.querySelector(".field-error");
    if (msg) {
      if (!err) {
        err = document.createElement("div");
        err.className = "field-error";
        input.parentElement.appendChild(err);
      }
      err.textContent = msg;
    } else if (err) {
      err.remove();
    }
  });
}

export function debounce(fn, ms = 350) {
  let t = null;
  return (...args) => {
    clearTimeout(t);
    t = setTimeout(() => fn(...args), ms);
  };
}
