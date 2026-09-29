# Kotlin Payment Workflow Reference

An executable Kotlin reference for a payment workflow where an authorization response can be uncertain.

It is a **public, recreated demonstration** based on general payment-system principles. It is not company code and does not model a specific payment provider.

## Why this exists

An API timeout is not proof that a payment failed. The gateway may have accepted the request while its response never reached our service. Retrying blindly can double-charge a customer; failing immediately can incorrectly discard a successful payment.

The implementation protects four invariants:

1. **One idempotency key represents one full intent.** A retry returns the original record; the same key with different payment fields is rejected.
2. **Unknown is a real state.** `PROCESSING` is neither a success nor a failure.
3. **Terminal states do not regress.** A late worker or webhook cannot alter a confirmed or failed payment.
4. **Reconciliation is repeat-safe.** It resolves a stable external reference rather than issuing another authorization.

```mermaid
sequenceDiagram
    participant Client
    participant API as Payment API
    participant Store as Payment Store
    participant Gateway

    Client->>API: initiate(idempotencyKey)
    API->>Store: persist one PROCESSING intent
    API->>Gateway: authorize(externalReference)
    Gateway--xAPI: response is not received
    API-->>Client: PROCESSING + paymentId

    API->>Gateway: query(externalReference)
    Gateway-->>API: SUCCESS or FAILED
    API->>Store: transition to CONFIRMED or FAILED
```

## Run it

No package manager or framework is required. The Kotlin compiler (`kotlinc`) is the only prerequisite.

```bash
bash run-demo.sh
```

The script compiles the source with the JDK and runs five checks:

- repeated idempotency keys do not create multiple payments;
- a reused key with changed request data is rejected;
- a lost authorization response stays `PROCESSING` and becomes reconciliation-required;
- a provider that has not settled yet remains pending until a later query resolves it;
- a terminal payment does not regress or trigger another query.

## Design choices

| Decision | Reason | Boundary |
| --- | --- | --- |
| In-memory repository | Keeps the example runnable without infrastructure | A production implementation needs a durable database and a unique constraint on the idempotency key. |
| `PROCESSING` + reconciliation state | A timeout is neither success nor failure | A scheduler, webhook, or retry worker must eventually reconcile stale records. |
| Gateway query by external reference | Resolves an uncertain response without a second authorization | Only valid when a provider exposes query semantics keyed by a stable reference. |
| Terminal state guard | Delayed retries cannot overwrite `CONFIRMED` or `FAILED` | Production services should use optimistic locking or a conditional database update. |
| Idempotency request fingerprint | Prevents one key from representing two different payment intents | A production API should hash every relevant canonical request field. |

## Project layout

```text
PaymentReconciliationDemo.kt   # runnable state machine, service, and checks
```

## What a production version still needs

- Persist payment and idempotency records with a unique constraint, canonical request fingerprint, and optimistic version.
- Use an outbox/worker for guaranteed reconciliation delivery where appropriate.
- Authenticate and sign webhooks before they can transition payment state.
- Add provider-specific retry policy, audit trails, metrics, and alerting.
