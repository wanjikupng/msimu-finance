// Single source of API configuration for the whole frontend.
// The backend runs on port 8080; CORS already allows localhost and 127.0.0.1.
export const API_BASE = "http://localhost:8080/api/v1";

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

// Dev seed accounts shown on the login page (backend bootstrap data).
export const DEMO_ACCOUNTS = [
  { label: "Customer", email: "demo@lipamdogo.com", password: "Demo@1234" },
  { label: "Admin", email: "admin@tracom.local", password: "Admin@1234" },
];

// Cashless: M-PESA is the only payment rail. Customers pay from their own
// phone or approve a request an agent sends from a terminal.
export const CHANNELS = ["MPESA"];

// Demo simulation knob honored only by the mock provider (never real money).
export const MOCK_SCENARIOS = ["AUTO", "SUCCESS", "PENDING", "FAILED"];
