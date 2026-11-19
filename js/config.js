// Single source of API configuration for the whole frontend.
//
// Spring Boot serves these pages and the API from the same origin, so the
// relative "/api/v1" base works in the Docker image, on Render, and locally
// via http://localhost:8080. No absolute host is baked into the bundle.
//
// For a deliberately split deployment (static frontend + separate API host),
// define window.MSIMU_API_BASE before this module loads:
//   <script>window.MSIMU_API_BASE = "https://msimu-api.example.com/api/v1";</script>
const runtimeApiBase =
  typeof window !== "undefined" && window.MSIMU_API_BASE ? window.MSIMU_API_BASE : "/api/v1";

export const API_BASE = runtimeApiBase.replace(/\/+$/, "");

export const SESSION_KEY = "msimu.session";

// Where each role lands after login. Portal pages enforce this too.
export const ROLE_HOME = {
  CUSTOMER: "app.html",
  ADMIN: "admin.html",
  LOAN_OFFICER: "admin.html",
  PAYMENTS_OPERATOR: "admin.html",
  AGENT: "partner.html",
  MERCHANT: "partner.html",
  TECHNICIAN: "partner.html",
};

// Seed accounts shown on the login page. These are the documented demo
// defaults and must match the deployment's TRACOM_*_PASSWORD variables.
// They are public academy/demo credentials, never real user credentials.
export const DEMO_ACCOUNTS = [
  { label: "Customer", email: "demo@lipamdogo.com", password: "Demo@1234" },
  { label: "Admin", email: "admin@tracom.local", password: "Admin@1234" },
];

// Cashless: M-PESA is the only payment rail. Customers pay from their own
// phone or approve a request an agent sends from a terminal.
export const CHANNELS = ["MPESA"];

// Demo simulation knob honored only by the mock provider (never real money).
export const MOCK_SCENARIOS = ["AUTO", "SUCCESS", "PENDING", "FAILED"];
