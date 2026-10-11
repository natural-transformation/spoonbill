# Local consumer feedback for 5.0.0-SNAPSHOT

Validated locally on 2026-10-11. Consumer correctness checks pass. Full latency,
allocation and retained-memory acceptance remains open.

This records independent consumption of the locally published artifacts using
the [v5 migration/testing guide](migration-testing-5.0.0-snapshot.md). The earlier
[4.1 feedback](snapshot-4.1.0-consumer-feedback.md) remains a historical record.
No consumer names, deployment details, credentials or real data are included.

## Results

| Check | Result |
| --- | --- |
| Local artifact provenance | All 24 published module JARs have revision `5.0.0-SNAPSHOT`; hashes matched before and after validation. |
| Consumer compilation | Main and test compilation passed after updating the explicit ownership mode. |
| Consumer focused checks | 56 tests passed, none failed or canceled. |
| Chromium separate-process fixture | All 13 scenarios passed. |
| WebKit separate-process fixture | All 13 scenarios passed, sequentially after Chromium. |
| Documented deterministic output-admission comparison | Passed: provider transactions 5 → 1; independent clock connections 15 → 3; stale-owner refusal preserved. Timing mode was explicitly disabled. |

The core JAR SHA-256 matches the guide:
`7d524c7af54359fa716cddae8a719e8a500e41556459443f8e8fd178d76d02f9`.
The consumer browser launcher rejects mixed/older Spoonbill artifacts and uses
resolved local `5.0.0-SNAPSHOT` JAR paths. Checks ran through the relevant Nix
environments with isolated disposable PostgreSQL; no production system was used.

## Changes verified since 4.1

- **Explicit ownership:** the consumer uses `BrowserOwnership.SharedViews` with
  one provider/coordinator per process and reserved clock connection capacity.
  It requires neither a singleton nor application-owned remoting infrastructure.
- **Schema refusal diagnostics:** the consumer removed its temporary projection
  exception-observer wrapper. The framework reporter now observes incompatible
  restoration. The browser test verifies an unchanged digest over persisted
  snapshot revision, schema format and payload after refusal, then restores the
  same view on a compatible process without reloading the document.
- **Protected HTTP:** a real framework-rendered protected route accepts current
  authority and returns 403 after revocation during takeover.
- **Capacity recovery:** the excess view is refused; closing a current view
  frees one active slot while retained history remains, and a new view can enter.
- **Administrative retirement:** consumer callbacks use real host session and
  administrator-policy validation. Tests show that denial rolls back the audit
  write, successful exact-owner retirement preserves snapshots/session/ceremony
  history, and a stale observed target cannot retire a peer's successor. An
  authorized stale attempt can record its audit without reporting success.

The retained browser coverage also passes split HTML/socket routing, unknown
view refusal, original password/factor ceremony recovery, lost delivery replies,
acknowledged presentation handoff, paused and crashed owners, graceful shutdown,
lost business responses without automatic replay, storage failure, and logout.

The two processes use the same framework snapshot with mutually readable
presentation formats 1 and 2. This is not a claim that exclusive 4.0 writers may
overlap shared v5 writers or that arbitrary mixed framework versions are safe.

## Release assessment

The earlier *minor-version compatibility* objection is addressed by selecting
an explicitly breaking major version and requiring consumer recompilation.
Successful recompilation/integration is the applicable check; unrecompiled 4.0
binaries are not advertised as compatible.

No new framework correctness defect was reproduced in these consumer checks.
The initial additional consumer test tried to read a transport token's private
field; it was corrected to obtain the host-issued credential through the host's
public delivery API. No framework visibility or security checks were relaxed.

The admission comparison proves a reduction of work for the measured protocol,
using the current provider for both sequences. It is not a comparison against
an immutable historical binary, an end-to-end latency gate, or a memory test.
Do not promote it to a no-regression claim. The remaining performance bar needs
equivalent protected-event, handoff and churn measurements with the historical
pins preserved.

Administrative retirement releases active ownership; it does not compact all
retained records or add an automatic abandoned-owner reaper. Operational capacity
and retirement choices remain explicit, as documented in the guide.

The feature passes this consumer's functional acceptance. A release decision
requiring non-regressing latency and memory still needs that measured evidence.
