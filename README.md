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
| **End-of-day** | Nightly arrears sweep moves contracts (and their devices) into and out of arrears; staff can run it on demand |
| **Reconciliation** | Import the (simulated) M-PESA statement and match by provider reference: matched, amount/status mismatch, missing on either side, duplicates. Never changes a balance |
| **Portals** | Customer portal, staff console (role-aware), partner portal for agents and merchants |

## Quick start

Requirements: Java 17+, Maven 3.9+, Python 3 (only to serve the static frontend).

```bash
# 1. Backend (port 8080)
mvn spring-boot:run

# 2. Frontend (second terminal, port 8000)
python3 -m http.server 8000
```

Open **http://localhost:8000**. The API is at `http://localhost:8080/api/v1`; the H2 console is at `http://localhost:8080/h2-console` (JDBC URL `jdbc:h2:mem:lipamdogo`, user `sa`, no password).

The database is in-memory and rebuilt on every start, including the demo portfolio below.

### Run the tests

```bash
mvn test        # 103 tests: unit + Spring integration tests
```

## Demo accounts

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
6. **Admin → Overview**: portfolio at risk, the collections chart, Needs attention. **Run end-of-day** re-evaluates arrears.
7. **Payments operator → Reconciliation**: every M-PESA statement line matches (customer-app and agent-terminal payments alike) except two planted exceptions, an amount mismatch and an unknown paybill payment, which are flagged but never auto-corrected.

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
| `/admin/devices`, `/admin/merchants`, `/admin/agents` | GET/POST | ADMIN | Inventory and network |

## Configuration

| Variable | Default | Purpose |
|---|---|---|
| `TRACOM_JWT_SECRET` | dev-only value | JWT signing key (≥ 32 bytes) — **set this anywhere shared** |
| `TRACOM_ADMIN_PASSWORD`, `TRACOM_DEMO_PASSWORD`, `TRACOM_AGENT_PASSWORD`, `TRACOM_MERCHANT_PASSWORD` | see table above | Seed account passwords |
| `TRACOM_DEMO_PORTFOLIO` | `true` | Seed the demo portfolio |
| `TRACOM_ARREARS_CRON` | `0 5 0 * * *` | When the nightly arrears sweep runs |
| `TRACOM_LOCK_GRACE_DAYS` | `2` | Days after a missed due date before the phone locks |
| `TRACOM_SIMULATED_CUSTOMER_PHONE` | `true` | Lets the terminal play the customer's phone answering the M-PESA prompt. Set `false` with a live M-PESA callback |

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

## Roadmap

- **Earned grace days:** a good payment streak earns extra days before the phone locks.
- **Settlement & commissions:** daily merchant settlement from the M-PESA collections, and agent commissions per terminal collection.
- **Live M-PESA sandbox:** swap the mock adapter for Safaricom Daraja (STK Push, C2B).

## License

Tracom Academy project. Not for production use.
