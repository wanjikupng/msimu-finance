# Phase 7 — Cashless agent terminals

**M-Simu Finance doesn't handle cash.** Every repayment moves over M-PESA.
Agents at partner shops still matter: many customers want help, aren't
comfortable with apps, or are paying for a relative. So the agent's terminal
is an **M-PESA collection point**, not a cash till:

1. The agent finds the customer (phone number or contract number).
2. The terminal suggests the amount due now; the agent confirms it.
3. The platform asks M-PESA to send a **payment prompt (STK push)** to the
   customer's phone.
4. The customer checks the amount and approves it with **their own M-PESA
   PIN**. The agent never touches money or sees the PIN.
5. The payment posts at once. The terminal prints a receipt with the M-PESA
   reference, the new balance and the phone's **new unlock code** (Phase 6).

There's no cash float, no cash-up and no counting at the end of the day. The
money is already in the business's M-PESA account.

## Model

| Concept | Meaning |
|---|---|
| `Terminal` | A POS device (here an Ingenico Move/5000, the brand Tracom distributes) deployed at a merchant, with at most one operating agent. Status `ACTIVE`, `SUSPENDED`, `FAULTY` or `RETIRED`. |
| `PaymentTransaction.channel` | Always `MPESA`: the money rail. |
| `PaymentTransaction.origin` | Where the payment was started: `CUSTOMER_APP`, `AGENT_TERMINAL` or `BACK_OFFICE`. |
| `terminal`, `agent`, `payer_msisdn` | Who sent the prompt, from which terminal, to which number (stored, shown masked `2547*****401`). |

## Payment flow

```
Agent terminal ── POST /pos/TRM-0001/collections ──► PaymentService.initiateAtTerminal
                                                      │  INITIATED → PENDING (STK push sent)
                                                      │  amount reserved, nothing applied
Customer phone: "Pay KES 252 to M-SIMU FINANCE?" PIN
                                                      ▼
M-PESA callback (simulated) ─────────────────────► PaymentService.completeStkPush
        approved → SUCCESS → POSTED (allocations, receipt, unlock code)
        declined → FAILED (reservation released, balance untouched)
Terminal polls the status ──► APPROVED + printed slip, or NOT PAID
```

Agent collections reuse the Phase 4 engine as-is: idempotency keys (a
network retry never sends a second prompt), balance reservations (two open
prompts can't promise the same shilling twice), posting, allocations, the
device lock and reconciliation. The simulated M-PESA statement includes
terminal payments, so they reconcile like any other.

## Who can do what

| Action | Rule |
|---|---|
| Use a terminal | Must be signed in as the **agent assigned to it**. The terminal, the agent and the merchant must all be **ACTIVE**. A suspended or faulty terminal stops working immediately. |
| Assign a terminal | ADMIN only, and only to an ACTIVE agent of the **same merchant**. |
| See the fleet | ADMIN and PAYMENTS_OPERATOR (staff console → Terminals); a merchant sees their own terminals; an agent sees theirs. |
| Customer lookup | Returns a short name ("Achieng A."), the masked phone and what's due. It never returns the full phone number or other personal details. |

## Terminal management (TMS)

The staff console's **Terminals** section follows Tracom's Lango terminal
management: register a terminal at a merchant, assign or unassign its agent,
suspend or flag it faulty, and see for each one whether it's online (a
heartbeat in the last 5 minutes), its app version, and today's approved and
declined M-PESA collections.

## API

| Endpoint | Who |
|---|---|
| `GET /api/v1/pos/{terminal}/session` · `POST …/heartbeat` | assigned agent |
| `GET /api/v1/pos/{terminal}/customers?query=` | assigned agent |
| `POST /api/v1/pos/{terminal}/collections` (`Idempotency-Key`) | assigned agent |
| `GET /api/v1/pos/{terminal}/collections/{ref}` · `…/receipt` | assigned agent |
| `POST /api/v1/pos/{terminal}/collections/{ref}/customer-response` | assigned agent, **prototype only** |
| `GET /api/v1/pos/{terminal}/day-summary` | assigned agent |
| `GET/POST /api/v1/admin/terminals`, `…/{id}/assign`, `…/{id}/unassign`, `PATCH …/{id}/status` | ADMIN (reads also PAYMENTS_OPERATOR) |
| `GET /api/v1/agents/me/terminals`, `GET /api/v1/merchants/me/terminals` | AGENT, MERCHANT |

## Screens

- **`pos.html`, the terminal.** A keypad-driven terminal app: sign in →
  menu (collect, reprint, end of day) → find customer → amount → M-PESA number
  → confirm → waiting → approved or not paid, plus a printed slip. A side
  panel plays the **customer's phone** showing the M-PESA prompt with a PIN
  box.
- **Partner portal.** An agent sees "My terminal" (online, today's
  collections, Open terminal); a merchant sees all their terminals and
  today's M-PESA total.
- **Staff console → Terminals**, plus the payment lists show "M-PESA via
  agent terminal TRM-0001" or "Customer app".
- **Customer Activity** shows "M-PESA · via agent Amina Y. · TRM-0001".

## Prototype limits

- The customer's answer comes from the simulated phone panel
  (`app.payments.simulated-customer-phone=true`). With Safaricom Daraja, the
  STK push request and its callback URL replace it, and that endpoint is
  switched off.
- Prompts don't time out on their own. In production a missing callback is
  settled by the M-PESA transaction-status query, and reconciliation flags
  anything left PENDING.
- Agent commissions and merchant settlement come next (Phase 8).

## Tests

`AgentTerminalIntegrationTest`:

- A collection stays PENDING with the balance untouched, posts only when the
  customer approves, and its receipt carries the M-PESA reference and unlock
  code.
- A declined prompt applies nothing and releases the reserved amount.
- Retrying with the same idempotency key doesn't send a second prompt.
- Only the assigned agent can use an ACTIVE terminal, and agents can't be
  given another merchant's terminal.

The browser run covers the whole agent journey, including Brian being refused
on Amina's terminal.
