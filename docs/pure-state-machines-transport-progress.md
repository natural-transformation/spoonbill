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

Validation after this isolated fix: `sbt 'pekko/test' 'akka/test'` passed both
complete adapter suites. Publisher lifecycle extraction remains a separate audit
decision; this commit does not treat that work as complete.

Other phase-7 items remain under audit; no unreviewed item is marked retained or
complete here.
