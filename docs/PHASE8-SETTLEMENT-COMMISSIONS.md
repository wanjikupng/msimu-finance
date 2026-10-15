# Phase 8 — Commissions and settlement

## Who owns the money

Customer repayments belong to **M-Simu Finance**, which financed the phone.
They land in M-Simu's M-PESA account, whether the customer paid from the app
or approved an agent's prompt. Partners are paid **commission** for the
business they bring, and **settlement** is how that commission is paid out:
over M-PESA, never in cash.

## Commission plan

| Who | Earns | Default | Setting |
|---|---|---|---|
| Agent | share of each M-PESA collection sent from their terminal | **2%**, capped at **KES 50** per payment | `TRACOM_AGENT_COMMISSION_RATE`, `TRACOM_AGENT_COMMISSION_CAP` |
| Merchant | override on collections at its terminals | **0.5%** | `TRACOM_MERCHANT_COMMISSION_RATE` |
| Merchant | flat fee per phone financed from its stock | **KES 300** | `TRACOM_MERCHANT_SALE_FEE` |

A customer paying from their own app earns nobody commission.

## The ledger (`commission_entries`)

- Commission accrues when a payment **posts**. A prompt that is only sent,
  then declined, earns nothing.
- The ledger is **append-only.** If a payment is reversed, nothing is edited:
  a negative **clawback** entry is added for each original entry
  (`reverses_entry_id`), and the two net out.
- Statuses: `ACCRUED` (waiting for a run) → `IN_SETTLEMENT` (in a batch
  awaiting approval) → `SETTLED` (paid).

## Settlement

```
Run (maker, e.g. payments operator)
  · every ACCRUED entry earned up to the cut-off date
  · netted per partner: earned − clawbacks
  · rounded DOWN to whole shillings (M-PESA pays whole KES); the cents carry forward
  · one payout line per agent (M-PESA B2C) and per merchant (M-PESA B2B)
  · entries locked IN_SETTLEMENT, so no other run can take them
        │
Approve (checker, must be a DIFFERENT person)  ── or ──  Reject
  · each line paid over M-PESA                           · nothing paid
  · paid line   → its entries SETTLED                    · all entries back to ACCRUED
  · failed line → its entries back to ACCRUED (next run)
```

| Rule | Why |
|---|---|
| **Maker-checker:** the person who prepared a batch can't approve it | Standard banking control: no single person can send money out |
| **Entries locked while in a batch** | A second run can't pay the same commission twice (the second run returns "Nothing to settle") |
| **Whole shillings** | M-PESA B2C/B2B can't send cents. Leftover cents become a `ROUNDING_CARRY` pair, minus in this batch and plus in the next, so `earned − clawbacks − carried = paid` exactly and nobody loses a cent |
| **Net ≤ 0 is carried forward** | A partner whose clawbacks exceed their earnings isn't paid a negative amount; the balance waits |
| **A failed payout returns to the ledger** | For example, a payee with no valid M-PESA number. The commission isn't lost and is retried next run |

## Screens

- **Staff console → Settlements**: what's owed, awaiting approval and paid;
  the commission plan; settlement batches with **Prepare settlement**, then
  **Approve and pay out** or **Reject** (the button is disabled for the
  person who prepared the batch); what each partner is owed; and the
  commission ledger, with clawbacks and carried cents marked.
- **Agent portal → My commission**: owed, on its way, paid to their M-PESA,
  earned today, payouts with M-PESA references, and the entries behind them.
- **Merchant portal → Commission & settlement**: the business's own
  commission and payouts, plus each agent's figures.
- **Terminal → End of day**: the agent's commission earned today.

## API

| Endpoint | Who |
|---|---|
| `GET /api/v1/admin/commissions`, `/commissions/plan`, `/commissions/balances` | ADMIN, PAYMENTS_OPERATOR |
| `GET/POST /api/v1/admin/settlements`, `POST /settlements/{id}/approve`, `/reject` | ADMIN, PAYMENTS_OPERATOR (approver ≠ preparer) |
| `GET /api/v1/agents/me/commissions` | AGENT |
| `GET /api/v1/merchants/me/commissions` | MERCHANT |

## Demo data

The seed pays out everything earned up to yesterday: the run is prepared by
the payments operator (Daniel Kiprop) and approved by the administrator.
Today's commission is still owed, ready for a live run.

## Prototype limits

- Payouts use a mock M-PESA B2C/B2B adapter that answers at once. With
  Daraja, each payout's result arrives on a callback, and payouts would be
  reconciled against the B2C statement like collections are.
- One plan for everyone. Per-merchant plans or tiered rates would need a
  `commission_plans` table.

## Tests

`CommissionSettlementIntegrationTest`:

- Commission accrues on terminal collections (including the cap) but not on
  app payments.
- A reversal claws commission back to net zero.
- A merchant earns its sale fee.
- Maker-checker is enforced.
- Payouts are whole shillings and the figures balance exactly.
- A second run can't settle the same commission again.
- A rejected batch returns its commission to the ledger.
- A merchant sees only its own payouts.
- Partners can't run settlement.
