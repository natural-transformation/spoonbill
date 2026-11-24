# Runtime architecture decisions

| When changing… | Read the decision |
| --- | --- |
| A lifecycle, a shared concurrency primitive, or extension delivery | [0001 — Pure decisions and runtime ownership](0001-pure-runtime-decisions.md) |
| Hub, partitioning, batching, cancellation, or Future instance selection | [0002 — Stream routing and effect instances](0002-stream-routing-and-effect-instances.md) |
| Session teardown, rendering, browser authority, or persistence | [0003 — Session and security ownership](0003-session-and-security-ownership.md) |
| A hot path, a performance budget, or its benchmark | [0004 — Runtime performance validation](0004-runtime-performance-validation.md) |
| Transport setup, timeouts, or incremental decoders | [0005 — Transport deadlines and framing](0005-transport-deadlines-and-framing.md) |
| A legacy attach, a missing session state, or when a page is told to reload | [0006 — A legacy attach needs its stored state](0006-legacy-attach-needs-stored-state.md) |
| History events of an unguarded view, their confirmation, or the URL that a render sends | [0007 — An unguarded view confirms a navigation once its route is published](0007-navigation-confirmation.md) |

For application code migrating from `FutureEffect`, see the
[Future instance guide](../effect-future-instances.md).
