# Phase 6 — Pay-as-you-go device lock

A financed phone keeps working while the customer keeps paying. When payments
stop it locks, and a payment unlocks it again with a short code. The code works
with **no data connection**, so it suits customers who live where coverage
is patchy or whose data bundle has run out.

## How it fits together

```
Approval ──► DeviceLockKey (256-bit secret, anchor date)          server
   │                    │
   │                    └──► technician loads the key into the phone's lock agent
   ▼
Payment posts ──► paid-through date moves ──► new UnlockCode ──► SMS / portal
                                                     │
Customer types the 12-digit code on the phone ──► lock agent verifies offline
                                                     │
                                       phone unlocked until the coded date
```

| When | Server does | Phone does |
|---|---|---|
| Contract approved | Creates the device key and code #1: unlocked until first due date + grace | Loaded with the key at handover |
| Payment completes an installment | New code with the later date | Unlocks until that date once the code is entered |
| Partial payment (installment not complete) | Nothing: the date didn't move | Nothing |
| Payment reversed | New code with the earlier date | Takes the new code (higher number) |
| Paid off | Permanent code (`999`) | Unlocked for good |
| Customer stops paying | Nothing to do: the date simply passes | Locks itself after the date |

The server never has to reach the phone, and the phone never has to reach the
server.

## The code

```
 0 1 6   0 0 7   3 5 2 1 0 3
 └ seq ┘ └days┘ └─── MAC ───┘
```

- **seq (001–999)**: the phone only accepts a code numbered higher than the
  last one it accepted. A customer can't re-enter an old, longer code after a
  reversal, and a code can't be used twice.
- **days (000–998)**: "unlocked until" as days after the contract's anchor
  date; **999** means permanent.
- **MAC (6 digits)**: HMAC-SHA256 over `MSIMU-UNLOCK-V1|<device code>|<seq><days>`
  with the device's key, cut to six digits using RFC 4226 (HOTP) dynamic
  truncation.

The first six digits are readable, but changing them invalidates the MAC.
Without the key, forging a code means guessing six digits (1 in a million),
and the phone blocks entry for 30 minutes after five wrong codes. A code made
for one phone fails on every other phone.

The scheme has three implementations, all checked against the same
known-answer vectors:

- `UnlockCodeCodec.java`: the server, which issues codes.
- `js/unlock-codec.js`: the phone, which verifies them using WebCrypto.
- An independent Python HMAC computation, used to produce the vectors in
  `UnlockCodeCodecTest`.

## Lock rule and grace

`unlocked until = next payment due date + grace days` (default **2**,
`TRACOM_LOCK_GRACE_DAYS`).

| Day | Contract | Phone |
|---|---|---|
| Due date, unpaid | ACTIVE | UNLOCKED |
| Due + 1, + 2 | OVERDUE | **GRACE**: warning banner, still usable |
| Due + 3 | OVERDUE | **LOCKED** until a payment brings it current |

The contract turns OVERDUE first (the end-of-day sweep from Phase 5.5) and
the phone locks only after the grace period, so a customer gets a warning
before losing their phone. Emergency calls stay available on the lock screen.

## API

| Endpoint | Who | Returns |
|---|---|---|
| `GET /api/v1/loans/{contractId}/device-lock` | the contract's customer, staff | state, unlocked-until, latest code |
| `GET /api/v1/admin/devices/{deviceId}/lock` | ADMIN, LOAN_OFFICER, PAYMENTS_OPERATOR, TECHNICIAN | the same, plus every code ever issued |
| `POST /api/v1/admin/devices/{deviceId}/provisioning` | ADMIN, TECHNICIAN | the device key, for loading into the lock agent (logged) |

Customers never receive the key or the code history.

## Screens

- **Customer home → Phone lock**: state in words, the latest code (Copy), a
  link to the phone, and "Pay now" when late. After a payment, the Pay screen
  shows the new code at once.
- **Staff → Devices → device drawer → Device lock**: state, current code, full
  history with the payment that caused each code. Admins can **Load key into
  phone simulator**.
- **`device.html` — phone simulator**: the lock agent. It holds the key in
  browser storage (standing in for the phone's secure element) and verifies
  codes locally; it has no server calls. Demo controls move the phone's clock
  forward to show it locking.

## Prototype limits

- Keys are stored in the database. In production they'd live in a hardware
  security module (Futurex, one of Tracom's partners, makes them).
- A contract has 999 sequence numbers. A daily payer on the longest plan uses
  about 200; after 999, codes stop and the device needs re-provisioning
  (logged).
- The simulator is a browser page. On a real phone the lock agent runs as a
  device-owner app (for example, Android's Device Lock Controller) that a
  customer can't remove.
- Earned grace days (more grace for a good payment streak) are a natural next
  step.

## Tests

- `UnlockCodeCodecTest`: known-answer vectors, tamper resistance, other
  device or key, input formats, range checks.
- `DeviceLockIntegrationTest`: provisioning on approval, codes follow the
  paid-through date (partial payment, full installment, reversal, payoff),
  grace → locked, and access control.
- Browser run (Playwright): load the key → phone locked → forged code rejected
  → pay → new code unlocks → replay refused → clock forward locks again.
