# Runtime architecture decisions

| When changing… | Read the decision |
| --- | --- |
| A lifecycle, a shared concurrency primitive, or extension delivery | [0001 — Pure decisions and runtime ownership](0001-pure-runtime-decisions.md) |
| Hub, partitioning, batching, cancellation, or Future instance selection | [0002 — Stream routing and effect instances](0002-stream-routing-and-effect-instances.md) |
| Session teardown, rendering, browser authority, or persistence | [0003 — Session and security ownership](0003-session-and-security-ownership.md) |
| A hot path, a performance budget, or its benchmark | [0004 — Runtime performance validation](0004-runtime-performance-validation.md) |
| Transport setup, timeouts, or incremental decoders | [0005 — Transport deadlines and framing](0005-transport-deadlines-and-framing.md) |

For application code migrating from `FutureEffect`, see the
[Future instance guide](../effect-future-instances.md).
