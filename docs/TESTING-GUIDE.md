# Testing guide: from GitHub to every feature

A step-by-step walkthrough for testing M-Simu Finance on your own computer:
getting the code, running the automated tests, starting the system, and
checking every feature by hand with the demo accounts.

> Everything is simulated. M-PESA is a mock adapter and no real money moves.
> The database is in memory, so **every backend restart resets the demo data**.

## 1. One-time setup

Install:

| Tool | Why | Where |
|---|---|---|
| **Git** | Download the code | git-scm.com |
| **Java JDK 17 or newer** | Runs the backend | adoptium.net (Temurin) |
| **Maven 3.9+** | Builds and runs the backend | maven.apache.org (IntelliJ also includes it) |
| **Python 3** | Serves the web pages | python.org |

Check them in a terminal (Command Prompt or PowerShell on Windows):

```bash
git --version
java -version      # must show 17 or higher
mvn -version
python --version   # on Windows you may need: py --version
```

## 2. Get the code from GitHub

The repository is private: you must be signed in to GitHub with an account
that has access.

**With Git:**

```bash
git clone https://github.com/wanjikupng/msimu-finance.git
cd msimu-finance
```

If Git asks for a password, use a GitHub **Personal Access Token**
(GitHub → Settings → Developer settings → Personal access tokens), not your
account password. GitHub Desktop also works.

**Without Git:** open the repository on GitHub, click the green **Code**
button, choose **Download ZIP**, and unzip it.

## 3. Run the automated tests

From the `msimu-finance` folder:

```bash
mvn test
```

Expected: `Tests run: 109, Failures: 0, Errors: 0` and **BUILD SUCCESS**.
The first run downloads libraries and takes a few minutes.

| Test class | What it proves |
|---|---|
| `AuthIntegrationTest`, `UserAccountFlowIntegrationTest` | Sign-up, login, JWT |
| `AuthorizationIntegrationTest` | Each role sees only what it should; customers only their own records |
| `FinancingCalculationServiceTest`, `FinancingTermsSnapshotIntegrationTest`, `FinancingWorkflowIntegrationTest` | Quotes, frozen terms, review → approve/reject |
| `PaymentEngineIntegrationTest`, `PaymentReservationTest`, `PaymentAllocationTest`, `LoanServiceTest`, `MultiContractIntegrationTest` | Idempotency, no overpaying, allocations, reversals |
| `ArrearsSweepIntegrationTest` | End-of-day overdue / recovered |
| `ReconciliationIntegrationTest` | Statement matching and exceptions |
| `UnlockCodeCodecTest`, `DeviceLockIntegrationTest` | Unlock codes, replay protection, lock states |
| `MerchantAgentDeviceIntegrationTest` | Merchants, agents, device lifecycle |
| `AgentTerminalIntegrationTest` | Cashless STK push collections at terminals |
| `CommissionSettlementIntegrationTest` | Commission, clawbacks, maker-checker payouts |

## 4. Start the system

Two terminals, both in the project folder:

```bash
# Terminal 1 — backend (wait for "Started LipaMdogoApplication")
mvn spring-boot:run

# Terminal 2 — web pages
python -m http.server 8000      # Windows: py -m http.server 8000
```

Open **http://localhost:8000** in Chrome.

In IntelliJ you can instead open the folder and run `LipaMdogoApplication`.

## 5. Demo accounts

| Role | Email | Password |
|---|---|---|
| Admin | `admin@tracom.local` | `Admin@1234` |
| Loan officer | `loan.officer@tracom.local` | `Staff@1234` |
| Payments operator | `payments@tracom.local` | `Staff@1234` |
| Customer (main demo) | `demo@lipamdogo.com` | `Demo@1234` |
| Customer: Wanjiru (has a PENDING payment) | `wanjiru@demo.msimu` | `Demo@1234` |
| Customer: Otieno (OVERDUE, phone locked) | `otieno@demo.msimu` | `Demo@1234` |
| Customer: Achieng (OVERDUE, phone `0733000401`) | `achieng@demo.msimu` | `Demo@1234` |
| Customer: Kiprono (PAID OFF) | `kiprono@demo.msimu` | `Demo@1234` |
| Applicants: Mutua, Halima, Peter | `mutua@demo.msimu`, `halima@demo.msimu`, `peter@demo.msimu` | `Demo@1234` |
| Agent: Amina (terminal TRM-0001) | `amina.agent@tracom.local` | `Agent@1234` |
| Agents: Brian (TRM-0002), Faith (TRM-0003) | `brian.agent@tracom.local`, `faith.agent@tracom.local` | `Agent@1234` |
| Merchant: QuickMart | `quickmart@tracom.local` | `Merchant@1234` |

Tip: use a normal window and an Incognito window to be signed in as two
people at once.

## 6. Feature checklist

### A. Sign-up, login and security

1. On the landing page click **Sign up** and create a customer. You land in the customer portal.
2. Sign out and log in with a wrong password. You get an error.
3. Logged in as `demo@lipamdogo.com`, open `http://localhost:8000/admin.html`. A customer does **not** get the staff console.
4. Open `http://localhost:8080/api/v1/admin/payments` in the browser. Refused (401): no login token.

### B. Customer portal (`demo@lipamdogo.com`)

1. **Home**: ownership % (about 30%), next due date, payoff planner, and the **Phone lock** card with an unlock code.
2. **Pay** KES 70 over M-PESA (simulated). It shows **POSTED** with a receipt; ownership rises and a **new unlock code** appears on Home.
3. **Activity**: payment history and receipts.
4. **Phones**: pick a phone and a plan (Flex 200 / Standard 120 / Fast 90 days); the server calculates the daily amount. **Review → Sign** (e-signature) **→ Submit**. Keep this application for step C.
5. **Account → USSD simulator**: reply `1` balance, `2` pay, `3` mini statement, `4` next due date.

