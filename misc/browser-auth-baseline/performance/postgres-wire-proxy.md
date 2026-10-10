# PostgreSQL synchronization-exchange instrumentation

`postgres-wire-proxy.mjs` is a disposable, owned loopback proxy for Phase 0
instrumentation. It counts actual PostgreSQL v3 frontend `Q` (simple query) and
`S` (Sync) messages paired with backend `Z` (ReadyForQuery) replies. Startup's
ReadyForQuery is counted separately. These are **protocol synchronization
exchanges**, not JDBC API executions, SQL statement counts, TCP packets, physical
network transmissions, or database lock acquisitions. A simple-query message
can contain several statements; one extended Parse/Bind/Describe/Execute/Sync
cycle can produce several server messages while contributing one exchange.

The default `mode: "strict-sequential"` permits one outstanding startup/query/sync
exchange. Starting another cycle before its ReadyForQuery fails closed, as before.
The explicitly opted-in `mode: "pipelined-cycles"` observes bounded FIFO cycles;
it does not interpret them as round trips. Flush, COPY, SSL/GSS negotiation, cancellation
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
header/queue storage counts and diagnostics. They expose no mutable maps or arrays.
The streaming parser copies only eight frontend header bytes and five backend
header bytes per live connection. It skips SQL, parameters, authentication data
and result payloads without copying or retaining them. Payloads are forwarded
through ordinary socket backpressure; the proxy emits no logs and exposes no raw
wire capture. The `retainedPayloadBytes: 0` invariant concerns parser retention,
not Node/native socket buffers or whole-process heap usage.

`headerStorageBytes` reports the thirteen allocated header-buffer bytes per live
connection. `allocatedParserBytes` is retained as a legacy alias for that quantity
only; neither field measures all parser memory or JavaScript heap. The pending
queue is a fixed `Uint8Array` with anonymous startup/sync tokens.
`cycleStorageBytes` reports its byte capacity, `pendingCycleSlots` its live pending
occupancy, and `peakPendingCycleSlots` its observed high-water mark. The proxy also
reports the maximum per-connection high-water mark as
`peakConnectionPendingCycleSlots`. JavaScript objects/counters and socket buffers
are additional memory, not implicitly included in these byte counts.

## Opt-in bounded pipeline observation

```js
const proxy = await createPostgresWireProxy({
  targetPort: disposablePostgresPort,
  mode: 'pipelined-cycles',
  maxPendingCycles: 32,
});
```

The queue bound is explicit (1–1024 cycles per connection; the opt-in default is
32). Strict mode requires a bound of one. A completed frontend Query or Sync
enqueues one anonymous token; each ReadyForQuery removes exactly one FIFO token.
Backend ErrorResponse does not itself settle a cycle. No SQL, parameter, result
or error payload enters the queue. Startup remains exclusive: query work cannot
overlap authentication, and frontend password messages require pending startup.
Overflow, missing Sync/Ready, truncation and the other unsupported protocol cases
still make all accepted cycle metrics null and close the owned socket pair.

`completedProtocolSyncCycles` is the accepted completed-cycle count.
`overlapObserved` is latched for the connection and aggregate, including after a
connection closes. Any overlapping frontend work permanently makes the legacy
`syncExchanges` metric null for that observation. `physicalRoundTrips` is always
null: this parser does not observe physical network transmissions. A pipelined
snapshot can have status `complete` for protocol observation with both round-trip
metrics null; this is not performance acceptance. Raw
`completedSyncExchanges` remains a legacy partial-event counter and must not be
substituted for a missing accepted metric.

Queue admission is checked while parsing headers; a partial next message can
latch overlap before its completed boundary occupies a slot. Peak occupancy
therefore measures queued, fully framed requests, not every partially received
command. Storage never grows with payload size or completed connection history.

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

The tests start plain v3 trust-authenticated connections to the disposable
engine through the proxy, execute synthetic `SELECT 1`, and prove one startup
exchange, one query exchange and zero active proxy connections after shutdown.
An additional real-engine test sends a coalesced `BEGIN` Query followed by an
extended Parse/Bind/Describe/Execute/Sync, checks the successful result row and two
ordered Ready messages, and verifies the overlap latch survives final shutdown.
These validate actual engine framing, not browser-authentication workloads, TLS,
physical round trips or performance acceptance. The separate
[JDBC conformance probe](reference-wire-probe.md) tests the real driver's protocol
using the same explicit opt-in mode.
