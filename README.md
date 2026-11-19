# M-Simu Finance

A device-financing (*Lipa Mdogo Mdogo*) and payment-management platform built at **Tracom Academy**. Customers pick a phone, pay a deposit and repay a little every day; staff review applications, manage a device fleet, run the payment engine and reconcile it against the provider's statement.

The phone loan is the business story. Underneath it is the infrastructure Tracom Services builds for banks: channels, agents and merchants, a device and terminal inventory, a transaction engine, and reconciliation.

> **Cashless.** Every repayment moves over M-PESA, whether the customer pays from their own phone or an agent sends the M-PESA request from a terminal. No cash is handled anywhere.
>
> **Prototype / simulation.** M-PESA is a mock adapter and the provider statement is simulated. No real money moves.

## What it does

| Area | Highlights |
|---|---|
| **Identity & security** | BCrypt passwords, stateless JWT, 7 roles, backend-enforced authorization, customers can only ever see their own records |
| **Network & inventory** | Merchants and their agents; catalogue products vs. individual physical devices (IMEI/serial) with a guarded lifecycle `AVAILABLE → RESERVED → FINANCED ⇄ OVERDUE → PAID_OFF` |
| **Financing** | Application → review → approve/reject. Terms are snapshotted at submission: installment = financed amount ÷ plan term, so a 90-day plan really ends in 90 days |
| **Payment engine** | `INITIATED → PENDING → SUCCESS/FAILED → POSTED`; idempotency keys, balance reservations against double-spend, partial payments, receipts |
| **Allocation ledger** | Every posted shilling is allocated to a specific day's installment; reversals replay the ledger instead of guessing |
| **Device lock** | Each financed phone locks itself when payments stop. Every payment issues a signed 12-digit unlock code that the phone verifies **offline**; old codes can't be replayed and dates can't be edited |
| **Agent terminals** | Cashless POS terminals at partner shops: the agent sends an **M-PESA STK push** to the customer's phone, the customer approves with their own PIN, and the terminal prints a receipt with the new unlock code. Terminal management console (online status, assignment, suspend/faulty, today's collections) |
| **Commissions & settlement** | Agents earn a share of every M-PESA collection from their terminal, merchants an override plus a fee per phone financed from their stock. Append-only ledger with automatic clawbacks on reversal. Settlement batches need **two people** (maker-checker) and pay out over M-PESA B2C/B2B in whole shillings, carrying the cents forward |
| **End-of-day** | Nightly arrears sweep moves contracts (and their devices) into and out of arrears; staff can run it on demand |
| **Reconciliation** | Import the (simulated) M-PESA statement and match by provider reference: matched, amount/status mismatch, missing on either side, duplicates. Never changes a balance |
| **Portals** | Customer portal, staff console (role-aware), partner portal for agents and merchants |

## Quick start

Requirements: Java 17+, Maven 3.9+.

```bash
# One process serves the frontend and the API (single origin, port 8080)
mvn spring-boot:run
```

Open **http://localhost:8080**. The API is at `http://localhost:8080/api/v1`; the H2 console is at `http://localhost:8080/h2-console` (JDBC URL `jdbc:h2:mem:lipamdogo`, user `sa`, no password).

The frontend has no build step. Its HTML/CSS/ES modules live at the repository root and are packaged into the jar (`target/classes/static`) by `maven-resources-plugin`, so Spring Boot serves the pages and the API from the same origin — the same way the Render deployment does. To work on the static files with a separate file server instead, set `window.MSIMU_API_BASE = "http://localhost:8080/api/v1"` before `js/config.js` loads; the CORS defaults already allow localhost origins.

The database is in-memory and rebuilt on every start, including the demo portfolio below.

### Run the tests

```bash
mvn test        # 113 tests: unit + Spring integration tests
```

## Demo accounts

