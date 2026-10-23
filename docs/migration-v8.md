# Migrating to Spoonbill 8: bounded request and browser lifecycles

This guide targets **8.0.0**, prepared on 2026-10-23. It covers the changes from
7.0.0; it is not confirmation that release artifacts have been published.

Update every Spoonbill module to `8.0.0` together and recompile all consumers.
Public configuration case classes and method signatures have changed, including
`SpoonbillServiceConfig`, `BrowserConfiguration`, `BrowserPersistenceLimits`,
the Pekko/Akka server configurations, and HTTP client/server entry points.
Defaulted additions do not preserve binary compatibility. Review positional
construction, extractor patterns, custom wrappers, and provider integrations.
When upgrading from before 7.0.0, also apply the
[explicit state-update migration](migration-v7.md).

## Browser authentication and PostgreSQL cutover

Existing PostgreSQL installations require the forward
[V6 browser retention migration](../modules/security-jdbc/src/main/resources/spoonbill/migrations/postgresql/V6__browser_retention.sql)
after V4 and V5. Drain and stop **every older serving or writing binary** before
starting an 8.0.0 provider against the same scope. There is no supported mixed
pre-V6/retention-aware writer period. Apply the migration with the application's
migration role; request handling does not initialize the schema.

Enrollment establishes durable issuance state and fencing floors and can shorten
legacy browser-session expiry to `enrollment time + maxSessionSeconds` (24 hours
by default). Plan for possible reauthentication. Retention-aware shared nodes
may overlap after the cutover, subject to the existing client protocol and
snapshot-format compatibility contract.

Preserve the issuance prefix/counter, encrypted issuance key and its vault keys,
binding/view floors, and durable clock across restart and restore. After
reclamation starts, rollback is limited to binaries that honor V6 fences and the
same host transaction contract. Leaving V6 tables in place does not make a
7.x writer safe. Do not reset metadata, restore older authority high-water marks,
or drop tables as a rollback shortcut. Follow the complete
[migration, restart, and rollback procedure](request-admission-lifecycle.md#postgresql-v6-migration-restart-and-rollback)
and [deployment operations](browser-auth-v4-deployment.md).

Each application must schedule **`browser.maintenance()`** on every live
coordinator; construction starts no timer. Calling only `provider.maintenance()`
does not renew/detach local guards or reclaim local presentation bookkeeping.
Use one owned, nonoverlapping timer, with 30 seconds as a starting point for the
default windows. Keep its interval below the shortest configured owner lease and
recovery window, monitor failures, and stop it before closing the coordinator.
An expired owner must obtain fresh admission rather than revive its old guard.
Review the new retention and capacity defaults, shared work-table permissions,
and alert integration in the
[maintenance and observability contract](request-admission-lifecycle.md#required-application-maintenance-and-observability).

The built-in providers now derive a unique browser credential using a
provider-owned issuance key. **`HostSessionPrepared.credential` is preparation
material, not the cookie delivered to the browser.** Custom host issuers may
continue supplying suitably random preparation material, but tests, clients, and
integrations must use the actual provider-delivered cookie. Preparation that
exceeds `BrowserRetentionPolicy.maxSessionSeconds` is rejected without committing
the host mutation. Review custom providers separately: the SPI's default
`inspectPrincipal` delegates to `capture`; nonallocating observation, retention,
and capacity reporting require their own implementations. See the
[admission and issuance contract](request-admission-lifecycle.md).

## Static resources, device IDs, and cookies

Audit classpath assets before deployment. Static path segments now accept only
ASCII letters, digits, `-`, `_`, `.`, and `~`, excluding `.` and `..` segments.
Escaped/Unicode names and symlink aliases are rejected. Rename such assets or
serve them through a controlled application route. `staticResourceLimits` defaults
to 64 cached resources, 16 MiB cached payload, 4 MiB per resource, four concurrent
reads, and 2,048 path characters. Oversized resources or read saturation return
503; invalid/missing paths return 404. Configure a higher finite resource cap
when required. See the [static-resource contract](request-static-resources.md).

Custom device-ID generators must emit 1–128 ASCII letters, digits, or `._~-`.
Invalid incoming IDs no longer select their former presentation state; invalid
generator output fails before state initialization. Cookie helpers encode names
and values symmetrically, decode percent escapes once, and treat duplicate decoded
names as unavailable across all Cookie fields. Migrate assumptions that depend on
first/last-cookie selection or raw delimiter insertion. See
[cookie and header safety](request-header-safety.md).

## Transport limits and the ZIO runtime

All four server adapters default to a **16 KiB inbound WebSocket message/frame
cap**. Size custom commands explicitly and configure `wsMaxMessageBytes` if
needed; this is distinct from the outbound client's 16 MiB WebSocket response
cap. Review the finite queues, absolute assembly deadlines, standalone connection
and cleanup budgets, and deployment connection limits in the
[transport contract](request-transport-limits.md).

The ZIO HTTP adapter requires `spoonbill-zio-http-runtime`, derived from pinned
ZIO HTTP **3.11.6** with ZIO and ZIO Streams **2.1.27**. Use the runtime supplied
by the adapter dependency. Exclude the original `dev.zio:zio-http` artifact if
another dependency introduces it; duplicate or unpatched handlers are rejected
at startup. Do not rely on classpath order. The ZIO server rejects fragmented
frames and WebSocket bytes sent before HTTP 101. Check custom clients against
these policies and retain the runtime artifact/classpath verification described
in the [derived runtime contract](request-transport-limits.md#derived-zio-http-runtime).

## Standalone HTTP and outbound clients

Standalone defaults now bound request headers to 16 KiB with a 30-second absolute
deadline, including keepalive idle time. Request bodies have a separate 30-second
absolute framing deadline; chunk lines and trailers default to 1,024 bytes and
16 KiB. Ambiguous framing is rejected, and unread or canceled bodies prevent
connection reuse. Review slow uploads and proxy behavior before cutover.

`HttpClient.create` defaults to 16 KiB response headers, a 30-second response-header
deadline, a separate 30-second TCP/TLS setup deadline, 1,024 total connections
including pending cleanup, and two cleanup workers. Pool wait and DNS resolution
are outside these deadlines; response-body consumption is outside the header
deadline. Consume or cancel bodies and ensure finalizers terminate.

HEAD, 204, and 304 responses expose empty bodies while retaining representation
metadata. Informational responses do not reset the header deadline; 101 exposes
upgraded bytes and is never pooled as HTTP. Response EOF no longer waits for an
unfinished upload, but reuse still requires the upload to succeed. Review custom
client code that coupled these events. The complete settings and framing rules
are in the [HTTP safety contract](request-header-safety.md).

Before serving traffic, verify schema cutover, maintenance scheduling, actual
cookie delivery, static paths, message sizes, client deadlines, and the runtime
classpath in the deployment environment. Refresh browser pages onto the deployed
client bundle. The [verification record](request-handling-availability-verification.md)
and [PR review resolutions](pr-17-review-resolution.md) document implementation
evidence; they do not replace deployment-specific checks.
