# Static resource containment and budgets

The built-in `/static` route applies the same limits before authentication in
both guarded and unguarded services. Configure `SpoonbillServiceConfig.staticResourceLimits`
with `StaticResourceLimits`; defaults per service instance are:

| Limit | Default |
| --- | --- |
| Successful cached resources | 64 |
| Cached payload bytes | 16 MiB |
| Payload bytes per resource | 4 MiB |
| Concurrent classpath lookups and reads | 4 |
| Canonical path characters | 2,048 |

The hit cache evicts least recently used entries until both cache budgets fit.
Misses, invalid paths, failed reads, and oversized resources are never cached.
A zero entry or byte budget disables caching of nonempty resources. Resources
larger than the cache budget but within the resource budget are served uncached.
Concurrent duplicate misses may each use a read slot; there is no lookup waiter
queue. Saturated reads and oversized resources return 503. Existing cache hits
remain available during saturation. Missing or rejected paths return 404.

The service counts bytes actually read, probes at most one byte beyond the
resource limit, and sets Content-Length from that count. It never uses
`InputStream.available()` as a size. Chunks are at most 8 KiB, and partial reads
are combined before retaining a chunk, keeping per-resource chunk counts bounded.
Payload storage during reads is bounded by
`maxConcurrentReads * (maxResourceBytes + min(8192, maxResourceBytes + 1))`,
in addition to the cache payload budget. Keys, headers, chunks, and map entries
have bounded object overhead rather than a measured exact heap size. Responses
already handed to a transport may continue referencing evicted payloads; connection
quotas, transport backpressure and response timeouts remain necessary to bound
that separate lifetime. These are application-level bounds, not a measured
whole-process heap limit.

Read admission, local I/O, stream closure, and reservation release run in an
uncancelable region. Cancellation is deferred until the admitted read finishes;
there are no abandoned asynchronous cache-fill promises or waiting callers.
I/O failures release capacity and propagate to the normal server error handling.
File and JAR classpaths are supported; network and custom URL protocols are
rejected. A stalled local filesystem read can occupy one bounded slot until the
filesystem completes it. Static files must be deployment-owned and immutable
while being served; containment validation does not defend against concurrent
privileged filesystem mutation between validation and opening.

Only ASCII unreserved characters (letters, digits, `-`, `_`, `.`, `~`) are
accepted in path segments, and `.` and `..` segments are rejected. Percent escapes,
backslashes, controls, embedded separators, and other ambiguous characters are
rejected before classpath lookup. Query strings do not participate in identity;
repeated/trailing separators already collapsed by `PathAndQuery` use the same
cache key. Directory resources must resolve to their exact real location below
the public `static` directory: symlink escapes and internal symlink aliases are
rejected. JAR entries must have exactly the validated public path. Application
classpath files outside `static` cannot be requested through this service.
This narrows compatibility for escaped, Unicode, or symlinked asset names: rename
these assets to unreserved names or serve them through an explicitly controlled
application route. Assets above 4 MiB require an explicit higher resource limit.
Client JavaScript keeps its no-cache headers; other assets keep existing MIME
and cache-header behavior.

Regression commands (from the repository's Nix environment):

```
nix develop --no-write-lock-file --command sbt 'spoonbill/testOnly spoonbill.server.internal.services.FilesServiceSpec spoonbill.server.internal.services.FilesServiceBoundsSpec' 'ce3/testOnly spoonbill.server.internal.services.FilesServiceCancellationIOSpec'
```

The bounded stress fixture sends 2,000 distinct misses through guarded and
unguarded routing and 1,000 query/separator aliases; it checks cache entries,
payload bytes, and read reservations directly. Other tests cover tiny cache
budgets, byte boundaries, misleading available sizes, injected I/O failure,
saturation/recovery, real Cats Effect cancellation, directory/JAR containment,
and symlink rejection. Proxy-specific path rewriting and deployment filesystem
behavior still require verification in the chosen deployment.