These are the **local development defaults**. A public/hosted deployment sets its own seed passwords through the `TRACOM_*_PASSWORD` variables (see [Deployment on Render](#deployment-on-render)); the login page's one-click demo buttons only appear on `localhost`, so a hosted deployment never pre-fills or discloses credentials.

| Role | Email | Password | Lands on |
|---|---|---|---|
| Admin | `admin@tracom.local` | `Admin@1234` | Staff console (everything) |
| Loan officer | `loan.officer@tracom.local` | `Staff@1234` | Staff console: applications, customers |
| Payments operator | `payments@tracom.local` | `Staff@1234` | Staff console: payments, reconciliation |
| Customer | `demo@lipamdogo.com` | `Demo@1234` | Customer portal |
| Agent | `amina.agent@tracom.local` | `Agent@1234` | Partner portal → terminal TRM-0001 |
| Merchant | `quickmart@tracom.local` | `Merchant@1234` | Partner portal |

### Demo portfolio

On a fresh start `DemoPortfolioBootstrap` creates a 40-day book of business **through the real services** (apply → review → approve → pay), so balances, allocations, receipts and device states are all genuine engine output:

| Customer (password `Demo@1234`) | Story |
|---|---|
| `demo@lipamdogo.com` | Galaxy A15, Flex 200 Days, pays KES 70 daily; missed one day and caught up |
| `wanjiru@demo.msimu` | Tecno Camon 20, pays three days at a time; one payment still PENDING today |
| `otieno@demo.msimu` | Redmi 14C, stopped paying 10+ days ago, a failed attempt — **OVERDUE**, device in arrears |
| `achieng@demo.msimu` | Infinix Hot 40, missed the last few days — **OVERDUE** (1–7 day bucket) |
| `kiprono@demo.msimu` | Tecno Spark 10, big deposit, three lump sums — **PAID OFF** |
| `mutua@` / `halima@` / `peter@demo.msimu` | Application queue: submitted, under review, rejected |

It then runs the arrears sweep, imports the simulated M-PESA statement (with two deliberate exceptions) and runs reconciliation. For a blank system: `TRACOM_DEMO_PORTFOLIO=false mvn spring-boot:run`.

## A five-minute demo script

1. **Customer** (`demo@lipamdogo.com`): Home shows ownership, next due date and the payoff planner. **Pay** KES 70 → POSTED with a receipt; the phone "charges".
2. **Phones**: pick a device, choose a plan and watch the server quote the daily installment, then e-sign and submit.
3. **Loan officer**: Applications → open the new one → Start review → Approve. The contract and device handover happen in one transaction.
4. **Device lock.** As admin, open **Devices**, filter OVERDUE, open Otieno's Redmi and click **Load key into phone simulator**. The simulator opens showing the phone **locked**. Sign in as **Otieno** (`otieno@demo.msimu`) in another tab: Home shows *Phone lock: LOCKED*. Pay KES 1,876 to catch up and a **new unlock code** appears. Type it into the phone's keypad and it unlocks, with no server involved. Try it again and it's refused. Click *+3 days* a few times and the phone locks again.
5. **Agent terminal (cashless).** Sign in as **Amina** (`amina.agent@tracom.local`) and click **Open terminal**, then sign in on the terminal. Press **1**, type Achieng's number `0733000401`, **OK**, then **1** to pick her contract. The amount due is filled in. Press **OK**, **OK** (her registered M-PESA number) and **OK** to send. The *customer's phone* panel shows the M-PESA prompt: enter any 4-digit PIN and **Send**. The terminal shows APPROVED and prints a receipt with the M-PESA reference and the phone's new unlock code. Try it again and press **Cancel** on the phone: NOT PAID, nothing applied. Press **3** for the end-of-day report.
6. **Commission settlement (maker-checker).** After Amina's collection, sign in as the **payments operator** (`payments@tracom.local`). Open **Settlements** and click **Prepare settlement** with today as the cut-off. The batch shows one M-PESA payout per partner, and **Approve** is disabled for you because you prepared it. Sign in as **admin**, open the batch and click **Approve and pay out**: each line gets an M-PESA B2C/B2B reference. Back in Amina's portal, *My commission* shows the payout.
7. **Admin → Overview**: portfolio at risk, the collections chart, Needs attention. **Run end-of-day** re-evaluates arrears.
8. **Payments operator → Reconciliation**: every M-PESA statement line matches (customer-app and agent-terminal payments alike) except two planted exceptions, an amount mismatch and an unknown paybill payment, which are flagged but never auto-corrected.

## Architecture

```
Customers · Agents · Merchants          (portals, role-based access)
        │
Device inventory & lifecycle            (product ≠ physical device)
        │
Financing applications → contracts      (snapshotted terms)
        │
Payment engine → posting → allocations  (idempotent, reserved, reversible)
        │
End-of-day arrears sweep                (status only, never money)
        │
Reconciliation vs provider statement    (evidence only, never money)
        │
Commissions → settlement                (maker-checker M-PESA B2C/B2B payouts)
```

**Backend:** Spring Boot 3.3, Java 17, Spring Security + JWT, JPA/Hibernate, H2 (PostgreSQL mode).
**Frontend:** Plain HTML/CSS/ES modules, no build step.

`docker-compose.yml` starts a PostgreSQL 16 instance for future use; the application currently runs on in-memory H2.

### Frontend layout

```
index.html / login.html / signup.html   Landing page and auth
app.html      + js/customer.js          Customer portal
device.html   + js/device.js            Phone simulator (lock agent, offline)
pos.html      + js/pos.js               Agent terminal (cashless, M-PESA STK push)
js/unlock-codec.js                      Unlock-code verification (mirrors UnlockCodeCodec.java)
admin.html    + js/admin.js             Staff console
partner.html  + js/partner.js           Agent / merchant portal
js/api.js     fetch wrapper, session, 401 handling
js/ui.js      escaping, formatting, modals, drawers, toasts
css/app.css   design system
```

## Key API endpoints

All under `/api/v1`. Every endpoint except register and login needs `Authorization: Bearer <token>`.

| Endpoint | Method | Roles | Purpose |
|---|---|---|---|
| `/auth/register`, `/auth/login` | POST | public | Create account, sign in |
| `/users/me/dashboard` | GET | CUSTOMER | Contracts, balances, timeline |
| `/financing/quote` | GET | authenticated | Server-computed terms |
| `/financing/applications` | POST | CUSTOMER | Submit an application |
| `/admin/financing/applications/{id}/review` · `/approve` · `/reject` | POST | ADMIN, LOAN_OFFICER | Decision workflow |
| `/payments` | POST | CUSTOMER | Initiate payment (`Idempotency-Key` header) |
| `/payments/{ref}/post` · `/reverse` | POST | ADMIN, PAYMENTS_OPERATOR | Post / reverse |
| `/admin/payments` | GET | ADMIN, PAYMENTS_OPERATOR | All transactions |
| `/admin/operations/arrears-sweep` | POST | staff | Run end-of-day now |
| `/admin/reconciliation/feed/simulate` | POST | ADMIN, PAYMENTS_OPERATOR | Import simulated M-PESA statement |
| `/admin/reconciliation/runs` | POST | ADMIN, PAYMENTS_OPERATOR | Reconcile |
| `/loans/{id}/device-lock` | GET | owner, staff | Lock state and latest unlock code |
| `/admin/devices/{id}/lock` | GET | staff, TECHNICIAN | Lock state and code history |
| `/admin/devices/{id}/provisioning` | POST | ADMIN, TECHNICIAN | Device key for the lock agent |
| `/pos/{terminal}/customers` · `/collections` · `/collections/{ref}/receipt` · `/day-summary` | GET/POST | assigned AGENT | Cashless M-PESA collection at a terminal |
| `/admin/terminals` | GET/POST | ADMIN (read: PAYMENTS_OPERATOR) | Terminal management |
| `/admin/commissions` · `/admin/settlements` (+ `/{id}/approve`, `/{id}/reject`) | GET/POST | ADMIN, PAYMENTS_OPERATOR | Commission ledger and maker-checker payouts |
| `/agents/me/commissions` · `/merchants/me/commissions` | GET | AGENT, MERCHANT | Partner's own commission and payouts |
| `/admin/devices`, `/admin/merchants`, `/admin/agents` | GET/POST | ADMIN | Inventory and network |

## Configuration

| Variable | Default | Purpose |
|---|---|---|
| `TRACOM_JWT_SECRET` | dev-only value | JWT signing key (≥ 32 bytes) — **set this anywhere shared** |
| `TRACOM_ADMIN_PASSWORD`, `TRACOM_DEMO_PASSWORD`, `TRACOM_STAFF_PASSWORD`, `TRACOM_AGENT_PASSWORD`, `TRACOM_MERCHANT_PASSWORD` | see table above | Seed account passwords |
| `TRACOM_DEMO_PORTFOLIO` | `true` | Seed the demo portfolio |
| `TRACOM_H2_CONSOLE_ENABLED` | `true` (local) / `false` (prod profile) | Enable the H2 web console |
| `TRACOM_CORS_ALLOWED_ORIGINS` | `http://localhost:*,http://127.0.0.1:*` | Comma-separated CORS origin patterns; empty by default in the prod profile because the frontend is same-origin |
| `PORT` | `8080` | HTTP port; Render injects this, the app binds `0.0.0.0` |
| `TRACOM_ARREARS_CRON` | `0 5 0 * * *` | When the nightly arrears sweep runs |
| `TRACOM_LOCK_GRACE_DAYS` | `2` | Days after a missed due date before the phone locks |
| `TRACOM_AGENT_COMMISSION_RATE` / `_CAP` | `0.02` / `50` | Agent's share of each terminal collection, capped per payment |
| `TRACOM_MERCHANT_COMMISSION_RATE` / `TRACOM_MERCHANT_SALE_FEE` | `0.005` / `300` | Merchant's override and fee per phone financed from its stock |
| `TRACOM_SIMULATED_CUSTOMER_PHONE` | `true` | Lets the terminal play the customer's phone answering the M-PESA prompt. Set `false` with a live M-PESA callback |

## Deployment on Render

The presentation deployment runs as **one Render Docker Web Service**. Spring Boot serves the static pages *and* the `/api/v1` API from the same origin, so there is no second service, no cross-origin request and one URL to share with assessors.

```
Browser ──HTTPS──▶ Render edge (TLS) ──▶ Docker container
                                          └─ Spring Boot on 0.0.0.0:$PORT
                                              ├─ /              frontend pages (static)
                                              ├─ /css /js       frontend assets (static)
                                              ├─ /api/v1/**     JSON API, JWT-protected
                                              ├─ /api/v1/health deployment probe (public)
                                              └─ in-memory H2   rebuilt on every boot
```

**Why one service.** The frontend is plain HTML/CSS/ES modules with relative asset paths and no build step; `maven-resources-plugin` copies it into the jar at `static/`. Serving it from Spring Boot keeps API calls relative (`/api/v1`), keeps the JWT in the same-origin `sessionStorage`, removes the localhost CORS dependency and avoids a free static site plus a separate API host with its own CORS and mixed-content rules.

**Why Docker.** Java is not a native Render runtime, so the service builds `Dockerfile` (multi-stage Java 17 + Maven → JRE 17, non-root user).

### Files that implement the deployment

| File | Purpose |
|---|---|
| `Dockerfile` | Multi-stage build; packages the jar; runs as UID 1001; reads `PORT` |
| `.dockerignore` | Keeps `.git`, `target`, docs and local env files out of the image |
| `render.yaml` | Blueprint: free Docker web service, `/api/v1/health` check, environment variables |
| `src/main/resources/application-prod.properties` | `prod` profile: H2 console off, JWT secret required, quiet error responses, graceful shutdown |
| `src/main/java/com/tracom/lipamdogo/controller/HealthController.java` | Public `GET /api/v1/health` |
| `docs/render-env.example` | Checklist of every environment variable (placeholders only) |
| `pom.xml` | Copies `*.html`, `*.svg`, `css/`, `js/` into the jar's static resources |
| `js/config.js` | Relative `API_BASE` (`/api/v1`) with an optional `window.MSIMU_API_BASE` override |

### Deploy through the Blueprint (recommended)

1. Push this repository, including `Dockerfile` and `render.yaml`, to GitHub.
2. In the Render Dashboard choose **New → Blueprint**, connect `wanjikupng/msimu-finance` and select the branch that contains the deployment commit (`main` after merging).
3. Render reads `render.yaml`. It generates `TRACOM_JWT_SECRET`; for each `sync: false` entry it asks you to type a seed password. Use a **private** value for each account (for example `openssl rand -base64 18`); do not reuse the publicly documented development passwords for the administrator account. The one-click demo buttons are hidden outside `localhost`, so the deployed login page does not pre-fill them. Keep the values outside the repository and share them with assessors separately.
4. Click **Apply**. The first image build typically takes a few minutes on the free plan; the service is routed traffic only after `/api/v1/health` returns 200.
5. Open the service URL — `https://msimu-finance.onrender.com` if that name is available, otherwise the name shown in the Dashboard.

### Deploy manually (without the Blueprint)

**New → Web Service → Build and deploy from a Git repository**, then:

| Setting | Value |
|---|---|
| Language / Runtime | Docker |
| Branch | the deployment branch |
| Dockerfile path | `./Dockerfile` |
| Docker build context | `.` (repository root) |
| Compute plan | Free |
| Health check path | `/api/v1/health` |
| Auto-deploy | On commit (or manual) |

Add the environment variables from the table below in **Environment**. `PORT` is injected by Render and must not be set by hand.

> If you connect the repository as a **Public Git Repository** instead of installing the Render GitHub app, Render does not support auto-deploys for that service. Deploy each change with **Manual Deploy → Deploy latest commit**. The Blueprint route (which uses the GitHub app) keeps auto-deploy.

### Required environment variables

| Variable | Required | Value for the presentation deployment |
|---|---|---|
| `SPRING_PROFILES_ACTIVE` | yes | `prod` |
| `TRACOM_JWT_SECRET` | yes | Random ≥ 32 bytes. `render.yaml` uses `generateValue: true`; manually, `openssl rand -base64 48` |
| `TRACOM_ADMIN_PASSWORD` | yes | Private value (not the README development default) |
| `TRACOM_DEMO_PASSWORD` | yes | Private value shared with the customer demo accounts |
| `TRACOM_STAFF_PASSWORD` | yes (portfolio on) | Private value for the seeded loan-officer and payments-operator accounts |
| `TRACOM_AGENT_PASSWORD` | yes | Private value shared with the demo agents |
| `TRACOM_MERCHANT_PASSWORD` | yes | Private value shared with the demo merchants |
| `TRACOM_DEMO_PORTFOLIO` | no | `true` (default) keeps the synthetic book of business |
| `TRACOM_H2_CONSOLE_ENABLED` | no | `false`; the `prod` profile disables it regardless |
| `PORT` | injected | Render sets it; the app listens on it and binds `0.0.0.0` |
| `TRACOM_CORS_ALLOWED_ORIGINS` | only for split hosting | Comma-separated origins when the frontend is not served by this service |

The seed passwords are configured **only** in the Render Dashboard, never in the repository. `js/config.js` keeps the development defaults for local convenience, but the login page only renders the one-click demo buttons when it is served from `localhost`, so the deployed site neither pre-fills nor reveals credentials.

### Health check

`GET /api/v1/health` is public and returns no application data:

```json
{"status":"UP","application":"lipa-mdogo-mdogo","database":"UP","readiness":"ACCEPTING_TRAFFIC","timestamp":"2026-01-01T00:00:00Z"}
```

It runs `SELECT 1` against the datasource and reports Spring Boot's readiness state. During startup (including the demo portfolio seed) it answers **503** with `"status":"STARTING"`; if the database is unreachable it answers **503** with `"status":"DOWN"`. Both are exactly what Render's HTTP health check should see, so traffic is routed only to a fully seeded instance.

### Redeploying after a GitHub change

- **Automatic:** `autoDeployTrigger: commit` in `render.yaml` means every push to the deployed branch builds and deploys. The health check gates the switch to the new instance.
- **Manual:** Dashboard → service → **Manual Deploy → Deploy latest commit**.
- **Rollback:** Dashboard → **Rollbacks** (the free plan keeps the two most recent deploys).

### Free-plan behaviour to expect during the presentation

- The service **spins down after 15 minutes without traffic** and takes roughly a minute to wake on the next request. Open the URL a few minutes before assessors arrive.
- 512 MB RAM / 0.1 CPU. The image caps the JVM heap at 60% of the container (`-XX:MaxRAMPercentage=60`) and uses the serial collector, leaving room for metaspace and native memory.
- 750 free instance-hours per workspace per month; a spun-down service does not consume them.
- The filesystem is ephemeral, and so is the H2 database.

### Database persistence

The deployment keeps the application's **in-memory H2** database. It is created from `schema.sql`/`data.sql` on every boot, and `DemoPortfolioBootstrap` rebuilds the same 40-day demo book through the real services. Consequences:

- A restart, redeploy, crash or free-plan spin-down **resets all data** to the seeded demo state; anything an assessor creates is lost.
- The demo always starts from a known, reproducible state — an advantage for a presentation.
- The H2 console is disabled in the `prod` profile and is not exposed publicly.

**PostgreSQL was evaluated and intentionally not enabled for this deployment.** The schema uses portable `GENERATED BY DEFAULT AS IDENTITY` columns and the entities are plain JPA, so the SQL is close to portable — but `data.sql` deletes every table on each start (`spring.sql.init.mode=always`), which would wipe a persistent database just as surely as H2 resets. A correct switch needs versioned migrations (Flyway/Liquibase), a profile-specific datasource, and a full re-run of the integration suite; a free Render Postgres also expires 30 days after creation. Shipping that untested to a presentation deployment would add risk without adding persistence, so it is left as future work rather than a claim.

### Prototype limits (unchanged by deployment)

This is an academic prototype. M-PESA is a **mock adapter** with a simulated provider statement; there are no Safaricom credentials, no real STK push, no real B2C/B2B payout and no real money. Every "payment" is engine output against synthetic data. The public URL is a demonstration environment, not a production financial service.

## Documentation

Design notes per phase, in [`docs/`](docs):

1. [Authentication, JWT, roles](docs/PHASE1-AUTH.md)
2. [Merchants, agents, device inventory](docs/PHASE2-INVENTORY.md)
3. [Financing applications and approval](docs/PHASE3-FINANCING.md)
4. [Payment engine, reservations, allocations](docs/PHASE4-PAYMENTS.md)
5. [Reconciliation](docs/PHASE5A-RECONCILIATION.md)
6. [Operations hardening: end-of-day, terms, demo portfolio](docs/PHASE5.5-OPERATIONS.md)
7. [Pay-as-you-go device lock](docs/PHASE6-DEVICE-LOCK.md)
8. [Cashless agent terminals](docs/PHASE7-AGENT-TERMINALS.md)
9. [Commissions and settlement](docs/PHASE8-SETTLEMENT-COMMISSIONS.md)

**Testing it yourself:** [step-by-step testing guide](docs/TESTING-GUIDE.md), from cloning the repo to checking every feature with the demo accounts.

## Roadmap

- **Earned grace days:** a good payment streak earns extra days before the phone locks.
- **Payout reconciliation:** match settlement payouts against the M-PESA B2C/B2B statement, the same way collections are reconciled.
- **Live M-PESA sandbox:** swap the mock adapter for Safaricom Daraja (STK Push, C2B).

## License

Tracom Academy project. Not for production use.