### C. Loan officer (`loan.officer@tracom.local`)

1. **Applications**: open the application from B4.
2. **Start review** (UNDER_REVIEW), then **Approve**. The contract is created and the phone handed over in one step.
3. Open Mutua's or Halima's application and **Reject** it with a reason.
4. **Customers**: open a customer to see contracts and balances.
5. Log in as the customer from B4: Home now shows the new contract and its first unlock code.

### D. Device lock (admin + Otieno)

1. As **admin**: **Devices**, filter **OVERDUE**, open **Otieno's Redmi 14C**, click **Load key into phone simulator**. The simulator tab shows the phone **LOCKED**.
2. In Incognito, log in as **Otieno**: Home shows *Phone lock: LOCKED*.
3. Pay **KES 1,876** to catch up. A **new 12-digit unlock code** appears.
4. Type the code into the simulator keypad. The phone **unlocks**, with no server involved.
5. Type the **same code again**. **Refused**: old codes can't be replayed.
6. Move the simulator's clock forward a few days. Once the paid-up date plus the 2 grace days passes, the phone **locks again**.

### E. Agent terminal, cashless (`amina.agent@tracom.local`)

1. Partner portal → **My terminal → Open terminal**; sign in on the terminal with the same email and password.
2. Press **1** (Collect payment), type Achieng's number `0733000401`, **OK**, then **1** to pick her contract. The amount due is filled in.
3. **OK**, **OK** (her M-PESA number), **OK** to send. The terminal shows **WAITING**.
4. In the **customer's phone** panel, enter any 4-digit PIN and press **Send**. The terminal shows **APPROVED** and prints a slip with the M-PESA reference and the new unlock code.
5. Repeat, but press **Cancel** on the customer's phone. **NOT PAID**; nothing is applied.
6. **2** reprints the last receipt, **3** shows the end-of-day report (collections and today's commission), **9** signs out.
7. Sign in to TRM-0001 as Brian. Refused: it is assigned to Amina.

### F. Terminal management (admin)

1. **Terminals**: each terminal's merchant, agent, online/offline (heartbeat within 5 minutes) and today's M-PESA collections.
2. **Suspend** TRM-0001, then try to collect on Amina's terminal. Refused. Set it back to **ACTIVE**.
3. **Register terminal**; try assigning a terminal to an agent of a **different merchant**. Refused.

### G. Payments (`payments@tracom.local`)

1. **Payments**: every transaction, from the customer app and from agent terminals.
2. Find **Wanjiru's PENDING** payment and **Resolve** it (post it or mark it failed).
3. Open a POSTED payment and **Reverse** it. The balance is restored; if it was a terminal payment, a negative **clawback** appears in the commission ledger (Settlements).

### H. Reconciliation (payments operator)

1. **Reconciliation** → **Fetch M-PESA statement (simulated)** → **Run reconciliation**.
2. Everything matches except two planted exceptions: an **amount mismatch** and an **unknown paybill payment**. They are flagged, never auto-corrected.

### I. Commission settlement, maker-checker

1. Do a terminal collection first (section E).
2. As the **payments operator**: **Settlements → Prepare settlement**, cut-off today. One payout line per agent and per merchant.
3. **Approve and pay out** is **disabled** for you: you prepared the batch.
4. As **admin**: open the batch, **Approve and pay out**. Each line gets an `MPESA-B2C-…` / `MPESA-B2B-…` reference, in whole shillings with the cents carried forward.
5. **Prepare settlement** again straight away: *"Nothing to settle"*. Nothing is paid twice.
6. Optional: prepare a batch and **Reject** it; the commission returns to the ledger.

### J. Partner portals

1. **Amina** → **My commission**: owed, on its way, paid to her M-PESA, with references.
2. **QuickMart** (`quickmart@tracom.local`): its own commission (0.5% override + KES 300 per phone financed), payouts, each agent's figures and terminals. No other partner's payouts.

### K. Admin extras (`admin@tracom.local`)

1. **Overview**: portfolio at risk, 14-day collections chart, *Needs attention*.
2. **Run end-of-day**: customers behind become OVERDUE, customers who caught up return to ACTIVE. No money moves.
3. **Catalog** (add a product or plan), **Network** (add/suspend a merchant or agent), **Devices** (add a device by IMEI, change status), **Reports**.
4. **Kiprono**: contract **PAID OFF**; his phone is permanently unlocked (OWNED).

### L. Database (optional)

`http://localhost:8080/h2-console` — JDBC URL `jdbc:h2:mem:lipamdogo`, user `sa`,
no password. Try:

```sql
SELECT * FROM payment_transactions;
SELECT * FROM payment_allocations;
SELECT * FROM commission_entries;
```

## 7. Troubleshooting

| Problem | Fix |
|---|---|
| "Cannot reach the server on port 8080" | The backend isn't running; start Terminal 1 again |
| Blank page or 404 | Run the Python server from the project folder (the one containing `index.html`) |
| "Port 8080 already in use" | Close the old backend window, or restart the computer |
| Demo data looks wrong | Restart the backend; the data resets |
| `mvn` not found | Install Maven and add it to PATH, or use IntelliJ's Maven |
| Blank system with no demo data wanted | `TRACOM_DEMO_PORTFOLIO=false mvn spring-boot:run` |
