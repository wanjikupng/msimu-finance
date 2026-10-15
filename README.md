# M-Simu Finance — TRACOM Finance Hub

A device-financing and payment-management platform built for Tracom Academy. Customers browse phones, apply for financing, sign contracts, and pay daily; staff review applications, manage inventory, and monitor payments. Built on a Spring Boot backend and a plain HTML/CSS/JS frontend.

> **Prototype/simulation, not a live lender.**

## Architecture

```
Customer / Agent / Merchant
        ↓
   Device Financing
        ↓
   Payment Engine (mock M-PESA)
        ↓
   Loan Engine + Receipts
        ↓
   Settlement & Reporting
```

**Backend:** Spring Boot 3.3.3, Java 17, H2 in-memory (PostgreSQL mode), Spring Security, JWT, BCrypt, JPA.
**Frontend:** Vanilla HTML/CSS/JS, ES modules, no build step, no frameworks.

## Quick start

### 1. Start the backend

```bash
cd Tracomproject
/home/ruthmwangi/.m2/wrapper/dists/apache-maven-3.9.16-bin/5grr65jo27hi51sujmtcldfovl/apache-maven-3.9.16/bin/mvn spring-boot:run
```

If you have `mvn` on PATH you can just run `mvn spring-boot:run`. First boot downloads dependencies and takes a few minutes; later boots are fast.

### 2. Start the frontend (second terminal)

```bash
cd Tracomproject
python3 -m http.server 8000
```

### 3. Open it

- **App:** http://localhost:8000
- API: http://localhost:8080/api/v1
- H2 console: http://localhost:8080/h2-console (JDBC URL `jdbc:h2:mem:lipamdogo`, user `sa`, empty password)

### 4. Log in

| Account | Password | Role | Portal |
|---|---|---|---|
| `admin@tracom.local` | `Admin@1234` | ADMIN | Staff console |
| `demo@lipamdogo.com` | `Demo@1234` | CUSTOMER | Customer portal |
| `amina.agent@tracom.local` | `Agent@1234` | AGENT | Partner portal |
| `quickmart@tracom.local` | `Merchant@1234` | MERCHANT | Partner portal |

Create more accounts via the admin console or `POST /api/v1/admin/users`.

## Frontend structure

```
index.html          Landing page with interactive calculator
login.html          Sign in (split layout)
signup.html         Create account
app.html            Customer portal (hash router)
admin.html          Staff console (hash router)
partner.html        Agent/merchant portal
css/app.css         Design system and all styles
js/config.js        API base URL, roles, demo accounts
js/api.js           Fetch wrapper, session, role guards
js/ui.js            Escaping, formatting, toasts, modals
js/phone.js         Charging phone SVG visual
js/landing.js       Landing calculator
js/auth.js          Login/signup logic
js/customer.js      Customer portal (home, phones, pay, activity, account)
js/admin.js         Staff console (overview, applications, customers, devices, catalog, payments, network, reports)
js/partner.js       Agent/merchant read-only views
legacy/             Old single-page UI (archived, not served by default)
```

## Portals

### Customer (`app.html`)
- **Home:** charging phone, ownership %, due label, finish date, streak, payoff planner, activity, contract switcher
- **Phones:** stepped flow → catalog → plan + live quote → review → e-sign → submit application
- **Pay:** amount chips, channel selector (MPESA mock), demo controls, idempotency key, receipt + phone charges on success, PENDING polling
- **Activity:** payment history with receipt modal, application tracking with cancel
- **Account:** profile, cash loan request, USSD simulator (`*456#`), sign out

### Staff (`admin.html`)
- **Overview:** KPI strip, application funnel, device fleet bar, 14-day collections chart, needs-attention list
- **Applications:** table with status filter, drawer, start-review / approve / reject with confirm
- **Customers:** searchable table, register modal, customer dashboard drawer
- **Devices:** filter by status/product, add modal, assign, release, change status with backend error messages
- **Catalog:** products, payment plans, payment modes with add forms
- **Payments:** status filter, post pending/success transactions, explain POSTED = only status that changes balances
- **Network:** merchants and agents with creation (agent = user + profile in one modal)
- **Reports:** collections by day, portfolio-at-risk buckets, CSV export for contracts and payments

### Partner (`partner.html`)
- **Agent:** profile, assigned devices
- **Merchant:** business info, agents list, devices list

## Backend API (key endpoints)

