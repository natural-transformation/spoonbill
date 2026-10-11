# v3 client responsibility inventory

Reference revision: `ab95a4fe65399814931c6ddd7f29dd68f33bbc5c`.
This is Phase 0 evidence for plan §4.5. No client responsibility has been removed
and no bundle/performance reduction is claimed.

| Source / responsibility | Classification | Candidate action and preserved behavior |
| --- | --- | --- |
| Scala `AuthenticationCompletionService` and provider: completion eligibility, exact origin, binding, cookies, activation | Scala-owned already | Retain server authority; there is no client account-policy implementation to migrate. |
| `connection.js`: same-origin POST, three bounded delivery attempts, abort timeout, reconnect on a fresh physical socket | Necessarily browser-local | Preserve. Moving fetch, cancellation or reconnect to a server acknowledgment cannot replace the browser effect. Credentials remain HttpOnly. |
| `connection.js`: stale physical socket checks, ordered compressed sends, pending-send capacity, guarded transport selection and route observations | Necessarily browser-local | Preserve local fencing and bounds, including after asynchronous decompression. Server checks remain separate. |
| `bridge.js`: patch ordering, view/revision fencing, callback suppression, readiness, navigation/departure barriers | Necessarily browser-local | Preserve; synchronous DOM/custom-element callbacks can run before any server round trip. |
| `sensitive.js`: closed shadow-root display, immediate departure/disconnect/expiry clearing, DOM-patch cleanup and payload validation | Necessarily browser-local | Preserve all local defenses. Repeated server validation is a distinct boundary rather than removable duplication. |
| `spoonbill.js`: typed form capture, no credential-event replay, DOM/history effects and sensitive-region event suppression | Necessarily browser-local | Preserve. Application login/factor policies belong to Scala typed actions. |
| `bridge.js` numeric procedure cases and Scala `Frontend.Procedure` codes | Duplicated protocol definitions | Candidate deletion: replace handwritten numeric labels with tiny generated Scala-owned constants, only if compiled/compressed output and runtime costs do not grow. No general protocol generator is justified. |
| `spoonbill.js` `CallbackType` and Scala `Frontend.CallbackType` | Duplicated protocol definitions | Candidate deletion: generate the small callback table from one Scala-owned definition under the same gates. The browser still emits callbacks. |
| `spoonbill.js` DOM operation cases, `sensitive.js` DOM operand widths and Scala `Frontend.ModifyDomProcedure` | Duplicated protocol shape | Candidate deletion: one tiny generated code/width table. `beforePatch` must still inspect affected regions locally before mutation. |
| `launcher.js`, `utils.js`: startup and URI encoding | Necessarily browser-local | Inventory and preserve; no audited removable authentication policy. |
| Reference source under `memory/`, `jdbc/` and `app/` | Scala-owned | Require no application authentication JavaScript or `evalJs`. Both applications exercise the same real browser flow; complete measured workload equivalence is still required. |

The duplicated definitions are concrete removal candidates, not certified safe
reductions. Their replacement is gated on parity tests and emitted-size,
exchange-count, latency and memory evidence. All other audited components retain
their minimum browser responsibilities. The existing generic `evalJs` feature is
outside this authentication change; reference authentication must not use it.

After compiling the v3 client through Nix, capture all six handwritten source
modules and the actual emitted bundle separately:

```sh
nix develop --no-write-lock-file --command node \
  misc/browser-auth-baseline/client-inventory.mjs \
  modules/spoonbill/target/scala-3.3.7/resource_managed/main/static/spoonbill-client.min.js \
  /tmp/spoonbill-v3-client-inventory.json
```

The report includes SHA-256, all nonblank physical source lines (comments
included), raw emitted bytes, deterministic gzip level 9 and Brotli quality 11
bytes, application scripts and embedded-script review candidates. Generated
output always counts. The entire compiled framework client is reported because
Closure optimization does not provide a reliable authentication-only byte
attribution. Source-line accounting is descriptive, not a reduction gate based
on reformatting. Browser startup/parse/compile/execution, memory and exchanges
need runtime collection; compressed bytes do not establish those measurements.
