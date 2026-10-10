# PostgreSQL synchronization-exchange instrumentation

`postgres-wire-proxy.mjs` is a disposable, owned loopback proxy for Phase 0
instrumentation. It counts actual PostgreSQL v3 frontend `Q` (simple query) and
`S` (Sync) messages paired with backend `Z` (ReadyForQuery) replies. Startup's
ReadyForQuery is counted separately. These are **protocol synchronization
exchanges**, not JDBC API executions, SQL statement counts, TCP packets, physical
network transmissions, or database lock acquisitions. A simple-query message
can contain several statements; one extended Parse/Bind/Describe/Execute/Sync
cycle can produce several server messages while contributing one exchange.

Only sequential, non-pipelined v3 connections are supported. The tracker permits
one outstanding startup/query/sync exchange. Starting another cycle before its
ReadyForQuery fails closed. Flush, COPY, SSL/GSS negotiation, cancellation
connections, unsupported protocol versions, malformed/oversized messages,
truncated input, missing ReadyForQuery and socket errors produce closed diagnostic
codes. Once unsupported, the connection and aggregate exchange metrics become
`null` with status `inconclusive`; partial counts cannot be relabeled as a pass.

The proxy binds `127.0.0.1` and forwards only to an explicitly supplied port on
`127.0.0.1`. Use it only with the repository's disposable PostgreSQL wrapper. It
has no remote-host selection, TLS interception or authentication handling. Disable
SSL negotiation in the **disposable test client's** settings; do not change a
production database or connection policy. Do not use this tool on a production
connection, even through a local tunnel.

```js
import {createPostgresWireProxy} from './postgres-wire-proxy.mjs';

const proxy = await createPostgresWireProxy({targetPort: disposablePostgresPort});
try {
  // Run a supported test client against 127.0.0.1:proxy.port, with SSL disabled.
  // Keep it sequential and await each ReadyForQuery before the next cycle.
  const observation = proxy.snapshot();
  // Store only this immutable numeric/diagnostic observation.
} finally {
  const final = await proxy.close();
  // All owned sockets must be closed. Incomplete exchanges remain inconclusive.
}
```

Snapshots contain byte/message counts, requested/completed synchronization cycles,
startup/query exchange counts, pending exchanges, opened/closed/active connections,
fixed parser storage and diagnostics. They expose no mutable maps or arrays.
The streaming parser copies only eight frontend header bytes and five backend
header bytes per live connection. It skips SQL, parameters, authentication data
and result payloads without copying or retaining them. Payloads are forwarded
through ordinary socket backpressure; the proxy emits no logs and exposes no raw
wire capture. The `retainedPayloadBytes: 0` invariant concerns parser retention,
not Node/native socket buffers or whole-process heap usage.

Defaults are 16 MiB maximum protocol message size, 32 simultaneous connections,
and a 10-second socket inactivity timeout. All are explicit constructor inputs
within checked limits. Oversized/unsupported input closes the proxy-owned socket
pair; idle clients and failed backend connections are also bounded. `close()` is
idempotent, stops acceptance, destroys owned sockets and waits for their close
events. Closed connection trackers are retired into numeric aggregate totals.

Use this collector in the separate operations pass. Its parsing and forwarding
overhead does not belong in latency acceptance unless both variants explicitly
include identical instrumentation in the frozen measurement protocol. This
instrument is one prerequisite, not the full auth workload collector: mapping
JDBC driver operations to observed Sync cycles and freezing each reference cell's
budgets remain outstanding. The manifest is still awaiting reference review.

Unit and owned fake-peer checks:

```sh
nix develop --no-write-lock-file --command node --test \
  misc/browser-auth-baseline/performance/postgres-wire-proxy.test.mjs
```

The separate physical-engine test fails if its disposable configuration is
missing; it never silently skips. Do not include it in a no-PostgreSQL test glob.

```sh
nix develop --no-write-lock-file --command bash scripts/with-test-postgres.sh \
  node --test misc/browser-auth-baseline/performance/postgres-wire-proxy.pg-tests.mjs
```

That test starts a plain v3 trust-authenticated connection to the disposable
engine through the proxy, executes synthetic `SELECT 1`, and proves one startup
exchange, one query exchange and zero active proxy connections after shutdown.
It validates actual engine framing. It does **not** establish JDBC driver,
browser-authentication workload, TLS, pipelining or performance acceptance.
