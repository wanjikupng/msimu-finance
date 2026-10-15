// Login + signup logic (one module, detects which form is on the page).
// Signup auto-logs-in and lands in the customer portal; login redirects by
// role. Backend fieldErrors map onto fields via data-field attributes.
import { api, session, homeFor, ApiError } from "./api.js";
import { DEMO_ACCOUNTS } from "./config.js";
import { esc, setFieldErrors } from "./ui.js";
import { renderPhone } from "./phone.js";

// Normalise Kenyan mobile numbers to +254XXXXXXXXX. Accepts 07xx/01xx,
// +254..., 254... with spaces or dashes. Returns null when invalid.
export function normalizePhone(raw) {
  const digits = String(raw ?? "").replace(/[\s\-()]/g, "");
  let rest = null;
  if (/^0[17]\d{8}$/.test(digits)) rest = digits.slice(1);
  else if (/^\+254[17]\d{8}$/.test(digits)) rest = digits.slice(4);
  else if (/^254[17]\d{8}$/.test(digits)) rest = digits.slice(3);
  return rest ? `+254${rest}` : null;
}

// Live checklist mirroring the backend password rules exactly:
// 8–100 chars, at least one letter, at least one digit.
export function passwordChecks(password) {
  const p = String(password ?? "");
  return {
    length: p.length >= 8 && p.length <= 100,
    letter: /[A-Za-z]/.test(p),
    digit: /\d/.test(p),
  };
}

function showFormError(message) {
  const box = document.getElementById("form-error");
  if (!box) return;
  if (!message) {
    box.classList.add("hidden");
    box.textContent = "";
  } else {
    box.classList.remove("hidden");
    box.textContent = message;
  }
}

function wirePasswordToggle() {
  document.querySelectorAll("[data-toggle]").forEach((btn) => {
    btn.addEventListener("click", () => {
      const input = document.getElementById(btn.dataset.toggle);
      if (!input) return;
      const show = input.type === "password";
      input.type = show ? "text" : "password";
      btn.textContent = show ? "Hide" : "Show";
      btn.setAttribute("aria-label", show ? "Hide password" : "Show password");
    });
  });
}

function wireChecklist() {
  const input = document.getElementById("password");
  const list = document.getElementById("password-checklist");
  if (!input || !list) return;
  const update = () => {
    const checks = passwordChecks(input.value);
    list.querySelectorAll("[data-rule]").forEach((li) => {
      li.classList.toggle("ok", !!checks[li.dataset.rule]);
    });
  };
  input.addEventListener("input", update);
  update();
}

function wirePhonePreview() {
  const input = document.getElementById("phoneNumber");
  const preview = document.getElementById("phone-preview");
  if (!input || !preview) return;
  const update = () => {
    if (!input.value.trim()) {
      preview.textContent = "Kenyan mobile number, e.g. 0712 345 678.";
      return;
    }
    const normalized = normalizePhone(input.value);
    preview.textContent = normalized
      ? `We'll save this as ${normalized}.`
      : "Enter a Kenyan number like 0712 345 678.";
  };
  input.addEventListener("input", update);
}

function wireDemoAccounts() {
  const box = document.getElementById("demo-accounts");
  if (!box) return;
  // esc() every label: these strings are ours today, but the helper makes
  // the pattern impossible to forget when the list grows.
  box.innerHTML = DEMO_ACCOUNTS.map(
    (a, i) => `<button type="button" class="btn btn-secondary btn-sm" data-demo="${i}">${esc(a.label)}</button>`,
  ).join("");
  box.querySelectorAll("[data-demo]").forEach((btn) => {
    btn.addEventListener("click", () => {
      const account = DEMO_ACCOUNTS[Number(btn.dataset.demo)];
      if (!account) return;
      document.getElementById("email").value = account.email;
      document.getElementById("password").value = account.password;
      document.getElementById("password").focus();
    });
  });
}

async function handleLogin(form) {
  const submit = document.getElementById("submit-btn");
  submit.disabled = true;
  showFormError(null);
  setFieldErrors(form, {});
  try {
    const auth = await api("/auth/login", {
      body: {
        email: document.getElementById("email").value.trim(),
        password: document.getElementById("password").value,
      },
      redirectOn401: false,
    });
    session.save(auth);
    window.location.replace(homeFor(auth.role));
  } catch (err) {
    if (err instanceof ApiError && err.status === 401) {
      showFormError("Wrong email or password. Check both and try again.");
    } else if (err instanceof ApiError) {
      setFieldErrors(form, err.fieldErrors);
      showFormError(err.message);
    } else {
      showFormError("Something went wrong. Please try again.");
    }
    submit.disabled = false;
  }
}

async function handleSignup(form) {
  const submit = document.getElementById("submit-btn");
  submit.disabled = true;
  showFormError(null);
  setFieldErrors(form, {});

  const fullName = document.getElementById("fullName").value.trim();
  const email = document.getElementById("email").value.trim();
  const phoneRaw = document.getElementById("phoneNumber").value;
  const password = document.getElementById("password").value;

  // Client-side gates mirror the server so mistakes surface instantly;
  // the server still re-validates everything.
  const localErrors = {};
  if (!fullName) localErrors.fullName = "Full name is required.";
  if (!email) localErrors.email = "Email is required.";
  const phoneNumber = normalizePhone(phoneRaw);
  if (!phoneNumber) localErrors.phoneNumber = "Enter a Kenyan number like 0712 345 678.";
  const checks = passwordChecks(password);
  if (!checks.length || !checks.letter || !checks.digit) {
    localErrors.password = "Use 8–100 characters with at least one letter and one digit.";
  }
  if (Object.keys(localErrors).length > 0) {
    setFieldErrors(form, localErrors);
    submit.disabled = false;
    return;
  }

  try {
    await api("/auth/register", {
      body: { fullName, email, phoneNumber, password },
    });
    // New accounts are CUSTOMERs: log straight in, no second form.
    const auth = await api("/auth/login", { body: { email, password }, redirectOn401: false });
    session.save(auth);
    window.location.replace(homeFor(auth.role));
  } catch (err) {
    if (err instanceof ApiError) {
      setFieldErrors(form, err.fieldErrors);
      showFormError(err.message);
    } else {
      showFormError("Something went wrong. Please try again.");
    }
    submit.disabled = false;
  }
}

function init() {
  // Already signed in? Skip the form and go home.
  if (session.valid()) {
    window.location.replace(homeFor(session.load().role));
    return;
  }
  renderPhone(document.getElementById("brand-phone"), { pct: 68, state: "due" });
  wirePasswordToggle();
  wireChecklist();
  wirePhonePreview();
  wireDemoAccounts();

  const loginForm = document.getElementById("login-form");
  if (loginForm) {
    loginForm.addEventListener("submit", (e) => {
      e.preventDefault();
      handleLogin(loginForm);
    });
  }
  const signupForm = document.getElementById("signup-form");
  if (signupForm) {
    signupForm.addEventListener("submit", (e) => {
      e.preventDefault();
      handleSignup(signupForm);
    });
  }
}

init();
