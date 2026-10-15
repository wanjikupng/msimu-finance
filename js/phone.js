// Signature visual: a phone that CHARGES like a battery as the customer pays.
// The screen fills from the bottom to the ownership % (deposit counts as paid).
// States: active (teal), due (amber), overdue (red-grey + lock), owned (full + check).
// The fill animates once on load; prefers-reduced-motion renders it instantly.
import { esc } from "./ui.js";

let _uid = 0;
const FILL = {
  active: "#12B08A",
  due: "#E39A0B",
  overdue: "#8d6b6b",
  owned: "#12B08A",
};

export function phoneSVG({ pct = 0, state = "active", label = "Phone charge level" } = {}) {
  const id = `ph${++_uid}`;
  const clamped = Math.max(0, Math.min(100, Number(pct) || 0));
  const color = FILL[state] || FILL.active;
  // Screen geometry: x16 y28 w88 h180. Fill grows upward from y=208.
  const h = (clamped / 100) * 180;
  const y = 208 - h;
  const overlay =
    state === "overdue"
      ? `<g aria-hidden="true">
           <rect x="48" y="100" width="24" height="20" rx="4" fill="#0E2330"/>
           <path d="M53 100 v-6 a7 7 0 0 1 14 0 v6" fill="none" stroke="#0E2330" stroke-width="5"/>
           <circle cx="60" cy="110" r="2.6" fill="#FBE3E1"/>
         </g>`
      : state === "owned"
        ? `<g aria-hidden="true">
             <circle cx="60" cy="110" r="17" fill="#087A5D"/>
             <path d="M52 110 l6 6 l11 -13" fill="none" stroke="#fff" stroke-width="4" stroke-linecap="round" stroke-linejoin="round"/>
           </g>`
        : "";
  return `<svg viewBox="0 0 120 244" width="120" height="244" role="img" aria-label="${esc(label)}">
    <rect x="8" y="4" width="104" height="236" rx="18" fill="#FFFFFF" stroke="#0E2330" stroke-width="3"/>
    <rect x="52" y="12" width="16" height="5" rx="2.5" fill="#0E2330"/>
    <clipPath id="${id}-clip"><rect x="16" y="28" width="88" height="180" rx="8"/></clipPath>
    <rect x="16" y="28" width="88" height="180" rx="8" fill="#F2F6F7"/>
    <g clip-path="url(#${id}-clip)">
      <rect class="phone-charge-fill" x="16" y="${y.toFixed(1)}" width="88" height="${h.toFixed(1)}" fill="${color}"/>
    </g>
    <rect x="16" y="28" width="88" height="180" rx="8" fill="none" stroke="#0E2330" stroke-width="2"/>
    ${overlay}
    <rect x="48" y="220" width="24" height="4" rx="2" fill="#0E2330"/>
  </svg>`;
}

// Render into el, animating the fill from empty to pct once (instant when the
// user prefers reduced motion).
export function renderPhone(el, opts = {}) {
  if (!el) return;
  const reduce = window.matchMedia("(prefers-reduced-motion: reduce)").matches;
  if (reduce) {
    el.innerHTML = phoneSVG(opts);
    return;
  }
  el.innerHTML = phoneSVG({ ...opts, pct: 0 });
  const fill = el.querySelector(".phone-charge-fill");
  const target = Math.max(0, Math.min(100, Number(opts.pct) || 0));
  const h = (target / 100) * 180;
  requestAnimationFrame(() => {
    requestAnimationFrame(() => {
      if (!fill.isConnected) return;
      fill.setAttribute("y", (208 - h).toFixed(1));
      fill.setAttribute("height", h.toFixed(1));
    });
  });
}

// Ownership % from a contract: deposit counts as paid.
export function ownershipPct(contract) {
  const price = Number(contract.devicePrice ?? 0);
  const remaining = Number(contract.amountRemaining ?? 0);
  if (!price || price <= 0) return 0;
  return Math.max(0, Math.min(100, ((price - remaining) / price) * 100));
}

// Map contract + due position to a phone state.
export function phoneStateFor(contract, daysLate) {
  if (!contract) return "active";
  if (contract.loanStatus === "PAID_OFF") return "owned";
  if (contract.loanStatus === "OVERDUE" || (daysLate ?? 0) > 0) return "overdue";
  if ((daysLate ?? 0) >= -3) return "due";
  return "active";
}
