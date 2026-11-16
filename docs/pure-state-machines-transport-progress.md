# Transport state-machine work

This records phase-7 decisions and validation. Tests use the repository-local
`flake.nix`; production effects and security ordering remain in their existing
interpreters. Setup-ownership sharing and the shared deadline are tracked by
their own work units.

## Upgrade input

`HttpUpgradeInput` now names its three atomic states `Available`, `Claimed` and
`Sealed`. The same compare-and-set claim and get-and-set seal preserve its public
boundary. This is the plan's explicit enum-only change, not a new machine.

Three new characterization tests passed before the change: a claim succeeds only
once, only the first seal reports that claim, an unused seal prevents a later
claim, and ordinary stream operations do not consume transport ownership.

Validation:

- `sbt 'http/testOnly *HttpUpgradeInputSpec'` on the old code: pass, 3 tests.
- `sbt 'http/testOnly *HttpUpgradeInputSpec *HttpServerPublicUpgradeSpec'` after
  the enum change: pass, 10 tests, including real public HTTP/WebSocket upgrades.

## Publisher: confirmed defect fixed separately

The Pekko and Akka publisher bodies are equivalent apart from their namespaces
and unused imports. Both admitted a second single-mode subscriber reentrantly
from the first subscriber's `onSubscribe`, because their claim happened after
application code returned. Their five-case characterization suites each passed
four existing-behavior cases and failed the new reentrant-claim case before the
fix. A single atomic claim now precedes the callback, also serializing concurrent
subscription attempts. Fanout and the demand/terminal loop are unchanged.

Validation after this isolated fix: `sbt 'pekko/test' 'akka/test'` passed 46 and
38 tests respectively, including the subscriber laws below. Publisher lifecycle extraction remains a separate audit
decision; this commit does not treat that work as complete.

## Subscriber: retain the existing explicit state

The candidates are
`interop/pekko/src/main/scala/korolev/pekko/util/KorolevStreamSubscriber.scala`
and its Akka counterpart. Their production files differ only in package name.
Both already keep subscription, waiting callbacks, terminal result and cancellation
ownership in one immutable `State`, published under the same reentrant monitor.

| Input | Both candidates' behavior | Characterization |
| --- | --- | --- |
| Pull before subscribe | Queue the waiter; request its demand when subscription arrives | Deferred-demand case |
| Pull after subscribe | Publish waiter before requesting under the monitor | Synchronous-response case |
| Repeated identical subscription | No request or cancellation is repeated | New same-instance case |
| Different second subscription | Cancel the incoming subscription; preserve the active owner | Duplicate-subscription case |
| Next value | Give one queued waiter the value; ignore values after terminal state | Delivery and cancel-race cases |
| Complete or fail | Keep the first terminal result; drain waiters once | Completion and retained-error cases |
| Cancel before subscribe | Complete pending pulls; cancel late subscription without demand | Early-cancellation cases |
| Complete before subscribe | Cancel late subscription without replaying demand | New late-subscription case |
| Cancel after subscribe | Cancel once, preserving any terminal error | Attached-cancellation case |
| Throwing upstream cancel | Preserve the failure and still settle waiting pulls | New throwing-cancel case |

Disposition: retain under plan rule 7. The lifecycle is already one explicit
immutable value; the twelve tests in each adapter exercise wrong-move and race
boundaries; review found no confirmed defect in this subscriber protocol. A
shared extraction would split the existing subscription/callback ownership table
without resolving an unsettled rule. The monitor is intentional: upstream
`request` may synchronously call `onNext`, and demand must not escape after cancel.
No effects, monitors or subscriber production lines were changed.

Validation: `pekko/testOnly *SpoonbillStreamSubscriberSpec` and its Akka counterpart
each passed all 12 tests on unchanged subscriber code. Both complete adapter
suites then passed, 46 Pekko and 38 Akka tests, after the isolated publisher fix.

Other phase-7 items remain under audit; no unreviewed item is marked retained or
complete here.
