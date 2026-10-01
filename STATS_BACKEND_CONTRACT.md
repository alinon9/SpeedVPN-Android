# Stats backend contract

The Android client sends usage batches with `stats_batch_id`. Delivery is intentionally **at-least-once**. A process death can occur after the server accepts a batch but before the client records its final local acknowledgement.

The backend must therefore enforce idempotency using a unique key equivalent to:

```text
(device_id, stats_batch_id)
```

For a duplicate batch ID, the backend must return success without applying the byte counters a second time.

The Android client treats a pending batch as immutable. Traffic observed after that batch is frozen is stored separately and is sent later under a new `stats_batch_id`; therefore the same id must never be intentionally reused with different byte totals.

The Android client does not claim exactly-once delivery by itself; exactly-once accounting is a client + backend property.