| Endpoint | Method | Roles | Purpose |
|---|---|---|---|
| `/auth/register` | POST | public | Create account |
| `/auth/login` | POST | public | Sign in, get JWT |
| `/users/me` | GET | authenticated | Own profile |
| `/users/me/dashboard` | GET | CUSTOMER | Dashboard + timeline |
| `/users/{id}/dashboard` | GET | ADMIN, LOAN_OFFICER | Customer dashboard |
| `/products` | GET | authenticated | Product catalog |
| `/financing/eligible-devices` | GET | authenticated | Available devices |
| `/financing/payment-plans` | GET | authenticated | Payment plans |
| `/financing/quote` | GET | authenticated | Server-authoritative quote |
| `/financing/applications` | POST | CUSTOMER | Submit application |
| `/financing/applications/me` | GET | CUSTOMER | My applications |
| `/admin/financing/applications` | GET | ADMIN, LOAN_OFFICER | All applications |
| `/admin/financing/applications/{id}/review` | POST | ADMIN, LOAN_OFFICER | Start review |
| `/admin/financing/applications/{id}/approve` | POST | ADMIN, LOAN_OFFICER | Approve → contract |
| `/admin/financing/applications/{id}/reject` | POST | ADMIN, LOAN_OFFICER | Reject with reason |
| `/loans` | GET | ADMIN, LOAN_OFFICER | All contracts |
| `/loans/me` | GET | CUSTOMER | My contracts |
| `/payments` | POST | CUSTOMER | Initiate payment |
| `/payments/me` | GET | CUSTOMER | My transactions |
| `/payments/{ref}/receipt` | GET | authenticated | Receipt |
| `/payments/{ref}/post` | POST | ADMIN, PAYMENTS_OPERATOR | Post SUCCESS payment |
| `/payments/{ref}/reverse` | POST | ADMIN, PAYMENTS_OPERATOR | Reverse posted payment |
| `/admin/payments` | GET | ADMIN, PAYMENTS_OPERATOR | All transactions |
| `/admin/users` | GET | ADMIN, LOAN_OFFICER | List users by role |
| `/admin/users` | POST | ADMIN | Create user |
| `/admin/devices` | GET/POST | ADMIN | Device inventory |
| `/admin/devices/{id}/assign` | POST | ADMIN | Assign device |
| `/admin/devices/{id}/release` | POST | ADMIN | Release device |
| `/admin/devices/{id}/status` | PATCH | ADMIN | Change device status |
| `/admin/merchants` | GET/POST | ADMIN | Merchants |
| `/admin/agents` | GET/POST | ADMIN | Agents |
| `/agents/me` | GET | AGENT | Agent profile |
| `/merchants/me` | GET | MERCHANT | Merchant profile |
| `/cash-loans` | POST | CUSTOMER | Request cash loan |

## Dev seed data

The database is in-memory and re-seeds on every boot. Accounts are created by `IdentityBootstrap` and `DomainBootstrap`. Passwords come from environment variables (defaults documented in `application.properties`). Override with:

```bash
TRACOM_ADMIN_PASSWORD=Admin@1234
TRACOM_DEMO_PASSWORD=Demo@1234
TRACOM_AGENT_PASSWORD=Agent@1234
TRACOM_MERCHANT_PASSWORD=Merchant@1234
TRACOM_JWT_SECRET=your-secret-key-at-least-32-bytes
```

## Documentation

- [docs/PHASE1-AUTH.md](docs/PHASE1-AUTH.md) — Authentication, JWT, RBAC
- [docs/PHASE2-INVENTORY.md](docs/PHASE2-INVENTORY.md) — Merchants, agents, device inventory
- [docs/PHASE3-FINANCING.md](docs/PHASE3-FINANCING.md) — Financing applications, approval, contracts
- [docs/PHASE4-PAYMENTS.md](docs/PHASE4-PAYMENTS.md) — Payment engine, posting, receipts

## Known seed data issue

The seeded payment plans do not add up: `durationDays × dailyAmount` is less than `price − deposit`. For example, Samsung Galaxy A15: KES 14,000 to finance, 90 days × KES 70 = KES 6,300. The UI always computes days remaining from `amountRemaining / dailyAmount`, not from `planDurationDays`. Corrected seed values would be:

| Plan | Daily | Days to cover KES 14,000 |
|---|---|---|
| Classic | KES 70 | 200 days |
| Smart | KES 60 | 234 days |
| Premium | KES 55 | 255 days |

Or adjust `dailyAmount` upward (e.g. Classic: KES 160/day × 90 = KES 14,400).

## License

Tracom Academy demo project. Not for production use.
