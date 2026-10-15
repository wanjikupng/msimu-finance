# Phase 5.5 — Operations hardening

An audit of the running application (every screen, every role, plus an
end-to-end browser run: apply → review → approve → pay → reconcile) found
gaps between what the backend could do and what actually worked. This phase
closes them before any new feature is added.

## 1. End-of-day arrears sweep

**Problem.** A contract's status (ACTIVE / OVERDUE) was only recomputed when
money moved — a posting or a reversal. A customer who simply stopped paying
generated no event, so their contract stayed ACTIVE forever. Portfolio at risk
always read 0%.

**Change.** `ArrearsService` re-evaluates every live contract against today's
date with the same rule the posting engine uses
(`PaymentPostingService.refreshLoanState`). It runs nightly
(`app.arrears.sweep-cron`, default 00:05) and on demand:
`POST /api/v1/admin/operations/arrears-sweep` or **Run end-of-day** on the
staff overview.

The device follows its contract: `FINANCED → OVERDUE` when the customer falls
behind, `OVERDUE → FINANCED` once they catch up, through two dedicated
`DeviceService` transitions. The OVERDUE device state is what a future
device-lock integration acts on.

The sweep never changes balances, allocations or due dates.

## 2. Installments follow the plan term

**Problem.** A plan called "Classic 90 Days" used a fixed KES 70/day for every
device. On a KES 14,000 balance that is 200 days, not 90. Separately, approval
copied the *live* plan's daily amount rather than the terms quoted to the
customer, so editing a plan changed applications already in the queue.

**Change.**

- Installment = financed amount ÷ plan duration, rounded **up** to the
  shilling (`FinancingCalculationService`). The final installment is the
  smaller remainder, so the contract ends within the term.
- The quoted installment is stored on the application at submission
  (`financing_applications.installment_amount`) and approval uses that
  snapshot.
- Seed plans: Flex 200 / Standard 120 / Fast 90 Days.

## 3. Demo portfolio built by the real engine

`DemoPortfolioBootstrap` seeds staff users, eight customers and about 40 days
of history. It doesn't insert rows directly: every step goes through the
services, with `SystemTime.runAt(...)` pinning "now" to the historical day.
The result is the engine's own output — contracts, allocations, receipts,
device states, an arrears sweep, a provider statement and a reconciliation
run.

`SystemTime` is now the single source of the current date/time for services
and entity callbacks. Its override is thread-confined, so request threads
always see the real clock.

It also fixed a seed inconsistency: device `TRC-000131` was FINANCED to the
demo customer with no contract behind it. It now starts AVAILABLE and is
financed through the workflow.

## 4. Simulated provider statement

Reconciliation previously needed hand-typed JSON, so every demo run reported
every payment as MISSING_PROVIDER. `ProviderStatementSimulator` produces the
statement the mock M-PESA adapter would send for the transactions it handled.
It can optionally inject an amount difference and an unknown paybill payment
so reconciliation has real exceptions to show. It's exposed as
`POST /admin/reconciliation/feed/simulate` and as **Fetch M-PESA statement
(simulated)** on the reconciliation screen. Import is idempotent.

## 5. Frontend fixes

| Bug | Effect | Fix |
|---|---|---|
| Portal shell grid placed top bar, rail and page in the wrong cells | Every portal page was squashed into a narrow column on desktop; staff had no navigation at 860–960px | Explicit grid placement, one breakpoint |
| `dueLabel` not imported; `due` used outside its block | Customer home crashed for anyone with a contract | Import + scope fix |
| Row and its button both bound to "view" | Two stacked drawers; the visible one's buttons did nothing (Review/Approve looked broken) | `stopPropagation` |
| Funnel checked `Array.isArray` on an object | "Could not load summary" always | Object check |
| Reconciliation "All non-matched" showed matched rows | Exceptions buried in 60 rows | Filter client-side |
| Overview fetched endpoints the role can't read | 403s in the console; KES 0 collections for loan officers | Role-aware fetches, "—" where not visible |
| Payment streak counted from today only | "1 day" for a customer who paid all week but not yet today | Count from yesterday if today is unpaid |

The landing page now describes the Tracom connection accurately: an Academy
project modelled on Tracom's lines of business, not "running on" their
infrastructure. The USSD and agent claims also match what the prototype does.

## Tests

91 passing, including the new `ArrearsSweepIntegrationTest`,
`FinancingCalculationServiceTest` and `FinancingTermsSnapshotIntegrationTest`.
